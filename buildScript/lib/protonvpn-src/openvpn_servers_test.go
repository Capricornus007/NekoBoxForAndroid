package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"testing"
)

// 這一檔專測「OpenVPN 節點到底有沒有被取回來」。用戶的抱怨很具體：
// 全部協定裡抓得到 WireGuard，卻沒有任何一台被標成能用 OpenVPN，於是他按下去
// 永远只会拿到 WG 剖面，而他要的是「登一次就自己獲取可用節點、包含 OpenVPN、不問帳密」。
// 根因不是 UI，是我方請 logicals 時沒帶 WithEntriesForProtocols，上游就不回
// OpenVPN 那一族的條目（證據與端點見 openvpn_servers.go 檔頭）。

// recordingServer 記下每條請求的「路徑?查詢」，並按路徑分派回包：
// logicals 回清單、clientconfig 回預設埠、其他路徑一律 404（讓測試自己決定要不要接）。
func recordingServer(t *testing.T, logicalsBody string, configBody string, status int, pathStatus map[string]int,
) (*httptest.Server, *[]string) {
	t.Helper()
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RawQuery != "" {
			seen = append(seen, r.URL.Path+"?"+r.URL.RawQuery)
		} else {
			seen = append(seen, r.URL.Path)
		}
		if code, ok := pathStatus[r.URL.Path]; ok {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(code)
			_, _ = w.Write([]byte(`{"Code":2,"Error":"stubbed failure"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		switch r.URL.Path {
		case logicalsV2Path, legacyLogicalsPath:
			_, _ = w.Write([]byte(logicalsBody))
		case clientConfigPath:
			if configBody == "" {
				_, _ = w.Write([]byte(`{"Code":1000}`))
				return
			}
			_, _ = w.Write([]byte(configBody))
		case "/auth/v4/refresh":
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"Code":10013,"Error":"Invalid refresh token"}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	return srv, &seen
}

func serverByID(servers []nodeView, id string) (nodeView, bool) {
	for _, s := range servers {
		if s.ID == id {
			return s, true
		}
	}
	return nodeView{}, false
}

// 一台同時有 WG 與 OpenVPN 的機器（官方 win-app 請求的就是這五把鍵，所以這是最真實的形状：
// 每協定一條自己的 relay IPv4）。
const dualProtocolFixture = `{"StatusID":"status-1","LogicalServers":[
 {"ID":"50","Name":"NL#50 雙協定","Tier":1,"State":"up","ExitCountry":"nl","City":"Amsterdam",
  "Features":16,"Load":4,"Score":3.5,"StatusReference":{"Index":50,"Penalty":0.1,"Cost":1},
  "Servers":[{"ID":"p50","Domain":"nl50.protonvpn.net","EntryIP":"10.0.0.50","Status":true,
    "X25519PublicKey":"KKKKAl==",
    "EntryPerProtocol":{
      "WireGuardUDP":{"IPv4":"10.0.0.150","Ports":[51820,443]},
      "OpenVPNUDP":{"IPv4":"10.0.0.250","Ports":[1194,4569,5060]},
      "OpenVPNTCP":{"IPv4":"10.0.0.350","Ports":[443,80]}}}]}
]}`

// (a) 只有 OpenVPN 的伺服器要進清單，且 protocols 含 openvpn。
func TestNodesEmitsAnOpenVpnRowForAnOpenVpnOnlyServer(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"51","Name":"NL#51 只有 OpenVPN","Tier":1,"State":"up","ExitCountry":"nl","City":"Haarlem",
	  "Features":0,"Load":9,"Score":2.0,
	  "Servers":[{"Domain":"nl51.protonvpn.net","EntryIP":"10.0.0.51","Status":true,
	    "EntryPerProtocol":{"OpenVPNUDP":{"IPv4":"10.0.0.151","Ports":[1194]}}}]}
	]}`
	srv, _ := recordingServer(t, payload, "", http.StatusOK, nil)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("an OpenVPN-only server must list fine: code=%d out=%+v", code, out)
	}
	row, ok := serverByID(out.Servers, "51"+openVpnRowIDSuffix)
	if !ok {
		t.Fatalf("no OpenVPN row in %+v", out.Servers)
	}
	if !slices.Contains(row.Protocols, protocolOpenVPNName) {
		t.Errorf("protocols = %v, want it to carry %q", row.Protocols, protocolOpenVPNName)
	}
	if slices.Contains(row.Protocols, protocolWireGuardName) {
		t.Errorf("protocols = %v, must not claim wireguard without an X25519 key", row.Protocols)
	}
	if row.PublicKey != "" {
		t.Errorf("wgPublicKey = %q, want empty: the app routes on it, and a key here would build a WireGuard profile", row.PublicKey)
	}
	// 位址必須是 OpenVPN 那一族的 relay，不是 EntryIP（那是 WG 的）。
	if row.Endpoint != "10.0.0.151" {
		t.Errorf("endpoint = %q, want the OpenVPNUDP relay IPv4", row.Endpoint)
	}
	if row.Transport != transportUDP {
		t.Errorf("transport = %q, want %q so the .ovpn proto line is not guessed", row.Transport, transportUDP)
	}
	if row.Port != 1194 || !slices.Equal(row.Ports, []int{1194}) {
		t.Errorf("port/ports = %d/%v, want 1194/[1194]", row.Port, row.Ports)
	}
	if row.Name != "NL#51 只有 OpenVPN" || row.Country != "nl" || row.City != "Haarlem" {
		t.Errorf("metadata lost: %+v", row)
	}
}

// (b) 同一台同時有 WG 與 OpenVPN 時兩個都看得到，而且各自的位址/埠是自己那一族的。
func TestNodesSplitsAserverIntoOneRowPerDialableProtocol(t *testing.T) {
	srv, _ := recordingServer(t, dualProtocolFixture, "", http.StatusOK, nil)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	if len(out.Servers) != 2 {
		t.Fatalf("got %d rows, want 2 (WireGuard 一列、OpenVPN 一列): %+v", len(out.Servers), out.Servers)
	}
	wg, _ := serverByID(out.Servers, "50")
	ovpn, _ := serverByID(out.Servers, "50"+openVpnRowIDSuffix)

	if !slices.Equal(wg.Protocols, []string{protocolWireGuardName}) {
		t.Errorf("wireguard row protocols = %v, want [wireguard]", wg.Protocols)
	}
	if wg.Endpoint != "10.0.0.150" || wg.Port != 51820 || wg.PublicKey != "KKKKAl==" {
		t.Errorf("wireguard row = %+v, want the WireGuardUDP relay", wg)
	}
	if wg.ID == ovpn.ID {
		t.Error("both rows share one id; the app would not be able to tell them apart")
	}

	if !slices.Equal(ovpn.Protocols, []string{protocolOpenVPNName}) {
		t.Errorf("openvpn row protocols = %v, want [openvpn]", ovpn.Protocols)
	}
	// UDP 排在 TCP 前面（同一台兩把鍵時要有確定答案，不然 Go map 的亂序會讓埠飄）。
	if ovpn.Endpoint != "10.0.0.250" || ovpn.Port != 1194 || ovpn.Transport != transportUDP {
		t.Errorf("openvpn row = %+v, want 10.0.0.250 / 1194 / udp", ovpn)
	}
	if !slices.Equal(ovpn.Ports, []int{1194, 4569, 5060}) {
		t.Errorf("openvpn ports = %v, want the whole UDP list so OpenVPN can retry across ports", ovpn.Ports)
	}
	// 跟協定無關的那幾欄必須兩列一致，否則同一台機器在清單上會排出兩個位置、
	// 顯示兩種空閒度，用戶會以為是兩台不一樣的機器。
	if wg.Load != ovpn.Load || wg.Score != ovpn.Score || wg.Tier != ovpn.Tier ||
		wg.IPv6 != ovpn.IPv6 || wg.SecureCore != ovpn.SecureCore {
		t.Errorf("row metadata disagree: wg=%+v ovpn=%+v", wg, ovpn)
	}
}

// (c) 層級過濾對 OpenVPN 條目同樣生效：免費層不該看到 Tier 2 機器的 OpenVPN 列，
// 否則匯進來就是一台連不上的節點（那條規則原本只管 WG，用戶的抱怨同樣適用在這裡）。
func TestNodesTierFilterAppliesToTheOpenVpnRow(t *testing.T) {
	srv, _ := recordingServer(t, dualProtocolFixture, "", http.StatusOK, nil)
	defer srv.Close()
	statePath := writeTestSession(t, t.TempDir())

	// fixture 那台是 Tier 1：不帶 --tier 與 --tier 1 都會留著兩列。
	for _, args := range [][]string{{}, {"--tier", "1"}} {
		out, _ := runNodesWith(t, srv.URL, statePath, args...)
		if len(out.Servers) != 2 {
			t.Fatalf("args %v gave %d rows, want 2: %+v", args, len(out.Servers), out.Servers)
		}
	}

	// 同一台改成 Tier 2，免費層（--tier 1）就兩列都該消失，dropped 記 1（一台機器，不是兩列）。
	paid := strings.Replace(dualProtocolFixture, `"Tier":1`, `"Tier":2`, 1)
	srvPaid, _ := recordingServer(t, paid, "", http.StatusOK, nil)
	defer srvPaid.Close()
	out, _ := runNodesWith(t, srvPaid.URL, statePath, "--tier", "1")
	if out.OK || len(out.Servers) != 0 {
		t.Fatalf("paid server leaked into tier 1: ok=%v %+v", out.OK, out.Servers)
	}
	if out.Dropped != 1 {
		t.Errorf("dropped = %d, want 1 (the server, not its two rows)", out.Dropped)
	}
	if out.MaxTier != 1 {
		t.Errorf("maxTier = %d, want it echoed back", out.MaxTier)
	}

	// 國家過濾同理：非所選國家時連 OpenVPN 列也不該出現。
	out, _ = runNodesWith(t, srv.URL, statePath, "--country", "us")
	if len(out.Servers) != 0 {
		t.Errorf("US filter gave %+v, want nothing", out.Servers)
	}
}

// (d) 錯誤路徑：非 200 要報得出來、token 過期要給 session_expired，不能擱死也不能生假節點。
func TestNodesReportsUpstreamErrorsFromTheOpenVpnPath(t *testing.T) {
	statePath := writeTestSession(t, t.TempDir())

	srv500, _ := recordingServer(t, dualProtocolFixture, "", http.StatusInternalServerError, nil)
	defer srv500.Close()
	out, code := runNodesWith(t, srv500.URL, statePath)
	if code == 0 || out.OK || !strings.Contains(out.Error, "500") {
		t.Fatalf("a 500 must surface as an error: code=%d out=%+v", code, out)
	}
	if len(out.Servers) != 0 {
		t.Errorf("servers = %+v, want none on a failed request", out.Servers)
	}

	srv401, _ := recordingServer(t, "", "", http.StatusUnauthorized, nil)
	defer srv401.Close()
	out, code = runNodesWith(t, srv401.URL, statePath)
	if code == 0 || out.OK || out.Code != codeSessionExpired {
		t.Fatalf("a rejected token must give code %q: code=%d out=%+v", codeSessionExpired, code, out)
	}
}

// 萬一 v2 這條路被改路徑或拒收參數（404/405/400），要退回舊的 /vpn/logicals：
// 那樣就沒有 OpenVPN 列，但 WireGuard 清單還在。功能新增不能把既有功能賭进去。
func TestNodesFallsBackToTheLegacyPathWhenV2IsRejected(t *testing.T) {
	srv, seen := recordingServer(t, serverListFixture, "", http.StatusOK,
		map[string]int{logicalsV2Path: http.StatusNotFound})
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("legacy fallback must still list WireGuard servers: code=%d out=%+v", code, out)
	}
	if _, ok := serverByID(out.Servers, "11"); !ok {
		t.Errorf("server 11 missing from %+v", out.Servers)
	}
	for _, id := range []string{"10" + openVpnRowIDSuffix, "11" + openVpnRowIDSuffix} {
		if _, found := serverByID(out.Servers, id); found {
			t.Errorf("row %s must not exist: the legacy response carries no protocol entries", id)
		}
	}
	if !slices.Contains(*seen, legacyLogicalsPath) {
		t.Errorf("requests = %v, want the legacy path to have been tried", *seen)
	}
}

// 5xx 不能算「路徑被拒」：退路只會再錯一次，而且會蓋掉真正的錯誤訊息。
func TestNodesDoesNotRetryTheLegacyPathOnServerErrors(t *testing.T) {
	srv, seen := recordingServer(t, "", "", http.StatusOK,
		map[string]int{logicalsV2Path: http.StatusBadGateway})
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code == 0 || out.OK {
		t.Fatalf("a 502 must not report success: %+v", out)
	}
	for _, request := range *seen {
		if strings.HasPrefix(request, legacyLogicalsPath) {
			t.Errorf("requests = %v, want no legacy retry after a 5xx", *seen)
		}
	}
}

// 這才是這次修復的本體：請清單時一定要帶 WithEntriesForProtocols，而且值裡要有
// OpenVPNUDP／OpenVPNTCP（win-app 那串），否則上游不回條目、UI 就永遠筛不出 OpenVPN。
func TestNodesAsksForTheOpenVpnProtocolEntries(t *testing.T) {
	srv, seen := recordingServer(t, dualProtocolFixture, "", http.StatusOK, nil)
	defer srv.Close()

	if _, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir())); code != 0 {
		t.Fatalf("nodes failed with exit %d", code)
	}
	var query string
	for _, request := range *seen {
		if strings.HasPrefix(request, logicalsV2Path+"?") {
			query = strings.TrimPrefix(request, logicalsV2Path+"?")
		}
	}
	if query == "" {
		t.Fatalf("no query on the logicals request: %v", *seen)
	}
	values, err := url.ParseQuery(query)
	if err != nil {
		t.Fatalf("parse query %q: %v", query, err)
	}
	if values.Get("WithState") != "true" {
		t.Errorf("WithState = %q, want true (that is what carries Load/Score)", values.Get("WithState"))
	}
	if values.Get("SecureCoreFilter") != "all" {
		t.Errorf("SecureCoreFilter = %q, want all (otherwise the Secure-Core servers vanish)", values.Get("SecureCoreFilter"))
	}
	entries := values.Get("WithEntriesForProtocols")
	for _, want := range []string{"OpenVPNUDP", "OpenVPNTCP", "WireGuardUDP"} {
		if !strings.Contains(entries, want) {
			t.Errorf("WithEntriesForProtocols = %q, want it to ask for %s", entries, want)
		}
	}
}

