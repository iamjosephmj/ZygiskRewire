#!/system/bin/sh

set -e

export TMP_PATH=@@TMP_PATH@@

# INFO: Detach the module's system lib overlays first — their upper dirs
#         reference the runtime dir removed below.
umount /system/lib64 2>/dev/null || true
umount /system/lib 2>/dev/null || true

rm -rf "$TMP_PATH"

rm -f /data/adb/service.d/@@MOD_ID@@.sh

# INFO: Only removes if dir is empty
rmdir /data/adb/service.d

exit 0
