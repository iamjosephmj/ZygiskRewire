# ZygiskRewire

A stealth re-engineering of Zygisk: every build carries a fresh random
identity, the runtime lives on tmpfs, the loader and all third-party module
libraries present as system libraries, and the entire payload ships inside the
manager app's APK.

> **Credits** — ZygiskRewire is a fork of
> [PerformanC/ReZygisk](https://github.com/PerformanC/ReZygisk) (GPL-3.0;
> upstream, its authors, and its license are credited and preserved). Upstream
> channels: [PerformanC Discord](https://discord.gg/uPveNfTuCJ),
> [ReZygisk Telegram](https://t.me/rezygisk),
> [@performancorg](https://t.me/performancorg) on Telegram.

## Architecture

- **Per-build random identity** — module id, binary names, library names,
  socket names, SELinux type, and log tags are regenerated on every
  `make release`; two builds share nothing.
- **Boot-from-APK payload** — the manager app (`tech.thessemaj.zygiskrewire`)
  carries the daemon, ptracer, and loader in its assets; `post-fs-data.sh`
  locates the installed APK, extracts the payload into a tmpfs runtime dir
  (`/dev/<id>/`), verifies every file's sha256 plus the module id against the
  build manifest, and refuses silently on any mismatch.
- **Tmpfs runtime, scripts-only module dir** — no binaries persist under
  `/data/adb`; the module directory carries scripts, `module.prop`,
  `sepolicy.rule`, and the webroot only. Upgrades prune stale modules before
  they can race for the init ptrace.
- **System-library mimic** — the loader library is mapped file-backed from
  `/system/lib64/<random>.so` via a directory-scoped overlayfs; no text
  relocations, full RELRO, exec text byte-identical to the overlay file.
- **Third-party modules: the same mimic** — module libraries are staged into
  the overlay under neutral names and mapped from `/system/lib64/<neutral>.so`;
  module-dir paths never appear in app memory maps.
- **Denylist** — denylisted apps have the overlay detached from their
  namespaces: no loader, no module libraries, no mount entries.
- **Manager UI** — the carrier app provides root-backed status and
  start/stop control over the tmpfs runtime (dark terminal HUD); the KSU
  WebUI remains for module listing.

See [docs/METHODOLOGY.md](docs/METHODOLOGY.md) for the full stealth
methodology, invariants, and the recorded residual detector surface.

## Build

```sh
make identity          # mint this build's random identity
JAVA_HOME=<jbr> make release   # module zip + paired carrier APKs
./gradlew assembleDebug        # (alternative) carrier app only
```

Install: flash `build/out/SysConfig-*.zip` via your manager, install
`app/build/outputs/apk/debug/app-debug.apk`, reboot.

## License

GPL-3.0 — inherited from upstream ReZygisk; see [LICENSE](./LICENSE).
