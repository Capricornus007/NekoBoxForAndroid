#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1

####

# 从 nb4a.properties 读取官方 sing-box 版本（如 SINGBOX_VERSION=v1.13.15）
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi
echo ">> Using official sing-box $SINGBOX_VERSION"

OFFICIAL_REPO="https://github.com/SagerNet/sing-box.git"

pushd ..

if [ ! -d "sing-box" ]; then
  git clone --depth 1 --branch "$SINGBOX_VERSION" "$OFFICIAL_REPO" sing-box
else
  pushd sing-box
  # 历史遗留：本地可能是旧 fork（starifly/sing-box）的克隆，强制校正为官方仓库
  if ! git remote get-url origin 2>/dev/null | grep -qE "(SagerNet|sagernet)/sing-box"; then
    echo ">> Existing sing-box clone is not official, re-pointing to $OFFICIAL_REPO"
    git remote set-url origin "$OFFICIAL_REPO"
  fi
  git fetch --depth 1 origin "refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION" || \
    git fetch origin "refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION"
  git checkout -f "$SINGBOX_VERSION"
  popd
fi

popd
