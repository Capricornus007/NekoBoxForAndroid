package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// Proton's API is versioned through these headers; omitting them gets a v2
// response shape back, which is why they are not optional decoration.
const (
	protonAPIVersion = "3"
	protonAccept     = "application/vnd.proton.api.v3+json"
)

// errSessionExpired is the *last* stage, not the first: a 401/403 from the API first
// triggers a token renewal (refresh.go), and this error only stands when that renewal
// was impossible or itself rejected. Wording stays "log in again" because at that
// point it is the only thing left that can help.
var errSessionExpired = errors.New("stored session was rejected, please log in again")

// Field names and types follow Proton's own client models
// (ProtonVPN/android-app servers/api/{LogicalServer,ConnectingDomain}.kt), whose
// @SerialName annotations are the wire format of /vpn/logicals.
type logicalServer struct {
	ID              string  `json:"ID"`
	Name            string  `json:"Name"`
	State           string  `json:"State"`
	Load            int     `json:"Load"`
	Score           float64 `json:"Score"`
	Tier            int     `json:"Tier"`
	Features        int     `json:"Features"`
	Country         string  `json:"ExitCountry"`
	City            string  `json:"City"`
	Servers         []connectingDomain
	StatusReference struct {
		Penalty float64 `json:"Penalty"`
		Cost    int     `json:"Cost"`
	} `json:"StatusReference"`
}

// Server features, from the same constants file: bit 4 marks IPv6 support.
const featureIPv6 = 16

// featureSecureCore 是 Proton 標記 Secure-Core 的 bit，值 1。
// 這條不是推論，是實物證據：官方 APK ch.protonvpn.android 5.20.74.0（aapt2 badging 查實
// versionName='5.20.74.0'，反編譯產物在 jadx/sources）的
// com/protonvpn/android/servers/Server.java 裡逐條寫著
//
//	isSecureCoreServer() => a.a.d0(this.features, 1)
//	isIPv6Supported()    => a.a.d0(this.features, 16)   // 與上方 featureIPv6 = 16 對得上
//	isTor()              => d0(features, 2)   isP2pServer()     => d0(features, 4)
//	isStreamingServer()  => d0(features, 8)   isGatewayServer() => d0(features, 32)
//
// d0 的實作在 a/a.java：`return (i9 & i10) == i10`，就是掩碼測試。同一支檔、同一個 d0，
// 它給 IPv6 用的掩碼是 16，跟我們早已落地並驗證過的 featureIPv6 = 16 精確對上，代表這張
// bit 表跟既有那欄出自同一份模型，不是湊出來的數字。
const featureSecureCore = 1

type connectingDomain struct {
	EntryIP          string                     `json:"EntryIP"`
	Domain           string                     `json:"Domain"`
	Status           *int                       `json:"Status"`
	X25519PublicKey  string                     `json:"X25519PublicKey"`
	EntryPerProtocol map[string]serverEntryInfo `json:"EntryPerProtocol"`
}

type serverEntryInfo struct {
	IPv4  string `json:"IPv4"`
	Ports []int  `json:"Ports"`
}

// wireGuardEntry 取 WireGuard 那條 per-protocol 記錄。
// ⚠️ 鍵的大小寫不能寫死：2026-10-13 逆推官方 APK（ch.protonvpn.android 5.20.74.0）查實
//
//	客戶端查的是**大寫開頭**的 "WireGuard"——`ConnectingDomain.java:235` 用
//	`protocol.getApiName()` 當鍵，而 `getApiName()` 是「VPN 名＋傳輸名」直接串接
//	（所以那側還有 WireGuardUDP／WireGuardTCP／WireGuardTLS，Stealth 就是最後一把）。
//	我方先前只比對小寫 "wireguard" ⇒ 查表永遠落空、靜默退回 EntryIP＋預設埠 51820，
//	表面上「能用」其實是沒吃到協商過的埠。改成先精確命中、再不分大小寫逐鍵找，
//	兩種上游寫法都拿得到；真的查不到才走原本的 fallback。
//	2026-10-14 補第三步：既然現在會把協定能力輸出給 UI，就必須連 WireGuardUDP／
//	WireGuardTCP／WireGuardTLS 這種「VPN 名＋傳輸名」的鍵也當成 WireGuard 的記錄來用，
//	否則一邊宣稱 wireguard 可用、一邊又退回 EntryIP＋51820，等於自打嘴巴。
func (d connectingDomain) wireGuardEntry() (serverEntryInfo, bool) {
	if e, ok := d.EntryPerProtocol[protocolWireGuardName]; ok {
		return e, true
	}
	for k, e := range d.EntryPerProtocol {
		if strings.EqualFold(k, protocolWireGuardName) {
			return e, true
		}
	}
	return d.protocolEntryNamed(protocolWireGuardName)
}

