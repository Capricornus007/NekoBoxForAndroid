package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTestSession(t *testing.T, dir string) string {
	t.Helper()
	path := filepath.Join(dir, "session.json")
	payload, err := json.Marshal(storedCredential{
		UID:          "uid-test",
		UserID:       "user-test",
		AccessToken:  "token-test",
		RefreshToken: "refresh-test",
	})
	if err != nil {
		t.Fatalf("marshal session: %v", err)
	}
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write session: %v", err)
	}
	return path
}

func runNodesWith(t *testing.T, apiURL, statePath string, extra ...string) (nodesOutput, int) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	args := append([]string{"--state", statePath, "--api-url", apiURL}, extra...)
	code := runNodes(args, &stdout, &stderr)
	var out nodesOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v (stderr %q)", stdout.String(), err, stderr.String())
	}
	return out, code
}

// serverList mirrors the wire format Proton's own client models declare: ID is a
// string, the key lives on the connecting domain as X25519PublicKey, and a logical
// server carries Load (0-100). The penalties below deliberately disagree with the
// loads so the test proves which one actually drives the order.
const serverListFixture = `{"LogicalServers":[
 {"ID":"11","Name":"JP#2 高載","Tier":2,"State":"up","ExitCountry":"jp","City":"Tokyo","Features":16,"Load":95,"Score":1.0,
  "StatusReference":{"Index":11,"Penalty":0.2,"Cost":1},
  "Servers":[{"Domain":"jp2.protonvpn.net","EntryIP":"1.2.3.5","Status":1,"X25519PublicKey":"BBBBAl==",
    "EntryPerProtocol":{"wireguard":{"IPv4":"1.2.3.55","Ports":[51820,443]}}}]},
 {"ID":"10","Name":"JP#1 空閒","Tier":2,"State":"up","ExitCountry":"jp","City":"Osaka","Features":0,"Load":5,"Score":9.0,
  "StatusReference":{"Index":10,"Penalty":0.9,"Cost":3},
  "Servers":[{"Domain":"jp1.protonvpn.net","EntryIP":"1.2.3.4","Status":1,"X25519PublicKey":"AAAAAl=="}]},
 {"ID":"12","Name":"無公鑰","Tier":0,"State":"up","ExitCountry":"jp",
  "StatusReference":{"Index":12,"Penalty":0.1,"Cost":0},
  "Servers":[{"Domain":"jp4.protonvpn.net","EntryIP":"1.2.3.6","Status":1}]},
 {"ID":"13","Name":"離線群組","Tier":0,"State":"down","ExitCountry":"jp",
  "StatusReference":{"Index":13,"Penalty":0.1,"Cost":0},
  "Servers":[{"Domain":"jp5.protonvpn.net","X25519PublicKey":"DDDDCl==","Status":1}]},
 {"ID":"14","Name":"域離線","Tier":2,"State":"up","ExitCountry":"jp",
  "StatusReference":{"Index":14,"Penalty":0.05,"Cost":0},
  "Servers":[{"Domain":"jp6.protonvpn.net","X25519PublicKey":"EEEECl==","Status":0}]}
]}`

