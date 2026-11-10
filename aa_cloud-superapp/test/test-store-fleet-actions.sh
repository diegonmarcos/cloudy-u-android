#!/usr/bin/env bash
# Store ▸ Cloud per-app menu: DECLARED in libs:appstore's
# assets/appstore-fleet-actions.json, dispatched by id in the code.
#
# #1588 the menu is TWO grouped lines:
#   rows[0] fetch/install lifecycle : Update · Uninstall⇄Install · Fetch · Clear · Download
#   rows[1] open/inspect            : Open · Stop │ Access · Perm │ Libs · About
# Update fetches (reusing the cache) AND installs through the prompted BootstrapInstall system sheet.
# `install_toggle` is ONE button that reads Install (absent) / Uninstall (installed) and runs the
# matching action. Fetch is the cache-only download (StoreStages.DOWNLOAD, no install); Clear wipes
# the cache; Download copies the apk into the phone's public Downloads for the user to keep. The
# `direct_install` and `routes` handlers stay wired in the code but are no longer drawn on the row.
# `divider_before` draws the owner's `│` rule before Access and before Libs, setting Open/Stop apart
# from the Access/Perm pair and that apart from the Libs/About pair. About opens the full detail sheet.
# Stop still goes through the fleet's EXISTING privileged door (StoreShell).
#
# Order, captions, colours and grouping are the asset's; this guard reads them from there and checks
# the code draws exactly that and can act on every id.
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

rows = decl["rows"]
flat = [a for line in rows for a in line]
ids = [a["id"] for line in rows for a in line]
details = [a["id"] for a in decl["details"]]
label = {a["id"]: a["label"] for a in flat + decl["details"]}

print("== T1: the declaration is the two grouped lines the owner asked for (#1588) ==")
if [a["id"] for a in rows[0]] == ["update", "install_toggle", "fetch", "clear", "download"]:
    ok("row 0 = Update · Uninstall/Install · Fetch · Clear · Download")
else: bad("row 0 is not the fetch/install lifecycle line: %s" % [a["id"] for a in rows[0]])
if [a["id"] for a in rows[1]] == ["open", "stop", "access", "perms", "libs", "about"]:
    ok("row 1 = Open · Stop │ Access · Perm │ Libs · About")
else: bad("row 1 is not the open/inspect line: %s" % [a["id"] for a in rows[1]])
tog = next((a for a in flat if a["id"] == "install_toggle"), {})
if tog.get("label") == "Install" and tog.get("label_installed") == "Uninstall":
    ok("install_toggle reads Install / Uninstall")
else: bad("install_toggle is not an Install/Uninstall toggle: %s" % tog)
if label.get("update") == "Update" and label.get("fetch") == "Fetch" \
        and label.get("download") == "Download" and label.get("perms") == "Perm":
    ok("captions relabelled: Update, Fetch, Download, Perm")
else: bad("captions not relabelled per #1588: %s" % {k: label.get(k) for k in ("update","fetch","download","perms")})
access = next((a for a in flat if a["id"] == "access"), {})
libsd = next((a for a in flat if a["id"] == "libs"), {})
if access.get("divider_before") is True and libsd.get("divider_before") is True:
    ok("the `│` dividers are declared before Access and before Libs")
else: bad("the group dividers are not before Access and Libs: access=%s libs=%s" % (access, libsd))
if {"update", "fetch", "download"} <= set(ids) and "install" not in ids and "direct_install" not in ids:
    ok("Update/Fetch/Download are on the row; plain `install` and `direct_install` are off it")
else: bad("row lifecycle ids wrong: %s" % ids)
if details == ["app_settings"]: ok("the About sheet keeps App settings: %s" % details)
else: bad("details is not [app_settings]: %s" % details)

print("== T2: the code draws the two lines and can act on every id ==")
body = fn(store, "FleetDetail")
if "FleetActions.rows(ctx)" in body and "StoreRow.Actions(" in body and "perLine = drawn.size" in body:
    ok("FleetDetail draws every declared line through the shared StoreRow.Actions, one visual row per line")
else: bad("FleetDetail does not draw its lines through StoreRow.Actions")
if "dividerBefore" in body and "dividers = dividers" in body:
    ok("the owner's `│` rule is passed to StoreRow.Actions where dividerBefore is declared")
else: bad("FleetDetail does not pass the divider to StoreRow.Actions")
bar = read(os.path.join(kt, "StoreBar.kt"))
if re.search(r"fun Actions\(verbs:[^)]*dividers:", bar, re.S) and '"│"' in bar:
    ok("StoreRow.Actions (the shared row builder) draws the `│` divider")