const protocolWireGuardName = "wireguard"

// 協定正規化名。值全部小寫，Kotlin／UI 那側就拿這些字串當篩選鍵。
const (
	protocolOpenVPNName = "openvpn"
	protocolProTunName  = "protun"
	protocolStealthName = "stealth"
	protocolSmartName   = "smart"
)

// protocolOrder 決定 Protocols 輸出的順序：固定順序加上前綴去重，清單才有可比對性，
// App 那側也不會因為 Go map 的隨機迭代順序而每次畫面排序不同。
var protocolOrder = []string{
	protocolWireGuardName,
	protocolStealthName,
	protocolOpenVPNName,
	protocolProTunName,
	protocolSmartName,
}

// transportTLS 是 Transmission 那半段的其中一個值。官方 APK 的反編譯產物裡
// ProtocolSelection.STEALTH = new ProtocolSelection(VpnProtocol.WireGuard, TLS)，
// 而 getApiName() 是 `vpn.getApiName() + transmission.name()` 直接串接
// （transmission 是 UDP／TCP／TLS 三個值），所以「Stealth」在 wire format 上就是
// 鍵名尾端的 TLS，不是另一把獨立的金鑰。
const transportTLS = "tls"

// splitProtocolKey 把 EntryPerProtocol 的鍵拆成「VPN 名＋傳輸名」兩半，一律不分大小寫
// （教訓見上方 wireGuardEntry 的註解：鍵的大小寫不能寫死）。認不出來的 VPN 名就把整串
// 小寫當 VPN 名回傳，讓上游哪天新增協定時資訊先保留下來，而不是靜默丟掉。
func splitProtocolKey(key string) (vpn string, transport string) {
	lower := strings.ToLower(strings.TrimSpace(key))
	for _, name := range protocolOrder {
		if strings.HasPrefix(lower, name) {
			return name, strings.TrimPrefix(lower, name)
		}
	}
	return lower, ""
}

// protocolCaps 從一台伺服器的 per-protocol 鍵推導協定集合，順帶取出 Secure-Core。
// 三個刻意的设计：
//  1. X25519PublicKey 有值但 EntryPerProtocol 沒帶 WireGuard 鍵時也算 wireguard——
//     舊形狀的 payload（只有公鑰、沒有 per-protocol 表）不能退化成「這台沒有 WG」。
//  2. 尾端是 TLS 的鍵除了它自己的 VPN 名以外，還算一份 stealth（見上方 transportTLS 的證據）。
//  3. 結果去重、依 protocolOrder 排序；不認識的鍵名以小寫原樣排在最後。
func protocolCaps(d connectingDomain, ls logicalServer) ([]string, bool) {
	seen := make(map[string]bool, len(d.EntryPerProtocol)+1)
	if d.X25519PublicKey != "" {
		seen[protocolWireGuardName] = true
	}
	for key := range d.EntryPerProtocol {
		vpn, transport := splitProtocolKey(key)
		if vpn == "" {
			continue
		}
		seen[vpn] = true
		if transport == transportTLS {
			seen[protocolStealthName] = true
		}
	}
	protocols := make([]string, 0, len(seen))
	for _, name := range protocolOrder {
		if seen[name] {
			protocols = append(protocols, name)
		}
	}
	var unknown []string
	for name := range seen {
		known := false
		for _, want := range protocolOrder {
			if want == name {
				known = true
				break
			}
		}
		if !known {
			unknown = append(unknown, name)
		}
	}
	sort.Strings(unknown)
	protocols = append(protocols, unknown...)
	if len(protocols) == 0 {
		// 沒有線索就回 nil（JSON 端 omitempty 會整個省略欄位），不要吐一個空陣列
		// 讓 UI 誤會成「有清單但裡面沒東西」。
		return nil, ls.Features&featureSecureCore != 0
	}
	return protocols, ls.Features&featureSecureCore != 0
}

// providesProtocol 判斷某個鍵是否屬於某個正規化協定（stealth 認尾端 TLS 或鍵名本身）。
func providesProtocol(key, name string) bool {
	vpn, transport := splitProtocolKey(key)
	if vpn == name {
		return true
	}
	return name == protocolStealthName && transport == transportTLS
}

