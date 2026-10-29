/* INFO: Solist-consistency spike artifact — VERDICT OUTCOME.
           COMPILED OUT BY DEFAULT (ZKSU_SOLIST_REGISTER defaults to 0): the
           anchor this registration requires — the linker's solist head
           variable ("solist"/"g_solist") — is a linker-internal C++
           file-scope static, and statics are LOCAL symbols that never appear
           in .dynsym. The dynsym-only runtime anchor below therefore cannot
           resolve on any Android version; kept as the documented spike
           artifact. See docs/superpowers/notes/phase3-solist-spike.md for
           the verdict and the conditions a future activation must close.

           If force-enabled (-DZKSU_SOLIST_REGISTER=1) with a working anchor,
           it registers the anonymously mapped loader as a linker soinfo
           entry so that dl_iterate_phdr reports it with the same /system
           overlay path that /proc/<pid>/maps shows.

           Grounded structure (verified against AOSP bionic nougat..main):
             - do_dl_iterate_phdr walks "for (si = solist_get_head(); si;
               si = si->next)" and reads exactly:
                 dlpi_addr  = si->link_map_head.l_addr
                 dlpi_name  = si->link_map_head.l_name   (realpath, set at load)
                 dlpi_phdr  = si->phdr
                 dlpi_phnum = si->phnum
                 (modern versions additionally read si->get_tls(), null-checked)
             - "solist" is a plain soinfo* head; insertion is normally done at
               the tail via sonext, but a head insert is coherent on its own:
               later dlopen appends chain after the real tail through sonext
               and never touches the head pointer.

           Safety design (no compiled-in version tables):
             1. All soinfo field offsets are derived at runtime from memory.
             2. The "next" offset is identified as the unique slot that forms
                a long, acyclic, NULL-terminated chain of soinfo-shaped
                records starting at the solist head.
             3. Name/l_addr/phdr/phnum slots are identified per donor by
                content (value == donor base, pointer-to-donor-realpath,
                phdr-table offset, phnum with phdr adjacency) and must agree
                exactly across three independent donor soinfos.
             4. Donors must be real /system or /apex libraries without a
                PT_TLS segment, so the cloned tls_ pointer stays NULL and
                get_tls() yields no TLS module for the clone.
             5. The clone is fully validated before a single release-store
                installs it as the new solist head. Any ambiguity aborts the
                registration (skip = pre-existing behaviour, no solist entry).

           This file runs inside zygote, at the __libc_init GOT hook, before
           __libc_init and before any thread exists. */

#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "logging.h"
#include "misc.h"
#include "solist_register.h"

#if ZKSU_SOLIST_REGISTER

/* INFO: Same string the ptracer maps the loader from (Task 1): the maps path
           and the dl_iterate_phdr name must be byte-identical. */
static const char kOverlayPath[] =
  "/system" LP_SELECT("/lib/", "/lib64/") "/" LIB_NAME;

#define SOLIST_SCAN_WINDOW   (sizeof(void *) == 8 ? 1536 : 768)
#define SOLIST_MIN_CHAIN     5
#define SOLIST_MAX_HOPS      48
#define SOLIST_MAX_PATHS     256
#define SOLIST_MAX_DYNS      512
#define SOLIST_MAX_PHNUM     128
#define SOLIST_DONOR_COUNT   3

/* INFO: Offset classes, one bit per class per scanned slot. */
#define CL_BASE     (1u << 0)
#define CL_NAMEPTR  (1u << 1)
#define CL_PHDR     (1u << 2)
#define CL_PHNUM    (1u << 3)
#define CL_CLASS_MASK \
  (CL_BASE | CL_NAMEPTR | CL_PHDR | CL_PHNUM)

struct path_group {
  const char *path;
  uintptr_t bias;   /* lowest mapping start = load bias */
  uintptr_t end;    /* highest mapping end */
};

/* INFO: ── maps helpers ──────────────────────────────────────────────────── */

static struct map_entry *region_of(struct maps_info *maps, uintptr_t addr) {
  if (!maps) return NULL;

  for (size_t i = 0; i < maps->length; i++) {
    struct map_entry *e = &maps->maps[i];
    if (addr >= e->start && addr < e->end) return e;
  }

  return NULL;
}

