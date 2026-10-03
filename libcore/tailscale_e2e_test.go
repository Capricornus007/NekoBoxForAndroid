package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/matsuridayo/libneko/neko_log"
)

// Runs against a live tailnet: NEKOBOX_TAILSCALE_E2E_CONFIG points to a sing-box config with
// a Tailscale endpoint tagged "ts" that selects an exit node, and NEKOBOX_TAILSCALE_E2E_EXIT
// names a peer that offers the exit. See info_for_agents/tailscale/e2e for the Headscale setup.
func TestTailscaleEndToEnd(t *testing.T) {
	configPath := os.Getenv("NEKOBOX_TAILSCALE_E2E_CONFIG")
	if configPath == "" {
		t.Skip("NEKOBOX_TAILSCALE_E2E_CONFIG is not set")
	}
	exitName := os.Getenv("NEKOBOX_TAILSCALE_E2E_EXIT")
	content, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	// The platform log writer forwards to neko_log, which InitCore sets up on Android.
	if err := neko_log.SetupLog(1<<20, filepath.Join(t.TempDir(), "neko.log")); err != nil {
		t.Fatal(err)
	}
	instance, err := NewSingBoxInstance(string(content), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}

	if err := TailscaleWaitReady(instance, "ts", true, 30000); err != nil {
		t.Fatal("wait ready:", err)
	}
	if err := TailscaleWaitReady(instance, "missing", false, 1000); err == nil {
		t.Fatal("expected an error for an unknown endpoint tag")
	}

	raw, err := TailscalePeers(instance, "ts")
	if err != nil {
		t.Fatal("peers:", err)
	}
	var peers []tailscalePeer
	if err := json.Unmarshal([]byte(raw), &peers); err != nil {
		t.Fatal(err)
	}
	t.Log("peers:", raw)
	found := false
	for _, peer := range peers {
		if peer.Name == exitName && peer.ExitNode && len(peer.IPs) > 0 {
			found = true
		}
	}
	if exitName != "" && !found {
		t.Fatalf("exit node %q not offered in %s", exitName, raw)
	}

	latency, err := UrlTestOutbound(instance, "ts", "http://connectivitycheck.gstatic.com/generate_204", 10000)
	if err != nil {
		t.Fatal("url test through ts:", err)
	}
	t.Log("latency through ts:", latency, "ms")
	if _, err := UrlTestOutbound(instance, "missing", "http://connectivitycheck.gstatic.com/generate_204", 1000); err == nil {
		t.Fatal("expected an error for an unknown outbound tag")
	}
}
