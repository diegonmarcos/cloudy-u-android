#!/usr/bin/env bash
# #1502 Cloud Store may keep alive or wake ONLY with auto-update ON and on an unmetered network (Wi-Fi); otherwise zero background
# wakes (owner rule). Static, no build, no device. The pure rule is libs:core BackgroundPolicyTest.
#  S1 every periodic / delayed WorkRequest of the Store passes (Updater, ConstellationWorker) takes its network type from
#     BackgroundGate (UNMETERED in Store) and is cancelled when auto-update is off
#  S2 no receiver, no sticky service, no boot hook of the Store's own; HostShell is gated on BackgroundGate.mayRun
#  S3 workers re-check the gate when they run (an older build's persisted job ends there)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I - "$ROOT" <<'PY'
import os, re, sys
R = sys.argv[1]
fails = 0
def code(p):
    s = open(os.path.join(R, p), encoding="utf-8").read()
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'(?m)(?<![:"])//[^\n]*', '', s)
    return re.sub(r'<!--.*?-->', '', s, flags=re.S)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
L = "ab_cloud-libs-shared/libs/"
up = code(L + "updater/src/main/java/com/diegonmarcos/superapp/updater/Updater.kt")
cw = code(L + "appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationWorker.kt")
bg = code(L + "updater/src/main/java/com/diegonmarcos/superapp/updater/BackgroundGate.kt")
ok("NetworkType.UNMETERED" in bg and "AUTO_UPDATE_UNMETERED" in bg, "S1 BackgroundGate.network is UNMETERED for the Store", "S1 BackgroundGate lost the UNMETERED constraint")
ok("setRequiredNetworkType(BackgroundGate.network(context))" in up and "setRequiredNetworkType(BackgroundGate.network(context))" in cw, "S1 the self-update and the fleet check take their network type from BackgroundGate", "S1 a periodic request has its own network type")
ok("NetworkType.CONNECTED" not in up.split("fun start")[1].split("fun cancel(")[0] and "NetworkType.CONNECTED" not in cw.split("private fun constraints")[1].split("fun cancelAll")[0], "S1 no hard-coded CONNECTED in the periodic and delayed constraints", "S1 a periodic constraint is CONNECTED again")
ok(cw.count("constraints(context)") >= 3 and "setInitialDelay(30, TimeUnit.SECONDS)" in cw, "S1 periodic, kick and the 30 s one-shot all use the gated constraints", "S1 a ConstellationWorker request lost the gated constraints")
ok("AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)" in up.replace("!BuildConfig.", "").replace("!AuConfig.", "") or ("!AutoUpdatePrefs.enabled(context)" in up and "cancel(context)" in up), "S1 auto-update off cancels the self-update", "S1 auto-update off no longer cancels the self-update")
ok("!AutoUpdatePrefs.enabled(context)" in cw and cw.count("cancelUniqueWork(WORK_NAME)") >= 2, "S1 auto-update off cancels the fleet check and its one-shot", "S1 auto-update off no longer cancels the fleet check")
mf = code("ac_cloud-store/app/src/main/AndroidManifest.xml")
ok("<receiver" not in mf and "BOOT_COMPLETED" not in mf and "MY_PACKAGE_REPLACED" not in mf, "S2 no receiver in the Store manifest", "S2 the Store declares a receiver")
ok(not os.path.exists(os.path.join(R, "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/UpdateChecksBootReceiver.kt")), "S2 UpdateChecksBootReceiver is gone", "S2 UpdateChecksBootReceiver is back")
bad = []
for root, _, files in os.walk(os.path.join(R, "ac_cloud-store/app/src/main")):
    for f in files:
        if f.endswith((".kt", ".java")):
            s = code(os.path.relpath(os.path.join(root, f), R))
            if "START_STICKY" in s.replace("START_NOT_STICKY", "") or "AlarmManager" in s: bad.append(f)
ok(not bad, "S2 no sticky service or alarm in the Store sources", "S2 " + ", ".join(bad))
app = code("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt")
ok("HostShell.backgroundAllowed = { com.diegonmarcos.superapp.updater.BackgroundGate.mayRun(it) }" in app and app.index("backgroundAllowed") < app.index("CloudStoreShell.install"), "S2 the Store gates HostShell on BackgroundGate.mayRun before arming it", "S2 the Store's HostShell is not gated")
uw = code(L + "updater/src/main/java/com/diegonmarcos/superapp/updater/UpdateWorker.kt")
ok("!force && !BackgroundGate.mayRun(applicationContext)" in uw and "BackgroundGate.mayRun(applicationContext)" in cw, "S3 both workers re-check the gate when they run", "S3 a worker runs without the gate")
bp = code(L + "core/src/main/java/com/diegonmarcos/superapp/core/BackgroundPolicy.kt")
ok("autoUpdateOn && unmetered" in bp, "S3 BackgroundPolicy: Store needs auto-update AND unmetered", "S3 BackgroundPolicy Store rule changed")
sys.exit(1 if fails else 0)
PY
