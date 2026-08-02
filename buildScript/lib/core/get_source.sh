#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

#### sing-box ####
# nb4a.properties はリポジトリルートに存在する。pushd .. の前に読むこと。
SINGBOX_BRANCH=$(grep '^SINGBOX_BRANCH=' nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_BRANCH" ]; then
  SINGBOX_BRANCH="1.13.x"
fi
echo ">> Using NekoBox sing-box fork, branch: $SINGBOX_BRANCH"

pushd .. >/dev/null

NEKO_SINGBOX_REPO="https://github.com/Capricornus007/sing-box.git"
# Pin to COMMIT_SING_BOX (branch is only used as a fallback) so CI builds are
# reproducible and the LibCore cache invalidates whenever sing-box changes.
SINGBOX_COMMIT="${COMMIT_SING_BOX:-}"
if [ ! -d "sing-box" ]; then
  if [ -n "$SINGBOX_COMMIT" ]; then
    git clone "$NEKO_SINGBOX_REPO" sing-box
  else
    git clone --branch "$SINGBOX_BRANCH" "$NEKO_SINGBOX_REPO" sing-box
  fi
else
  pushd sing-box >/dev/null
  if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/sing-box"; then
    echo ">> Existing sing-box clone is not NekoBox fork, re-pointing to $NEKO_SINGBOX_REPO"
    git remote set-url origin "$NEKO_SINGBOX_REPO"
  fi
  git fetch origin || git fetch origin
  popd >/dev/null
fi
pushd sing-box >/dev/null
if [ -n "$SINGBOX_COMMIT" ]; then
  git checkout -f "$SINGBOX_COMMIT"
else
  git checkout -f "$SINGBOX_BRANCH"
  git pull origin "$SINGBOX_BRANCH" || true
fi
echo ">> sing-box at $(git rev-parse --short HEAD)"
popd >/dev/null

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
