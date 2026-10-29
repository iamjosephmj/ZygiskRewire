# Phase 2: Runtime Relocation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At runtime nothing that executes or communicates lives persistently under `/data/adb`: binaries are copied to a tmpfs directory at boot, every process re-execs via file descriptors (no on-disk path behind any running binary), and the module directory keeps only scripts and the loader library.

**Architecture:** `post-fs-data.sh` creates `/dev/<MOD_ID>` (tmpfs, 0700, per-build random name derived from the identity), copies the daemon/ptracer binaries there, and starts the monitor from that copy. The monitor opens all four binaries (both ABIs) at startup and later re-execs the daemon and tracers via `/proc/self/fd/<n>`; the daemon spawns companions via `/proc/self/exe`. Once the primary daemon reports live, the stub script deletes `<runtime>/bin` — sockets and state stay in the tmpfs dir. TMP_PATH moves from `/data/adb/<id>` to `/dev/<id>` (compile-time, so the loader derives it with no runtime plumbing).

**Tech Stack:** GNU make identity system (Phase 1), C (loader ptracer/monitor, zygiskd), Magisk/KSU/APatch module scripts.

**Spec:** `docs/superpowers/specs/2026-10-28-zygiskrewire-app-space-design.md` (§2 module stub, §4 fd-exec, §6 sockets — rev 3). Manager-app payload (§3) is Phase 4: binaries still install into the module dir this phase; the stub relocates them at boot.

## Global Constraints

