#ifndef SOLIST_REGISTER_H
#define SOLIST_REGISTER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* INFO: Compile-time flag for the solist-consistency spike.
           DEFAULT OFF (verdict outcome): the solist head variable the
           registration must anchor on is a linker-internal C++ file-scope
           static ("solist"/"g_solist") and therefore never appears in
           .dynsym — the runtime anchor cannot resolve on any Android
           version. With the flag at 0 the registration compiles to a no-op
           stub. See docs/superpowers/notes/phase3-solist-spike.md for the
           verdict and the conditions a future activation must close.
           Force-enable for experimentation only: -DZKSU_SOLIST_REGISTER=1. */
#ifndef ZKSU_SOLIST_REGISTER
  #define ZKSU_SOLIST_REGISTER 0
#endif

/* INFO: Register the anonymously mapped loader in the linker's solist so that
           dl_iterate_phdr reports it with the same /system overlay path that
           /proc/<pid>/maps shows. Spike artifact, inert unless
           ZKSU_SOLIST_REGISTER is set to 1 at compile time. Every structural
           fact (soinfo field offsets, solist walk shape) is derived at
           runtime and cross-validated against multiple real soinfo entries;
           on any ambiguity the registration is skipped.
           Returns false when compiled out. */
bool solist_register_library(uintptr_t lib_base, size_t lib_size);

#endif /* SOLIST_REGISTER_H */
