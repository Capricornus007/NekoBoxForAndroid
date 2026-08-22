#!/bin/bash
# One-command build: native core (libcore.aar) + Android APK.
# Assumes scripts/env.sh describes a valid SDK/NDK for this machine.
#
# Usage:  scripts/build-all.sh [gradle-task]
#   default gradle task: app:assembleFdroidRelease   (unsigned OSS variant, no secrets)
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
source scripts/env.sh

GRADLE_TASK="${1:-app:assembleFdroidRelease}"

echo "==================================================="
echo ">> Step 1/2: native core (this clones sing-box/libneko at the pinned"
echo "   commits from buildScript/lib/core/get_source_env.sh and runs gomobile bind)"
echo "==================================================="
./run lib core
[ -f app/libs/libcore.aar ] || { echo "ERROR: app/libs/libcore.aar was not produced"; exit 1; }
echo ">> libcore.aar: $(du -h app/libs/libcore.aar | cut -f1)"

echo "==================================================="
echo ">> Step 2/2: Android APK  ($GRADLE_TASK)"
echo "==================================================="
./gradlew "$GRADLE_TASK" --stacktrace

echo "==================================================="
echo ">> APK(s):"
find app/build/outputs/apk -name '*.apk' 2>/dev/null || echo "   (none found)"
