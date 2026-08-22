#!/bin/bash
# Deterministic build environment for ZYBC-VPN-Android.
# Usage:  source scripts/env.sh
#
# Adjust ANDROID_HOME / NDK_VERSION below if your machine differs, then everything
# else (get_source.sh, libcore/init.sh, env_ndk.sh, Gradle) picks these up.

# --- Android SDK (must contain: platforms/android-35, build-tools/35.0.1, cmake/3.22.1) ---
export ANDROID_HOME="${ANDROID_HOME:-/home/aiuser/workspace/SOFTWARE/AndroidSDK}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# --- NDK (buildScript/init/env_ndk.sh prefers 25.0.8775105, else falls back to this) ---
NDK_VERSION="${NDK_VERSION:-25.2.9519653}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export NDK="$ANDROID_NDK_HOME"

# --- Go / gomobile output on PATH ---
export PATH="$(go env GOPATH)/bin:$PATH"

# Sanity checks (non-fatal, just informative)
[ -d "$ANDROID_HOME/platforms/android-35" ] || echo "WARN: platforms/android-35 missing in $ANDROID_HOME"
[ -f "$ANDROID_NDK_HOME/source.properties" ] || echo "WARN: NDK not found at $ANDROID_NDK_HOME"
[ -d "$ANDROID_HOME/cmake/3.22.1" ] || echo "WARN: cmake/3.22.1 missing in $ANDROID_HOME"

echo "env: ANDROID_HOME=$ANDROID_HOME"
echo "env: ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
echo "env: go=$(go version 2>/dev/null | awk '{print $3}')  java=$(java -version 2>&1 | head -1 | awk -F'\"' '{print $2}')"
