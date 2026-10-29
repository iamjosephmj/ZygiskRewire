# ZygiskRewire: App-Space Payload Architecture

Date: 2026-10-28 (rev 2 — revised after full codebase investigation)
Status: Draft for review
Base: Fork of PerformanC/ReZygisk @ b5aa415 (GPL-3.0)
Investigation basis: three code audits (boot flow, artifact inventory, injection mechanics) of the
unmodified tree; all file:line references below are verified against b5aa415.

## Problem

ReZygisk is trivially detectable because every runtime artifact advertises itself. Verified inventory:

| Surface | Artifacts today |
|---|---|
| `/proc/1/status` | `TracerPid:` permanently points at the monitor (it `PTRACE_SEIZE`s init, `monitor.c:562-580`) |
| `/proc/*/cmdline` | `./bin/zygisk-ptrace64 monitor`, `./bin/zygiskd64`, `zygisk-ptraceNN trace <pid>`, companions `zygiskd64-<module>` (`monitor.c:446-477, 752-786`; `zygiskd.c:256-273`) |
| `/proc/<pid>/maps` in zygote + every app | file-backed `/data/adb/modules/rezygisk/lib[64]/libzygisk.so` (remote `openat` at `remote_csoloader.c:734-762`, mapped at `851-1002`); module `.so` paths |
| `/proc/net/unix` + filesystem | `/data/adb/rezygisk/init_monitor`, `/data/adb/rezygisk/cp{32,64}.sock` (`daemon.h:44`; `zygiskd.c:43-44`) |
| `/data/adb` | module dir `rezygisk` with `bin/zygiskd{32,64}`, `bin/zygisk-ptrace{32,64}`, `lib[64]/libzygisk.so`, `machikado.*` signature blobs, `state.json`, runtime-rewritten `module.prop` containing "ReZygisk" (`monitor.c:826-885`) |
| SELinux live policy | type `zygisk_file` (`sepolicy.rule`), label `u:object_r:zygisk_file:s0` on sockets (`utils.c:208`) |
| logcat | tags `zygisk-core`, `zygiskd64`, `zygisk-ptrace64`, `zygisk-injector64`, `zygisk-elfutil64`, `zygisk-sh`; "ReZygisk"/"Zygisk" throughout message strings |
| Manager app env | `ZYGISK_ENABLED=1` set in the root-manager process (`hook.c:1110`) |
| Embedded strings | `ZKSU_VERSION` build fingerprint in every binary; `updateJson` URL in `module.prop` |

## Goal

1. Everything that executes is extracted from the ZygiskRewire manager app (a normal installed APK);
   no ELF persists under `/data/adb` after the bootstrap moment.
2. No Zygisk-derived name, path, tag, or string exists anywhere inspectable at runtime
   (cmdline, maps, `/proc/net/unix`, logcat, `/data/adb`, SELinux policy, embedded strings).
3. Identity is randomized per build: observing one build reveals nothing about the next.
4. Zygisk module API stays compatible (modules load, companions work, `ZYGISK_ENABLED` kept).

## Non-Goals (known residuals, explicitly accepted for phase 1)

- **`TracerPid` of init.** The ptrace-init architecture requires the monitor to hold pid 1; the
  tracer pid remains visible in `/proc/1/status`. Phase 1 mitigation: the tracer process has a
  neutral random name and no identifying maps. Eliminating the attachment itself is a phase-2
  architecture question, not a rename.
- **Behavioral detection**: PLT/GOT entries in `libandroid_runtime.so` pointing into anonymous
  executable memory; the extra seccomp filter on kernels < 5.10; ART internals probing. Unchanged
  from ReZygisk today.
- **SELinux rule semantics**: `allow zygote ... execmem` and the `/data`-access rules are behaviorally
  fingerprintable regardless of naming. Only the type/label *names* are randomized.
- **Play Integrity / hardware attestation**, server-side checks.
- **Other modules' own names**: a companion's argv0 embeds the *module's* name; module dirs under
  `/data/adb/modules/<their-name>/zygisk/` belong to those modules. We neutralize our own names only.

## Verified architecture facts the design builds on

- The monitor (`zygisk-ptraceNN monitor`) is started by `post-fs-data.sh` and must start **before
  zygote** (it seizes init at `monitor.c:562-580`; late start = permanent failure). Injection
  catches zygote at `PTRACE_EVENT_EXEC` of `app_processNN`.
