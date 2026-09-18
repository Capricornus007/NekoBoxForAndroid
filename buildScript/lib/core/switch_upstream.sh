#!/bin/bash
# Apply buildScript/lib/core/upstream.conf:
#   1) ensure sing-box.<name> exists (clone remote if missing)
#   2) checkout the configured pin
#   3) retarget ../sing-box symlink for go.mod
#
# Ported from behindflower/NekoBoxForAndroid commit 7b819835e
# ("chore: bump to 1.4.2-mod-08 and add switchable sing-box upstream").
# NOTE: this fork's get_source_env.sh does not yet export SING_BOX_REPO /
# SING_BOX_TREE / SING_BOX_LINK / SING_BOX_DIR_NAME / NEKOBOX_ROOT, so the
# helper is not wired into get_source.sh here; it is kept as an operator tool.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Optional: pass ref1nd|starifly to override conf for this run and rewrite conf.
if [ "${1:-}" != "" ]; then
  case "$1" in
    ref1nd|reF1nd|starifly|old|rollback)
      export SING_BOX_UPSTREAM="$1"
      # normalize name written back to conf
      case "$1" in
        starifly|old|rollback) _write=starifly ;;
        *) _write=ref1nd ;;
      esac
      if [ -f "$SCRIPT_DIR/upstream.conf" ]; then
        sed -i "s/^SING_BOX_UPSTREAM=.*/SING_BOX_UPSTREAM=${_write}/" "$SCRIPT_DIR/upstream.conf"
        echo ">> upstream.conf -> SING_BOX_UPSTREAM=${_write}"
      fi
      ;;
    -h|--help)
      echo "Usage: $0 [ref1nd|starifly]"
      echo "  no args: apply upstream.conf"
      echo "  with arg: set conf + apply"
      exit 0
      ;;
    *)
      echo "unknown upstream: $1 (use ref1nd|starifly)" >&2
      exit 1
      ;;
  esac
fi

# shellcheck disable=SC1091
source "$SCRIPT_DIR/get_source_env.sh"

echo ">> upstream: ${SING_BOX_UPSTREAM_NAME}"
echo ">>   remote: ${SING_BOX_REPO}"
echo ">>   pin:    ${COMMIT_SING_BOX}"
echo ">>   tree:   ${SING_BOX_TREE}"

mkdir -p "$NEKOBOX_ROOT"
if [ ! -d "$SING_BOX_TREE/.git" ] && [ ! -f "$SING_BOX_TREE/.git" ]; then
  echo ">> clone ${SING_BOX_REPO} -> ${SING_BOX_TREE}"
  git clone "$SING_BOX_REPO" "$SING_BOX_TREE"
fi

git -C "$SING_BOX_TREE" remote get-url origin >/dev/null 2>&1 || \
  git -C "$SING_BOX_TREE" remote add origin "$SING_BOX_REPO"
if [ "$(git -C "$SING_BOX_TREE" remote get-url origin)" != "$SING_BOX_REPO" ]; then
  echo ">> retarget origin -> ${SING_BOX_REPO}"
  git -C "$SING_BOX_TREE" remote set-url origin "$SING_BOX_REPO"
fi

git -C "$SING_BOX_TREE" fetch --tags --force origin
# pin may be only on this remote
if ! git -C "$SING_BOX_TREE" cat-file -e "${COMMIT_SING_BOX}^{commit}" 2>/dev/null; then
  git -C "$SING_BOX_TREE" fetch --tags --force origin "$COMMIT_SING_BOX"
fi
git -C "$SING_BOX_TREE" checkout --detach "$COMMIT_SING_BOX"

# Point go.mod path at the active tree
if [ -e "$SING_BOX_LINK" ] && [ ! -L "$SING_BOX_LINK" ]; then
  echo ">> refuse to replace non-symlink ${SING_BOX_LINK}" >&2
  echo "   move it aside first (e.g. mv sing-box sing-box.manual.bak)" >&2
  exit 1
fi
ln -sfn "$SING_BOX_DIR_NAME" "$SING_BOX_LINK"

echo ">> sing-box -> $(readlink "$SING_BOX_LINK")"
echo ">> HEAD: $(git -C "$SING_BOX_LINK" rev-parse HEAD) ($(git -C "$SING_BOX_LINK" describe --tags --always 2>/dev/null || true))"
