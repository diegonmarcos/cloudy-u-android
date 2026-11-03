#!/usr/bin/env bash
# test-me-perms: Configs > Perms is DECLARED (build.json::ui.permissions) and the manifest agrees with it.
#   P1 every `entries` id is a row of the shared libs:perms catalogue, once
#   P2 AndroidManifest.xml <uses-permission> == the declared permissions, BOTH ways (catalogue rows' permission,
#      `install`, `health.read`, `health.write`)
#   P3 health.read is the SuperApp's exact Health Connect set (libs:health's manifest); every write has its read
#   P4 the Health Connect rationale activity, the Android 14 VIEW_PERMISSION_USAGE alias and the queries are declared
#   P5 wiring: libs:perms is a module the app depends on, the declaration is baked and read, the Kotlin names no permission
#   P6 the start-up rule: runtime asks only DENIED (never PERMANENTLY_DENIED), Health Connect stops after 2 asks, special
#      pages open once; MainActivity runs startup on open and Grant all from the banner; Configs shows the list
#   P7 the page look: every row has a libs:ui-icons icon and a one-line reason; the page and banner are libs:perms'
#      shared PermsList / PermsPromptCard (#1372: unused collapsed, chips, dividers, the Grant all primary button with its
#      count, a dismissible banner), MePerms.kt uses the ui-kit palette only, no raw ids in Configs
# Then each mutation below is planted in a scratch copy and must turn the check RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
FAILS=0; ok() { echo "  PASS: $1"; }; bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
CHECK="$(mktemp -d)/check.py"; S="$(mktemp -d)"; trap 'rm -rf "$S" "$(dirname "$CHECK")"' EXIT
cat > "$CHECK" <<'PY'
import json, re, sys
root = sys.argv[1]; app = root + "/ac_cloud-me"; src = app + "/app/src/main/java/com/diegonmarcos/cloudme"
out = []
def bad(m): out.append(m)
d = json.load(open(app + "/build.json")); p = d["ui"]["permissions"]
cat = {e["id"]: e for e in json.load(open(root + "/ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json"))["entries"]}
ids = [e["id"] for e in p["entries"]]
if len(ids) != len(set(ids)): bad("P1 an entry id is declared twice")
for i in ids:
    if i not in cat: bad("P1 %s is not a libs:perms catalogue row" % i)
for e in p["entries"]:
    if not e.get("why"): bad("P1 %s has no reason" % e["id"])
man = open(app + "/app/src/main/AndroidManifest.xml").read()
in_man = set(re.findall(r'<uses-permission\s+android:name="([^"]+)"', man))
declared = {cat[i]["permission"] for i in ids if i in cat and cat[i].get("permission") and cat[i]["kind"] != "adb_only"}
declared |= {x["perm"] for x in p["install"]} | set(p["health"]["read"]) | set(p["health"]["write"])
if in_man - declared: bad("P2 in the manifest, not declared: %s" % sorted(in_man - declared))
if declared - in_man: bad("P2 declared, not in the manifest: %s" % sorted(declared - in_man))
for need in ("special", "runtime"):
    if not any(cat.get(i, {}).get("section") == need or cat.get(i, {}).get("kind") == need for i in ids): bad("P2 no %s entry declared" % need)
hman = open(root + "/ab_cloud-libs-shared/libs/health/src/main/AndroidManifest.xml").read()
sa = set(re.findall(r'<uses-permission\s+android:name="(android\.permission\.health\.[^"]+)"', hman))
if set(p["health"]["read"]) != sa: bad("P3 health.read differs from the SuperApp's set: missing %s, extra %s" % (sorted(sa - set(p["health"]["read"])), sorted(set(p["health"]["read"]) - sa)))
for w in p["health"]["write"]:
    if w.replace(".WRITE_", ".READ_") not in p["health"]["read"]: bad("P3 %s has no matching read" % w)
if not p["health"].get("rationale"): bad("P3 no Health Connect rationale text")
act = re.search(r'<activity\s+android:name="\.health\.HealthRationaleActivity".*?</activity>', man, re.S)
if not act or "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" not in act.group(0): bad("P4 no rationale activity with ACTION_SHOW_PERMISSIONS_RATIONALE")
alias = re.search(r'<activity-alias.*?</activity-alias>', man, re.S)
if not alias or not all(k in alias.group(0) for k in ("android.intent.action.VIEW_PERMISSION_USAGE", "android.intent.category.HEALTH_PERMISSIONS", "START_VIEW_PERMISSION_USAGE", ".health.HealthRationaleActivity")):
    bad("P4 no VIEW_PERMISSION_USAGE / HEALTH_PERMISSIONS alias guarded by START_VIEW_PERMISSION_USAGE")
