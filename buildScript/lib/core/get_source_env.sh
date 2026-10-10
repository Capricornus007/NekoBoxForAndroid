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
export COMMIT_SING_BOX="6f3422e78ab85f40d0e0fbde13ed74fe6380e3b0"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.15.0-alpha.10-mod.34"
export COMMIT_LIBNEKO="d5ae8b4d046a01a7686e43dda40ded4cda472fd8"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
# 2026-09-27 前推到 aceae72d：sing-box 上游帶進來的 transport/wireguard/device_peer_router.go
# 會呼叫 device.Peer.WritePackets（上游 da3fb92 "Add blocking per-peer packet injection" 才有的
# API），而 sing-box/go.mod 那側的 replace 本來就指到 aceae72d —— 這個 pin 留在 1eabb449 會造成
# 「本機用 sibling checkout 編得過、CI 用這個 commit 編不過」（CI 實測報 q.peer.WritePackets undefined）。
# 兩邊必須是同一個 commit。
export COMMIT_WIREGUARD_GO="5c8c2d946422ae687d4138b91bde45a3252dcd3d"
# 歷史：2026-09-27 曾推到 d5db986（吃下 upstream/dev 的 client API（SetKeepIdleConnections /
# CloseIdleConnections / ContextWithKeepSession），現值見下方 export。上游 6a3a24d 那個 port hopping 修復我方早已以
# 32c2895 合併、又在 a8f70b4 把目的地判斷從 IsFqdn 改成 IsDomain，所以 hop.go 那處衝突保留我方。
# 隔離副本 go build（host 與 android/arm64）＋ go vet 全 rc=0 才抬。
# 2026-10-04 抬到 b44e4aa：吃下 upstream f8e9941「Fix idle connection close racing with new streams」
# （已由我方 sing-juicity fork 合併推送），check-core-pins.sh 段 2 報「在尖端」。
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="1f4a0d0c6d9fe5da589f4b4bbf25391288c117ff"
export COMMIT_SING_JUICITY="b44e4aa2d6a033ab70371cbc3e7c39b54613bcaf"
export COMMIT_SING_TRUSTTUNNEL="3b04fc11a1cc9bc81379c93ced12615e63d20c98"
