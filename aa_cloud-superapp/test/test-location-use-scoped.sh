#!/usr/bin/env bash
# Tester (#1666): the SuperApp is not an active location user. Every location-class call is user-initiated and screen-scoped.
#
# WHY THIS EXISTS. After #1654 the quick panel still said "Location — being used by Cloud SuperApp". Android counts as location
# use every API behind the location app-ops, not only fixes: WifiManager.getConnectionInfo (the status strip read it on every
# RSSI_CHANGED broadcast, and the energy sampler from its worker), getLastKnownLocation (the strip's GPS dots), the telephony
# service-state listener / getter (the strip's mobile watch), Wi-Fi and BLE scans the analysers left running behind them, and a
# tracker foreground service a 15-min watchdog re-spawned. Closed here, statically (no device, no build):
#   G1 every location-class call site in the SuperApp + every lib it links is in location-use-allowlist.txt with an owner whose
#      trigger is screen / button / weather / legacy-sdk; a new call site, or one more call in a listed file, fails
#   G2 the guard bites: a planted call in the status strip, in a new file, and the same call inside a comment (ignored)
#   G3 nothing in the background: no location-class call in the status strip, the home boards, the energy sampler or any
#      *Service / *Worker / *Receiver / *Badge* / *Widget* file; StripWatch never calls lastKnown; the mobile watch registers
#      its service-state listener only with INCLUDE_LOCATION_DATA_NONE (API 33+)
#   G4 leaving each screen releases it: Wi-Fi page (WifiLifecycle: leave + ON_STOP detach), Bluetooth page (BtLifecycle:
#      leave + ON_STOP stop scan / inquiry), popup GPS section (stop on leave, unregister GNSS)
#   G5 the map tracker (libs:maps, Cloud Nav) starts only from the user's Start: ACTION_USER_START required, START_NOT_STICKY,
#      the watchdog never starts it and is never scheduled
#   G6 weather keeps the #1654 4 h rule
#   G7 the ledger + X-Ray App "Location use" (app ledger + system app-ops through the broker) exist
#   Rule 16: the analysers keep their scans / samplers (allowlisted, not removed)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
python3 - "$HERE" "$ROOT" <<'PY'
import os, re, sys
here, root = sys.argv[1], sys.argv[2]
sys.path.insert(0, here)
import location_use_scan as scan
P = F = 0
def ok(c, good, bad):
    global P, F
    if c: P += 1; print("  PASS: " + good)
    else: F += 1; print("  FAIL: " + bad)
def read(rel):
    try: return open(os.path.join(root, rel), encoding="utf-8").read()
    except OSError: return ""
def code(rel): return "\n".join(scan.code_lines(read(rel)))

TRIGGERS = {"screen", "button", "weather", "legacy-sdk"}
allow = {}
bad_rows = []
for ln in open(os.path.join(here, "location-use-allowlist.txt"), encoding="utf-8"):
    ln = ln.strip()
    if not ln or ln.startswith("#"): continue
    parts = [x.strip() for x in ln.split("|")]
    if len(parts) != 5 or not parts[2].isdigit() or parts[3] not in TRIGGERS or len(parts[4]) < 20: bad_rows.append(ln); continue
    allow[(parts[0], parts[1])] = (int(parts[2]), parts[3], parts[4])
hits = scan.scan(root)

print("== G1: every location-class call site has a user-initiated, screen-scoped owner ==")
ok(not bad_rows, f"{len(allow)} allowlist rows well formed (trigger in {sorted(TRIGGERS)}, owner named)", f"malformed rows: {bad_rows}")
ok(len(hits) >= 10, f"the scanner sees the call sites ({len(hits)} file/api pairs over {len(scan.libs_closure(root))} linked libs)", "the scanner found almost nothing: is the tree checked out?")
unowned = sorted(f"{p} [{a}] x{n}" for (p, a), n in hits.items() if (p, a) not in allow)
grown = sorted(f"{p} [{a}] {n} > {allow[(p, a)][0]}" for (p, a), n in hits.items() if (p, a) in allow and n > allow[(p, a)][0])
stale = sorted(f"{p} [{a}]" for (p, a), v in allow.items() if hits.get((p, a), 0) < v[0])
ok(not unowned, "no unowned location-class call", "UNOWNED location-class call (add a screen-scoped owner or remove it): " + "; ".join(unowned))
ok(not grown, "no listed file grew a call", "more calls than the owner covers: " + "; ".join(grown))
ok(not stale, "no stale allowlist row (each count is exact)", "allowlist row covers calls that are gone (lower the count): " + "; ".join(stale))
legacy_bad = [p for (p, a), v in allow.items() if v[1] == "legacy-sdk" and "Build.VERSION.SDK_INT" not in read(p)]
ok(not legacy_bad, "every legacy-sdk owner sits behind a Build.VERSION.SDK_INT branch", f"legacy-sdk without an SDK branch: {legacy_bad}")
noted = [p for (p, a), v in allow.items() if v[1] in ("screen", "weather") and "LocationUse" not in read(p)]
ok(not noted, "every screen / weather call site notes the app's ledger (LocationUse)", f"not noted in LocationUse: {noted}")

