# Transient Overlay Mount Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `/system/lib{,64}` overlayfs a transient injection scaffold — mounted by the monitor before zygote, unmounted (MNT_DETACH) once every injected zygote's loader has consumed it — so no app-observable mount table ever shows it.

**Architecture:** The ptracer monitor owns the overlay lifecycle. It mounts at startup and again on every zygote (re)start detection; each loader instance acks consumption over the existing loader→daemon→monitor message chain; the monitor unmounts when all injected arches acked, or on a 30 s degrade timer. `post-fs-data.sh` keeps only staging and perm finalization.

**Tech Stack:** C (bionic, Android NDK 29.0.14206865, GNU Make), POSIX syscalls (mount/umount2/timerfd), sh (module scripts).

**Spec:** `docs/superpowers/specs/2026-10-30-transient-overlay-mount-design.md`

## Implementation note (deviation from spec, simplification)

The spec sketched 32/64 ack coordination "through marker files in the identity tmpfs dir". Investigation showed a single monitor process already receives messages for both arches (`status64`/`status32`, `monitor.c:48-71`) over its existing datagram channel. Coordination therefore lives in monitor-internal state — no marker files, no cross-daemon protocol. Semantics identical to the spec: one unmount authority, per-arch acks, timeout degrade.

## Global Constraints

- No identity-bearing strings in source: all runtime names come from IDENTITY_CFLAGS (`-DTMP_PATH`, `-DSOCKET_NAME`, `DAEMON_BIN`, `PTRACER_BIN`, `LIB_NAME`). Never hardcode `/dev/syscfg-*` or similar.
- Every failure path degrades to current behavior (overlay stays mounted, boot works). No new `exit()`/fatal paths.
- No new runtime configuration or properties (YAGNI).
- Neutral log output only (existing `LOGD`/`LOGI`/`LOGW` macros, neutral tags).
- Commit messages: plain conventional style, no attribution trailers.
- This codebase has no host-side unit harness for the C targets: per-task verification is a clean compile plus targeted static checks; on-device validation is the final task. Do not invent host test frameworks.
- Build from repo root `/home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk`: `make debug` (also regenerates identity). Full gate: `ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh` → must print PASS.
- Test device: Pixel 9 Pro, adb serial `adb-4B131FDAP006ZJ-IIcBjQ._adb-tls-connect._tcp`, KSU Next with shell root. Root ops from this repo MUST go through a script file under `/tmp` pushed to the device (inline `su` in adb commands is blocked by a repo guard). Uninstall any previously installed ZygiskRewire module before installing a new build (two builds cannot coexist — exclusive ptrace on init).

---

### Task 1: Overlay mount/unmount primitives

**Files:**
- Create: `loader/src/ptracer/overlay_mount.h`
- Create: `loader/src/ptracer/overlay_mount.c`
- Modify: `loader/Makefile:34-35` (`PTRACER_SRCS`)

