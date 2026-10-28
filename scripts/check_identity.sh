#!/usr/bin/env bash
# scripts/check_identity.sh — assert two consecutive builds are identity-disjoint
# and carry zero zygisk/rezygisk tokens.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Spec-mandated API/compat strings that must remain; filtered before matching.
# The /zygisk/ module-dir path segment may appear with or without a trailing
# slash (e.g. "$MODDIR/zygisk"); both spellings are keep-listed.
filter_keep_list() {
  sed -E 's/zygisk_module_entry//g; s/zygisk_companion_entry//g; s/ZYGISK_ENABLED//g; s|/zygisk/|/dir/|g; s|/zygisk|/dir|g'
}

check_clean() { # $1 = dir
  local hits=0
  while IFS= read -r -d '' f; do
    if strings "$f" 2>/dev/null | filter_keep_list | grep -qiE 'zygisk|rezygisk'; then
      echo "IDENTITY LEAK in $f:"
      strings "$f" 2>/dev/null | filter_keep_list | grep -iE 'zygisk|rezygisk' | head -5
      hits=1
    fi
  done < <(find "$1" -type f -print0)
  return "$hits"
}

check_names() { # $1 = dir — no artifact may carry an identifying NAME
  local hits=0 name
  while IFS= read -r -d '' f; do
    name="$(basename "$f")"
    if printf '%s' "$name" | grep -qiE 'zygisk|rezygisk'; then
      echo "IDENTITY LEAK in filename: $f"
      hits=1
    fi
  done < <(find "$1" -type f -print0)
  return "$hits"
}

rm -rf build/check-a build/check-b
scripts/gen_identity.sh build/check-a
# BUILD_DIR must be absolute: the top-level Makefile uses $(CURDIR)/build, and
# loader/Makefile forwards it as a relative out= to sub-makes run from other dirs.
make BUILD_DIR="$ROOT/build/check-a" IDENTITY_MK="$ROOT/build/check-a/identity.mk" release >/dev/null
scripts/gen_identity.sh build/check-b
make BUILD_DIR="$ROOT/build/check-b" IDENTITY_MK="$ROOT/build/check-b/identity.mk" release >/dev/null

# Unstripped intermediates (obj/) carry DWARF/source-filename residuals that
# stripping removes from the distribution; the check covers the distribution set.
rm -rf build/check-a/obj build/check-b/obj

FAIL=0
for dir in build/check-a build/check-b; do
  check_clean "$dir" || FAIL=1
  check_names "$dir" || FAIL=1
done
[ "$FAIL" -eq 0 ] || { echo "FAIL: leaks found"; exit 1; }

A="$(grep ID_HASH build/check-a/identity.mk)"; B="$(grep ID_HASH build/check-b/identity.mk)"
[ "$A" != "$B" ] || { echo "FAIL: identities identical across builds"; exit 1; }
echo "PASS: builds are identity-disjoint and neutral"
