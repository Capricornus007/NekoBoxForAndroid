# sing-box 1.14.x fork commit (Capricornus007/sing-box, branch 1.14.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="d8bdd3eedd487e664402c5280935c784784fc5a8"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.14.0-beta.17-mod.5"
export COMMIT_LIBNEKO="ca7786f10ba67f7f0e679ec1de0809c5c5875537"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
export COMMIT_WIREGUARD_GO="50e7da80384befcf96c83a044505c78ed954b842"
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="7f405db6b742088e36b471f4bd0a4331ad5b1436"
export COMMIT_SING_JUICITY="19d4f674d8e7a4f4311adde332b8668041dff1c7"
export COMMIT_SING_TRUSTTUNNEL="305963e0cb8b4e00874797e306a0f355098c1654"
