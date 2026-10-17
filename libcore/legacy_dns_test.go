package libcore

import (
	"context"
	"encoding/json"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

func TestLegacyDNSURLsRetainPathBootstrapAndPreferences(t *testing.T) {
	const raw = `{"dns":{"servers":[{"tag":"bootstrap","address":"local"},{"tag":"remote","address":"https://dns.example.test:8443/a%2Fb?profile=fixture","address_resolver":"bootstrap","address_strategy":"ipv4_only","strategy":"prefer_ipv6","detour":"proxy","client_subnet":"192.0.2.0/24"}],"rules":[{"domain":["example.test"],"server":"remote"}],"final":"remote"}}`
	root := normalizedConfig(t, raw)
	dns := root["dns"].(map[string]any)
	server := dns["servers"].([]any)[1].(map[string]any)
	if server["type"] != "https" || server["server"] != "dns.example.test" || server["server_port"] != float64(8443) || server["path"] != "/a%2Fb?profile=fixture" || server["detour"] != "proxy" {
		t.Fatal("lost DNS URL or detour")
	}
	resolver := server["domain_resolver"].(map[string]any)
	if resolver["server"] != "bootstrap" || resolver["strategy"] != "ipv4_only" {
		t.Fatal("lost bootstrap resolver preference")
	}
	for _, item := range dns["rules"].([]any) {
		rule := item.(map[string]any)
		if rule["server"] == "remote" && (rule["strategy"] != "prefer_ipv6" || rule["client_subnet"] != "192.0.2.0/24") {
			t.Fatal("lost per-server DNS options")
		}
	}
	encoded, _ := json.Marshal(root)
	if strings.Contains(string(encoded), `"address"`) {
		t.Fatal("left a legacy DNS address")
	}
	aliases := normalizedConfig(t, `{"dns":{"servers":[{"address":"localhost"},{"address":"udp://127.0.0.1:5353"},{"address":"https://resolver.example.test/query?key=fixture"}]}}`)
	items := aliases["dns"].(map[string]any)["servers"].([]any)
	if items[0].(map[string]any)["type"] != "local" || items[1].(map[string]any)["type"] != "udp" || items[1].(map[string]any)["server_port"] != float64(5353) {
		t.Fatal("changed explicit loopback or bare system DNS alias")
	}
	if len(items) != 4 || items[2].(map[string]any)["domain_resolver"] == nil {
		t.Fatal("legacy hostname DNS needs independent bootstrap")
	}
}

func TestLegacyRcodeResolverNeverUsesNetworkFallback(t *testing.T) {
	for _, code := range []struct {
		name  string
		value int
	}{{"refused", mDNS.RcodeRefused}, {"success", mDNS.RcodeSuccess}, {"name_error", mDNS.RcodeNameError}} {
		t.Run(code.name, func(t *testing.T) {
			var networkCalls atomic.Int32
			local := &testLocalDNS{lookup: func(*ExchangeContext, string) { networkCalls.Add(1) }}
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(local), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
			var options option.Options
			if err := decodeConfigOptions(ctx, []byte(`{"dns":{"servers":[{"address":"rcode://`+code.name+`","tag":"local-only"}],"final":"local-only"},"outbounds":[{"type":"direct","tag":"direct"}]}`), &options); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err := instance.Start(); err != nil {
				t.Fatal(err)
			}
			router := service.FromContext[adapter.DNSRouter](ctx)
			qctx, cancel := context.WithTimeout(ctx, time.Second)
			defer cancel()
			response, err := router.Exchange(qctx, new(mDNS.Msg).SetQuestion("test.example.", mDNS.TypeA), adapter.DNSQueryOptions{})
			if err != nil || response.Rcode != code.value {
				t.Fatalf("rcode changed: %v %v", response, err)
			}
			if networkCalls.Load() != 0 {
				t.Fatal("rcode resolver leaked to system DNS")
			}
		})
	}
}

func TestLegacyDNSMigrationRejectsInvalidOptions(t *testing.T) {
	for _, raw := range []string{
		`{"dns":{"servers":[{"address":"local","tag":7}]}}`,
		`{"dns":{"servers":[{"address":"local","detour":true}]}}`,
		`{"dns":{"servers":[{"address":"local","address_resolver":3}]}}`,
		`{"dns":{"servers":[{"address":"local","strategy":"invalid"}]}}`,
		`{"dns":{"servers":[{"address":"local","address_strategy":"invalid"}]}}`,
		`{"dns":{"servers":[{"address":"local","address_fallback_delay":"oops"}]}}`,
		`{"dns":{"servers":[{"address":"local","client_subnet":"invalid"}]}}`,
		`{"dns":{"servers":[{"address":"https://dns.test:99999/query"}]}}`,
		`{"dns":{"servers":[{"address":"local","unknown":true}]}}`,
		`{"dns":{"fakeip":{"enabled":true,"unknown":true}}}`,
		`{"dns":{"fakeip":{"enabled":"true"}}}`,
		`{"dns":{"fakeip":{"enabled":true,"inet4_range":"fd00::/8"}}}`,
	} {
		if _, err := normalizeConfigJSON(context.Background(), []byte(raw)); err == nil {
			t.Errorf("accepted invalid options: %s", raw)
		}
	}
}

func TestLegacyFakeIPRangesPreserveTypedOverrides(t *testing.T) {
	root := normalizedConfig(t, `{"dns":{"fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"},"servers":[{"type":"fakeip","tag":"typed","inet4_range":"198.19.0.0/16"},{"address":"fakeip","tag":"old"}]}}`)
	dns := root["dns"].(map[string]any)
	if _, exists := dns["fakeip"]; exists {
		t.Fatal("left legacy global options")
	}
	servers := dns["servers"].([]any)
	typed := servers[0].(map[string]any)
	old := servers[1].(map[string]any)
	if typed["inet4_range"] != "198.19.0.0/16" || typed["inet6_range"] != "fc00::/18" || old["inet4_range"] != "198.18.0.0/15" {
		t.Fatal("lost FakeIP ranges or explicit override")
	}
}
