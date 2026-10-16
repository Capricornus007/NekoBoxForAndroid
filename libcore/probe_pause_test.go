package libcore

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
)

func TestScreenLockCancelsManualProbeWithoutDevicePause(t *testing.T) {
	b, err := NewSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	started := make(chan struct{})
	go func() { buffer := make([]byte, 2048); _, _, _ = server.ReadFrom(buffer); close(started) }()
	done := make(chan error, 1)
	go func() { _, err := b.NetworkProbe("direct", "example.com", server.LocalAddr().String()); done <- err }()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("manual UDP probe did not start")
	}
	b.SetProbesPaused(true)
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled probe reported success")
		}
	case <-time.After(time.Second):
		t.Fatal("manual probe survived screen lock")
	}
	if b.pauseManager.IsDevicePaused() {
		t.Fatal("screen lock paused the VPN device")
	}
	if _, err = b.NetworkProbe("direct", "example.com", server.LocalAddr().String()); err == nil {
		t.Fatal("manual probe started while locked")
	}
	b.SetProbesPaused(false)
	if b.probesPaused || b.probePowerCtx.Err() != nil {
		t.Fatal("probe context did not recover after unlock")
	}
}

func TestHealthChecksPauseWithoutStoppingTraffic(t *testing.T) {
	for _, strategy := range []string{"fastest", "priority", "direct-fallback"} {
		t.Run(strategy, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
			defer server.Close()
			g, direct, proxy, _ := newFallbackTest(t, server.URL, time.Hour, func(o *option.URLTestOutboundOptions) { o.Strategy = strategy })
			g.CheckOutbounds()
			g.SetProbesPaused(true)
			before := direct.probes.Load() + proxy.probes.Load()
			g.CheckOutbounds()
			if _, err := g.URLTest(context.Background()); err != nil {
				t.Fatal(err)
			}
			if direct.probes.Load()+proxy.probes.Load() != before {
				t.Fatal("paused group still probes")
			}
			fallbackPayload(t, g, g.Now())
			g.SetProbesPaused(false)
			g.CheckOutbounds()
			if direct.probes.Load()+proxy.probes.Load() <= before {
				t.Fatal("checks did not resume")
			}
		})
	}
}

func TestLockCancelsProbeWithoutChangingFallbackHealth(t *testing.T) {
	var block atomic.Bool
	started := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if block.Load() {
			started <- struct{}{}
			<-r.Context().Done()
			return
		}
		w.WriteHeader(204)
	}))
	defer server.Close()
	g, _, _, _ := newFallbackTest(t, server.URL, time.Hour)
	g.CheckOutbounds()
	before := g.SelectionDiagnostics()["fallback"]
	block.Store(true)
	done := make(chan struct{})
	go func() { g.CheckOutbounds(); close(done) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("probe did not start")
	}
	g.SetProbesPaused(true)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("in-flight probe was not cancelled")
	}
	if !reflect.DeepEqual(g.SelectionDiagnostics()["fallback"], before) {
		t.Fatal("cancelled round changed fallback health")
	}
}
