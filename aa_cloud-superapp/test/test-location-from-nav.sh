#!/usr/bin/env bash
# #1690 the SuperApp runs NO location tracker and asks Cloud Nav (the fleet's one tracker owner) for location.
#   N1 no tracker in the SuperApp APK: neither the app nor any lib it links (location_use_scan.libs_closure) is libs:maps;
#      no manifest of the app or those libs declares LocationTrackerService or a foregroundServiceType="location" service;
#      no SuperApp source names LocationTrackerService.
#   N2 IPC contract: NavLocation asks tracker/location over FleetIpc; Cloud Nav serves it (NavLocationWire) and every
#      field NavLocation reads is one NavLocationWire writes.
#   N3 consumers fed from IPC: Weather (resolveFromNav + NavLocation.ask), the strip (StripWatch subscribes), the popup GPS
#      section (subscribes while visible), GpsProbe.lastKnown reads NavLocation; none of them reads LocationManager fixes.
#   N4 Cloud-Nav-missing path: every reason has its line, openOrInstall exists, Weather NAV_UNAVAILABLE -> OPEN_NAV,
#      the popup shows the reason + "Open Cloud Nav"; NavLocation makes no location-class call (no silent fallback).
#   N5 permissions: FINE / COARSE stay because remaining features still need them (the scanner still finds wifi-info,
#      ble-scan and gnss call sites); ACCESS_BACKGROUND_LOCATION stays under the #1597 hold-every-permission policy.
#   N6 field parity: the popup still prints Fix quality / fix line / satellites / receiver; the strip still draws dots.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
python3 - "$HERE" "$ROOT" <<'PY'
import os, re, sys
here, root = sys.argv[1], sys.argv[2]
sys.path.insert(0, here)
import location_use_scan as scan
fails = []
def ok(c, g, b):
    print(("  PASS: " + g) if c else ("  FAIL: " + b))
    if not c: fails.append(b)
def read(rel):
    try: return open(os.path.join(root, rel), errors="ignore").read()
    except OSError: return ""
def code(rel):
    s = re.sub(r"/\*.*?\*/", "", read(rel), flags=re.S)
    return "\n".join(l.split("//")[0] for l in s.splitlines())
def xml(rel): return re.sub(r"<!--.*?-->", "", read(rel), flags=re.S)
SA = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/"
NAV = "ac_cloud-nav/app/src/main/java/com/diegonmarcos/cloudnav/tracker/"

print("== N1: no tracker in the SuperApp APK ==")
libs = scan.libs_closure(root)
ok(len(libs) >= 10, f"{len(libs)} linked libs resolved", "the lib closure is empty: is the tree checked out?")
ok("maps" not in libs, "libs:maps is not linked (directly or through a lib)", "the SuperApp links libs:maps: the tracker would be compiled in")
mans = ["aa_cloud-superapp/app/src/main/AndroidManifest.xml"] + [f"ab_cloud-libs-shared/libs/{l}/src/main/AndroidManifest.xml" for l in libs]
decl = [m for m in mans if re.search(r"<service[^>]*LocationTrackerService", xml(m), re.S)]
fgs = [m for m in mans if re.search(r'foregroundServiceType="[^"]*location', xml(m))]
ok(not decl, "no manifest in the APK declares LocationTrackerService", f"tracker declared in {decl}")
ok(not fgs, "no location foreground service in the APK's manifests", f"location foreground service in {fgs}")
src = []
for d, _, fs in os.walk(os.path.join(root, "aa_cloud-superapp/app/src/main")):
    for f in fs:
        if f.endswith(".kt") and "LocationTrackerService" in code(os.path.relpath(os.path.join(d, f), root)): src.append(f)
ok(not src, "no SuperApp source names LocationTrackerService", f"tracker referenced in {src}")

print("== N2: IPC contract ==")
nl = code(SA + "location/NavLocation.kt"); wire = code(NAV + "NavLocationWire.kt"); prov = code(NAV + "TrackerDebugApiProvider.kt")
ok('OP = "tracker/location"' in nl and "FleetIpc.call(app, PKG, OP" in nl and 'PKG = "com.diegonmarcos.cloudnav"' in nl,
   "NavLocation asks com.diegonmarcos.cloudnav for tracker/location over FleetIpc", "NavLocation does not ask Cloud Nav's tracker/location")
ok('"location" -> location(app)' in prov, "Cloud Nav routes tracker/location", "Cloud Nav does not serve tracker/location")
reads = set(re.findall(r'j\.(?:get|opt)\w*\("(\w+)"', nl)); writes = set(re.findall(r'\.put\("(\w+)"', wire))
ok(reads and reads <= writes, f"every field NavLocation reads is written by NavLocationWire ({sorted(reads)})", f"fields read but never written: {sorted(reads - writes)}")

