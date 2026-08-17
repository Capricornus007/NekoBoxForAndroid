#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1

# Apply upstream.conf (symlink + pin) then ensure libneko.
bash buildScript/lib/core/switch_upstream.sh

source "buildScript/lib/core/get_source_env.sh"
pushd "$NEKOBOX_ROOT"

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout "$LIBNEKO_REPO" libneko
fi
pushd libneko
git remote get-url origin >/dev/null 2>&1 || git remote add origin "$LIBNEKO_REPO"
git fetch --tags --force origin 2>/dev/null || git fetch --tags --force
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
