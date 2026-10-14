package libcore

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/protocol/socks/socks5"
	"github.com/sagernet/sing/service"
)

func TestTypedDNSRoutesBootstrapRemoteAndFakeIP(t *testing.T) {
	var udpCalls, httpsCalls, proxyCalls atomic.Int32
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	udpServer := &mDNS.Server{PacketConn: udp, Handler: mDNS.HandlerFunc(func(w mDNS.ResponseWriter, request *mDNS.Msg) {
		udpCalls.Add(1)
		response := new(mDNS.Msg).SetReply(request)
		if request.Question[0].Qtype == mDNS.TypeA {
			address := net.ParseIP("192.0.2.10")
			if request.Question[0].Name == "dns.example.test." || request.Question[0].Name == "proxy.example.test." {
				address = net.ParseIP("127.0.0.1")
			}
			response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: address}}
		}
		_ = w.WriteMsg(response)
	})}
	go func() { _ = udpServer.ActivateAndServe() }()
	defer udpServer.Shutdown()
	https := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpsCalls.Add(1)
		if r.URL.Path != "/dns-query" || r.URL.RawQuery != "key=example" {
			t.Errorf("DoH URL lost path/query: %s", r.URL.String())
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
			return
		}
		request := new(mDNS.Msg)
		if err = request.Unpack(body); err != nil {
			t.Error(err)
			return
		}
		response := new(mDNS.Msg).SetReply(request)
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.ParseIP("203.0.113.10")}}
		packed, _ := response.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(packed)
	}))
	defer https.Close()
	httpsPort, _ := strconv.Atoi(strings.Split(https.Listener.Addr().String(), ":")[1])
	proxy, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	go func() {
		conn, err := proxy.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		reader := bufio.NewReader(conn)
		if _, err = socks5.ReadAuthRequest(reader); err != nil {
			t.Error(err)
			return
		}
		if err = socks5.WriteAuthResponse(conn, socks5.AuthResponse{Method: socks5.AuthTypeNotRequired}); err != nil {
			t.Error(err)
			return
		}
		request, err := socks5.ReadRequest(reader)
		if err != nil {
			t.Error(err)
			return
		}
		proxyCalls.Add(1)
		if request.Command != socks5.CommandConnect || request.Destination != M.SocksaddrFromNet(https.Listener.Addr()) {
			t.Errorf("unexpected proxy destination: %s", request.Destination)
			return
		}
		upstream, err := net.DialTimeout("tcp", request.Destination.String(), time.Second)
		if err != nil {
			t.Error(err)
			return
		}
		defer upstream.Close()
		if err = socks5.WriteResponse(conn, socks5.Response{ReplyCode: socks5.ReplyCodeSuccess}); err != nil {
			t.Error(err)
			return
		}
		go func() { _, _ = io.Copy(conn, upstream) }()
		_, _ = io.Copy(upstream, reader)
	}()
	configuration := map[string]any{
		"dns": map[string]any{
			"servers": []any{
				map[string]any{"type": "local", "tag": "dns-local"},
				map[string]any{"type": "udp", "tag": "dns-direct", "server": "127.0.0.1", "server_port": udp.LocalAddr().(*net.UDPAddr).Port, "domain_resolver": "dns-local"},
				map[string]any{"type": "https", "tag": "dns-remote", "server": "dns.example.test", "server_port": httpsPort, "path": "/dns-query?key=example", "detour": "proxy", "domain_resolver": "dns-direct", "tls": map[string]any{"enabled": true, "insecure": true}},
				map[string]any{"type": "fakeip", "tag": "dns-fake", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"},
			},
			"rules": []any{
				map[string]any{"domain": []string{"blocked.test"}, "action": "predefined", "rcode": "NOERROR"},
				map[string]any{"outbound": []string{"any"}, "server": "dns-direct", "strategy": "ipv4_only"},
				map[string]any{"domain_suffix": []string{"internal.test"}, "server": "dns-direct", "strategy": "ipv4_only"},
				map[string]any{"inbound": []string{"tun-in"}, "query_type": []string{"A", "AAAA"}, "server": "dns-fake", "strategy": "ipv4_only", "disable_cache": true},
				map[string]any{"server": "dns-remote", "strategy": "ipv4_only"},
			}, "final": "dns-remote",
		},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}, map[string]any{"type": "socks", "tag": "proxy", "server": "proxy.example.test", "server_port": proxy.Addr().(*net.TCPAddr).Port, "domain_strategy": "ipv4_only"}},
		"route":     map[string]any{"final": "proxy"},
	}
	content, _ := json.Marshal(configuration)
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[platform.Interface](ctx, dnsTestPlatform{})
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, content); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	query := func(domain, inbound string) *mDNS.Msg {
		t.Helper()
		requestCtx, cancel := context.WithTimeout(adapter.WithContext(ctx, &adapter.InboundContext{Inbound: inbound}), 3*time.Second)
		defer cancel()
		response, err := service.FromContext[adapter.DNSRouter](ctx).Exchange(requestCtx, new(mDNS.Msg).SetQuestion(domain, mDNS.TypeA), adapter.DNSQueryOptions{})
		if err != nil {
			t.Fatal(err)
		}
		return response
	}
	if response := query("host.internal.test.", ""); len(response.Answer) != 1 || response.Answer[0].(*mDNS.A).A.String() != "192.0.2.10" {
		t.Fatal("direct DNS routing failed")
	}
	if httpsCalls.Load() != 0 || proxyCalls.Load() != 0 {
		t.Fatal("direct DNS leaked to remote server")
	}
	if response := query("www.example.test.", ""); len(response.Answer) != 1 || response.Answer[0].(*mDNS.A).A.String() != "203.0.113.10" {
		t.Fatal("remote HTTPS DNS failed")
	}
	if udpCalls.Load() < 2 || httpsCalls.Load() != 1 || proxyCalls.Load() != 1 {
		t.Fatal("remote DNS hostname did not bootstrap through direct DNS")
	}
	if response := query("fake.example.test.", "tun-in"); len(response.Answer) != 1 || !netip.MustParsePrefix("198.18.0.0/15").Contains(netip.MustParseAddr(response.Answer[0].(*mDNS.A).A.String())) {
		t.Fatal("typed FakeIP did not answer virtual addresses")
	}
	if response := query("blocked.test.", "tun-in"); response.Rcode != mDNS.RcodeSuccess || len(response.Answer) != 0 {
		t.Fatal("blocked DNS response changed")
	}
}