static bool window_readable(struct maps_info *maps, uintptr_t addr, size_t len) {
  struct map_entry *e = region_of(maps, addr);

  return e && (uintptr_t)(e->end - addr) >= (uintptr_t)len;
}

static bool read_cstring(struct maps_info *maps, uintptr_t addr, char *out, size_t cap) {
  struct map_entry *e = region_of(maps, addr);
  if (!e) return false;

  size_t avail = (size_t)(e->end - addr);
  if (avail > cap) avail = cap;
  if (avail == 0) return false;

  memcpy(out, (const void *)addr, avail);
  out[cap - 1] = '\0';

  return memchr(out, '\0', avail) != NULL;
}

static const char *path_basename(const char *path) {
  const char *slash = strrchr(path, '/');

  return slash ? slash + 1 : path;
}

/* INFO: Group consecutive path-carrying mappings by path; bias = lowest start. */
static size_t build_path_groups(struct maps_info *maps, struct path_group *groups, size_t cap) {
  size_t count = 0;

  for (size_t i = 0; i < maps->length && count < cap; i++) {
    struct map_entry *e = &maps->maps[i];
    if (!e->path || !*e->path) continue;

    size_t j;
    for (j = 0; j < count; j++) {
      if (strcmp(groups[j].path, e->path) == 0) {
        if (e->start < groups[j].bias) groups[j].bias = e->start;
        if (e->end > groups[j].end) groups[j].end = e->end;

        break;
      }
    }

    if (j < count) continue;

    groups[count].path = e->path;
    groups[count].bias = e->start;
    groups[count].end = e->end;
    count++;
  }

  return count;
}

static const struct path_group *group_by_bias(const struct path_group *groups, size_t count, uintptr_t bias) {
  for (size_t i = 0; i < count; i++) {
    if (groups[i].bias == bias && groups[i].path[0] == '/') return &groups[i];
  }

  return NULL;
}

/* INFO: ── ELF image helpers (in-memory, load-bias relative) ─────────────── */

static bool read_ehdr(struct maps_info *maps, uintptr_t bias, ElfW(Ehdr) *ehdr) {
  if (!window_readable(maps, bias, sizeof(*ehdr))) return false;

  memcpy(ehdr, (const void *)bias, sizeof(*ehdr));

  return ehdr->e_ident[EI_MAG0] == ELFMAG0 &&
         ehdr->e_ident[EI_MAG1] == ELFMAG1 &&
         ehdr->e_ident[EI_MAG2] == ELFMAG2 &&
         ehdr->e_ident[EI_MAG3] == ELFMAG3 &&
         ehdr->e_phentsize == sizeof(ElfW(Phdr)) &&
         ehdr->e_phnum > 0 && ehdr->e_phnum <= SOLIST_MAX_PHNUM &&
         window_readable(maps, bias + ehdr->e_phoff, ehdr->e_phnum * sizeof(ElfW(Phdr)));
}

static bool elf_has_tls(struct maps_info *maps, uintptr_t bias) {
  ElfW(Ehdr) ehdr;
  if (!read_ehdr(maps, bias, &ehdr)) return true; /* unknown: treat as TLS-bearing */

  for (unsigned i = 0; i < ehdr.e_phnum; i++) {
    ElfW(Phdr) phdr;
    uintptr_t at = bias + ehdr.e_phoff + (uintptr_t)i * sizeof(ElfW(Phdr));

    memcpy(&phdr, (const void *)at, sizeof(phdr));
    if (phdr.p_type == PT_TLS) return true;
  }

  return false;
}

static bool elf_phdr_info(struct maps_info *maps, uintptr_t bias, uintptr_t *phoff, size_t *phnum) {
  ElfW(Ehdr) ehdr;
  if (!read_ehdr(maps, bias, &ehdr)) return false;

  *phoff = (uintptr_t)ehdr.e_phoff;
  *phnum = (size_t)ehdr.e_phnum;

  return true;
}

/* INFO: Resolve a linker-internal (__dl_-prefixed) symbol directly from the
           mapped linker image via PT_DYNAMIC, without relying on section
           headers (device linker binaries may not carry any). */