print("== G2: the guard bites ==")
STRIP = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt"
planted = read(STRIP) + "\nprivate fun leak(w: android.net.wifi.WifiManager) = w.connectionInfo\n"
ok((STRIP, "wifi-info") in scan.scan(root, {STRIP: planted}), "a connectionInfo planted in the strip is caught", "a planted strip call is NOT caught")
NEW = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/Planted.kt"
ok((NEW, "fix") in scan.scan(root, {NEW: "fun f(m: android.location.LocationManager) = m.getLastKnownLocation(\"network\")\n"}),
   "a getLastKnownLocation in a new file is caught", "a new file's call is NOT caught")
ok((NEW, "fix") not in scan.scan(root, {NEW: "// m.getLastKnownLocation(\"network\")\n/* m.requestLocationUpdates() */\nval s = \"/sys/*\"\n"}),
   "the same API inside a comment / string is ignored", "a comment is counted as a call")
ok((NEW, "service-state") not in scan.scan(root, {NEW: "val s = tm.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE)\n"}),
   "a location-free ServiceState read (INCLUDE_LOCATION_DATA_NONE) is not location use", "INCLUDE_LOCATION_DATA_NONE counted as location use")

print("== G3: nothing location-class runs in the background ==")
SA = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/"
bg_files = [STRIP, SA + "boards/ResourcesProvider.kt", "ab_cloud-libs-shared/libs/battery/src/main/java/com/diegonmarcos/superapp/battery/EnergyWatchdog.kt"]
bg_hits = [f"{p} [{a}]" for (p, a) in hits if p in bg_files and allow.get((p, a), (0, ""))[1] != "legacy-sdk"]
ok(not bg_hits, "status strip, home boards and the energy sampler make no location-class call", f"background call: {bg_hits}")
svc = [f"{p} [{a}]" for (p, a) in hits if re.search(r"(Service|Worker|Receiver|Badge\w*|Widget\w*|Watchdog)\.kt$", p) and allow.get((p, a), (0, ""))[1] != "legacy-sdk"]
ok(not svc, "no location-class call in any service / worker / receiver / badge / widget file", f"service-side call: {svc}")
strip = code(STRIP)
ok("connectionInfo" not in strip and "NetworkSignal.wifiRssi" in strip, "the strip's Wi-Fi dots read NetworkSignal (capabilities), not getConnectionInfo", "the strip still reads getConnectionInfo")
gp = code(SA + "network/GpsProbe.kt")
sw = gp[gp.find("class StripWatch"):gp.find("class LiveSession")]
ok(sw and "lastKnown(" not in sw and "getLastKnownLocation" not in sw, "StripWatch never calls lastKnown / getLastKnownLocation (reads GpsProbe.cached)", "StripWatch still reads the last known location")
mp = code(SA + "network/MobileProbe.kt")
ok(re.search(r"SDK_INT >= 33\) ServiceCb31", mp) and "registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE" in mp,
   "the mobile watch's service-state listener is API 33+ only, registered with INCLUDE_LOCATION_DATA_NONE", "the strip's telephony watch can still note location")
ok("getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE)" in mp and re.search(r"screen -> \{ LocationUse\.note", mp),
   "ServiceState is read location-free (33+), and below that only for a screen", "the ServiceState getter is not scoped")
reads = [l for l in strip.splitlines() if "MobileProbe.read(" in l and "screen = true" in l]
ok(not reads, "the strip's MobileProbe.read never claims a screen", f"the strip reads as a screen: {reads}")

