#!/bin/bash
set -eo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
pushd "$PROJECT_ROOT/.."

####

# Keep the new compatibility sources separate from older fork checkouts.
# Reverse only our recorded overlay; unrelated dependency edits cause a clear
# failure instead of being discarded by a reset/clean.
prepare_overlay() {
  local directory="$1" repository="$2" commit="$3" patch="$4"
  if [ ! -d "$directory" ]; then
    git clone --no-checkout "$repository" "$directory"
    git -C "$directory" checkout --detach "$commit"
  fi
  (
    cd "$directory"
    local recorded
    recorded="$(git rev-parse --git-path neko-applied.patch)"
    if [ -f "$recorded" ]; then
      git apply --reverse --check "$recorded"
      git apply --reverse "$recorded"
      mv "$recorded" "$recorded.previous"
    fi
    if [ -n "$(git status --porcelain)" ]; then
      echo "Dependency $directory has local changes; preserve or remove them before preparing sources." >&2
      exit 1
    fi
    git cat-file -e "$commit^{commit}" 2>/dev/null || git fetch "$repository" "$commit"
    git checkout --detach "$commit"
    git apply --check "$patch"
    git apply "$patch"
    cp "$patch" "$recorded"
  )
}
prepare_overlay sing-box-114 https://github.com/SagerNet/sing-box.git "$COMMIT_SING_BOX" "$PROJECT_ROOT/buildScript/lib/core/compat_114.patch"
prepare_overlay sing-quic-114 https://github.com/SagerNet/sing-quic.git "$COMMIT_SING_QUIC" "$PROJECT_ROOT/buildScript/lib/core/compat_quic_114.patch"

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git checkout --detach "$COMMIT_LIBNEKO"
git restore --source="$COMMIT_LIBNEKO" --worktree -- .
popd

####

# AmneziaWG is fetched as the pinned official v3 Go module (see libcore/go.mod).

####

popd
