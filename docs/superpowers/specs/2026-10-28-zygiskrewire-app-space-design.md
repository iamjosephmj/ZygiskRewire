# ZygiskRewire: App-Space Payload Architecture

Date: 2026-10-28
Status: Draft for review
Base: Fork of PerformanC/ReZygisk @ b5aa415 (GPL-3.0)

## Problem

ReZygisk is easy to detect because every runtime artifact advertises itself:

- The daemon runs from `/data/adb/rezygisk/bin/zygiskd` (process names `ReZygiskd64`/`ReZygiskd32`, ptracers `zygisk-ptrace`).
- The injected library is `/data/adb/rezygisk/lib/libzygisk.so`, opened by path inside every hooked app process, so the path appears in `/proc/<pid>/maps`.
- Unix sockets are created under `rezygiskd`-derived paths (`init_monitor`, `TMP_PATH` socket files), visible in `/proc/net/unix`.
- Log strings and `module.prop` literally say "Zygisk"/"ReZygisk".

A detector only needs to list `/data/adb` or grep maps for known names.

## Goal

Runtime identity moves out of `/data/adb` and into ordinary app space:

1. Everything that executes comes from the ZygiskRewire manager app (a normal installed app), not from the root module directory.
2. No Zygisk-derived name, path, or string exists at runtime in any inspectable place (`/proc/*/maps`, `/proc/*/cmdline`, `/proc/net/unix`, `/data/adb`, logcat).
3. Identity is randomized per build, so observing one build reveals nothing about the next.

## Non-Goals

- Hiding from behavioral detectors (PLT-hook scanning, unexpected executable pages). ReZygisk's existing custom-linker strategy remains the answer there; this design removes name/path fingerprints only.
- Defeating Play Integrity hardware attestation or any Google-side server check.
- Compatibility with unmodified Zygisk modules by name (module API compatibility is preserved; only naming changes).

## Architecture

Three components, per approach A as approved:

```
+----------------------------------------------------------+
| ZygiskRewire app (normal installed APK)                   |
|  - payload: daemon + loader .so as obfuscated assets      |
|  - manager UI: status, logs, module webroot               |
+----------------------------------------------------------+
          |  read at boot by root script (unzip assets)
          v
+----------------------------------------------------------+
| /data/adb/modules/<R>/  (R = random neutral module id)    |
|  - module.prop (neutral name/description)                 |
|  - post-fs-data.sh, service.sh: tiny bootstrap scripts    |
|  - NO binaries stored here                                |
+----------------------------------------------------------+
          |  extract to tmpfs, exec, delete
          v
+----------------------------------------------------------+
| /dev/.<R>/  (tmpfs, wiped on reboot, deleted after exec)  |
|  - daemon binary, loader .so, random neutral filenames    |
+----------------------------------------------------------+
          |  daemon (neutral argv[0]) + loader injected
          v
     app processes: maps show no file path (anonymous copy)
```

### 1. Build-time identity randomization

A new Make target step generates `identity.mk` per build:

- Module id/name: random neutral string (e.g. `syscfg-e4b9…`).
- Daemon binary name, ptracer binary name, loader `.so` name: random (e.g. `libwgf.so`, `netd2`, `rmt_storage_b`).
- Socket file/abstract names: random.
- Process argv[0] values: neutral system-ish names.

