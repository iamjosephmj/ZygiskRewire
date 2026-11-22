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
