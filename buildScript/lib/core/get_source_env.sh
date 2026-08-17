# sing-box / libneko pins and upstream selection for NB4A libcore.
#
# Layout (sibling of NekoBoxForAndroid):
#   sing-box.ref1nd    checkout of reF1nd/sing-box
#   sing-box.starifly  checkout of starifly/sing-box
#   sing-box           symlink -> sing-box.<active>  (go.mod replace target)
#
# Switch by editing:
#   buildScript/lib/core/upstream.conf
# then:
#   bash buildScript/lib/core/switch_upstream.sh
# Env var SING_BOX_UPSTREAM still overrides the conf file when set.

_CORE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_UPSTREAM_CONF="${_CORE_DIR}/upstream.conf"

# --- remotes (canonical) ---
export SING_BOX_REPO_REF1ND="https://github.com/reF1nd/sing-box.git"
export SING_BOX_REPO_STARIFLY="https://github.com/starifly/sing-box.git"
export LIBNEKO_REPO="${LIBNEKO_REPO:-https://github.com/starifly/libneko.git}"

# --- pins ---
# reF1nd: sing-box-releases testing / AsteriskBOX (v1.14.0-beta.15-reF1nd)
export COMMIT_SING_BOX_REF1ND="d1184b6cd26159397c841f6e294b7e78fbd70fec"
# starifly: previous NB4A pin (XHTTP line)
export COMMIT_SING_BOX_STARIFLY="83983fc4c0074746962c5202d16f0b46af26418f"
export COMMIT_LIBNEKO="1c47a3af71990a7b2192e03292b4d246c308ef0b"

# Load conf unless already overridden by environment.
if [ -z "${SING_BOX_UPSTREAM+x}" ] || [ -z "${SING_BOX_UPSTREAM}" ]; then
  if [ -f "$_UPSTREAM_CONF" ]; then
    # shellcheck disable=SC1090
    source "$_UPSTREAM_CONF"
  fi
fi
# Default starifly: NB4A libcore binds that API surface. reF1nd needs a port first.
SING_BOX_UPSTREAM="${SING_BOX_UPSTREAM:-starifly}"

case "$SING_BOX_UPSTREAM" in
  starifly|old|rollback)
    export SING_BOX_UPSTREAM_NAME="starifly"
    export SING_BOX_REPO="$SING_BOX_REPO_STARIFLY"
    export COMMIT_SING_BOX="$COMMIT_SING_BOX_STARIFLY"
    export SING_BOX_DIR_NAME="sing-box.starifly"
    ;;
  ref1nd|reF1nd|refind|new|*)
    export SING_BOX_UPSTREAM_NAME="ref1nd"
    export SING_BOX_REPO="$SING_BOX_REPO_REF1ND"
    export COMMIT_SING_BOX="$COMMIT_SING_BOX_REF1ND"
    export SING_BOX_DIR_NAME="sing-box.ref1nd"
    ;;
esac

# core -> lib -> buildScript -> NekoBoxForAndroid; parent holds sing-box.* trees
export NEKOBOX_APP_ROOT="$(cd "${_CORE_DIR}/../../.." && pwd)"
export NEKOBOX_ROOT="$(cd "${NEKOBOX_APP_ROOT}/.." && pwd)"
export SING_BOX_TREE="${NEKOBOX_ROOT}/${SING_BOX_DIR_NAME}"
export SING_BOX_LINK="${NEKOBOX_ROOT}/sing-box"
