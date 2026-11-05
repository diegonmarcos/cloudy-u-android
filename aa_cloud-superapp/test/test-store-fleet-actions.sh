#!/usr/bin/env bash
# Store ▸ Cloud row and Details buttons: DECLARED in libs:appstore's
# assets/appstore-fleet-actions.json, dispatched by id in the code.
#
# The ask, interpreted: Direct install leaves the row for the Details sheet,
# which also gains App settings (Android's own page for the package). In
# Direct install's old place the row gets Stop, immediately before Uninstall.
# Stop goes through the fleet's EXISTING privileged door — the ShellChannels
# ladder Phone Apps' Stop already used — not a second one, and with no channel
# armed it says so and offers App settings instead of doing nothing.
#
# Order and captions are the asset's; this guard reads them from there and
# checks the code draws exactly that and can act on every id.
#
# Override for mutation runs: LIBS.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
python3 - "${LIBS:-$ROOT/ab_cloud-libs-shared/libs}" <<'PY'
import json, os, re, sys

libs = sys.argv[1]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

base = os.path.join(libs, "appstore/src/main")
kt = os.path.join(base, "java/com/diegonmarcos/superapp/appstore")
decl = json.loads(read(os.path.join(base, "assets/appstore-fleet-actions.json")))
store = read(os.path.join(kt, "StoreCloudFragment.kt"))
sheet = read(os.path.join(kt, "ApkDetailSheet.kt"))
phone = read(os.path.join(kt, "StorePhoneFragment.kt"))
actions = read(os.path.join(kt, "PhoneAppActions.kt"))
strings = read(os.path.join(base, "res/values/strings.xml"))

def fn(src, name):
    """Body of `fun name(` up to the next member at class indentation."""
    m = re.search(r"\n    (?:private )?fun %s\(.*?(?=\n    (?:private |internal )?(?:fun|val|/\*\*|//)|\n}\s*$)" % name, src, re.S)
    return m.group(0) if m else ""

row = [a["id"] for a in decl["row"]]
details = [a["id"] for a in decl["details"]]
label = {a["id"]: a["label"] for a in decl["row"] + decl["details"]}

print("== T1: the declaration says what the owner asked ==")
if row[-2:] == ["stop", "uninstall"]: ok("row ends [..., stop, uninstall]: %s" % row)
else: bad("row does not end with stop then uninstall: %s" % row)
if "direct_install" not in row: ok("the row has no direct_install")
else: bad("direct_install is still on the row")
if {"direct_install", "app_settings"} <= set(details): ok("Details declares direct_install and app_settings: %s" % details)
else: bad("Details is missing direct_install or app_settings: %s" % details)
want = {"stop": "Stop", "direct_install": "Direct install", "app_settings": "App settings"}
off = {k: label.get(k) for k, v in want.items() if label.get(k) != v}
if not off: ok("captions: %s" % ", ".join("%s=%r" % (k, label[k]) for k in want))
else: bad("captions differ from the ask: %s" % off)

print("== T2: the code draws the declaration and can act on every id ==")
body = fn(store, "FleetDetail")
if "FleetActions.row(ctx)" in body and "rowAction(" in body: ok("the row is built by iterating FleetActions.row")
else: bad("FleetDetail does not build its buttons from FleetActions.row")
if "FleetActions.details(ctx)" in sheet and "detailAction(" in sheet: ok("Details is built by iterating FleetActions.details")
else: bad("ApkDetailSheet does not build its actions from FleetActions.details")
ra, da = fn(store, "rowAction"), fn(sheet, "detailAction")
missing = [i for i in row if '"%s" ->' % i not in ra] + [i for i in details if '"%s" ->' % i not in da]
if ra and da and not missing: ok("every declared id has a handler (%d row, %d details)" % (len(row), len(details)))
else: bad("declared ids with no handler: %s" % (missing or "rowAction/detailAction not found"))
def code(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r'(?<![:"])//.*$', "", l) for l in text.splitlines())
lit = [l for l in label.values() if '"%s"' % l in code(store) + code(sheet)]
if not lit: ok("no declared caption is written in the Kotlin")
else: bad("captions hard-coded in Kotlin: %s" % lit)

print("== T3: Stop uses the existing privileged door, and is never a silent no-op ==")
fs = fn(actions, "forceStop")
if "StoreShell.forceStop(" in fs: ok("PhoneAppActions.forceStop runs am force-stop through StoreShell (the shell broker, #1512)")
else: bad("forceStop does not go through StoreShell")
doors = sum(read(os.path.join(dp, f)).count("am force-stop")
            for dp, _, fs_ in os.walk(os.path.join(libs, "appstore/src")) for f in fs_ if f.endswith(".kt"))
if doors == 1: ok("exactly one force-stop door in libs:appstore")
else: bad("%d force-stop commands in libs:appstore — a second door" % doors)
if "PhoneAppActions.forceStop(" in phone: ok("Phone Apps' Stop uses the same door")
else: bad("Phone Apps' Stop does not use PhoneAppActions.forceStop")
st = fn(store, "stop")
if '"stop" ->' in ra and "stop(ctx, app)" in ra and "PhoneAppActions.forceStop(" in st: ok("the row's Stop calls forceStop")
else: bad("the row's Stop does not reach PhoneAppActions.forceStop")
nul = re.search(r"out == null ->(.*?)(?=\n\s*out\.contains)", st, re.S)
# the dialog is state: stop() raises it (stopNoChannel), the page's Content draws it
cont = store[store.find("override fun Content()"):store.find("// ── tabs:")]
if nul and "stopNoChannel = app" in nul.group(1) and "AlertDialog" in cont and "PhoneAppActions.appInfo(" in cont \
        and "R.string.store_fleet_stop_no_channel" in cont:
    ok("no channel -> a dialog that says so and offers App settings")
else: bad("with no channel armed, Stop does not explain and offer App settings")
if 'name="store_fleet_stop_no_channel"' in strings: ok("the no-channel message is declared")
else: bad("store_fleet_stop_no_channel is not in strings.xml")

print("== T4: App settings is Android's own app-details page ==")
if "PermsPages.appInfoIntent(pkg)" in fn(actions, "appInfo") and \
        re.search(r'"app_settings" ->(?:(?!\n\s*"\w+" ->).)*PhoneAppActions\.appInfo\(', da, re.S):
    ok("app_settings opens the package's app-details page through libs:perms PermsPages (#1274)")
else: bad("app_settings does not open ACTION_APPLICATION_DETAILS_SETTINGS")
if re.search(r'"direct_install" ->\s*directInstall\(', da) and "BootstrapInstall.launch(" in fn(sheet, "directInstall"):
    ok("Direct install on Details is the BootstrapInstall floor")
else: bad("Details' Direct install does not reach BootstrapInstall.launch")

print("\n%d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