// 明確請它不要 OpenVPN 列時（--openvpn=false，線上出狀況時的逃生閥）清單要回到舊形狀。
func TestNodesOpenVpnRowsCanBeDisabled(t *testing.T) {
	srv, _ := recordingServer(t, dualProtocolFixture, "", http.StatusOK, nil)
	defer srv.Close()

	var stdout, stderr bytes.Buffer
	args := []string{"--state", writeTestSession(t, t.TempDir()), "--api-url", srv.URL, "--openvpn=false"}
	if code := runNodes(args, &stdout, &stderr); code != 0 {
		t.Fatalf("exit=%d stderr=%q", code, stderr.String())
	}
	var out nodesOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse %q: %v", stdout.String(), err)
	}
	if len(out.Servers) != 1 || out.Servers[0].ID != "50" {
		t.Fatalf("rows = %+v, want only the WireGuard row", out.Servers)
	}
}

// 上游的 Status 有兩張臉：android 的模型是 Boolean、win-app 的是數字、舊形狀實測是 0/1。
// 寫死任何一種都會讓整份 JSON 解析失敗（那會兒用戶看到的是「清單抓不到」，比漏一台嚴重）。
func TestNodesAcceptsBothStatusEncodings(t *testing.T) {
	const asNumber = `{"LogicalServers":[
	 {"ID":"60","Name":"TW#60","Tier":1,"State":"up","ExitCountry":"tw","City":"Taipei","Load":1,"Score":1.0,
	  "Servers":[{"Domain":"tw60.protonvpn.net","EntryIP":"10.0.0.60","Status":1,
	    "EntryPerProtocol":{"OpenVPNUDP":{"IPv4":"10.0.0.160","Ports":[1194]}}}]}
	]}`
	const asBool = `{"LogicalServers":[
	 {"ID":"61","Name":"TW#61","Tier":1,"State":"up","ExitCountry":"tw","City":"Taipei","Load":1,"Score":1.0,
	  "Servers":[{"Domain":"tw61.protonvpn.net","EntryIP":"10.0.0.61","Status":true,
	    "EntryPerProtocol":{"OpenVPNUDP":{"IPv4":"10.0.0.161","Ports":[1194]}}}]}
	]}`
	const asOffline = `{"LogicalServers":[
	 {"ID":"62","Name":"TW#62","Tier":1,"State":"up","ExitCountry":"tw","City":"Taipei","Load":1,"Score":1.0,
	  "Servers":[{"Domain":"tw62.protonvpn.net","EntryIP":"10.0.0.62","Status":false,
	    "EntryPerProtocol":{"OpenVPNUDP":{"IPv4":"10.0.0.162","Ports":[1194]}}}]}
	]}`

	for _, tc := range []struct {
		name    string
		payload string
		rows    int
		dropped int
	}{
		{"numeric status", asNumber, 1, 0},
		{"boolean status", asBool, 1, 0},
		{"explicitly offline", asOffline, 0, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv, _ := recordingServer(t, tc.payload, "", http.StatusOK, nil)
			defer srv.Close()
			out, _ := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
			if len(out.Servers) != tc.rows {
				t.Errorf("rows = %d, want %d: %+v", len(out.Servers), tc.rows, out.Servers)
			}
			if out.Dropped != tc.dropped {
				t.Errorf("dropped = %d, want %d", out.Dropped, tc.dropped)
			}
		})
	}
}

