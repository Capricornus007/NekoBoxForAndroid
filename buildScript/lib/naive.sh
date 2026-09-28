#!/bin/bash
# Extract the official naiveproxy client binary from klzgrad/naiveproxy plugin
# APKs and install it as a bundled native executable
# (app/executableSo/<abi>/libnaive.so).
#
# This lets NekoBox run NaïveProxy profiles without installing the separate
# external plugin (moe.matsuri.exe.naive). PluginManager.initNativeInternal
# resolves "naive-plugin" -> libnaive.so from nativeLibraryDir, mirroring the
# bundled Mieru mechanism, and falls back to the external APK plugin
# when the bundled binary is absent.
#
# Usage: ./run lib naive
set -e
set -o pipefail

# Pinned naiveproxy release. The SHA256 values below are for the downloaded
# plugin APKs for this exact version; update them in the same change as any
# NAIVE_VERSION bump.
NAIVE_VERSION="${NAIVE_VERSION:-v154.0.8037.49-2}"
NAIVE_SHA256_ARM64_V8A="${NAIVE_SHA256_ARM64_V8A:-7be4a93d502e6bb28f86aa77c7d140a214bb69747e2ed37fdda69d064c743948}"
NAIVE_SHA256_ARMEABI_V7A="${NAIVE_SHA256_ARMEABI_V7A:-58c973113275cccd20f9c48181a180489afae356a731a4c262ee9d48d941746f}"
NAIVE_SHA256_X86="${NAIVE_SHA256_X86:-f5a680abc70750606b7e58a497640b9d2588fa760bc0b00f9c40a8f4a26107bc}"
NAIVE_SHA256_X86_64="${NAIVE_SHA256_X86_64:-b27b346f72ac2c812c903209ece2b8bac15c61b6ec5f591e58839f7930c9d4a1}"
BASE="https://github.com/klzgrad/naiveproxy/releases/download/${NAIVE_VERSION}"

OUT="$(pwd)/app/executableSo"
WORK="$(pwd)/.naive-build"
mkdir -p "$WORK"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

expected_sha256_for_abi() {
  case "$1" in
    arm64-v8a) echo "$NAIVE_SHA256_ARM64_V8A" ;;
    armeabi-v7a) echo "$NAIVE_SHA256_ARMEABI_V7A" ;;
    x86) echo "$NAIVE_SHA256_X86" ;;
    x86_64) echo "$NAIVE_SHA256_X86_64" ;;
    *) echo "Error: unsupported ABI $1" >&2; exit 1 ;;
  esac
}

verify_sha256() {
  local file="$1" expected="$2" actual
  actual="$(sha256_tool "$file")"
  if [ "$expected" != "$actual" ]; then
    echo "Error: checksum mismatch for $(basename "$file") (expected $expected, got $actual)" >&2
    exit 1
  fi
}

# Map Android ABI -> naiveproxy plugin APK ABI tag (identical here).
extract_abi() {
  local abi="$1"
  local apk="naiveproxy-plugin-${NAIVE_VERSION}-${abi}.apk"
  local apk_path="$WORK/$apk"
  echo ">> fetching libnaive.so for $abi ($apk)"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$BASE/$apk" -o "$apk_path"
  verify_sha256 "$apk_path" "$(expected_sha256_for_abi "$abi")"

  mkdir -p "$OUT/$abi"
  # The plugin APK ships the client as lib/<abi>/libnaive.so. Extract only after
  # the downloaded APK matches the pinned SHA256.
  unzip -o -j "$apk_path" "lib/$abi/libnaive.so" -d "$OUT/$abi" >/dev/null
  if [ ! -f "$OUT/$abi/libnaive.so" ]; then
    echo "Error: libnaive.so not found in $apk" >&2
    exit 1
  fi
  chmod +x "$OUT/$abi/libnaive.so"
}

extract_abi "arm64-v8a"
extract_abi "armeabi-v7a"
extract_abi "x86"
extract_abi "x86_64"

echo ">> installed naive binaries:"
ls -la "$OUT"/*/libnaive.so
