package libcore

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
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

type waterfallProbeOutbound struct {
	outbound.Adapter
	address string
}

func (o *waterfallProbeOutbound) DialContext(ctx context.Context, network string, _ M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, o.address)
}
func (*waterfallProbeOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("not used")
}
func TestWaterfallStartsOnFirstHealthyPriorityMember(t *testing.T) {
	release := make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	defer unblock()
	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { time.Sleep(20 * time.Millisecond); w.WriteHeader(204) }))
	defer fast.Close()
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
		time.Sleep(20 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer slow.Close()
	defer unblock()
	ctx, cancel := context.WithCancel(service.ContextWithDefaultRegistry(context.Background()))
	defer cancel()
	ctx = pause.WithDefaultManager(ctx)
	manager := &trafficOutboundManager{items: map[string]adapter.Outbound{}}
	service.MustRegister[adapter.OutboundManager](ctx, manager)
	history := urltest.NewHistoryStorage()
	history.StoreURLTestHistory("slow", &adapter.URLTestHistory{Time: time.Now().Add(-time.Minute), Delay: 1})
	service.MustRegister(ctx, history)
	for tag, address := range map[string]string{"fast": fast.Listener.Addr().String(), "slow": slow.Listener.Addr().String()} {
		manager.items[tag] = &waterfallProbeOutbound{outbound.NewAdapter("test", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil), address}
	}
	logger := log.NewNOPFactory().NewLogger("test")
	child, err := group.NewURLTest(ctx, nil, logger, "child", option.URLTestOutboundOptions{Outbounds: []string{"fast", "slow"}, URL: "http://control.test", Strategy: "fastest", ManagedByParent: true, Timeout: badoption.Duration(2 * time.Second)})
	if err != nil {
		t.Fatal(err)
	}
	manager.items["child"] = child
	parent, err := group.NewURLTest(ctx, nil, logger, "parent", option.URLTestOutboundOptions{Outbounds: []string{"child"}, URL: "http://control.test", Strategy: "priority", WaitForInitial: true, Timeout: badoption.Duration(2 * time.Second)})
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range []adapter.Outbound{child, parent} {
		if err := item.(*group.URLTest).Start(adapter.StartStateStart, adapter.NewScope(ctx, logger)); err != nil {
			t.Fatal(err)
		}
		defer item.(*group.URLTest).Close()
	}
	done := make(chan error, 1)
	go func() { done <- parent.(*group.URLTest).PostStart() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("startup waited for blocked remaining member")
	}
	if parent.(*group.URLTest).Now() != "child" || child.(*group.URLTest).Now() != "fast" {
		t.Fatal("priority or first verified candidate lost")
	}
	unblock()
}
