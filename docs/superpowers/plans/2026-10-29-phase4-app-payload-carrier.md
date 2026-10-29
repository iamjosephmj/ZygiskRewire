# Phase 4: Manager App as Payload Carrier — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every executing binary comes from the installed ZygiskRewire app's APK, not from the module directory: post-fs-data locates the APK, extracts the payload into the tmpfs runtime dir, verifies it against a build-generated identity manifest, and boots from it — leaving `/data/adb/modules/<id>/` with only scripts, `module.prop`, `sepolicy.rule`, and the webroot.

**Architecture:** The fork repo becomes the Android project root (the app scaffold migrates in — required by the open-source commitment; the app code must live in the public fork). A `make app-payload` target exports the four built binaries + loader into `app/src/main/assets/payload/` under their identity names plus `identity.props` (key=value: names, sha256sums, module id). The app build consumes them as assets. `post-fs-data.sh` globs the installed APK, unzips the payload into the runtime dir, verifies sha256s, stages the loader overlay upper from the same tmpfs copy, and boots as in Phase 2/3. `customize.sh` stops installing `bin/`, `lib/`, `lib64/`, and `system/` trees entirely. Manager UI: deferred — the app ships as a silent carrier with a minimal launcher activity; the KSU WebUI remains the interface (its start/stop buttons are removed this phase; they re-home to the app in Phase 5).

**Tech Stack:** AGP 9.4.1 / compileSdk 37 (scaffold versions), GNU make, module zip format, toybox shell.

**Spec:** `docs/superpowers/specs/2026-10-28-zygiskrewire-app-space-design.md` (§3 Manager app as payload carrier; §2 stub; Phase 3 residual cleanups riding this phase: F2 stale-copy cleanup, F4 stacking-seam note, denylist/Magisk live checks at device test).

## Global Constraints

