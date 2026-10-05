#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi
BIN_DIR=$(go env GOBIN)
if [ -z "$BIN_DIR" ]; then
    BIN_DIR="$GOPATH/bin"
fi
TARGET_BIN="$GOPATH/bin"
mkdir -p "$TARGET_BIN"

# 工具鏈來源：github.com/sagernet/gomobile。
# （原本這裡抓的是 MatsuriDayo/gomobile@17d6af3；二進位檔名仍叫 *-matsuri，
#  build.sh 与其他 sidecar 脚本都引用這名字，改名只影響可讀性、不影響行為。）
#
# 為什麼必須換來源（2026-10-05 CI run #594/#669 實測根因）：
#   upstream #181 把 libcore 的 asset import 從 golang.org/x/mobile/asset
#   改成 github.com/sagernet/gomobile/asset（commit 798ac89a7）。x/mobile 就此
#   不被任何 .go 檔引用，而 build.sh 在 gomobile bind 之前一定要跑 `go mod tidy`
#   → tidy 把 golang.org/x/mobile 整條剪掉。MatsuriDayo 那顆 gobind 的模組路徑還是
#   golang.org/x/mobile，它啟動時要用 go/packages 定位自己的 bind 套件
#   （cmd/gobind/gen.go 的 packageDir()），查不到就死在生成之前：
#     unable to import bind: no Go package in golang.org/x/mobile/bind
#   所以這題不是網絡、不是冷快取、也不是 pin 過舊：是工具鏈找的路徑被 tidy 剪掉了。
#   sagernet 版找的是 github.com/sagernet/gomobile/bind —— 上層 libcore 自己就
#   require 同一個模組（asset 那行），兩邊永遠來自同一顆模組、同一個版本。
#
# 版本不再寫死在這裡，直接从 libcore/go.mod 讀：工具鏈与被綁定的函式庫綁死同一版，
# 讀不到就停（宁可紅，不要靜默用另一版生成 binding）。
GOMOBILE_VERSION="${GOMOBILE_VERSION:-$(awk '/^[[:space:]]*github\.com\/sagernet\/gomobile[[:space:]]/{print $2; exit}' go.mod)}"
if [ -z "$GOMOBILE_VERSION" ]; then
    echo ">> ERROR: libcore/go.mod 裡找不到 github.com/sagernet/gomobile 的 require 行" >&2
    echo "   gomobile 工具鏈版本由那行決定（與 libcore 實際 import 的那顆必須同版本）。" >&2
    exit 1
fi
echo ">> gomobile toolchain: github.com/sagernet/gomobile $GOMOBILE_VERSION"

if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ]; then
    rm -rf gomobile
    git init -q gomobile
    git -C gomobile remote add origin https://github.com/sagernet/gomobile.git
    git -C gomobile fetch --depth 1 origin "$GOMOBILE_VERSION" || exit 1
    git -C gomobile checkout -q FETCH_HEAD || exit 1
    # 不再额外 `go get golang.org/x/tools@latest`、也不再 sed 掉 gotypesalias：
    # 那兩條是替 MatsuriDayo 舊版（go 1.20 + godebug gotypesalias=0）續命的。
    # 實測 v0.1.13 的 go.mod 沒有 godebug 指令、x/tools v0.36.0 在 Go 1.27.1 下
    # 直接 `go install` 就過（本機 2026-10-05 驗），多抬 tools 尖端反而會讓
    # 生成器與 libcore 的 bind 運行期不同版。
    pushd gomobile || exit
    pushd cmd || exit
    pushd gomobile || exit
    go install -v
    popd || exit
    pushd gobind || exit
    go install -v
    popd || exit
    popd || exit
    popd || exit
    rm -rf gomobile
    cp -f "$BIN_DIR/gomobile" "$TARGET_BIN/gomobile-matsuri"
    cp -f "$BIN_DIR/gobind" "$TARGET_BIN/gobind-matsuri"
fi

export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
