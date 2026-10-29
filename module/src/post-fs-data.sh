#!/system/bin/sh

set -e

MODDIR=${0%/*}
if [ "$ZYGISK_ENABLED" ]; then
  exit 0
fi

cd "$MODDIR"

if [ "$(which magisk)" ]; then
  for file in ../*; do
    if [ -d "$file" ] && [ -d "$file/zygisk" ] && ! [ -f "$file/disable" ]; then
      if [ -f "$file/post-fs-data.sh" ]; then
        cd "$file"
        log -p i -t "@@TAG@@-sh" "Manually trigger post-fs-data.sh for $file"

        # INFO: Don't propagate errexit
        set +e

        sh "$(realpath ./post-fs-data.sh)"

        # INFO: Re-enable errexit
        set -e

        cd "$MODDIR"
      fi
    fi
  done
fi

export TMP_PATH=@@TMP_PATH@@
rm -rf "$TMP_PATH"
mkdir -p "$TMP_PATH"
chmod 700 "$TMP_PATH"
mkdir -p "$TMP_PATH/bin"
cp "$MODDIR/bin/"* "$TMP_PATH/bin/"
chmod 755 "$TMP_PATH/bin/"*

# INFO: We need to cleanup the module.prop to its default state before the monitor writes to it
#         so that it never duplicates the module status in there.
cp "$MODDIR/module.prop.bak" "$MODDIR/module.prop"

# INFO: Utilize the one with the biggest output, as some devices with Tango have the full list
#         in ro.product.cpu.abilist but others only have a subset there, and the full list in
#         ro.system.product.cpu.abilist
CPU_ABIS_PROP1=$(getprop ro.system.product.cpu.abilist)
CPU_ABIS_PROP2=$(getprop ro.product.cpu.abilist)

if [ "${#CPU_ABIS_PROP2}" -gt "${#CPU_ABIS_PROP1}" ]; then
  CPU_ABIS=$CPU_ABIS_PROP2
else
  CPU_ABIS=$CPU_ABIS_PROP1
fi

cd "$TMP_PATH"

if [[ "$CPU_ABIS" == *"arm64-v8a"* || "$CPU_ABIS" == *"x86_64"* ]]; then
  ./bin/@@PTRACER_BIN@@64 monitor &
else
  # INFO: Device is 32-bit only

  ./bin/@@PTRACER_BIN@@32 monitor &
fi

# INFO: Once the primary daemon has reported in (state file written by the
#         monitor), every binary is held open by the monitor via /proc/self/fd.
#         Deleting the on-disk copies then leaves no file behind any running process.
(
  # INFO: Don't propagate errexit — this cleanup must never fail the boot
  set +e
  i=0
  while [ $i -lt 40 ]; do
    if grep -q '"state": 1' "$TMP_PATH/"s*.json 2>/dev/null; then
      rm -rf "$TMP_PATH/bin"
      exit 0
    fi
    sleep 0.5
    i=$((i + 1))
  done
  # INFO: Fallback — leave the copies in place (tmpfs, neutral names, 0700 dir).
) &

exit 0