func mockServer(t *testing.T, body string, status int) (*httptest.Server, func() *int) {
	t.Helper()
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		if r.URL.Path != "/vpn/logicals" {
			t.Errorf("path = %q, want /vpn/logicals", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer token-test" {
			t.Errorf("Authorization = %q, want the bearer form of the stored token", got)
		}
		if got := r.Header.Get("x-pm-uid"); got != "uid-test" {
			t.Errorf("x-pm-uid = %q, want uid-test", got)
		}
		if got := r.Header.Get("x-pm-apiversion"); got != protonAPIVersion {
			t.Errorf("x-pm-apiversion = %q, want %q", got, protonAPIVersion)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	return srv, func() *int { return &hits }
}

func TestNodesReadsProtonsRealFieldNames(t *testing.T) {
	srv, _ := mockServer(t, serverListFixture, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	// 12 has no key, 13 is down, 14's domain says Status:0 -> three dropped.
	if len(out.Servers) != 2 {
		t.Fatalf("got %d servers, want 2 usable: %+v", len(out.Servers), out.Servers)
	}
	if out.Dropped != 3 {
		t.Errorf("dropped = %d, want 3: %+v", out.Dropped, out.Servers)
	}
	// 11 carries the lower penalty but the higher load, so this is the case where
	// the two disagree: Load decides, because Penalty is not on the wire at all.
	if out.Servers[0].ID != "10" || out.Servers[1].ID != "11" {
		t.Errorf("order = %s,%s; want load-ascending 10,11", out.Servers[0].ID, out.Servers[1].ID)
	}
	if out.Servers[0].Load != 5 || out.Servers[1].Load != 95 {
		t.Errorf("load = %d,%d; want the API's 5,95 so the UI can show idle capacity", out.Servers[0].Load, out.Servers[1].Load)
	}
	// The scores here are deliberately the other way round, so this also proves Load
	// is the primary key and Score only breaks a tie.
	if out.Servers[0].Score != 9.0 || out.Servers[1].Score != 1.0 {
		t.Errorf("score = %v,%v; want the API's 9,1 carried through for tie-breaking", out.Servers[0].Score, out.Servers[1].Score)
	}
	if out.Servers[1].PublicKey != "BBBBAl==" {
		t.Errorf("public key = %q, want the X25519PublicKey the API returned", out.Servers[1].PublicKey)
	}
	if !out.Servers[1].IPv6 {
		t.Error("Features bit 16 should surface as ipv6=true")
	}
	if out.Servers[0].IPv6 {
		t.Error("Features 0 should surface as ipv6=false")
	}
}

func TestNodesPrefersTheWireGuardEntryAndItsFirstPort(t *testing.T) {
	srv, _ := mockServer(t, serverListFixture, http.StatusOK)
	defer srv.Close()

	out, _ := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	var jp2 *nodeView
	for i := range out.Servers {
		if out.Servers[i].ID == "11" {
			jp2 = &out.Servers[i]
		}
	}
	if jp2 == nil {
		t.Fatalf("server 11 missing from %+v", out.Servers)
	}
	if jp2.Endpoint != "1.2.3.55" {
		t.Errorf("endpoint = %q, want the EntryPerProtocol wireguard IPv4", jp2.Endpoint)
	}
	if jp2.Port != 51820 {
		t.Errorf("port = %d, want the first wireguard port", jp2.Port)
	}

	// Without a per-protocol record it falls back to EntryIP, then the default port.
	if jp1 := out.Servers[0]; jp1.ID != "10" || jp1.Endpoint != "1.2.3.4" || jp1.Port != defaultWireGuardPort {
		t.Errorf("fallback gave id=%s endpoint=%q port=%d, want 10 / 1.2.3.4 / %d",
			jp1.ID, jp1.Endpoint, jp1.Port, defaultWireGuardPort)
	}
}

func TestNodesCountryFilterAndLimit(t *testing.T) {
	srv, _ := mockServer(t, serverListFixture, http.StatusOK)
	defer srv.Close()
	statePath := writeTestSession(t, t.TempDir())

	out, _ := runNodesWith(t, srv.URL, statePath, "--country", "JP")
	if len(out.Servers) != 2 {
		t.Fatalf("country filter gave %d servers, want 2: %+v", len(out.Servers), out.Servers)
	}
	out, _ = runNodesWith(t, srv.URL, statePath, "--country", "us")
	if len(out.Servers) != 0 {
		t.Fatalf("US filter gave %d servers, want none: %+v", len(out.Servers), out.Servers)
	}
	out, _ = runNodesWith(t, srv.URL, statePath, "--limit", "1")
	if len(out.Servers) != 1 || out.Servers[0].ID != "10" {
		t.Fatalf("limit 1 gave %+v, want only the least loaded 10", out.Servers)
	}
}

func TestNodesReportsAnExpiredSessionInsteadOfFakeNodes(t *testing.T) {
	srv, _ := mockServer(t, `{"Code":10013,"Error":"Invalid access token"}`, http.StatusUnauthorized)
	defer srv.Close()

	dir := t.TempDir()
	statePath := writeTestSession(t, dir)
	out, code := runNodesWith(t, srv.URL, statePath)
	if code == 0 || out.OK {
		t.Fatalf("nodes reported success against a rejected token: %+v", out)
	}
	if len(out.Servers) != 0 {
		t.Errorf("servers = %d, want none after an auth rejection", len(out.Servers))
	}
	if out.Error != errSessionExpired.Error() {
		t.Errorf("error = %q, want the re-login hint %q", out.Error, errSessionExpired.Error())
	}
	// The app routes back to the sign-in screen on Code, not on the English sentence:
	// without this the field could silently disappear and leave the UI stuck on a
	// message the user cannot act on.
	if out.Code != codeSessionExpired {
		t.Errorf("code = %q, want %q", out.Code, codeSessionExpired)
	}
}

func TestNodesRefusesToInventNodesFromAnEmptyList(t *testing.T) {
	srv, _ := mockServer(t, `{"LogicalServers":[]}`, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code == 0 || out.OK {
		t.Fatalf("empty list reported success: %+v", out)
	}
	if out.Error == "" {
		t.Error("empty list failed without an explanation")
	}
}

func TestNodesFailsLoudlyWhenNothingIsUsable(t *testing.T) {
	srv, _ := mockServer(t, `{"LogicalServers":[
	  {"ID":"1","Name":"no key","State":"up","Servers":[{"Domain":"a.example"}]}]}`, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code == 0 || out.OK {
		t.Fatalf("an all-unusable list reported success: %+v", out)
	}
	if out.Dropped != 1 {
		t.Errorf("dropped = %d, want 1", out.Dropped)
	}
}

func TestNodesRejectsAMissingSession(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := runNodes([]string{"--state", filepath.Join(t.TempDir(), "absent.json")}, &stdout, &stderr)
	if code == 0 {
		t.Fatalf("missing session reported success: %s", stdout.String())
	}
	if !bytes.Contains(stdout.Bytes(), []byte("session")) {
		t.Errorf("output %q should explain the session problem", stdout.String())
	}
}

// 節點清單這一條被 Proton 要求人類驗證時（422/9001），錯誤訊息要講得出來，而且不能把
// 含挑戰 token 的驗證網址吐給 Kotlin（那邊會進 Logs）。
func TestNodesReportsACaptchaRequestWithoutLeakingTheToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(realCaptchaChallenge))
	}))
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code == 0 || out.OK {
		t.Fatalf("a 422 must not report success: %+v", out)
	}
	if !strings.Contains(out.Error, "CAPTCHA") {
		t.Errorf("error = %q, want it to name the CAPTCHA", out.Error)
	}
	if strings.Contains(out.Error, "HVSTART123") {
		t.Errorf("the challenge token leaked into the node-list error: %q", out.Error)
	}
}
