#!/bin/bash
set -e

echo "=== Building NekoBox ossDebug ==="

# 1. Build
./gradlew assembleOssDebug
echo "Build succeeded!"

# 2. Check adb
if ! command -v adb &>/dev/null; then
    echo "adb not found. Make sure Android SDK platform-tools is in PATH."
    exit 1
fi

# 3. Get device
DEVICE=$(adb devices | grep -v "^List" | grep -v "^$" | grep -v "offline" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "No Android device connected via adb."
    echo "Run 'adb devices' to check."
    exit 1
fi
echo "Device: $DEVICE"

# 4. Detect device ABI
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
echo "ABI: $ABI"

# 5. Find matching APK
APK_DIR="app/build/outputs/apk/oss/debug"
APK=""

if [ -n "$ABI" ]; then
    for f in "$APK_DIR"/*-"$ABI"-debug.apk; do
        [ -f "$f" ] && APK="$f" && break
    done
fi

# Fallback: try arm64-v8a, then armeabi-v7a
if [ -z "$APK" ]; then
    for abi in arm64-v8a armeabi-v7a x86_64 x86; do
        for f in "$APK_DIR"/*-"$abi"-debug.apk; do
            [ -f "$f" ] && APK="$f" && break 2
        done
    done
fi

if [ -z "$APK" ]; then
    echo "No matching APK found in $APK_DIR"
    ls "$APK_DIR"/*.apk 2>/dev/null
    exit 1
fi

echo "Installing: $(basename "$APK")"
adb install -r -d "$APK"

echo "=== Done! NekoBox installed successfully ==="