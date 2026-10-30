# ZygiskRewire: Stealth Architecture & Methodology

ZygiskRewire is a fork of PerformanC/ReZygisk that re-engineers the Zygisk
implementation for stealth: every build carries a fresh random identity, the
runtime lives on tmpfs, the loader presents as a system library, third-party
module libraries load from system overlay paths, and the entire payload ships
inside the manager app's APK. This document describes the methodologies behind
each layer.

## 1. Per-build random identity

Every `make release` runs `scripts/gen_identity.sh`, which mints a complete
identity: module id (`syscfg-<8 hex>`), daemon/ptracer binary names, library
name, socket names, state-file name, SELinux type, and log tags. The identity
is rendered into the boot scripts (template tokens), the manager app's payload
assets, and the C sources (compile-time defines). Two builds share nothing:
no identifier, path, tag, or string survives from one build to the next, so
blocklists built from one install cannot match the next.

Build artifacts are committed only where they are the deliverable: the module
zip and the carrier APK ship per release; intermediate outputs (`out/classes`,
dex objects) are gitignored.

## 2. Boot-from-APK payload delivery

The manager app (`tech.thessemaj.zygiskrewire`) carries the entire payload in
its APK assets: the daemon, the ptracer, and the loader library, each under
its identity name, with `identity.props` (id + per-file sha256).

At boot, `post-fs-data.sh`:

1. Locates the installed APK by glob (`/data/app/*/*/tech.thessemaj.zygiskrewire*/base.apk`, with a `find` fallback for other layouts).
2. Extracts the payload into the tmpfs runtime dir `/dev/<MOD_ID>/`.
3. Verifies every file's sha256 against `identity.props` and requires the `id`
   key to match the rendered module id — any mismatch or missing id exits
   silently (fail-safe: no injection, boot continues).
4. Stages the loader overlay upper from the same tmpfs copy, mounts the
   directory-scoped overlayfs over `/system/lib{,64}`, and execs the monitor
   from the runtime `bin/`.

The module directory keeps only scripts, `module.prop`, `sepolicy.rule`, and
the webroot — no binaries ever persist there. Upgrades automatically prune
stale `syscfg-*` module dirs and their service scripts before they can race
for the init ptrace.

## 3. Tmpfs runtime, exec-by-fd, wait-then-delete semantics

The runtime dir persists for the whole boot session: the extracted binaries
are the overlay upper's source and the fd-exec sources, and the kernel wipes
tmpfs at reboot. Phase 2's wait-then-delete block was removed in Phase 4 when
the payload moved into the APK (the extracted payload IS the session copy).

## 4. System-library mimic (the loader)

The loader library is mapped file-backed from `/system/lib64/<LIB_NAME>` via
the directory-scoped overlayfs, so app memory maps show a plausible system
library. Anti-cloning invariants:

- `-Wl,-z,text`: no text relocations (link fails if any appear).
- Full RELRO: BIND_NOW + `PT_GNU_RELRO` finalized read-only by the remote
  loader after relocations — dirty pages only in data segments, exec text
  byte-identical to the overlay file.

## 5. Third-party module libraries: the same mimic

Third-party Zygisk modules (PIF, validators, your own) are staged at boot into
the overlay upper under neutral names (`ov` + first 8 hex of
sha256(module id), deterministic per module id), with a `.ovl` marker in the
module's zygisk dir. ksud's ReadModules hands the loader the overlay path;
the loader maps the library file-backed from `/system/lib64/<neutral>.so`.
Modules without a marker fall back to their module-dir path (compat).

Known residual: the neutral names are dictionary-invertible (a detector with
a list of popular modules can compute the mapping). Mitigations if ever
needed: per-boot random suffix with the full basename in the root-only marker,
or HMAC with a per-build secret.

## 6. Denylist interaction

`umount_root()` detaches type-overlay mounts on `/system/lib{,64}` for
denylisted apps, removing the loader and all staged module libraries from
their namespaces. The mapped pages can persist in an already-running process
(inherent to the mimic design, same as upstream); recorded as an accepted
residual.

## 7. Root-manager identification without PackageManager

The daemon must know which UID belongs to the root manager: `GetProcessFlags`
compares every specializing process against it and sets `PROCESS_IS_MANAGER`
(the loader then sets `ZYGISK_ENABLED` for the manager and skips the
clean-mount-namespace update for it, and manager processes are excluded from
the denylist branch entirely). But the daemon never asks PackageManager.
Package-based resolution fails exactly when it matters — root managers hide
themselves from PackageManager (Magisk's "Hide the Magisk app" repackages the
app under a random package name; KSU-family managers hide the app or ship
under variant-specific names) — and a PM query from the daemon would put a
recognizable IPC on the binder. Instead, identification is root-side only:

- **`uid_from_pkg()` (`zygiskd/src/root_impl/common.c`)** maps a package name
  to a UID without PackageManager: it walks `/data/user` and stats
  `/data/user_de/<user>/<pkg>` — the device-protected data dir still exists
  and is owned by the app's UID no matter how the package presents to PM.
  `APP_ID()` strips the user portion, so multi-user installs match. On
  failure it returns 0, which is safe as a fail signal because Android never
  assigns UID 0 to an app.
- **Magisk** (`magisk.c`): the active (possibly randomized) package name is
  read from Magisk's own database — `magisk --sqlite "select value from
  strings where key='requester'"` records the repackaged app id — falling
  back to `com.topjohnwu.magisk`, then `uid_from_pkg()`.
- **KernelSU** (`kernelsu.c`): preferred is the kernel's own answer —
  `prctl(KSU_INSTALL_MAGIC1, CMD_GET_MANAGER_UID, ...)` on prctl-style
  builds, `KSU_IOCTL_GET_MANAGER_UID` on ksuctl builds. This is spoof-
  resistant: a fake KSU manager with a different package name still matches,
  because the UID comes from the kernel rather than any data-dir layout.
  Legacy variants fall back to `uid_from_pkg()` over the variant's manager
  package list (`me.weishu.kernelsu`, `com.rifsxd.ksunext`).
- **APatch** (`apatch.c`): fixed package `me.bmax.apatch` via
  `uid_from_pkg()`.

The consequence for stealth: the daemon never opens a channel a detector can
observe or influence (no binder PM calls, no package lists read at runtime),
and manager detection keeps working while the manager is invisible to
PackageManager.

## 8. Residual detector surface (accepted, recorded)

- `TracerPid` of init (ptrace-on-init architecture).
- Solist cross-check: the loader is invisible to `dl_iterate_phdr` (solist
  registration attempted; concluded infeasible without version-fragile
  anchoring — see `docs/superpowers/notes/phase3-solist-spike.md`).
- Unknown-system-lib heuristic (names are opaque; the mapping is
  dictionary-invertible).
- Overlay mount entry in `/proc/mounts` (upperdir leaks the runtime dir name;
  SUSFS-equipped managers can hide it).
- Package name of the carrier app and the (hidden) manager.

## 9. Validation methodology

Every phase lands only after: gate (`scripts/check_identity.sh`) PASS, per-task
review waves with device evidence, a final whole-branch adversarial review, and
a full on-device battery (single module, scripts-only module dir, runtime
persistence, uninstall lifecycle with zero dangling overlays, stealth greps,
maps headline with RELRO tiers, denylist predicate live).
