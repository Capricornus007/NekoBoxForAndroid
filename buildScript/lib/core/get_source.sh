#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

pushd .. >/dev/null

#### sing-box ####
SINGBOX_BRANCH=$(grep '^SINGBOX_BRANCH=' nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_BRANCH" ]; then
  SINGBOX_BRANCH="1.13.x"
fi
echo ">> Using NekoBox sing-box fork, branch: $SINGBOX_BRANCH"

NEKO_SINGBOX_REPO="https://github.com/Capricornus007/sing-box.git"
if [ ! -d "sing-box" ]; then
  git clone --depth 1 --branch "$SINGBOX_BRANCH" "$NEKO_SINGBOX_REPO" sing-box
else
  pushd sing-box >/dev/null
  if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/sing-box"; then
    echo ">> Existing sing-box clone is not NekoBox fork, re-pointing to $NEKO_SINGBOX_REPO"
    git remote set-url origin "$NEKO_SINGBOX_REPO"
  fi
  git fetch --depth 1 origin "$SINGBOX_BRANCH" || git fetch origin "$SINGBOX_BRANCH"
  git checkout -f "$SINGBOX_BRANCH"
  git pull origin "$SINGBOX_BRANCH" || true
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
