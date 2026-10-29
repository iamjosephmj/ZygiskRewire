#!/usr/bin/env bash
# scripts/export_payload.sh — export the built module binaries into the manager
# app's assets so the APK carries the runtime payload (Phase 4).
#
# Exported layout (consumed by the boot stub from the installed APK):
#   payload/<ID_DAEMON_BIN>32/64   flat — daemon, installed name
#   payload/<ID_PTRACER_BIN>32/64  flat — ptracer, installed name
#   payload/lib64/<ID_LIB_NAME>    64-bit loader (from lib/<64-bit arch> staging)
#   payload/lib/<ID_LIB_NAME>      32-bit loader (from lib/<32-bit arch> staging)
#   payload/identity.props         id=<ID_MOD_ID> + one <name>.sha256=<hex> per file
#
# One ABI per bitness is exported (default: the arm pair — the payload names
# are keyed by bitness, so two ABIs of the same bitness would carry identical
# installed names; requesting both together is a hard error, never a silent
# overwrite). An x86-family payload can be exported explicitly:
#   make app-payload PAYLOAD_ABIS="x86_64 x86"
#
# Every module build output the export needs is validated before anything is
# written: a missing output aborts with a non-zero exit instead of leaving a
# partial payload behind.
#
# Usage: scripts/export_payload.sh [BUILD_DIR] [ABI...]
#   BUILD_DIR defaults to <repo>/build; ABIs default to arm64-v8a armeabi-v7a.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${1:-$REPO_ROOT/build}"
[ "$#" -gt 0 ] && shift
ABIS=("$@")

if [ "${#ABIS[@]}" -eq 0 ]; then
  ABIS=(arm64-v8a armeabi-v7a)
fi
# Keep relative BUILD_DIR usable from any invoking directory.
case "$BUILD_DIR" in
  /*) ;;
  *) BUILD_DIR="$(pwd)/$BUILD_DIR" ;;
esac

die() { echo "export_payload: ERROR: $*" >&2; exit 1; }

id_of() { sed -n "s/^$1 := //p" "$BUILD_DIR/identity.mk"; }
bits_of() { case "$1" in arm64-v8a | x86_64) echo 64 ;; *) echo 32 ;; esac; }

# --- Validate the identity and the staged build outputs (write nothing yet) ---

[ -f "$BUILD_DIR/identity.mk" ] \
  || die "identity manifest missing: $BUILD_DIR/identity.mk (run make release first)"

ID_MOD_ID="$(id_of ID_MOD_ID)"
ID_DAEMON_BIN="$(id_of ID_DAEMON_BIN)"
ID_PTRACER_BIN="$(id_of ID_PTRACER_BIN)"
ID_LIB_NAME="$(id_of ID_LIB_NAME)"
[ -n "$ID_MOD_ID" ] && [ -n "$ID_DAEMON_BIN" ] && [ -n "$ID_PTRACER_BIN" ] \
  && [ -n "$ID_LIB_NAME" ] \
  || die "identity.mk is missing ID_MOD_ID/ID_DAEMON_BIN/ID_PTRACER_BIN/ID_LIB_NAME"

STAGE="$BUILD_DIR/module/release"
SEEN_BITS=""
for abi in "${ABIS[@]}"; do
  bits="$(bits_of "$abi")"
  case " $SEEN_BITS " in
    *" $bits "*) die "two ABIs of the same bitness requested ($abi); their installed names would overwrite each other" ;;
  esac
  SEEN_BITS="$SEEN_BITS $bits"
  for f in "bin/$abi/${ID_DAEMON_BIN}${bits}" \
           "bin/$abi/${ID_PTRACER_BIN}${bits}" \
           "lib/$abi/${ID_LIB_NAME}"; do
    [ -f "$STAGE/$f" ] \
      || die "module build output missing for ABI $abi: $STAGE/$f (run make release first)"
  done
done

# --- Export ---

OUT="$REPO_ROOT/app/src/main/assets/payload"
PROPS="$OUT/identity.props"

# Rebuild the payload tree from scratch so no stale file survives an identity change.
rm -rf "$OUT"
mkdir -p "$OUT/lib" "$OUT/lib64"
printf 'id=%s\n' "$ID_MOD_ID" > "$PROPS"

copy_out() { # $1 = staged source, $2 = asset-relative destination, $3 = kind (bin|lib)
  cp "$1" "$OUT/$2"
  if [ "$3" = bin ]; then
    chmod 755 "$OUT/$2"
  else
    chmod 644 "$OUT/$2"
  fi
  printf '%s.sha256=%s\n' "$2" "$(sha256sum "$OUT/$2" | cut -d ' ' -f1)" >> "$PROPS"
}

# Stable ordering: 64-bit family first, then 32-bit; daemon, ptracer, loader.
for abi in "${ABIS[@]}"; do
  bits="$(bits_of "$abi")"
  copy_out "$STAGE/bin/$abi/${ID_DAEMON_BIN}${bits}" "${ID_DAEMON_BIN}${bits}" bin
  copy_out "$STAGE/bin/$abi/${ID_PTRACER_BIN}${bits}" "${ID_PTRACER_BIN}${bits}" bin
  if [ "$bits" -eq 64 ]; then
    copy_out "$STAGE/lib/$abi/${ID_LIB_NAME}" "lib64/${ID_LIB_NAME}" lib
  else
    copy_out "$STAGE/lib/$abi/${ID_LIB_NAME}" "lib/${ID_LIB_NAME}" lib
  fi
done

# Self-check: every recorded digest must verify against the exported file.
rc=0
while IFS='=' read -r key hash; do
  case "$key" in
    id) ;;
    *.sha256) echo "$hash  $OUT/${key%.sha256}" | sha256sum -c - >/dev/null 2>&1 || rc=1 ;;
  esac
done < "$PROPS"
[ "$rc" -eq 0 ] || die "payload self-check failed against $PROPS"

echo "export_payload: exported $(( $(wc -l < "$PROPS") - 1 )) files to $OUT (id=$ID_MOD_ID)"
