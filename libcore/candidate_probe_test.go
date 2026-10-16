package libcore

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type candidateFixture struct {
	outbound.Adapter
	dnsPort    uint16
	failDNS    atomic.Bool
	dnsCalls   atomic.Int32
	httpsCalls atomic.Int32
}

func (o *candidateFixture) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if destination.Port == o.dnsPort {
		o.dnsCalls.Add(1)
		if o.failDNS.Load() {
			return nil, fmt.Errorf("fixture DNS path unavailable")
		}
	} else {
		o.httpsCalls.Add(1)
	}
	address := destination.String()
	if destination.IsDomain() {
		address = fmt.Sprintf("127.0.0.1:%d", destination.Port)
	}
	return (&net.Dialer{}).DialContext(ctx, network, address)
}
func (*candidateFixture) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("unused")
}

type candidateFixtureOptions struct {
	FailDNS bool `json:"fail_dns"`
}

func TestConfiguredProbeUsesCandidateDoHAndRejectsFalseHealthyNode(t *testing.T) {
	var rcode atomic.Int32
	var blockDNS atomic.Bool
	startedDNS := make(chan struct{}, 1)
	var httpStatus atomic.Int32
	httpStatus.Store(204)
	doh := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/private-token" || r.Header.Get("X-Probe-Secret") != "secret-value" {
			t.Error("configured DNS path or header lost")
			w.WriteHeader(403)
			return
		}
		payload, _ := io.ReadAll(r.Body)
		if blockDNS.Load() {
			select {
			case startedDNS <- struct{}{}:
			default:
			}
			<-r.Context().Done()
			return
		}
		request := new(mDNS.Msg)
		if request.Unpack(payload) != nil {
			t.Error("invalid DNS query")
			return
		}
		reply := new(mDNS.Msg).SetReply(request)
		reply.Rcode = int(rcode.Load())
		if reply.Rcode == mDNS.RcodeSuccess && request.Question[0].Qtype == mDNS.TypeA {
			reply.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.ParseIP("127.0.0.1")}}
		}
		content, _ := reply.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(content)
	}))
	defer doh.Close()
	endpoint := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "HEAD" {
			t.Error("probe downloaded a page")
		}
		w.WriteHeader(int(httpStatus.Load()))
	}))
	defer endpoint.Close()
	dnsPort := uint16(doh.Listener.Addr().(*net.TCPAddr).Port)
	instances := make(map[string]*candidateFixture)
	registry := nekoboxAndroidOutboundRegistry()
	outbound.Register[candidateFixtureOptions](registry, "candidate-fixture", func(ctx context.Context, _ adapter.Router, _ log.ContextLogger, tag string, opts candidateFixtureOptions) (adapter.Outbound, error) {
		o := &candidateFixture{Adapter: outbound.NewAdapter("candidate-fixture", tag, []string{"tcp", "udp"}, nil), dnsPort: dnsPort}
		o.failDNS.Store(opts.FailDNS)
		instances[tag] = o
		return o, nil
	})
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), registry, nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, &dnsTestPlatform{})
	httpsURL := fmt.Sprintf("https://example.com:%d/check", endpoint.Listener.Addr().(*net.TCPAddr).Port)
	config := map[string]any{
		"log":         map[string]any{"disabled": true},
		"certificate": map[string]any{"certificate": []string{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: endpoint.Certificate().Raw}))}},
		"dns":         map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote", "server": "127.0.0.1", "server_port": dnsPort, "path": "/private-token", "headers": map[string]string{"X-Probe-Secret": "secret-value"}, "tls": map[string]any{"enabled": true, "server_name": "example.com"}, "detour": "auto"}}, "final": "dns-remote"},
		"outbounds":   []any{map[string]any{"type": "candidate-fixture", "tag": "bad", "fail_dns": true}, map[string]any{"type": "candidate-fixture", "tag": "good"}, map[string]any{"type": "urltest", "tag": "auto", "outbounds": []string{"bad", "good"}, "health_check": true, "failure_cooldown": true, "health_url": httpsURL, "wait_for_initial": true, "interval": "1h", "idle_timeout": "1h"}},
		"route":       map[string]any{"final": "auto"},
	}
	content, _ := json.Marshal(config)
	var options option.Options
	if err := decodeConfigOptions(ctx, content, &options); err != nil {
		t.Fatal(err)
	}
	checker := newCandidateChecker(options)
	service.MustRegister[group.CandidateHealthChecker](ctx, checker)
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	auto, _ := instance.Outbound().Outbound("auto")
	g := auto.(*group.URLTest)
	if g.Now() != "good" {
		t.Fatalf("selected node with broken DoH: %s", g.Now())
	}
	if instances["good"].dnsCalls.Load() == 0 || instances["good"].httpsCalls.Load() == 0 || instances["bad"].dnsCalls.Load() == 0 {
		t.Fatal("DNS and HTTPS did not use the actual candidates")
	}
	if _, err = urltest.URLTest(ctx, httpsURL, instances["bad"]); err != nil {
		t.Fatal("fixture must pass ordinary URLTest despite its broken DNS", err)
	}
	_, report, err := checker.CheckCandidate(ctx, "good", "dns-remote", httpsURL)
	if err != nil {
		t.Fatalf("configured DNS probe failed: %v: %v", err, report)
	}
	dns := report["dns"].(map[string]any)
	if dns["a"].(map[string]any)["addresses"] != 1 || dns["aaaa"].(map[string]any)["addresses"] != 0 {
		t.Fatal("A/AAAA results lost")
	}
	encoded, _ := json.Marshal(report)
	for _, secret := range []string{"private-token", "secret-value", "127.0.0.1"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatal("probe report leaked DNS configuration or answers")
		}
	}
	if g.Now() != "good" || options.DNS.Servers[0].Options.(*option.RemoteHTTPSDNSServerOptions).Detour != "auto" {
		t.Fatal("manual probe changed the running configuration")
	}
	httpStatus.Store(451)
	_, report, err = checker.CheckCandidate(ctx, "good", "dns-remote", httpsURL)
	if err == nil || report["https"].(map[string]any)["error"] != "http-status" {
		t.Fatal("accepted HTTPS block page")
	}
	httpStatus.Store(204)
	rcode.Store(mDNS.RcodeNameError)
	_, report, err = checker.CheckCandidate(ctx, "good", "dns-remote", httpsURL)
	if err == nil || report["dns"].(map[string]any)["state"] != "failed" {
		t.Fatal("accepted DNS NXDOMAIN")
	}
	stopped, stop := context.WithCancel(ctx)
	stop()
	if _, _, err = checker.CheckCandidate(stopped, "good", "dns-remote", httpsURL); err == nil {
		t.Fatal("ignored cancellation")
	}
	if _, _, err = checker.CheckCandidate(ctx, "auto", "dns-remote", httpsURL); err == nil {
		t.Fatal("allowed recursive group probe")
	}
	rcode.Store(0)
	blockDNS.Store(true)
	b := &BoxInstance{Box: instance, ctx: ctx, cancel: cancel, pauseManager: service.FromContext[pause.Manager](ctx), candidateChecker: checker, state: 1}
	b.pauseManager.NetworkWake() // The fixture has a local network, not an Android interface monitor.
	done := make(chan error, 1)
	go func() { _, err := b.ConfiguredProbe("good", "dns-remote", httpsURL); done <- err }()
	select {
	case <-startedDNS:
	case err := <-done:
		t.Fatalf("manual configured probe exited before DNS: %v", err)
	case <-time.After(time.Second):
		t.Fatal("manual configured DNS probe did not start")
	}
	b.SetProbesPaused(true)
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled comparison reported success")
		}
	case <-time.After(time.Second):
		t.Fatal("screen lock did not cancel configured DNS probe")
	}
	if b.state != 1 || b.pauseManager.IsDevicePaused() {
		t.Fatal("comparison pause stopped VPN traffic")
	}
	if _, err = b.ConfiguredProbe("good", "dns-remote", httpsURL); err == nil {
		t.Fatal("comparison started while locked")
	}

}
