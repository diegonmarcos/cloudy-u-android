# Shell broker (#1510)

ONE owner of the privileged (uid 2000) shell: the SuperApp. Every other app is a thin client.

## Parts
- `libs:shell-broker` (SuperApp only): `BrokerCore` (allowlist, concurrency cap, timeout, audit; pure, unit-tested), `ShellBroker` (over the embedded ADB / Shizuku ladder of `libs:shizuku-adb-debug-tools`), `ShellBrokerProvider` (`<SuperApp>.shellbroker`), `ShellBrokerService` (Messenger, bound only), `ShellArmActivity` ("Arm in SuperApp"). All signature-guarded (CONSTELLATION_DATA). No foreground service, no `START_STICKY`, no boot receiver; worker threads die 30 s after the last call (`IdlePolicy`); one arm attempt per call at most, gated by `RetryBackoff` (30 s floor) and the SuperApp's own auto-arm switch.
- `libs:shell-client` (every other app): `ShellClient.exec / stream / status / arm`, `ShellResult` = `Ok{exitCode, stdout, stderr, source}` | `NeedsArm` | `Denied` | `Failed`. No pairing, no reconnect loop, no service, no receiver, no project dependency.
- Mesh door: Cloud-Webserver `GET /shell/status`, `POST /shell/exec`, `POST /shell/stream` (bearer, mesh only, closed with the Server switch). The broker sees the Webserver as the caller `mesh`.

## Policy
`libs/shell-broker/src/main/assets/shell-allowlist.json`: per caller package, full-match regexes over the trimmed command; default deny; shell metacharacters refused unless the rule declares them; the SuperApp's own entry also has `exact` lines (X-Ray probes) and `segments` (Background policy chains). The caller is the package behind the binder uid. Every call is audited (caller, redacted command, exit, duration, source; never output) to a ring and `files/shell-broker-audit.jsonl`.

## Guards
`aa_cloud-superapp/test/test-shell-broker-guards.sh` (no unlisted app on the shizuku lib or naming an ADB connection class, shell-client apps have an allowlist entry, no sticky / boot / foreground in the broker, signature-guarded components, default deny), `aa_cloud-superapp/data/shell-migration.json` (the ratchet of apps still to move), `SelfAllowlistTest` (every X-Ray / Background policy command is allowed), `libs/shell-broker` and `libs/shell-client` unit tests.

## Stdin door (#1512)
`ShellClient.execStdin(cmd, file)` streams a bounded input to a command's stdin: `pm install -S <size>` (one APK) and `pm install-write -S <size> <session> <name> -` (the splits of a session; `pm install-create / install-commit / install-abandon` are plain execs). The input crosses as a `ParcelFileDescriptor` extra of the provider call `exec_stdin` (never the mesh door, never the Messenger), with its size in `stdinSize`.
- Allowlist: a per-caller `stdin` rule list (`re` over the command, `maxBytes`), default deny. Only Cloud Store, Cloud Account and Cloud Drive (the installers) have one; the mesh, the terminals and every read-only caller stream nothing. Hard cap `ShellWire.STDIN_MAX_BYTES` (512 MiB), a rule may only lower it; the `-S <n>` of the command must equal the bytes received.
- Bounded: the client refuses an empty or oversized file before the call; the broker reads at most `size + 1` bytes into a cache spool (deleted in `finally`, stale files swept), refuses a longer or shorter input, runs ONE stdin transfer at a time (plus the usual concurrency cap), merges stderr itself (` 2>&1`, the one redirect any command may end with), has its own timeout ceiling (`STDIN_MAX_TIMEOUT_MS`, 10 min, default 3 min) and audits `cmd <stdin N B>` with the outcome, never the content.
- libs:updater has three source sets: `shell/` (SuperApp, the real ladder), `broker/` (apps on libs:shell-client: Fleet.shellInstall and the self-update stream the APK over this door), `noshell/` (no channel).

## Terminals (#1512)
`rish` inside the proot cannot call a binder, so it speaks one loopback HTTP request to a relay in the terminal app's own process (`RishRelay`, started by `ShellRish.export` from `TermuxApplication.onCreate`): 127.0.0.1, an ephemeral port, a random per-process bearer token written 0600 to `rish.env`, `GET /exec?cmd=...`, one request per connection, a blocking accept on a daemon thread (no loop, no service). The relay forwards through `ShellClient` under the terminal's own allowlist entry (`cld.termux`, `cld.termux.nix`), so it grants nothing the terminal does not already have; the answer is stdout+stderr with the exit code in `X-Exit-Code`. The Shizuku binder surface is not implemented: the terminal tools (`pm`, `am`, `settings`, `dumpsys`, ...) keep working through `rish <command>`, an interactive `rish` (no argv) is gone.

## State
Moved (all): SuperApp X-Ray and Background policy / Super Saving, Cloud Drive, Cloud Store, Cloud Account (libs:appstore, libs:account, libs:updater on libs:shell-client), Cloud Terminal and Cloud Nix terminal (rish relay), the Webserver door, the c3 peers-control patch (cloudy-u-containers, not pushed). `data/shell-migration.json` `pending` is empty and the guard is strict: no app except the SuperApp links libs:shizuku-adb-debug-tools or names an ADB connection class (B1, B2, B7), and only the installers hold stdin rules (B8).
