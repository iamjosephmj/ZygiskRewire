# Phase 3 / Task 4 — solist-consistency spike note

Date: 2026-10-29
Outcome: **IMPLEMENTED** — runtime-derived, cross-validated solist registration behind a
compile-time flag (`ZKSU_SOLIST_REGISTER`, default 1). No version-specific offsets are
compiled in; every structural fact is probed from memory at runtime and the registration
is skipped on any ambiguity.

Implementation: `loader/src/injector/solist_register.c` (call site: `loader/src/injector/entry.c`).

## 1. The anomaly being closed

Tasks 1–3 map the loader file-backed from `/system/lib{,64}/<LIB_NAME>` (overlaid by the
module framework). `/proc/<zygote>/maps` therefore shows a plausible system library, but
the image was mapped by our own remote loader, so the linker has no `soinfo` for it.
`dl_iterate_phdr` — and anything derived from it (debuggerd unwinding, ART
`DlIteratePhdr`, detector code) — does not report the mapping. A detector that
cross-checks "executable file-backed mapping in /system/lib*" against "solist entry with
the same realpath" finds an exec region with no solist entry.

## 2. Probe: how `dl_iterate_phdr` actually walks (read-only, grounded)

Verified against AOSP bionic sources for nougat (API 24/25), oreo-mr1, android11 and
master (the shipped linker of the API 36 target is in this lineage). The walk shape is
**identical across all of them**:

```c
int do_dl_iterate_phdr(int (*cb)(dl_phdr_info*, size_t, void*), void* data) {
  int rv = 0;
  for (soinfo* si = solist_get_head(); si != nullptr; si = si->next) {
    dl_phdr_info dl_info;
    dl_info.dlpi_addr  = si->link_map_head.l_addr;
    dl_info.dlpi_name  = si->link_map_head.l_name;   /* realpath, stored at load */
    dl_info.dlpi_phdr  = si->phdr;
    dl_info.dlpi_phnum = si->phnum;
    /* master/11 also: soinfo_tls* t = si->get_tls(); if (t) { ... } */
    rv = cb(&dl_info, sizeof(dl_phdr_info), data);
    if (rv != 0) break;
  }
  return rv;
}
```

Fields needed for a consistent entry (complete list actually consumed by the walk):

| slot                     | value we must provide                  |
|--------------------------|----------------------------------------|
| `soinfo* next`           | old head (we insert at list head)      |
| `link_map_head.l_addr`   | our load bias (== our mapping base)    |
| `link_map_head.l_name`   | the overlay path string, NUL-terminated|
| `phdr`                   | `base + e_phoff` of the mapped image   |
| `phnum`                  | `e_phnum` of the mapped image          |
| `tls_` (via `get_tls()`) | NULL (walk null-checks it)             |

`solist` is a plain `soinfo*` file-scope pointer (master `linker_main.cpp`:
`static soinfo* solist; static soinfo* sonext;`). Normal insertion is at the tail
(`solist_add_soinfo` uses `sonext`), which is why **head** insertion was chosen for the
clone: it is coherent on its own — a later `dlopen` appends after the real tail via
`sonext` and never touches the head pointer, so the clone can never be orphaned by a
subsequent insertion (a tail insert would have to patch `sonext`, an extra version-
sensitive global).

### Timing (constraint: pre-`__libc_init` safety)

`entry()` runs on the `__libc_init` GOT hook installed by the ptracer
(`loader/src/ptracer/ptracer.c`, `inject_on_main`): the linker has finished linking all
of zygote's `DT_NEEDED` libraries (the solist is fully populated — the walk itself proves
this at runtime by finding a ≥5-node chain), and `__libc_init` — hence any thread
creation — has not run. Zygote is single-threaded at registration time; the code also
logs `Threads:` from `/proc/self/status` at runtime as evidence. Even if a thread
existed, the clone is fully built before one aligned release-store
(`__atomic_store_n(..., __ATOMIC_RELEASE)`) swaps the head, so a concurrent walker sees
either the old or the new list, both consistent.

### soinfo layout across versions (why hardcoded offsets are unacceptable)

The layout differs by generation (nougat: version-gated `FLAG_NEW_SOINFO` tail with
`gnu_bucket_`/`soname_`/`realpath_` as `std::string`; oreo+: namespaces, `handle_`;
R+: TLS `tls_`/`tlsdesc_args_`, gap; T+: memtag, `vma_names_` on arm64; plus 32-bit
`old_name_[128]` compat fields). Field offsets are private ABI with no runtime
descriptor: the linker exports only function addresses (`__dl_dlopen`, ...) and the
solist head variable, never struct offsets, and ships without DWARF. Compiled-in
per-version offset tables are exactly the fragile assumption the plan's abort criterion
names — they were rejected.

## 3. Implemented design: clone-and-patch with runtime derivation

All offsets are derived from memory at runtime, cross-validated, and installed only
after a dry-run passes. Any ambiguity → skip (log + continue; identical to pre-Task-4
behaviour).

1. **Own maps** via `parse_maps_safe("self")` (project hygiene: no maps atime update).
2. **Locate the linker**: mapped image whose basename is `linker64`/`linker`
   (API 29+: `/apex/com.android.runtime/bin/...`, older: `/system/bin/...`);
   bias = lowest mapping start.