// transportRank 決定同一協定有好多把鍵時先採哪一條：沒後綴（最老也最正規的形狀）優先，
// 接著 UDP、TCP、TLS。排序後再比鍵名，是為了同一份 payload 每次解析都拿到同一個埠——
// Go map 的迭代順序是亂數，直接取第一把的話埠會跟著飄，測試也就沒法比對。
func transportRank(transport string) int {
	switch transport {
	case "":
		return 0
	case "udp":
		return 1
	case "tcp":
		return 2
	case transportTLS:
		return 3
	default:
		return 4
	}
}

// protocolEntryNamed 取該協定在 EntryPerProtocol 裡的記錄。
func (d connectingDomain) protocolEntryNamed(name string) (serverEntryInfo, bool) {
	var keys []string
	for key := range d.EntryPerProtocol {
		if providesProtocol(key, name) {
			keys = append(keys, key)
		}
	}
	if len(keys) == 0 {
		return serverEntryInfo{}, false
	}
	sort.Slice(keys, func(i, j int) bool {
		_, ti := splitProtocolKey(keys[i])
		_, tj := splitProtocolKey(keys[j])
		if ri, rj := transportRank(ti), transportRank(tj); ri != rj {
			return ri < rj
		}
		return keys[i] < keys[j]
	})
	return d.EntryPerProtocol[keys[0]], true
}

// dialProtocolOrder 是「這條線要實際拨出去」時挑協定記錄的順序，與 protocolOrder（報能力
// 用的順序）不同：stealth 擺最後，因為它的 per-protocol 記錄其實是上面某個協定的 TLS 變體
// （WireGuardTLS／OpenVPNTLS），基礎協定那把先選得到才輪到它。
var dialProtocolOrder = []string{
	protocolWireGuardName,
	protocolOpenVPNName,
	protocolProTunName,
	protocolSmartName,
	protocolStealthName,
}

type logicalServersResp struct {
	LogicalServers []logicalServer `json:"LogicalServers"`
}

type nodeView struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Load      int     `json:"load"`
	Score     float64 `json:"score"`
	Penalty   float64 `json:"penalty"`
	Tier      int     `json:"tier"`
	IPv6      bool    `json:"ipv6,omitempty"`
	Country   string  `json:"country,omitempty"`
	City      string  `json:"city,omitempty"`
	Endpoint  string  `json:"endpoint,omitempty"`
	Domain    string  `json:"domain,omitempty"`
	PublicKey string  `json:"wgPublicKey,omitempty"`
	Port      int     `json:"port,omitempty"`
	// Protocols 與 SecureCore 必須加在尾端：ProtonNode 那側與既有測試有按位置構造物件的
	// 呼叫端，插在中間會靜默錯位（欄位全部對到旁邊那個）。協定名是正規化後的小寫
	// （wireguard／stealth／openvpn／protun／smart，上游不認識的鍵名原樣小寫送出），
	// UI 的「依協定篩選」就以這欄為準；wgPublicKey 留空但 Protocols 有值的那種，
	// 就是「這台只能走別協定」而不是「壞掉的節點」。
	Protocols  []string `json:"protocols,omitempty"`
	SecureCore bool     `json:"secureCore,omitempty"`
}

type nodesOutput struct {
	OK      bool       `json:"ok"`
	Servers []nodeView `json:"servers,omitempty"`
	// Dropped counts servers that came back but cannot be used at all — no WireGuard
	// key *and* no per-protocol entry to dial, or the group is offline. A node that
	// only speaks OpenVPN/ProTun is NOT dropped: it lands in Servers with an empty
	// wgPublicKey and its protocols listed, so the UI can tell "few nodes" from
	// "broken response" without hiding perfectly dialable servers.
	Dropped int    `json:"dropped"`
	Error   string `json:"error,omitempty"`
	// Code is the machine-readable twin of Error. Error is prose meant for a human
	// (and Proton rewords things), so "route the user back to sign-in" must never be
	// decided by matching an English sentence. Only session_expired exists today.
	Code string `json:"code,omitempty"`
}

// codeSessionExpired means the stored uid/token was rejected outright. The one and
// only recovery is a fresh sign-in, which is why the app needs to recognise it.
const codeSessionExpired = "session_expired"

