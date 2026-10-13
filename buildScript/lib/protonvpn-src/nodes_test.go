package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
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
		// 401/403 現在會先觸發一次 token 換新（refresh.go），所以這條路徑也要有答案：
		// 一律拒絕，讓「連換都換不到」的測試仍然走到 session_expired，而不是把
		// 未預期的路徑當成測試失敗。
		if r.URL.Path == "/auth/v4/refresh" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid refresh token"}`))
			return
		}
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

// TestNodesAcceptsTheCapitalisedWireGuardKey 釘住 2026-10-13 逆推官方 APK 查出來的缺陷：
// 客戶端查的鍵是大寫開頭的 "WireGuard"，我方只比對小寫 "wireguard" 時會永遠查不到、
// 靜默退回 EntryIP＋預設埠 51820（表面能用，其實沒吃到協商過的埠）。
func TestNodesAcceptsTheCapitalisedWireGuardKey(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"21","Name":"JP#21 大寫鍵","Tier":2,"State":"up","ExitCountry":"jp","City":"Tokyo",
	  "Features":0,"Load":7,"Score":8.0,
	  "StatusReference":{"Index":21,"Penalty":0.3,"Cost":1},
	  "Servers":[{"Domain":"jp21.protonvpn.net","EntryIP":"1.2.3.9","Status":1,
	    "X25519PublicKey":"FFFFAl==",
	    "EntryPerProtocol":{"WireGuard":{"IPv4":"1.2.3.77","Ports":[51821,443]}}}]}
	]}`
	srv, _ := mockServer(t, payload, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK || len(out.Servers) != 1 {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	got := out.Servers[0]
	// 查表命中才會拿到 per-protocol 的 IPv4 與它的第一個埠；退回 fallback 會是
	// EntryIP 1.2.3.9 ＋預設 51820，那正是這條測試要擋住的行為。
	if got.Endpoint != "1.2.3.77" {
		t.Errorf("endpoint = %q, want the per-protocol IPv4 1.2.3.77 (fallback would be 1.2.3.9)", got.Endpoint)
	}
	if got.Port != 51821 {
		t.Errorf("port = %d, want the entry's first port 51821 (fallback would be 51820)", got.Port)
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

func serverWithID(t *testing.T, servers []nodeView, id string) nodeView {
	t.Helper()
	for _, s := range servers {
		if s.ID == id {
			return s
		}
	}
	t.Fatalf("server %s missing from %+v", id, servers)
	return nodeView{}
}

// 協定能力就藏在 EntryPerProtocol 的鍵裡，而官方 APK（ch.protonvpn.android 5.20.74.0）的
// ProtocolSelection.getApiName() 是「VPN 名＋傳輸名」串接，鍵名是大寫開頭的
// WireGuardUDP／WireGuardTCP／WireGuardTLS 這一型；STEALTH = WireGuard + TLS，
// 所以尾端的 TLS 要另外算一份 stealth。這條釘住三件事：
// wireguard、openvpn、stealth 必須同時出現，且 WG 的位址/埠要吃到帶後綴那把鍵。
func TestNodesReportsProtocolsFromThePerProtocolKeys(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"31","Name":"HK#31 多協定","Tier":2,"State":"up","ExitCountry":"hk","City":"Hong Kong",
	  "Features":1,"Load":8,"Score":7.0,
	  "StatusReference":{"Index":31,"Penalty":0.2,"Cost":1},
	  "Servers":[{"Domain":"hk31.protonvpn.net","EntryIP":"10.0.0.31","Status":1,"X25519PublicKey":"GGGGAl==",
	    "EntryPerProtocol":{
	      "WireGuardUDP":{"IPv4":"10.0.0.131","Ports":[51820]},
	      "OpenVPNTLS":{"IPv4":"10.0.0.231","Ports":[443]}}}]}
	]}`
	srv, _ := mockServer(t, payload, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK || len(out.Servers) != 1 {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	got := out.Servers[0]
	want := []string{protocolWireGuardName, protocolStealthName, protocolOpenVPNName}
	if !slices.Equal(got.Protocols, want) {
		t.Errorf("protocols = %v, want %v (order is fixed so the UI can compare)", got.Protocols, want)
	}
	if !got.SecureCore {
		t.Error("Features bit 1 should surface as secureCore=true (isSecureCoreServer in the official client)")
	}
	// 帶後綴的 WireGuardUDP 也是 WireGuard 的記錄：查表命中才会拿到 10.0.0.131/51820，
	// 退回 EntryIP 會得到 10.0.0.31，那正是這條要擋住的行為。
	if got.Endpoint != "10.0.0.131" || got.Port != 51820 {
		t.Errorf("endpoint/port = %q/%d, want the WireGuardUDP entry 10.0.0.131/51820", got.Endpoint, got.Port)
	}
	if got.PublicKey != "GGGGAl==" {
		t.Errorf("public key = %q, want the WG key carried through as usual", got.PublicKey)
	}
}

// 舊形狀的 payload（只有 X25519PublicKey、完全沒有 per-protocol 表）不能因為「查不到鍵」
// 就退化成「這台不能用 WireGuard」。
func TestNodesLegacyShapeWithoutPerProtocolStillCountsAsWireGuard(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"32","Name":"TW#32 舊形狀","Tier":2,"State":"up","ExitCountry":"tw","City":"Taipei",
	  "Features":0,"Load":3,"Score":4.0,
	  "StatusReference":{"Index":32,"Penalty":0.1,"Cost":1},
	  "Servers":[{"Domain":"tw32.protonvpn.net","EntryIP":"10.0.0.32","Status":1,"X25519PublicKey":"HHHHAL=="}]}
	]}`
	srv, _ := mockServer(t, payload, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK || len(out.Servers) != 1 {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	got := out.Servers[0]
	if !slices.Equal(got.Protocols, []string{protocolWireGuardName}) {
		t.Errorf("protocols = %v, want [%s]", got.Protocols, protocolWireGuardName)
	}
	if got.SecureCore {
		t.Error("Features 0 must not report secureCore")
	}
	if got.Endpoint != "10.0.0.32" || got.Port != defaultWireGuardPort {
		t.Errorf("fallback = %q/%d, want EntryIP plus the WireGuard default %d",
			got.Endpoint, got.Port, defaultWireGuardPort)
	}
}

// 只有 OpenVPN 鍵、完全沒有 WG 公鑰的伺服器必須照常進清單：wgPublicKey 留空、
// 由 protocols 說明它能用什麼，而且這不算「回應壞了」，dropped 不能因為它增加。
func TestNodesKeepsAServerWithoutAWireGuardKeyInList(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"33","Name":"JP#33 只有 OpenVPN","Tier":2,"State":"up","ExitCountry":"jp","City":"Tokyo",
	  "Features":16,"Load":6,"Score":5.0,
	  "StatusReference":{"Index":33,"Penalty":0.2,"Cost":1},
	  "Servers":[{"Domain":"jp33.protonvpn.net","EntryIP":"10.0.0.33","Status":1,
	    "EntryPerProtocol":{
	      "OpenVPNTLS":{"IPv4":"10.0.0.233","Ports":[443]},
	      "OpenVPNUDP":{"IPv4":"10.0.0.133","Ports":[1194,54123]}}}]}
	]}`
	srv, _ := mockServer(t, payload, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("an OpenVPN-only server must not fail the listing: code=%d out=%+v", code, out)
	}
	if len(out.Servers) != 1 {
		t.Fatalf("got %d servers, want the OpenVPN-only one kept: %+v", len(out.Servers), out.Servers)
	}
	if out.Dropped != 0 {
		t.Errorf("dropped = %d, want 0: only-OpenVPN is a capability, not a broken response", out.Dropped)
	}
	got := out.Servers[0]
	if got.PublicKey != "" {
		t.Errorf("wgPublicKey = %q, want it empty when the API sent no X25519 key", got.PublicKey)
	}
	if !slices.Equal(got.Protocols, []string{protocolStealthName, protocolOpenVPNName}) {
		t.Errorf("protocols = %v, want [%s %s]", got.Protocols, protocolStealthName, protocolOpenVPNName)
	}
	// 位址與埠要用它自己協定的記錄，而且不能憑空套 WireGuard 的 51820。
	// 同協定兩把鍵時 UDP 排在 TLS 前面，所以拿 10.0.0.133/1194。
	if got.Endpoint != "10.0.0.133" || got.Port != 1194 {
		t.Errorf("endpoint/port = %q/%d, want the OpenVPNUDP entry 10.0.0.133/1194", got.Endpoint, got.Port)
	}
	if !got.IPv6 {
		t.Error("Features bit 16 should still surface as ipv6=true")
	}
}