- The daemon is spawned **lazily by the monitor** at first zygote exec via `execl("./bin/zygiskd64")`
  relative to the monitor's cwd (`monitor.c:446-477`); the tracer is likewise re-exec'd per zygote
  restart (`monitor.c:752-786`). The monitor therefore needs its binaries available (or open) for the
  whole boot session — "extract, exec, delete" does **not** work for the monitor itself.
- All paths are compile-time string literals in C (`daemon.h:44`, `ptracer.c:184`, `zygiskd.c:41-46`);
  the `TMP_PATH` env var is never read. Renaming = build-system defines, no runtime discovery needed.
- The remote loader applies all relocations externally from the tracer, has no lazy binding, no TLS
  (`PT_TLS` never referenced), and resolves DT_NEEDED only from already-mapped system libs
  (`remote_csoloader.c:439-504, 1019-1040`). **Nothing requires file-backed pages** — path-free
  anonymous mapping is a localized change.
- At post-fs-data there is no `pm`/`system_server`. The installed APK **is** reachable by filesystem
  glob (`/data/app/*<pkg>*/base.apk`): userdata is mounted, paths are stable per install (change on
  app update). The daemon itself resolves packages by filesystem for the same reason
  (`root_impl/common.c:110-146`).
- `loader/src/external/csoloader` and `plti` are git submodules, not checked out. They must be
  checked out and audited before implementation (module-`.so` mapping method is inside CSOLoader).

## Architecture

```
+-----------------------------------------------------------+
| ZygiskRewire app (normal installed APK, tech.thessemaj.zygiskrewire) |
|  payload: daemon, ptracer, loader .so as obfuscated assets |
|  manager UI: status/control over randomized socket         |
+-----------------------------------------------------------+
        |  post-fs-data.sh (stub, random module id R):
        |    glob APK -> unzip assets -> decode -> /dev/.<R>/ (tmpfs)
        v
+-----------------------------------------------------------+
| /dev/.<R>/  tmpfs, random name per boot, random file names |
|  monitor opens all binaries, keeps fds, execs itself        |
|  last: daemon+tracer binaries deleted (monitor re-execs     |
|  them via /proc/self/fd/<n>), dir removed                   |
+-----------------------------------------------------------+
        |  monitor (neutral argv0, fds to binaries kept)
        v
     init seized -> zygote exec observed -> daemon spawned via fd,
     loader mapped into zygote anonymously (no path in maps)
```

### 1. Build-time identity randomization

`make release` generates `identity.mk` (gitignored) and passes everything as `-D` defines:

- Module id: random neutral (`syscfg-<hex>`); daemon/ptracer/loader/socket/state file names: random.
- Log tags: neutral (e.g. `<rand>-core64`), all "ReZygisk"/"Zygisk" message strings rewritten.
- ELF soname of the loader (`-Wl,-soname,libzygisk.so` today), `ZKSU_VERSION` neutralized.
- SELinux type/label: `zygisk_file` -> `<rand>_file` (in `sepolicy.rule` + `utils.c:208`).
- The app build consumes the same `identity.mk` so APK asset names and the module stub agree.

### 2. Module stub (`/data/adb/modules/<R>/`)

Contains only: neutral `module.prop` (no `updateJson`, no Zygisk wording, no emoji status rewrite —
status moves to the app UI), `sepolicy.rule` (renamed type), the two stub scripts, `module.prop.bak`
logic dropped. `post-fs-data.sh` does: locate APK by glob, extract+decode assets to `/dev/.<R>/`,
exec the monitor from there (cwd = `/dev/.<R>`), pass the binary fds.

### 3. Manager app as payload carrier

- Ships daemon + ptracer + loader `.so` for each ABI as size-prefixed XOR-obfuscated assets
  (not recognizable ELFs to a casual `unzip`), names derived from `identity.mk`.
- Provides the manager UI (webroot ported into the app), status, start/stop via the randomized
  control socket, and identity-hash verification (`verify.sh` logic moves in-app; module stub
  re-checks the hash embedded in `module.prop` at boot — mismatch = refuse to start).

### 4. Monitor-side exec hygiene (code change in `monitor.c`)

- At startup the monitor opens daemon/ptracer binaries (both ABIs it may need) and keeps the fds.
- `ensure_daemon_created` / tracer spawn change from `execl("./bin/...")` to
  `execl("/proc/self/fd/<n>", "<neutral argv0>", ...)` with `FD_CLOEXEC` cleared on the execed fd.