func runNodes(args []string, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("nodes", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "stored session to read (required)")
	limit := command.Int("limit", 0, "return at most this many usable nodes")
	country := command.String("country", "", "only this exit country, e.g. JP")
	apiURL := command.String("api-url", defaultProtonAPIURL, "API base URL")
	timeoutSec := command.Int("timeout", 30, "overall deadline in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}
	if *statePath == "" {
		fmt.Fprintln(stderr, "protonvpn: nodes: --state is required")
		return 2
	}

	cred, err := readCredentialFile(*statePath)
	if err != nil {
		return emitNodes(stdout, nodesOutput{Error: err.Error()})
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	resp, err := fetchLogicalServers(ctx, *apiURL, cred)
	if err != nil && errors.Is(err, errSessionExpired) {
		// 401/403 先當作「access token 到期」處理：用存著的 RefreshToken 換一張再試一次，
		// 換到的寫回狀態檔。只有連換都換不到、或換完仍然被拒，才是真的要重登。
		refreshed, rerr := refreshSession(ctx, *apiURL, *statePath, cred)
		switch {
		case rerr != nil && !isAuthRejection(rerr):
			// 網路斷/5xx 不能報成「工作階段失效」：那樣 App 會把人踢回登入頁，
			// 而他的帳密其實沒問題（2026-10-13 用戶看到的就是這種誤判的後果）。
			return emitNodes(stdout, nodesOutput{Error: rerr.Error()})
		case rerr != nil:
			return emitNodes(stdout, nodesOutput{Error: errSessionExpired.Error(), Code: codeSessionExpired})
		default:
			resp, err = fetchLogicalServers(ctx, *apiURL, refreshed)
		}
	}
	if err != nil {
		if errors.Is(err, errSessionExpired) {
			return emitNodes(stdout, nodesOutput{Error: err.Error(), Code: codeSessionExpired})
		}
		return emitNodes(stdout, nodesOutput{Error: err.Error()})
	}

	servers, dropped := selectNodes(resp, *country, *limit)
	if len(servers) == 0 {
		// Reporting an empty success would let the UI say "this account has no
		// servers", which is a different claim from "nothing in the list was usable".
		return emitNodes(stdout, nodesOutput{
			Dropped: dropped,
			Error:   fmt.Sprintf("no usable servers in the response (%d dropped)", dropped),
		})
	}
	return emitNodes(stdout, nodesOutput{OK: true, Servers: servers, Dropped: dropped})
}

func fetchLogicalServers(ctx context.Context, apiURL string, cred storedCredential) (*logicalServersResp, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(apiURL, "/")+"/vpn/logicals", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("x-pm-uid", cred.UID)
	req.Header.Set("x-pm-appversion", protonAppVersion)
	req.Header.Set("x-pm-apiversion", protonAPIVersion)
	req.Header.Set("Accept", protonAccept)

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	switch {
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return nil, errSessionExpired
	case res.StatusCode == http.StatusUnprocessableEntity:
		// 422 在 Proton 那邊幾乎一定是人類驗證（Code 9001）。節點清單這條路目前沒有
		// 站內解驗證碼的流程（那是 login 的功能），但至少要講出發生了什麼。
		// 不能吐 Proton 的原文：那句話裡含驗證網址，網址裡含挑戰 token，進了 Kotlin
		// 的日誌就等于把短效憑證外洩。
		body, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		var api struct {
			Code int `json:"Code"`
		}
		_ = json.Unmarshal(body, &api)
		if api.Code == hvAPIErrorCode {
			return nil, errors.New("Proton wants a CAPTCHA for this network before listing servers: sign in again from the Proton page")
		}
		return nil, fmt.Errorf("API returned HTTP %d (code %d)", res.StatusCode, api.Code)
	case res.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("API returned HTTP %d", res.StatusCode)
	}

	var out logicalServersResp
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode server list: %w", err)
	}
	if len(out.LogicalServers) == 0 {
		return nil, errors.New("API returned an empty server list")
	}
	return &out, nil
}

// selectNodes 丢掉的是「回來了但完全不可用」的那台：既沒有能拨的 WireGuard domain，
// 也沒有任何帶 per-protocol 條目的 domain。協定能力由 nodeView.Protocols 表達，所以
// 「只有 OpenVPN／ProTun」的伺服器要照常進清單（wgPublicKey 留空），不能算丟棄——
// Dropped 這欄的用途還是讓 UI 分得出「節點少」與「回應壞了」。
func selectNodes(resp *logicalServersResp, country string, limit int) ([]nodeView, int) {
	var usable []nodeView
	dropped := 0

	for _, ls := range resp.LogicalServers {
		if ls.State != "" && ls.State != "up" {
			dropped++
			continue
		}
		if country != "" && !strings.EqualFold(ls.Country, country) {
			continue
		}
		var chosen *connectingDomain
		wireGuardChosen := false
		for i := range ls.Servers {
			d := &ls.Servers[i]
			if d.X25519PublicKey == "" {
				continue
			}
			// A missing Status must not hide a node, so only an explicit 0 rejects it.
			if d.Status != nil && *d.Status == 0 {
				continue
			}
			chosen = d
			wireGuardChosen = true
			break
		}
		if chosen == nil {
			// 沒有可用的 WG domain 不等於這台不能用：退而選「有 per-protocol 條目、
			// 也講得出位址、又沒被明確標成離線」的那一台，靠 Protocols 說明它能用什麼。
			for i := range ls.Servers {
				d := &ls.Servers[i]
				if len(d.EntryPerProtocol) == 0 {
					continue
				}
				if d.Status != nil && *d.Status == 0 {
					continue
				}
				if d.EntryIP == "" && d.Domain == "" {
					continue
				}
				chosen = d
				break
			}
		}
		if chosen == nil {
			dropped++
			continue
		}
		protocols, secureCore := protocolCaps(*chosen, ls)
		entry, hasEntry := chosen.wireGuardEntry()
		if !hasEntry && !wireGuardChosen {
			// 非 WG 那條路：位址與埠要用它自己協定協商出來的記錄，不能沿用 EntryIP＋51820。
			// 依 dialProtocolOrder 挑（stealth 是別的協定的 TLS 變體，排最後）；上游塞了
			// 不認識的協定名時再照 protocols 的順序補一次，別靜默退回 EntryIP。
			for _, name := range dialProtocolOrder {
				if e, ok := chosen.protocolEntryNamed(name); ok {
					entry, hasEntry = e, true
					break
				}
			}
			if !hasEntry {
				for _, name := range protocols {
					if e, ok := chosen.protocolEntryNamed(name); ok {
						entry, hasEntry = e, true
						break
					}
				}
			}
		}
		usable = append(usable, nodeView{
			ID:         ls.ID,
			Name:       ls.Name,
			Load:       ls.Load,
			Score:      ls.Score,
			Penalty:    ls.StatusReference.Penalty,
			Tier:       ls.Tier,
			IPv6:       ls.Features&featureIPv6 != 0,
			Country:    ls.Country,
			City:       ls.City,
			Endpoint:   chosen.entry(entry, hasEntry),
			Domain:     chosen.Domain,
			PublicKey:  chosen.X25519PublicKey,
			Port:       chosen.port(entry, hasEntry, wireGuardChosen),
			Protocols:  protocols,
			SecureCore: secureCore,
		})
	}

	// Load is the utilisation number Proton shows users (0-100), and Score is the
	// same value Proton's own client ranks servers by: ServerManager2.kt says
	// "Sorted by score (best at front)" before sortedBy(Server::score), and the
	// placeholder for "no score yet" is 1_000_000.0, so lower is better.
	// Penalty is only a last key because a live capture of all 1610 servers for this
	// account returned no StatusReference at all, which makes it a constant zero.
	sort.SliceStable(usable, func(i, j int) bool {
		if usable[i].Load != usable[j].Load {
			return usable[i].Load < usable[j].Load
		}
		if usable[i].Score != usable[j].Score {
			return usable[i].Score < usable[j].Score
		}
		return usable[i].Penalty < usable[j].Penalty
	})
	if limit > 0 && len(usable) > limit {
		usable = usable[:limit]
	}
	return usable, dropped
}

// entry prefers the per-protocol IPv4, then the plain EntryIP, then the domain.
func (d connectingDomain) entry(entry serverEntryInfo, hasEntry bool) string {
	if hasEntry && entry.IPv4 != "" {
		return entry.IPv4
	}
	if d.EntryIP != "" {
		return d.EntryIP
	}
	return d.Domain
}

// port 取協定協商出來的埠。51820 是 WireGuard 的慣用預設埠，只有真的按 WG 選中時
// 才准 fallback 到它；OpenVPN／ProTun 那一側回 0（JSON 的 omitempty 會省略 port），
// 寧可讓 Kotlin 那側自己決定預設值，也不能憑空填一個 WG 的埠進去。
func (d connectingDomain) port(entry serverEntryInfo, hasEntry bool, wireGuard bool) int {
	if hasEntry {
		for _, port := range entry.Ports {
			if port > 0 && port <= 65535 {
				return port
			}
		}
	}
	if !wireGuard {
		return 0
	}
	return defaultWireGuardPort
}

const defaultWireGuardPort = 51820

func readCredentialFile(path string) (storedCredential, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return storedCredential{}, fmt.Errorf("read session: %w", err)
	}
	var cred storedCredential
	if err := json.Unmarshal(payload, &cred); err != nil {
		return storedCredential{}, fmt.Errorf("parse session: %w", err)
	}
	if cred.AccessToken == "" || cred.UID == "" {
		return storedCredential{}, errors.New("stored session is missing its token")
	}
	return cred, nil
}

func emitNodes(w io.Writer, out nodesOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}
