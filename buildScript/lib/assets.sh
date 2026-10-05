#!/bin/bash

set -e
set -o pipefail

# ⚠️ 2026-10-05：上游 SagerNet/sing-geosite 是**滾動發版**，舊 tag 會被刪掉。
#   實測本檔原先釘的 20260904020013 現在回 HTTP 404，把 CI 的 `Build OSS APK` 直接打紅
#   （curl: (22) The requested URL returned error: 404 → exit 22，不是編譯錯）。
#   geoip 那顆 20260712 目前仍 200。下次再紅就是同一個原因：抬版本＋換下面那串實際算出的
#   SHA256（必須真下載後 sha256sum 取，不能憑空填——本檔的校驗就是防上游偷換檔）。
GEOIP_VERSION="${GEOIP_VERSION:-20260712}"
GEOIP_SHA256="${GEOIP_SHA256:-d5b99682b8744cd8985812869afa6b2feedad01ab4fbbef65e16c68ad92bd29c}"
GEOSITE_VERSION="${GEOSITE_VERSION:-20261004053124}"
GEOSITE_SHA256="${GEOSITE_SHA256:-4874a7ec12508849ab113225f988ba5c56aa91159c3b43c3e23ad8cce0d3f2b8}"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

download_verified() {
  local url="$1" output="$2" expected="$3" tmp actual
  tmp="${output}.download"
  rm -f "$tmp"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$url" -o "$tmp"
  actual="$(sha256_tool "$tmp")"
  if [ "$expected" != "$actual" ]; then
    rm -f "$tmp"
    echo "Error: checksum mismatch for $output (expected $expected, got $actual)" >&2
    exit 1
  fi
  mv "$tmp" "$output"
}

DIR=app/src/main/assets/sing-box
rm -rf "$DIR"
mkdir -p "$DIR"
cd "$DIR"

####
echo VERSION_GEOIP=$GEOIP_VERSION
echo -n "$GEOIP_VERSION" > geoip.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geoip/releases/download/$GEOIP_VERSION/geoip.db" \
  geoip.db \
  "$GEOIP_SHA256"
xz -9 geoip.db

####
echo VERSION_GEOSITE=$GEOSITE_VERSION
echo -n "$GEOSITE_VERSION" > geosite.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geosite/releases/download/$GEOSITE_VERSION/geosite.db" \
  geosite.db \
  "$GEOSITE_SHA256"
xz -9 geosite.db
