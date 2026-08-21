# sing-box 1.14.x fork commit (Capricornus007/sing-box, branch 1.14.x).
# Pinned so CI builds are reproducible and so the LibCore cache key
# (golang_status hashes this file) invalidates when sing-box changes.
export COMMIT_SING_BOX="e7a9270e7ce61f8cc68ef27840ad8be57b826bf8"
# Human-readable sing-box version for the About screen. Pinned alongside the commit so the
# build does not depend on tags being present in the CI clone (git describe there only
# resolves a bare hash). Update this together with COMMIT_SING_BOX.
export VERSION_SING_BOX="1.14.0-beta.17"
export COMMIT_LIBNEKO="705cff5b0a6c144e6b737872ba6f3259955eab64"
# sing-box 1.14.x は wireguard-go v0.0.5-0.20260810121456-c6c8a831ef70 を要求
export COMMIT_WIREGUARD_GO="c6c8a831ef7091d564dad5452703c8e82b3c800e"
