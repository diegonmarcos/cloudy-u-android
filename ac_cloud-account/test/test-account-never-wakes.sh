#!/usr/bin/env bash
# #1502 Cloud Account must NEVER keep itself alive or wake itself in the background (owner rule). Static, no build, no device.
#  W1 manifest: no BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver, no RECEIVE_BOOT_COMPLETED, no AutofillService or
#     CredentialProviderService (a system binding the app cannot refuse), no sync adapter / account authenticator
#  W2 source: no PeriodicWorkRequest, no AlarmManager, no JobScheduler anywhere under the app; AccountForeground is the only place
#     the Autocomplete sync and the auto-backup run, and it schedules nothing
#  W3 services: every service Account can start is NOT sticky, and #1512 it arms no shell channel of its own (no HostShell);
#     libs:updater's Updater / ConstellationWorker cancel instead of scheduling for it (BackgroundGate.never)
#  W4 callers: no fleet code wakes Account on a schedule: FleetPeers.wake refuses it, the SuperApp strip ticker never wakes a
#     no-wake peer, WdKeeper never queries one, the Webserver mesh answers 403 for it, and no WorkManager worker of any app
#     queries Account's providers
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I - "$ROOT" <<'PY'
import os, re, sys
R = sys.argv[1]
fails = 0
def rd(p):
    return open(os.path.join(R, p), encoding="utf-8").read()
def code(p):
    s = rd(p)
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'(?m)(?<![:"])//[^\n]*', '', s)
    return re.sub(r'<!--.*?-->', '', s, flags=re.S)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

A = "ac_cloud-account/app/src/main"
mf = code(A + "/AndroidManifest.xml")
ok("BOOT_COMPLETED" not in mf and "MY_PACKAGE_REPLACED" not in mf and "<receiver" not in mf, "W1 no boot / package-replaced receiver", "W1 Account declares a receiver that can start it")
ok("RECEIVE_BOOT_COMPLETED" not in mf, "W1 no RECEIVE_BOOT_COMPLETED", "W1 RECEIVE_BOOT_COMPLETED is held")
bad = [t for t in ("AutofillService", "CredentialProviderService", "android.accounts.AccountAuthenticator", "android.content.SyncAdapter", "BIND_AUTOFILL", "BIND_CREDENTIAL_PROVIDER") if t in mf]
ok(not bad, "W1 no AutofillService / CredentialProvider / authenticator / sync adapter (no system binding)", "W1 system-bound component: " + ", ".join(bad))

bad = []
for root, _, files in os.walk(os.path.join(R, A, "java")):
    for f in files:
        if f.endswith((".kt", ".java")):
            p = os.path.relpath(os.path.join(root, f), R)
            s = code(p)
            for t in ("PeriodicWorkRequest", "enqueueUniquePeriodicWork", "AlarmManager", "JobScheduler", "JobInfo", "setExactAndAllowWhileIdle", "START_STICKY"):
                if t in s: bad.append(f"{f}:{t}")
ok(not bad, "W2 no periodic work, alarm, job or sticky service in the app sources", "W2 " + ", ".join(bad))
af = code(A + "/java/com/diegonmarcos/cloudaccount/AccountForeground.kt")
ok("WorkRequest" not in af.replace("cancelUniqueWork", "") and "enqueue" not in af, "W2 AccountForeground schedules nothing", "W2 AccountForeground schedules work")
mn = code(A + "/java/com/diegonmarcos/cloudaccount/MainActivity.kt")
ok("AccountForeground.onForeground(this)" in mn and not os.path.exists(os.path.join(R, A, "java/com/diegonmarcos/cloudaccount/AutoBackupWorker.kt")) and not os.path.exists(os.path.join(R, A, "java/com/diegonmarcos/cloudaccount/MeAutocompleteWorker.kt")),
   "W2 autocomplete sync and auto-backup run from the foreground only; the two workers are gone", "W2 a worker is back or MainActivity no longer syncs")

