package libcore

import (
	"context"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
)

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
