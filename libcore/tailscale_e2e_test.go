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
// names a peer that offers the exit. NEKOBOX_TAILSCALE_E2E_URL is a private probe URL.
func TestTailscaleEndToEnd(t *testing.T) {
	configPath := os.Getenv("NEKOBOX_TAILSCALE_E2E_CONFIG")
	if configPath == "" {
		t.Skip("NEKOBOX_TAILSCALE_E2E_CONFIG is not set")
	}
	exitName := os.Getenv("NEKOBOX_TAILSCALE_E2E_EXIT")
	probeURL := os.Getenv("NEKOBOX_TAILSCALE_E2E_URL")
	if probeURL == "" {
		t.Fatal("NEKOBOX_TAILSCALE_E2E_URL must identify the private harness probe")
	}
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
		t.Fatal("node did not become ready")
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
	found := false
	for _, peer := range peers {
		if peer.Name == exitName && peer.ExitNode && len(peer.IPs) > 0 {
			found = true
		}
	}
	if exitName != "" && !found {
		t.Fatal("configured exit node is not offered")
	}

	latency, err := UrlTestOutbound(instance, "ts", probeURL, 10000)
	if err != nil {
		t.Fatal("url test through ts:", err)
	}
	t.Log("latency through ts:", latency, "ms")
	if _, err := UrlTestOutbound(instance, "missing", probeURL, 1000); err == nil {
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
		t.Fatal("expected a pending login")
	}
	authURL, err := TailscaleAuthURL(instance, "ts")
	if err != nil || !strings.HasPrefix(authURL, "http") {
		t.Fatal("expected an HTTP(S) authentication URL")
	}
	statusRaw, err := TailscaleStatus(instance, "ts")
	if err != nil {
		t.Fatal("login status unavailable")
	}
	var status tailscaleStatusJSON
	if err := json.Unmarshal([]byte(statusRaw), &status); err != nil {
		t.Fatal("invalid status JSON")
	}
	if !status.NeedsLogin || status.NeedsApproval || status.KeyAuth || status.AuthURL != authURL {
		t.Fatal("interactive sign-in status fields are inconsistent")
	}
	urlFile, err := os.OpenFile(urlOut, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		t.Fatal("cannot create private registration handoff")
	}
	defer urlFile.Close()
	if urlFile.Chmod(0o600) != nil {
		t.Fatal("cannot restrict registration handoff permissions")
	}
	if _, err := urlFile.WriteString(authURL); err != nil {
		t.Fatal("cannot write private registration handoff")
	}
	if urlFile.Close() != nil {
		t.Fatal("cannot close private registration handoff")
	}
	// The harness registers the node once it sees the URL; the node must come up without a restart.
	deadline := time.Now().Add(2 * time.Minute)
	for {
		err = TailscaleWaitReady(instance, "ts", false, 5000)
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("login never completed")
		}
		time.Sleep(250 * time.Millisecond)
	}
	if again, _ := TailscaleAuthURL(instance, "ts"); again != "" {
		t.Fatal("authentication URL still pending after login")
	}
	if _, err := TailscalePeers(instance, "ts"); err != nil {
		t.Fatal("peer inventory unavailable after login")
	}
	statusRaw, err = TailscaleStatus(instance, "ts")
	if err != nil || json.Unmarshal([]byte(statusRaw), &status) != nil {
		t.Fatal("post-login status unavailable")
	}
	if status.NeedsLogin || status.NeedsApproval || status.AuthURL != "" || status.Self == nil {
		t.Fatal("post-login status fields are inconsistent")
	}
}
