#!/bin/bash
# Sync byedpi native sources into the app from the ByeByeDPI repo's byedpi submodule
# (which tracks upstream https://github.com/hufrea/byedpi).
#
# Only the C sources / headers / LICENSE are copied — the Android app compiles them
# via app/src/main/cpp/CMakeLists.txt. Build files that belong to byedpi's own
# standalone build (Makefile, Dockerfile, dist/, README) are intentionally skipped.
#
# Usage:  scripts/sync-byedpi.sh [path-to-ByeByeDPI]
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BYEBYE="${1:-$PROJECT_ROOT/../ByeByeDPI}"
SRC="$BYEBYE/app/src/main/cpp/byedpi"
DST="$PROJECT_ROOT/app/src/main/cpp/byedpi"

[ -d "$SRC" ] || { echo "ERROR: byedpi source not found at $SRC"; echo "Pass the ByeByeDPI path as arg 1, and make sure its submodule is checked out:"; echo "  git -C '$BYEBYE' submodule update --init app/src/main/cpp/byedpi"; exit 1; }

echo ">> byedpi source: $SRC"
( cd "$SRC" && git describe --tags 2>/dev/null | sed 's/^/   version: /' || true )

echo ">> syncing *.c *.h LICENSE -> $DST"
cp -v "$SRC"/*.c "$DST"/
cp -v "$SRC"/*.h "$DST"/
cp -v "$SRC"/LICENSE "$DST"/

echo ">> done. Review changes with: git -C '$PROJECT_ROOT' diff app/src/main/cpp/byedpi"
