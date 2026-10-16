package libcore

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type fallbackTestOutbound struct {
	outbound.Adapter
	control string
	probes  atomic.Int32
	packets atomic.Int32
}

func (o *fallbackTestOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if destination.String() == o.control {
		o.probes.Add(1)
		return (&net.Dialer{}).DialContext(ctx, network, o.control)
	}
	local, remote := net.Pipe()
	go func() { defer remote.Close(); _, _ = io.WriteString(remote, o.Tag()) }()
	return local, nil
}
func (o *fallbackTestOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	o.packets.Add(1)
	return net.ListenPacket("udp", "127.0.0.1:0")
}
func newFallbackTest(t *testing.T, url string, interval time.Duration, modify ...func(*option.URLTestOutboundOptions)) (*group.URLTest, *fallbackTestOutbound, *fallbackTestOutbound, *urltest.HistoryStorage) {
	t.Helper()
	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = pause.WithDefaultManager(ctx)
	history := urltest.NewHistoryStorage()
	service.MustRegister(ctx, history)
	control := M.ParseSocksaddr(url[len("http://"):]).String()
	direct := &fallbackTestOutbound{Adapter: outbound.NewAdapter("test", "direct", []string{N.NetworkTCP, N.NetworkUDP}, nil), control: control}
	proxy := &fallbackTestOutbound{Adapter: outbound.NewAdapter("test", "proxy", []string{N.NetworkTCP, N.NetworkUDP}, nil), control: control}
	service.MustRegister[adapter.OutboundManager](ctx, &trafficOutboundManager{items: map[string]adapter.Outbound{"direct": direct, "proxy": proxy}})
	options := option.URLTestOutboundOptions{Outbounds: []string{"direct", "proxy"}, URL: url, Strategy: "direct-fallback", WaitForInitial: true, Interval: badoption.Duration(interval), IdleTimeout: badoption.Duration(interval), Timeout: badoption.Duration(500 * time.Millisecond), InterruptExistConnections: true}
	for _, change := range modify {
		change(&options)
	}
	o, err := group.NewURLTest(ctx, nil, log.NewNOPFactory().NewLogger("test"), "direct-fallback", options)
	if err != nil {
		t.Fatal(err)
	}
	g := o.(*group.URLTest)
	if err = g.Start(adapter.StartStateStart, adapter.NewScope(ctx, log.NewNOPFactory().Logger())); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = g.Close() })
	return g, direct, proxy, history
}
func fallbackPayload(t *testing.T, g *group.URLTest, want string) {
	t.Helper()
	c, err := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("payload.test:80"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(time.Second))
	data, err := io.ReadAll(c)
	if err != nil || string(data) != want {
		t.Fatalf("payload=%q err=%v want=%s", data, err, want)
	}
	p, err := g.ListenPacket(context.Background(), M.ParseSocksaddr("payload.test:53"))
	if err != nil {
		t.Fatal(err)
	}
	_ = p.Close()
}
func TestDirectFallbackSwitchesTCPUDPAndIsolatesControlHistory(t *testing.T) {
	var status atomic.Int32
	status.Store(204)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "HEAD" {
			t.Error("expected HEAD")
		}
		w.WriteHeader(int(status.Load()))
	}))
	defer server.Close()
	g, direct, proxy, history := newFallbackTest(t, server.URL, time.Hour)
	assertReason := func(want string) {
		content, err := json.Marshal(g.SelectionDiagnostics())
		if err != nil {
			t.Fatal(err)
		}
		var state struct {
			TCP struct {
				Reason    string    `json:"reason"`
				Current   string    `json:"current"`
				ChangedAt time.Time `json:"changed_at"`
			} `json:"tcp"`
		}
		if err = json.Unmarshal(content, &state); err != nil {
			t.Fatal(err)
		}
		if state.TCP.Reason != want || state.TCP.ChangedAt.IsZero() || state.TCP.Current != g.Now() {
			t.Fatalf("diagnostics=%s", content)
		}
	}
	if err := g.PostStart(); err != nil {
		t.Fatal(err)
	}
	if g.Now() != "direct" {
		t.Fatalf("initial=%s", g.Now())
	}
	assertReason("initial-selection")
	fallbackPayload(t, g, "direct")
	for _, code := range []int32{403, 451, 503} {
		status.Store(code)
		history.StoreURLTestHistory("direct", &adapter.URLTestHistory{Time: time.Now(), Delay: 1})
		g.CheckOutbounds()
		if g.Now() != "proxy" {
			t.Fatalf("HTTP%d selected %s", code, g.Now())
		}
		fallbackPayload(t, g, "proxy")
		assertReason("control-site-unreachable")
	}
	if proxy.probes.Load() != 0 {
		t.Fatal("fallback proxy was probed against control site")
	}
	status.Store(200)
	g.CheckOutbounds()
	fallbackPayload(t, g, "direct")
	assertReason("direct-recovered")
	if g.Now() != "direct" || direct.packets.Load() != 2 || proxy.packets.Load() != 3 {
		t.Fatal("TCP/UDP did not switch back")
	}
	if history.LoadURLTestHistory("direct").Delay != 1 {
		t.Fatal("fallback polluted shared URL-test history")
	}
	server.Close()
	g.CheckOutbounds()
	fallbackPayload(t, g, "proxy")
}
func TestDirectFallbackChecksWhileIdleAndClosesSafely(t *testing.T) {
	var status atomic.Int32
	status.Store(503)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(int(status.Load())) }))
	defer server.Close()
	g, direct, _, _ := newFallbackTest(t, server.URL, 20*time.Millisecond)
	_ = g.PostStart()
	if g.Now() != "proxy" {
		t.Fatal("blocked startup must use proxy")
	}
	status.Store(204)
	deadline := time.Now().Add(time.Second)
	for g.Now() != "direct" && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if g.Now() != "direct" {
		t.Fatal("idle recovery not detected")
	}
	var readers sync.WaitGroup
	for i := 0; i < 4; i++ {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for j := 0; j < 50; j++ {
				_ = g.Now()
				c, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("payload.test:80"))
				if e == nil {
					_, _ = io.ReadAll(c)
					_ = c.Close()
				}
			}
		}()
	}
	for i := 0; i < 5; i++ {
		status.Store(503)
		g.CheckOutbounds()
		status.Store(204)
		g.CheckOutbounds()
	}
	readers.Wait()
	_ = g.Close()
	_ = g.Close()
	before := direct.probes.Load()
	time.Sleep(80 * time.Millisecond)
	if after := direct.probes.Load(); after > before+1 {
		t.Fatal("checks continued after close")
	}
}
func TestDirectFallbackTimeoutAndConnectionInterruption(t *testing.T) {
	var slow atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if slow.Load() {
			select {
			case <-r.Context().Done():
				return
			case <-time.After(2 * time.Second):
			}
		}
		w.WriteHeader(204)
	}))
	defer server.Close()
	g, _, _, _ := newFallbackTest(t, server.URL, time.Hour)
	_ = g.PostStart()
	if g.Now() != "direct" {
		t.Fatal("healthy control site did not select direct before timeout test")
	}
	c, err := g.DialContext(interrupt.ContextWithIsExternalConnection(context.Background()), "tcp", M.ParseSocksaddr("payload.test:80"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	slow.Store(true)
	start := time.Now()
	g.CheckOutbounds()
	if time.Since(start) > 1500*time.Millisecond || g.Now() != "proxy" {
		t.Fatal("bounded timeout did not select proxy")
	}
	_ = c.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = c.Read(make([]byte, 1)); err == nil {
		t.Fatal("old direct connection was not interrupted")
	}
}

func TestDirectFallbackSessionTrafficFollowsChosenPath(t *testing.T) {
	var status atomic.Int32
	status.Store(204)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(int(status.Load())) }))
	defer server.Close()
	g, _, _, _ := newFallbackTest(t, server.URL, time.Hour)
	_ = g.PostStart()
	serverStats := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true})
	stats := serverStats.StatsService()
	for _, tag := range []string{"direct", "proxy", "direct"} {
		if tag == "direct" {
			status.Store(204)
		} else {
			status.Store(451)
		}
		g.CheckOutbounds()
		local, remote := net.Pipe()
		c := stats.RoutedConnection(context.Background(), local, adapter.InboundContext{Inbound: "tun-in"}, nil, g)
		go func() { _, _ = remote.Write([]byte("data")); _ = remote.Close() }()
		if _, err := io.ReadFull(c, make([]byte, 4)); err != nil {
			t.Fatal(err)
		}
		_ = c.Close()
		up := serverStats.QueryStats("session>>>" + tag + ">>>traffic>>>uplink")
		if up != 4 {
			t.Fatalf("%s traffic=%d", tag, up)
		}
	}
}

