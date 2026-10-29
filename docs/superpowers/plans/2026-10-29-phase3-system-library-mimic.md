# Phase 3: System-Library Mimic — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The injected loader stops being a module-dir file in app memory: it is staged as a systemless overlay at `/system/lib{,64}/<random>.so`, mapped file-backed from there (every app's maps shows a plausible system library), passes memory-vs-file integrity checks exactly like a real linker-loaded library (no text relocations, RELRO read-only after relocation), and any remaining anonymous regions carry kernel-visible ART-plausible VMA names.

**Architecture:** customize.sh stages the loader under `$MODPATH/system/lib{,64}/<LIB_NAME>`; the module framework's own overlay mounts it at `/system/lib{,64}/<LIB_NAME>` during pre-init (all three managers, before zygote). `ptracer.c` switches the injection path to the overlay path; the existing remote openat + file-backed mapping stays. `remote_csoloader` gains RELRO finalization (parse `PT_GNU_RELRO`, `mprotect` read-only after relocations) and the loader link gains `-Wl,-z,text` so text relocations fail the build. Anonymous regions (bss tails) are named via remote `prctl(PR_SET_VMA)`. A bounded spike attempts solist registration.

**Tech Stack:** Module framework systemless overlay (Magisk magic mount / KSU / APatch — mounted before zygote), NDK clang, existing remote-syscall machinery in `remote_csoloader.c`.

**Spec:** `docs/superpowers/specs/2026-10-28-zygiskrewire-app-space-design.md` (§5 System-library mimic stack, anti-cloning hardening, §5 consistency layers — rev 3).

## Global Constraints

- All code and comments in English. Preserve GPL-3.0 LICENSE/NOTICE.
- Phase 1/2 contracts binding: identity macros via IDENTITY_CFLAGS; rendered module templates; gate `scripts/check_identity.sh` PASS (keep-list and boundary-aware rule unchanged); runtime dir `/dev/<MOD_ID>` 0700 with wait-then-delete orchestration; exec-by-fd everywhere.
- The overlay file is the ONLY copy: remove the module-root `lib[64]/` staging (dual copies would let a detector compare module-dir vs /system presence). `ptracer.c`, `customize.sh`, and any webroot references all move to the overlay path.
- Overlay mount visibility is accepted+hidden by existing `umount_root()` (source `magisk`/`KSU`/`APatch` mounts are unmounted in denylisted-app namespaces) — verify at device test; no new mount-hiding code this phase.
- Anti-cloning invariants: mapped exec text pages must be byte-identical to the overlay file (no post-map content patching anywhere); dirty pages only in data segments; `PT_GNU_RELRO` region read-only post-relocation; `DT_TEXTREL` must be absent (link-time assert).
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Generated files never committed.

---

### Task 1: Overlay staging + injection path switch

**Files:**
- Modify: `module/src/customize.sh` (stage loader under `$MODPATH/system/lib{,64}/<LIB_NAME>`; drop module-root `lib[64]/` staging)
- Modify: `loader/src/ptracer/ptracer.c:185` (lib path → overlay path)
- Modify: `scripts/render_module.sh` / templates only if a token reference to `lib64` exists (verify)

**Interfaces:**
- Produces: installed layout `$MODPATH/system/lib64/<LIB_NAME>` (64-bit) and `$MODPATH/system/lib/<LIB_NAME>` (32-bit), overlaid by the framework at `/system/lib64/<LIB_NAME>` / `/system/lib/<LIB_NAME>`; ptracer reads `"/system" LP_SELECT("/lib/", "/lib64/") "/" LIB_NAME`.

- [ ] **Step 1: Switch the ptracer path**

Replace (ptracer.c:185):
```c
char *lib_path = "/data/adb/modules/" MOD_ID "/lib" LP_SELECT("", "64") "/" LIB_NAME;
```
with:
```c
char *lib_path = "/system" LP_SELECT("/lib/", "/lib64/") "/" LIB_NAME;
```
(LP_SELECT: first arg 32-bit → `/system/lib/`, second 64-bit → `/system/lib64/` — matches the existing convention where LP_SELECT("","64") gave `lib`/`lib64`.)

- [ ] **Step 2: Rework customize.sh staging**

