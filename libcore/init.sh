#!/bin/bash
set -eo pipefail
source ../buildScript/lib/core/get_source_env.sh
TASK_GOPATH="$(go env GOPATH)"
TARGET_BIN="$TASK_GOPATH/bin"
mkdir -p "$TARGET_BIN" .build
STAMP="$COMMIT_GOMOBILE module-graph-v2 $(go version)"
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

# Keep the main module as the dependency root. Flattening `go list -m all`
# makes unused transitive modules direct dependencies and can upgrade the graph.
p = Path('.build/gomobile/cmd/gomobile/bind.go')
s = p.read_text()
anchor = '\t\tif mod != nil {\n'
replacement = anchor + '\t\t\tif mod.Main {\n\t\t\t\tif err := f.AddRequire(mod.Path, "v0.0.0"); err != nil {\n\t\t\t\t\treturn nil, err\n\t\t\t\t}\n\t\t\t}\n'
if s.count(anchor) != 1:
    raise SystemExit('unexpected gomobile module loop')
s = s.replace(anchor, replacement)
anchor = '\t\t\t\t} else {\n\t\t\t\t\tif err := f.AddRequire(mod.Path, v); err != nil {\n\t\t\t\t\t\treturn nil, err\n\t\t\t\t\t}\n'
if s.count(anchor) != 1:
    raise SystemExit('unexpected gomobile dependency requirements')
p.write_text(s.replace(anchor, ''))
PY
    cp ../buildScript/tests/gomobile_module_graph_test.go .build/gomobile/cmd/gomobile/neko_module_graph_test.go
    (cd .build/gomobile && NEKO_BIND_MAIN_MODULE="$(realpath ../..)" go test ./cmd/gomobile -run '^TestNekoBindingModuleGraph$' -count=1)
    (cd .build/gomobile && go build -o "$TARGET_BIN/gomobile-matsuri" ./cmd/gomobile && go build -o "$TARGET_BIN/gobind-matsuri" ./cmd/gobind)
    printf '%s\n' "$STAMP" > "$TARGET_BIN/neko-mobile.version"
fi
export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