static uintptr_t linker_dynsym_lookup(struct maps_info *maps, uintptr_t bias, const char **names, size_t name_count) {
  ElfW(Ehdr) ehdr;
  if (!read_ehdr(maps, bias, &ehdr)) return 0;

  ElfW(Dyn) *dynamic = NULL;
  for (unsigned i = 0; i < ehdr.e_phnum; i++) {
    ElfW(Phdr) phdr;
    uintptr_t at = bias + ehdr.e_phoff + (uintptr_t)i * sizeof(ElfW(Phdr));

    memcpy(&phdr, (const void *)at, sizeof(phdr));
    if (phdr.p_type == PT_DYNAMIC) {
      dynamic = (ElfW(Dyn) *)(bias + phdr.p_vaddr);

      break;
    }
  }
  if (!dynamic || !window_readable(maps, (uintptr_t)dynamic, sizeof(ElfW(Dyn)))) return 0;

  uintptr_t symtab = 0, strtab = 0;
  size_t strsz = 0, sym_count = 0;
  const ElfW(Dyn) *dyn = dynamic;

  for (size_t i = 0; i < SOLIST_MAX_DYNS && dyn->d_tag != DT_NULL; i++, dyn++) {
    switch (dyn->d_tag) {
      case DT_SYMTAB: symtab = bias + dyn->d_un.d_ptr; break;
      case DT_STRTAB: strtab = bias + dyn->d_un.d_ptr; break;
      case DT_STRSZ: strsz = (size_t)dyn->d_un.d_val; break;
      case DT_HASH: {
        if (window_readable(maps, bias + dyn->d_un.d_ptr, 2 * sizeof(uint32_t))) {
          uint32_t counts[2];

          memcpy(&counts, (const void *)(bias + dyn->d_un.d_ptr), sizeof(counts));
          sym_count = counts[1]; /* nchain == symbol count */
        }

        break;
      }
      default: break;
    }
  }

  if (symtab == 0 || strtab == 0 || strsz == 0 || !window_readable(maps, strtab, strsz)) return 0;

  /* INFO: DT_HASH absent (modern linkers): derive the upper symbol bound from
             GNU_HASH: highest bucket index, then walk the chain until the
             end-of-chain bit. */
  if (sym_count == 0) {
    dyn = dynamic;
    for (size_t i = 0; i < SOLIST_MAX_DYNS && dyn->d_tag != DT_NULL; i++, dyn++) {
      if (dyn->d_tag != DT_GNU_HASH) continue;

      uintptr_t gnu = bias + dyn->d_un.d_ptr;
      uint32_t hdr[4];

      if (!window_readable(maps, gnu, sizeof(hdr))) return 0;

      memcpy(&hdr, (const void *)gnu, sizeof(hdr));

      uint32_t nbucket = hdr[0], symoffset = hdr[1], bloom_size = hdr[2];
      const uint32_t *buckets = (const uint32_t *)(gnu + sizeof(hdr) + (uintptr_t)bloom_size * sizeof(uintptr_t));

      if (!window_readable(maps, (uintptr_t)buckets, (size_t)nbucket * sizeof(uint32_t))) return 0;

      uint32_t max_index = 0;
      for (uint32_t b = 0; b < nbucket; b++) {
        uint32_t v;

        memcpy(&v, &buckets[b], sizeof(v));
        if (v > max_index) max_index = v;
      }

      if (max_index < symoffset) {
        sym_count = symoffset;

        break;
      }

      const uint32_t *chain = buckets + nbucket;
      uint32_t idx = max_index;

      while (idx >= symoffset && idx < symoffset + 0x100000u) {
        uint32_t chain_val;

        if (!window_readable(maps, (uintptr_t)&chain[idx - symoffset], sizeof(chain_val))) return 0;

        memcpy(&chain_val, &chain[idx - symoffset], sizeof(chain_val));
        if (chain_val & 1u) break; /* end of chain */

        idx++;
      }

      sym_count = (size_t)idx + 1;

      break;
    }
  }

  if (sym_count == 0 || sym_count > 0x100000u) return 0;
  if (!window_readable(maps, symtab, sym_count * sizeof(ElfW(Sym)))) return 0;

  char name_buf[128];
  for (size_t i = 0; i < sym_count; i++) {
    ElfW(Sym) sym;

    memcpy(&sym, (const void *)(symtab + i * sizeof(ElfW(Sym))), sizeof(sym));
    if (sym.st_name == 0 || sym.st_name >= strsz || sym.st_shndx == SHN_UNDEF || sym.st_value == 0) continue;

    const char *name = (const char *)(strtab + sym.st_name);
    if (!read_cstring(maps, (uintptr_t)name, name_buf, sizeof(name_buf))) continue;

    for (size_t n = 0; n < name_count; n++) {
      if (strcmp(name_buf, names[n]) == 0) return bias + sym.st_value;
    }
  }

  return 0;
}