else: bad("StoreRow.Actions has no divider support")
if "FleetActions.details(ctx)" in sheet and "detailAction(" in sheet: ok("the About sheet iterates FleetActions.details")
else: bad("ApkDetailSheet does not build its actions from FleetActions.details")
ra, da = fn(store, "rowAction"), fn(sheet, "detailAction")
# a handler branch is `"id" ->` or a grouped `"id", "other" ->`; accept the id literal followed by , or ->
def handled(src, i): return re.search(r'"%s"\s*(?:,|->)' % re.escape(i), src) is not None
missing = [i for i in ids if not handled(ra, i)] + [i for i in details if not handled(da, i)]
if ra and da and not missing: ok("every declared id has a handler (%d row, %d details)" % (len(ids), len(details)))
else: bad("declared ids with no handler: %s" % (missing or "rowAction/detailAction not found"))
if re.search(r'"install_toggle" ->.*?installed.*?Fleet\.uninstall', ra, re.S) and "promptedInstall(" in ra:
    ok("install_toggle: Uninstall when installed, else the prompted BootstrapInstall path")
else: bad("install_toggle does not toggle uninstall vs prompted install")
# #1588 the relabelled lifecycle verbs reach their real backing paths.
if re.search(r'"update" ->.*?promptedInstall\(', ra, re.S):
    ok("Update fetches+installs through the prompted BootstrapInstall path")
else: bad("Update is not wired to the prompted install path")
if re.search(r'"fetch" ->.*?StoreStages\.DOWNLOAD', ra, re.S):
    ok("Fetch is the cache-only StoreStages download")
else: bad("Fetch is not wired to the cache-only StoreStages download")
if re.search(r'"download" ->.*?saveToDownloads\(', ra, re.S) and \
        "MediaStore.Downloads" in store and "DIRECTORY_DOWNLOADS" in store:
    ok("Download copies the apk into the phone's public Downloads (MediaStore)")
else: bad("Download is not wired to a public-Downloads copy")
# direct_install stays wired in the code though it is no longer drawn on the row (#1588).
if '"direct_install" ->' in ra and "StoreStages.INSTALL" in ra:
    ok("the silent StoreStages install (direct_install) stays wired")
else: bad("the silent StoreStages install is not wired")
for vid, kind in (("perms", "Perms"), ("routes", "Routes"), ("libs", "Libs"), ("access", "Access")):
    if re.search(r'"%s" ->.*?inspect\.add\(Inspect\.%s\(' % (vid, kind), ra, re.S): ok("%s opens the %s inspect sheet" % (vid, kind))
    else: bad("%s does not open an inspect sheet" % vid)
if re.search(r'"about", "details" ->.*?details\.add', ra, re.S): ok("About opens the full detail sheet (supersedes Details)")
else: bad("About does not open the detail sheet")
def code(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r'(?<![:"])//.*$', "", l) for l in text.splitlines())
# The ROW's captions must come from the declaration, so none is written in the dispatch code
# (StoreCloudFragment). The About sheet is content, not the menu — its own section titles like
# "Libs"/"Installed" legitimately repeat a word and are not scanned here.
lit = [l for l in label.values() if '"%s"' % l in code(store)]
if not lit: ok("no declared caption is written in StoreCloudFragment's dispatch")
else: bad("captions hard-coded in Kotlin: %s" % lit)

print("== T3: Stop uses the existing privileged door, and is never a silent no-op ==")
fs = fn(actions, "forceStop")
if "StoreShell.forceStop(" in fs: ok("PhoneAppActions.forceStop runs am force-stop through StoreShell (the shell broker, #1512)")
else: bad("forceStop does not go through StoreShell")
if "PhoneAppActions.forceStop(" in phone: ok("Phone Apps' Stop uses the same door")
else: bad("Phone Apps' Stop does not use PhoneAppActions.forceStop")
st = fn(store, "stop")
if '"stop" ->' in ra and "stop(ctx, app)" in ra and "PhoneAppActions.forceStop(" in st: ok("the row's Stop calls forceStop")
else: bad("the row's Stop does not reach PhoneAppActions.forceStop")
nul = re.search(r"out == null ->(.*?)(?=\n\s*out\.contains)", st, re.S)
cont = store[store.find("override fun Content()"):store.find("// ── tabs:")]
if nul and "stopNoChannel = app" in nul.group(1) and "AlertDialog" in cont and "PhoneAppActions.appInfo(" in cont \
        and "R.string.store_fleet_stop_no_channel" in cont:
    ok("no channel -> a dialog that says so and offers App settings")
else: bad("with no channel armed, Stop does not explain and offer App settings")
if 'name="store_fleet_stop_no_channel"' in strings: ok("the no-channel message is declared")
else: bad("store_fleet_stop_no_channel is not in strings.xml")

print("== T4: App settings and the prompted install reach the real platform paths ==")
if "PermsPages.appInfoIntent(pkg)" in fn(actions, "appInfo") and \
        re.search(r'"app_settings" ->(?:(?!\n\s*"\w+" ->).)*PhoneAppActions\.appInfo\(', da, re.S):
    ok("app_settings opens the package's app-details page through libs:perms PermsPages (#1274)")
else: bad("app_settings does not open ACTION_APPLICATION_DETAILS_SETTINGS")
if "BootstrapInstall.launch(" in fn(store, "promptedInstall"):
    ok("the toggle's prompted install is the BootstrapInstall system-sheet floor")
else: bad("promptedInstall does not reach BootstrapInstall.launch")

print("\n%d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