// 每台機器自己帶的埠是主來源；沒有帶才用 clientconfig 的 DefaultPorts.OpenVPN。
// 兩邊都沒有時留空，由 App 那側決定預設值——不憑空捏一個 Proton 的埠。
func TestOpenVpnPortsPreferTheServerEntryThenClientConfig(t *testing.T) {
	const withoutPorts = `{"LogicalServers":[
	 {"ID":"70","Name":"SE#70","Tier":1,"State":"up","ExitCountry":"se","City":"Stockholm",
	  "Load":2,"Score":1.0,
	  "Servers":[{"Domain":"se70.protonvpn.net","EntryIP":"10.0.0.70","Status":true,
	    "EntryPerProtocol":{"OpenVPNUDP":{"IPv4":"10.0.0.170"}}}]}
	]}`
	config := `{"Code":1000,"DefaultPorts":{"OpenVPN":{"UDP":[80,51820,4569,1194,5060],"TCP":[443,7770,8443]},"WireGuard":{"UDP":[443]}}}`

	srv, _ := recordingServer(t, withoutPorts, config, http.StatusOK, nil)
	defer srv.Close()
	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	row, ok := serverByID(out.Servers, "70"+openVpnRowIDSuffix)
	if !ok {
		t.Fatalf("no OpenVPN row: %+v", out.Servers)
	}
	if !slices.Equal(row.Ports, []int{80, 51820, 4569, 1194, 5060}) {
		t.Errorf("ports = %v, want the clientconfig UDP list verbatim (order is not meaningful: the config carries remote-random)", row.Ports)
	}

	// clientconfig 壞了不能拖垮清單：那一列照樣生，只是沒有備援埠（port 留 0，
	// 由 App 決定；生一個假埠比留空更壞）。
	srvBroken, _ := recordingServer(t, withoutPorts, "", http.StatusOK,
		map[string]int{clientConfigPath: http.StatusInternalServerError})
	defer srvBroken.Close()
	out, code = runNodesWith(t, srvBroken.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("a broken client config must not fail the listing: code=%d out=%+v", code, out)
	}
	row, ok = serverByID(out.Servers, "70"+openVpnRowIDSuffix)
	if !ok {
		t.Fatalf("no OpenVPN row after a client config failure: %+v", out.Servers)
	}
	if row.Port != 0 || len(row.Ports) != 0 {
		t.Errorf("port/ports = %d/%v, want 0/empty instead of an invented port", row.Port, row.Ports)
	}
	if row.Endpoint != "10.0.0.170" {
		t.Errorf("endpoint = %q, want the relay IPv4 even when ports are missing", row.Endpoint)
	}
}

