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
  git clone --no-checkout https://github.com/Capricornus007/libneko.git
fi
pushd libneko >/dev/null
git remote set-url origin https://github.com/Capricornus007/libneko.git
git fetch origin --tags --force
git checkout "$COMMIT_LIBNEKO"
popd >/dev/null

#### wireguard-go ####
WIREGUARD_REPO="https://github.com/Capricornus007/wireguard-go.git"
if [ ! -d "wireguard-go" ]; then
  git clone --no-checkout "$WIREGUARD_REPO" wireguard-go
fi
pushd wireguard-go >/dev/null
if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/wireguard-go"; then
  git remote set-url origin "$WIREGUARD_REPO"
fi
git fetch origin
git checkout "$COMMIT_WIREGUARD_GO"
popd >/dev/null

#### sing-quic ####
SING_QUIC_REPO="https://github.com/Capricornus007/sing-quic.git"
if [ ! -d "sing-quic" ]; then
  git clone --no-checkout "$SING_QUIC_REPO" sing-quic
fi
pushd sing-quic >/dev/null
if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/sing-quic"; then
  git remote set-url origin "$SING_QUIC_REPO"
fi
git fetch origin
git checkout -f "$COMMIT_SING_QUIC"
echo ">> sing-quic at $(git rev-parse --short HEAD)"
popd >/dev/null

#### sing-juicity ####
SING_JUICITY_REPO="https://github.com/Capricornus007/sing-juicity.git"
if [ ! -d "sing-juicity" ]; then
  git clone --no-checkout "$SING_JUICITY_REPO" sing-juicity
fi
pushd sing-juicity >/dev/null
if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/sing-juicity"; then
  git remote set-url origin "$SING_JUICITY_REPO"
fi
git fetch origin
git checkout -f "$COMMIT_SING_JUICITY"
echo ">> sing-juicity at $(git rev-parse --short HEAD)"
popd >/dev/null

#### sing-trusttunnel ####
SING_TRUSTTUNNEL_REPO="https://github.com/Capricornus007/sing-trusttunnel.git"
if [ ! -d "sing-trusttunnel" ]; then
  git clone --no-checkout "$SING_TRUSTTUNNEL_REPO" sing-trusttunnel
fi
pushd sing-trusttunnel >/dev/null
if ! git remote get-url origin 2>/dev/null | grep -q "Capricornus007/sing-trusttunnel"; then
  git remote set-url origin "$SING_TRUSTTUNNEL_REPO"
fi
git fetch origin
git checkout -f "$COMMIT_SING_TRUSTTUNNEL"
echo ">> sing-trusttunnel at $(git rev-parse --short HEAD)"
popd >/dev/null

popd >/dev/null