/* INFO: ── soinfo plausibility + offset identification ──────────────────── */

/* INFO: A record is soinfo-shaped when some slot holds the exact load bias of
           a mapped file-backed library and some slot points at that image's
           program header table. Both facts are known ground truth. */
static bool soinfo_plausible(struct maps_info *maps, const struct path_group *groups, size_t group_count, uintptr_t node, size_t window) {
  /* INFO: Clamp the window to the mapped region holding this record: soinfo
             blocks live in large linker allocator regions, but be defensive. */
  struct map_entry *e = region_of(maps, node);
  if (!e) return false;
  if ((size_t)(e->end - node) < window) window = (size_t)(e->end - node);

  bool have_base = false, have_phdr = false;

  for (size_t off = 0; off + sizeof(uintptr_t) <= window; off += sizeof(uintptr_t)) {
    uintptr_t v;

    memcpy(&v, (const void *)(node + off), sizeof(v));

    if (!have_base && group_by_bias(groups, group_count, v)) have_base = true;

    if (!have_phdr) {
      /* INFO: phdr points at the image's program header table: within the
                 first 0x400 bytes of a mapped library image. */
      for (size_t g = 0; g < group_count; g++) {
        if (v >= groups[g].bias && v - groups[g].bias <= 0x400 && v > groups[g].bias) {
          if (window_readable(maps, v, sizeof(ElfW(Phdr)))) have_phdr = true;

          break;
        }
      }
    }

    if (have_base && have_phdr) return true;
  }

  return false;
}

/* INFO: Unique slot X such that following *(node + X) from the solist head
           forms a long acyclic chain of soinfo-shaped records ending at NULL. */
static bool find_next_offset(struct maps_info *maps, const struct path_group *groups, size_t group_count, uintptr_t head, size_t window, size_t *out_off) {
  size_t found = 0, found_off = 0;

  for (size_t x = 0; x + sizeof(uintptr_t) <= window; x += sizeof(uintptr_t)) {
    uintptr_t node = head, visited[SOLIST_MAX_HOPS];
    size_t hops = 0;
    bool ok = true, opaque_used = false;

    while (true) {
      if (!window_readable(maps, node, window)) {
        ok = false;

        break;
      }

      if (!soinfo_plausible(maps, groups, group_count, node, window)) {
        /* INFO: Tolerate at most one opaque entry (e.g. linker stub soinfos
                   with a zero base on old versions). */
        if (opaque_used || hops >= SOLIST_MAX_HOPS) {
          ok = false;

          break;
        }

        opaque_used = true;
      }

      for (size_t i = 0; i < hops; i++) {
        if (visited[i] == node) {
          ok = false; /* cycle */

          break;
        }
      }
      if (!ok) break;

      if (hops < SOLIST_MAX_HOPS) visited[hops] = node;
      hops++;

      uintptr_t next;
      memcpy(&next, (const void *)(node + x), sizeof(next));

      if (next == 0) break; /* proper chain end */
      node = next;

      if (hops > SOLIST_MAX_HOPS) {
        ok = false;

        break;
      }
    }

    if (ok && hops >= SOLIST_MIN_CHAIN) {
      if (found && found_off != x) return false; /* ambiguous */

      found++;
      found_off = x;
    }
  }

  if (!found) return false;

  *out_off = found_off;

  return true;
}

