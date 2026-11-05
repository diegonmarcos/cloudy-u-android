#!/usr/bin/env bash
# #1078 Cloud Store's background update checks stay reliable through user-facing consent only.
# Held statically (no build, no device, no network):
#  U1 the periodic fleet check is still scheduled at launch (App.kt -> ConstellationWorker.start); #1502 no boot/update
#     receiver (WorkManager keeps its jobs; a job exists only with auto-update on over an unmetered network)
#  U2 UpdateChecks asks the system: notifications through the permission dialog, install source and battery through
#     their settings screens; and it NEVER grants or exempts: no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (manifest or code),
#     no pm grant, no secure-settings write, no shell channel in the three update-check files
#  U3 it is reachable: Configs > Notifications draws the card, MainActivity shows the one-time battery prompt
#  U4 the suite exists and runs through this app (UpdateChecksTest; storeUnitTests -> :app:testDebugUnitTest)
# Then each mutation is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-store-update-checks.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }

check() {  # check <root> -> PASS/FAIL lines, exit = #fails
python3 - "$1" <<'PY'
import re, sys
R = sys.argv[1]
K = "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/"
def rd(p, strip=True):
    s = open(f"{R}/{p}", encoding="utf-8").read()
    if strip:
        s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
        s = re.sub(r'(?m)(?<![:"])//[^\n]*', '', s)
    return s
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad))
    fails += 0 if c else 1
app = rd(K + "App.kt"); uc = rd(K + "UpdateChecks.kt"); card = rd(K + "UpdateChecksCard.kt")
mf = rd("ac_cloud-store/app/src/main/AndroidManifest.xml")
ok("ConstellationWorker.start(this)" in app, "U1 launch schedules the periodic fleet check", "U1 App.onCreate no longer starts ConstellationWorker")
ok("android.intent.action.BOOT_COMPLETED" not in mf and "MY_PACKAGE_REPLACED" not in mf and "<receiver" not in re.sub(r"<!--.*?-->", "", mf, flags=re.S),
   "U1 #1502 no boot or package-replaced receiver in the Store manifest", "U1 #1502 the Store manifest declares a receiver that can start it at boot")
ok("RECEIVE_BOOT_COMPLETED" not in mf, "U1 #1502 RECEIVE_BOOT_COMPLETED is not held", "U1 #1502 RECEIVE_BOOT_COMPLETED is back")
ok("ActivityResultContracts.RequestPermission()" in card and "android.Manifest.permission.POST_NOTIFICATIONS" in card,
   "U2 notifications are asked through the system permission dialog", "U2 notifications are not asked through RequestPermission")
ok("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES" in uc and "Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS" in uc,
   "U2 install source and battery open their system settings screens", "U2 an update-check need no longer opens its system screen")
bad = [t for t in ("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "WRITE_SECURE_SETTINGS", "pm grant",
                   "HostShell", "PermsGrant", "setDeviceOwner", "DevicePolicyManager") if t in uc + card ]
ok(not bad, "U2 no privileged or silent self-grant in the update-check files", f"U2 privileged/silent grant found: {bad}")
ok("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in rd("ac_cloud-store/app/src/main/AndroidManifest.xml", False), "U2 the manifest does not request a battery exemption", "U2 the manifest requests REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
cfg = rd(K + "ConfigsPage.kt"); ma = rd(K + "MainActivity.kt")
ok("UpdateChecksCard()" in cfg.split("private fun NotificationsPage")[1].split("// ──")[0], "U3 Configs > Notifications draws the card", "U3 the Notifications page does not draw UpdateChecksCard")
ok("BatteryPrompt()" in ma, "U3 MainActivity shows the one-time battery prompt", "U3 MainActivity does not show BatteryPrompt")
ok(card.count("markBatteryPrompted") >= 3 and "shouldPromptBattery" in card, "U3 the prompt is asked once", "U3 the prompt is not once-only")
t = rd("ac_cloud-store/app/src/test/java/com/diegonmarcos/cloudstore/UpdateChecksTest.kt")
ok("fun `a reboot or an update starts nothing in Cloud Store`" in t and "fun `the card has a row per need" in t, "U4 UpdateChecksTest holds the no-receiver and card contracts", "U4 UpdateChecksTest lost a contract")
ok("':app:testDebugUnitTest'" in rd("ac_cloud-store/build.gradle", False), "U4 storeUnitTests runs the app's unit tests", "U4 storeUnitTests no longer runs :app:testDebugUnitTest")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
K=ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore
FILES="ac_cloud-store/build.gradle ac_cloud-store/app/src/main/AndroidManifest.xml $K/App.kt $K/MainActivity.kt $K/ConfigsPage.kt
$K/UpdateChecks.kt $K/UpdateChecksCard.kt
ac_cloud-store/app/src/test/java/com/diegonmarcos/cloudstore/UpdateChecksTest.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
mutate "fleet check not scheduled at launch" $K/App.kt 'ConstellationWorker.start(this)' 'Unit' || M=$((M+1))
mutate "boot receiver returns" ac_cloud-store/app/src/main/AndroidManifest.xml '<!-- #1502 no boot receiver' '<receiver android:name=".X"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver><!-- #1502 no boot receiver' || M=$((M+1))
mutate "battery exemption requested by the app" ac_cloud-store/app/src/main/AndroidManifest.xml '<uses-permission android:name="android.permission.INTERNET" />' '<uses-permission android:name="android.permission.INTERNET" /><uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />' || M=$((M+1))
mutate "silent self-grant added" $K/UpdateChecks.kt 'fun batteryIntent()' 'fun grantSilently(sh: HostShell) = sh.run("pm grant") ; fun batteryIntent()' || M=$((M+1))
mutate "battery screen replaced by a direct request" $K/UpdateChecks.kt 'Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS' 'Settings.ACTION_APPLICATION_DETAILS_SETTINGS' || M=$((M+1))
mutate "notifications not asked through the system dialog" $K/UpdateChecksCard.kt 'ActivityResultContracts.RequestPermission()' 'ActivityResultContracts.StartActivityForResult()' || M=$((M+1))
mutate "card not drawn" $K/ConfigsPage.kt 'UpdateChecksCard()   // #1078' 'Unit' || M=$((M+1))
mutate "prompt not shown" $K/MainActivity.kt 'BatteryPrompt()   // #1078' 'Unit' || M=$((M+1))
mutate "prompt repeats every launch" $K/UpdateChecksCard.kt 'UpdateChecks.markBatteryPrompted(ctx); show = false
            UpdateChecks.open' 'show = false
            UpdateChecks.open' || M=$((M+1))
mutate "unit suite dropped from storeUnitTests" ac_cloud-store/build.gradle "':app:testDebugUnitTest'" "':app:x'" || M=$((M+1))

echo "-- $M mutation(s) not caught, real tree fails: $REAL --"
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
