package main

// OpenVPN 節點清單的來源（這一檔存在的理由：舊做法把「能不能走 OpenVPN」整個漏掉了）。
//
// 實查官方開源客戶端的碼，2026-10-27：
//
//   1. **沒有 `GET /openvpn/v1/servers` 这种东西**。GitHub 全域 code search 查
//      "openvpn/v1/servers" 在 ProtonVPN 名下零命中；官方兩支目前仍在維護的客戶端
//      都是從 logicals 拿 OpenVPN 條目：
//      - ProtonVPN/win-app `src/Api/ProtonVPN.Api/ApiClient.cs` 常數
//        `LOGICALS_PROTOCOL_ENTRIES_PARAM_VALUE =
//        "WireGuardUDP,WireGuardTCP,WireGuardTLS,OpenVPNUDP,OpenVPNTCP"`，
//        拼進 `vpn/v2/logicals?SignServer=...&SecureCoreFilter=all&WithState=true
//        &WithEntriesForProtocols=<那串>`（同檔 `GetServers` 路徑）。
//      - ProtonVPN/android-app `api/ProtonVPNRetrofit.kt` 的
//        `@GET("vpn/v2/logicals") getServers(@Query("WithEntriesForProtocols") protocols)`，
//        呼叫端 `servers/UpdateServerListFromApi.kt` 送 `ProtocolSelection.REAL_PROTOCOLS.apiNames()`
//        （Android 端已把 OpenVPN 下架，所以它那串只有 WireGuard*/ProTun*；
//        要 OpenVPN 條目得按 win-app 那串問）。
//      - ProtonVPN/python-proton-vpn-api-core `proton/vpn/session/servers/server_list_fetcher.py`
//        的 `MixinEndpointV1.LOGICALS` / `MixinEndpointV2.LOGICALS` 也是同一個 logicals，
//        差別只在它沒問 per-protocol 條目。
//      ⇒ **我方舊碼的缺點就在這裡**：`nodes.go` 打的是不帶任何查詢參數的
//        `/vpn/logicals`，上游因此不回 `EntryPerProtocol`，`protocols` 恆空，
//        UI 的 `canUse("openvpn")` 永遠為 false（用戶實測 1588 台全空）。
//
//   2. 欄位形狀：`ProtonVPN/android-app servers/api/ConnectingDomain.kt` 的
//      `ServerEntryInfo(@SerialName("IPv4") ipv4, @SerialName("Ports") ports)` 與
//      `ConnectingDomain(@SerialName("EntryIP"), @SerialName("EntryPerProtocol") Map<String,ServerEntryInfo>,
//      @SerialName("Domain"), @SerialName("Status") isOnline: Boolean, @SerialName("X25519PublicKey"))`；
//      win-app `src/Api/ProtonVPN.Api.Contracts/Servers/EntryPerProtocolResponse.cs` 用
//      `[JsonProperty("OpenVPNUDP")] OpenVpnUdp` / `[JsonProperty("OpenVPNTCP")] OpenVpnTcp`
//      收同樣的鍵（它的 `PhysicalServerResponse.Status` 是 sbyte，而 Android 那側宣告成
//      Boolean ⇒ 同一個欄位兩種型別，我方必須兩種都吃得下，見 `entryStatus`）。
//
//   3. 「一台同時會 WG 與 OpenVPN」的對應方式：不是靠 `Metadata`、也不是靠 `Features` 位，
//      而是看同一台 logical server 的 `Servers[].EntryPerProtocol` 裡有沒有對應鍵。
//      官方判定函數就在 android-app `models/vpn/usecase/SupportsProtocol.kt`：
//      `supportsRealProtocol = getEntryIp(protocol) != null && (vpn ∉ {WireGuard,ProTun} || X25519 非空白)`。
//      win-app `.../Mappers/PhysicalServerMapper.cs` 的 `CreateRelayIpByProtocol` 同樣逐鍵比
//      `OpenVpnUdp?.Ipv4` / `OpenVpnTcp?.Ipv4`。⇒ **每協定有自己的 relay IPv4**，
//      OpenVPN 那側不能沿用 WireGuard 的 EntryIP。
//
//   4. 埠：優先級照官方。
//      - 每台機器自己的 `EntryPerProtocol[...].Ports`（android `ConnectingDomain.getEntryPorts`）；
//      - 沒有就取 `GET /vpn/v2/clientconfig` 的 `DefaultPorts.OpenVPN.{UDP,TCP}`
//        （android `@GET("vpn/v2/clientconfig") getAppConfig`；win-app `ApiClient.GetVpnConfig`
//        + `Contracts/VpnConfig/DefaultPortsResponse.cs`（`[JsonProperty("OpenVPN")]`）→
//        `Client/Settings/.../GlobalSettings.cs` 的 `OpenVpnUdpPorts/OpenVpnTcpPorts`；
//        python `proton/vpn/session/client_config.py` 的 `ClientConfig.from_dict` 讀
//        `apidata["DefaultPorts"]["OpenVPN"]`，`dataclasses/client_config/protocol_ports.py`
//        的 `ProtocolPorts.from_dict` 認 "UDP"/"TCP"/"TLS" 三個鍵）；
//      - 兩邊都沒有就留空，由 App 那側決定預設值（不憑空捏一個埠）。
//      兩支客戶端的 fallback 清單本身不一致（python 內建 `DEFAULT_CLIENT_CONFIG` 是
//      UDP [80,51820,4569,1194,5060]／TCP [443,7770,8443]，win-app `DefaultSettings.cs` 是
//      UDP [443,3389,8080,8443]／TCP [443,1194,4569,5060,80]），而且它們都把整份清單寫成
//      多條 `remote` 再加 `remote-random`（python `backend/networkmanager/protocol/openvpn/openvpn.py`
//      的 `OPENVPN_V2_TEMPLATE`）⇒ 順序不具意義，所以**我方不把任何一套硬編成「Proton 的埠」**，
//      只照上面那兩層真實資料。
//
//   5. 自由層能不能取：OpenVPN 條目跟 WG 出自同一個 logicals 回應、同一台機器，
//      能不能用只取決於帳號的 `Tier`（见 `nodes.go` 的 maxTier 過濾）與該台的
//      `EntryPerProtocol` 有沒有這一鍵，沒有另外一個「OpenVPN 專用端點」要解锁。
//      憑證那條路（`ovpn.go` 的 `POST /vpn/v1/certificate`）用的就是既有登入 token，
//      所以「登一次就自動拿到可用節點」在 OpenVPN 側同樣成立，不需要任何帳密。
//
// 「TLS 後綴的 OpenVPN 鍵」刻意不拿去拨號：win-app 只請求並映射 OpenVPNUDP/OpenVPNTCP
// 兩把（`VpnProtocol.cs`、`PhysicalServerMapper.cs` 也沒有 OpenVpnTls），Stealth 在官方模型
// 裡是 WireGuard＋TLS（android `ProtocolSelection.STEALTH`），而我方那份 .ovpn 模板沒有
// TLS 封裝層。寧可少列一列，也不能生一份「點下去連不上」的剖面。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
)

