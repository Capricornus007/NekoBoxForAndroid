# sing-box 1.15.x fork commit (Capricornus007/sing-box, branch 1.15.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="3a6a1a5e581200444888129bb10eb5f7f503a4ca"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.15.0-alpha.9-mod.21"
export COMMIT_LIBNEKO="d5ae8b4d046a01a7686e43dda40ded4cda472fd8"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
# 2026-09-27 前推到 aceae72d：sing-box 上游帶進來的 transport/wireguard/device_peer_router.go
# 會呼叫 device.Peer.WritePackets（上游 da3fb92 "Add blocking per-peer packet injection" 才有的
# API），而 sing-box/go.mod 那側的 replace 本來就指到 aceae72d —— 這個 pin 留在 1eabb449 會造成
# 「本機用 sibling checkout 編得過、CI 用這個 commit 編不過」（CI 實測報 q.peer.WritePackets undefined）。
# 兩邊必須是同一個 commit。
export COMMIT_WIREGUARD_GO="aceae72d2393b19fd0a8c0e52925e0a1d376a7c1"
# 歷史：2026-09-27 曾推到 d5db986（吃下 upstream/dev 的 client API（SetKeepIdleConnections /
# CloseIdleConnections / ContextWithKeepSession），現值見下方 export。上游 6a3a24d 那個 port hopping 修復我方早已以
# 32c2895 合併、又在 a8f70b4 把目的地判斷從 IsFqdn 改成 IsDomain，所以 hop.go 那處衝突保留我方。
# 隔離副本 go build（host 與 android/arm64）＋ go vet 全 rc=0 才抬。
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="ce128b6e1a041aabecb7a28a57529ee8b2cbd40d"
export COMMIT_SING_JUICITY="df1b0f66af1986da23936c0e842184535856bdc7"
export COMMIT_SING_TRUSTTUNNEL="fecacba9d921e7df5c39e3bfed5b52898bbead2f"
