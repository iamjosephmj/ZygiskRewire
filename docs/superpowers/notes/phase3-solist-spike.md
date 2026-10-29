# Phase 3 / Task 4 — solist-consistency spike VERDICT

Date: 2026-10-29 (fix round 1; supersedes the earlier implementation report of this note)
Outcome: **VERDICT — not feasible under the task constraints; solist consistency defers
to Phase 4+.**

The spike artifact (`loader/src/injector/solist_register.c`, flag `ZKSU_SOLIST_REGISTER`)
is kept for the record but **compiles to a no-op stub by default** (flag default 0). The
reason is a hard blocker on the anchor, documented below; the rest of the spike's safety
machinery was reviewed as sound and is preserved for a future attempt.

## 1. The blocker (Critical, review round 1)

The registration must anchor on the linker's solist head variable. In every bionic
generation (nougat through main) that variable is a **C++ file-scope `static`**:

```cpp
// linker_main.cpp (master); same storage class in N/P-era linker.cpp
static soinfo* solist;
static soinfo* sonext;
```

C++ file-scope statics have internal linkage: they are **LOCAL symbols, categorically
never present in `.dynsym`**. Empirically confirmed by the reviewer on an extracted API
28 linker binary: no `__dl_g_solist` / `__dl_solist` in the dynamic symbol table.

Consequences for the spike artifact:

- The dynsym-only resolution path (`linker_dynsym_lookup` probing `__dl_g_solist`, then
  `__dl_solist`) always returns 0 on every Android version. The `__dl_solist` fallback
  matches nothing on any version (the name never existed in that form).
- Every downstream step (chain walk, donor identification, clone, install) was therefore
  unreachable dead code as shipped.

The one name that does exist on-disk is the mangled static `__dl__ZL6solist` (N/P-era
naming), and only in the **`.symtab`** — which device linker binaries may strip, and
whose mangled spelling drifts per version. Relying on mangled static names is exactly
the version-specific anchoring the task forbids.

## 2. What a real implementation would require (and why it was not done here)

Any of the following would unblock activation; all violate the task's constraints or
trade a new risk for the one closed:

1. **File-symtab parsing of the linker's statics** (`__dl__ZL6solist` and friends).
   Requires per-version mangled-name tables (they are compiler- and version-specific)
   and an unstripped on-device linker — a compiled-in version table by another name.
2. **Heuristic anchor discovery without symbols** (e.g. locating the head pointer by
   scanning the linker's writable data for a pointer that starts a valid soinfo chain).
   Plausible in principle; carries a real probability of selecting an unrelated pointer,
   and a wrong selection means writing a forged pointer into unknown linker state.
   Would need its own dedicated spike with device validation across the full API range.
3. **Load-flow redesign** (Phase 4+ candidate): drop the manual remote mapping entirely
   and have the tracer remote-call the linker's real `__dl_dlopen` on the overlay path,
   so the soinfo/solist entry is genuine by construction. This preserves all Phase 3
   invariants (file-backed from the overlay, real relocation, real RELRO — the linker
   does all of it) and eliminates the manual loader, but is a redesign of the injection
   path, out of this task's scope. Open questions there: `do_dlopen` signature/caller-
   namespace resolution across versions and its behaviour pre-`__libc_init`.

## 3. Verified facts that remain valid (input to any future attempt)

Grounded against AOSP bionic nougat, oreo-mr1, android11 and master:

- `do_dl_iterate_phdr` walks `for (si = solist_get_head(); si; si = si->next)` and reads
  exactly: `dlpi_addr = si->link_map_head.l_addr`, `dlpi_name = si->link_map_head.l_name`
  (realpath, stored at load), `dlpi_phdr = si->phdr`, `dlpi_phnum = si->phnum`; modern
  versions additionally read `si->get_tls()`, null-checked. This field ledger is complete
  and stable across API 25..36 lineage.
- `solist` is a plain `soinfo*` head; normal insertion is tail-append via `sonext`.
  **Head** insertion is self-coherent (later dlopens append after the real tail through
  `sonext` and never touch the head pointer), which avoids any `sonext` dependency.
- Injection timing: `entry()` fires on the `__libc_init` GOT hook — after the linker has
  finished linking zygote's `DT_NEEDED` set (solist fully populated), before
  `__libc_init` and before any thread exists. Single-threaded mutation is safe; a single
  aligned release-store swap of the head is safe regardless.
- soinfo layout varies by generation (version-gated tails, `std::string` fields, TLS,
  memtag, 32-bit `old_name_[128]` compat) and has no runtime descriptor — compiled-in
  per-version offset tables remain unacceptable.

## 4. Spike artifact status and content

`loader/src/injector/solist_register.c` implements, behind `ZKSU_SOLIST_REGISTER`
(default **0** → no-op stub):

- own-maps parsing (`parse_maps_safe`), linker image location, in-memory `PT_DYNAMIC`
  dynsym resolution of the (never-matching) head symbol candidates;
- `next`-slot identification via unique long acyclic soinfo-shaped chain walk;
- three-donor content-based slot identification (`== base`, pointer-to-realpath,
  phdr-table pointer, phnum adjacent to phdr) with exact cross-donor set equality;
- clone-and-patch into anonymous memory (loader lifetime), dry-run of the
  `dl_iterate_phdr` consumption path, then a single `__atomic_store_n` head swap.

The review judged the safety machinery sound; it is retained so a future anchor fix (§2)
can enable it with `-DZKSU_SOLIST_REGISTER=1` after closing §5.

## 5. Conditions any future activation must close

- **S3 (dry-run completeness):** the pre-install dry-run must re-walk the *entire old
  chain* through the identified `next_off` (it currently re-validates only the old head
  node), so the install is gated on the full post-install list being walkable.
- **S4 (TLS null assertion):** the code currently infers `tls_ == NULL` from the donor
  having no `PT_TLS` segment. Activation must add an explicit assertion that the cloned
  `tls_` slot is NULL (or otherwise that `get_tls()` yields no module for the clone), so
  the modern walk never reports a duplicate TLS module id for the fake entry.
- **S5 (arm32 exidx hazard):** on 32-bit ARM the clone copies the donor's
  `ARM_exidx`/`ARM_exidx_count` pointers, which reference the *donor's* text. An
  unwinder pairing the fake entry's `dlpi_addr` with the donor's exidx tables reads
  wrong unwind data. Activation must either null/patch these slots (offsets are already
  content-identifiable) or document why no consumer pairs them.
- Plus the §2 anchor: a solist head resolution that does not depend on version-specific
  mangled static names.

## 6. Verification hooks kept

- Force-enabled builds log `solist:` LOGD lines and a `solist: registered ...` LOGI at
  zygote start.
- On-device probe (Task 6): `dl_iterate_phdr` from any app process must list
  `/system/lib{,64}/<LIB_NAME>` with `dlpi_addr` equal to the mapping base — this is the
  check that will stay negative until Phase 4+ lands an approach from §2.
- Default builds: the registration path is compiled out; `strings` on the release loader
  contains no `solist:` diagnostics.