/* INFO: Per-donor slot classification by content. */
static void classify_donor(struct maps_info *maps, uintptr_t node, size_t window,
                           uintptr_t base, const char *path, uintptr_t phoff, size_t phnum,
                           uint8_t *classes) {
  char path_buf[256];

  for (size_t off = 0; off + sizeof(uintptr_t) <= window; off += sizeof(uintptr_t)) {
    uintptr_t v;

    memcpy(&v, (const void *)(node + off), sizeof(v));

    if (v == base) classes[off] |= CL_BASE;

    if (v >= base && v - base <= 0x400 && (v - base) == phoff && window_readable(maps, v, sizeof(ElfW(Phdr)))) {
      classes[off] |= CL_PHDR;
    }

    if (read_cstring(maps, v, path_buf, sizeof(path_buf)) && strcmp(path_buf, path) == 0) {
      classes[off] |= CL_NAMEPTR;
    }
  }

  /* INFO: phnum: exact value match adjacent (<= 16 bytes) to the phdr slot.
             phdr and phnum are declared adjacently in every observed soinfo
             generation (nougat..main). */
  for (size_t off = 0; off + sizeof(uintptr_t) <= window; off += sizeof(uintptr_t)) {
    uintptr_t v;

    memcpy(&v, (const void *)(node + off), sizeof(v));
    if (v != (uintptr_t)phnum) continue;

    for (size_t p = (off > 16 ? off - 16 : 0); p <= off + 16 && p + sizeof(uintptr_t) <= window; p += sizeof(uintptr_t)) {
      if (classes[p] & CL_PHDR) {
        classes[off] |= CL_PHNUM;

        break;
      }
    }
  }
}

static bool offset_sets_equal(const uint8_t *a, const uint8_t *b, size_t window, uint8_t mask) {
  for (size_t off = 0; off < window; off++) {
    if ((a[off] & mask) != (b[off] & mask)) return false;
  }

  return true;
}

static bool class_nonempty(const uint8_t *classes, size_t window, uint8_t bit) {
  for (size_t off = 0; off < window; off++) {
    if (classes[off] & bit) return true;
  }

  return false;
}

/* INFO: ── main entry ────────────────────────────────────────────────────── */