- After the daemon for the primary ABI is up, the stub script's cleanup step deletes `/dev/.<R>`;
  the running processes hold open fds, so exec-by-fd keeps working for the session (incl. 32-bit
  lazy daemon and zygote-restart tracers). Fallback if fd-exec proves fragile on a manager:
  keep `/dev/.<R>/` for the session (still tmpfs, random per boot, no identifying names) and accept
  the root-visible tmpfs listing as a residual.

### 5. Loader mapping: system-library mimic stack (decision 2026-10-28, rev 3)

Chosen layering (user-approved), replacing the anonymous-only mapping of rev 2:

**Primary — system-library mimic.** The loader `.so` is staged by `customize.sh` inside the module
stub under a randomized name (`<R>/system/lib64/<rand>.so` layout) so the module framework's
systemless overlay bind-mounts it at `/system/lib64/<rand>.so` during pre-init — the same mechanism
that mounts any module's `system/` tree. Injection maps it **file-backed** from that path
(remote `openat` stays; `remote_csoloader.c` mapping unchanged in mechanism). Every app's
`/proc/self/maps` then shows a legitimate-looking system library. Trade-off accepted: this one ELF
persists on disk inside the stub (root-visible, neutral name). The daemon and ptracer remain
app-payload + tmpfs only.

**Anti-cloning / integrity-check hardening (required):**
- Loader built with **no text relocations** (`DT_TEXTREL` absent — verified for PIC `.so`; assert at
  link time via `-Wl,-z,text` so any textrel fails the build).
- Add **RELRO finalization** to the remote loader (today missing): after relocations, `mprotect`
  the relocation-only region (`PT_GNU_RELRO`) read-only, matching real-linker behavior, so the
  dirty-page signature (dirty data pages only, clean exec text) is byte-for-byte normal.
- Overlay file content must equal the mapped content: the ptracer maps from the overlay path
  itself; no post-map content patching anywhere.

**Stacked consistency layers (Phase 3 implementation residuals, recorded 2026-10-29):**

- The overlay mount is visible in `/proc/mounts` of every app, and the option
  string leaks the runtime dir name (`upperdir=/dev/<MOD_ID>/ovl/...`).
  Additionally, `/system/lib64/<LIB_NAME>` is a file no package database, APEX
  manifest, or OTA manifest knows about — an "unknown system library" detector
  remains possible. On SUSFS-equipped managers the mount entry can be hidden.
- The denylist interaction is handled in code: `umount_root()` unmounts
  type-overlay mounts on `/system/lib{,64}` for denylisted apps (Phase 3 fix
  wave); the live denylist check rides Phase 4/5 device validation.
- Manager-stacking seam: on managers WITH native module mounts (Magisk), the
  manager's mount and the stub overlay stack; the denylist peel reveals the
  stub mount underneath. Validate on Magisk/APatch before broad release.
- Dirty-page byte-compare (memory text vs overlay file) is verified by static
  analysis only (textrel-free link, BIND_NOW, file-backed MAP_PRIVATE,
  relocations data-only); device-side byte-compare rides Phase 4 validation.
- Solist consistency (dl_iterate_phdr cross-check) is deferred: the solist head
  is a linker-internal static unreachable by any safe version-independent
  anchor — see docs/superpowers/notes/phase3-solist-spike.md (VERDICT) and the
  S3/S4/S5 future-activation conditions recorded there.
- **VMA naming**: any mapping that must remain anonymous (fallback path, transient bootstrap) is
  named via `prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME)` mimicking ART-owned region names
  (defeats grep-based anonymous-region detection).