// logicals 這條路的三把路徑。v2 是官方兩支客戶端現在用的；舊的 /vpn/logicals 留作
// 「v2 被改路徑/拒收參數」時的退路（退路沒有 per-protocol 條目，等於回到舊行為，
// 但至少 WireGuard 清單還在）。
const (
	logicalsV2Path     = "/vpn/v2/logicals"
	legacyLogicalsPath = "/vpn/logicals"
	clientConfigPath   = "/vpn/v2/clientconfig"
)

// logicalsProtocolEntries 逐字抄 win-app 的 LOGICALS_PROTOCOL_ENTRIES_PARAM_VALUE：
// 那是實測會回 OpenVPN 條目的那一串。沒有把 android 那串的 ProTun* 併進來，因為
// 「win-app 那串加上 ProTunUDP/TCP/TLS」這個組合沒有任何官方客戶端用過，拿未驗證的
// 查詢去賭一個 1588 台的清單不值得；要 ProTun 的能力由 WireGuard* 那三把鍵代表
// （win-app `CreateRelayIpByProtocol` 就是把 ProTunUdp/Tcp/Tls 直接映射到 WireGuard* 的 IPv4）。
var logicalsProtocolEntries = []string{
	"WireGuardUDP", "WireGuardTCP", "WireGuardTLS",
	"OpenVPNUDP", "OpenVPNTCP",
}

// logicalsQuery 组裝官方那幾個參數。WithState=true 才會有 Load/Score（清單要按空閒度排），
// SecureCoreFilter=all 才不會把 Secure-Core 那批機器從回應裡消失（我方有 secureCore 那欄）。
func logicalsQuery(entries []string) string {
	values := url.Values{}
	values.Set("SecureCoreFilter", "all")
	values.Set("WithState", "true")
	if len(entries) > 0 {
		values.Set("WithEntriesForProtocols", strings.Join(entries, ","))
	}
	return values.Encode()
}

// entryStatus 是 Servers[].Status 這個欄位的兩張臉：android 的模型是 Boolean
// （`ConnectingDomain.isOnline`），win-app 的是 sbyte（`PhysicalServerResponse.Status`），
// 舊形狀（不帶條目的 /vpn/logicals）實測回的是 0/1。寫死任何一種都會讓另一種直接
// 让整个 JSON 解析失敗——那比漏一台嚴重得多（用戶看到的會是「清單抓不到」）。
type entryStatus struct {
	online bool
	known  bool
}

