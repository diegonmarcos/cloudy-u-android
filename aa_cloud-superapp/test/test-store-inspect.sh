#!/usr/bin/env bash
# Store ▸ Cloud per-app INSPECT sheets (#redesign): Perms, Routes, Libs, About — the four buttons on
# the menu's second line. Each opens as a ModalBottomSheet OVER the Cloud page, so the store's bottom
# nav and Back are untouched (the nav-shape bug the guards exist for: a sub-screen must never replace
# the nav). This guard holds the sheets, their REAL data sources, the "none declared" fallback, and
# the nav-safety (overlay, not a nav-replacing activity).
#
# Override for mutation runs: LIBS.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
python3 - "${LIBS:-$ROOT/ab_cloud-libs-shared/libs}" <<'PY'
import os, re, sys
libs = sys.argv[1]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    try:
        with open(p, encoding="utf-8") as f: return f.read()
    except FileNotFoundError: return ""

kt = os.path.join(libs, "appstore/src/main/java/com/diegonmarcos/superapp/appstore")
insp = read(os.path.join(kt, "StoreInspect.kt"))
store = read(os.path.join(kt, "StoreCloudFragment.kt"))
sheet = read(os.path.join(kt, "ApkDetailSheet.kt"))

print("== T1: the inspect sheets exist and open over the page (nav-safe) ==")
if insp: ok("StoreInspect.kt is present")
else: bad("StoreInspect.kt is missing")
for name in ("Perms", "Routes", "Libs"):
    if re.search(r"fun %s\(app: Fleet\.App" % name, insp): ok("StoreInspect.%s(app, onDismiss) exists" % name)
    else: bad("StoreInspect.%s is missing" % name)
if insp.count("ModalBottomSheet(") >= 1 and "fun Sheet(" in insp:
    ok("the sheets are ModalBottomSheet overlays (the nav bar and Back stay)")
else: bad("the inspect sheets are not ModalBottomSheet overlays")
# nav-safety: the ONLY startActivity an inspect sheet makes is Perms' Open to Android app settings;
# nothing here pushes a fragment/nav destination that could replace the store's bottom nav.
starts = re.findall(r"startActivity\([^\n]*", insp)
if len(starts) == 1 and "appInfoIntent" in starts[0]:
    ok("no inspect sheet replaces the screen — the only intent is Perms' Open to Android settings")
else: bad("unexpected startActivity in an inspect sheet: %s" % starts)
# the fragment drives them off a stack, like the About/Details sheet — not a tab/nav swap
if "private val inspect" in store and "Inspect.Perms" in store and "inspect.lastOrNull()" in store:
    ok("StoreCloudFragment renders the inspect stack over Content (same overlay as the About sheet)")
else: bad("the fragment does not render the inspect stack as an overlay")

print("== T2: Perms — three tables from PackageManager, with a top action row ==")
if "GET_PERMISSIONS" in insp and "requestedPermissions" in insp and "requestedPermissionsFlags" in insp:
    ok("Perms reads getPackageInfo(pkg, GET_PERMISSIONS) + flags")
else: bad("Perms does not read the package's permissions from PackageManager")
for tag in ("Manifest", "Auto grants", "Runtime"):
    if 'Table("%s"' % tag in insp: ok("the %s table is drawn" % tag)
    else: bad("the %s table is missing" % tag)
if "PROTECTION_DANGEROUS" in insp and "REQUESTED_PERMISSION_GRANTED" in insp:
    ok("Runtime is the dangerous perms with granted/denied state; Auto grants the install-time normal ones")
else: bad("Perms does not split dangerous (runtime) from normal (auto-granted)")
if "PermsPages.appInfoIntent(" in insp and "Ask Grant All" in insp and "StoreShell.exec(" in insp and "pm grant" in insp:
    ok("top row: Open (Android app screen) + Ask Grant All (StoreShell `pm grant`, the broker Stop uses)")
else: bad("the Perms top action row (Open / Ask Grant All) is not wired to the real paths")

print("== T3: Routes — MCP/IPC/API tabs over REAL declared route data ==")
if "StoreTabs.Bar(" in insp and '"MCP"' in insp and '"IPC"' in insp and '"API"' in insp:
    ok("Routes draws MCP · IPC · API tabs")
else: bad("Routes does not draw the three tabs")
if "AppsMcpApi.load(" in insp and "AppsMcpApi.rows(" in insp and 'byKind?.get("mcp")' in insp:
    ok("MCP + API come from AppsMcpApi (assets/apps-mcp-api.json)")
else: bad("Routes does not read AppsMcpApi for MCP/API")
if "IpcMesh.baked()" in insp and ".members.firstOrNull { it.id == app.id }" in insp:
    ok("IPC comes from IpcMesh (baked ipc-mesh.json), matched by the app's fleet id")
else: bad("Routes does not read IpcMesh for IPC")
if insp.count('Note("none declared")') >= 1:
    ok("a tab or table with no declared data shows \"none declared\" (never fabricated)")
else: bad("no \"none declared\" fallback for empty route/lib data")

print("== T4: Libs — the fleet row's own build.json-derived deps ==")
if "app.libs" in insp and "app.engineLibs" in insp:
    ok("Libs lists app.libs (compiled in) and app.engineLibs (bound engines)")
else: bad("Libs does not read the app's declared lib deps")

print("== T5: About reuses the full detail sheet (ApkDetailSheet) ==")
if "ApkDetailSheet.Sheet(" in store and "InstalledCard" in sheet and "AvailableCard" in sheet:
    ok("About opens ApkDetailSheet — version, package, install state, built-from, size")
else: bad("About does not reuse ApkDetailSheet")

print("\n%d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