- All code and comments in English. Preserve GPL-3.0 LICENSE/NOTICE.
- Phase 1–3 contracts binding: identity macros; rendered module templates; gate `scripts/check_identity.sh` PASS; runtime dir `/dev/<MOD_ID>` 0700; exec-by-fd; wait-then-delete; directory-scoped overlay self-mount with `|| true` guards; denylist overlay predicate in `umount_root`.
- Ruling (obfuscation deviation from spec §3): payload assets ship UNOBFUSCATED under identity-derived random names. The APK is private to the app (reading it requires root or the app's own uid); XOR-decoding megabytes in toybox shell is impractically slow, and random names are the identity defense. Recorded as a spec deviation.
- Ruling (UI deferral): no manager UI this phase — a minimal launcher activity only (so the app is visible/manageable in Android settings). WebUI remains the manager surface minus start/stop buttons.
- Identity verification is mandatory: the stub refuses to boot the payload if any sha256 in `identity.props` mismatches (fail-safe = no injection, boot continues normally).
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Generated files never committed.

---

### Task 1: Repo restructure — migrate the app into the fork

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `gradle.properties`, `local.properties` (gitignored), `app/**` — migrated from the outer scaffold at `/home/joseph/AndroidStudioProjects/ZygiskRewire/` (the fork repo root is `/home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk/`)
- Modify: `.gitignore` (Gradle build outputs, `local.properties`)

**Interfaces:**
- Produces: `./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk` from the fork root; the ReZygisk Makefile world untouched alongside.

- [ ] **Step 1: Migrate the scaffold**

Copy (not move) the outer scaffold files into the fork root: `app/` (whole tree), `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`. Do NOT copy `local.properties` (machine-specific; create a fresh one pointing at the same SDK: `sdk.dir=$HOME/Android/Sdk`). Adjust `.gitignore` for Gradle outputs (`.gradle/`, `build/`, `local.properties`, `app/build/`).

- [ ] **Step 2: Verify the app builds from the fork root**

Run: `./gradlew assembleDebug 2>&1 | tail -3` — Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`. (First run downloads Gradle/AGP — allow time.)

- [ ] **Step 3: Verify the module build still works**

Run: `make release 2>&1 | tail -1` and `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -1` — Expected: both PASS (the Makefile world is untouched by the restructure).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat gradle.properties .gitignore app
git commit -m "build: migrate manager app into the fork as payload carrier

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Payload export make target

**Files:**
- Modify: `Makefile` (new `app-payload` target, wired as a prerequisite of the app's asset dir)
- Create: `scripts/export_payload.sh` (copies built binaries into `app/src/main/assets/payload/` under identity names; writes `identity.props` with names + sha256s + module id; excludes `.sha256` sidecars)

**Interfaces:**
- Consumes: build outputs (daemon/ptracer per staged ABI under `build/module/release/bin/<arch>/`, loader under `lib/<arch>/`), `build/identity.mk` values.
- Produces: `app/src/main/assets/payload/<identity names>` + `app/src/main/assets/payload/identity.props` (keys: `id`, and `<name>.sha256` per file). `post-fs-data.sh` (Task 4) consumes `identity.props` and the payload entries.

- [ ] **Step 1: Write the exporter**

`scripts/export_payload.sh`: reads `build/identity.mk` values (ID_DAEMON_BIN, ID_PTRACER_BIN, ID_LIB_NAME, ID_MOD_ID); for each staged ABI dir in `build/module/release/bin/` copy `daemon`/`ptracer` binaries to assets under their installed names (`${ID_DAEMON_BIN}32/64`, `${ID_PTRACER_BIN}32/64`); copy the loader(s) as `${ID_LIB_NAME}`; write `identity.props` with `id=${ID_MOD_ID}` and one `<filename>.sha256=<sha256>` line per payload file (sha256sum of the copied file). Set a stable file ordering. Fail hard if the module build outputs are missing.

- [ ] **Step 2: Wire the Makefile target**

`app-payload:` target calling the exporter; the existing `release` target gains `app-payload` as an order-precondition so a module build always refreshes the assets. (Check the current `release` recipe first and append cleanly.)

- [ ] **Step 3: Verify**

Run: `make release 2>&1 | tail -1` then `ls app/src/main/assets/payload/` — Expected: 4–6 binaries (per staged ABIs) + `identity.props`; names match identity.mk values; `sha256sum -c` against the props entries succeeds. Gate PASS.

- [ ] **Step 4: Commit**

```bash
git add Makefile scripts/export_payload.sh app/src/main/assets
git commit -m "build: export module payload into app assets

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
(Note: assets are build products consumed by the APK — committed here intentionally so the app builds reproducibly from a fresh clone without running the NDK build; revisit in Phase 5 if this churns.)

---

### Task 3: App manifest + minimal carrier activity

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (explicit launcher activity)
- Create: `app/src/main/java/tech/thessemaj/zygiskrewire/CarrierActivity.kt` (minimal: displays app name and the module id from assets)

**Interfaces:**
- Consumes: assets from Task 2.
- Produces: an installable, launchable, visible-in-settings app (`tech.thessemaj.zygiskrewire`) with no permissions requested.

- [ ] **Step 1: Manifest + activity**

Manifest: `<application>` with the launcher activity declared explicitly; no permissions. Activity: reads `assets/payload/identity.props`, shows `id` in a TextView; nothing else.

- [ ] **Step 2: Verify**

Run: `./gradlew assembleDebug 2>&1 | tail -2` — BUILD SUCCESSFUL; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets` — payload present in APK.

- [ ] **Step 3: Commit**

```bash
git add app/src/main
git commit -m "feat(app): minimal payload-carrier activity

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Boot-from-APK stub rewrite

**Files:**
- Modify: `module/src/post-fs-data.sh` (APK glob → payload extraction → sha256 verify → overlay upper from tmpfs copy; module-dir binary staging removed)
- Modify: `module/src/customize.sh` (stop installing `bin/`, `lib/`, `lib64/`, `system/` trees; keep scripts, module.prop, sepolicy.rule, webroot; stale-copy cleanup `rm -rf "$MODPATH"/{bin,lib,lib64,system}`)
- Modify: `module/src/uninstall.sh` (unmounts stay; nothing else references binaries)
- Modify: `module/src/service.sh` (if it references module binaries — verify)

**Interfaces:**
- Consumes: installed APK (glob `/data/app/*tech.thessemaj.zygiskrewire*/base.apk`), payload + identity.props inside it.
- Produces: runtime dir contains the extracted binaries + overlay upper (tmpfs), verified before exec; module dir carries no binaries.

- [ ] **Step 1: Rewrite the boot section**

Replace the module-dir binary copy and overlay upper staging with:
```sh
APK=$(ls /data/app/*tech.thessemaj.zygiskrewire*/base.apk 2>/dev/null | head -1)
[ -n "$APK" ] || { exit 0; }   # app not installed: no payload, no injection this boot
mkdir -p "$TMP_PATH/bin" "$TMP_PATH/ovl/lib64" "$TMP_PATH/ovl/lib"
unzip -o -j "$APK" 'assets/payload/*' -d "$TMP_PATH/bin" >/dev/null
# verify
cd "$TMP_PATH/bin" || exit 0
while read -r key value; do
  case "$key" in
    id) [ "$value" = "$(grep -oP '(?<=^id=).*' "$MODDIR/module.prop" 2>/dev/null)" ] || exit 0 ;;
    *.sha256) f="${key%.sha256}"; echo "$value  $f" | sha256sum -c - >/dev/null 2>&1 || exit 0 ;;
  esac
done < identity.props
chmod 755 ./*
# overlay upper = tmpfs copies of the loader (relabel like Phase 3)
cp "${ID_LIB_NAME64:-}" "$TMP_PATH/ovl/lib64/" 2>/dev/null || true
```
(Exact implementation must handle: the two loader names differ per ABI — the props/asset naming must make the 64-bit loader identifiable to the 64-bit tracer and the 32-bit to the 32-bit tracer without ambiguity; simplest: assets include BOTH loader copies named `${ID_LIB_NAME}` under `payload/lib64/` and `payload/lib/` subdirs, and the overlay upperdir becomes `$TMP_PATH/ovl` with subdirs lib/lib64. Design this cleanly in the implementation and keep `mount_overlay`'s contract: lowerdir=real, upperdir=tmpfs tree, workdir on same tmpfs.)
Also: `mount_overlay` upperdir moves from `$MODDIR/system/lib64` to the tmpfs payload tree; the `chcon system_lib_file` relabel stays (`|| true` guards stay).
Remove: the module-dir `cp "$MODDIR/bin/"*` staging entirely.

- [ ] **Step 2: Slim customize.sh**

Delete all `bin/`, `lib/`, `lib64/`, `system/` extraction and their mkdirs; add stale-copy cleanup: `rm -rf "$MODPATH/bin" "$MODPATH/lib" "$MODPATH/lib64" "$MODPATH/system"` (closes the Phase 1/2 upgrade residual F2/F4). Keep: module.prop install + .bak, sepolicy.rule, webroot, service.d script, uninstall.sh, per-module metadata. Module zip shrinks to scripts + webroot only.

- [ ] **Step 3: Verify**

Run: `make release` (zip contains NO bin/lib/system trees — `unzip -l` check); gate PASS. Device test (Task 6) is the functional proof.

- [ ] **Step 4: Commit**

```bash
git add module/src
git commit -m "feat(stub): boot payload from the manager app's APK

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Webroot control-surface adjustment + full gate

**Files:**
- Modify: `webroot/js/pages/actions/index.js` (remove/neutralize the start/stop buttons that exec module-dir binaries — the binaries no longer exist there)
- Modify: `webroot/js/pages/home/index.js`, `modules/index.js` (paths already rendered; verify no module-dir binary references remain)

**Interfaces:**
- Consumes: rendered webroot (identity tokens).
- Produces: webroot shows status (state.json via runtime dir) without binary-exec buttons; no webroot path references a module-dir binary.

- [ ] **Step 1: Neutralize the binary-exec buttons; keep the status surface**

Remove the start/stop action buttons (they exec `<module>/bin/<ptracer> ctl` — that path no longer exists). Status cards (state.json read) stay. Add a neutral notice "control via manager app" placeholder text.

- [ ] **Step 2: Full verification**

Run: `make release 2>&1 | tail -1`; gate PASS; `unzip -p <zip> webroot/js/pages/actions/index.js | grep -cE 'bin/'` = 0; `unzip -l <zip> | grep -cE 'bin/|lib/|lib64/|system/'` = 0; `./gradlew assembleRelease` builds the carrier APK with the same payload.

- [ ] **Step 3: Commit**

```bash
git add webroot
git commit -m "feat(webroot): drop binary-exec control, keep status surface

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: On-device test (Pixel 9 Pro, KSU Next — Joseph's device)

**Files:** none; adb-driven (serial `4B131FDAP006ZJ`; two transports — always `adb -s`).

- [ ] **Step 1: Install carrier APK + module, reboot**

Install `app-debug.apk` (or release) via `adb install`; push + `ksud module install` the Phase 4 zip; uninstall any stale syscfg modules first (`ksud module list` — exactly one must remain after); reboot; boot-wait; settle 45 s.

- [ ] **Step 2: Full battery**

Root checks: exactly one syscfg module; module dir contains NO `bin/`, `lib/`, `lib64/`, `system/`; runtime dir holds the extracted payload + overlay upper; state json `zygote.64 == 1`; zygote maps show `/system/lib64/<LIB_NAME>` file-backed with r--p RELRO; VMA names present; ps/unix/logcat greps 0 (third-party module tags excepted); denylist overlay predicate present in the daemon (strings); the app's package is installed and launchable.

- [ ] **Step 3: Upgrade-path + uninstall-lifecycle test**

Reboot once more (persistence). Then `ksud module uninstall <id>` + reboot: device boots clean with NO ZygiskRewire module and no dangling overlay (unmount-first fix verified).

- [ ] **Step 4: Record results** in the ledger; failures route to owning task.

---

## Self-Review Notes

- Spec §3 coverage: APK payload carrier (T1–T4), identity verification (T4 — sha256 fail-safe), minimal app visibility (T3), webroot status kept (T5). Spec §3's "obfuscated assets" deviates per recorded ruling (private APK + random identity names; toybox XOR impractical). Manager UI port defers to Phase 5 (recorded ruling).
- Boot-flow ordering: APK glob → unzip → verify → overlay upper → mount → monitor exec → wait-delete (now deletes bin/ AND payload copies? NO — the payload copies ARE the running binaries; wait-delete now deletes nothing (no bin/ staging) — the wait-delete block becomes unnecessary this phase and should be REMOVED (the payload copies stay: they are the overlay upper's source and the fd-exec sources). Correct the Task 4 implementation accordingly: the wait-delete block from Phase 2 is REMOVED, replaced by nothing (binaries persist on tmpfs for the boot session, wiped by reboot).
- Upgrade path: stale module-dir trees cleaned by customize.sh (F2/F4 residuals close).
- Denylist predicate unaffected (upper still tmpfs).
