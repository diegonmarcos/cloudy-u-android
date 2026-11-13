# Cloud Terminal

Our build of [Termux](https://github.com/termux/termux-app), vendored into the fleet.

## Why it is here

The watchdog app needed to run a command in a terminal that belongs to a
different app, and Android gives you almost nothing for that: `RUN_COMMAND`
fires and forgets, and the interesting output is on the far side of a sandbox.
The workaround was ssh to loopback, which meant depending on someone else's
sshd being installed, running, on the expected port and holding our key — four
things that can each be false on a phone, and were.

Owning the terminal replaces all four with one: a bound service, guarded by a
signature-level permission, that only APKs signed with the constellation key
can call. This is the one that RUNS commands.

## Agent CLIs

The glibc rootfs entered through proot carries both coding agents: `claude`
(`@anthropic-ai/claude-code`, npm global) and `agy` (Antigravity CLI, pinned release
tarball), both declared in `rootfs/rootfs.json`. Bionic itself cannot load them; proot
supplies the glibc root. `agy` login is a runtime step, never baked.

## What we changed

Divergence from upstream belongs in this section as it lands, so the cost of the fork
stays visible.

### Running at targetSdk 29+ (#1708)

Upstream targets SDK 28 because from 29 Android forbids `execve()` of files in the app's data
directory. This fork targets 35 (silent Cloud Store updates on Android 17) the way upstream's own
`termux-exec` 2.x provides for:

- the first exec of every session or command goes through the system linker
  (`termux-shared/.../shell/SystemLinkerExec.java`, called from `TermuxShellUtils.setupProcessArgs`):
  `/system/bin/linker64 $PREFIX/bin/sh $PREFIX/bin/login`;
- the session carries `LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so` from its first process
  on, so the bootstrap's own library rewrites every later exec the same way;
- proot and its loader run from the native library directory (`libcloudproot.so`,
  `libcloudprootloader.so`, linked beside `enter.sh` by `CloudRootfs.linkNative`), since a static
  binary cannot be loaded by the linker.

All of it applies only when the process may not exec its data (Android 10+ and not the
`untrusted_app_25/27` domain); elsewhere nothing changes. `test/test-system-linker-exec.sh` runs the
class and pins the wiring; `1_cicd/src/scripts/emulator-terminal-target-sdk.sh` runs the terminal on
API 34 and 35 emulators. Known limits, upstream's: a statically linked ELF in the data directory
cannot run, and a program sees the linker as `/proc/self/exe`.

## applicationId

Left at upstream's `com.termux`, deliberately. Termux compiles the prefix
`/data/data/com.termux/files/usr` into every binary it ships, and Nix-on-Droid
does the same with `com.termux.nix` for its Nix store, so renaming the package
orphans the whole bootstrap and every package in it.

We own the source and sign it with our own key; we do not own the namespace.
The consequence is accepted rather than worked around: this build REPLACES an
installed upstream Termux rather than sitting beside it — same id, different
signature — so the upstream one has to be uninstalled first.

## Licence and provenance

Termux, the Termux developers, GPL-3.0. Vendored at `v0.118.3`. The upstream licence is kept
verbatim in `LICENSE.md`; see `ATTRIBUTION.md`. This is a derivative work and
stays GPL-3.0.
