#ifndef REMOTE_CSOLOADER_ARM32_H
#define REMOTE_CSOLOADER_ARM32_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "utils.h"

#ifdef __aarch64__

bool arm32_csoloader_load(int pid, struct user_regs_struct *regs,
                          struct maps *remote_map,
                          const char *lib_path,
                          uint32_t *out_base, uint32_t *out_size,
                          uint32_t *out_entry);

bool arm32_find_remote_symbol(struct maps *remote_map,
                              const char *lib_path,
                              const char *sym_name,
                              uint32_t *out_addr);

#endif

#endif /* REMOTE_CSOLOADER_ARM32_H */
