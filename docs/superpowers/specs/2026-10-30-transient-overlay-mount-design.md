# ZygiskRewire: Transient Overlay Mount

Date: 2026-10-30
Status: Draft for review
Phase: 6a (mount-provenance hardening; slots ahead of the pending Phase 6 CI/packaging work)
Base: ZygiskRewire @ 7b9da26 lineage (Phase 5 merged)

## Problem

The Phase 3 overlay hides the loader from `/proc/<pid>/maps` provenance checks (mapped from
`/system/lib64/<ID_LIB_NAME>`, labeled `system_lib_file`) but advertises itself in the mount
table. Verified on the test device (Pixel 9 Pro, KSU Next, build `syscfg-56532f10`):

```
overlay /system/lib64 overlay rw,seclabel,relatime,lowerdir=/system/lib64,upperdir=/dev/syscfg-56532f10/ovl/lib64,workdir=/dev/syscfg-56532f10/ovl/lib64.w 0 0
overlay /system/lib  overlay rw,seclabel,relatime,lowerdir=/system/lib, upperdir=/dev/syscfg-56532f10/ovl/lib, workdir=/dev/syscfg-56532f10/ovl/lib.w  0 0
```

Both entries are visible to every unprivileged process via `/proc/mounts`,
`/proc/self/mountinfo`, and `/proc/self/mountstats`. Two independent anomalies per line:

1. **fstype `overlay` on `/system/lib{,64}`** — no stock device mounts anything there; one
   grep catches the whole mechanism without knowing what it is looking at.
2. **`upperdir=/dev/syscfg-*`** — the module's per-build identity runtime dir in plaintext.

`post-fs-data.sh:181-205` mounts both overlays in the shared (init) namespace at boot and
they stay mounted for the whole session. Every app inherits them.

## Goal

After boot settles, no process outside the module's own root-only runtime views sees any
trace of the overlay in the mount table: zero `overlay` fstype entries, zero
`/dev/syscfg-*` strings, in every namespace an app can observe. The overlay becomes a
transient injection scaffold: mounted before zygote, unmounted before the first fork.

## Non-Goals (accepted residuals, explicitly)

- **Mapped-path ENOENT.** After unmount, the already-mapped loader pages remain executable
  (fd-backed mappings survive umount) but `/system/lib64/<ID_LIB_NAME>` no longer resolves.
  A RASP that re-opens mapped system files to hash them gets ENOENT. Accepted: the name is
  per-build random and already fails any known-good-list check; the mounts-table scan this
  phase removes is the strictly more common commercial check. Mitigation is deferred to the
  JIT-memfd execution spike.
- **Transient exposure window.** Between monitor start and last loader ack, the overlay is
  mounted and visible. No app process exists in that window (apps only fork from a zygote
  whose loader entry has completed), but a root process racing the window could observe it.
- **`dl_iterate_phdr` / solist residue** — Phase 4+ linker-dlopen redesign territory.
- **Play Integrity / hardware attestation** — separate layer (PIF / tricky_store).
- **Other modules' own mounts** — unchanged; `umount_root` (`utils.c:681-741`) keeps
  handling root-module unmounts for denylisted apps as today.

## Verified architecture facts the design builds on

- Module loading is complete before any app exists: the loader entry runs on each zygote's
  main thread via the `__libc_init` GOT redirect (`ptracer.c:165-190`), and the loader's
  module pass (`hook.c:938 load_modules_only`) dlopens every module `.so` from the overlay
  path (`/system/lib64/ov<hash>.so`, chosen in `zygiskd.c:517-554`) before zygote proceeds
  to fork. "All loaders acked" therefore strictly precedes the first app process.
- The monitor (`loader/src/ptracer/monitor.c`) is the boot-time coordinator: it must start
  before zygote (post-fs-data context), tracks per-arch state in `status64`/`status32`
  including `zygote_injected` (`monitor.c:48-71`), receives tracer/daemon messages over its
  existing channel (`ZYGOTE64_INJECTED` / `DAEMON64_SET_INFO`, `monitor.c:280-289`), spawns
  the daemon lazily at first zygote exec, and re-execs the tracer per zygote restart.
- Zygote restart is already an event the loader reports (`ZygoteRestart` in
  `enum DaemonSocketAction`, `constants.h`), and the monitor re-runs the injection cycle.
- The daemon keeps module libs as **fds** opened from the module dir (`zygiskd.c:89`), and
  companions dlopen via `/proc/self/fd/<n>` (`companion.c:23-31`) — companion spawning after
  unmount does **not** need the overlay.
