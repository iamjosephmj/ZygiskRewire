#!/system/bin/sh

set -e

export TMP_PATH=@@TMP_PATH@@

# INFO: Detach the module's system lib overlays first — their upper dirs
#         reference the runtime dir removed below. Only detach mounts that
#         are OURS: an overlay at the mountpoint (the module's loader
#         overlay) or a mount sourced from our /dev runtime dir. A manager's
#         own bind at the same mountpoint has a foreign source and a
#         non-overlay fstype and must survive module removal. Best-effort:
#         every failure ignored, no identifying output.
umount_if_ours() {
  mp="$1"
  line=$(awk -v mp="$mp" '$2 == mp { print $1 " " $3; exit }' /proc/mounts 2>/dev/null) || return 0
  [ -n "$line" ] || return 0
  src=${line%% *}
  fst=${line#* }
  case "$src" in
    /dev/*) umount "$mp" 2>/dev/null || true; return 0 ;;
  esac
  if [ "$fst" = overlay ]; then
    umount "$mp" 2>/dev/null || true
  fi
  return 0
}
umount_if_ours /system/lib64
umount_if_ours /system/lib

rm -rf "$TMP_PATH"

rm -f /data/adb/service.d/@@MOD_ID@@.sh

# INFO: Only removes if dir is empty
rmdir /data/adb/service.d

exit 0
