# sing-box 1.15.x fork commit (Capricornus007/sing-box, branch 1.15.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="8aa65f17c4861e72ba22a72a0ad40cfbee9beb90"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.15.0-alpha.10-mod.23"
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
export COMMIT_SING_TRUSTTUNNEL="ffbffe838a6f8f53c5e0b3bbebaefe166c5ffbb7"
