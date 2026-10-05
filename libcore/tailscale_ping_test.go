package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

func TestTailscalePingPaths(t *testing.T) {
	for _, test := range []struct {
		name   string
		result *adapter.TailscalePingResult
		path   string
		failed bool
	}{
		{"direct", &adapter.TailscalePingResult{LatencyMs: 2, IsDirect: true}, "direct", false},
		{"endpoint", &adapter.TailscalePingResult{LatencyMs: 2, Endpoint: "192.0.2.1:123"}, "direct", false},
		{"derp", &adapter.TailscalePingResult{LatencyMs: 5, DERPRegionID: 1}, "derp", false},
		{"peer relay precedence", &adapter.TailscalePingResult{LatencyMs: 3, PeerRelay: "relay", IsDirect: true, DERPRegionID: 1}, "peer-relay", false},
		{"unknown", &adapter.TailscalePingResult{LatencyMs: 1}, "unknown", false},
		{"region code alone", &adapter.TailscalePingResult{DERPRegionCode: "test"}, "unknown", false},
		{"error", &adapter.TailscalePingResult{Error: "offline", IsDirect: true, DERPRegionID: 1}, "unknown", true},
		{"nil", nil, "unknown", true},
		{"nan", &adapter.TailscalePingResult{LatencyMs: math.NaN()}, "unknown", true},
		{"negative", &adapter.TailscalePingResult{LatencyMs: -1}, "unknown", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			sample := projectTailscalePing("peer", "100.64.0.2", 1, test.result)
			if sample.Path != test.path || (sample.LatencyMs == nil) != test.failed || (sample.Error != "") != test.failed {
				t.Fatal("incorrect ping classification")
			}
			if _, err := json.Marshal(sample); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestTailscalePingStableIdentityResolution(t *testing.T) {
	status := statusWithPeers(&adapter.TailscalePeer{StableID: "peer", TailscaleIPs: []string{"192.0.2.1", "fd7a:115c:a1e0::2", "100.64.0.2"}})
	if ip, err := resolveTailscalePingPeer(status, "peer"); err != nil || ip != "100.64.0.2" {
		t.Fatal("IPv4 preference")
	}
	for _, invalid := range []string{"", "missing", "100.64.0.2", "192.0.2.1"} {
		if _, err := resolveTailscalePingPeer(status, invalid); err == nil {
			t.Fatal("non-inventory stable identity accepted")
		}
	}
	status.UserGroups[0].Peers[0].TailscaleIPs = []string{"2001:db8::1", "fd7a:115c:a1e0:0::2"}
	if ip, err := resolveTailscalePingPeer(status, "peer"); err != nil || ip != "fd7a:115c:a1e0::2" {
		t.Fatal("IPv6-only peer")
	}
	status.UserGroups[0].Peers[0].TailscaleIPs = []string{"192.0.2.1", "fd7a:115c:a1e0::2%zone"}
	if _, err := resolveTailscalePingPeer(status, "peer"); err == nil {
		t.Fatal("arbitrary or zoned address accepted")
	}
}

type tailscalePingFixture struct {
	tailscaleStatusFixture
	results     []*adapter.TailscalePingResult
	address     string
	pingCleaned bool
	earlyReturn bool
	terminal    error
}

func (f *tailscalePingFixture) StartTailscalePing(ctx context.Context, address string, callback func(*adapter.TailscalePingResult)) error {
	f.address = address
	defer func() { f.pingCleaned = true }()
	for _, result := range f.results {
		callback(result)
	}
	if f.earlyReturn {
		return f.terminal
	}
	<-ctx.Done()
	return ctx.Err()
}

func TestTailscalePingFiveSamplesAndCleanup(t *testing.T) {
	fixture := &tailscalePingFixture{
		tailscaleStatusFixture: tailscaleStatusFixture{status: statusWithPeers(&adapter.TailscalePeer{StableID: "peer", TailscaleIPs: []string{"100.64.0.2"}})},
		results: []*adapter.TailscalePingResult{
			{LatencyMs: 50, DERPRegionID: 1}, {LatencyMs: 2, IsDirect: true}, {Error: "timeout"},
			{LatencyMs: 3, PeerRelay: "relay"}, {LatencyMs: 4}, {LatencyMs: 5},
		},
	}
	var samples []tailscalePingSample
	err := produceTailscalePing(context.Background(), fixture, "peer", time.Second, func(raw string) {
		var sample tailscalePingSample
		if err := json.Unmarshal([]byte(raw), &sample); err != nil {
			t.Error(err)
		}
		samples = append(samples, sample)
	})
	if err != nil || len(samples) != 5 || !fixture.cleaned || !fixture.pingCleaned || fixture.address != "100.64.0.2" {
		t.Fatalf("bounded ping/cleanup: %d samples, error %v", len(samples), err)
	}
	for n, sample := range samples {
		if sample.Sequence != n+1 || sample.PeerID != "peer" || sample.PeerIP != fixture.address {
			t.Fatal("sample identity/sequence changed")
		}
	}
	if samples[0].Path != "derp" || samples[1].Path != "direct" || samples[2].LatencyMs != nil {
		t.Fatal("outcomes lost")
	}
}

func TestTailscalePingEarlyEndIsNotCompletion(t *testing.T) {
	for _, terminal := range []error{nil, io.EOF} {
		fixture := &tailscalePingFixture{
			tailscaleStatusFixture: tailscaleStatusFixture{status: statusWithPeers(&adapter.TailscalePeer{StableID: "peer", TailscaleIPs: []string{"100.64.0.2"}})},
			earlyReturn:            true, terminal: terminal,
		}
		s := newTailscaleStream(context.Background(), 5, false)
		s.run(func() {}, func(ctx context.Context) error {
			return produceTailscalePing(ctx, fixture, "peer", time.Second, s.publish)
		})
		awaitTailscaleDone(t, s.done)
		if _, err := s.Next(1); err == nil || err.Error() == "EOF" {
			t.Fatal("early producer termination appeared as natural completion")
		}
		if !fixture.pingCleaned {
			t.Fatal("early termination did not join cleanup")
		}
		s.Close()
	}
}

func TestTailscalePingDeadlineAndInvalidPeer(t *testing.T) {
	fixture := &tailscalePingFixture{tailscaleStatusFixture: tailscaleStatusFixture{status: statusWithPeers(&adapter.TailscalePeer{StableID: "peer", TailscaleIPs: []string{"100.64.0.2"}})}}
	err := produceTailscalePing(context.Background(), fixture, "peer", 10*time.Millisecond, func(string) { t.Error("unexpected sample") })
	if !errors.Is(err, context.DeadlineExceeded) || !fixture.pingCleaned {
		t.Fatal("deadline did not stop and join ping")
	}
	fixture.address = ""
	if err := produceTailscalePing(context.Background(), fixture, "unknown", time.Second, func(string) {}); err == nil || fixture.address != "" {
		t.Fatal("invalid identity reached ping")
	}
	for _, timeout := range []int32{0, -1, 10001} {
		if _, err := StartTailscalePeerPing(nil, "ts", "peer", timeout); err == nil {
			t.Fatal("invalid total timeout accepted")
		}
	}
}
