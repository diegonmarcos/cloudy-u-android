#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #813 — Home ▸ Apps last row, and the Disk Management engine + page        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   K1  THE GRID SIZES ITSELF. AppsGrid is never held to a height computed from
#       a row count (`appTile * rows` clipped the last row) and is not a lazy
#       grid nested in Home's LazyColumn (a lazy grid never composes the rows it
#       is too short for). AppsGridLayoutTest proves the last tile on screen.
#   K2  THE ENTRY IS DECLARED. data/drive-apps.json routes a Disk Management
#       tile to {tab: home, page: manage} (#1484: Manage is a Home sub-page; it was
#       a bottom-nav tab), and ManageScreen dispatches the apps page.
#   K3  THE STORE CACHE IS CLEANED THROUGH ITS CONTRACT. DriveDisk previews with
#       ApkCache.plan(..).redundantBytes and clears with ApkCache.clearRedundant
#       (proven-installed only) — never ApkCache.clear (which drops pending
#       installs) and never a delete of its own in that directory (#625, #812).
#   K4  THE DRY RUN IS THE RUN. DiskEngine.clean executes the previewed plan it
#       is handed, and the API runs a clean only with run=1 AND confirm=1.
#   K5  THE GRANT IS A BUTTON. Usage access is asked for through
#       AppSizes.openPermissions (#639, #1274: the Permissions page), started from a Pill, never a text line.
#   K6  /api/disk/* IS REGISTERED AND DECLARED. DriveDebugApi registers the disk
#       group on the shared server; build.json's debug_api lists it.
#
# Each check is MUTATION-PROVEN below. python3 and grep only, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
LIB="$ROOT/ab_cloud-libs-shared/libs/disk/src/main/java/com/diegonmarcos/cloudlib/disk"
GRID="$SRC/apps/AppsGrid.kt"; APPS="$APP/data/drive-apps.json"
DD="$SRC/disk/DriveDisk.kt"; ENG="$LIB/DiskEngine.kt"; SCREEN="$SRC/manage/ManageScreen.kt"
API="$SRC/debugapi/DriveDebugApi.kt"; DAPI="$SRC/disk/DiskDebugApi.kt"; BJ="$APP/build.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

for f in "$GRID" "$APPS" "$DD" "$ENG" "$SCREEN" "$API" "$DAPI" "$BJ"; do
    [ -f "$f" ] || { echo "  FAIL  missing $f"; exit 1; }
done

k1() { [ "$(_code "$1" | grep -cE 'appTile \* rows|LazyVerticalGrid|\.height\(DriveMetrics\.appTile')" -eq 0 ] && [ "$(grep -c 'chunked(COLUMNS)' "$1")" -ge 1 ]; }
k2() {
    python3 - "$1" "$2" <<'PY'
import json, sys
apps = json.load(open(sys.argv[1], encoding="utf-8"))["apps"]
if not any(a.get("route") == {"tab": "manage", "page": "overview"} for a in apps): sys.exit(1)
screen = open(sys.argv[2], encoding="utf-8").read()
# #1715 Fleet apps page removed: Local Apps is the one AppsPage
if 'fun LocalAppsPage(' not in screen or 'AppsPage(prefs, actions = actions' not in screen: sys.exit(1)
PY
}
k3() {
    [ "$(grep -c 'ApkCache.plan(ctx).redundantBytes' "$1")" -eq 1 ] || return 1
    [ "$(grep -c 'ApkCache.clearRedundant(ctx).freedBytes' "$1")" -eq 1 ] || return 1
    [ "$(_code "$1" | grep -cE 'ApkCache\.clear\(|ApkCache\.drop\(|\.delete\(\)|deleteRecursively')" -eq 0 ]
}
k4() {
    [ "$(grep -c 'val res = CleanPlan.run(preview.plan, { p -> sizeOf(p) })' "$1")" -eq 1 ] || return 1
    [ "$(grep -c 'if (q\["confirm"\] != "1") ok(false' "$1")" -eq 1 ] || return 1
    [ "$(grep -c 'if (q\["run"\] == "1")' "$1")" -eq 1 ]
}
k5() { [ "$(grep -c 'AppSizes.openPermissions(ctx)' "$1")" -ge 1 ] && [ "$(grep -c 'Pill(stringResource(R.string.disk_grant_usage), { AppSizes.openPermissions(ctx)' "$1")" -eq 1 ]; }
k6() {
    [ "$(_code "$1" | grep -c 'DiskDebugApi.register(app)')" -eq 1 ] || return 1
    [ "$(grep -c 'AppDebugServer.route(' "$2")" -ge 1 ] || return 1
    python3 -c 'import json,sys; sys.exit(0 if "disk" in json.load(open(sys.argv[1]))["diagnostics"]["debug_api"]["groups"] else 1)' "$3"
}

