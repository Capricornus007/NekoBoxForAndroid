package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"io"
	"net"
	"testing"
	"time"
)

type trafficOutbound struct {
	outbound.Adapter
	conn   net.Conn
	packet net.PacketConn
}

func (o *trafficOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return o.conn, nil
}
func (o *trafficOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return o.packet, nil
}

type trafficOutboundManager struct {
	adapter.OutboundManager
	items map[string]adapter.Outbound
}

func (m *trafficOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	o, ok := m.items[tag]
	return o, ok
}
func trafficCounter(t *testing.T, s *boxapi.SbV2rayServer, prefix, direction string, expected int64) {
	t.Helper()
	if got := s.QueryStats(prefix + ">>>traffic>>>" + direction); got != expected {
		t.Fatalf("%s/%s=%d, want %d", prefix, direction, got, expected)
	}
}
func TestSelectorTracksActualChildAndExistingConnections(t *testing.T) {
	ctx := service.ContextWithDefaultRegistry(context.Background())
	server := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"proxy", "first", "second"}})
	service.MustRegister[adapter.OutboundStatsTracker](ctx, server.StatsService().(adapter.OutboundStatsTracker))
	a, ar := net.Pipe()
	defer a.Close()
	defer ar.Close()
	b, br := net.Pipe()
	defer b.Close()
	defer br.Close()
	first := &trafficOutbound{Adapter: outbound.NewAdapter("test", "first", []string{N.NetworkTCP}, nil), conn: a}
	second := &trafficOutbound{Adapter: outbound.NewAdapter("test", "second", []string{N.NetworkTCP}, nil), conn: b}
	service.MustRegister[adapter.OutboundManager](ctx, &trafficOutboundManager{items: map[string]adapter.Outbound{"first": first, "second": second}})
	o, err := group.NewSelector(ctx, nil, nil, "proxy", option.SelectorOutboundOptions{Outbounds: []string{"first", "second"}})
	if err != nil {
		t.Fatal(err)
	}
	selector := o.(*group.Selector)
	if err = selector.Start(); err != nil {
		t.Fatal(err)
	}
	metadata := adapter.InboundContext{Inbound: "tun-in"}
	dialCtx := interrupt.ContextWithIsExternalConnection(adapter.WithContext(ctx, &metadata))
	old, err := selector.DialContext(dialCtx, "tcp", M.Socksaddr{})
	if err != nil {
		t.Fatal(err)
	}
	if !selector.SelectOutbound("second") {
		t.Fatal("selection failed")
	}
	current, err := selector.DialContext(dialCtx, "tcp", M.Socksaddr{})
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		local, remote net.Conn
		value         string
	}{{old, ar, "old"}, {current, br, "newer"}} {
		_ = c.local.SetDeadline(time.Now().Add(time.Second))
		done := make(chan error, 1)
		go func() { _, err := io.CopyN(io.Discard, c.remote, int64(len(c.value))); done <- err }()
		if _, err = io.WriteString(c.local, c.value); err != nil {
			t.Fatal(err)
		}
		if err = <-done; err != nil {
			t.Fatal(err)
		}
	}
	trafficCounter(t, server, "outbound>>>first", "uplink", 3)
	trafficCounter(t, server, "outbound>>>second", "uplink", 5)
	trafficCounter(t, server, "outbound>>>first", "uplink", 0)
	trafficCounter(t, server, "session>>>proxy", "uplink", 0)
}
func TestIngressSessionCountsOnceAndExcludesPluginTransport(t *testing.T) {
	s := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"group", "leaf"}})
	stats := s.StatsService()
	local, remote := net.Pipe()
	defer local.Close()
	defer remote.Close()
	_ = local.SetDeadline(time.Now().Add(time.Second))
	groupOutbound := &trafficOutbound{Adapter: outbound.NewAdapter("test", "group", nil, nil)}
	c := stats.RoutedConnection(context.Background(), local, adapter.InboundContext{Inbound: "tun-in"}, nil, groupOutbound)
	c = stats.(adapter.OutboundStatsTracker).TrackOutboundConnection("leaf", c, true)
	go func() { _, _ = remote.Write([]byte("hello")) }()
	if _, err := io.ReadFull(c, make([]byte, 5)); err != nil {
		t.Fatal(err)
	}
	trafficCounter(t, s, "session>>>proxy", "uplink", 5)
	trafficCounter(t, s, "outbound>>>group", "uplink", 5)
	trafficCounter(t, s, "outbound>>>leaf", "uplink", 5)
	c = stats.RoutedConnection(context.Background(), local, adapter.InboundContext{Inbound: "c-1-mapping-9"}, nil, groupOutbound)
	go func() { _, _ = remote.Write([]byte("tls")) }()
	if _, err := io.ReadFull(c, make([]byte, 3)); err != nil {
		t.Fatal(err)
	}
	trafficCounter(t, s, "session>>>proxy", "uplink", 0)
}
func TestUDPGroupTracksSelectedCandidateAndDirection(t *testing.T) {
	ctx := service.ContextWithDefaultRegistry(context.Background())
	s := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"leaf"}})
	service.MustRegister[adapter.OutboundStatsTracker](ctx, s.StatsService().(adapter.OutboundStatsTracker))
	local, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer local.Close()
	remote, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()
	leaf := &trafficOutbound{Adapter: outbound.NewAdapter("test", "leaf", []string{N.NetworkTCP, N.NetworkUDP}, nil), packet: local}
	service.MustRegister[adapter.OutboundManager](ctx, &trafficOutboundManager{items: map[string]adapter.Outbound{"leaf": leaf}})
	o, err := group.NewURLTest(ctx, nil, nil, "group", option.URLTestOutboundOptions{Outbounds: []string{"leaf"}, ManagedByParent: true})
	if err != nil {
		t.Fatal(err)
	}
	u := o.(*group.URLTest)
	if err = u.Start(); err != nil {
		t.Fatal(err)
	}
	meta := adapter.InboundContext{Inbound: "tun-in"}
	c, err := u.ListenPacket(adapter.WithContext(ctx, &meta), M.Socksaddr{})
	if err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(time.Second))
	if _, err = c.WriteTo([]byte("up"), remote.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	if _, err = remote.WriteTo([]byte("down"), local.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	if _, _, err = c.ReadFrom(make([]byte, 32)); err != nil {
		t.Fatal(err)
	}
	trafficCounter(t, s, "outbound>>>leaf", "uplink", 2)
	trafficCounter(t, s, "outbound>>>leaf", "downlink", 4)
}