func TestDirectFallbackQuorumAndConsecutiveRounds(t *testing.T) {
	var failing atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/two" && failing.Load() {
			w.WriteHeader(503)
		} else {
			w.WriteHeader(204)
		}
	}))
	defer server.Close()
	g, _, _, _ := newFallbackTest(t, server.URL, time.Hour, func(options *option.URLTestOutboundOptions) {
		options.ControlURLs = []string{server.URL + "/one", server.URL + "/two"}
		options.RequiredSuccesses = 2
		options.FailureThreshold = 2
		options.RecoveryThreshold = 2
	})
	if err := g.PostStart(); err != nil {
		t.Fatal(err)
	}
	if g.Now() != "proxy" {
		t.Fatal("used unconfirmed direct path")
	}
	g.CheckOutbounds()
	if g.Now() != "direct" {
		t.Fatal("did not recover after two healthy rounds")
	}
	failing.Store(true)
	g.CheckOutbounds()
	if g.Now() != "direct" {
		t.Fatal("single failure caused flapping")
	}
	g.CheckOutbounds()
	if g.Now() != "proxy" {
		t.Fatal("quorum loss did not switch to proxy")
	}
	failing.Store(false)
	g.CheckOutbounds()
	if g.Now() != "proxy" {
		t.Fatal("single recovery caused flapping")
	}
	g.CheckOutbounds()
	if g.Now() != "direct" {
		t.Fatal("did not recover")
	}
}

func TestFallbackDiagnosticsReportsTimeoutAsUnreachable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer server.Close()
	g, _, _, _ := newFallbackTest(t, server.URL, time.Hour, func(o *option.URLTestOutboundOptions) { o.Timeout = badoption.Duration(30 * time.Millisecond) })
	g.CheckOutbounds()
	report := g.SelectionDiagnostics()["fallback"].(map[string]any)
	sites := report["sites"].([]map[string]any)
	if report["round_state"] != "completed" || sites[0]["state"] != "unreachable" || sites[0]["reason"] != "timeout" {
		t.Fatalf("ambiguous timeout verdict: %v", report)
	}
	if g.Now() != "proxy" {
		t.Fatal("timeout enabled direct routing")
	}
}