echo "── K checks ──"
k1 "$GRID" && pass "K1 the Apps grid sizes itself (no row-count height, no nested lazy grid)" || fail "K1"
k2 "$APPS" "$SCREEN" && pass "K2 the Disk Management tile routes to Manage ▸ Overview (#1537)" || fail "K2"
k3 "$DD" && pass "K3 the Store APK cache is previewed and cleared through ApkCache's proven-installed half only" || fail "K3"
k4 "$ENG" && pass "K4 the clean runs the previewed plan; the API deletes only with run=1&confirm=1" || fail "K4"
k5 "$SCREEN" && pass "K5 usage access is a button over AppSizes.openPermissions" || fail "K5"
k6 "$API" "$DAPI" "$BJ" && pass "K6 /api/disk/* registered on the shared server and declared" || fail "K6"

echo "== M mutation-proof =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
_stage() { cp "$GRID" "$APPS" "$DD" "$ENG" "$SCREEN" "$API" "$DAPI" "$BJ" "$W/"; }
_sub() { python3 - "$1" "$2" "$3" <<'PY'
import sys
p, a, b = sys.argv[1:]
s = open(p, encoding="utf-8").read()
if a not in s: sys.exit("mutation anchor not found in " + p + ": " + a)
open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
}
_red() { local label="$1"; shift; if "$@" >/dev/null 2>&1; then fail "MUT-SURVIVED $label"; else echo "  MUT-RED     $label"; fi; }

_stage; _sub "$W/AppsGrid.kt" 'Modifier.fillMaxWidth().testTag(DriveTags.APPS_GRID)' 'Modifier.fillMaxWidth().height(DriveMetrics.appTile * rows).testTag(DriveTags.APPS_GRID)'
_red "K1 the grid is held to appTile * rows again" k1 "$W/AppsGrid.kt"
_stage; _sub "$W/drive-apps.json" '"page": "overview"' '"page": "rclone"'
_red "K2 the tile routes somewhere else" k2 "$W/drive-apps.json" "$W/ManageScreen.kt"
_stage; _sub "$W/DriveDisk.kt" 'ApkCache.clearRedundant(ctx).freedBytes' 'ApkCache.clear(ctx).freedBytes'
_red "K3 the clean evicts pending installs (ApkCache.clear)" k3 "$W/DriveDisk.kt"
_stage; _sub "$W/DriveDisk.kt" 'ApkCache.plan(ctx).redundantBytes' 'ApkCache.totalBytes(ctx)'
_red "K3 the preview counts the whole cache, pending installs included" k3 "$W/DriveDisk.kt"
_stage; _sub "$W/DiskEngine.kt" 'val res = CleanPlan.run(preview.plan, { p -> sizeOf(p) })' 'val res = CleanPlan.run(cleanPreview().plan, { p -> sizeOf(p) })'
_red "K4 the run re-plans instead of executing the previewed plan" k4 "$W/DiskEngine.kt"
_stage; _sub "$W/DiskEngine.kt" 'if (q["confirm"] != "1") ok(false' 'if (false) ok(false'
_red "K4 the API cleans without confirm" k4 "$W/DiskEngine.kt"
_stage; _sub "$W/ManageScreen.kt" 'Pill(stringResource(R.string.disk_grant_usage), { AppSizes.openPermissions(ctx)' 'Text(stringResource(R.string.disk_grant_usage)); Pill("", {'
_red "K5 the grant becomes a sentence" k5 "$W/ManageScreen.kt"
_stage; _sub "$W/DriveDebugApi.kt" 'com.diegonmarcos.clouddrive.disk.DiskDebugApi.register(app)' ''
_red "K6 the disk routes stop being registered" k6 "$W/DriveDebugApi.kt" "$W/DiskDebugApi.kt" "$W/build.json"
_stage; _sub "$W/build.json" '"log", "disk"]' '"log"]'
_red "K6 the declaration forgets the disk group" k6 "$W/DriveDebugApi.kt" "$W/DiskDebugApi.kt" "$W/build.json"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-disk: all checks passed"; exit 0; fi
echo "test-drive-disk: $FAILURES check(s) FAILED"; exit 1
