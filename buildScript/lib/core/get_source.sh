#!/bin/bash
set -eo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
pushd "$PROJECT_ROOT/.."

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/starifly/sing-box.git
fi
pushd sing-box
git checkout --detach "$COMMIT_SING_BOX"
git restore --source="$COMMIT_SING_BOX" --worktree -- .
python3 "$PROJECT_ROOT"/buildScript/lib/core/patch_sing_box_awg.py "$(pwd)"
python3 "$PROJECT_ROOT"/buildScript/lib/core/patch_sing_box_balancers.py "$(pwd)"
python3 "$PROJECT_ROOT"/buildScript/lib/core/patch_sing_box_traffic.py "$(pwd)"
python3 "$PROJECT_ROOT"/buildScript/lib/core/patch_sing_box_dns.py "$(pwd)"
python3 "$PROJECT_ROOT"/buildScript/lib/core/patch_sing_box_direct_fallback.py "$(pwd)"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git checkout --detach "$COMMIT_LIBNEKO"
git restore --source="$COMMIT_LIBNEKO" --worktree -- .
popd

####

if [ ! -d "wireguard-go" ]; then
  git clone --no-checkout https://github.com/amnezia-vpn/amneziawg-go.git wireguard-go
fi
pushd wireguard-go
git checkout --detach "$COMMIT_WIREGUARD_GO"
git restore --source="$COMMIT_WIREGUARD_GO" --worktree -- .
# Keep import path compatible with sing-box (expects github.com/sagernet/wireguard-go).
sed -i '1s|^module .*|module github.com/sagernet/wireguard-go|' go.mod
grep -rl 'github.com/amnezia-vpn/amneziawg-go/' . | xargs sed -i 's|github.com/amnezia-vpn/amneziawg-go/|github.com/sagernet/wireguard-go/|g'
popd

####

popd
