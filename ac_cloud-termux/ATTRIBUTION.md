# Attribution

Cloud Terminal is a derivative work of [Termux](https://github.com/termux/termux-app) by the Termux developers, licensed GPL-3.0.

Vendored at `v0.118.3`. Upstream's licence is kept verbatim in `LICENSE.md`.

Changes made by this fork are listed in README.md under "What we changed".

## Bundled binaries

- **proot** ([termux/proot](https://github.com/termux/proot), GPL-2.0), fetched at build time, hash-pinned
  (`ab_cloud-terminal-store/proot.json`), never committed. It ships twice in the APK, as the same bytes: the
  `proot` asset staged beside `enter.sh`, and `lib/<abi>/libcloudproot.so` with its loader
  `libcloudprootloader.so` (the byte range proot itself embeds, written out by
  `ab_cloud-terminal-store/carve-proot-loader.py`, pinned in `proot-loader.json`). The second copy exists
  because an app that targets SDK 29+ may only exec files from its native library directory (#1708).
- **Termux bootstrap** ([termux/termux-packages](https://github.com/termux/termux-packages), each package
  under its own licence, listed under `$PREFIX/share/doc/*/copyright` on the device), downloaded at build
  time against the sha256 pins in `app/build.gradle`. Its `termux-exec` package (MIT / Apache-2.0) is what
  starts every program through the system linker at targetSdk 29+; this app adds no code to it.

