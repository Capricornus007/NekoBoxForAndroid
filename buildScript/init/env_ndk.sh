#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

_NDK="$ANDROID_HOME/ndk/25.0.8775105"
# 脚本在 set -u 下被 source，未导出的变量要用 :- 兜底。
[ -f "$_NDK/source.properties" ] || _NDK="${ANDROID_NDK_HOME:-}"
[ -f "$_NDK/source.properties" ] || _NDK="${NDK:-}"
[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_HOME/ndk-bundle"

# 上面都没命中时，挑 $ANDROID_HOME/ndk/ 下版本号最大的一个。CI runner 和
# 本机的 NDK 版本目录名不固定，写死单个版本号太脆。
if [ ! -f "$_NDK/source.properties" ]; then
  _latest="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n1)"
  [ -n "$_latest" ] && _NDK="$_latest"
fi

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found."
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
