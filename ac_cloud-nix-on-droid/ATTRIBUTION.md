# Attribution

Cloud Nix-on-Droid is a derivative work of [Nix-on-Droid](https://github.com/nix-community/nix-on-droid-app) by the Nix-on-Droid developers, licensed GPL-3.0.

Vendored at `e87b609`. Upstream's licence is kept verbatim in `LICENSE.md`.

Changes made by this fork are listed in README.md under "What we changed".

## Bundled binaries

- **proot** ([termux/proot](https://github.com/termux/proot), GPL-2.0), fetched at build time, hash-pinned
  (`ab_cloud-terminal-store/proot.json`), never committed. It ships in the bootstrap as `bin/proot-static`
  and, as the same bytes, in the APK as `lib/<abi>/libcloudproot.so` with its loader
  `libcloudprootloader.so` (the byte range proot itself embeds, written out by
  `ab_cloud-terminal-store/carve-proot-loader.py`, pinned in `proot-loader.json`). The second copy exists
  because an app that targets SDK 29+ may only exec files from its native library directory (#1708).

