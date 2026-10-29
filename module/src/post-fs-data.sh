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

# INFO: Some managers (KSU Next 3.4.0) have no module-mount implementation and
#         never overlay module system/ trees. Mount the loader into /system ourselves:
#         a directory-scoped overlayfs whose lower layer is the real /system/lib{,64}
#         and whose upper layer is a copy of the module's staged loader. Real libraries
#         keep working (they resolve to the lower layer); our loader appears as a new
#         upper file.
# INFO: The upper layer must live in the runtime tmpfs: this kernel (and others of the
#         same era) rejects f2fs /data as any overlayfs layer — dmesg:
#         "overlayfs: filesystem on '<module dir>' not supported" — so the module tree
#         itself can be neither upperdir nor lowerdir. The persistent copy stays staged
#         under $MODDIR/system/lib{,64}; the tmpfs copy is ephemeral inside the 0700
#         runtime dir and is relabeled so zygote reads it exactly like a system lib.
mount_overlay() {
  local lower="$1" upper="$2" staged="$3"
  [ -d "$staged" ] || return 0
  [ -d "$lower" ] || return 0
  local work="${upper}.w"
  mkdir -p "$upper" "$work" 2>/dev/null || return 0
  cp "$staged/"*.so "$upper/" 2>/dev/null || return 0
  chmod 644 "$upper/"* 2>/dev/null || true
  chcon u:object_r:system_lib_file:s0 "$upper/"* 2>/dev/null || true
  if ! grep -q " $lower overlay " /proc/mounts 2>/dev/null; then
    mount -t overlay overlay -o "lowerdir=$lower,upperdir=$upper,workdir=$work" "$lower" 2>/dev/null || true
  fi
}
mount_overlay /system/lib64 "$TMP_PATH/ovl/lib64" "$MODDIR/system/lib64"
mount_overlay /system/lib "$TMP_PATH/ovl/lib" "$MODDIR/system/lib"

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