func (s *entryStatus) UnmarshalJSON(data []byte) error {
	text := strings.TrimSpace(string(data))
	s.known = true
	switch {
	case text == "true":
		s.online = true
	case text == "false":
		s.online = false
	default:
		var n float64
		if err := json.Unmarshal(data, &n); err != nil {
			return fmt.Errorf("status must be a boolean or a number, got %s", text)
		}
		s.online = n != 0
	}
	return nil
}

// offline 只在「明確標成不可用」時為真：欄位缺席不能把一台機器藏掉（舊註解同樣這條，
// 實測過 Proton 對某些群組根本不给 Status）。
func (s *entryStatus) offline() bool { return s != nil && s.known && !s.online }

// clientConfigResponse 只取埠那一小块（其他欄位與清單無關，上游加欄位也不會在這裡炸）。
type clientConfigResponse struct {
	Code         int `json:"Code"`
	DefaultPorts struct {
		OpenVPN   portsByTransport `json:"OpenVPN"`
		WireGuard portsByTransport `json:"WireGuard"`
	} `json:"DefaultPorts"`
}

type portsByTransport struct {
	UDP []int `json:"UDP"`
	TCP []int `json:"TCP"`
	TLS []int `json:"TLS"`
}

// openVpnPorts 取該傳輸的預設埠；只有真的 1..65535 的才算數。
func (c clientConfigResponse) openVpnPorts(transport string) []int {
	var raw []int
	switch transport {
	case transportUDP:
		raw = c.DefaultPorts.OpenVPN.UDP
	case transportTCP:
		raw = c.DefaultPorts.OpenVPN.TCP
	default:
		return nil
	}
	return sanitizePorts(raw)
}

// fetchClientConfig 是「best effort」：拿不到不該讓節點清單失敗（每台機器自己的
// EntryPerProtocol.Ports 才是主來源，這一欄只在它缺席時補）。唯一要往上傳的是
// 工作階段被拒，否則呼叫端會以為一切正常。
func fetchClientConfig(ctx context.Context, apiURL string, cred storedCredential) (clientConfigResponse, error) {
	var out clientConfigResponse
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(apiURL, "/")+clientConfigPath, nil)
	if err != nil {
		return out, err
	}
	applyProtonAuthHeaders(req, cred)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return out, err
	}
	defer res.Body.Close()
	switch {
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return out, errSessionExpired
	case res.StatusCode != http.StatusOK:
		return out, fmt.Errorf("client config returned HTTP %d", res.StatusCode)
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return out, fmt.Errorf("decode client config: %w", err)
	}
	return out, nil
}

// applyProtonAuthHeaders 把這支 sidecar 統一的認證／版本標頭貼上去。抽出來是因為
// logicals、clientconfig、certificate 三條路都要同一套，漏一個欄位的話 Proton 會
// 回舊版形狀或直接 401，那種錯最难归因。
func applyProtonAuthHeaders(req *http.Request, cred storedCredential) {
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("x-pm-uid", cred.UID)
	req.Header.Set("x-pm-appversion", protonAppVersion)
	req.Header.Set("x-pm-apiversion", protonAPIVersion)
	req.Header.Set("Accept", protonAccept)
}

const (
	transportUDP = "udp"
	transportTCP = "tcp"
)

// openVpnDialKey 把一台機器的 per-protocol 鍵分揀成「我方那份 .ovpn 真的能拨的」
// OpenVPN 條目：裸鍵與 UDP 後綴走 udp、TCP 後綴走 tcp，TLS 後綴直接不收（理由見檔頭
// 第 4 點）。回傳的順序是 udp 優先，跟 App 那側預設的傳輸一致。
func openVpnDialKey(key string) (transport string, ok bool) {
	vpn, suffix := splitProtocolKey(key)
	if vpn != protocolOpenVPNName {
		return "", false
	}
	switch suffix {
	case "", transportUDP:
		return transportUDP, true
	case transportTCP:
		return transportTCP, true
	default:
		return "", false
	}
}

// openVpnEntry 取这台機器該用哪個 OpenVPN 中繼位址與哪一種傳輸。
// 位址只用該條目自己的 IPv4（每協定一條 relay，見檔頭第 3 點）；真的空着才退到域名，
// 絕不退回 EntryIP——那是 WireGuard 的中繼，拿去拨 OpenVPN 就是連不上。
func (d connectingDomain) openVpnEntry() (serverEntryInfo, string, bool) {
	type candidate struct {
		key       string
		transport string
		rank      int
	}
	var candidates []candidate
	for key := range d.EntryPerProtocol {
		transport, ok := openVpnDialKey(key)
		if !ok {
			continue
		}
		rank := 1
		if transport == transportUDP {
			rank = 0
		}
		candidates = append(candidates, candidate{key: key, transport: transport, rank: rank})
	}
	if len(candidates) == 0 {
		return serverEntryInfo{}, "", false
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].rank != candidates[j].rank {
			return candidates[i].rank < candidates[j].rank
		}
		return candidates[i].key < candidates[j].key
	})
	chosen := candidates[0]
	entry, ok := d.EntryPerProtocol[chosen.key]
	if !ok {
		return serverEntryInfo{}, "", false
	}
	return entry, chosen.transport, true
}

