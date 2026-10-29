# Phase 5: Manager UI, Signing, Validation Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The payload-carrier app becomes the real manager (root-backed status, start/stop control), release builds are signed and installable, the deferred hardening backlog closes, and the whole system re-validates on the device — including the first live denylist check.

**Architecture:** A minimal `SuShell` utility (plain `su -c` via `Runtime.exec`, no new dependencies) backs the app's status read (`cat /dev/<id>/*.json`) and control (`/dev/<id>/bin/<ptracer> ctl start|stop` — the runtime binaries persist all session since Phase 4 removed the wait-delete). Release signing reads a gitignored keystore via `keystore.properties` with a debug-signing fallback so ad-hoc builds always install. The hardening sweep closes the ledger's deferred minors. Denylist live check and the full battery are device tasks.

**Tech Stack:** Existing scaffold (Java, AGP 9.4.1), toybox shell on device, KernelSU Next (test manager).

**Spec:** `docs/superpowers/specs/2026-10-28-zygiskrewire-app-space-design.md` (§3 manager UI port; Phase 5 residuals listed in the spec's Phase 3 residuals section and both ledgers).

## Global Constraints

- All code and comments in English. Preserve GPL-3.0 LICENSE/NOTICE.
- Phase 1–4 contracts binding: identity macros; rendered templates; gate PASS; runtime dir `/dev/<MOD_ID>` 0700; exec-by-fd; overlay self-mount with `|| true` guards; denylist predicate; boot-from-APK with sha256 fail-safe and mandatory id-key.
- NO new Gradle dependencies (ruling: plain `su -c` via `Runtime.exec`; libsu deliberately not added — YAGNI for cat/ctl).
- Root is requested ONLY on explicit user action (status refresh / control buttons) — never at app launch.
- Signing: keystore gitignored, generated locally; `keystore.properties` gitignored; gradle falls back to the debug signing config when the keystore file is absent (ad-hoc builds stay installable).
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Generated files never committed.

---

### Task 1: SuShell utility + manager status UI

**Files:**
- Create: `app/src/main/java/tech/thessemaj/zygiskrewire/SuShell.java` (static `String run(String cmd)` — `su -c <cmd>` via Runtime.exec with stdout capture and a timeout; returns null on any failure)
- Modify: `app/src/main/java/tech/thessemaj/zygiskrewire/CarrierActivity.java` (extends to manager status: module id card from assets, daemon/zygote state from `su -c cat /dev/<id>/*.json`, module.prop info; a Refresh button invoking root reads on demand; Start/Stop buttons invoking `su -c /dev/<id>/bin/<ptracer><abi> ctl start|stop` — the ptracer name and id come from identity.props/derivation)

**Interfaces:**
- Consumes: `payload/identity.props` (id), tmpfs runtime layout (`/dev/<id>/bin/<PTRACER><abi>`, `/dev/<id>/*.json`).
- Produces: `SuShell.run()` used by Task 2's signing-independent pieces and the activity; status + control surface.

- [ ] **Step 1: SuShell** — exec `su`, feed the command via stdin (`su` without `-c` to avoid quoting issues; write command + `exit` to the process stdin), capture stdout until exit, 5 s watchdog, return stdout or null on exception/timeout. Synchronous; callers run off the main thread.
- [ ] **Step 2: Status UI** — CarrierActivity gains: a status TextView (module id, daemon state parsed from the state json, zygote injected yes/no), a Refresh button (AsyncTask-style thread → SuShell reads → post to UI), Start/Stop buttons (SuShell ctl calls; toast the result). Root-denied/absent states render neutral text ("root unavailable"). State json path derived from the asset-read module id (`/dev/<id>/`).
- [ ] **Step 3: Verify** — `./gradlew assembleDebug` OK; on-device smoke (Task 5 covers full; quick check here: install, launch, grant root via KSU prompt, status populates).
- [ ] **Step 4: Commit** — `feat(app): manager status and control via su shell`

---

### Task 2: Release signing config

**Files:**
- Modify: `app/build.gradle.kts` (signingConfigs reading `keystore.properties` if present; release buildType uses it, else falls back to the debug signing config)
- Modify: `.gitignore` (`keystore.properties`, `*.jks`, `*.keystore` at root/app levels)
- Create: `keystore.properties.example` (committed template documenting the expected keys)

**Interfaces:**
- Produces: `./gradlew assembleRelease` output installable via adb; fresh clones without a keystore still build (debug-signed fallback).

- [ ] **Step 1: Gradle signing config** — if `keystore.properties` exists (storeFile/storePassword/keyAlias/keyPassword), create the release signing config from it; else assign the debug signing config to release so output stays installable. Log which path was taken at configuration time.
- [ ] **Step 2: Keystore generation doc** — the example file documents `keytool -genkeypair -v -keystore release.keystore -alias zygiskrewire -keyalg RSA -keysize 4096 -validity 10000`; README gains one line.
- [ ] **Step 3: Verify** — without keystore.properties: `./gradlew assembleRelease` OK and the APK signature matches debug (`apksigner verify --print-certs` shows the debug cert). With a test keystore.properties + keystore: release signed by that cert. Capture both.
- [ ] **Step 4: Commit** — `build(app): release signing with local keystore fallback`

---

### Task 3: Validation-hardening sweep (deferred minors)

**Files:**
- Modify: `module/src/post-fs-data.sh` (MODULE_ID sed failure path: replace the bare `sed -n` assignment with a guarded extraction that exits 0 cleanly when module.prop is missing)
- Modify: `loader/src/ptracer/remote_csoloader.c` (segs[64] overflow: LOGE on overflow before dropping)
- Modify: `module/src/uninstall.sh` (path-based umount: add a source check via /proc/mounts grep before umount, so a manager's own bind at the same mountpoint is not detached)
- Modify: `app/src/main/AndroidManifest.xml` (tools:node="remove" for androidx startup provider + profileinstaller receiver — trim library-merged components)
- Modify: `webroot/js/...` locale files (drop unused `monitor.button.*` keys across the 15 locales)
- Modify: `scripts/export_payload.sh` (reject a `build/`-prefixed OUT argument — the misuse guard the Makefile wiring renders moot but defense-in-depth wants)
- Modify: `docs/superpowers/notes/phase3-solist-spike.md` + README (fix the find -maxdepth doc nit to depth 4 wording; README build-order note verified present)

**Interfaces:** none new — hardening only.

- [ ] **Step 1: Apply each item** (small, independent; each with its file-scoped reason comment)
- [ ] **Step 2: Verify** — `make release` OK; rendered scripts `sh -n`; gate PASS; `./gradlew assembleDebug` OK (manifest trim must not break androidx core startup — verify the app still builds and the payload check in CarrierActivity still reads assets); webroot grep for `monitor.button` = 0 hits.
- [ ] **Step 3: Commit** — `fix: validation hardening sweep from phase review waves`

---

### Task 4: Denylist live check (device, Pixel 9 Pro / KSU Next)

**Files:** none; adb-driven.

- [ ] **Step 1: Configure** — add a test app to the KSU umount list (via `ksud` App Profile CLI if available, else the KSU manager App Profile → enable "Umount modules" for the chosen app — user may need to toggle in the manager UI). Candidate: a harmless installed app.
- [ ] **Step 2: Verify** — launch the denylisted app; as root: its `/proc/<pid>/mounts` must NOT contain the overlay `/system/lib64` entry; non-denylisted apps (zygote-descended normal apps) still see it; the denylisted app functions normally.
- [ ] **Step 3: Record** — verbatim evidence in the ledger (first live proof of the Phase 3 predicate).

---

### Task 5: Full battery + contingent Magisk/APatch pass (device)

**Files:** none; adb-driven.

- [ ] **Step 1: Full battery** on the Phase 5 build (single module; module dir scripts+webroot only; runtime dir; state; maps headline; VMA names; greps; denylist check from Task 4 re-verified; carrier app).
- [ ] **Step 2: Contingent cross-manager pass** — Magisk/APatch validation REQUIRES flashing those managers, which is a device-destructive user decision. Default: DEFERRED — recorded as pending user environment. If Joseph provides a Magisk-flashed device or approves re-flashing the Pixel, this becomes an install+battery run on that manager.
- [ ] **Step 3: Record** — ledger + spec residuals updated with live results.

---

## Self-Review Notes

- Spec §3 UI port: covered by T1 (status + control; the full webroot parity — module list page — is NOT ported; ruling: status+control is the manager surface, module listing stays in the KSU WebUI. Recorded deviation).
- Signing: T2 covers release installability; Play-store-grade signing is out of scope.
- The Magisk/APatch pass is USER-CONTINGENT by nature (requires flashing managers) — scoped as Step 2 of T5 with an explicit deferral default.
- SuShell thread-safety: synchronous only, callers own threading (documented in the class).