3. **Resolve the head**: `__dl_g_solist` (fallback `__dl_solist`) from the mapped
   linker's `PT_DYNAMIC` → `DT_SYMTAB`/`DT_STRTAB`/`DT_HASH`/`DT_GNU_HASH` — no section
   headers needed (device linker binaries may not carry them). Missing symbol → skip.
4. **Identify `next`**: the unique slot `X` such that following `*(node+X)` from the
   head yields a ≥5-node, acyclic, NULL-terminated chain of soinfo-shaped records
   ("shaped" = one slot holds the exact load bias of a mapped file-backed library and
   one slot points within 0x400 bytes of that bias at a readable program-header table).
   Ambiguity (two passing slots) → skip.
5. **Donors**: three chain nodes whose library lives under a real `/.../lib.../` path
   and whose ELF has **no `PT_TLS`** — the clone therefore has `tls_ == NULL` and
   `get_tls()` reports no TLS module (no TLS id collision for the walk on modern
   bionic).
6. **Slot identification by content, per donor**: `CL_BASE` (value == donor bias —
   covers `base`, `load_bias`, `link_map_head.l_addr` which legitimately all equal the
   load bias for system libs), `CL_NAMEPTR` (slot points at a NUL-terminated string
   equal to the donor realpath — covers `l_name` and any `std::string` heap/SSO data
   pointer without needing the libc++ string encoding), `CL_PHDR` (pointer at donor
   `bias + e_phoff`), `CL_PHNUM` (value == donor `e_phnum` adjacent ≤16 bytes to a
   `CL_PHDR` slot — phdr/phnum are declared adjacently in every observed generation).
   The three donors' slot sets must be **identical** (same struct layout) and every
   class non-empty, else skip.
7. **Clone**: anonymous `mmap` (RW), sized `SOLIST_SCAN_WINDOW` (1536/768 bytes,
   LP64/32) copied from donor 0. The copied tail keeps pointing at real linker memory
   (donor strtab/symtab/namespace pointers), so even unpatched slots are safe reads.
   The clone is never freed — lifetime equals the loader's.
8. **Patch**: every identified slot → our values (base, `kOverlayPath`, phdr, phnum);
   `next` slot → old head.
9. **Dry-run**: re-read the clone exactly the way `do_dl_iterate_phdr` would
   (l_addr/l_name/phdr/phnum via the identified slots, name string == overlay path)
   and re-validate the old chain head. Failure → unmap + skip.
10. **Install**: single aligned release-store of the clone address into the solist
    head pointer. The clone stays at the list head for the life of the process and is
    inherited by every forked app — which is the point: every app's `dl_iterate_phdr`
    now reports `/system/lib{,64}/<LIB_NAME>` matching maps.

## 4. Assumption ledger

| assumption | status |
|---|---|
| `dl_iterate_phdr` walks `solist` via `si->next`, reading `link_map_head.l_addr/l_name`, `phdr`, `phnum` | VERIFIED nougat..master (AOSP sources); verified structurally at runtime by the chain identification |
| linker solist initialized before `entry()` | VERIFIED (GOT hook fires on the `__libc_init` call, after linking) |
| zygote single-threaded at registration | VERIFIED (pre-`__libc_init`, no threads yet; logged at runtime; release-store makes it safe regardless) |
| soinfo offsets derivable without version tables | ACHIEVED via content identification + 3-donor cross-validation (this was the plan's open question) |
| `__dl_g_solist` exported in every target linker | NOT verifiable here; API 25 min / API 36 target — if absent or renamed (`__dl_solist` fallback covers N-era naming) the code skips registration gracefully |
| tail insertion coherence | avoided entirely by head insertion (no `sonext` dependency) |

## 5. Residual risks (documented, accepted)

- **Ordering**: the clone appears first in the walk (head). Real load order is not
  reproduced; no mainstream consumer assumes order. Considered and rejected: tail
  insertion (requires patching a real soinfo's `next` plus the version-sensitive
  `sonext` global).
- **dlopen interaction**: the clone exists only in `g_solist`, not in any linker
  namespace list, so name/inode dedup for `dlopen` walks namespace lists and never
  selects the clone. Worst theoretical path (a lookup that did walk `g_solist` by
  inode) would land on donor-copied inode fields — read-only confusion, not memory
  unsafety.
- **Confident-but-wrong identification**: mitigated by 3-donor set equality and the
  dry-run; not provable zero without device testing. Unstable behaviour at Task 6
  device test → revert by building with `-DZKSU_SOLIST_REGISTER=0` (flag default 1 is
  in-source; the Makefile can override).

## 6. Task 6 verification hooks

- logcat at zygote start: `solist:` LOGD lines (next offset, Threads: value) and
  `solist: registered /system/lib64/<LIB_NAME> at ...` LOGI.
- On-device probe (root shell, any app process):
  a small `dlopen`-less `dl_iterate_phdr` loop (or `debuggerd -b <pid>` backtrace map)
  must list `/system/lib{,64}/<LIB_NAME>` with `dlpi_addr` == the mapping base from
  `/proc/<pid>/maps`.
- Instability (zygote crash loop) → rebuild with `ZKSU_SOLIST_REGISTER=0`.