// Android supplies interface updates through Java. Mirror that contract without
// requiring Linux netlink privileges in the test environment.
type dnsTestPlatform struct{ platform.Interface }

func (dnsTestPlatform) Initialize(adapter.NetworkManager) error { return nil }
func (dnsTestPlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return dnsTestMonitor{}
}
func (dnsTestPlatform) Interfaces() ([]adapter.NetworkInterface, error) { return nil, nil }
func (dnsTestPlatform) UsePlatformAutoDetectInterfaceControl() bool     { return false }
func (dnsTestPlatform) IncludeAllNetworks() bool                        { return false }
func (dnsTestPlatform) UnderNetworkExtension() bool                     { return false }
func (dnsTestPlatform) SystemCertificates() []string                    { return nil }
func (dnsTestPlatform) ReadWIFIState() adapter.WIFIState                { return adapter.WIFIState{} }

type dnsTestMonitor struct{ tun.DefaultInterfaceMonitor }

func (dnsTestMonitor) Start() error { return nil }
func (dnsTestMonitor) Close() error { return nil }
func (dnsTestMonitor) RegisterCallback(tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}
func (dnsTestMonitor) DefaultInterface() *control.Interface { return nil }
func (dnsTestMonitor) AndroidVPNEnabled() bool              { return false }