In every ABI branch, replace `extract "$ZIPFILE" "lib/<arch>/${ID_LIB_NAME}" "$MODPATH/lib[64]" true` with `extract "$ZIPFILE" "lib/<arch>/${ID_LIB_NAME}" "$MODPATH/system/lib64" true` (32-bit: `$MODPATH/system/lib`), creating the dirs first (`mkdir -p`). Remove the module-root `mkdir "$MODPATH/lib"`/`lib64"` lines. Binaries under `bin/` staging stay unchanged (exec'd via tmpfs copies — Phase 2).

- [ ] **Step 3: Verify staging + no dual copies**

Run: `make release 2>&1 | tail -2` then `unzip -l build/out/SysConfig-*-release.zip | grep -E 'lib|system'` — Expected: loader appears ONLY under `lib/<arch>/` (zip source staging); after install-rendering, `customize.sh` places it solely under `system/lib{,64}/`. Confirm `grep -rn 'MODPATH/lib' module/src/customize.sh | grep -v system` is empty. Gate: `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -1` PASS.

- [ ] **Step 4: Commit**

```bash
git add module/src/customize.sh loader/src/ptracer/ptracer.c
git commit -m "feat: stage loader as systemless overlay, inject from /system path

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Anti-cloning hardening — no-textrel assert + RELRO finalization

**Files:**
- Modify: `loader/Makefile` (~line 106, loader link line): add `-Wl,-z,text`
- Modify: `loader/src/ptracer/remote_csoloader.c` (parse `PT_GNU_RELRO` in `elf_load_dyn_info` or `compute_load_layout`; after relocations and before/within the existing per-segment mprotect finalization at ~line 1054-1078, `mprotect` the RELRO region read-only in the remote process)

**Interfaces:**
- Consumes: existing segment recording `segs[]` and `remote_syscall(SYS_mprotect, ...)` at `remote_csoloader.c:1061`.
- Produces: after load, the RELRO region is PROT_READ (no write), exec text pages clean; build fails if any text relocation is emitted.

- [ ] **Step 1: Link-time textrel assert**

Add `-Wl,-z,text` to the loader shared-lib link line (`loader/Makefile` ~line 106-107, the `-fPIC -shared -Wl,-soname,$(ID_LIB_NAME)` recipe). Build: `make debug` — if it fails with text relocation errors, the loader has textrels that must be fixed at source (investigate; do NOT switch to `-z notext`).

- [ ] **Step 2: RELRO finalization**

In `remote_csoloader.c`: during ELF parsing (`elf_load_dyn_info` / `compute_load_layout` region), read `PT_GNU_RELRO` from the program headers (local fd parsing — the machinery reads phdrs already for PT_LOAD). Record `relro_start` (page-aligned down) and `relro_end` (page-aligned up) rebased by `load_bias`. After `apply_relocations` completes and within/after the `segs[]` mprotect finalization loop, remote-`mprotect(relro_start, relro_size, PROT_READ)`. Failure to mprotect: `LOGE` and continue (a read-write RELRO is a hardening gap, not a load failure) — log neutrally.

- [ ] **Step 3: Verify**

Run: `make release` OK; gate PASS. Then verify the invariant mechanically on a check-tree binary pair: (a) `readelf -d <staged loader>` shows NO `TEXTREL`; (b) `readelf -l <staged loader>` shows a `GNU_RELRO` segment (so the finalization has a region to protect). The memory-side RELRO verification is the device test (Task 6).

- [ ] **Step 4: Commit**

```bash
git add loader/Makefile loader/src/ptracer/remote_csoloader.c
git commit -m "feat(loader): RELRO finalization and no-textrel assert

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: VMA naming for anonymous regions

**Files:**
- Modify: `loader/src/ptracer/remote_csoloader.c` (bss-tail anonymous mappings + PROT_NONE reserve)

**Interfaces:**
- Consumes: existing `remote_syscall` machinery (remote `SYS_prctl` with `PR_SET_VMA`/`PR_SET_VMA_ANON_NAME` = 13/14, name pointer written to remote memory via `write_proc`).
- Produces: anonymous RW regions created during load carry `[anon:libc_malloc]`; the PROT_NONE reserve is left unnamed (reads as a guard region — naming a PROT_NONE range is itself anomalous).

- [ ] **Step 1: Implement remote VMA naming**

Helper: `static bool remote_name_anon(pid_t pid, uintptr_t addr, size_t len, const char *name)` — write the NUL-terminated name into remote stack scratch (reuse the path-write pattern), `remote_syscall(SYS_prctl, {PR_SET_VMA, PR_SET_VMA_ANON_NAME, addr, len, remote_name_ptr})`. Guard: kernel < 5.10 lacks PR_SET_VMA (returns EINVAL) — tolerate silently (`LOGD` only). Apply to each anonymous bss-tail mapping (`910-932`, `964-986` region of the file) with name `[anon:libc_malloc]`.

- [ ] **Step 2: Verify**

Run: `make release` OK; gate PASS; `grep -c PR_SET_VMA loader/src/ptracer/remote_csoloader.c` ≥ 1. Naming visibility itself is device-test (Task 6: `grep '\[anon:' /proc/<zygote>/maps` shows the named region).

- [ ] **Step 3: Commit**

```bash
git add loader/src/ptracer/remote_csoloader.c
git commit -m "feat(loader): name anonymous mappings with plausible VMA labels

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Solist-consistency spike (bounded attempt)

**Files:**
- Modify (only if the spike succeeds): `loader/src/injector/` (post-entry registration via linker internals)
- Create: spike verdict document `docs/superpowers/notes/phase3-solist-spike.md`

**Interfaces:**
- Consumes: the mapped loader's base/size (already passed to `entry`).
- Produces: EITHER a linker-solist entry consistent with the maps path, OR a written feasibility verdict with the blocking reasons.

- [ ] **Step 1: Feasibility probe (read-only)**

Trace how `dl_iterate_phdr` walks solist on API 25 (soinfo fields via `__dl_g_solist` symbol resolution — the elf_util machinery already resolves `__dl_*` symbols, precedent at `remote_csoloader.c:483-499`). Deliverable: the spike note documents the soinfo layout fields needed (next/link pointer, name, phdr, base) and whether linker-internal mutation from the tracer is safe pre-`__libc_init`.

- [ ] **Step 2: Implement OR abort with verdict**

If the probe shows a safe minimal registration (allocate remote soinfo-sized scratch, set name = overlay path, phdr = mapped base's phdrs, link into solist head): implement behind a compile-time default-on flag; verify gate + build. If it requires fragile layout assumptions (Android version-specific offsets that cannot be probed safely), DO NOT implement — write the verdict documenting exactly which assumptions failed, and stop. Both outcomes complete this task; instability found at device test → revert the flag default.

- [ ] **Step 3: Commit**

```bash
git add loader/src docs/superpowers/notes/phase3-solist-spike.md
git commit -m "feat(loader): solist consistency (or spike verdict)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
(If nothing but the verdict was produced, commit only the note.)

---

### Task 5: Full gate + end-to-end verification

**Files:** none expected (checker extension only with failing-evidence-backed leak class)

- [ ] **Step 1: Gate**

Run: `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -2` — PASS.

- [ ] **Step 2: Rendered-artifact verification**

Run: `make release`; verify in the zip: loader present ONLY under `lib/<arch>/` zip-source staging, `customize.sh` renders `$MODPATH/system/lib64` extraction, ptracer binary strings contain `/system/lib64/` and zero `/data/adb/modules/` lib-path strings, `readelf -d` no TEXTREL (on the extracted staged lib), `readelf -l` shows GNU_RELRO. Capture outputs.

- [ ] **Step 3: Commit if checker changed** (else none), message with trailer.

---

### Task 6: On-device smoke test (Pixel 9 Pro, KSU Next — Joseph's device)

**Files:** none; adb-driven.

- [ ] **Step 1: Install + reboot** (push zip, `ksud module install`, `adb reboot`, boot-wait).

- [ ] **Step 2: Mimic verification**

Root shell: `ls -l /system/lib64/ | grep -c syscfg` (overlay file present), `cat /proc/<zygote>/maps | grep lib64 | grep syscfg` (maps shows `/system/lib64/<LIB_NAME>` — THE headline check), RELRO: dump the mapped region perms via `grep -A1 -B1 syscfg /proc/<zygote>/maps` (r--p region present, no w-x anomalies), VMA names (`grep '\[anon:libc_malloc\]' /proc/<zygote>/maps`), third-party modules still load (state json), greps clean (ps/unix/logcat as before), denylisted-app check if feasible (overlay mount absent in a denylisted app's namespace).

- [ ] **Step 3: Reliability** — 3× reboot: injection succeeds every time (overlay mounted before zygote each boot).

- [ ] **Step 4: Record results** in ledger; failures route to owning task.

---

## Self-Review Notes

- Spec §5 coverage: overlay staging + path switch (T1), no-textrel + RELRO (T2 — anti-cloning), VMA naming (T3), solist consistency (T4, bounded spike with explicit abort path), verification (T5/T6). The COW-overwrite alternative and odex piggyback are explicitly NOT here (spec lists piggyback as its own later phase; COW overwrite was rejected).
- LP_SELECT note: ptracer 64-bit reads `/system/lib64/`, 32-bit reads `/system/lib/` — matches `LP_SELECT("/lib/", "/lib64/")` order (32-bit first).
- Overlay timing relies on framework behavior (mount before zygote) — device test T6 Step 2 is the empirical check; if the overlay is absent at injection time, injection fails loudly (openat fails) — same failure shape as any lib path error, caught by T6 immediately.