**Interfaces:**
- Consumes: `-DTMP_PATH` compile flag (guarded by `daemon.h`'s `#error`), `LOGD`/`LOGI`/`LOGW` from ptracer `utils.h`.
- Produces (used by Tasks 2 and 4):
  - `bool overlay_mount_all(void)`
  - `void overlay_unmount_all(void)`
  - `void overlay_notify_injected(bool is_64bit)`
  - `void overlay_notify_consumed(bool is_64bit)`
  - `bool overlay_all_consumed(void)`
  - `int overlay_timer_create(void)`
  - `void overlay_arm_degrade_timer(void)`
  - `void overlay_timer_fired(void)`

- [ ] **Step 1: Create `loader/src/ptracer/overlay_mount.h`**

```c
#ifndef OVERLAY_MOUNT_H
#define OVERLAY_MOUNT_H

#include <stdbool.h>

/* INFO: Lifecycle of the /system/lib{,64} overlay scaffold.
           Mount is idempotent and best-effort: a failure leaves the loader on
           its module-dir fallback path (existing ReadModules fallback) and
           boot continues. Unmount is MNT_DETACH, so mapped loader/module
           pages stay alive regardless. Consumption bookkeeping is per zygote
           arch; teardown happens when every arch that was injected has
           acked, or when the degrade timer fires. */

bool overlay_mount_all(void);

void overlay_unmount_all(void);

/* INFO: Called by the monitor on ZYGOTE{64,32}_INJECTED. Marks the arch as
           injected, resets its consumed flag, and arms the degrade timer. */
void overlay_notify_injected(bool is_64bit);

/* INFO: Called by the monitor on OVERLAY{64,32}_CONSUMED. May trigger the
           teardown inline. */
void overlay_notify_consumed(bool is_64bit);

bool overlay_all_consumed(void);

/* INFO: Creates the degrade timerfd. Returns -1 on failure (timer simply
           never fires; overlay then stays mounted — current behavior). */
int overlay_timer_create(void);

void overlay_arm_degrade_timer(void);

/* INFO: Epoll callback wrapper: drains the timerfd and unmounts regardless
           of pending acks when it expired (fail-safe degrade). */
void overlay_timer_fired(void);

#endif /* OVERLAY_MOUNT_H */
```

- [ ] **Step 2: Create `loader/src/ptracer/overlay_mount.c`**

```c
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <stdio.h>
#include <unistd.h>

#include "overlay_mount.h"
#include "utils.h"

#define OVERLAY_DEGRADE_TIMEOUT_SEC 30

struct overlay_arch_state {
  bool injected;
  bool consumed;
};

static struct overlay_arch_state arch_states[2]; /* [0] = 32-bit, [1] = 64-bit */
static bool overlay_currently_mounted = false;
static int overlay_timer_fd = -1;

static struct overlay_arch_state *arch_state(bool is_64bit) {
  return &arch_states[is_64bit ? 1 : 0];
}

/* INFO: Mirrors the deleted shell guard (grep " $lower overlay "
           /proc/mounts): these targets contain no spaces, so /proc/mounts
           needs no octal unescaping. */
static bool path_is_overlay_mounted(const char *target) {
  FILE *f = fopen("/proc/self/mounts", "re");
  if (f == NULL) return false;

  char source[256], tgt[256], fstype[256];
  char line[1024];
  bool found = false;
  while (fgets(line, sizeof(line), f) != NULL) {
    if (sscanf(line, "%255s %255s %255s", source, tgt, fstype) != 3) continue;
    if (strcmp(tgt, target) == 0 && strcmp(fstype, "overlay") == 0) {
      found = true;
      break;
    }
  }

  fclose(f);

  return found;
}

static bool upper_has_files(const char *upper) {
  DIR *d = opendir(upper);
  if (d == NULL) return false;

  struct dirent *e;
  bool found = false;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    found = true;
    break;
  }

  closedir(d);

  return found;
}

static bool overlay_mount_one(const char *target, const char *upper) {
  if (path_is_overlay_mounted(target)) return true;
  if (!upper_has_files(upper)) return false;

  /* INFO: Same options and layout the removed shell helper used; the .w
             workdir sits beside the upper and was created at staging time. */
  char opts[8192];
  snprintf(opts, sizeof(opts), "lowerdir=%s,upperdir=%s,workdir=%s.w", target, upper, upper);

  if (mount("overlay", target, "overlay", 0, opts) == -1) {
    LOGD("overlay mount %s failed: %s", target, strerror(errno));

    return false;
  }

  LOGI("overlay mounted: %s", target);

  return true;
}

bool overlay_mount_all(void) {
  bool any = overlay_mount_one("/system/lib64", TMP_PATH "/ovl/lib64");
  if (overlay_mount_one("/system/lib", TMP_PATH "/ovl/lib")) any = true;

  overlay_currently_mounted = any;

  return any;
}

void overlay_unmount_all(void) {
  static const char *const targets[] = { "/system/lib64", "/system/lib" };

  for (size_t i = 0; i < sizeof(targets) / sizeof(targets[0]); i++) {
    if (umount2(targets[i], MNT_DETACH) == -1) {
      LOGD("overlay unmount %s failed: %s", targets[i], strerror(errno));

      continue;
    }

    LOGI("overlay unmounted: %s", targets[i]);
  }

  overlay_currently_mounted = false;
}

bool overlay_all_consumed(void) {
  for (size_t i = 0; i < sizeof(arch_states) / sizeof(arch_states[0]); i++) {
    /* INFO: A zygote that was never injected on this device never acks and
               must never hold the overlay open. */
    if (arch_states[i].injected && !arch_states[i].consumed) return false;
  }

  return true;
}

static void overlay_try_teardown(void) {
  if (!overlay_currently_mounted) return;
  if (!overlay_all_consumed()) return;

  LOGI("overlay consumed by all injected zygotes");

  overlay_unmount_all();
}

void overlay_notify_injected(bool is_64bit) {
  struct overlay_arch_state *state = arch_state(is_64bit);
  state->injected = true;
  state->consumed = false;

  overlay_arm_degrade_timer();
}

void overlay_notify_consumed(bool is_64bit) {
  arch_state(is_64bit)->consumed = true;

  overlay_try_teardown();
}

int overlay_timer_create(void) {
  overlay_timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
  if (overlay_timer_fd == -1) {
    LOGD("overlay timerfd create failed: %s", strerror(errno));
  }

  return overlay_timer_fd;
}

void overlay_arm_degrade_timer(void) {
  if (overlay_timer_fd == -1) return;

  /* INFO: Single-shot, re-armed on every injection event, so the deadline is
             always OVERLAY_DEGRADE_TIMEOUT_SEC after the LAST injection. */
  struct itimerspec spec;
  memset(&spec, 0, sizeof(spec));
  spec.it_value.tv_sec = OVERLAY_DEGRADE_TIMEOUT_SEC;
  if (timerfd_settime(overlay_timer_fd, 0, &spec, NULL) == -1) {
    LOGD("overlay timer arm failed: %s", strerror(errno));
  }
}

void overlay_timer_fired(void) {
  uint64_t expirations = 0;
  if (read(overlay_timer_fd, &expirations, sizeof(expirations)) != (ssize_t)sizeof(expirations)) return;

  if (!overlay_currently_mounted) return;
  if (overlay_all_consumed()) return; /* teardown will have run or will run */

  LOGW("overlay degrade timeout: unmounting with pending acks");

  overlay_unmount_all();
}
```

- [ ] **Step 3: Add the new source to the ptracer build**

In `loader/Makefile`, change:

```make
PTRACER_SRCS = src/ptracer/main.c src/ptracer/monitor.c src/ptracer/ptracer.c \
			   src/ptracer/remote_csoloader.c src/ptracer/utils.c
```

to:

```make
PTRACER_SRCS = src/ptracer/main.c src/ptracer/monitor.c src/ptracer/ptracer.c \
			   src/ptracer/remote_csoloader.c src/ptracer/utils.c \
			   src/ptracer/overlay_mount.c
```

- [ ] **Step 4: Verify the build compiles with the new file linked but still unused**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && make debug 2>&1 | tail -5`
Expected: build completes with no errors; a linker warning about unused symbols is acceptable (monitor.c consumes them in Task 2).

- [ ] **Step 5: Commit**

```bash
git add loader/src/ptracer/overlay_mount.h loader/src/ptracer/overlay_mount.c loader/Makefile
git commit -m "loader: overlay mount/unmount primitives with per-arch ack state"
```

---

### Task 2: Monitor wiring — mount at start, remount per zygote, ack handling, degrade timer

**Files:**
- Modify: `loader/src/ptracer/monitor.h` (enum `daemon_command`)
- Modify: `loader/src/ptracer/monitor.c` (`init_monitor()` at line 1067, message switch cases around lines 240-400, `PRE_INJECT` / `PRE_INJECT_TANGO` macros around lines 558-620)

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: monitor accepts datagram cmds `OVERLAY64_CONSUMED = 10` and `OVERLAY32_CONSUMED = 11` (senders implemented in Task 4); monitor mounts/unmounts the overlay scaffold automatically.

- [ ] **Step 1: Extend `enum daemon_command` in `loader/src/ptracer/monitor.h`**

```c
enum daemon_command {
  START = 1,
  STOP = 2,
  EXIT = 3,

  /* sent from daemon */
  ZYGOTE64_INJECTED = 4,
  ZYGOTE32_INJECTED = 5,
  DAEMON64_SET_INFO = 6,
  DAEMON32_SET_INFO = 7,
  DAEMON64_SET_ERROR_INFO = 8,
  DAEMON32_SET_ERROR_INFO = 9,

  /* sent from daemon after the arch's loader acked overlay consumption */
  OVERLAY64_CONSUMED = 10,
  OVERLAY32_CONSUMED = 11
};
```

- [ ] **Step 2: Handle the consumed message and reset state on injection in `monitor.c`**

In the message switch, extend the existing `ZYGOTE64_INJECTED` / `ZYGOTE32_INJECTED` case (currently at `monitor.c:277-286`) and add a new case after it:

```c
      case ZYGOTE64_INJECTED:
      case ZYGOTE32_INJECTED: {
        LOGI("Received Zygote%s injected command", msg->cmd == ZYGOTE64_INJECTED ? "64" : "32");

        struct daemon_status *status = msg->cmd == ZYGOTE64_INJECTED ? &status64 : &status32;
        status->zygote_injected = true;

        /* INFO: A fresh zygote instance has to consume the overlay again;
                   this also arms the degrade timer for the restart cycle. */
        overlay_notify_injected(msg->cmd == ZYGOTE64_INJECTED);

        update_status(NULL);

        break;
      }
      case OVERLAY64_CONSUMED:
      case OVERLAY32_CONSUMED: {
        LOGD("Received overlay consumed from %s loader", msg->cmd == OVERLAY64_CONSUMED ? "64" : "32");

        overlay_notify_consumed(msg->cmd == OVERLAY64_CONSUMED);

        break;
      }
```

- [ ] **Step 3: Remount when a zygote (re)start is detected**

Inside the `PRE_INJECT(abi, is_64)` macro (after the `ensure_daemon_created` check succeeds, just before the macro's closing brace) add:

```c
    /* INFO: Restart cycle: re-mount the scaffold before the tracer maps the
               loader from the overlay path. Idempotent when already mounted. */
    overlay_mount_all();
```

Add the identical call inside `PRE_INJECT_TANGO` at the same position (after its `ensure_daemon_created(false)` check).

- [ ] **Step 4: Mount at startup and register the timer in `init_monitor()`**

In `init_monitor()` (line 1067), after `monitor_events_init();` and before `daemon_listener_init()`, insert:

```c
  /* INFO: Mount the transient overlay scaffold before any zygote can start.
             Non-fatal on failure: loaders fall back to module-dir paths. */
  overlay_mount_all();

  int overlay_timer_fd = overlay_timer_create();
  if (overlay_timer_fd != -1) {
    monitor_events_register_event(overlay_timer_fired, overlay_timer_fd, EPOLLIN | EPOLLET);
  }
```

Add the include at the top of `monitor.c` with the other local includes (after `#include "monitor.h"`):

```c
#include "overlay_mount.h"
```

- [ ] **Step 5: Verify the build**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && make debug 2>&1 | tail -5`
Expected: clean build, no unused-symbol warnings from `overlay_mount.c` anymore.

- [ ] **Step 6: Static check of the teardown decision table**

Confirm by reading the code once (no test harness exists):
- 64-only device, ack from the 64 loader → unmount after that single ack (32 never `injected`).
- 64+32 device → unmount only after both acks.
- ack lost → 30 s timer unmounts.
- monitor died pre-unmount → overlay persists (status quo).

- [ ] **Step 7: Commit**

```bash
git add loader/src/ptracer/monitor.h loader/src/ptracer/monitor.c
git commit -m "loader: monitor owns transient overlay lifecycle with ack-driven teardown"
```

---

### Task 3: Loader ack — `OverlayConsumed` action sent after the module pass

**Files:**
- Modify: `loader/src/include/daemon.h` (enum `daemon_actions`, declarations)
- Modify: `loader/src/common/daemon.c` (new function, near `daemon_zygote_restart()` at line 305)
- Modify: `loader/src/injector/hook.c` (`load_modules_only()`, lines 938-999)

**Interfaces:**
- Consumes: daemon socket write helpers (`daemon_connect`, `safe_write`, `write_uint8_t`) used by every `daemon_*` function in `daemon.c`.
- Produces: `void daemon_overlay_consumed()` — fire-and-forget, sends action byte `OverlayConsumed` (= 9) to the daemon. Called at the tail of `load_modules_only()`.

- [ ] **Step 1: Extend the loader's action enum and declare the function in `loader/src/include/daemon.h`**

```c
enum daemon_actions {
  ZygoteInjected,
  GetProcessFlags,
  GetInfo,
  ReadModules,
  RequestCompanionSocket,
  GetModuleDir,
  ZygoteRestart,
  UpdateMountNamespace,
  RemoveModule,
  OverlayConsumed
};
```

and add to the declaration block:

```c
void daemon_overlay_consumed();
```

- [ ] **Step 2: Implement it in `loader/src/common/daemon.c`** (copy the `daemon_zygote_restart` pattern exactly)

```c
void daemon_overlay_consumed() {
  int fd = daemon_connect(1);
  if (fd == -1) return;

  safe_write(write_uint8_t(fd, (uint8_t)OverlayConsumed), "OverlayConsumed action", return);

  close(fd);
}
```

- [ ] **Step 3: Send the ack at the tail of `load_modules_only()` in `loader/src/injector/hook.c`**

Change the end of the function from:

```c
  free_modules(&ms);

  return true;
}
```

to:

```c
  free_modules(&ms);

  /* INFO: Every overlay path this zygote will ever use was dlopened above;
             tell the daemon so the monitor can tear the scaffold down before
             the first app forks. Fire-and-forget: a lost ack only means the
             overlay stays mounted until the monitor's degrade timeout. */
  daemon_overlay_consumed();

  return true;
}
```

(The early `return false` on `daemon_read_modules` failure intentionally does NOT ack — the daemon is unreachable in that case, and the degrade timer covers it.)

- [ ] **Step 4: Verify the build**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && make debug 2>&1 | tail -5`
Expected: clean build.

