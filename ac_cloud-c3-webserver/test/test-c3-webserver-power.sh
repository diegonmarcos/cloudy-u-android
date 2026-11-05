#!/bin/sh
# test-c3-webserver-power.sh — #1502 guard: the Server / Keep alive switches stay the only way anything starts this app's servers.
#
# THE FAILURE THIS EXISTS FOR. A keep-alive foreground service that restarts itself, starts at boot and answers every outside
# start request held fleet apps awake for hours (#1487). Server OFF must free every port; Keep alive (default OFF) must be the
# only reason a service, a sticky restart or a boot receiver exists. Checks, all from the declaration and the sources:
#   P1 build.json::power.matrix has the four states, service/boot/sticky only for Server ON + Keep alive ON, wake never,
#      listeners == Server, and the default is Keep alive OFF
#   P2 no alarm, job, work or wake-lock API anywhere in the app's Kotlin or Rust (no scheduled wake)
#   P3 the service, the boot receiver and MainActivity all go through PowerState.serviceAllowed; START_STICKY follows the gate
#   P4 the boot receiver is declared disabled in the manifest (enabled only by MainActivity.syncKeepAlive)
#   P5 server::start() refuses to bind while Server is OFF
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import json, os, re, sys
app = sys.argv[1]
rd = lambda p: open(os.path.join(app, p)).read()
bad = []
kt = "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/"
pw = json.loads(rd("build.json"))["power"]
m = pw["matrix"]
if sorted((r["server"], r["keep_alive"]) for r in m) != [(False, False), (False, True), (True, False), (True, True)]:
    bad.append("P1 the matrix does not hold exactly the four states")
for r in m:
    on = r["server"] and r["keep_alive"]
    if (r["service"], r["boot"], r["sticky"]) != (on, on, on) or r["wake"] or r["listeners"] != r["server"]:
        bad.append("P1 row %r breaks the rules" % r)
if pw["default"]["keep_alive"] is not False:
    bad.append("P1 Keep alive must default to OFF")
banned = re.compile(r"AlarmManager|setExactAndAllowWhileIdle|setRepeating|JobScheduler|JobService|WorkManager|PeriodicWorkRequest|WakeLock|newWakeLock")
for root in (os.path.join(app, "src-tauri"), os.path.join(app, "server/src")):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ("target", "build", ".gradle")]
        for f in files:
            if f.endswith((".kt", ".rs", ".xml")) and banned.search(open(os.path.join(d, f)).read()):
                bad.append("P2 %s uses a scheduling or wake API" % os.path.join(d, f))
svc, boot, act = rd(kt + "KeepAliveService.kt"), rd(kt + "BootReceiver.kt"), rd(kt + "MainActivity.kt")
for name, src in (("KeepAliveService", svc), ("BootReceiver", boot), ("MainActivity", act)):
    if "PowerState.serviceAllowed" not in src:
        bad.append("P3 %s does not go through PowerState.serviceAllowed" % name)
if svc.index("PowerState.serviceAllowed") > svc.index("START_STICKY"):
    bad.append("P3 START_STICKY is returned before the gate")
if "START_NOT_STICKY" not in svc:
    bad.append("P3 the refused path must be START_NOT_STICKY")
if not re.search(r'android:name="\.BootReceiver"\s+android:enabled="false"', rd("src-tauri/gen/android/app/src/main/AndroidManifest.xml")):
    bad.append("P4 the boot receiver is not declared android:enabled=\"false\"")
lib = rd("server/src/lib.rs")
if "power::plan(power::current().sw).listeners" not in lib.split("pub fn start(")[1].split("fn start_once")[0]:
    bad.append("P5 start() does not check the Server switch")
print("\n".join(bad) if bad else "test-c3-webserver-power: all green")
sys.exit(1 if bad else 0)
PY
