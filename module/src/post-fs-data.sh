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

# INFO: We need to cleanup the module.prop to its default state before the monitor writes to it
#         so that it never duplicates the module status in there.
cp "$MODDIR/module.prop.bak" "$MODDIR/module.prop"

# INFO: The module directory carries no binaries anymore — the manager app's
#         installed APK is the payload carrier (Phase 4). If the app is not
#         installed there is no payload anywhere: bail out before creating
#         anything (no runtime dir, no injection this boot — boot continues
#         normally). No identifying output on any failure path.
# INFO: Android 11+ nests the installed package under TWO random directory
#         levels (/data/app/~~rnd==/<pkg>-rnd==/base.apk) and sh globs do not
#         cross '/', so the glob needs both levels. The find fallback covers
#         any other layout depth.
APK=$(ls /data/app/*/*/tech.thessemaj.zygiskrewire*/base.apk 2>/dev/null | head -1)
if [ ! -f "$APK" ]; then
  APK=$(find /data/app -maxdepth 3 -name base.apk -path '*tech.thessemaj.zygiskrewire*' 2>/dev/null | head -1)
fi
if [ ! -f "$APK" ]; then
  exit 0
fi

rm -rf "$TMP_PATH"
mkdir -p "$TMP_PATH/bin" "$TMP_PATH/ovl/lib64" "$TMP_PATH/ovl/lib"
chmod 700 "$TMP_PATH"

# INFO: Extract the payload PRESERVING the lib/ + lib64/ loader subdirs (no
#         -j for those entries): both loaders share one installed name, the
#         subdirectory IS the bitness. The daemon/ptracer binaries are flat
#         assets and land beside them together with identity.props.
unzip -o "$APK" 'assets/payload/*' -d "$TMP_PATH" >/dev/null 2>&1 || exit 0
PAYLOAD="$TMP_PATH/assets/payload"
[ -f "$PAYLOAD/identity.props" ] || exit 0

# INFO: Fail-safe verification. identity.props is STRICT key=value split on
#         '=' (no spaces). The sha256 keys carry the payload-relative path, so
#         lib64/<lib>.sha256 verifies the loader in place inside the
#         extraction tree. A module-id mismatch or ANY sha256 mismatch means a
#         corrupted or foreign payload: no injection this boot, boot
#         continues normally.
cd "$PAYLOAD" || exit 0

MODULE_ID=$(sed -n 's/^id=//p' "$MODDIR/module.prop" 2>/dev/null)
[ -n "$MODULE_ID" ] || exit 0

while IFS= read -r line || [ -n "$line" ]; do
  key="${line%%=*}"
  value="${line#*=}"
  case "$key" in
    id)
      [ "$value" = "$MODULE_ID" ] || exit 0
      ;;
    *.sha256)
      echo "$value  ${key%.sha256}" | sha256sum -c - >/dev/null 2>&1 || exit 0
      ;;
  esac
done < identity.props

# INFO: Stage the verified payload. Binaries land flat in bin/ under their
#         installed names (the monitor re-execs ./bin/... from $TMP_PATH as
#         its cwd); the loaders become the overlay upper tree. This tree
#         persists on the tmpfs runtime dir for the whole session — it is the
#         exec source AND the overlay upper source, and tmpfs wipes at reboot,
#         which replaces the Phase 2 wait-then-delete block entirely.
mv lib64/* "$TMP_PATH/ovl/lib64/" 2>/dev/null || exit 0
mv lib/* "$TMP_PATH/ovl/lib/" 2>/dev/null || exit 0
mv @@DAEMON_BIN@@32 @@DAEMON_BIN@@64 @@PTRACER_BIN@@32 @@PTRACER_BIN@@64 "$TMP_PATH/bin/" || exit 0
chmod 755 "$TMP_PATH/bin/"* 2>/dev/null || exit 0

cd "$TMP_PATH"
rm -rf "$TMP_PATH/assets"

# INFO: Some managers (KSU Next 3.4.0) have no module-mount implementation and
#         never overlay module system/ trees. Mount the loader into /system ourselves:
#         a directory-scoped overlayfs whose lower layer is the real /system/lib{,64}
#         and whose upper layer is the tmpfs copy of the payload's loader. Real libraries
#         keep working (they resolve to the lower layer); our loader appears as a new
#         upper file.
# INFO: The upper layer lives in the runtime tmpfs: this kernel (and others of the
#         same era) rejects f2fs /data as any overlayfs layer — dmesg:
#         "overlayfs: filesystem on '<module dir>' not supported" — so neither the
#         module tree nor /data can be upperdir or lowerdir. The verified loader copy
#         is moved into the tmpfs overlay tree above, kept ephemeral inside the 0700
#         runtime dir and relabeled so zygote reads it exactly like a system lib.
mount_overlay() {
  local lower="$1" upper="$2"
  [ -d "$lower" ] || return 0
  ls "$upper/"* >/dev/null 2>&1 || return 0
  local work="${upper}.w"
  mkdir -p "$upper" "$work" 2>/dev/null || return 0
  chmod 644 "$upper/"* 2>/dev/null || true
  chcon u:object_r:system_lib_file:s0 "$upper/"* 2>/dev/null || true
  if ! grep -q " $lower overlay " /proc/mounts 2>/dev/null; then
    mount -t overlay overlay -o "lowerdir=$lower,upperdir=$upper,workdir=$work" "$lower" 2>/dev/null || true
  fi
}
mount_overlay /system/lib64 "$TMP_PATH/ovl/lib64"
mount_overlay /system/lib "$TMP_PATH/ovl/lib"

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

exit 0