- All code and comments in English. Preserve GPL-3.0 LICENSE/NOTICE.
- Build with installed NDK 29.0.14206865 (already the pin since Phase 1).
- Identity contract from Phase 1 is binding: macros via `IDENTITY_CFLAGS`, module files rendered from `@@TOKEN@@` templates by `scripts/render_module.sh`, gate = `scripts/check_identity.sh` (keep-list: `zygisk_module_entry`, `zygisk_companion_entry`, `ZYGISK_ENABLED`, `/zygisk/` module-dir segment; boundary-aware `[^[:alnum:]_]` rule stays).
- Runtime dir name = `/dev/<MOD_ID>`: tmpfs (contents wiped each boot), created `0700` by the stub script. The loader derives it from compile-time `MOD_ID` — no runtime path may be passed between components this phase.
- Every running binary must end up with no existing on-disk file behind it EXCEPT the injected library (module dir, Phase 3's target) and module scripts. Deletion of `<runtime>/bin` must be safe for the lazy 32-bit daemon: the monitor opens all ABIs at startup, before deletion.
- Fallback: if the daemon-state wait times out, the stub leaves `<runtime>/bin` in place (root-visible, neutral names, tmpfs) — logged nowhere identifying.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Generated files never committed.

---

### Task 1: Runtime dir rebase — TMP_PATH to tmpfs

**Files:**
- Modify: `scripts/gen_identity.sh` (ID_TMP_PATH value)
- Modify: `module/src/post-fs-data.sh` (dir creation mode; binary copy; exec from runtime dir)
- Modify: `module/src/sepolicy.rule` (tmpfs dir search for zygote, if absent)
- Modify: `module/src/service.sh` / `module/src/rezygisk.sh` / `webroot` (only if they reference the old dir semantics)

**Interfaces:**
- Produces: `TMP_PATH` = `/dev/<MOD_ID>` everywhere (C macros via IDENTITY_CFLAGS, rendered scripts); runtime layout `<TMP_PATH>/bin/<DAEMON_BIN|PTRACER_BIN><32|64>` (0755, root), sockets + state at `<TMP_PATH>/` root; the monitor's cwd = `<TMP_PATH>`.

- [ ] **Step 1: Change the identity generator**

In `scripts/gen_identity.sh`, change:
```bash
TMP_PATH="/data/adb/${MOD_ID}"
```
to:
```bash
TMP_PATH="/dev/${MOD_ID}"
```
(Consumer scripts render `@@TMP_PATH@@` — no renderer change needed. Verify with `grep -rn 'data/adb' module/src scripts webroot/js | grep -v 'modules'` that no non-module-dir `/data/adb` reference remains.)

- [ ] **Step 2: Rework post-fs-data.sh runtime-dir section**

Replace the `create_sys_perm` block and exec tail:
```sh
export TMP_PATH=@@TMP_PATH@@
rm -rf "$TMP_PATH"
mkdir -p "$TMP_PATH"
chmod 700 "$TMP_PATH"
mkdir -p "$TMP_PATH/bin"
cp "$MODDIR/bin/"* "$TMP_PATH/bin/"
chmod 755 "$TMP_PATH/bin/"*
cp "$MODDIR/module.prop.bak" "$MODDIR/module.prop"
# ... ABI pick unchanged ...
cd "$TMP_PATH"
./bin/@@PTRACER_BIN@@64 monitor &
```
(Drop `chcon u:object_r:system_file:s0` — a tmpfs path with an apex/system label would be an anomaly; default tmpfs labeling + the sepolicy rules in Step 3 are correct. Keep the `ZYGISK_ENABLED` guard and magisk rerun loop untouched. `module.prop.bak` reset stays.)

- [ ] **Step 3: Check sepolicy coverage**

`grep -n 'tmpfs' module/src/sepolicy.rule` — the file already carries `allow zygote tmpfs file *`. Add (idempotently) if not present:
```
allow zygote tmpfs dir { search getattr open read };
```
so the zygote-context loader can traverse `/dev/<MOD_ID>` to reach the socket. Rendered type token `@@SEPOL_TYPE@@` rules stay unchanged.

- [ ] **Step 4: Verify the build and rendered scripts**

Run: `make release 2>&1 | tail -2` then inspect the zip: `unzip -p build/out/SysConfig-*-release.zip post-fs-data.sh | grep -nE 'TMP_PATH|/dev/'`
Expected: `TMP_PATH=/dev/syscfg-…`; `mkdir 700`; binaries copied; exec from `./bin` under runtime dir; no `/data/adb/syscfg` string anywhere in rendered files.

- [ ] **Step 5: Commit**

```bash
git add scripts/gen_identity.sh module/src
git commit -m "feat: relocate runtime dir to per-build tmpfs path

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Monitor fd-exec — no on-disk path behind spawned binaries

**Files:**
- Modify: `loader/src/ptracer/monitor.c` (`ensure_daemon_created` ~line 448; tracer spawn ~line 756-770; new startup-open helper)

**Interfaces:**
- Produces: `static int fd_daemon64, fd_daemon32, fd_tracer64, fd_tracer32;` opened in `init_monitor()` from `./bin/<name>` (cwd = runtime dir); helper `static bool exec_via_fd(int fd, const char *argv0, char *const argv[])` = `fcntl(fd, F_SETFD, 0)` then `execl("/proc/self/fd/<n>", argv0, ..., NULL)`.
- Consumes: runtime dir layout from Task 1 (monitor cwd = `<TMP_PATH>`).

- [ ] **Step 1: Add the startup-open + exec helper**

In `monitor.c`, above `ensure_daemon_created`:
```c
static int bin_fds[2][2]; /* [64bit?0:1][daemon?0:1] rough mapping; use named slots */
static int fd_daemon[2] = { -1, -1 };  /* [0]=32, [1]=64 */
static int fd_tracer[2] = { -1, -1 };  /* [0]=32, [1]=64 */

static int open_bin(const char *name) {
  char path[PATH_MAX];
  snprintf(path, sizeof(path), "./bin/%s", name);
  return open(path, O_RDONLY | O_CLOEXEC | O_EXEC);
}
```
(If `O_EXEC`/`O_PATH` semantics are unavailable on the NDK API level, use `O_RDONLY` — exec-by-fd works with a read fd on Linux.) In `init_monitor()` (before the events loop), open all four: `fd_daemon[1] = open_bin(DAEMON_BIN "64"); fd_daemon[0] = open_bin(DAEMON_BIN "32"); fd_tracer[1] = open_bin(PTRACER_BIN "64"); fd_tracer[0] = open_bin(PTRACER_BIN "32");` — log failures with `LOGE` only (missing 32-bit on 64-bit-only devices is normal; mark the slot unsupported).

```c
static bool exec_via_fd(int fd, const char *argv0, char *const argv[]) {
  if (fd < 0) return false;
  if (fcntl(fd, F_SETFD, 0 /* clear FD_CLOEXEC */) == -1) return false;
  char fdpath[32];
  snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);
  execl(fdpath, argv0, argv[1] /* variadic callers pass full list */, NULL);
  return false;
}
```
(Adapt signature to each call site: daemon needs no extra argv; the tracer call passes `trace <pid> [--restart] [--tango]`.)

- [ ] **Step 2: Convert `ensure_daemon_created` (~line 448)**

Replace the child body:
```c
char daemon_name[PATH_MAX] = "./bin/" DAEMON_BIN;
strcat(daemon_name, is_64bit ? "64" : "32");
execl(daemon_name, daemon_name, NULL);
```
with:
```c
char argv0[64];
snprintf(argv0, sizeof(argv0), "%s%s", DAEMON_BIN, is_64bit ? "64" : "32");
char *const argv[] = { argv0, NULL };
if (!exec_via_fd(fd_daemon[is_64bit ? 1 : 0], argv0, argv)) {
  /* fallback: direct path if fd exec fails */
  char daemon_name[PATH_MAX] = "./bin/" DAEMON_BIN;
  strcat(daemon_name, is_64bit ? "64" : "32");
  execl(daemon_name, daemon_name, NULL);
}
```

- [ ] **Step 3: Convert the tracer spawn (~line 756-770)**

Same pattern in the `fork_dont_care()` child: build argv (`trace`, `pid_str`, `--restart`[, `--tango`]), `exec_via_fd(fd_tracer[...], PTRACER_BIN "<abi>", argv)` with direct-path fallback.

- [ ] **Step 4: Verify — build + strings + rotation**

Run: `make release 2>&1 | tail -2`, then on the shipped ptracer binary: `strings build/out/SysConfig-*-release.zip >/dev/null; unzip -p build/out/SysConfig-*-release.zip lib64/<LIB_NAME>` — extract the ptracer from the zip's `lib64/` (it ships as `libzygisk_ptrace.so` → `bin/<PTRACER_BIN>64` after install; check the staged lib) and confirm: no `./bin/` literal remains in the daemon-spawn code path (the fallback keeps it — assert the FALLBACK only, e.g. `grep -c 'proc/self/fd' <staged ptracer>` ≥ 1), and `scripts/check_identity.sh` still PASSes.

Run: `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -1` — Expected PASS.

- [ ] **Step 5: Commit**

```bash
git add loader/src/ptracer/monitor.c
git commit -m "feat(monitor): spawn daemon and tracers via exec-by-fd

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Companion spawn via /proc/self/exe

