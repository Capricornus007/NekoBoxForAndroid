# sing-box 1.14.x fork commit (Capricornus007/sing-box, branch 1.14.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="94adf308b3f9e843208a3f94d478131228257283"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.14.0-beta.17-mod.5"
export COMMIT_LIBNEKO="ca7786f10ba67f7f0e679ec1de0809c5c5875537"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
export COMMIT_WIREGUARD_GO="a9ace872067dcf7aca7da798e334820655b8adc0"
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="f2d9d4b93a5b1bd966b9ef1a3e6bd28d4195fee8"
export COMMIT_SING_JUICITY="2cfb90230367219ead326f8bfcb090ed08c3f9e0"
export COMMIT_SING_TRUSTTUNNEL="b96f8c0141bf5639147f427e2b3fb197aba93adc"
