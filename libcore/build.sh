#!/bin/bash

# 對齊 gradle.properties:9（`org.gradle.jvmargs=… -Dfile.encoding=UTF-8`）：那條只管 **Gradle 自己的
# JVM**，而本腳本是 shell 直接起 gomobile/javac，吃的是環境預設編碼。CI 容器與本機都可能
# LANG 未設（POSIX/C 環境），那時 javac 的預設 platform encoding 不是 UTF-8，非 ASCII 字串
# （生成的 Java 桩、註解、資源鍵）就會以錯編碼進 class 檔——這種壞法不會報錯，只在執行期變亂碼。
# JAVA_TOOL_OPTIONS 會被**任何**被啟動的 JVM 讀到，所以一條就蓋住整條鏈；它同時會印一行
# "Picked up JAVA_TOOL_OPTIONS: …" 到 stderr，這是正常現象，不是錯誤。
# 本腳本先前 `source ./env_java.sh || true` 那行其實是空轉（倉裡沒有 env_java.sh，被 || true 吞掉），
# 所以這裡是這條鏈上第一處真的設到編碼的地方。
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfile.encoding=UTF-8"

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

# 在编译时锁定singbox侧依赖；重试三次，擋 sum.golang.org 瞬態抖動
for i in 1 2 3; do
  go mod tidy && break || {
    if [ "$i" -eq 3 ]; then
      echo ">> ERROR: go mod tidy failed after 3 attempts" >&2
      exit 1
    fi
    echo ">> go mod tidy transient failure, retrying ($i/3)..." >&2
    sleep 3
  }
done

# 官方 sing-box 的 constant.Version 默认为 "unknown"，需经 ldflags -X 在链接期注入；
# 版本号取自 nb4a.properties 的 SINGBOX_VERSION（与 get_source.sh 克隆的源码版本一致）
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' ../nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi

export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION -extldflags=-Wl,-z,max-page-size=16384" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
