# sing-box 1.15.x fork commit (Capricornus007/sing-box, branch 1.15.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="52139a98beaf5ef6859cb293140f04efd45dc491"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.15.0-alpha.2-mod.1"
export COMMIT_LIBNEKO="d5ae8b4d046a01a7686e43dda40ded4cda472fd8"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
export COMMIT_WIREGUARD_GO="c0bd5749db21a24417e47091b86d920d41d035ce"
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="2f044ea1dc3da1bb69fa28ad6ca8c2e6a38c7eca"
export COMMIT_SING_JUICITY="54c9819c172c41685e2fbae32c0607e07ec3d82c"
export COMMIT_SING_TRUSTTUNNEL="c4dda77c5189d838784c9b060dd3c2f693e06896"