- **Solist consistency**: plant a matching entry in the linker's solist so `dl_iterate_phdr` and
  maps agree (today's total linker invisibility is itself a detector cross-check).

**Fallback — fully anonymous** (rev-2 mechanism, kept): anonymous copy via `process_vm_writev`
+ `mprotect` finalization, used when the overlay path is unavailable (manager without systemless
mount support) and as the phase-1 bring-up milestone. memfd variant dropped (name fingerprint).

**Phase 2 (designed, not built): odex piggyback** — daemon patches each app's `base.odex`
(loader image appended), loader re-homed file-backed from the app's own odex post-fork via the
existing post-specialize hooks. Maximum stealth; requires per-app patch/re-patch on package
events, fallback for odex-less apps, and duplicate-inode-mapping acceptance. Shares all
phase-1 plumbing.

### 6. Sockets

Keep **file** sockets (abstract sockets change the SELinux story for the zygote-context loader and
lose the existing cloaking semantics), but at `/dev/.<R>/<rand>.sock` (tmpfs, per-boot random) for
`init_monitor` + `cp{32,64}`-equivalents; SELinux: zygote gets read/write on tmpfs sockets via the
renamed `zygisk_file`-equivalent type. `/proc/net/unix` shows random tmpfs paths only while
sockets are live. The daemon's `sockcreate` zygote cloaking (`zygiskd.c:160`) is kept.

### 7. Companions and module loading

- Companion `execv` argv0 becomes fully neutral (`<rand>`, no module name); the fd-number argument
  is replaced by an fd received via the existing socketpair instead of argv (drops `zygiskd64-<module>
  companion <fd>` from cmdline).
- Companion `dlopen("/proc/self/fd/<n>")` resolves to the module's real path in its own maps
  (root-only surface, module's own file — accepted residual; noted for phase 2 via memfd copy).
- Module `.so` scanning of `/data/adb/modules/*/zygisk/` is unchanged (API compatibility);
  `GetModuleDir` fd passing unchanged.

## Boot Sequence (revised)

1. Manager applies `sepolicy.rule` (renamed type) in pre-init — unchanged mechanism.
2. `post-fs-data.sh` (stub): glob `/data/app/*tech.thessemaj.zygiskrewire*/base.apk`, extract
   obfuscated assets, decode into `/dev/.<R>/` (tmpfs), verify identity hash against the one
   stamped in `module.prop`, `cd /dev/.<R>`, exec `<rand> monitor` (the ptracer), which opens all
   binary fds and starts. Stub script deletes `/dev/.<R>` once the primary daemon is confirmed up.
3. Monitor seizes init (before zygote — hard timing constraint, same as today).
4. Zygote exec observed -> daemon spawned via `/proc/self/fd/<n>` with neutral argv0; daemon
   `setns`es to init's namespace, scans module dirs, reports state (to the app UI, no
   `module.prop` rewriting, `state.json` replaced by a randomized path or dropped).
5. Tracer injects the loader into zygote via GOT hook (unchanged mechanics) mapping it anonymously.
6. Apps fork; per-app unmount/mount-ns swap, denylist handling, module pre/postSpecialize: unchanged.
7. Manager app UI connects to the randomized socket for status/control.

## Error Handling

- **APK glob empty** (app not installed / renamed package): stub retries briefly, then exits; a
  later manual app open (its UI starts the daemon via `su`) recovers the session. No boot failure.
- **App updated between boots**: glob matches the new randomized dir (package prefix stable);
  identity-hash mismatch (module stub from older build vs newer APK) -> refuse + surface in app UI
  with re-flash instructions.
- **tmpfs unavailable**: fall back to `/data/local/tmp` (flash-touched; logged only to the app UI).
- **fd-exec fails on a manager** (exec-by-fd restrictions): monitor falls back to keeping
  `/dev/.<R>/` for the session (accepted residual, see §4).

## Testing

Pixel 6 Pro (`raven`), Pixel 9 Pro (`caiman`), Android 16, Magisk/KernelSU/APatch as available:

1. Two consecutive builds produce fully disjoint identities (diff all embedded strings).
2. After boot, as root: `grep -rE 'zygisk|rezygisk' /data/adb` -> stub only; `ps -A`,
   `cat /proc/net/unix`, `logcat -d`, `sepolicy` query -> no identifying hits (TracerPid residual
   documented as expected).
3. **Anti-cloning validation**: in a hooked app, compare the mapped `<rand>.so` pages against the
   overlay file byte-for-byte: exec text clean-identical; dirty pages only in the post-RELRO-excluded
   data region; `PT_GNU_RELRO` region read-only; no `DT_TEXTREL`. Repeat against a stock
   linker-loaded library as the control signature.
4. **Consistency validation**: `dl_iterate_phdr` inside a hooked app lists the solist entry matching
   the maps path; anonymous fallback regions carry plausible VMA names.
5. Overlay mount is absent from denylisted apps' mount tables (existing `umount_root` covers it —
   verify, since the mount target is `/system/lib64`, not `/data/adb/modules`).
6. A real third-party Zygisk module loads, its companion works, per-app unmount works.
7. Boot reliability: 10 reboots per device/manager; measure stub->monitor->daemon timing margins.
8. App-update scenario: update APK without re-flashing -> clean refusal + UI guidance.

## Licensing

GPL-3.0 preserved; all modifications published in `iamjosephmj/ZygiskRewire`; NOTICE/README credit
PerformanC/ReZygisk as upstream.