**Files:**
- Modify: `zygiskd/src/zygiskd.c` (`spawn_companion` ~line 164, exec at ~line 263; `ZYGISKD_PATH` define ~line 45)

**Interfaces:**
- Consumes: daemon runs from runtime dir (Task 1) or from fd (Task 2) — either way `/proc/self/exe` resolves to the running image.
- Produces: companions exec'd as `/proc/self/exe` with argv0 = `DAEMON_BIN<abi>` (no module suffix, no fd in argv changes beyond existing `companion <fd>` protocol — keep the socketpair protocol byte-identical).

- [ ] **Step 1: Convert spawn_companion**

Replace `non_blocking_execv(ZYGISKD_PATH, eargv)` with `non_blocking_execv("/proc/self/exe", eargv)`; keep `eargv` exactly as-is (`{ process_name, "companion", companion_fd_str, NULL }`, `process_name = DAEMON_BIN LP_SELECT("32","64")` from Phase 1). Delete the now-unused `ZYGISKD_PATH` define (grep consumers first — if anything else uses it, leave the define but switch the value to `/proc/self/exe` semantics only here).

- [ ] **Step 2: Verify**

Run: `make release 2>&1 | tail -2 && ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -1` — Expected: build OK, gate PASS. Confirm the daemon binary contains `/proc/self/exe` and no module-dir `bin/` path: extract the staged daemon (`unzip -p … bin/arm64-v8a/<DAEMON_BIN>64 | strings | grep -c 'proc/self/exe'` ≥ 1).

- [ ] **Step 3: Commit**

