#ifndef MISC_H
#define MISC_H

#include <inttypes.h>

#ifdef __LP64__
  #define LP_SELECT(lp32, lp64) lp64
#else
  #define LP_SELECT(lp32, lp64) lp32
#endif

#define IS_ISOLATED_SERVICE(uid)      \
  ((uid) >= 90000 && (uid) < 1000000)

struct kernel_version {
  uint8_t major;
  unsigned int minor;
  unsigned int patch;
};

int parse_int(const char *str);

struct kernel_version parse_kversion();

#endif /* MISC_H */