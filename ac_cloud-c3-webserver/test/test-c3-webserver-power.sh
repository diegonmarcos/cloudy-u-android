#!/bin/sh
# test-c3-webserver-power.sh — #1502 guard: the Server / Keep alive switches stay the only way anything starts this app's servers.
#
# THE FAILURE THIS EXISTS FOR. A keep-alive foreground service that restarts itself, starts at boot and answers every outside
# start request held fleet apps awake for hours (#1487). Server OFF must free every port; Keep alive (default OFF) must be the
# only reason a service, a sticky restart or a boot receiver exists. Checks, all from the declaration and the sources:
#   P1 build.json::power.matrix has the four states, service/boot/sticky only for Server ON + Keep alive ON, wake never,
#      listeners == Server, admin (the :8002 shell + /api/power) true in EVERY row, and the default is Keep alive OFF
#   P2 no alarm, job, work or wake-lock API anywhere in the app's Kotlin or Rust (no scheduled wake)
#   P3 the service, the boot receiver and MainActivity all go through PowerState.serviceAllowed; START_STICKY follows the gate
#   P4 the boot receiver is declared disabled in the manifest (enabled only by MainActivity.syncKeepAlive)
#   P5 server::start() binds no CONTENT listener while Server is OFF, but always binds the admin surface
#   P6 Server OFF never unbinds /__app__: power::apply uses stop_content (not stop_all), the drive 503s non-shell paths, the UI has a confirm
#      and no longer navigates away to off_url
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
    if (r["service"], r["boot"], r["sticky"]) != (on, on, on) or r["wake"] or r["listeners"] != r["server"] or r.get("admin") is not True:
        bad.append("P1 row %r breaks the rules" % r)
if pw["default"]["keep_alive"] is not False:
    bad.append("P1 Keep alive must default to OFF")
banned = re.compile(r"AlarmManager|setExactAndAllowWhileIdle|setRepeating|JobScheduler|JobService|WorkManager|PeriodicWorkRequest|WakeLock|newWakeLock")
for root in (os.path.join(app, "src-tauri"), os.path.join(app, "core"), os.path.join(app, "server/src")):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ("target", "build", ".gradle")]
        for f in files:
            if f.endswith((".kt", ".rs", ".xml")) and banned.search(open(os.path.join(d, f)).read()):
                bad.append("P2 %s uses a scheduling or wake API" % os.path.join(d, f))
svc, boot, act = rd(kt + "KeepAliveService.kt"), rd(kt + "BootReceiver.kt"), rd(kt + "MainActivity.kt")
for name, src in (("KeepAliveService", svc), ("BootReceiver", boot), ("MainActivity", act)):
    if "PowerState.serviceAllowed" not in src:
        bad.append("P3 %s does not go through PowerState.serviceAllowed" % name)
# #1724 the start flow moved to KeepAliveFlow (pure, unit-tested): the service's gate is its Host.serviceAllowed, and the flow
# checks it before it binds the servers and returns STICKY; every refused path is NOT_STICKY.
flow = rd(kt + "KeepAliveFlow.kt")
if "KeepAliveFlow.onStartCommand" not in svc:
    bad.append("P3 KeepAliveService does not run KeepAliveFlow.onStartCommand")
if flow.index("host.serviceAllowed()") > flow.index("return STICKY"):
    bad.append("P3 STICKY is returned before the gate")
if "NOT_STICKY" not in flow or flow.count("return NOT_STICKY") < 3:
    bad.append("P3 the refused paths must be NOT_STICKY")
if not re.search(r'android:name="\.BootReceiver"\s+android:enabled="false"', rd("src-tauri/gen/android/app/src/main/AndroidManifest.xml")):
    bad.append("P4 the boot receiver is not declared android:enabled=\"false\"")
lib = rd("server/src/lib.rs")
start_src = lib.split("pub fn start(")[1].split("static CFG")[0]
if "power::plan(power::current().sw).listeners" not in start_src:
    bad.append("P5 start() does not check the Server switch")
if "start_admin(" not in start_src or start_src.index("start_admin(") > start_src.index("power::plan(power::current().sw).listeners"):
    bad.append("P5 start() must bind the admin surface BEFORE the Server gate")
pwr = rd("server/src/power.rs")
apply_src = pwr.split("pub fn apply(")[1].split("pub fn status_json")[0]
if "stop_content()" not in apply_src or "stop_all()" in apply_src:
    bad.append("P6 power::apply must stop content only (stop_content), never stop_all")
if "power::plan(power::current().sw).listeners" not in lib.split("pub fn drive_handler(")[1].split("pub struct Config")[0]:
    bad.append("P6 the drive handler does not 503 non-shell paths while Server is OFF")
sh = rd("server/ui/shell.html")
if "confirm(" not in sh or "location.replace(st.off_url)" in sh:
    bad.append("P6 the Server switch needs a confirm and must not navigate away to off_url")
print("\n".join(bad) if bad else "test-c3-webserver-power: all green")
sys.exit(1 if bad else 0)
PY