// 只有 TCP 鍵的機器要能用 tcp 傳輸生列（App 那側靠 transport 决定 .ovpn 的 proto）。
func TestOpenVpnRowUsesTheTcpEntryWhenThereIsNoUDP(t *testing.T) {
	const payload = `{"LogicalServers":[
	 {"ID":"71","Name":"SE#71","Tier":1,"State":"up","ExitCountry":"se","City":"Stockholm",
	  "Load":3,"Score":1.0,
	  "Servers":[{"Domain":"se71.protonvpn.net","EntryIP":"10.0.0.71","Status":true,
	    "EntryPerProtocol":{"OpenVPNTCP":{"IPv4":"10.0.0.171","Ports":[443,80]}}}]}
	]}`
	srv, _ := recordingServer(t, payload, "", http.StatusOK, nil)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	row, ok := serverByID(out.Servers, "71"+openVpnRowIDSuffix)
	if !ok {
		t.Fatalf("no OpenVPN row: %+v", out.Servers)
	}
	if row.Transport != transportTCP || row.Port != 443 || !slices.Equal(row.Ports, []int{443, 80}) {
		t.Errorf("row = %+v, want tcp / 443 / [443 80]", row)
	}
}

// TLS 後綴的 OpenVPN 鍵不可拨（我方那份模板沒有 TLS 封裝層，官方也只請求/映射
// OpenVPNUDP｜OpenVPNTCP），這種機器宁可不生列，也不要生一份點下去連不上的剖面。
func TestOpenVpnDialKeyRejectsTheTLSVariant(t *testing.T) {
	cases := map[string]struct {
		transport string
		ok        bool
	}{
		"OpenVPNUDP":   {transportUDP, true},
		"OpenVPNTCP":   {transportTCP, true},
		"openvpn":      {transportUDP, true},
		"OpenVPN":      {transportUDP, true},
		"OpenVPNTLS":   {"", false},
		"WireGuardUDP": {"", false},
	}
	for key, want := range cases {
		transport, ok := openVpnDialKey(key)
		if ok != want.ok || transport != want.transport {
			t.Errorf("openVpnDialKey(%q) = %q,%v; want %q,%v", key, transport, ok, want.transport, want.ok)
		}
	}
}

