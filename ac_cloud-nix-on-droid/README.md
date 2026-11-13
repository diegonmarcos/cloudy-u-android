# Cloud Nix-on-Droid

Our build of [Nix-on-Droid](https://github.com/nix-community/nix-on-droid-app), vendored into the fleet.

## Why it is here

The watchdog app needed to run a command in a terminal that belongs to a
different app, and Android gives you almost nothing for that: `RUN_COMMAND`
fires and forgets, and the interesting output is on the far side of a sandbox.
The workaround was ssh to loopback, which meant depending on someone else's
sshd being installed, running, on the expected port and holding our key — four
things that can each be false on a phone, and were.

Owning the terminal replaces all four with one: a bound service, guarded by a
signature-level permission, that only APKs signed with the constellation key
can call. This is the DECLARATIVE env — the machine is described in Nix and rebuilt from it; commands are run in the Termux fork beside it.

## Agent CLIs

Both coding agents are baked into the store: `claude` (nixpkgs `claude-code`) and
`agy`, the Antigravity CLI (Google's successor to the Gemini CLI). `agy` is built by
`app/src/main/cpp/rootfs-extras.nix` from the same pinned glibc release tarball the
Termux rootfs installs (`ac_cloud-termux/rootfs/rootfs.json` `tarballs.agy`, hashes in
`build.json` `extras.pins.agy`), patched to nix's loader. Authentication is a runtime
login (`agy`), never baked.

## What we changed

Divergence from upstream belongs in this section as it lands, so the cost of the fork
stays visible.

### Running at targetSdk 29+ (#1708)

Upstream targets SDK 28 because from 29 Android forbids `execve()` of files in the app's data
directory. Here that is three files, and this fork targets 35 (silent Cloud Store updates on
Android 17) by starting each of them another way:

- `bin/login`, a `/system/bin/sh` script, is started as `/system/bin/sh $PREFIX/bin/login`
  (`termux-shared/.../shell/SystemLinkerExec.java`, called from
  `TermuxShellUtils.setupShellCommandArguments`);
- `bin/proot-static` becomes a link to `libcloudproot.so` in the native library directory, and
  `PROOT_LOADER` names `libcloudprootloader.so` there (`termux-shared/.../termux/shell/NativeProot.java`,
  applied whenever a command's environment is built). The bootstrap's own proot is kept beside it as
  `proot-static.data`.

Everything inside the guest is mapped by proot's loader, not exec'd, and is unaffected. All of it
applies only when the process may not exec its data (Android 10+ and not the `untrusted_app_25/27`
domain). `test/test-system-linker-exec.sh` pins the wiring;
`1_cicd/src/scripts/emulator-terminal-target-sdk.sh` runs the terminal on API 34 and 35 emulators.
Known limit: in the failsafe session a script in the data directory is run as `sh <script>`.

## applicationId

Left at upstream's `com.termux.nix`, deliberately. Termux compiles the prefix
`/data/data/com.termux/files/usr` into every binary it ships, and Nix-on-Droid
does the same with `com.termux.nix` for its Nix store, so renaming the package
orphans the whole bootstrap and every package in it.

We own the source and sign it with our own key; we do not own the namespace.
The consequence is accepted rather than worked around: this build REPLACES an
installed upstream Nix-on-Droid rather than sitting beside it — same id, different
signature — so the upstream one has to be uninstalled first.

## Licence and provenance

Nix-on-Droid, the Nix-on-Droid developers, GPL-3.0. Vendored at `e87b609`. The upstream licence is kept
verbatim in `LICENSE.md`; see `ATTRIBUTION.md`. This is a derivative work and
stays GPL-3.0.
