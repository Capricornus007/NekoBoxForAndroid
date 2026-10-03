package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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

// Interactive login: NEKOBOX_TAILSCALE_E2E_LOGIN_CONFIG points to a config whose "ts" endpoint
// has no auth key. The auth URL is written to NEKOBOX_TAILSCALE_E2E_AUTH_URL_OUT; the harness
// approves that registration on the control server while the test waits for the node.
func TestTailscaleInteractiveLogin(t *testing.T) {
	configPath := os.Getenv("NEKOBOX_TAILSCALE_E2E_LOGIN_CONFIG")
	urlOut := os.Getenv("NEKOBOX_TAILSCALE_E2E_AUTH_URL_OUT")
	if configPath == "" || urlOut == "" {
		t.Skip("NEKOBOX_TAILSCALE_E2E_LOGIN_CONFIG or NEKOBOX_TAILSCALE_E2E_AUTH_URL_OUT is not set")
	}
	content, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
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

	err = TailscaleWaitReady(instance, "ts", false, 15000)
	if err == nil || !strings.Contains(err.Error(), "needs login") {
		t.Fatal("expected a pending login, got:", err)
	}
	authURL, err := TailscaleAuthURL(instance, "ts")
	if err != nil || !strings.HasPrefix(authURL, "http") {
		t.Fatal("auth url:", authURL, err)
	}
	t.Log("auth url:", authURL)
	if err := os.WriteFile(urlOut, []byte(authURL), 0o644); err != nil {
		t.Fatal(err)
	}
	// The harness registers the node once it sees the URL; the node must come up without a restart.
	deadline := time.Now().Add(2 * time.Minute)
	for {
		err = TailscaleWaitReady(instance, "ts", false, 5000)
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("login never completed:", err)
		}
	}
	if again, _ := TailscaleAuthURL(instance, "ts"); again != "" {
		t.Fatal("auth url still pending after login:", again)
	}
	raw, err := TailscalePeers(instance, "ts")
	if err != nil {
		t.Fatal("peers:", err)
	}
	t.Log("logged in; peers:", raw)
}