- [ ] **Step 5: Commit**

```bash
git add loader/src/include/daemon.h loader/src/common/daemon.c loader/src/injector/hook.c
git commit -m "loader: ack overlay consumption after the module load pass"
```

---

### Task 4: Daemon forward — `OverlayConsumed` → monitor `OVERLAY_CONSUMED`

**Files:**
- Modify: `zygiskd/src/constants.h` (`#define` block at lines 9-12, `enum DaemonSocketAction`)
- Modify: `zygiskd/src/zygiskd.c` (action switch, `ZygoteInjected` case at lines 385-391)

**Interfaces:**
- Consumes: `unix_datagram_sendto(CONTROLLER_SOCKET, &hdr, sizeof(hdr))` pattern, `struct rzd_msg_header`.
- Produces: daemon accepts socket action `OverlayConsumed` (= 9, must match Task 3's enum position) and forwards cmd `OVERLAY_CONSUMED` (10 on 64-bit daemons, 11 on 32-bit — matching Task 2's `monitor.h` values).

- [ ] **Step 1: Extend `zygiskd/src/constants.h`**

Add to the define block:

```c
#define OVERLAY_CONSUMED LP_SELECT(11, 10)
```

and extend the enum:

```c
enum DaemonSocketAction {
  ZygoteInjected         = 0,
  GetProcessFlags        = 1,
  GetInfo                = 2,
  ReadModules            = 3,
  RequestCompanionSocket = 4,
  GetModuleDir           = 5,
  ZygoteRestart          = 6,
  UpdateMountNamespace   = 7,
  RemoveModule           = 8,
  OverlayConsumed        = 9
};
```