- The loader's own mapping is done before any of this by the tracer directly from the
  overlay path (`ptracer.c:184-186`).
- `mount_overlay` in `post-fs-data.sh:192-203` is already idempotent (checks
  `/proc/mounts` first); the same guard moves into C.
- The overlay upper tree in `$TMP_PATH/ovl/{lib,lib64}` persists on the identity tmpfs for
  the whole session, so remounting for a zygote restart needs no re-staging.

## Architecture

**The overlay mount lifecycle moves from shell to the monitor.** `post-fs-data.sh` keeps
only staging (payload extraction, sha256 verification, tmpfs tree, module-lib neutral
staging, monitor start); its `mount_overlay` function and both call sites are deleted.

### Mount (monitor start)

At monitor startup, before any zygote is expected, the monitor mounts both overlays with
the same options the shell helper used (`lowerdir=/system/lib{,64},
upperdir=$TMP_PATH/ovl/{lib,lib64}, workdir=...`), guarded by the same idempotency check
(`grep`-equivalent scan of its own `/proc/mounts`). The monitor inherits the post-fs-data
context, so mount permission is identical to today's shell mount. Failure to mount is
non-fatal to boot: the monitor continues, injection falls back to the module-dir path
(existing `ReadModules` fallback, `zygiskd.c:551-554`), matching today's staging-failure
behavior.

### Ack (loader, per zygote)

After the loader finishes its module dlopen pass in a given zygote, it sends one new
message to its daemon: `OverlayConsumed` (new `DaemonSocketAction`). The daemon forwards a
per-arch consumed notification to the monitor over the existing monitor message channel,
modeled on `ZYGOTE64_INJECTED` / `DAEMON64_SET_INFO` handling (`monitor.c:280-289`).

### Unmount (monitor, coordinated)

The monitor is the single unmount authority. It unmounts both overlays with
`umount2(MNT_DETACH)` when **all zygote arches it injected have acked** — i.e. both
`status64.zygote_injected` and `status32.zygote_injected` (where applicable per device
ABIs) have matching consumed notifications. MNT_DETACH makes the mounts vanish from
`/proc/mounts` immediately while any still-mapped pages stay alive. The unmount happens in
the shared init namespace the monitor lives in; zygote and all apps fork from that same
namespace and see a clean table. On a 64-bit-only device the monitor waits on the single
injected arch only.

### Restart cycle

On zygote restart the monitor re-mounts (idempotent guard), re-injects, re-collects acks,
and unmounts again. The cycle is identical to boot because the staged upper tree never
goes away.

### Coordination constants

Expected-arch bookkeeping and the ack timeout live as compile-time constants next to the
existing monitor defines. No runtime configuration is added (YAGNI).

## Error handling / failure policy

- **Loader never acks** (crash mid-pass, foreign old build): the monitor holds a bounded
  timeout after the last injection, then **degrades to today's behavior** — overlay stays
  mounted for the session. Boot works; exposure equals the status quo; log once.
- **Monitor dies before unmount**: same degradation; no new failure modes. The existing
  service-side restart path re-enters the normal cycle.
- **Mount fails at monitor start**: proceed unmounted; loader falls back to module-dir
  paths exactly as a staging failure does today. No identifying output on any failure path
  (project-wide rule).
- **Partial injection (one arch ok, other failed)**: unmount on the acks received plus
  timeout for the rest — never leave the overlay mounted solely because a zygote that was
  never injected never acked.

## Testing

1. **Device validation (Pixel 9 Pro, KSU Next):**
   - After boot settles: `grep overlay /proc/<zygote>/mounts` and an app-process
     `/proc/self/mounts` read show zero overlay entries; `/dev/syscfg` appears nowhere.
   - Detector battery (`tech.thessemaj.deviceintelligence.sample`) run clean; INTEL_0009
     inventory unchanged from the baseline captured 2026-10-30 (two ART JIT memfd pairs,
     app odex tmp).
   - Third-party modules (playintegrityfix, tricky_store) still function; companion
     requested **after** unmount still resolves (exercises the `lib_fd` path).
   - `kill` zygote64 → monitor re-injects → mounts table clean again (restart cycle).
   - Timeout degradation: build with the loader ack suppressed → overlay persists, boot
     fine, single log line.
2. **Identity gate:** `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh` → PASS
   (two disjoint neutral builds; no new identity-bearing strings introduced).
3. **Shell regression:** `post-fs-data.sh` with staging intact mounts nothing; `sh -n`
   clean; uninstall flow (`uninstall.sh`) unchanged and still detaches any leftover.

## Licensing

No new third-party code; project remains GPLv3.
