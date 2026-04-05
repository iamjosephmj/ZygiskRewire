#!/system/bin/sh

export TMP_PATH=/data/adb/rezygisk
rmdir $TMP_PATH

rm -rf /data/adb/post-fs-data.d/rezygisk.sh

# INFO: If post-fs-data.d is empty after removing rezygisk.sh, remove it.
if [ -z "$(ls -A /data/adb/post-fs-data.d)" ]; then
  rmdir /data/adb/post-fs-data.d
fi
