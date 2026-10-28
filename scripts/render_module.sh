#!/usr/bin/env bash
# scripts/render_module.sh — substitute @@TOKEN@@ placeholders with this build's
# per-build identity (from module/identity.env + build/identity.mk).
#
# Usage: scripts/render_module.sh <file>...
#
# NOTE: substitution is destructive (tokens disappear), so this must only ever
# be run on STAGED COPIES under build/module/<type>/, never on the module/src
# or webroot source templates.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="module/identity.env"
MK_FILE="build/identity.mk"
[ -f "$ENV_FILE" ] || scripts/gen_identity.sh build
# shellcheck disable=SC1090
source "$ENV_FILE"

# Tokens that only live in identity.mk (make syntax, not shell-exportable).
mk_val() {
  sed -n "s/^ID_$1 := //p" "$MK_FILE"
}

CP_BASE="$(mk_val CP_BASE)"
STATE_NAME="$(mk_val STATE_NAME)"
SEPOL_TYPE="$(mk_val SEPOL_TYPE)"
TAG="$(mk_val TAG)"

# Guard against a stale identity.env (binaries embed identity.mk values; a
# mismatch would produce a zip whose scripts disagree with the compiled code).
if [ "$(mk_val MOD_ID)" != "$ID_MOD_ID" ]; then
  echo "error: identity.env (id=$ID_MOD_ID) and identity.mk (id=$(mk_val MOD_ID)) disagree" >&2
  echo "       re-run scripts/gen_identity.sh <builddir>" >&2
  exit 1
fi

render() {
  sed -i \
    -e "s|@@MOD_ID@@|${ID_MOD_ID}|g" \
    -e "s|@@DAEMON_BIN@@|${ID_DAEMON_BIN}|g" \
    -e "s|@@PTRACER_BIN@@|${ID_PTRACER_BIN}|g" \
    -e "s|@@LIB_NAME@@|${ID_LIB_NAME}|g" \
    -e "s|@@TMP_PATH@@|${ID_TMP_PATH}|g" \
    -e "s|@@CP_BASE@@|${CP_BASE}|g" \
    -e "s|@@STATE_NAME@@|${STATE_NAME}|g" \
    -e "s|@@SEPOL_TYPE@@|${SEPOL_TYPE}|g" \
    -e "s|@@TAG@@|${TAG}|g" \
    "$1"
}

for f in "$@"; do
  render "$f"
done
