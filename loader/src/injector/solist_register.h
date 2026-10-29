#ifndef SOLIST_REGISTER_H
#define SOLIST_REGISTER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* INFO: Register the anonymously mapped loader in the linker's solist so that
           dl_iterate_phdr reports it with the same /system overlay path that
           /proc/<pid>/maps shows. See
           docs/superpowers/notes/phase3-solist-spike.md for the full design
           and feasibility analysis.
           Every structural fact (soinfo field offsets, solist walk shape) is
           derived at runtime and cross-validated against multiple real soinfo
           entries; on any ambiguity the registration is skipped and the
           process keeps the pre-phase-3 behaviour (missing solist entry).
*/
bool solist_register_library(uintptr_t lib_base, size_t lib_size);

#endif /* SOLIST_REGISTER_H */
