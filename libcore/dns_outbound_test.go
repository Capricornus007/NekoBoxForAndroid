package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func TestDomainOutboundWithOverriddenDNS(t *testing.T) {
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodConnect {
			t.Errorf("expected CONNECT, got %s", request.Method)
		}
		conn, buffer, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		_, _ = buffer.WriteString("HTTP/1.1 200 Connection established\r\n\r\nok")
		_ = buffer.Flush()
	}))
	defer proxy.Close()
	proxyURL, _ := url.Parse(proxy.URL)
	port, _ := strconv.Atoi(proxyURL.Port())
	for _, test := range []struct {
		name, dnsTag string
		resolver     any
		wantError    bool
	}{
		{"generated DNS", "dns-direct", map[string]any{"server": "dns-direct", "strategy": "ipv4_only"}, false},
		{"dev.3 regression", "user-dns", map[string]any{"server": "dns-direct", "strategy": "ipv4_only"}, true},
		{"custom DNS", "user-dns", nil, false},
		{"explicit custom resolver", "user-dns", map[string]any{"server": "user-dns", "strategy": "ipv4_only"}, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			outbounds := make([]any, 0, 102)
			for i := 0; i < 101; i++ {
				outbounds = append(outbounds, map[string]any{"type": "http", "tag": fmt.Sprintf("ip-%d", i), "server": "127.0.0.1", "server_port": port})
			}
			profile := map[string]any{"type": "http", "tag": "fqdn", "server": "proxy.example.test", "server_port": port, "domain_strategy": "ipv4_only"}
			if test.resolver != nil {
				profile["domain_resolver"] = test.resolver
			}
			outbounds = append(outbounds, profile)
			configuration := map[string]any{
				"dns":       map[string]any{"servers": []any{map[string]any{"type": "local", "tag": test.dnsTag}}, "final": test.dnsTag},
				"outbounds": outbounds, "route": map[string]any{"final": "fqdn"},
			}
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(&testLocalDNS{lookup: func(exchange *ExchangeContext, network string) { exchange.Success("127.0.0.1") }}), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
			content, _ := json.Marshal(configuration)
			var options option.Options
			if err := decodeConfigOptions(ctx, content, &options); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if test.wantError {
				if err == nil {
					instance.Close()
					t.Fatal("expected invalid resolver to fail")
				}
				if !strings.Contains(err.Error(), "initialize outbound[101]: domain resolver not found: dns-direct") {
					t.Fatal(err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			outbound, _ := instance.Outbound().Outbound("fqdn")
			requestCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
			defer cancel()
			conn, err := outbound.DialContext(requestCtx, "tcp", M.ParseSocksaddr("target.example.test:443"))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
			response := make([]byte, 2)
			if _, err = io.ReadFull(conn, response); err != nil || string(response) != "ok" {
				t.Fatalf("response=%q, err=%v", response, err)
			}
		})
	}
}
