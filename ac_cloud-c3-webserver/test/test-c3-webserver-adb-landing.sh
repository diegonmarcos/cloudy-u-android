#!/usr/bin/env bash
# #1682 every client's "open the ADB Shell page" (Store chip, Account, SuperApp) lands on the webserver's ADB Shell SECTION, not its Home.
# Static: the intent carries the page target, the webserver routes it in onCreate (cold) and onNewIntent (warm, singleTask) and Shell consumes it.
#   L1  ShellClient.openArmPage sends the declared explicit ARM activity of the owner package (BROKER_PKG)
#   L2  ShellArmActivity starts the owner's MainActivity with the page extra, NEW_TASK|SINGLE_TOP|CLEAR_TOP
#   L3  MainActivity routes the intent in onCreate BEFORE setContent, and in onNewIntent after setIntent; launchMode singleTask
#   L4  LaunchTarget maps that extra to the section id build.json::ui declares for the ADB Shell, and WebserverShell opens it
# Then each break is planted in a scratch copy and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
check() {
python3 - "$1" <<'PY'
import json, re, sys
R = sys.argv[1]; fails = 0
def ok(c, g, b):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (g if c else b)); fails += 0 if c else 1
def rd(p): return re.sub(r"//[^\n]*|/\*.*?\*/", "", open(f"{R}/{p}", encoding="utf-8").read(), flags=re.S)
A = "ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main"
wire = rd("ab_cloud-libs-shared/libs/shell-client/src/main/java/com/diegonmarcos/superapp/shellclient/ShellWire.kt")
cl = rd("ab_cloud-libs-shared/libs/shell-client/src/main/java/com/diegonmarcos/superapp/shellclient/ShellClient.kt")
arm = rd("ab_cloud-libs-shared/libs/shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellArmActivity.kt")
man = open(f"{R}/ab_cloud-libs-shared/libs/shell-broker/src/main/AndroidManifest.xml").read()
ma = rd(f"{A}/java/com/diegonmarcos/cloudwebserver/MainActivity.kt")
sh = rd(f"{A}/java/com/diegonmarcos/cloudwebserver/ui/Shell.kt")
decl = rd(f"{A}/java/com/diegonmarcos/cloudwebserver/Decl.kt")
wm = open(f"{R}/{A}/AndroidManifest.xml").read()
bj = json.load(open(f"{R}/ac_cloud-c3-webserver/build.json"))
act = re.search(r'ARM_ACTIVITY\s*=\s*"([^"]+)"', wire)
ok(bool(act) and "setClassName(ShellWire.BROKER_PKG, ShellWire.ARM_ACTIVITY)" in cl and "openArmPage" in cl and f'android:name="{act.group(1)}"' in man and 'android:exported="true"' in man,
   "L1 openArmPage sends the exported ARM activity of the owner package", "L1 openArmPage does not target the owner's exported ARM activity")
ok('putExtra("shortcut_action", "page:config/adb-shell")' in arm and '"$packageName.MainActivity"' in arm and all(f in arm for f in ("FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_SINGLE_TOP", "FLAG_ACTIVITY_CLEAR_TOP")),
   "L2 ShellArmActivity starts MainActivity with the page extra", "L2 ShellArmActivity does not carry the ADB page target to MainActivity")
oc = re.search(r"override fun onCreate\(.*?\n    \}", ma, re.S); on = re.search(r"override fun onNewIntent\(.*?\n    \}", ma, re.S)
oc = oc.group(0) if oc else ""; on = on.group(0) if on else ""
ok("LaunchTarget.from(intent)" in oc and oc.index("LaunchTarget.from(intent)") < oc.index("setContent"), "L3 onCreate routes the intent before setContent (cold start)", "L3 onCreate does not route the launch intent before setContent")
ok("setIntent(intent)" in on and "LaunchTarget.from(intent)" in on, "L3 onNewIntent routes the new intent (warm start)", "L3 onNewIntent does not route the new intent")
ok('android:launchMode="singleTask"' in wm.split('android:name=".MainActivity"')[0][-600:], "L3 MainActivity is singleTask (a warm start reaches onNewIntent)", "L3 MainActivity is not singleTask")
sec = re.search(r'SECTION_ADB_SHELL\s*=\s*"([^"]+)"', decl)
ids = [s.get("id") for s in (bj.get("ui") or {}).get("sections", [])] if isinstance(bj.get("ui"), dict) else []
ok(bool(sec) and (not ids or sec.group(1) in ids) and 'getStringExtra("shortcut_action") == "page:config/adb-shell") section = Decl.SECTION_ADB_SHELL' in ma,
   "L4 LaunchTarget maps the extra to the declared ADB Shell section", "L4 LaunchTarget does not map the extra to the ADB Shell section")
ok("val wanted = LaunchTarget.section" in sh and "LaunchedEffect(wanted)" in sh and "open(wanted)" in sh and "LaunchTarget.section = null" in sh, "L4 WebserverShell opens the wanted section and clears it", "L4 WebserverShell does not open the section a launch asked for")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
A=ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main
mutate() {  # mutate <label> <file> <old> <new>
  local d="$TMP/m"; rm -rf "$d"; mkdir -p "$d"; (cd "$ROOT" && git ls-files -z -- ab_cloud-libs-shared/libs/shell-client ab_cloud-libs-shared/libs/shell-broker ac_cloud-c3-webserver/build.json $A | xargs -0 -I{} sh -c 'mkdir -p "$0/$(dirname {})" && cp {} "$0/{}"' "$d") 2>/dev/null
  python3 - "$d/$2" "$3" "$4" <<'PY2' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys; p, o, n = sys.argv[1:4]; s = open(p, encoding="utf-8").read()
if o not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(o, n, 1))
PY2
  if check "$d" >/dev/null 2>&1; then echo "  MUTATION SURVIVED: $1"; return 1; fi; echo "  mutation caught: $1"
}
M=0
mutate "the arm activity drops the page extra" ab_cloud-libs-shared/libs/shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellArmActivity.kt '.putExtra("shortcut_action", "page:config/adb-shell")' '' || M=$((M+1))
mutate "onNewIntent stops routing" $A/java/com/diegonmarcos/cloudwebserver/MainActivity.kt '        setIntent(intent)
        LaunchTarget.from(intent)' '        setIntent(intent)' || M=$((M+1))
mutate "onCreate routes after setContent" $A/java/com/diegonmarcos/cloudwebserver/MainActivity.kt '        LaunchTarget.from(intent)
        runCatching { WebserverBenchmarks.register() }' '        runCatching { WebserverBenchmarks.register() }' || M=$((M+1))
mutate "MainActivity loses singleTask" $A/AndroidManifest.xml 'android:launchMode="singleTask"' 'android:launchMode="standard"' || M=$((M+1))
mutate "Shell stops opening the wanted section" $A/java/com/diegonmarcos/cloudwebserver/ui/Shell.kt 'open(wanted);' '' || M=$((M+1))
mutate "the client targets another class" ab_cloud-libs-shared/libs/shell-client/src/main/java/com/diegonmarcos/superapp/shellclient/ShellClient.kt 'ShellWire.ARM_ACTIVITY)' 'ShellWire.ARM_ACTIVITY + "X")' || M=$((M+1))
echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