// openVpnAddress 位址候選：per-protocol 的 IPv4 → 域名。
func (d connectingDomain) openVpnAddress(entry serverEntryInfo) string {
	if entry.IPv4 != "" {
		return entry.IPv4
	}
	return d.Domain
}

// sanitizePorts 過濾掉 0 與越界的埠，順帶去重：上游偶尔会把同一個埠列兩次，
// 複寫成兩條一樣的 remote 只是讓 OpenVPN 的重試清單变长。
func sanitizePorts(raw []int) []int {
	seen := make(map[int]bool, len(raw))
	out := make([]int, 0, len(raw))
	for _, port := range raw {
		if port <= 0 || port > 65535 || seen[port] {
			continue
		}
		seen[port] = true
		out = append(out, port)
	}
	return out
}

// buildOpenVpnRow 從一台 logical server 生「OpenVPN 那一列」。
// 回 false 的情況是這台沒有任何可拨的 OpenVPN 條目（不是錯誤：它可能只是台純 WG 機器）。
func buildOpenVpnRow(ls logicalServer, defaults clientConfigResponse) (nodeView, bool) {
	for i := range ls.Servers {
		d := &ls.Servers[i]
		if d.Status.offline() {
			continue
		}
		entry, transport, ok := d.openVpnEntry()
		if !ok {
			continue
		}
		address := d.openVpnAddress(entry)
		if address == "" {
			continue
		}
		ports := sanitizePorts(entry.Ports)
		if len(ports) == 0 {
			ports = defaults.openVpnPorts(transport)
		}
		port := 0
		if len(ports) > 0 {
			port = ports[0]
		}
		protocols, secureCore := protocolCapsFor(*d, ls, isOpenVpnFamily)
		if len(protocols) == 0 {
			// 理論上不會發生（有條目就有 openvpn），真出現了就是上游給了個認不出名的鍵；
			// 這種列宁可不生，也不要生一列 UI 筛不出來、又只能靠位置猜的資料。
			continue
		}
		view := nodeViewFromServer(ls)
		// id 加前後綴：同一台機器現在會生兩列（WG 一列、OpenVPN 一列），兩列同 id 的話
		// App 那側任何按 id 比對／去重的邏輯都會把它們當成同一台。
		view.ID = ls.ID + openVpnRowIDSuffix
		view.Endpoint = address
		view.Domain = d.Domain
		view.Port = port
		view.Ports = ports
		view.Transport = transport
		view.Protocols = protocols
		view.SecureCore = secureCore
		// PublicKey 刻意留空：Kotlin 那邊用「有沒有 WG 金鑰」判定這一列該走哪條匯入路，
		// 填了就會把 OpenVPN 列送去建 WireGuard 剖面。
		return view, true
	}
	return nodeView{}, false
}

const openVpnRowIDSuffix = "#openvpn"

// isOpenVpnFamily OpenVPN 那一列只收 openvpn 名的鍵（裸鍵、UDP、TCP、TLS 都算這一族，
// TLS 那把在 protocolCapsFor 裡會另外帶出 stealth，跟 WG 列各自計算）。
func isOpenVpnFamily(vpn, _ string) bool { return vpn == protocolOpenVPNName }

// isWireGuardFamily 除 openvpn 以外的鍵都留在 WG／舊行為那一列：wireguard、protun
// （win-app `CreateRelayIpByProtocol` 把 ProTun* 直接映射到 WireGuard* 的 IPv4）、
// smart、裸 stealth（android 的 ProtocolSelection.STEALTH 就是 WireGuard＋TLS），
// 以及上游不認識的鍵名——unknown 不收进任何一列的话，資訊會被靜默丢掉。
func isWireGuardFamily(vpn, _ string) bool { return vpn != protocolOpenVPNName }

// errUpstreamRejectedPath 只在「v2 路徑或查詢參數這個部署不認」時用來觸發退路；
// 401/403/422 不能算（那些要嘛是工作階段問題、要嘛是驗證碼，都得照原樣往上傳）。
type errUpstreamRejectedPath struct {
	status int
}

func (e *errUpstreamRejectedPath) Error() string {
	return fmt.Sprintf("API returned HTTP %d", e.status)
}

func rejectedPath(err error) bool {
	var e *errUpstreamRejectedPath
	return errors.As(err, &e)
}