```bash
git add zygiskd/src/zygiskd.c
git commit -m "feat(daemon): spawn companions via /proc/self/exe

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Stub orchestration — deletion of runtime binaries after daemon-up

**Files:**
- Modify: `module/src/post-fs-data.sh` (wait-for-daemon + delete `<runtime>/bin`)

**Interfaces:**
- Consumes: state JSON at `<TMP_PATH>/<STATE_NAME>` (monitor writes `"daemon"` state `1` inside the `<CP_BASE>_st` key when the primary daemon reports — Task 7 Phase 1 observed this live); monitor holds all four binary fds from Task 2.

- [ ] **Step 1: Add wait-then-delete after the exec**

In `post-fs-data.sh`, after the monitor background exec (keep `set +e` semantics for the wait — deletion must never fail the boot):
```sh
# INFO: Once the primary daemon has reported in (state file written by the
# monitor), every binary is held open by the monitor via /proc/self/fd.
# Deleting the on-disk copies then leaves no file behind any running process.
(
  i=0
  while [ $i -lt 40 ]; do
    if grep -q '"state": 1' "$TMP_PATH/"*st*.json 2>/dev/null; then
      rm -rf "$TMP_PATH/bin"
      exit 0
    fi
    sleep 0.5
    i=$((i + 1))
  done
  # INFO: Fallback — leave the copies in place (tmpfs, neutral names, 0700 dir).
) &
```
(Bounded 20 s wait; the glob `*st*.json` matches the rendered `STATE_NAME` without spelling it. 64-bit-only devices never see a 32-bit daemon entry — the primary ABI check is sufficient because ALL fds were opened at startup.)

- [ ] **Step 2: Verify rendered script**

Run: `make release 2>&1 | tail -2 && unzip -p build/out/SysConfig-*-release.zip post-fs-data.sh | grep -nE 'rm -rf|grep -q|sleep'` — Expected: the wait-delete block present with rendered `$TMP_PATH`.

- [ ] **Step 3: Commit**

```bash
git add module/src/post-fs-data.sh
git commit -m "feat(stub): delete runtime binaries once daemon is live

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Full gate + end-to-end build verification

**Files:**
- Modify: `scripts/check_identity.sh` (only if the phase introduced a leak class the checker misses — e.g. assert no `/data/adb/<id>` non-module string in rendered scripts; extend ONLY with a failing-evidence-backed case)

**Interfaces:**
- Produces: the standing gate green on the Phase 2 tree; documented negative test.

- [ ] **Step 1: Run the full gate**

Run: `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh 2>&1 | tail -3` — Expected PASS (two disjoint builds). If it fails, the failing file names the regression; fix at source, re-run.

- [ ] **Step 2: Verify rendered artifacts end to end**

Run: `make release && unzip -p build/out/SysConfig-*-release.zip post-fs-data.sh | grep -cE '/data/adb/(re)?zygisk'` — Expected 0. Then `unzip -l build/out/SysConfig-*-release.zip | grep -cE 'zygisk|rezygisk'` — Expected 0.

- [ ] **Step 3: Commit (if the checker changed)**

```bash
git add scripts/check_identity.sh
git commit -m "test(gate): cover runtime-relocation leak classes

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
(If the checker did not change, no commit — the gate run is the verification.)

---

### Task 6: On-device smoke test (needs Joseph's Pixel, KernelSU Next)

**Files:** none created; adb-driven (root shell granted to Shell in KSU).

**Interfaces:**
- Consumes: Phase 2 release zip.

- [ ] **Step 1: Install and reboot**

Run: push the fresh `SysConfig-*-release.zip` to `/data/local/tmp/`, `ksud module install`, `adb reboot`, wait for `sys.boot_completed`.

- [ ] **Step 2: Verify runtime relocation live**

Run on device (root): `ls -la /dev/ | grep syscfg` (expect the 0700 runtime dir), `ls /dev/<id>` (expect sockets + state; `bin` EXPECTED ABSENT after the daemon reported), `ls -l /proc/*/exe | grep data/adb | grep -v modules/playintegrityfix | grep -v modules/zygisk_vector` — every one of our processes' exe must show `(deleted)` paths under `/dev/<id>` or module-dir scripts; nothing under `/data/adb` except the module dir itself. Then the standing greps: `ps -A`, `/proc/net/unix`, `logcat -d`, `ls -R /data/adb` — zygisk/rezygisk hits only in third-party module trees.

- [ ] **Step 3: Verify boot reliability**

Reboot 3× more; each time confirm daemon state `1` and `bin` deleted. Any boot without injection = fallback engaged (bin kept) — record and investigate.

- [ ] **Step 4: Record results** in the phase ledger; failures route to the owning task.

---

## Self-Review Notes

- Spec coverage: §2 stub (T1/T4 — scripts only, no binaries persist), §4 fd-exec (T2, T3 `/proc/self/exe` variant), §6 sockets (T1 — sockets now in 0700 tmpfs dir). §3 (app payload) and §5 (mapping) are Phases 3–4.
- Consistency: `TMP_PATH` value change flows from `gen_identity.sh` through `IDENTITY_CFLAGS` and the `@@TMP_PATH@@` renderer automatically; no other consumer hardcodes the old prefix (verified by grep in T1 Step 1).
- The lazy-32-bit-daemon hazard is closed structurally: all four fds open at monitor startup (T2 Step 1), deletion waits only for the primary daemon (T4).
