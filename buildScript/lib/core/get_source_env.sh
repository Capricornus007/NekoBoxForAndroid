# sing-box 1.14.x fork commit (Capricornus007/sing-box, branch 1.14.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="7066b2e26"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.14.0-beta.4"
export COMMIT_LIBNEKO="6a85c185d62435a5293ef70ac3b638ae3ee1efa7"
# sing-box 1.14.x は wireguard-go v0.0.5-0.20260717024847-6f5e8b1947ae を要求
export COMMIT_WIREGUARD_GO="6f5e8b1947ae"
