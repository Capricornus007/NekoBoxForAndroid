#!/bin/bash
# Build ossDebug and install to a connected device (adb) or WayDroid.
# Usage:
#   ./build-and-install.sh              # build + install
#   ./build-and-install.sh --skip-build # install existing APK only
#   ./build-and-install.sh --launch     # launch app after install
set -euo pipefail

SKIP_BUILD=0
LAUNCH=0
for arg in "$@"; do
    case "$arg" in
        --skip-build|-s) SKIP_BUILD=1 ;;
        --launch|-l) LAUNCH=1 ;;
        -h|--help)
            sed -n '2,7p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            exit 1
            ;;
    esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

PACKAGE_DEBUG="moe.sb4a.debug"
APK_DIR="app/build/outputs/apk/oss/debug"

pick_apk() {
    local abi="$1"
    local apk=""

    if [ -n "$abi" ]; then
        for f in "$APK_DIR"/*-"$abi"-debug.apk "$APK_DIR"/*-"$abi".apk; do
            [ -f "$f" ] && apk="$f" && break
        done
    fi

    if [ -z "$apk" ]; then
        for candidate in arm64-v8a armeabi-v7a x86_64 x86; do
            for f in "$APK_DIR"/*-"$candidate"-debug.apk "$APK_DIR"/*-"$candidate".apk; do
                [ -f "$f" ] && apk="$f" && break 2
            done
        done
    fi

    # universal fallback
    if [ -z "$apk" ]; then
        for f in "$APK_DIR"/*.apk; do
            [ -f "$f" ] && apk="$f" && break
        done
    fi

    printf '%s' "$apk"
}

install_via_adb() {
    if ! command -v adb >/dev/null 2>&1; then
        return 1
    fi

    # Try common WayDroid IP if nothing is listed yet
    if ! adb devices 2>/dev/null | grep -v '^List' | grep -qE $'\tdevice$'; then
        if command -v waydroid >/dev/null 2>&1; then
            waydroid adb connect >/dev/null 2>&1 || true
            sleep 1
        fi
        # common waydroid bridge IP
        adb connect 192.168.240.112:5555 >/dev/null 2>&1 || true
        sleep 1
    fi

    local device
    device="$(adb devices 2>/dev/null | grep -v '^List' | grep -E $'\tdevice$' | head -1 | awk '{print $1}')"
    if [ -z "$device" ]; then
        return 1
    fi

    local abi
    abi="$(adb -s "$device" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    echo "adb device: $device (ABI: ${abi:-unknown})"

    local apk
    apk="$(pick_apk "$abi")"
    if [ -z "$apk" ]; then
        echo "No matching APK in $APK_DIR"
        ls -la "$APK_DIR" 2>/dev/null || true
        exit 1
    fi

    echo "Installing via adb: $(basename "$apk")"
    adb -s "$device" install -r -d "$apk"
    if [ "$LAUNCH" -eq 1 ]; then
        adb -s "$device" shell monkey -p "$PACKAGE_DEBUG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
            || adb -s "$device" shell am start -n "$PACKAGE_DEBUG/io.nekohasekai.sagernet.ui.MainActivity" >/dev/null 2>&1 \
            || true
    fi
    return 0
}

install_via_waydroid() {
    if ! command -v waydroid >/dev/null 2>&1; then
        return 1
    fi

    local status
    status="$(waydroid status 2>/dev/null || true)"
    if ! echo "$status" | grep -q 'Container:[[:space:]]*RUNNING'; then
        echo "WayDroid container is not RUNNING."
        echo "$status"
        return 1
    fi

    local abi=""
    # waydroid prop may need root; best-effort
    abi="$(waydroid prop get ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
    if [ -z "$abi" ]; then
        # host is often same arch as waydroid session on desktop
        case "$(uname -m)" in
            x86_64|amd64) abi="x86_64" ;;
            aarch64|arm64) abi="arm64-v8a" ;;
            armv7*|armhf) abi="armeabi-v7a" ;;
            i386|i686) abi="x86" ;;
        esac
    fi
    echo "WayDroid install (ABI guess: ${abi:-unknown})"

    local apk
    apk="$(pick_apk "$abi")"
    if [ -z "$apk" ]; then
        echo "No matching APK in $APK_DIR"
        ls -la "$APK_DIR" 2>/dev/null || true
        exit 1
    fi

    echo "Installing via waydroid app install: $(basename "$apk")"
    waydroid app install "$apk"
    if [ "$LAUNCH" -eq 1 ]; then
        waydroid app launch "$PACKAGE_DEBUG" >/dev/null 2>&1 || true
    fi
    return 0
}

if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "=== Building NekoBox ossDebug ==="
    ./gradlew assembleOssDebug
    echo "Build succeeded."
else
    echo "=== Skipping build ==="
fi

if [ ! -d "$APK_DIR" ]; then
    echo "APK dir missing: $APK_DIR (build first)"
    exit 1
fi

echo "=== Installing ==="
if install_via_adb; then
    echo "=== Done (adb) ==="
    exit 0
fi

if install_via_waydroid; then
    echo "=== Done (waydroid) ==="
    exit 0
fi

echo "Install failed: no usable adb device and WayDroid install unavailable."
echo "Tips:"
echo "  adb devices"
echo "  waydroid status"
echo "  waydroid adb connect"
echo "  sudo waydroid container unfreeze   # if frozen"
exit 1