All names flow into the C sources through `-D` compile definitions (replacing today's hardcoded `zygiskd`, `zygisk-ptrace`, `libzygisk.so`, `init_monitor`, `TMP_PATH` socket names). One `make release` produces one coherent identity; the next build is different.

### 2. Module stub (`/data/adb/modules/<R>/`)

Kept (the module framework requires it for boot execution) but reduced to:

- `module.prop` with the randomized neutral name.
- `post-fs-data.sh` / `service.sh` doing exactly one thing: locate the installed ZygiskRewire APK, extract payload assets to `/dev/.<R>/` (tmpfs), exec the daemon with the neutral argv[0], then `rm -rf /dev/.<R>`.

The payload assets live only inside the app's APK (built into it by the ZygiskRewire app build); `customize.sh` does nothing payload-related. No `.so` or ELF persists under `/data/adb` after boot completes.

### 3. Manager app as payload carrier

The existing `ZygiskRewire` Android Studio scaffold becomes the manager app (namespace `tech.thessemaj.zygiskrewire`):

- Ships daemon + loader binaries as assets in the APK (lightly obfuscated so `unzip` alone doesn't yield recognizable ELFs; extraction script XOR/size-prefix decodes them).
- Provides manager UI (reuses ReZygisk's webroot concept), status checks, and daemon control.
- Ships as a normal APK; nothing about it needs root to install.

### 4. Daemon execution with no on-disk path

- Script extracts daemon to `/dev/.<R>/` (tmpfs: content never touches flash, dies with reboot).
- Daemon is exec'd, then the directory is deleted; the running process keeps a valid text mapping with a deleted path that never re-appears anywhere (it is not in any app's maps, only its own).
- argv[0]/process name set to the randomized neutral value (ReZygisk already does `exec` with a name argument; we feed it the random name).

### 5. Loader mapping with no path in app processes

Current code (`loader/src/ptracer/remote_csoloader.c`) opens `libzygisk.so` **by path in the target process** (`openat` remote syscall), so the path lands in the app's `maps`.

Change: the ptracer reads the loader image itself, then in the target process:

1. remote-allocates anonymous memory (the code already reserves anonymous mappings),
2. copies the ELF image pages in via remote writes,
3. performs relocations/exports through the existing CSOLoader logic,

so `/proc/<pid>/maps` shows only anonymous executable regions with no file path and no memfd name. (Fallback if copy-through is too costly: remote `memfd_create` with a random neutral name — path reads as `/memfd:<random> (deleted)`.)

### 6. Socket hygiene

- Monitor and daemon-companion sockets become abstract-namespace sockets with the per-build random names, or file sockets under `/dev/.<R>/` (deleted at boot end), removing the current `rezygiskd`-path and `TMP_PATH` file sockets from `/proc/net/unix` fingerprints.

## Boot Sequence

1. Magisk/KSU/APatch runs `/data/adb/modules/<R>/post-fs-data.sh` as root.
2. Script finds the ZygiskRewire APK (`pm path tech.thessemaj.zygiskrewire`), extracts payload assets to `/dev/.<R>/`, decodes them.
3. Script execs the daemon (`argv[0]` = neutral name); daemon loads its identity from `identity.mk`-derived compile-time constants.
4. Script deletes `/dev/.<R>`.
5. Daemon ptraces zygote(s); monitor injects the loader into app processes via anonymous-copy mapping.
6. Manager app UI talks to the daemon over the randomized abstract socket for status/control.

## Error Handling

- **App not installed at boot:** daemon cannot start; script retries a bounded number of times, then exits. The manager app, when launched later, can start the daemon via `su` (its normal role), so recovery is a manual app open rather than a boot failure.
- **tmpfs extraction fails** (`/dev` unavailable): fall back to `/data/local/tmp` with `rm` after exec (slightly weaker: touches flash).
- **SELinux:** all extraction/exec happens in the root module-script context (unconfined), which may read the APK and exec from tmpfs. The daemon, once started from the script, inherits that context exactly as ReZygisk's daemon does today. No new policy needed beyond today's `sepolicy.rule`.
- **Identity drift between module stub and app APK:** `verify.sh` compares a per-build identity hash stored in both the module dir and the APK assets; on mismatch the daemon refuses to start and the app UI reports it.

## Testing

Joseph's devices: Pixel 6 Pro (`raven`), Pixel 9 Pro (`caiman`), Android 16, across Magisk/KernelSU/APatch as available.

- Build produces a coherent randomized identity; second build differs everywhere.
- After boot: `grep -r zygisk /data/adb` (as root) hits only the stub scripts; `cat /proc/*/maps | grep -E 'zygisk|rezygisk|/data/adb'` in a hooked app: no hits; `cat /proc/net/unix`: no zygisk names; `ps -A`: no zygisk/ReZygisk names.
- Module loads and a test module (e.g. a trivial `zygisk` module from the ecosystem) confirms API compatibility.
- Boot-time reliability across 10 reboots on each manager root.

## Licensing

The fork stays GPL-3.0: LICENSE is preserved, all modifications are published in this public fork (`iamjosephmj/ZygiskRewire`), and NOTICE/README credit PerformanC/ReZygisk as upstream.