(The `LP_SELECT(11, 10)` ordering mirrors `ZYGOTE_INJECTED LP_SELECT(5, 4)`: 32-bit daemons send the higher value, matching `OVERLAY32_CONSUMED = 11` / `OVERLAY64_CONSUMED = 10` in the monitor.)

- [ ] **Step 2: Add the forward case in `zygiskd/src/zygiskd.c`** directly after the `ZygoteInjected` case:

```c
      case OverlayConsumed: {
        struct rzd_msg_header hdr = {
          .cmd = OVERLAY_CONSUMED,
          .len = 0
        };
        unix_datagram_sendto(CONTROLLER_SOCKET, &hdr, sizeof(hdr));

        break;
      }
```

- [ ] **Step 3: Verify the daemon build**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && make debug 2>&1 | tail -5`
Expected: clean build (both loader and zygiskd rebuild).

- [ ] **Step 4: Cross-check enum parity**

Confirm `OverlayConsumed` is position 9 in BOTH `loader/src/include/daemon.h` (`enum daemon_actions`, implicit values) and `zygiskd/src/constants.h` (explicit `= 9`). A mismatch silently corrupts every later action.

- [ ] **Step 5: Commit**

```bash
git add zygiskd/src/constants.h zygiskd/src/zygiskd.c
git commit -m "zygiskd: forward overlay consumption acks to the monitor"
```

---

### Task 5: Shell staging — remove persistent mounts, keep perm finalization

**Files:**
- Modify: `module/src/post-fs-data.sh` (delete `mount_overlay()` and its two call sites, lines ~181-205; add perm-finalization loop in its place)

**Interfaces:**
- Consumes: `$TMP_PATH/ovl/{lib,lib64}` staged by the payload-extraction block above it.
- Produces: upper trees with `0644` perms, `u:object_r:system_lib_file:s0` labels, and `.w` workdirs present — the exact state `overlay_mount_one()` (Task 1) expects at monitor start.

- [ ] **Step 1: Replace the `mount_overlay` block**

Delete the entire block from the `# INFO: Some managers (KSU Next 3.4.0) have no module-mount implementation...` comment through `mount_overlay /system/lib "$TMP_PATH/ovl/lib"`, and replace with:

```sh
# INFO: The monitor mounts the /system/lib{,64} overlays itself (transient
#         scaffold: mounted before zygote, unmounted once every loader has
#         consumed it — see loader/src/ptracer/overlay_mount.c). Staging only
#         finalizes perms, labels, and the overlayfs workdirs so zygote reads
#         the uppers exactly like system libs. No mount happens in the shell
#         anymore, so /proc/mounts never shows an overlay in the app-visible
#         namespace after boot settles.
for bits in lib64 lib; do
  upper="$TMP_PATH/ovl/$bits"
  [ -d "$upper" ] || continue
  chmod 644 "$upper/"* 2>/dev/null || true
  chcon u:object_r:system_lib_file:s0 "$upper/"* 2>/dev/null || true
  mkdir -p "$upper.w" 2>/dev/null || true
done
```

- [ ] **Step 2: Syntax check**

Run: `sh -n /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk/module/src/post-fs-data.sh`
Expected: no output, exit 0.

- [ ] **Step 3: Confirm no other shell references to the removed function remain**

Run: `grep -n "mount_overlay" /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk/module/src/*.sh`
Expected: no matches. (`uninstall.sh` has its own independent overlay-detach logic and must remain untouched.)

- [ ] **Step 4: Commit**

```bash
git add module/src/post-fs-data.sh
git commit -m "module: drop persistent overlay mounts, staging finalizes perms only"
```

