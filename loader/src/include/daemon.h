#ifndef DAEMON_H
#define DAEMON_H

#include <stdbool.h>

#include <unistd.h>

enum daemon_actions {
  ZygoteInjected,
  GetProcessFlags,
  GetInfo,
  ReadModules,
  RequestCompanionSocket,
  GetModuleDir,
  ZygoteRestart,
  UpdateMountNamespace,
  RemoveModule
};

struct loaded_modules {
  char **modules;
  size_t modules_count;
};

enum root_impl {
  ROOT_IMPL_NONE,
  ROOT_IMPL_APATCH,
  ROOT_IMPL_KERNELSU,
  ROOT_IMPL_MAGISK
};

struct daemon_info {
  struct loaded_modules modules;
  enum root_impl root_impl;
  pid_t pid;
  bool running;
};

enum mount_namespace_state {
  Clean,
  Mounted
};

#ifndef TMP_PATH
#error "TMP_PATH must come from IDENTITY_CFLAGS"
#endif

static inline const char *daemon_get_path() {
  return TMP_PATH;
}

bool daemon_zygote_injected();

uint32_t daemon_get_process_flags(uid_t uid, const char *const process);

void daemon_get_info(struct daemon_info *info);

void free_daemon_info(struct daemon_info *info);

bool daemon_read_modules(struct loaded_modules *modules);

void free_modules(struct loaded_modules *modules);

int daemon_connect_companion(size_t index);

int daemon_get_module_dir(size_t index);

void daemon_zygote_restart();

bool daemon_update_mns(enum mount_namespace_state nms_state, char *buf, size_t buf_size);

bool daemon_remove_module(size_t index);

#endif /* DAEMON_H */
