# sing-box 1.15.x fork commit (Capricornus007/sing-box, branch 1.15.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="e0d900a5a872a15a7dd581d329c51b46c171e0cc"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.15.0-alpha.8-mod.11"
export COMMIT_LIBNEKO="d5ae8b4d046a01a7686e43dda40ded4cda472fd8"
# wireguard-go includes the fd-path I/O activity callback API used by newer
# sing-quic/quic-go integrations. This fork branch also fixes the callback to
# count only successful sends.
export COMMIT_WIREGUARD_GO="1eabb449181db31b4f61ea9b8ee11922e4930e20"
# sing-box and libcore both replace github.com/sagernet/sing-quic with the
# sibling checkout at ../../sing-quic. Pin and fetch it explicitly so clean CI
# runners do not accidentally depend on a developer machine's existing clone.
export COMMIT_SING_QUIC="a8f70b463df44aa40e329bf5447eff56b0f8c3d4"
export COMMIT_SING_JUICITY="df1b0f66af1986da23936c0e842184535856bdc7"
export COMMIT_SING_TRUSTTUNNEL="fecacba9d921e7df5c39e3bfed5b52898bbead2f"