func TestTypedFakeIPRespectsIPv6Modes(t *testing.T) {
	for _, strategy := range []string{"prefer_ipv6", "ipv6_only"} {
		t.Run(strategy, func(t *testing.T) {
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry())
			service.MustRegister[platform.Interface](ctx, dnsTestPlatform{})
			data := map[string]any{"dns": map[string]any{"servers": []any{map[string]any{"type": "local", "tag": "dns-local"}, map[string]any{"type": "fakeip", "tag": "dns-fake", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"}}, "rules": []any{map[string]any{"server": "dns-fake", "strategy": strategy}}}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}}
			content, _ := json.Marshal(data)
			var options option.Options
			if err := options.UnmarshalJSONContext(ctx, content); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			for _, qtype := range []uint16{mDNS.TypeA, mDNS.TypeAAAA} {
				response, err := service.FromContext[adapter.DNSRouter](ctx).Exchange(ctx, new(mDNS.Msg).SetQuestion("fake.test.", qtype), adapter.DNSQueryOptions{})
				if err != nil {
					t.Fatal(err)
				}
				if qtype == mDNS.TypeA && strategy == "ipv6_only" {
					if len(response.Answer) != 0 {
						t.Fatal("IPv6-only returned IPv4")
					}
					continue
				}
				if len(response.Answer) != 1 {
					t.Fatal("missing FakeIP answer")
				}
				if qtype == mDNS.TypeAAAA && !netip.MustParsePrefix("fc00::/18").Contains(netip.MustParseAddr(response.Answer[0].(*mDNS.AAAA).AAAA.String())) {
					t.Fatal("missing fake IPv6 address")
				}
			}
		})
	}
}

func TestFallbackDNSUsesSelectedPathWithoutResolverLoop(t *testing.T) {
	var controlStatus, proxyCalls, dnsCalls atomic.Int32
	controlStatus.Store(204)
	controlSite := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(int(controlStatus.Load())) }))
	defer controlSite.Close()
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	bootstrap := &mDNS.Server{PacketConn: udp, Handler: mDNS.HandlerFunc(func(w mDNS.ResponseWriter, r *mDNS.Msg) {
		response := new(mDNS.Msg).SetReply(r)
		if r.Question[0].Qtype == mDNS.TypeA {
			response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: r.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 1}, A: net.ParseIP("127.0.0.1")}}
		}
		_ = w.WriteMsg(response)
	})}
	go func() { _ = bootstrap.ActivateAndServe() }()
	defer bootstrap.Shutdown()
	controlURL := "http://control.example.test:" + strconv.Itoa(controlSite.Listener.Addr().(*net.TCPAddr).Port)
	doh := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dnsCalls.Add(1)
		body, _ := io.ReadAll(r.Body)
		request := new(mDNS.Msg)
		if err := request.Unpack(body); err != nil {
			t.Error(err)
			return
		}
		response := new(mDNS.Msg).SetReply(request)
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 1}, A: net.ParseIP("203.0.113.10")}}
		data, _ := response.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(data)
	}))
	defer doh.Close()
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "CONNECT" || r.Host != doh.Listener.Addr().String() {
			t.Error("unexpected proxy request")
			w.WriteHeader(403)
			return
		}
		proxyCalls.Add(1)
		upstream, err := net.Dial("tcp", r.Host)
		if err != nil {
			t.Error(err)
			return
		}
		defer upstream.Close()
		conn, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		_, _ = rw.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n")
		_ = rw.Flush()
		go func() { _, _ = io.Copy(conn, upstream) }()
		_, _ = io.Copy(upstream, rw)
	}))
	defer proxy.Close()
	configuration := map[string]any{
		"dns": map[string]any{"servers": []any{
			map[string]any{"type": "udp", "tag": "dns-direct", "server": "127.0.0.1", "server_port": udp.LocalAddr().(*net.UDPAddr).Port},
			map[string]any{"type": "https", "tag": "dns-direct-fallback", "server": "127.0.0.1", "server_port": doh.Listener.Addr().(*net.TCPAddr).Port, "detour": "direct-fallback", "domain_resolver": "dns-direct", "tls": map[string]any{"enabled": true, "insecure": true}},
		}, "rules": []any{map[string]any{"outbound": []string{"any"}, "server": "dns-direct"}, map[string]any{"server": "dns-direct-fallback", "strategy": "ipv4_only", "disable_cache": true}}},
		"outbounds": []any{
			map[string]any{"type": "direct", "tag": "direct"},
			map[string]any{"type": "http", "tag": "proxy", "server": "127.0.0.1", "server_port": proxy.Listener.Addr().(*net.TCPAddr).Port},
			map[string]any{"type": "urltest", "tag": "direct-fallback", "outbounds": []string{"direct", "proxy"}, "strategy": "direct-fallback", "url": controlURL, "interval": "1h", "idle_timeout": "1h", "timeout": "1s", "wait_for_initial": true, "interrupt_exist_connections": true},
		}, "route": map[string]any{"final": "proxy"},
	}
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry())
	service.MustRegister[platform.Interface](ctx, dnsTestPlatform{})
	content, _ := json.Marshal(configuration)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, content); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, _ := instance.Outbound().Outbound("direct-fallback")
	for i, status := range []int32{204, 451, 204} {
		controlStatus.Store(status)
		outbound.(interface{ CheckOutbounds() }).CheckOutbounds()
		qctx, cancel := context.WithTimeout(ctx, time.Second)
		response, err := service.FromContext[adapter.DNSRouter](ctx).Exchange(qctx, new(mDNS.Msg).SetQuestion("fallback.test.", mDNS.TypeA), adapter.DNSQueryOptions{})
		cancel()
		if err != nil || len(response.Answer) != 1 {
			t.Fatalf("DNS at HTTP%d: %v", status, err)
		}
		expected := int32(0)
		if i > 0 {
			expected = 1
		}
		if proxyCalls.Load() != expected {
			t.Fatalf("wrong DNS path at HTTP%d: proxy calls=%d", status, proxyCalls.Load())
		}
	}
	if dnsCalls.Load() != 3 {
		t.Fatal("missing DNS requests")
	}
}
