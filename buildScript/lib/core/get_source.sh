#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

pushd .. >/dev/null

#### sing-box ####
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi
echo ">> Using official sing-box $SINGBOX_VERSION"

OFFICIAL_REPO="https://github.com/SagerNet/sing-box.git"
if [ ! -d "sing-box" ]; then
  git clone --depth 1 --branch "$SINGBOX_VERSION" "$OFFICIAL_REPO" sing-box
else
  pushd sing-box >/dev/null
  if ! git remote get-url origin 2>/dev/null | grep -qE "(SagerNet|sagernet)/sing-box"; then
    echo ">> Existing sing-box clone is not official, re-pointing to $OFFICIAL_REPO"
    git remote set-url origin "$OFFICIAL_REPO"
  fi
  git fetch --depth 1 origin "refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION" || \
    git fetch origin "refs/tags/$SINGBOX_VERSION:refs/tags/$SINGBOX_VERSION"
  git checkout -f "$SINGBOX_VERSION"
  popd >/dev/null
fi

#### libneko ####
if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/hawkff/libneko.git
fi
pushd libneko >/dev/null
git remote set-url origin https://github.com/hawkff/libneko.git
git fetch origin --tags --force
git checkout "$COMMIT_LIBNEKO"
popd >/dev/null

#### wireguard-go ####
if [ ! -d "wireguard-go" ]; then
  git clone --no-checkout https://github.com/sagernet/wireguard-go.git wireguard-go
fi
pushd wireguard-go >/dev/null
git checkout "$COMMIT_WIREGUARD_GO"
popd >/dev/null

popd >/dev/null
