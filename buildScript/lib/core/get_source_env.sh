# sing-box 1.14.x fork commit (Capricornus007/sing-box, branch 1.14.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="c656054282cea01a1f68259a016a0a2a84db3cf7"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.14.0-beta.17-mod.2"
export COMMIT_LIBNEKO="705cff5b0a6c144e6b737872ba6f3259955eab64"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
export COMMIT_WIREGUARD_GO="377796e2cbd666f047d885750cc534b39586da6a"
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="320612ff1f36935262a999e6f28da15201fe8938"