print("== G4: leaving each screen releases its scan / listener ==")
WL = "ab_cloud-libs-shared/libs/wifi/src/main/java/com/diegonmarcos/superapp/wifi/"
wu = code(WL + "WifiUi.kt"); wl = code(WL + "WifiLifecycle.kt")
ok("WifiLifecycle.Attach(controller)" in wu and "controller.attach(); onDispose" not in wu, "the Wi-Fi page attaches through WifiLifecycle", "the Wi-Fi page attaches without the lifecycle scope")
ok("ON_STOP -> scope.stopped()" in wl and "scope.left()" in wl and "onDetach()" in wl, "Wi-Fi: ON_STOP and leaving the page detach (scan loop, results listener, link sampler)", "Wi-Fi scope does not detach on stop / leave")
wc = code(WL + "WifiController.kt")
det = wc[wc.find("fun detach()"):wc.find("fun refreshEnv()")]
ok("loop?.cancel()" in det and "unlisten?.invoke()" in det and "src.stopLink()" in det, "WifiController.detach cancels the scan loop, the listener and the link callback", "detach leaves something running")
BL = "ab_cloud-libs-shared/libs/bluetooth/src/main/java/com/diegonmarcos/superapp/bluetooth/"
bu = code(BL + "BluetoothUi.kt"); bl = code(BL + "BtLifecycle.kt")
ok("BtLifecycle.Scope(controller)" in bu, "the Bluetooth page is scoped by BtLifecycle", "the Bluetooth page has no lifecycle scope")
ok("ON_STOP) release(o)" in bl and "onDispose { owner.lifecycle.removeObserver(obs); release(o) }" in bl and "c.stopScan(); c.stopDiscovery()" in bl,
   "Bluetooth: ON_STOP and leaving the page stop the LE scan and the classic inquiry", "Bluetooth scans outlive the page")
ps = code(SA + "network/NetworkPopupSections.kt")
ok("if (visible) session?.start() else session?.stop()" in ps and "onDispose { session?.stop() }" in ps and "if (visible) GpsProbe.lastKnown(app) else GpsProbe.cached" in ps,
   "popup GPS: the GNSS session and the last-known read run only while visible, stopped on leave", "popup GPS section is not scoped")
live = gp[gp.find("class LiveSession"):]
ok("unregisterGnssStatusCallback" in live[live.find("fun stop()"):] and "MAX_MS" in live, "LiveSession.stop unregisters the GNSS callback; capped at MAX_MS", "GNSS callback not released")

print("== G5: the map tracker starts only from the user's Start ==")
MP = "ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/"
ts = code(MP + "LocationTrackerService.kt"); wd = code(MP + "TrackerWatchdog.kt")
ok(ts != "" and "START_STICKY" not in ts.replace("START_NOT_STICKY", ""), "LocationTrackerService is START_NOT_STICKY (Android never revives it)", "the tracker is still sticky")
ok("if (!isUserStart(intent?.action))" in ts and ".setAction(ACTION_USER_START)" in ts, "a start without ACTION_USER_START is refused (tracking off)", "the tracker accepts any start")
st = ts[ts.find("fun startTracking"):ts.find("fun stopTracking")]
ok("TrackerWatchdog.schedule" not in st and "TrackerWatchdog.cancel(ctx)" in st, "startTracking never schedules the watchdog", "startTracking still schedules the keep-alive")
ok(wd != "" and "startTracking" not in wd and "startForegroundService" not in wd and "cancel(ctx)" in wd, "TrackerWatchdog never starts the tracker and cancels itself", "the watchdog can still start the tracker")
mc = read(MP + "MapsConfigFragment.kt")
starts = [f for f in os.listdir(os.path.join(root, MP)) if f.endswith(".kt") and "startTracking(" in code(MP + f) and f != "LocationTrackerService.kt"]
ok(starts == ["MapsConfigFragment.kt"] and 'MapsButton("Start"' in mc, "the only startTracking caller is the Maps page's Start button", f"other tracker starters: {starts}")

print("== G6: weather keeps the 4 h rule (#1654) ==")
wlc = code(SA + "notificationcenter/WeatherLocation.kt")
ok("REFRESH_MS = 4 * 60 * 60_000L" in wlc and "FRESH_MS = REFRESH_MS" in wlc and os.path.exists(os.path.join(here, "test-coarse-location-4h.sh")),
   "REFRESH_MS = FRESH_MS = 4 h, test-coarse-location-4h.sh still guards it", "the 4 h weather rule moved")

print("== G7: ledger + X-Ray App 'Location use' ==")
lu = code("ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/LocationUse.kt")
ok("fun note(" in lu and "fun all(" in lu and "WRITE_EVERY_MS" in lu, "libs:perms LocationUse: note / all, bounded writes", "the ledger is missing")
dc = read(SA + "devcontrol/DevControlFragment.kt")
i_app = dc.find('macro("📱  APP & BUILD")'); i_loc = dc.find('infoSection("Location use")'); i_next = dc.find('macro(', i_app + 10)
ok(i_app >= 0 and i_app < i_loc < i_next and "LocationUseView.APPOPS_CMD" in dc, "X-Ray App ▸ APP & BUILD ▸ Location use (ledger rows + system app-ops via the broker)", "no Location use section in X-Ray App")
xp = read(SA + "xray/XrayProbes.kt"); lv = read(SA + "xray/LocationUseView.kt")
ok('Probe("locops", G_SECURITY' in xp and "appops get $PKG $it" in lv, "the export carries the system location app-ops (locops) and the ledger (locledger)", "the export lacks the location view")

print(f"== {P} passed, {F} failed ==")
sys.exit(1 if F else 0)
PY
