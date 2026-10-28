#!/system/bin/sh

set -e

export TMP_PATH=@@TMP_PATH@@
rm -rf "$TMP_PATH"

rm -f /data/adb/service.d/@@MOD_ID@@.sh

# INFO: Only removes if dir is empty
rmdir /data/adb/service.d

exit 0