app = code(A + "/java/com/diegonmarcos/cloudaccount/App.kt")
ok("HostShell" not in app and "adbdebug" not in app and "AccountForeground.retireLegacyJobs(this)" in app, "W3 #1512 App arms no shell channel of its own (no keep-alive service, no keeper) and retires the legacy jobs", "W3 App names HostShell again / does not retire the legacy jobs")
LIB = "ab_cloud-libs-shared/libs/"
svc = code(LIB + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/HostShellService.kt")
wd = code(LIB + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/WdKeeperService.kt")
ok("START_NOT_STICKY" in svc and "START_STICKY" not in svc.replace("START_NOT_STICKY", "") and "START_NOT_STICKY" in wd and "START_STICKY" not in wd.replace("START_NOT_STICKY", ""), "W3 HostShellService and WdKeeperService are not sticky", "W3 a keep-alive service is sticky")
hs = code(LIB + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/HostShell.kt")
wk = code(LIB + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/WdKeeper.kt")
ok("mayBackground(app)" in hs and "if (!mayBackground(ctx)) return" in hs and "HostShell.mayBackground(ctx)" in wk, "W3 HostShell.schedule, the service and WdKeeper all ask mayBackground", "W3 a HostShell / WdKeeper path ignores mayBackground")
up = code(LIB + "updater/src/main/java/com/diegonmarcos/superapp/updater/Updater.kt")
cw = code(LIB + "appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationWorker.kt")
ok("BackgroundGate.never(context)) { cancelAll(context); return }" in up and "BackgroundGate.never(context)) { cancelAll(context); return }" in cw, "W3 Updater.start and ConstellationWorker.start cancel for Account", "W3 Account could still schedule the self-update or the fleet check")
bp = code(LIB + "core/src/main/java/com/diegonmarcos/superapp/core/BackgroundPolicy.kt")
ok("ACCOUNT_PKG -> Mode.NEVER" in bp and 'ACCOUNT_PKG = "com.diegonmarcos.cloudaccount"' in bp, "W3 BackgroundPolicy: Account is NEVER", "W3 BackgroundPolicy no longer says Account never")

fp = code(LIB + "devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetPeers.kt")
m = re.search(r'NO_WAKE = setOf\(([^)]*)\)', fp)
ok(m and "com.diegonmarcos.cloudaccount" in m.group(1) and "com.diegonmarcos.cloudstore" in m.group(1) and "if (pkg in NO_WAKE)" in fp, "W4 FleetPeers.wake (fleet/wake op, Apps mesh) refuses Account and Store", "W4 FleetPeers.wake can start Account")
ok("BackgroundPolicy.mayWakePeer(it.applicationId)" in wk, "W4 WdKeeper never queries a no-wake peer", "W4 WdKeeper claims() can start a peer")
pr = code(LIB + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbPeerReader.kt")
ok("!wake && !BackgroundPolicy.mayWakePeer(peer.applicationId)" in pr, "W4 AdbPeerReader.remote skips a no-wake peer unless a person asked", "W4 AdbPeerReader can wake a peer unasked")
ok("AdbPeerReader.read(context, wake = false)" in code("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt"), "W4 the SuperApp strip's 15 s ticker reads with wake = false", "W4 the strip ticker can wake Store")
bj = rd("ac_cloud-c3-webserver/build.json")
ok('"no_wake": ["com.diegonmarcos.cloudaccount", "com.diegonmarcos.cloudstore"]' in bj and "this app is never woken from the mesh" in rd("ac_cloud-c3-webserver/server/src/meshlogs.rs"), "W4 Webserver mesh /logs answers 403 for Account and Store", "W4 Webserver mesh can call Account's log provider")
# no WorkManager Worker anywhere may query Account's or Store's providers (a scheduled poll starts them)
bad = []
for base in ("aa_cloud-superapp/app/src/main", LIB):
    for root, _, files in os.walk(os.path.join(R, base)):
        if "/build/" in root or "/test" in root: continue
        for f in files:
            if not f.endswith(".kt"): continue
            p = os.path.relpath(os.path.join(root, f), R)
            s = open(os.path.join(R, p), encoding="utf-8").read()
            if re.search(r':\s*(Coroutine)?Worker\(', s) and re.search(r'content://(\$\{?[A-Za-z_.]*\}?)?com\.diegonmarcos\.(cloudaccount|cloudstore)|cloudaccount\.accountdata|AUTHORITY_SUFFIX|\.fleet/token', s):
                bad.append(p)
ok(not bad, "W4 no Worker polls Account's or Store's provider", "W4 a Worker queries a no-wake provider: " + ", ".join(bad))
sys.exit(1 if fails else 0)
PY
