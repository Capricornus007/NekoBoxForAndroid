#!/usr/bin/env bash

source "buildScript/init/env.sh"

# fetch soucre
bash buildScript/lib/core/get_source.sh || exit 1

[ -f libcore/go.mod ] || exit 1
cd libcore

./init.sh || exit 1
