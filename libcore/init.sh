#!/bin/bash
set -eo pipefail
source ../buildScript/lib/core/get_source_env.sh
TASK_GOPATH="$(go env GOPATH)"
TARGET_BIN="$TASK_GOPATH/bin"
mkdir -p "$TARGET_BIN" .build
STAMP="$COMMIT_GOMOBILE $(go version)"
if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ] || [ "$(cat "$TARGET_BIN/neko-mobile.version" 2>/dev/null || true)" != "$STAMP" ]; then
    rm -rf .build/gomobile
    git clone --no-checkout https://github.com/MatsuriDayo/gomobile.git .build/gomobile
    git -C .build/gomobile checkout --detach "$COMMIT_GOMOBILE"
    # Both tools come from the pinned fork; upstream gobind bootstrap is incompatible with this fork.
    python3 - <<'PY'
from pathlib import Path
p = Path('.build/gomobile/cmd/gomobile/init.go')
s = p.read_text()
start = s.index('\tif err := goInstall(')
end = s.index('\n\t}', start) + len('\n\t}')
p.write_text(s[:start] + s[end:])
PY
    (cd .build/gomobile && go build -o "$TARGET_BIN/gomobile-matsuri" ./cmd/gomobile && go build -o "$TARGET_BIN/gobind-matsuri" ./cmd/gobind)
    printf '%s\n' "$STAMP" > "$TARGET_BIN/neko-mobile.version"
fi
export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
