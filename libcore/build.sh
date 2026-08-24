#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# 在编译时锁定singbox侧依赖
go mod tidy || exit 1

# 官方 sing-box 的 constant.Version 默认为 "unknown"，需经 ldflags -X 在链接期注入；
# 版本号取自 nb4a.properties 的 SINGBOX_VERSION（与 get_source.sh 克隆的源码版本一致）
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' ../nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi

export GOBIND=gobind-matsuri
# Go 1.27 -buildmode=c-shared emits a non-PIC relocation against
# golang.org/x/net/http2.(*Transport).connPool that the NDK linker rejects on
# x86/x86_64 (both NDK r23 and r27). arm64-v8a/armeabi-v7a link fine, so target
# only the real-device ABIs; x86/x86_64 emulator ABIs are dropped intentionally.
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -target=android/arm64,android/arm -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION -extldflags=-Wl,-z,max-page-size=16384" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
