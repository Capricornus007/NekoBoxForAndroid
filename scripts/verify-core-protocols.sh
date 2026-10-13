#!/usr/bin/env bash
# 驗產物、不驗配置：build.sh 的 `-tags` 寫了不等於真的編進 libgojni.so。
#
# 2026-10-13 實測到的假成功：只在 tag 清單加 `with_openvpn`，host 端 `go build` 綠、
# 產物照樣出，但 `strings` 裡 `protocol/openvpn` 計數仍是 **0**——因為 libcore 有自己的
# `box_include.go`（直接 import `protocol/*`），**不 import sing-box 的 `include` 套件**，
# 而 sing-box 用 `//go:build with_openvpn` 守著的註冊碼就在 `include/openvpn.go` 裡。
# tag 因此對 libcore 完全空轉。修法是在 libcore 補 tag 守著的 endpoint／DNS transport
# 註冊（`box_include_openvpn.go` + `box_include_openvpn_stub.go`），雙向都量過：
# 有 tag → 8、無 tag → 0（基準：同支二進位裡 wireguard=7）。
#
# 這個腳本就是把那把尺搬到大學生產的產物上：對 AAR 裡每個 ABI 的 libgojni.so 數 marker，
# 缺任何一個就紅。marker 在完全 strip（`--strip-all`）之後仍存活，實測計數不變，
# 所以 `-ldflags="-s -w"` 不會造成假紅。
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <libcore.aar>" >&2
    exit 2
fi

aar=$1

if [ ! -f "$aar" ]; then
    echo "Error: AAR not found: $aar" >&2
    exit 1
fi

for command in unzip strings; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Error: required command is unavailable: $command" >&2
        exit 1
    fi
done

# 這些是「少了就代表核心被裁到、而 App 那頭還是會畫出選項」的清單，
# 涵蓋 sing-box 內建協定與 libcore 自己的橋接層（libcore/protocol/*）。
REQUIRED=(
    protocol/openvpn
    protocol/wireguard
    protocol/amneziawg
    protocol/tailscale
    protocol/juicity
    protocol/snell
    protocol/trusttunnel
    protocol/shadowquic
    protocol/byedpi
    protocol/loadbalance
    protocol/urltest
    protocol/group
    protocol/shadowsocks
    protocol/vless
    protocol/vmess
    protocol/trojan
    protocol/hysteria
    protocol/hysteria2
    protocol/tuic
    protocol/anytls
    protocol/shadowtls
    protocol/ssh
    protocol/tor
    protocol/tun
    protocol/direct
    protocol/block
    protocol/dns
    protocol/mixed
    protocol/redirect
    protocol/socks
    protocol/http
)

ABIS=${LIBCORE_ABIS:-arm64-v8a x86_64}

tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
unzip -o "$aar" -d "$tmp" >/dev/null

failed=0
for abi in $ABIS; do
    so="$tmp/jni/$abi/libgojni.so"
    if [ ! -f "$so" ]; then
        echo "::error::missing required native lib jni/$abi/libgojni.so" >&2
        failed=1
        continue
    fi

    missing=()
    for marker in "${REQUIRED[@]}"; do
        if [ "$(strings -a "$so" | grep -c -F "$marker")" -eq 0 ]; then
            missing+=("$marker")
        fi
    done

    if [ "${#missing[@]}" -gt 0 ]; then
        echo "::error::$abi libgojni.so 缺 ${#missing[@]} 個協定 marker：${missing[*]}" >&2
        failed=1
        continue
    fi

    printf '%s libgojni.so %d bytes，%d 個協定 marker 全數在（openvpn=%s wireguard=%s）\n' \
        "$abi" "$(stat -c %s "$so")" "${#REQUIRED[@]}" \
        "$(strings -a "$so" | grep -c -F protocol/openvpn)" \
        "$(strings -a "$so" | grep -c -F protocol/wireguard)"
done

exit "$failed"