// 上游偶尔會給重複或越界的埠；OpenVPN 對 0/負數只在每條 remote 上白重試一次。
func TestSanitizePortsDropsJunkAndDuplicates(t *testing.T) {
	got := sanitizePorts([]int{1194, 0, -3, 70000, 1194, 443})
	if !slices.Equal(got, []int{1194, 443}) {
		t.Errorf("sanitizePorts = %v, want [1194 443]", got)
	}
	if len(sanitizePorts(nil)) != 0 {
		t.Error("nil ports must stay empty, not become a one-element slice")
	}
}

// 截斷是 v2 的真實行為（ResponseMetadata.ListIsTruncated，win-app 的 LogicalsMetadataResponse、
// android 的 LogicalsMetadata）。我方沒有 IncludeID[] 那條補漏的路，至少要讓呼叫端看得见
// 「這不是全部」，不能把截斷過的清單講成「這帳號的節點」。
func TestNodesReportsWhenTheUpstreamListIsTruncated(t *testing.T) {
	const truncated = `{"StatusID":"status-1","ResponseMetadata":{"ListIsTruncated":true},"LogicalServers":[
	 {"ID":"80","Name":"JP#80","Tier":1,"State":"up","ExitCountry":"jp","City":"Tokyo","Load":1,"Score":1.0,
	  "Servers":[{"Domain":"jp80.protonvpn.net","EntryIP":"10.0.0.80","Status":true,"X25519PublicKey":"LLLLAl=="}]}
	]}`
	srv, _ := recordingServer(t, truncated, "", http.StatusOK, nil)
	defer srv.Close()

	out, code := runNodesWith(t, srv.URL, writeTestSession(t, t.TempDir()))
	if code != 0 || !out.OK {
		t.Fatalf("nodes failed: code=%d out=%+v", code, out)
	}
	if !out.Truncated {
		t.Errorf("truncated = false, want the upstream flag carried out so the UI can say 「這不是全部」")
	}
}