print("== N3: consumers fed from IPC ==")
ws = code(SA + "notificationcenter/WeatherBadgeService.kt"); wl = code(SA + "notificationcenter/WeatherLocation.kt")
gp = code(SA + "network/GpsProbe.kt"); ps = code(SA + "network/NetworkPopupSections.kt")
ok("WeatherLocation.resolveFromNav" in ws and "NavLocation.ask" in ws and "AndroidProbe" not in ws, "Weather reads Cloud Nav's fix", "Weather does not read Cloud Nav")
ok(not re.search(r"LocationManager|getLastKnownLocation|getCurrentLocation", wl), "WeatherLocation makes no location-class call", "WeatherLocation still reads location")
ok("getLastKnownLocation" not in gp and "NavLocation.lastFix()" in gp, "GpsProbe.lastKnown is Cloud Nav's fix", "GpsProbe still reads getLastKnownLocation")
ok("NavLocation.subscribe(app, NAV_PERIOD_MS)" in gp and "nav?.close()" in gp, "the strip subscribes to Cloud Nav and closes on stop", "the strip is not fed by Cloud Nav")
ok("NavLocation.subscribe(app)" in ps and "onDispose { runCatching { sub?.close() } }" in ps and "if (visible) runCatching" in ps,
   "the popup GPS section subscribes only while visible", "the popup GPS section is not fed by Cloud Nav while visible")

print("== N4: Cloud Nav missing ==")
ok(all(f"Why.{w} ->" in nl for w in ["NOT_INSTALLED", "NOT_RUNNING", "DENIED", "OUTDATED", "NO_FIX"]) and "fun openOrInstall" in nl,
   "every reason has its line and Cloud Nav can be opened / installed", "a reason line or openOrInstall is missing")
ok(not re.search(r"LocationManager|getLastKnownLocation|getCurrentLocation|requestLocationUpdates|Fused", nl), "NavLocation never tracks itself (no fallback)", "NavLocation makes a location-class call")
wf = code(SA + "notificationcenter/WeatherFace.kt")
ok("Reason.NAV_UNAVAILABLE ->" in wf and "Action.OPEN_NAV ->" in wf, "Weather names Cloud Nav and offers Open Cloud Nav", "Weather has no Cloud-Nav-missing face")
ok('"Open Cloud Nav ›"' in ps and "NavLocation.Answer.Unavailable" in ps, "the popup prints the reason and Open Cloud Nav", "the popup hides why Cloud Nav gave nothing")

print("== N5: permissions still needed ==")
hits = scan.scan(root); apis = {a for (_, a) in hits}
mf = xml("aa_cloud-superapp/app/src/main/AndroidManifest.xml")
need = {"wifi-info", "ble-scan", "gnss"} & apis
ok(need == {"wifi-info", "ble-scan", "gnss"}, f"remaining location-permission features found: {sorted(need)}", f"expected wifi-info, ble-scan and gnss call sites, found {sorted(apis)}")
ok("ACCESS_FINE_LOCATION" in mf and "ACCESS_COARSE_LOCATION" in mf, "FINE / COARSE kept (Wi-Fi SSID, BLE scan, GNSS status still need them)", "a still-needed location permission was dropped")
ok("ACCESS_BACKGROUND_LOCATION" in mf and "#1597" in read("aa_cloud-superapp/app/src/main/AndroidManifest.xml"), "BACKGROUND_LOCATION kept under the #1597 hold-every-permission policy", "background location dropped against #1597")
ok("fix" not in apis or all(not p.startswith("aa_cloud-superapp/") for (p, a) in hits if a == "fix"), "no SuperApp file reads a location fix itself", "a SuperApp file still reads a fix: " + str([p for (p, a) in hits if a == "fix"]))

print("== N6: field parity ==")
for k in ['"Fix quality ', "GpsFix.fixLine(f)", "GpsFix.satelliteLines(", "GpsProbe.capabilityLines(", "GpsProbe.providersLine(app)", '"Location: "']:
    ok(k in ps, f"popup still prints {k.strip(chr(34))}", f"popup lost {k}")
ok("fun dots(" in gp and "cached ?: last" in gp, "the strip still draws its GPS dots", "the strip lost its GPS dots")
print(f"\n== {'FAIL' if fails else 'PASS'}: test-location-from-nav ({len(fails)} failing) ==")
sys.exit(1 if fails else 0)
PY
