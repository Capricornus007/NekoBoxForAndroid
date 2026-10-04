#!/bin/bash
# Build the Proton VPN client stack (Proton's go-vpn-lib plus go-srp and gopenpgp)
# as an Android native executable for all ABIs and install them as bundled
# sidecars (app/executableSo/<abi>/libprotonvpn.so).
#
# Proton's auth + node-list logic cannot live in Kotlin without re-implementing
# SRP and OpenPGP: the login handshake needs the SRP proof chain over Proton's
# signed 2048-bit modulus, and Proton's API returns the VPN endpoint lists as
# armored/clear-signed OpenPGP payloads that must be verified against Proton's
# hardcoded keys. Both are already implemented in Proton's own Go modules, so we
# ship those instead of translating them.
#
# We ship it as a child-process sidecar (like mieru/naive/masterdnsvpn/olcrtc)
# rather than a gomobile .aar: gomobile permits only ONE binding per app (every
# bound .aar bundles go.Seq + libgojni.so, which collide with libcore's), and a
# separate process also keeps Proton's dependency graph out of libcore's pinned
# sing-box module graph. The Go packages we link are ed25519, localAgent,
# github.com/ProtonMail/go-srp and github.com/ProtonMail/gopenpgp/v2; the
# `wgAndroid` package is deliberately skipped because it only compiles against
# Proton's forked wireguard-go device plus a patched Go runtime clock, and nb4a
# creates its tunnel through sing-box anyway.
#
# A tiny wrapper main (buildScript/lib/protonvpn-src) imports those packages at a
# pinned commit and exposes `version` / `selftest` for now; the account commands
# land on top of the same entry point in the follow-up tickets. `selftest` runs
# the whole linked stack offline (SRP handshake against Proton's real signed
# modulus, OpenPGP sign/verify, ed25519->x25519, localAgent features) and the
# build below runs it on the host first, so a broken pin fails here rather than
# on a device.
#
# go-srp and gopenpgp versions are pinned in buildScript/lib/protonvpn-src/go.mod
# (that file is part of the CI sidecar cache key); PROTON_REPO/PROTON_COMMIT pin
# the go-vpn-lib tree and default to an immutable upstream commit.
#
# Requires Go 1.27+ on PATH. libcore uses its own Go; in CI this build job gets
# its own setup-go.
#
# Usage: ./run lib protonvpn
set -e
set -o pipefail

source "buildScript/init/env.sh"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set (NDK required to cross-compile the Proton sidecar)." >&2
  exit 1
fi

PROTON_REPO="${PROTON_REPO:-https://github.com/ProtonVPN/go-vpn-lib.git}"
PROTON_COMMIT="${PROTON_COMMIT:-9440653831b00dbc4711f59e8a23a06af1999405}"

if ! command -v go >/dev/null 2>&1; then
  echo "Error: go not found on PATH (the Proton sidecar needs Go 1.27+)." >&2
  exit 1
fi
GO_VER="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
GO_MAJOR="${GO_VER%%.*}"
GO_REST="${GO_VER#*.}"; GO_MINOR="${GO_REST%%.*}"
if [ "${GO_MAJOR:-0}" -lt 1 ] || { [ "${GO_MAJOR:-0}" -eq 1 ] && [ "${GO_MINOR:-0}" -lt 27 ]; }; then
  echo "Error: Go $GO_VER is too old; the Proton sidecar needs Go 1.27+." >&2
  exit 1
fi

DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64/bin"
fi
if [ ! -d "$DEPS" ]; then
  echo "Error: NDK LLVM toolchain not found under $ANDROID_NDK_HOME." >&2
  exit 1
fi

SRC="$(pwd)/buildScript/lib/protonvpn-src"
WORK="$(pwd)/.protonvpn-build"
OUT="$(pwd)/app/executableSo"

# Clone the pinned go-vpn-lib commit so the wrapper builds against an immutable tree.
need_clone=1
if [ -d "$WORK/.git" ]; then
  if git -C "$WORK" fetch --depth 1 origin "$PROTON_COMMIT" \
     && git -C "$WORK" checkout -q FETCH_HEAD; then
    need_clone=0
  else
    echo ">> existing $WORK is unusable; re-cloning"
  fi
fi
if [ "$need_clone" -eq 1 ]; then
  rm -rf "$WORK"
  git init -q "$WORK"
  git -C "$WORK" remote add origin "$PROTON_REPO"
  git -C "$WORK" fetch --depth 1 origin "$PROTON_COMMIT"
  git -C "$WORK" checkout -q FETCH_HEAD
fi

# Stage the wrapper in a scratch build dir and point it at the cloned go-vpn-lib via
# a replace directive, so `go build` never has to resolve the untagged Proton module
# online.
BUILD="$(pwd)/.protonvpn-wrapper"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cp "$SRC/main.go" "$SRC/main_test.go" "$SRC/go.mod" "$BUILD/"
( cd "$BUILD" && go mod edit -replace "github.com/ProtonVPN/go-vpn-lib=$WORK" && go mod tidy )

# Host-side gate: the pinned Proton stack must build and its offline selftest must
# be green before any cross-compiled artifact is published.
( cd "$BUILD" && go test . )

build_abi() {
  local abi="$1" goarch="$2" cc="$3" goarm="${4:-}" pagesize="${5:-}"
  local ldflags="-s -w -X main.protonLibCommit=$PROTON_COMMIT"
  # Android 15+ can run on 16 KB page devices, where the loader refuses ELF
  # segments aligned below the page size. gomobile's libgojni.so already asks for
  # 16 KB; a plain Go android build defaults to 4 KB, so the 64-bit ABIs pass the
  # linker flag explicitly. 32-bit ABIs have no such requirement and would only
  # pay the padding.
  if [ -n "$pagesize" ]; then
    ldflags="$ldflags -extldflags=-Wl,-z,max-page-size=$pagesize"
  fi
  echo ">> building libprotonvpn.so for $abi"
  mkdir -p "$OUT/$abi"
  ( cd "$BUILD" && env GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CGO_ENABLED=1 \
    CC="$DEPS/$cc" \
    go build -trimpath -ldflags="$ldflags" \
      -o "$OUT/$abi/libprotonvpn.so" . )
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android21-clang" ""  16384
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi21-clang" "7"
build_abi "x86"         "386"   "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" "x86_64-linux-android21-clang" ""  16384

rm -rf "$BUILD"

# Fail here instead of shipping a sidecar that cannot be loaded on 16 KB page
# devices (same invariant scripts/verify-elf-alignment.sh enforces for libcore).
if command -v readelf >/dev/null 2>&1; then
  for abi in arm64-v8a x86_64; do
    min=""
    for alignment in $(readelf -lW "$OUT/$abi/libprotonvpn.so" | awk '/LOAD/{print $NF}'); do
      decimal=$(printf '%d' "$alignment")
      if [ -z "$min" ] || [ "$decimal" -lt "$min" ]; then
        min=$decimal
      fi
    done
    if [ "${min:-0}" -lt 16384 ]; then
      echo "Error: $abi libprotonvpn.so is NOT 16 KB aligned (min LOAD align ${min:-none})." >&2
      exit 1
    fi
    echo ">> $abi libprotonvpn.so min LOAD align = $min"
  done
else
  echo ">> readelf unavailable; skipped the 16 KB alignment check"
fi

echo ">> installed Proton sidecar binaries:"
ls -la "$OUT"/*/libprotonvpn.so
