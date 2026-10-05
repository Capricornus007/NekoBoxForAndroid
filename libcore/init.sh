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

GOMOBILE_COMMIT="${GOMOBILE_COMMIT:-17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f}"

# 工具鏈只能是這顆 MatsuriDayo fork，不能換成 libcore 現在 import 的
# github.com/sagernet/gomobile：build.sh 用的 `-androidapi`、`-cache` 是這顆 fork
# 加的旗標，sagernet 版是 upstream 那套 bind（實測 v0.1.13 直接回
# `flag provided but not defined: -cache`，usage 裡只剩 -target/-classpath/-o）。
# 換來源等於把那批旗標整排打掉，還要連動改 build.sh 與 sidecar 的產物佈局。
#
# 但這顆的模組路徑是 golang.org/x/mobile，它啟動時要用 go/packages 定位自己的
# bind 套件（cmd/gobind/gen.go 的 packageDir），定位不到就在生成 binding 之前死：
#   unable to import bind: no Go package in golang.org/x/mobile/bind
# 而 build.sh 在 bind 之前一定要跑 `go mod tidy`。#181（798ac89a7）把
# assets_android.go 的 import 換成 github.com/sagernet/gomobile/asset 之後，
# 沒有任何 .go 檔再引用 x/mobile → tidy 把整條依賴剪掉（CI run #594/#669 紅在這）。
# 對策是 libcore/tools_keep.go：只在 `tools` 標籤下才參與編譯、實際上永遠不會被
# built 的檔案，唯一用途是逼 tidy 保留那條 require。
# 上游各家（hawkff 等）的 go.mod 也留著 x/mobile，差別只在他們的 build.sh 不跑
# tidy，所以那顆依賴是「擱著不發」而不是被剪掉。
if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ]; then
    rm -rf gomobile
    git init -q gomobile
    git -C gomobile remote add origin https://github.com/MatsuriDayo/gomobile.git
    git -C gomobile fetch --depth 1 origin "$GOMOBILE_COMMIT" || exit 1
    git -C gomobile checkout -q FETCH_HEAD || exit 1
    # Go 1.27 removed the "gotypesalias" GODEBUG knob; drop the now-rejected
    # directive so gomobile's go.mod loads under Go 1.27+ toolchains.
    sed -i '/gotypesalias/d' gomobile/go.mod
    pushd gomobile || exit

    # Fix: upgrade x/tools for Go 1.26+ compatibility
    go get golang.org/x/tools@latest || exit
    go mod tidy || exit

    pushd cmd || exit
    pushd gomobile || exit
    go install -v || exit
    popd || exit
    pushd gobind || exit
    go install -v || exit
    popd || exit
    popd || exit
    popd || exit
    rm -rf gomobile
    cp -f "$BIN_DIR/gomobile" "$TARGET_BIN/gomobile-matsuri"
    cp -f "$BIN_DIR/gobind" "$TARGET_BIN/gobind-matsuri"
fi

export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
