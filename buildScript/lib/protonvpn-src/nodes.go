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
	Status           *entryStatus               `json:"Status"`
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
// 這是「不分族」的版本（舊簽名，供單元測試與全量查看使用）；實際產清單時走
// protocolCapsFor，因為同一台機器現在會拆成 WG 一列、OpenVPN 一列，
// 每列只能宣稱自己那一族真的能用的協定。
func protocolCaps(d connectingDomain, ls logicalServer) ([]string, bool) {
	return protocolCapsFor(d, ls, func(string, string) bool { return true })
}

// protocolCapsFor 同上，但只統計 keep(vpn 名, 傳輸後綴) 為真的鍵。
// 三個刻意的设计（與舊版一致）：
//  1. X25519PublicKey 有值但 EntryPerProtocol 沒帶 WireGuard 鍵時也算 wireguard——
//     舊形狀的 payload（只有公鑰、沒有 per-protocol 表）不能退化成「這台沒有 WG」。
//     這條只对還收 raw wireguard 鍵的那一族生效（OpenVPN 列永遠不能宣稱有 WG 金鑰，
//     否則 Kotlin 那側「有沒有公鑰」的分支會把 OpenVPN 列送去建 WireGuard 剖面）。
//  2. 尾端是 TLS 的鍵除了它自己的 VPN 名以外，還算一份 stealth（見上方 transportTLS 的證據）。
//  3. 結果去重、依 protocolOrder 排序；不認識的鍵名以小寫原樣排在最後。
func protocolCapsFor(d connectingDomain, ls logicalServer, keep func(vpn, transport string) bool) ([]string, bool) {
	seen := make(map[string]bool, len(d.EntryPerProtocol)+1)
	wireGuardKept := keep(protocolWireGuardName, "")
	if d.X25519PublicKey != "" && wireGuardKept {
		seen[protocolWireGuardName] = true
	}
	for key := range d.EntryPerProtocol {
		vpn, transport := splitProtocolKey(key)
		if vpn == "" || !keep(vpn, transport) {
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
// openvpn 不在這一張表裡：現在它有自己的列（buildOpenVpnRow），位址與埠都要用它那一族的
// 條目，混進 WG 列就會出現「列上寫 WireGuard、實際生的是 OpenVPN 的 relay」那種錯位。
var dialProtocolOrder = []string{
	protocolWireGuardName,
	protocolProTunName,
	protocolSmartName,
	protocolStealthName,
}

type logicalServersResp struct {
	LogicalServers []logicalServer `json:"LogicalServers"`
	// StatusID 給後續的二進位狀態輪詢用（官方 android 的 getBinaryStatus、python 的
	// MixinEndpointV2.STATUS 都以它為入口），這裡先讀起來留著，不要讓它變成隱形的欄位。
	StatusID string `json:"StatusID"`
	// ResponseMetadata.ListIsTruncated：v2 在回應太大時會只給清單的一部分（官方兩支
	// 客戶端都用 x-pm-response-truncation-permitted 明確宣告吃得下截断，再用
	// IncludeID[] 補補漏）。我方沒有那條補漏的路，所以至少要讓呼叫端看得见「這不是全部」，
	// 不能把「被截斷的 1588 台」當成「這帳號就這些機器」。
	ResponseMetadata struct {
		ListIsTruncated bool `json:"ListIsTruncated"`
	} `json:"ResponseMetadata"`
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
	//
	// 現在一台機器可能出兩列：WireGuard 一列（id 原樣）與 OpenVPN 一列
	// （id 帶 "#openvpn" 後綴、wgPublicKey 一定留空）。每列的 Protocols 只寫**它自己
	// 那一族真的能拨的**協定，所以「按協定篩選」選 OpenVPN 點下去就會匯入 OpenVPN，
	// 不會出现「列出來的是 WG 剖面」那種錯位（用戶要的正是這個）。
	Protocols  []string `json:"protocols,omitempty"`
	SecureCore bool     `json:"secureCore,omitempty"`
	// Ports 與 Transport 必須跟在 SecureCore 之後（同上：位置構造物件的呼叫端）。
	// Ports 是這一列真的能拨的埠清單（OpenVPN 的 remote 清單，順序不具意義，
	// 官方模板也是配 remote-random 一起用）；Port 只留給「取第一個」的舊呼叫端。
	// Transport 是該列的傳輸（udp／tcp），OpenVPN 那一列一定要有，Kotlin 才知道
	// 該把 .ovpn 的 proto 寫成什麼；WireGuard 列留空（WG 的傳輸由埠決定）。
	Ports     []int  `json:"ports,omitempty"`
	Transport string `json:"transport,omitempty"`
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
	// MaxTier 是這次過濾用的帳號層級上限（0＝沒過濾）。UI 拿它決定要不要在清單上
	// 註明「已按免費層過濾」，也免得再用「少掉的節點＝壞了」來解讀 dropped。
	MaxTier int `json:"maxTier,omitempty"`
	// Code is the machine-readable twin of Error. Error is prose meant for a human
	// (and Proton rewords things), so "route the user back to sign-in" must never be
	// decided by matching an English sentence. Only session_expired exists today.
	Code string `json:"code,omitempty"`
	// Truncated 是上游自己說的「這次只給了一部分」（ResponseMetadata.ListIsTruncated）。
	// 帶上 WithEntriesForProtocols 之後回應比以前大得多，這個分支真的可能發生；
	// 不報出來就會讓 UI 把「被截斷的清單」講成「這個帳號的節點」。
	Truncated bool `json:"truncated,omitempty"`
}

// codeSessionExpired means the stored uid/token was rejected outright. The one and
// only recovery is a fresh sign-in, which is why the app needs to recognise it.
const codeSessionExpired = "session_expired"

// vpnInfo 是 Proton「帳號本身」的 VPN 資訊回包。端點與欄位取自官方 Android 客戶端
// （ProtonVPN/android-app）：api/ProtonVPNRetrofit.kt 的
//
//	@GET("vpn/v2") suspend fun getVPNInfo(): VpnInfoResponse
//
// 其 models/login/VPNInfo.kt 帶 MaxTier/PlanName/MaxConnect。實測（2026-10-26）
// GET /vpn/v2 未帶 token 回 401「Invalid access token」，路由存在。
// 免費層 MaxTier=1、Plus=2、Visionary/Business=3；MaxTier 可能整個缺席
// （官方模型把它宣告為 Int?），所以這裡用指標區分「0」與「沒給」。
type vpnInfo struct {
	MaxTier    *int   `json:"MaxTier"`
	PlanName   string `json:"PlanName"`
	MaxConnect int    `json:"MaxConnect"`
	Status     int    `json:"Status"`
}

type vpnInfoResponse struct {
	Code       int     `json:"Code"`
	VPN        vpnInfo `json:"VPN"`
	Subscribed int     `json:"Subscribed"`
	Delinquent int     `json:"Delinquent"`
}

// fetchVPNInfo 用存著的憑證讀帳號 VPN 資訊。401/403 照全倉慣例回報為工作階段過期，
// 讓呼叫端能觸發既有的換 token 重試路徑。
func fetchVPNInfo(ctx context.Context, apiURL string, cred storedCredential) (vpnInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(apiURL, "/")+"/vpn/v2", nil)
	if err != nil {
		return vpnInfo{}, err
	}
	applyProtonAuthHeaders(req, cred)

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return vpnInfo{}, err
	}
	defer res.Body.Close()

	switch {
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return vpnInfo{}, errSessionExpired
	case res.StatusCode != http.StatusOK:
		return vpnInfo{}, fmt.Errorf("API returned HTTP %d", res.StatusCode)
	}

	var parsed vpnInfoResponse
	if err := json.NewDecoder(res.Body).Decode(&parsed); err != nil {
		return vpnInfo{}, fmt.Errorf("decode vpn info: %w", err)
	}
	return parsed.VPN, nil
}

type tierOutput struct {
	OK      bool   `json:"ok"`
	MaxTier int    `json:"maxTier,omitempty"`
	Plan    string `json:"plan,omitempty"`
	Error   string `json:"error,omitempty"`
	Code    string `json:"code,omitempty"`
}

func emitTier(stdout io.Writer, out tierOutput) int {
	if err := json.NewEncoder(stdout).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}

// runTier 回答「這個帳號能用到第幾層節點」，給 App 在匯入前決定 --tier。
func runTier(args []string, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("tier", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "stored session to read (required)")
	apiURL := command.String("api-url", defaultProtonAPIURL, "API base URL")
	timeoutSec := command.Int("timeout", 30, "overall deadline in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}
	if *statePath == "" {
		fmt.Fprintln(stderr, "protonvpn: tier: --state is required")
		return 2
	}
	cred, err := readCredentialFile(*statePath)
	if err != nil {
		return emitTier(stdout, tierOutput{Error: err.Error()})
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	info, err := fetchVPNInfo(ctx, *apiURL, cred)
	if err != nil && errors.Is(err, errSessionExpired) {
		refreshed, rerr := refreshSession(ctx, *apiURL, *statePath, cred)
		switch {
		case rerr != nil:
			return emitTier(stdout, tierOutput{Error: errSessionExpired.Error(), Code: codeSessionExpired})
		default:
			info, err = fetchVPNInfo(ctx, *apiURL, refreshed)
		}
	}
	if err != nil {
		code := ""
		if errors.Is(err, errSessionExpired) {
			code = codeSessionExpired
		}
		return emitTier(stdout, tierOutput{Error: err.Error(), Code: code})
	}
	if info.MaxTier == nil {
		// 回包沒帶 MaxTier：講明，讓呼叫端選擇不過濾，別把「不知道」當成「免費層」。
		return emitTier(stdout, tierOutput{Error: "Proton did not report a plan tier"})
	}
	return emitTier(stdout, tierOutput{OK: true, MaxTier: *info.MaxTier, Plan: info.PlanName})
}

func runNodes(args []string, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("nodes", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "stored session to read (required)")
	limit := command.Int("limit", 0, "return at most this many usable nodes")
	country := command.String("country", "", "only this exit country, e.g. JP")
	tier := command.Int("tier", 0, "drop servers above this plan tier (0 = do not filter)")
	apiURL := command.String("api-url", defaultProtonAPIURL, "API base URL")
	timeoutSec := command.Int("timeout", 30, "overall deadline in seconds")
	entries := command.String("with-entries-for-protocols", strings.Join(logicalsProtocolEntries, ","),
		"comma separated protocol keys to ask Proton for in the server list; empty = the old shape without entries")
	openVpn := command.Bool("openvpn", true, "emit one extra row per server for its OpenVPN entry")
	openVpnDomain := command.Bool("openvpn-domain-fallback", false,
		"when upstream sends no OpenVPN per-protocol entry at all, derive the OpenVPN row from the server domain (what Proton's own .ovpn files use)")
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

	requested := splitProtocolList(*entries)
	resp, err := fetchLogicalServers(ctx, *apiURL, cred, requested)
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
			resp, err = fetchLogicalServers(ctx, *apiURL, refreshed, requested)
		}
	}
	if err != nil {
		if errors.Is(err, errSessionExpired) {
			return emitNodes(stdout, nodesOutput{Error: err.Error(), Code: codeSessionExpired})
		}
		return emitNodes(stdout, nodesOutput{Error: err.Error()})
	}

	// 預設埠是「那台機器自己沒給埠」時的備援，不是必要條件：clientconfig 拿不到
	// （離線、401 已經在上一條路處理過、上游改 shape）時照樣只用人家自己带的埠，
	// 兩邊都沒有就留 0 讓 App 决定，不憑空捏一個。
	defaults := clientConfigResponse{}
	if *openVpn {
		if cfg, cfgErr := fetchClientConfig(ctx, *apiURL, cred); cfgErr == nil {
			defaults = cfg
		}
	}

	servers, dropped := selectNodes(resp, *country, *limit, *tier, defaults, *openVpn, *openVpnDomain)
	truncated := resp.ResponseMetadata.ListIsTruncated
	if len(servers) == 0 {
		// Reporting an empty success would let the UI say "this account has no
		// servers", which is a different claim from "nothing in the list was usable".
		tierNote := ""
		if *tier > 0 {
			tierNote = fmt.Sprintf(" for plan tier %d", *tier)
		}
		return emitNodes(stdout, nodesOutput{
			Dropped:   dropped,
			MaxTier:   *tier,
			Truncated: truncated,
			Error:     fmt.Sprintf("no usable servers in the response (%d dropped)%s", dropped, tierNote),
		})
	}
	return emitNodes(stdout, nodesOutput{
		OK: true, Servers: servers, Dropped: dropped, MaxTier: *tier, Truncated: truncated,
	})
}

// splitProtocolList 把 --with-entries-for-protocols 那串拆乾淨：空字串代表「照舊形狀問」
// （不帶 WithEntriesForProtocols，等於退回舊行為），其餘項目去空白、丟掉空項。
func splitProtocolList(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// fetchLogicalServers 打官方目前兩支客戶端用的那一條：GET /vpn/v2/logicals 加上
// WithEntriesForProtocols（OpenVPN 的條目只在這條路上會出現，證據見 openvpn_servers.go
// 檔頭）。v2 被改路徑或拒收參數（404/405/400）時退回舊的 /vpn/logicals：那樣就沒有
// per-protocol 條目、OpenVPN 列會消失，但 WireGuard 清單還在，比整條功能紅掉好。
// 401/403 與 422 不能算「路徑被拒」：那些要嘛是工作階段、要嘛是驗證碼，得原樣上傳
// 給呼叫端走既有的換 token／提示重登的路徑。
func fetchLogicalServers(ctx context.Context, apiURL string, cred storedCredential, entries []string) (*logicalServersResp, error) {
	resp, err := requestLogicalServers(ctx, apiURL, cred, logicalsV2Path, logicalsQuery(entries))
	if err != nil && len(entries) > 0 && rejectedPath(err) {
		return requestLogicalServers(ctx, apiURL, cred, legacyLogicalsPath, "")
	}
	return resp, err
}

func requestLogicalServers(
	ctx context.Context, apiURL string, cred storedCredential, path string, query string,
) (*logicalServersResp, error) {
	endpoint := strings.TrimRight(apiURL, "/") + path
	if query != "" {
		endpoint += "?" + query
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	applyProtonAuthHeaders(req, cred)

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
	case res.StatusCode == http.StatusNotFound, res.StatusCode == http.StatusMethodNotAllowed,
		res.StatusCode == http.StatusBadRequest:
		// 讓上層分辨得出來「這條路徑/這組參數不存在」，那種情況才值得退回舊路徑；
		// 5xx 不能退（退過去也只是再錯一次，而且會蓋掉真正的錯誤）。
		return nil, &errUpstreamRejectedPath{status: res.StatusCode}
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
//
// 一台機器現在最多出兩列：
//   - WireGuard 列（id 原樣）：跟舊行為同一條選域邏輯，只是 Protocols 不再包含 openvpn
//     那族（那是另一列的事），這樣 UI 選「OpenVPN」筛出來的每一列點下去都是 OpenVPN。
//   - OpenVPN 列（id 加 "#openvpn"、wgPublicKey 一定空）：見 buildOpenVpnRow。
//     includeOpenVpn=false（--openvpn=false）時不生這一列，等於完全回到舊形狀。
//
// maxTier 是帳號層級上限（免費＝1）：Tier 高於它的伺服器是「這帳號連不上」的付費節點，
// 匯進來只會製造連不上的節點，一律丟棄（計進 dropped）。maxTier <= 0 表示不知道層級、
// 不過濾。層級／國家過濾發生在生列之前，所以兩種來源受同一條規則管。
func selectNodes(
	resp *logicalServersResp, country string, limit int, maxTier int,
	defaults clientConfigResponse, includeOpenVpn bool, openVpnDomainFallback bool,
) ([]nodeView, int) {
	var usable []nodeView
	dropped := 0

	// 上游這次有沒有給**任何 OpenVPN 條目**：完全沒有才啟用「用伺服器網域生 OpenVPN 列」
	// 那條退路（理由見 buildOpenVpnRow 的註解）。
	//
	// 注意條件是「沒有 OpenVPN 鍵」而不是「沒有任何 per-protocol 資料」：2026-10-27
	// 手機實測的實際回包裡，多數機器沒有條目、但有 121 台帶著尾端 TLS 的鍵（因此被
	// 認成 stealth）。用「有任何條目就關退路」當條件，會讓退路整個不啟動——那正是
	// 第一次上機時 OpenVPN 篩選仍然是空的原因。
	allowDomainFallback := openVpnDomainFallback
	for _, ls := range resp.LogicalServers {
		for _, d := range ls.Servers {
			for key := range d.EntryPerProtocol {
				if vpn, _ := splitProtocolKey(key); vpn == protocolOpenVPNName {
					allowDomainFallback = false
				}
			}
		}
		if !allowDomainFallback {
			break
		}
	}

	for _, ls := range resp.LogicalServers {
		if ls.State != "" && ls.State != "up" {
			dropped++
			continue
		}
		if maxTier > 0 && ls.Tier > maxTier {
			dropped++
			continue
		}
		if country != "" && !strings.EqualFold(ls.Country, country) {
			continue
		}
		wgRow, hasWireGuard := wireGuardRow(ls)
		ovpnRow, hasOpenVpn := buildOpenVpnRow(ls, defaults, includeOpenVpn && allowDomainFallback)
		if !hasWireGuard && !hasOpenVpn {
			dropped++
			continue
		}
		if hasWireGuard {
			usable = append(usable, wgRow)
		}
		// --openvpn=false 只是不出那一列，不改變「這台到底能不能用」的判定：
		// 把纯 OpenVPN 的機器計進 dropped 會讓 UI 誤解成「回應裡有壞掉的」。
		if hasOpenVpn && includeOpenVpn {
			usable = append(usable, ovpnRow)
		}
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
	// limit 數的是**列**不是台：一台兩協定就是兩列。App 那側目前不帶 --limit
	// （清單要全量），所以這不是用戶看得見的差異，但改這裡的人必須知道。
	if limit > 0 && len(usable) > limit {
		usable = usable[:limit]
	}
	return usable, dropped
}

// nodeViewFromServer 把一台 logical server 的「跟協定無關」那部分資料抄進一列。
// WG 列與 OpenVPN 列共用它，兩边才不會出現「同一台機器在清單上層級/空閒度不一樣」這種事。
func nodeViewFromServer(ls logicalServer) nodeView {
	return nodeView{
		ID:         ls.ID,
		Name:       ls.Name,
		Load:       ls.Load,
		Score:      ls.Score,
		Penalty:    ls.StatusReference.Penalty,
		Tier:       ls.Tier,
		IPv6:       ls.Features&featureIPv6 != 0,
		Country:    ls.Country,
		City:       ls.City,
		SecureCore: ls.Features&featureSecureCore != 0,
	}
}

// wireGuardRow 生「WireGuard 那一列」，選域規則與舊碼一致：
//  1. 先找有 X25519 公鑰、又沒被明確標成離線的 domain；
//  2. 找不到就退而選「有 per-protocol 條目、講得出位址、沒被標離線」的那台
//     （protun／smart／上游新增的陌生協定名都走這條，資訊保留下來而不是靜默丢掉）。
//     唯一被挪出去的是 openvpn 那一族——它現在由 buildOpenVpnRow 單獨生一列，
//     否則同一台機器會在清單上被宣稱成「能用 OpenVPN」的 WG 剖面。
//
// 回 false 代表這台真的沒有 WG 側可用的 domain（不是錯誤）。
func wireGuardRow(ls logicalServer) (nodeView, bool) {
	var chosen *connectingDomain
	wireGuardChosen := false
	for i := range ls.Servers {
		d := &ls.Servers[i]
		if d.X25519PublicKey == "" {
			continue
		}
		// A missing Status must not hide a node, so only an explicit false/0 rejects it.
		if d.Status.offline() {
			continue
		}
		chosen = d
		wireGuardChosen = true
		break
	}
	if chosen == nil {
		for i := range ls.Servers {
			d := &ls.Servers[i]
			if len(d.EntryPerProtocol) == 0 {
				continue
			}
			if d.Status.offline() {
				continue
			}
			if d.EntryIP == "" && d.Domain == "" {
				continue
			}
			// 只有 OpenVPN 鍵的 domain 不在這裡接手：那是另一列的工作。
			if caps, _ := protocolCapsFor(*d, ls, isWireGuardFamily); len(caps) == 0 {
				continue
			}
			chosen = d
			break
		}
	}
	if chosen == nil {
		return nodeView{}, false
	}
	protocols, secureCore := protocolCapsFor(*chosen, ls, isWireGuardFamily)
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
	view := nodeViewFromServer(ls)
	view.Endpoint = chosen.entry(entry, hasEntry)
	view.Domain = chosen.Domain
	view.PublicKey = chosen.X25519PublicKey
	view.Port = chosen.port(entry, hasEntry, wireGuardChosen)
	view.Protocols = protocols
	view.SecureCore = secureCore
	if ports := chosen.portsOf(entry, hasEntry); len(ports) > 0 {
		view.Ports = ports
	}
	return view, true
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

// portsOf 給 WG 列也帶上整份埠清單（與 OpenVPN 列同一個欄位語意：Port 只是第一個）。
// 沒協商出條目時回空，由 port() 那條 fallback 决定埠。
func (d connectingDomain) portsOf(entry serverEntryInfo, hasEntry bool) []int {
	if !hasEntry {
		return nil
	}
	return sanitizePorts(entry.Ports)
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