q = man.split("<queries>", 1)[-1].split("</queries>", 1)[0]
if "com.google.android.apps.healthdata" not in q or "ACTION_SHOW_PERMISSIONS_RATIONALE" not in q: bad("P4 Health Connect is not in <queries>")
if "libs:perms" not in d["modules"] or "libs:perms" not in d["modules"]["app"]["depends_on"]: bad("P5 libs:perms is not an app module")
g = open(app + "/app/build.gradle").read()
if "UI_PERMISSIONS_B64" not in g or "project(':libs:perms')" not in g: bad("P5 the declaration is not baked / libs:perms not compiled")
k = open(src + "/MePerms.kt").read()
if "BuildConfig.UI_PERMISSIONS_B64" not in k: bad("P5 MePerms does not read the baked declaration")
if re.search(r'"android\.(permission|settings)\.', k): bad("P5 MePerms.kt names a permission or settings action")
st = k.split("fun startup(", 1)[1].split("\n    fun ", 1)[0]
if "PermState.PERMANENTLY_DENIED" in st or "it.missing" in st: bad("P6 startup asks a permanently denied permission again")
if "it.kind == RowKind.RUNTIME && it.state == PermState.DENIED" not in st: bad("P6 startup does not ask the denied runtime permissions")
if "mem.healthAsks < HEALTH_MAX_ASKS" not in st or "HEALTH_MAX_ASKS = 2" not in k: bad("P6 Health Connect is asked without the two-ask limit")
if "it.id !in mem.specialOpened" not in st: bad("P6 special pages are reopened on every start")
ga = k.split("fun grantAll(", 1)[1].split("\n    fun ", 1)[0]
if "RowKind.SPECIAL && it.missing" not in ga or "healthStep(rows)" not in ga: bad("P6 Grant all skips special or Health Connect rows")
m = open(src + "/MainActivity.kt").read()
if "MePermPlan.startup(" not in m or "MePermPlan.grantAll(" not in m or "MePerms.noteSpecialOpened" not in m: bad("P6 MainActivity does not run the start-up walk / Grant all")
c = json.load(open(app + "/data/ui/configs/perms.json"))
if not any(x.get("kind") == "permissions" for x in c): bad("P6 Configs has no Perms card")
icons = set(re.findall(r'^\s*"([a-z0-9_]+)" to ', open(root + "/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt").read(), re.M))
for e in p["entries"] + p["install"] + [dict(p["health"], id="health")]:
    n = e.get("id") or e.get("perm")
    if e.get("icon") not in icons: bad("P7 %s has no libs:ui-icons icon (%s)" % (n, e.get("icon")))
    if len(e.get("why", "x")) > 60: bad("P7 %s: the reason is not one line (%d chars > 60)" % (n, len(e["why"])))
if not any(e.get("used") is False for e in p["entries"]): bad("P7 no permission is flagged used: false (Not used yet is empty)")
for x in c:
    for f in ("title", "subtitle"):
        if re.search(r"::|build\.json|\bui\.[a-z_]+", x.get(f, "")): bad("P7 Configs shows a raw id in %s: %s" % (f, x[f]))
# #1372 the page and banner are libs:perms' shared UI (PermsList, PermsPromptCard over PermsView rules); Cloud Me feeds it
lib = root + "/ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms"
ui = open(lib + "/PermsUi.kt").read(); view = open(lib + "/PermsView.kt").read()
for need, why in (("PermsList(", "the page is the shared PermsList"), ("PermsPromptCard(", "the banner is the shared prompt card"),
                  ("MePermView.items(rows)", "the rows are fed as shared items"), ("UiIcons.vector(", "rows draw the fleet icon")):
    if need not in k: bad("P7 " + why + " (" + need + " missing in MePerms.kt)")
for need, why in (("ChipView(chip, p", "rows carry the state chip"), ("HorizontalDivider(", "rows are divided"),
                  ("onGrantAll != null) Button(", "Grant all is a primary button"), ("prog.label", "Grant all shows the progress count"),
                  ("testTag(PermsTags.DISMISS)", "the banner can be dismissed"), ("maxLines = 1, overflow = TextOverflow.Ellipsis", "row text never wraps into the chip")):
    if need not in ui: bad("P7 " + why + " (" + need + " missing in PermsUi.kt)")
