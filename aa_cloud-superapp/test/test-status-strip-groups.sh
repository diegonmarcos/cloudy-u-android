#!/usr/bin/env bash
# Tester (#1684): the home strip's network block in four groups, the WebS / SSHd round-trip indicators and TypC.
#
#   G1  the declared left order is 5G WiFi BT HS GPS | WG KDE | WebS ADB SSHd | TypC and equals NetworkSections.ORDER
#   G2  NetworkSections.GROUPS flattens to ORDER; the strip draws a separator where the group changes; the dropdown a
#       group divider (dropdown order = strip order: both from ORDER / the declaration)
#   G3  parity (rule 16): every pre-#1684 element id is still declared, Data renamed TypC (same id `data`), its pet kept
#   G4  WebS / SSHd are real round trips: HEAD on a declared route, TCP + SSH banner; no port literal in the code; the
#       ports come from other apps' build.json through app/build.gradle (into STATUS_BAR_B64), and resolve when they are present
#   G5  no polling: the door checks run from start / resume / network change only (no timer, no postDelayed, no loop)
#   G6  TypC from the USB_STATE and battery broadcasts (no polling): UsbDataModel.cable, re-derived in refreshBattery
#
# Static only (python3). A mutation proves G1 and G5 can go red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
check() {
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, mut = sys.argv[1], sys.argv[2]
fails = []
def ok(c, msg):
    print(("ok   " if c else "FAIL ") + msg)
    if not c: fails.append(msg)
def read(p): return open(os.path.join(app, p), encoding="utf-8").read()
def code(t): return re.sub(r'/\*.*?\*/|//[^\n]*', '', t, flags=re.S)
SRC = "app/src/main/java/com/diegonmarcos/superapp/"
bj = json.loads(read("build.json"))
sb = bj["home_status_bar"]
els = sb["elements"]
if mut == "order":
    for e in els:
        if e["id"] == "webs": e["order"] = 99
left = [e["id"] for e in sorted([e for e in els if e["position"] == "left" and e["kind"] == "network"], key=lambda e: e["order"])]
want = ["cellular", "wifi", "bluetooth", "hotspot", "gps", "mesh", "kde", "webs", "adb", "ssh", "data"]
ok(left == want, f"G1 left order {left}")
short = {e["id"]: e["short"] for e in els}
ok([short[i] for i in want] == ["5G", "WiFi", "BT", "HS", "GPS", "WG", "KDE", "WebS", "ADB", "SSHd", "TypC"], "G1 the strip captions read 5G WiFi BT HS GPS WG KDE WebS ADB SSHd TypC")
ns = read(SRC + "network/NetworkSections.kt")
consts = dict(re.findall(r'const val ([A-Z]+) = "([a-z]+)"', ns))
order = [consts[x] for x in re.search(r'val ORDER: List<String> = listOf\(([^)]*)\)', ns).group(1).replace(" ", "").split(",")]
ok(order == want, "G1 NetworkSections.ORDER is the same order")
groups = [[consts[x.strip()] for x in g.split(",")] for g in re.findall(r'listOf\(([A-Z, ]+)\)', ns.split("val GROUPS")[1].split("val ORDER")[0])]
ok(sum(groups, []) == order and len(groups) == 4, f"G2 GROUPS {groups} flatten to ORDER")
strip = code(read(SRC + "launcher/LauncherStatusStrip.kt"))
ok("NetworkSections.separatorBetween(prev, el.id)" in strip and "GroupSeparator(" in strip, "G2 the strip draws a separator between groups")
pop = code(read(SRC + "network/NetworkInfoPopup.kt"))
ok("NetworkSections.layout()" in pop and "is NetworkSections.Item.GroupDivider -> PopGroupDivider()" in pop, "G2 the dropdown lays out from ORDER with group dividers")
ok(all(f"{k} -> Section({k})" in pop for k in ("WEBS", "SSHD", "DATA")), "G2 the dropdown has WebS, SSHd and TypC sections")
dd = {d["id"]: d for d in sb["dropdowns"]}
ok(set(dd["left_network"]["opened_by"]) == set(want) - {"adb"}, "G2 every left element but ADB opens the network dropdown")
before = ["cellular", "wifi", "bluetooth", "mesh", "kde", "adb", "data", "hotspot", "gps", "clock", "ram", "storage", "cpu", "fps", "battery"]
ids = {e["id"]: e for e in els}
ok(all(i in ids for i in before), "G3 every pre-#1684 element is still declared")
ok(ids["data"]["short"] == "TypC" and ids["data"]["pet"] == "usb" and '"Data"' in ns, "G3 Data renamed TypC: same id, same pet, former label kept")
ld = read(SRC + "network/LocalDoors.kt"); ldc = code(ld)
ok('"HEAD $route HTTP/1.1' in ldc and 'startsWith("SSH-")' in ldc and "STATUS_BAR_B64" in ldc and '"resolved"' in ldc, "G4 HEAD on the route, SSH banner, ports from the resolved status-bar declaration")
lit = [p for p in ("8002", "8003", "8004", "8022", "8024", "8025") if p in ldc or p in code(read(SRC + "launcher/StripDoors.kt"))]
ok(not lit, f"G4 no port literal in the door code {lit}")
g = read("app/build.gradle")
ok("local_doors.resolved = localDoorsResolved" in g and "LOCAL_DOORS_B64" not in g, "G4 app/build.gradle resolves local_doors into the status bar declaration (no extra BuildConfig field)")
d = sb["local_doors"]
ok(all(not k.endswith("port") for k in d.get("webs", {})) and "port_from" in d["webs"], "G4 the declaration carries references, not ports")
mono = os.path.dirname(app)
def ref(r):
    path, keys = r.split("#", 1)
    f = os.path.join(mono, path)
    if not os.path.isfile(f): return "absent"
    v = json.load(open(f, encoding="utf-8"))
    for k in keys.split("."): v = v.get(k) if isinstance(v, dict) else None
    return v
w = ref(d["webs"]["port_from"])
if w != "absent":
    ok(isinstance(w, int), f"G4 the WebS port resolves ({w})")
    doors = ref(d["doors_from"]) or []
    for i in d["sshd_ids"]:
        x = next((y for y in doors if y.get("id") == i), None)
        p = ref(x["port_from"]) if x and "port_from" in x else None
        ok(isinstance(p, int), f"G4 SSH door {i} resolves its port ({p})")
else:
    print("skip G4 resolution: ac_cloud-c3-webserver not checked out here")
body = strip.split("internal fun refreshDoors()")[1].split("internal fun noteDoors")[0]
if mut == "poll": body += "mainHandler.postDelayed(x, 1000)"
ok(not re.search(r'postDelayed|Timer\(|schedule|while\s*\(\s*true|delay\(', body + ldc), "G5 the door checks never poll")
ok("refreshDoors()" in strip.split("fun start()")[1].split("fun stop()")[0] and "model.refreshDoors()" in strip and "onAvailable" in strip, "G5 checks run on start, resume and network change")
um = code(read(SRC + "network/UsbDataModel.kt"))
ok("val cable: Cable" in um and "EXTRA_PLUGGED" in strip.split("private fun refreshBattery")[1].split("private fun")[0] and "deriveCable()" in strip, "G6 TypC from USB_STATE + battery broadcasts")
sys.exit(1 if fails else 0)
PY
}
check "$APP" none || exit 1
for m in order poll; do
  if check "$APP" "$m" >/dev/null 2>&1; then echo "FAIL mutation '$m' did not go red"; exit 1; else echo "ok   mutation '$m' goes red"; fi
done
echo "PASS"
