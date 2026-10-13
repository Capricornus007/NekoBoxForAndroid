# sing-box 1.15.x fork commit (Capricornus007/sing-box, branch 1.15.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
# 2026-10-05 抬到 8e94ade9：#181 的 tailscale_change.go / tailscale_status.go 要吃
# BeginTailscaleExitNodeChange 與 adapter.TailscaleEndpointStatus.SelectedExitNodeID/IP，
# 那些 API 在 hawkff maintenance/native-stack 那條線上（cherry-pick 54564a44e 進來、
# 加上我方 modernize 修正），tag = v1.15.0-alpha.10-mod.26。
# 上一筆 d468ba64（mod.25）是 UDP 包緩衝空指標崩潰的修復，已含在這一筆的祖先裡。
# mod.27（020e8c3e0）= sing-box 1.15.x 整條吃下 hawkff/maintenance/native-stack 的 25 筆：
# 生命週期冪等（closeOnce）、AwneziaWG magic header 不再被抹掉、AWG 參數 CRLF 注入與金鑰
# 長度驗證、packet-up 拒絕非正數 sc_max_each_post_bytes，另外修掉 randStringFromCharset
# 的 byte 溢位死迴圈與 vless 加密握手失敗時的 typed-nil panic。
# mod.28（3bddc8ae6）= amneziawg 整條從 v1 換到 amneziawg-go/v3（按取捨規則：上游 v3 已含
# 我方 fork 的功能且更新），v3 會新拉 gvisor.dev/gvisor，因此 libcore 必須自己補上
# 那條 replace => Capricornus007/gvisor-awg；同一輪 check-core-pins.sh 段 1e 又抓到
# libcore 一直在編「上游 SagerNet 原版 tailscale」（缺 fork replace），一并補齊。
# mod.30（4fdc0a83d）= 吃下 upstream/testing 39 筆（b93f56a7a..fe92ab3e7，含 stable 的 3 筆）：
# tailssh 的 SSH banner 修正、redirect.go 改用 DNSModeOrDefault、sing-tun 的 require 抬到上游
# NAT 重做之後那版、iOS roothide 一行、masque 與 migration 文件精簡。route.go／wireguard／
# tailscale 那 29 檔衝突全部取我方（上游那些是同一邏輯的簡化版或會把 UDP 緩衝快取兩次）。
# mod.31（04b20057）= sing-tun 的 fork replace 抬到 69b8f1a：我們的 sing-tun fork 合併了
# upstream/dev 27 筆（Fix Android VPNService kernel bypass、fully functional auto redirect for
# Android、iptables DNS hijack 被 input connmark 跳過、go stack 重寫、arm64 NEON checksum 等），
# libcore 的 require 同時對齊 sing-box 的 7539c98。這批全是 Android 資料路徑上的修正，值得為它
# 多跑一輪核心建置。
# mod.33（e008139b）→ mod.34（6e9f774a）→ 尖端 6f3422e7：吃進 upstream/testing 的 39 筆
# （forward NAT 重做成 UDP mapping + fragment、per-destination backpressure、protocol input
# validation、scope cleanup 忽略 closed/canceled、naiveproxy 154.0.8037.49-2 等）。
# 抬到尖端的另一個原因是 libcore 已經把 sing-tun 換到 6d0ca107，而 sing-box 只有在
# mod.33 之後的 go.mod 才帶同一條 replace；兩邊不一致時 APK 吃 libcore 那版、
# sing-box 自己 CI 測的是另一版，scripts/check-core-pins.sh 段 1b/1e 就是抓這個的。
# mod.35（34727ed7）：吃進 eBPF inbound（cgroup+TC 引擎 10589 行 + protocol/ebpf 適配層）
# 與 `ebpf-probe` CLI。go.mod 只多一個 github.com/cilium/ebpf v0.22.0，三條 replace 指針
# （wireguard-go / sing-quic / sing）與 mod.34 完全相同，所以 check-core-pins.sh 段 1 不會
# 因為這次抬版變紅。
# mod.36（c1dfeafd）：修 `route.cachePacketBuffers` 把同一批 packet buffer 迭代兩次的缺陷
# ——第二段拿到的是已歸池、Buffer 欄位已被清空的物件，IncRef() 直接打 nil。用戶機
# com.nb4a:bg 實測閃退兩次（20:11:32／20:19:49）就是它，logcat 裡是 Go panic 不是 native
# crash。來源是合併 upstream/testing 時兩邊都留（上游的內聯迴圈 + 我方抽出来的 helper 呼叫）。
# 這個缺陷自 67a13e407 起就在我們所有建置裡，所以裝機上那版 1.4.4-mod-10 也帶著它。
# mod.37（0bd6e8e5）：⚠️ 這筆是**死代碼、已被 mod.38 回退**。它想把 libbox 的「刷介面清單失敗」
# 降成 Debug，但條件 `!UsePlatformNetworkInterfaces()` 恆為 false（platformDefaultInterfaceMonitor
# 只由 platformInterfaceWrapper 建立，而該 wrapper 寫死 return true），而且 NB4A 走的是自己那份
# libcore/interface_monitor.go，根本沒經過 libbox。當時對 netlinkrib 病因的判斷也錯了，見 mod.38。
# mod.38（f0c8a4f1）：回退 mod.37。真正的病因在宿主端——libcore 的
# boxPlatformInterfaceWrapper 一直回報「不支援平台介面清單」（上游 metaphore 那行
# `Interfaces() = errors.New("wtf")` 的遺留），核心只好每次退回 Go 的 net.Interfaces()，
# 而 Android 不給第三方 App bind netlink route socket（b/155595000）→ 每 3 秒一條
# `ERROR network: update interfaces: ... netlinkrib: permission denied`，且介面清單永遠是空的：
# bind_interface、network= 規則、default_network_strategy、閘道偵測全部拿不到資料。
# 修法是接上 Kotlin 早就寫好的 getInterfaces()，落在 nb4a 這側（同一筆提交），不是調日誌等級。
# mod.39（3c9399de）：xhttp packet-up 上傳中斷時不再丟棄真正的錯。之前上傳 goroutine 只
# `uploadPipeReader.Interrupt()` 就 return，呼叫端永遠只看到無從查考的 `io.ErrClosedPipe`；
# 補上 cause 之後同一條飄紅立刻變成 `xHTTP packet-up POST failed: use of closed network connection`。
# mod.2（e9c87b01）：sing-box 把 11 條 fork replace 抬到各 fork 尖端（那批尖端就是 10-11
# 「Go 吃 feed 尖端 + go 指令抬 1.28」的提交），APK 裡跑的才不再停在 09-30～10-09 那份。
# mod.3（53b70f4）：route 吃 sing 的 PacketBuffer 雙重歸還修復，並讓 cachePacketBuffers
# 自己擋掉 nil—— bufio.NewCachedPacketConn 首行 IncRef() 拿 nil 就是空指標 panic（裝置端
# tombstone 那族「兩格 unknown、沒有 Abort message」的根）。tag 已打：v1.15.0-alpha.12-mod.3。
export COMMIT_SING_BOX="53b70f4edabf93351143d08f1c2e6c8799750112"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
# ⚠️ 基號跟著「上游 tag 是否已被 1.15.x 包含」走，不是跟著上一次寫的字串走：
# 上游 v1.15.0-alpha.12（25b0d5b）自 3f2543f7a 那筆 additively 合併起就在我們歷史裡
# （git tag --contains v1.15.0-alpha.12 可驗），所以基號換到 alpha.12；mod.1 已經打在
# 3f2543f7a 上，后兩筆是 mod.2（e9c87b01）與 mod.3（53b70f4）。抬基號時這裡與 nb4a.properties 的
# SINGBOX_VERSION 要一起改，scripts/check-core-pins.sh 段 1c 就是抓這個的——
# 「Update sing-box core」那條 bot 只会盲目 +1 計數（它上一輪還寫 alpha.11-mod.41，
# 就是沒跟上基號換代），換代要人對。
export VERSION_SING_BOX="1.15.0-alpha.12-mod.3"
export COMMIT_LIBNEKO="1dad84547be59ff2ec5ed7bd2339b0441929edfd"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
# 2026-09-27 前推到 aceae72d：sing-box 上游帶進來的 transport/wireguard/device_peer_router.go
# 會呼叫 device.Peer.WritePackets（上游 da3fb92 "Add blocking per-peer packet injection" 才有的
# API），而 sing-box/go.mod 那側的 replace 本來就指到 aceae72d —— 這個 pin 留在 1eabb449 會造成
# 「本機用 sibling checkout 編得過、CI 用這個 commit 編不過」（CI 實測報 q.peer.WritePackets undefined）。
# 兩邊必須是同一個 commit。
export COMMIT_WIREGUARD_GO="b25b023a3c93d7ca0b2833612618e9879a33e439"
# 歷史：2026-09-27 曾推到 d5db986（吃下 upstream/dev 的 client API（SetKeepIdleConnections /
# CloseIdleConnections / ContextWithKeepSession），現值見下方 export。上游 6a3a24d 那個 port hopping 修復我方早已以
# 32c2895 合併、又在 a8f70b4 把目的地判斷從 IsFqdn 改成 IsDomain，所以 hop.go 那處衝突保留我方。
# 隔離副本 go build（host 與 android/arm64）＋ go vet 全 rc=0 才抬。
# 2026-10-04 抬到 b44e4aa：吃下 upstream f8e9941「Fix idle connection close racing with new streams」
# （已由我方 sing-juicity fork 合併推送），check-core-pins.sh 段 2 報「在尖端」。
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
# 2026-10-13 整批抬尖端（用戶規則 16「相關倉更新後要把新版本帶給 nb4a」）：
# libneko／wireguard-go／sing-quic／sing-juicity／sing-trusttunnel 五條原本都卡在
# 10-11「CI 改吃 feed 的 Go 尖端 + go 指令抬 1.28」那筆之前。其中
# wireguard-go 與 sing-quic 是 check-core-pins.sh 段 1 的硬配對，所以同輪把
# sing-box 的 go.mod 與 libcore/go.mod 一起抬（見 sing-box e9c87b01）。
# trusttunnel 那筆是它自己合併上游 dev 3 條之後的 f90a48e。
export COMMIT_SING_QUIC="8947bb00ad6f5a49f7213318d97766b90cd30eae"
export COMMIT_SING_JUICITY="8d0e56af704e38b4fc6a6233112fcb9e998485f2"
export COMMIT_SING_TRUSTTUNNEL="f90a48e591d26e39d141c989ced07dea73280483"
