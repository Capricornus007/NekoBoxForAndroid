#!/bin/bash
# Helper for pulling upstream updates. Does the SAFE, mechanical parts automatically
# and PRINTS (does not auto-apply) the sing-box/libneko pin bump, because bumping those
# can require code changes in libcore/ (see MAINTENANCE.md).
#
# Usage:  scripts/update-upstream.sh [path-to-ByeByeDPI]
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BYEBYE="${1:-$PROJECT_ROOT/../ByeByeDPI}"
PIN_FILE="$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"

echo "############ 1. byedpi (native DPI-bypass) ############"
if [ -d "$BYEBYE/.git" ]; then
  echo ">> updating byedpi submodule inside ByeByeDPI to its latest tracked commit"
  git -C "$BYEBYE" submodule update --init --remote app/src/main/cpp/byedpi
  "$PROJECT_ROOT/scripts/sync-byedpi.sh" "$BYEBYE"
else
  echo "!! ByeByeDPI repo not found at $BYEBYE — skipping byedpi update"
fi

echo ""
echo "############ 2. sing-box / libneko (VPN core) ############"
echo "Current pins ($PIN_FILE):"
grep -E 'COMMIT_(SING_BOX|LIBNEKO)' "$PIN_FILE" | sed 's/^/   /'
echo ""
echo ">> latest upstream commits (for reference — DO NOT blindly paste, read MAINTENANCE.md):"
echo -n "   MatsuriDayo/sing-box HEAD: "; git ls-remote https://github.com/MatsuriDayo/sing-box.git HEAD | cut -f1
echo -n "   MatsuriDayo/libneko  HEAD: "; git ls-remote https://github.com/MatsuriDayo/libneko.git  HEAD | cut -f1
echo ""
echo "To bump: edit COMMIT_SING_BOX / COMMIT_LIBNEKO in the pin file, delete the stale"
echo "checkouts (../sing-box ../libneko) or let get_source.sh re-checkout, then rebuild:"
echo "   scripts/build-all.sh"
echo ""
echo "WARNING: newer sing-box may have moved packages (protocol/* -> inbound/ + outbound/)"
echo "and removed nekoutils. If the core build fails on imports, that is a PORT, not a"
echo "version typo — see the 'Bumping the VPN core' section of MAINTENANCE.md."