// 多埠要寫成多條 remote 配 remote-random（官方 python 的 OPENVPN_V2_TEMPLATE 就是這個結構）。
// nb4a 的解析器吃多条 remote（OpenVPNConfigFmt.kt 的 "remote" 分支是累加）。
func TestOpenvpnConfigWritesEveryPortAsARemote(t *testing.T) {
	cfg := openvpnConfig("se-01.protonvpn.net", []int{1194, 4569, 5060}, "udp", "PRIV", "CERT", "")
	for _, want := range []string{"remote se-01.protonvpn.net 1194 udp",
		"remote se-01.protonvpn.net 4569 udp", "remote se-01.protonvpn.net 5060 udp", "remote-random"} {
		if !strings.Contains(cfg, want) {
			t.Errorf("config missing %q:\n%s", want, cfg)
		}
	}
	if lines := len(cfg); lines == 0 {
		t.Error("empty config")
	}

	single := openvpnConfig("se-01.protonvpn.net", []int{443}, "tcp", "PRIV", "CERT", "")
	if strings.Contains(single, "remote-random") {
		t.Errorf("a single-port profile must not carry remote-random:\n%s", single)
	}
	if strings.Count(single, "\nremote ") != 1 {
		t.Errorf("remote lines = %d, want exactly 1:\n%s", strings.Count(single, "\nremote "), single)
	}
}

// --ports 的解析：非數字/越界的一律丢，全丢光時要用 --port，再沒有就是用法錯誤（exit 2）。
func TestParsePortListAndOvpnPortFlags(t *testing.T) {
	if got := parsePortList("1194, 4569 ,bogus,70000,1194"); !slices.Equal(got, []int{1194, 4569}) {
		t.Errorf("parsePortList = %v, want [1194 4569]", got)
	}
	if got := parsePortList(""); got != nil {
		t.Errorf("parsePortList(\"\") = %v, want nil", got)
	}

	dir := t.TempDir()
	var stdout, stderr bytes.Buffer
	code := runOvpn([]string{"--state", writeTestSession(t, dir), "--out-dir", dir,
		"--host", "x.protonvpn.net"}, &stdout, &stderr)
	if code != 2 {
		t.Fatalf("exit=%d, want a usage error when neither --port nor --ports is given", code)
	}
}