// Secure-Core 是 Features 的另一個 bit（1），與 IPv6 那把（16）各自獨立，
// 兩個都開（17）時雨都要報 true。
func TestNodesSurfacesTheSecureCoreFeatureBit(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"41","Name":"SE#41 Secure-Core","Tier":2,"State":"up","ExitCountry":"se","City":"Stockholm",
	  "Features":1,"Load":10,"Score":3.0,
	  "Servers":[{"Domain":"se41.protonvpn.net","EntryIP":"10.0.0.41","Status":1,"X25519PublicKey":"IIIACl=="}]},
	 {"ID":"42","Name":"SE#42 兩開","Tier":2,"State":"up","ExitCountry":"se","City":"Stockholm",
	  "Features":17,"Load":11,"Score":3.0,
	  "Servers":[{"Domain":"se42.protonvpn.net","EntryIP":"10.0.0.42","Status":1,"X25519PublicKey":"JJJACl=="}]},
	 {"ID":"43","Name":"SE#43 只有 IPv6","Tier":2,"State":"up","ExitCountry":"se","City":"Stockholm",
	  "Features":16,"Load":12,"Score":3.0,
	  "Servers":[{"Domain":"se43.protonvpn.net","EntryIP":"10.0.0.43","Status":1,"X25519PublicKey":"KKKACl=="}]}
	]}`
	srv, _ := mockServer(t, payload, http.StatusOK)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK || len(out.Servers) != 3 {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	cases := []struct {
		id     string
		secure bool
		ipv6   bool
	}{
		{"41", true, false},
		{"42", true, true},
		{"43", false, true},
	}
	for _, c := range cases {
		got := serverWithID(t, out.Servers, c.id)
		if got.SecureCore != c.secure {
			t.Errorf("server %s secureCore = %v, want %v", c.id, got.SecureCore, c.secure)
		}
		if got.IPv6 != c.ipv6 {
			t.Errorf("server %s ipv6 = %v, want %v", c.id, got.IPv6, c.ipv6)
		}
	}
}

// protocolCaps 的直接單元測試：不分大小寫、去重、固定順序，認不認識的鍵名以小寫保留。
func TestProtocolCapsIsCaseInsensitiveDedupedAndOrdered(t *testing.T) {
	caps, secureCore := protocolCaps(connectingDomain{
		EntryPerProtocol: map[string]serverEntryInfo{
			"wireguardtcp": {},
			"WireGuardUDP": {},
			"WireGuardTLS": {},
			"Stealth":      {},
			"ProTunTCP":    {},
			"FooBar":       {},
		},
	}, logicalServer{Features: featureSecureCore | featureIPv6})
	want := []string{protocolWireGuardName, protocolStealthName, protocolProTunName, "foobar"}
	if !slices.Equal(caps, want) {
		t.Errorf("caps = %v, want %v", caps, want)
	}
	if !secureCore {
		t.Error("bit 1 must read as secureCore even when other bits are set")
	}

	// 什麼都沒有時回 nil，讓 JSON 端把 protocols 整個省略，而不是吐一個空陣列。
	empty, _ := protocolCaps(connectingDomain{}, logicalServer{})
	if empty != nil {
		t.Errorf("caps = %v, want nil for a domain with no signal at all", empty)
	}
}
