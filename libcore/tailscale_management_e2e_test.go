package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/common/dialer"
	M "github.com/sagernet/sing/common/metadata"
)

// The private harness supplies config, probe URL, two exit IDs/sink labels and direct/DERP
// peer IDs through NEKOBOX_TAILSCALE_E2E_* variables; no deployment defaults are inferred.
// This gated test is separate from the credential-free unit/race suite.
func TestTailscaleManagementEndToEnd(t *testing.T) {
	path := os.Getenv("NEKOBOX_TAILSCALE_MANAGEMENT_E2E_CONFIG")
	if path == "" {
		path = os.Getenv("NEKOBOX_TAILSCALE_E2E_CONFIG")
	}
	if path == "" {
		t.Skip("Tailscale E2E config is not set")
	}
	requireInput := func(name string) string {
		t.Helper()
		value := os.Getenv(name)
		if value == "" {
			t.Fatalf("missing explicit private harness input %s", name)
		}
		return value
	}
	exitA := requireInput("NEKOBOX_TAILSCALE_E2E_EXIT_A_ID")
	exitB := requireInput("NEKOBOX_TAILSCALE_E2E_EXIT_B_ID")
	directPeer := requireInput("NEKOBOX_TAILSCALE_E2E_DIRECT_PEER_ID")
	derpPeer := requireInput("NEKOBOX_TAILSCALE_E2E_DERP_PEER_ID")
	identities := make(map[string]bool)
	for _, id := range []string{exitA, exitB, directPeer, derpPeer} {
		if identities[id] {
			t.Fatal("private harness peer identities must be distinct")
		}
		identities[id] = true
	}
	sinkA := requireInput("NEKOBOX_TAILSCALE_E2E_EXIT_A_SINK")
	sinkB := requireInput("NEKOBOX_TAILSCALE_E2E_EXIT_B_SINK")
	if sinkA == sinkB {
		t.Fatal("private harness exit sink labels must be distinct")
	}
	probeURL := requireInput("NEKOBOX_TAILSCALE_E2E_URL")
	parsedURL, parseErr := url.Parse(probeURL)
	if parseErr != nil || parsedURL.Scheme != "http" || parsedURL.User != nil || parsedURL.RawQuery != "" || parsedURL.Fragment != "" {
		t.Fatal("private probe must be a credential-free HTTP URL")
	}
	probeIP, parseErr := netip.ParseAddr(parsedURL.Hostname())
	if parseErr != nil || probeIP.Zone() != "" || (!probeIP.IsPrivate() && !netip.MustParsePrefix("198.18.0.0/15").Contains(probeIP)) {
		t.Fatal("private probe must use a private or benchmark IP literal")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("cannot read private harness config")
	}
	if err := neko_log.SetupLog(1<<20, filepath.Join(t.TempDir(), "neko.log")); err != nil {
		t.Fatal("cannot initialize test logging")
	}
	var instance *BoxInstance
	t.Cleanup(func() {
		if instance != nil {
			instance.Close()
		}
	})
	start := func(config string) {
		t.Helper()
		instance, err = NewSingBoxInstance(config, nil)
		if err != nil {
			t.Fatal("cannot construct management node")
		}
		if instance.Start() != nil || TailscaleWaitReady(instance, "ts", false, 30000) != nil {
			t.Fatal("management node did not start and become ready")
		}
	}
	probe := func(ctx context.Context) (string, bool) {
		t.Helper()
		if _, loaded := instance.Box.Outbound().Outbound("ts"); !loaded {
			t.Fatal("private probe endpoint unavailable")
		}
		// Each probe opens a fresh connection through ts, never a host/default dialer.
		detour := dialer.NewDetour(instance.Box.Outbound(), "ts", true)
		transport := &http.Transport{
			DisableKeepAlives:     true,
			ResponseHeaderTimeout: 3 * time.Second,
			DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				return detour.DialContext(ctx, network, M.ParseSocksaddr(address))
			},
		}
		defer transport.CloseIdleConnections()
		client := &http.Client{
			Transport: transport, Timeout: 3 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		}
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
		if err != nil {
			t.Fatal("cannot construct private probe request")
		}
		response, err := client.Do(request)
		if response == nil {
			if err == nil {
				t.Fatal("private probe returned neither response nor error")
			}
			return "", false
		}
		defer response.Body.Close()
		body, readErr := io.ReadAll(io.LimitReader(response.Body, 4097))
		var result struct {
			Exit string `json:"exit"`
		}
		if err != nil || readErr != nil || response.StatusCode != http.StatusOK || len(body) > 4096 || json.Unmarshal(body, &result) != nil {
			return "", true
		}
		return result.Exit, true
	}
	assertTraffic := func(stage, expected string) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		for {
			actual, reached := probe(ctx)
			if expected == "" {
				if reached {
					t.Fatalf("%s: probe remained reachable without an exit", stage)
				}
				return
			}
			if reached && actual == expected {
				return
			}
			select {
			case <-ctx.Done():
				t.Fatalf("%s: private probe did not confirm the selected exit", stage)
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	readStatus := func() tailscaleStatusJSON {
		t.Helper()
		raw, err := TailscaleStatus(instance, "ts")
		var status tailscaleStatusJSON
		if err != nil || json.Unmarshal([]byte(raw), &status) != nil {
			t.Fatal("management status unavailable")
		}
		if len(raw) > tailscaleMaxStatusBytes || len(status.Peers) > tailscaleMaxPeers || status.AuthURL != "" || status.NeedsLogin || status.NeedsApproval || status.Self == nil {
			t.Fatal("running status schema or sign-in fields invalid")
		}
		return status
	}
	waitExit := func(id string) {
		t.Helper()
		deadline := time.Now().Add(15 * time.Second)
		for {
			status := readStatus()
			if id == "" && status.CurrentExit == nil {
				return
			}
			if id != "" && status.CurrentExit != nil && status.CurrentExit.ID == id && status.CurrentExit.Live {
				return
			}
			if time.Now().After(deadline) {
				t.Fatal("exit preference/live state failed to converge")
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	apply := func(id string) *TailscaleExitNodeChange {
		t.Helper()
		change, err := SetTailscaleExitNode(instance, "ts", id)
		if err != nil {
			t.Fatal("exit change failed")
		}
		if id == "" {
			if change.SavedValue() != "" {
				t.Fatal("clear returned a nonempty saved value")
			}
		} else if ip, err := netip.ParseAddr(change.SavedValue()); err != nil || ip.String() != change.SavedValue() {
			t.Fatal("saved exit is not a canonical IP")
		}
		return change
	}
	commit := func(change *TailscaleExitNodeChange) {
		t.Helper()
		if change.Commit() != nil || change.Commit() != nil || change.Rollback() == nil {
			t.Fatal("commit finalization semantics")
		}
	}
	restart := func(saved string) {
		t.Helper()
		if instance.Close() != nil {
			t.Fatal("cannot close node for saved-config restart")
		}
		var config map[string]any
		if json.Unmarshal(content, &config) != nil {
			t.Fatal("private harness config must be JSON")
		}
		endpoints, ok := config["endpoints"].([]any)
		if !ok {
			t.Fatal("private harness config needs endpoints")
		}
		updated := false
		for _, endpoint := range endpoints {
			entry, ok := endpoint.(map[string]any)
			if ok && entry["tag"] == "ts" {
				entry["exit_node"] = saved
				updated = true
			}
		}
		if !updated {
			t.Fatal("private harness endpoint ts missing")
		}
		encoded, err := json.Marshal(config)
		if err != nil {
			t.Fatal("cannot encode restart config")
		}
		start(string(encoded))
	}

	start(string(content))
	commit(apply(exitA))
	waitExit(exitA)
	assertTraffic("selected A", sinkA)
	undo := apply(exitB)
	waitExit(exitB)
	assertTraffic("applied B before finalization", sinkB)
	if _, err := SetTailscaleExitNode(instance, "ts", ""); err == nil {
		t.Fatal("concurrent unfinalized change accepted")
	}
	if undo.Rollback() != nil || undo.Rollback() != nil || undo.Commit() == nil {
		t.Fatal("rollback finalization semantics")
	}
	waitExit(exitA)
	assertTraffic("rollback A", sinkA)
	change := apply(exitB)
	savedB := change.SavedValue()
	commit(change)
	waitExit(exitB)
	assertTraffic("committed B", sinkB)

	checkPing := func(peerID, requiredPath string) {
		t.Helper()
		var peerIPs []string
		for _, peer := range readStatus().Peers {
			if peer.ID == peerID {
				peerIPs = peer.IPs
			}
		}
		if len(peerIPs) == 0 {
			t.Fatal("diagnostic fixture absent from peer inventory")
		}
		stream, err := StartTailscalePeerPing(instance, "ts", peerID, 10000)
		if err != nil {
			t.Fatal("cannot start peer diagnostic")
		}
		defer stream.Close()
		deadline := time.Now().Add(12 * time.Second)
		samples, matches := 0, 0
		pinnedIP := ""
		for {
			if time.Now().After(deadline) {
				t.Fatal("diagnostic stream exceeded its bounded completion time")
			}
			raw, err := stream.Next(1000)
			if err == io.EOF {
				break
			}
			if err != nil {
				t.Fatal("private harness ping did not complete five samples")
			}
			if raw == "" {
				continue
			}
			var sample tailscalePingSample
			if json.Unmarshal([]byte(raw), &sample) != nil {
				t.Fatal("invalid diagnostic sample")
			}
			samples++
			if samples > 5 || sample.PeerID != peerID || sample.Sequence != samples {
				t.Fatal("diagnostic identity, sequence or sample bound changed")
			}
			knownIP := false
			for _, ip := range peerIPs {
				knownIP = knownIP || ip == sample.PeerIP
			}
			if !knownIP || (pinnedIP != "" && sample.PeerIP != pinnedIP) {
				t.Fatal("diagnostic address differs from the pinned inventory peer")
			}
			pinnedIP = sample.PeerIP
			if sample.Error != "" || sample.LatencyMs == nil || math.IsNaN(*sample.LatencyMs) || math.IsInf(*sample.LatencyMs, 0) || *sample.LatencyMs < 0 {
				t.Fatal("private diagnostic fixture did not return a successful disco RTT")
			}
			if sample.Path == "derp" {
				if sample.DERPRegionID <= 0 || sample.DERPRegionCode == "" {
					t.Fatal("DERP diagnostic lacks a relay identity")
				}
			} else if sample.Path != "direct" || requiredPath == "derp" {
				t.Fatal("diagnostic path disagrees with the isolated fixture")
			}
			if sample.Path == requiredPath {
				matches++
			}
		}
		if samples != 5 || matches == 0 {
			t.Fatal("private diagnostic fixture requires five samples and the expected path")
		}
	}
	checkPing(directPeer, "direct")
	checkPing(derpPeer, "derp")

	cancelledPing, err := StartTailscalePeerPing(instance, "ts", directPeer, 10000)
	if err != nil {
		t.Fatal("cannot open cancellation diagnostic")
	}
	defer cancelledPing.Close()
	if sample, err := cancelledPing.Next(5000); err != nil || sample == "" {
		t.Fatal("cancellation diagnostic did not start")
	}
	if cancelledPing.Close() != nil || cancelledPing.Close() != nil {
		t.Fatal("diagnostic cancellation failed")
	}
	if _, err := cancelledPing.Next(100); !errors.Is(err, context.Canceled) {
		t.Fatal("closed diagnostic did not report cancellation")
	}
	cancelledObservation, err := ObserveTailscaleStatus(instance, "ts")
	if err != nil {
		t.Fatal("cannot open cancellation observation")
	}
	defer cancelledObservation.Close()
	if snapshot, err := cancelledObservation.Next(5000); err != nil || snapshot == "" {
		t.Fatal("cancellation observation did not start")
	}
	if cancelledObservation.Close() != nil || cancelledObservation.Close() != nil {
		t.Fatal("observation cancellation failed")
	}
	if _, err := cancelledObservation.Next(100); !errors.Is(err, context.Canceled) {
		t.Fatal("closed observation did not report cancellation")
	}
	assertTraffic("cancelled diagnostics preserve B", sinkB)
	restart(savedB)
	waitExit(exitB)
	assertTraffic("saved B restart", sinkB)
	commit(apply(""))
	waitExit("")
	assertTraffic("clear", "")
	restart("")
	waitExit("")
	assertTraffic("empty restart", "")
	observation, err := ObserveTailscaleStatus(instance, "ts")
	if err != nil {
		t.Fatal("cannot open shutdown observation")
	}
	if instance.Close() != nil {
		t.Fatal("cannot close observed node")
	}
	if _, err := observation.Next(100); !errors.Is(err, context.Canceled) {
		t.Fatal("closed box observation did not report cancellation")
	}
	observation.Close()
}
