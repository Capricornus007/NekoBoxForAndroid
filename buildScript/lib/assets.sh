#!/bin/bash
set -eo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$PROJECT_ROOT/buildScript/lib/bundle_assets.py" "$@"