bool solist_register_library(uintptr_t lib_base, size_t lib_size) {
  if (lib_base == 0 || lib_size == 0) return false;

  struct maps_info *maps = parse_maps_safe("self");
  if (!maps) {
    LOGW("solist: failed to parse own maps");

    return false;
  }

  /* INFO: Threading check. entry() runs on the __libc_init GOT hook before
             __libc_init; zygote has no threads at this point. Log it: even if
             a thread existed, the clone is fully built before one release
             store, so a concurrent walker sees a consistent list either way. */
  {
    char status[1024] = { 0 };
    int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);

    if (fd >= 0) {
      ssize_t n = read(fd, status, sizeof(status) - 1);

      close(fd);
      if (n > 0) {
        const char *threads = strstr(status, "Threads:");

        LOGD("solist: %s", threads ? threads : "Threads: unknown");
      }
    }
  }

  /* INFO: Locate the linker image (API 29+: /apex/com.android.runtime/bin,
             older: /system/bin). */
  uintptr_t linker_bias = 0;
  {
    const char *want = LP_SELECT("linker", "linker64");

    for (size_t i = 0; i < maps->length; i++) {
      struct map_entry *e = &maps->maps[i];
      if (!e->path || strcmp(path_basename(e->path), want) != 0) continue;

      if (linker_bias == 0 || e->start < linker_bias) linker_bias = e->start;
    }
  }
  if (linker_bias == 0) {
    LOGW("solist: linker image not found in maps");

    free_maps(maps);

    return false;
  }

  /* INFO: Resolve the solist head pointer. */
  const char *solist_names[] = { "__dl_g_solist", "__dl_solist" };
  uintptr_t solist_addr = linker_dynsym_lookup(maps, linker_bias, solist_names, 2);
  if (solist_addr == 0 || !window_readable(maps, solist_addr, sizeof(uintptr_t))) {
    LOGW("solist: __dl_g_solist not resolvable from linker dynsym");

    free_maps(maps);

    return false;
  }

  uintptr_t head = 0;
  memcpy(&head, (const void *)solist_addr, sizeof(head));
  if (head == 0) {
    LOGW("solist: solist head is empty");

    free_maps(maps);

    return false;
  }

  struct path_group groups[SOLIST_MAX_PATHS];
  size_t group_count = build_path_groups(maps, groups, SOLIST_MAX_PATHS);
  size_t window = SOLIST_SCAN_WINDOW;

  /* INFO: Clamp the scan window to the mapped region of the head node. */
  {
    struct map_entry *e = region_of(maps, head);
    if (e && (size_t)(e->end - head) < window) window = (size_t)(e->end - head);
    if (window < 256) {
      LOGW("solist: head node window too small");

      free_maps(maps);

      return false;
    }
  }

  /* INFO: Identify the "next" slot. */
  size_t next_off = 0;
  if (!find_next_offset(maps, groups, group_count, head, window, &next_off)) {
    LOGW("solist: next offset not uniquely identifiable");

    free_maps(maps);

    return false;
  }
  LOGD("solist: next offset %zu", next_off);

  /* INFO: Walk the chain, collect donor candidates: real /system or /apex
             libraries without PT_TLS. */
  uintptr_t donors[SOLIST_DONOR_COUNT];
  size_t donor_count = 0;
  {
    uintptr_t node = head;

    for (size_t hop = 0; hop < SOLIST_MAX_HOPS && node != 0; hop++) {
      uintptr_t base = 0;
      const struct path_group *lib = NULL;

      for (size_t off = 0; off + sizeof(uintptr_t) <= window && !lib; off += sizeof(uintptr_t)) {
        uintptr_t v;

        memcpy(&v, (const void *)(node + off), sizeof(v));
        lib = group_by_bias(groups, group_count, v);
        if (lib) base = v;
      }

      if (lib && donor_count < SOLIST_DONOR_COUNT &&
          lib->path[0] == '/' && strstr(lib->path, "/lib") != NULL &&
          !elf_has_tls(maps, base)) {
        donors[donor_count++] = node;
      }

      uintptr_t next;
      memcpy(&next, (const void *)(node + next_off), sizeof(next));
      node = next;
    }
  }

  if (donor_count < SOLIST_DONOR_COUNT) {
    LOGW("solist: only %zu eligible donors", donor_count);

    free_maps(maps);

    return false;
  }

  /* INFO: Clamp the window to every donor's region: classification and the
             clone copy read this window without per-read region checks. */
  for (size_t d = 0; d < donor_count; d++) {
    struct map_entry *e = region_of(maps, donors[d]);

    if (e && (size_t)(e->end - donors[d]) < window) window = (size_t)(e->end - donors[d]);
  }
  if (window < 256) {
    LOGW("solist: donor window too small");

    free_maps(maps);

    return false;
  }

  /* INFO: Classify all three donors; every class must yield identical slot
             sets and every class must be present. */
  uint8_t ref_classes[1536] = { 0 }, cur_classes[1536] = { 0 };
  {
    uintptr_t base = 0;
    const struct path_group *lib = NULL;

    for (size_t off = 0; off + sizeof(uintptr_t) <= window && !lib; off += sizeof(uintptr_t)) {
      uintptr_t v;

      memcpy(&v, (const void *)(donors[0] + off), sizeof(v));
      lib = group_by_bias(groups, group_count, v);
      if (lib) base = v;
    }
    if (!lib) {
      LOGW("solist: donor 0 base not identified");

      free_maps(maps);

      return false;
    }

    uintptr_t phoff = 0;
    size_t phnum = 0;
    if (!elf_phdr_info(maps, base, &phoff, &phnum)) {
      LOGW("solist: donor 0 elf header unreadable");

      free_maps(maps);

      return false;
    }

    classify_donor(maps, donors[0], window, base, lib->path, phoff, phnum, ref_classes);
  }

  for (size_t d = 1; d < SOLIST_DONOR_COUNT; d++) {
    uintptr_t base = 0;
    const struct path_group *lib = NULL;

    for (size_t off = 0; off + sizeof(uintptr_t) <= window && !lib; off += sizeof(uintptr_t)) {
      uintptr_t v;

      memcpy(&v, (const void *)(donors[d] + off), sizeof(v));
      lib = group_by_bias(groups, group_count, v);
      if (lib) base = v;
    }

    uintptr_t phoff = 0;
    size_t phnum = 0;
    if (!lib || !elf_phdr_info(maps, base, &phoff, &phnum)) {
      LOGW("solist: donor %zu not identified", d);

      free_maps(maps);

      return false;
    }

    memset(cur_classes, 0, sizeof(cur_classes));
    classify_donor(maps, donors[d], window, base, lib->path, phoff, phnum, cur_classes);

    if (!offset_sets_equal(ref_classes, cur_classes, window, CL_CLASS_MASK)) {
      LOGW("solist: donor %zu slot sets disagree", d);

      free_maps(maps);

      return false;
    }
  }

  if (!class_nonempty(ref_classes, window, CL_BASE) ||
      !class_nonempty(ref_classes, window, CL_NAMEPTR) ||
      !class_nonempty(ref_classes, window, CL_PHDR) ||
      !class_nonempty(ref_classes, window, CL_PHNUM)) {
    LOGW("solist: incomplete slot identification");

    free_maps(maps);

    return false;
  }

  /* INFO: Own image ground truth. */
  uintptr_t my_phoff = 0;
  size_t my_phnum = 0;
  if (!elf_phdr_info(maps, lib_base, &my_phoff, &my_phnum)) {
    LOGW("solist: own elf header unreadable");

    free_maps(maps);

    return false;
  }
  if (!window_readable(maps, lib_base + my_phoff, my_phnum * sizeof(ElfW(Phdr)))) {
    LOGW("solist: own phdr table not mapped");

    free_maps(maps);

    return false;
  }

  /* INFO: Build the clone from donor 0. */
  void *clone_mem = mmap(NULL, SOLIST_SCAN_WINDOW, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (clone_mem == MAP_FAILED) {
    LOGW("solist: clone mmap failed");

    free_maps(maps);

    return false;
  }
  uintptr_t clone = (uintptr_t)clone_mem;

  memcpy(clone_mem, (const void *)donors[0], window);

  uintptr_t old_head = head;
  for (size_t off = 0; off < window; off++) {
    if (!(ref_classes[off] & CL_CLASS_MASK)) continue;

    uintptr_t v = 0;

    if (ref_classes[off] & CL_BASE) v = lib_base;
    if (ref_classes[off] & CL_NAMEPTR) v = (uintptr_t)kOverlayPath;
    if (ref_classes[off] & CL_PHDR) v = lib_base + my_phoff;
    if (ref_classes[off] & CL_PHNUM) v = (uintptr_t)my_phnum;

    memcpy((void *)(clone + off), &v, sizeof(v));
  }

  /* INFO: Head insertion: clone -> old head; head pointer -> clone. */
  memcpy((void *)(clone + next_off), &old_head, sizeof(old_head));

  /* INFO: Dry-run the walk exactly the way do_dl_iterate_phdr consumes it. */
  {
    char path_buf[256];
    uintptr_t v_base = 0, v_name = 0, v_phdr = 0;
    uintptr_t v_phnum = 0, v_next = 0;

    for (size_t off = 0; off < window; off += sizeof(uintptr_t)) {
      uintptr_t v;

      memcpy(&v, (const void *)(clone + off), sizeof(v));
      if (ref_classes[off] & CL_BASE) v_base = v;
      if (ref_classes[off] & CL_NAMEPTR) v_name = v;
      if (ref_classes[off] & CL_PHDR) v_phdr = v;
      if (ref_classes[off] & CL_PHNUM) v_phnum = v;
    }

    memcpy(&v_next, (const void *)(clone + next_off), sizeof(v_next));

    if (v_base != lib_base || v_phnum != (uintptr_t)my_phnum ||
        v_phdr != lib_base + my_phoff || v_next != old_head ||
        !read_cstring(maps, v_name, path_buf, sizeof(path_buf)) ||
        strcmp(path_buf, kOverlayPath) != 0) {
      LOGW("solist: clone validation failed");

      munmap(clone_mem, SOLIST_SCAN_WINDOW);
      free_maps(maps);

      return false;
    }

    if (!soinfo_plausible(maps, groups, group_count, old_head, window)) {
      LOGW("solist: post-install walk validation failed");

      munmap(clone_mem, SOLIST_SCAN_WINDOW);
      free_maps(maps);

      return false;
    }
  }

  /* INFO: Single release store; clone is fully initialized above. */
  __atomic_store_n((uintptr_t *)solist_addr, clone, __ATOMIC_RELEASE);

  LOGI("solist: registered %s at %p (phdr %p, phnum %zu)", kOverlayPath,
       (void *)lib_base, (void *)(lib_base + my_phoff), my_phnum);

  free_maps(maps);

  return true;
}

#else /* !ZKSU_SOLIST_REGISTER: registration compiled out (verdict outcome) */

bool solist_register_library(uintptr_t lib_base, size_t lib_size) {
  (void) lib_base;
  (void) lib_size;

  return false;
}

#endif /* ZKSU_SOLIST_REGISTER */
