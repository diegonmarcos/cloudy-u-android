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

## State
Moved: SuperApp X-Ray and Background policy / Super Saving (through `ShellBroker.execText`), Cloud Drive (client), the Webserver door, the c3 peers-control patch (cloudy-u-containers, not pushed).
Pending (see shell-migration.json): Cloud Store and Cloud Account (installs need the broker's stdin door, `pm install` with the APK on stdin), Cloud Terminal and Cloud Nix terminal (the `rish` bridge inside proot speaks the Shizuku binder protocol to the SuperApp's provider, not this broker).