if "Group(NOT_USED, items.filter { !it.used }, collapsed = true)" not in view: bad("P7 unused permissions are not collapsed under Not used yet")
if re.search(r"\bfontSize = \d+\.sp|colorResource\(", k): bad("P7 MePerms.kt hardcodes a size or colour instead of the libs:ui-kit palette")
print("\n".join(out)); sys.exit(1 if out else 0)
PY
echo "[ac_cloud-me] Configs > Perms declaration"
r="$(python3 "$CHECK" "$ROOT" 2>&1)" && ok "declaration, manifest, Health Connect set and start-up rule agree" || bad "$r"
FILES="ac_cloud-me/build.json ac_cloud-me/app/src/main/AndroidManifest.xml ac_cloud-me/app/build.gradle ac_cloud-me/data/ui/configs/perms.json ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/MePerms.kt ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json ab_cloud-libs-shared/libs/health/src/main/AndroidManifest.xml ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/PermsUi.kt ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/PermsView.kt"
plant() { # <name> <python acting on T>
  rm -rf "$S/t"; for f in $FILES; do mkdir -p "$S/t/$(dirname "$f")"; cp "$ROOT/$f" "$S/t/$f"; done
  python3 - "$S/t" <<PY
import re, sys
T = sys.argv[1]; A = T + "/ac_cloud-me"; K = A + "/app/src/main/java/com/diegonmarcos/cloudme"; L = T + "/ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms"
def sub(path, a, b):
    s = open(path).read(); assert a in s, "mutation anchor missing: " + a; open(path, "w").write(s.replace(a, b, 1))
$2
PY
  if python3 "$CHECK" "$S/t" >/dev/null 2>&1; then bad "mutation stayed green: $1"; else ok "red: $1"; fi
}
plant "a declared permission leaves the manifest"  'sub(A + "/app/src/main/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />", "")'
plant "the manifest gains an undeclared permission" 'sub(A + "/app/src/main/AndroidManifest.xml", "<queries>", "<uses-permission android:name=\"android.permission.CAMERA\" />\n    <queries>")'
plant "a Health Connect read is dropped"            'sub(A + "/build.json", "\"android.permission.health.READ_STEPS\",", "")'
plant "an entry is not a catalogue row"             'sub(A + "/build.json", "{\"id\": \"overlay\"", "{\"id\": \"overlay_x\"")'
plant "the rationale action is removed"             'sub(A + "/app/src/main/AndroidManifest.xml", "<action android:name=\"androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE\" />\n            </intent-filter>\n        </activity>", "</intent-filter>\n        </activity>")'
plant "the Android 14 alias loses its guard"        'sub(A + "/app/src/main/AndroidManifest.xml", "android:permission=\"android.permission.START_VIEW_PERMISSION_USAGE\"", "")'
plant "the Kotlin names a permission"               'sub(K + "/MePerms.kt", "object MePerms {", "object MePerms {\n    const val X = \"android.permission.CAMERA\"")'
plant "start-up re-asks a permanently denied one"   'sub(K + "/MePerms.kt", "it.kind == RowKind.RUNTIME && it.state == PermState.DENIED }.map { it.permission }.distinct()\n            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }\n        if (mem", "it.kind == RowKind.RUNTIME && it.missing }.map { it.permission }.distinct()\n            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }\n        if (mem")'
plant "Health Connect asked with no limit"          'sub(K + "/MePerms.kt", "if (mem.healthAsks < HEALTH_MAX_ASKS) ", "")'
plant "libs:perms is not a module"                  'sub(A + "/build.json", "        \"libs:perms\",\n", "")'
plant "a row loses its icon"                       'sub(A + "/build.json", "{\"id\": \"body_sensors\", \"icon\": \"monitor_heart\", ", "{\"id\": \"body_sensors\", ")'
plant "a row names an icon the fleet does not have" 'sub(A + "/build.json", "\"icon\": \"bolt\", \"why\": \"Keeps", "\"icon\": \"battery_x\", \"why\": \"Keeps")'
plant "a reason runs over one line"                'sub(A + "/build.json", "\"why\": \"Heart rate from a connected wearable.\"", "\"why\": \"Heart rate from a connected wearable, read through the sensors API while the app is open.\"")'
plant "nothing is flagged unused"                  'import re; s = open(A + "/build.json").read(); open(A + "/build.json", "w").write(s.replace(", \"used\": false", ""))'
plant "Configs shows a raw build.json id"          'sub(A + "/data/ui/configs/perms.json", "What Cloud Me can access", "build.json::ui.permissions: what Cloud Me can access")'
plant "unused rows are not collapsed"              'sub(L + "/PermsView.kt", "items.filter { !it.used }, collapsed = true)", "items.filter { !it.used })")'
plant "the banner loses its dismiss"               'sub(L + "/PermsUi.kt", "testTag(PermsTags.DISMISS)", "testTag(\"x\")")'
plant "the state chip is dropped"                  'sub(L + "/PermsUi.kt", "ChipView(chip, p", "Pill(chip.word, p.dim")'
plant "Grant all is plain text again"              'sub(L + "/PermsUi.kt", "onGrantAll != null) Button(", "onGrantAll != null) TextButton(")'
plant "Cloud Me draws its own list again"          'sub(K + "/MePerms.kt", "    PermsList(\n", "    MeList(\n")'
plant "a hardcoded font size"                      'sub(K + "/MePerms.kt", "button = \"Grant all\"", "button = \"Grant all\", fontSize = 13.sp")'
echo; [ "$FAILS" -eq 0 ] && { echo "OK me perms"; exit 0; } || { echo "FAILED $FAILS"; exit 1; }