---

### Task 6: Identity gate and full build

**Files:**
- None (verification task)

**Interfaces:**
- Consumes: all prior tasks.

- [ ] **Step 1: Build both variants**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && make release 2>&1 | tail -3 && make debug 2>&1 | tail -3`
Expected: both complete; zip artifacts under the zip output dir.

- [ ] **Step 2: Run the identity gate**

Run: `cd /home/joseph/AndroidStudioProjects/ZygiskRewire/ReZygisk && ANDROID_HOME=$HOME/Android/Sdk scripts/check_identity.sh`
Expected: PASS (two disjoint neutral builds). If it flags new strings, check that nothing hardcoded an identity-shaped name — the only new literal paths are `/system/lib64`, `/system/lib`, `/proc/self/mounts`, all neutral.

- [ ] **Step 3: Commit (only if the gate required source fixes)**

If Step 2 needed changes, commit them; otherwise no commit for this task.

---

### Task 7: On-device validation battery

**Files:**
- Create: `/tmp/ovl_verify.sh` (device-side root script, run via adb — NOT committed)

**Interfaces:**
- Consumes: Task 6 release zip installed on the test device.

- [ ] **Step 1: Install the new build**

Per the two-modules gotcha, first uninstall the currently installed module (`syscfg-56532f10`) via the KSU Next manager or `ksud` module uninstall, reboot, then install the fresh zip (`make installKsu`, or install through the KSU manager) and reboot.

- [ ] **Step 2: Write and run the verification script**

```sh
#!/system/bin/sh
# /tmp/ovl_verify.sh — run via: adb shell su -c "sh /data/local/tmp/ovl_verify.sh"
echo "--- app mount table overlay count (expect 0):"
grep -c overlay /proc/mounts
echo "--- zygote mount table:"
grep overlay /proc/$(pidof zygote64 | awk '{print $1}')/mounts || echo "zygote clean"
echo "--- identity dir must not leak into mounts (expect 0):"
grep -c syscfg /proc/mounts
echo "--- loader still mapped in an app process (expect an r-xp /system/lib64 line):"
pid=$(pidof tech.thessemaj.deviceintelligence.sample | awk '{print $1}')
awk '$2 ~ /x/ && /\/system\/lib64\// {print}' /proc/$pid/maps | head -3
```

Push and run: `adb -s adb-4B131FDAP006ZJ-IIcBjQ._adb-tls-connect._tcp push /tmp/ovl_verify.sh /data/local/tmp/ && adb -s adb-4B131FDAP006ZJ-IIcBjQ._adb-tls-connect._tcp shell su -c "sh /data/local/tmp/ovl_verify.sh"`
Expected: zero overlay entries in both app and zygote mount tables; zero `syscfg` strings; the loader's `r-xp /system/lib64/<random>.so` line still present (mapping survived the unmount).

- [ ] **Step 3: Run the detector app and third-party modules**

Open `tech.thessemaj.deviceintelligence.sample`, capture the Signals screen: INTEL_0009 inventory must match the 2026-10-30 baseline (two ART JIT memfd pairs + app odex tmp — no new unbacked-exec regions). Verify playintegrityfix and tricky_store still function via their own status UIs.

- [ ] **Step 4: Zygote restart cycle**

`adb shell su -c "kill $(pidof zygote64)"`, wait for the framework to restart, re-run `/tmp/ovl_verify.sh`.
Expected: zygote re-injected, mount tables overlay-free again (remount → inject → ack → unmount cycle).

- [ ] **Step 5: Degrade path (optional but recommended)**

Temporarily comment out the `daemon_overlay_consumed()` call in `hook.c`, rebuild, reinstall, boot: overlay must remain mounted (grep finds it), boot normal, single `degrade timeout` log line ~30 s after the last injection. Revert the patch afterwards.

- [ ] **Step 6: Record results**

Add a dated results note to `docs/superpowers/notes/` (mounts-clean evidence, restart-cycle evidence, degrade evidence) and commit it.

```bash
git add docs/superpowers/notes/
git commit -m "docs: transient overlay mount validation results"
```
