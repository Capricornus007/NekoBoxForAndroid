#!/bin/bash
set -eo pipefail

[ -f ./env_java.sh ] && source ./env_java.sh
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

mkdir -p "$BUILD"
export PATH="$GOPATH/bin:$PATH"
go mod tidy -diff
export GOFLAGS="${GOFLAGS:-} -mod=readonly"

export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -f libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
