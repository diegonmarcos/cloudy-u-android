#!/usr/bin/env bash
# #894 Cloud Store has its own shell channel, and its progress bar holds one state per item.
#
# Held statically (no build, no device, no network):
#  1. #1512 build.json: no shizuku_client / shizuku_diagnostics / privileged_channel block, the broker client (libs:shell-client)
#     and fleetconfig modules on the app's dependency list, no shizuku module anywhere.
#  2. the app links the broker client and NOT the shizuku lib, declares no pairing / keep-alive service, no Shizuku provider and no
#     boot receiver (#1502), arms nothing from App.onCreate and draws the shell-broker page in Configs (its ADB Shell tab).
#  3. SuperApp stands down: no self-update schedule, no check, no Update-all of its own when Cloud
#     Store is installed.
#  4. the Store bar is written in ONE place, through ProgressBarModel (indeterminate only until
#     the first byte count, monotonic per item). The pure rules are ProgressBarModelTest's.
# Then each mutation is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-shell-channel.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }

check() {  # check <root> -> PASS/FAIL lines, exit = #fails
python3 - "$1" <<'PY'
import json, re, sys
R = sys.argv[1]
def rd(p):
    s = open(f"{R}/{p}", encoding="utf-8").read()
    s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$|(?<=[\s;{}])//[^\n]*', '', s)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad))
    fails += 0 if c else 1
bj = json.load(open(f"{R}/ac_cloud-store/build.json"))
mods = bj["modules"]
ok(not any(k in bj for k in ("shizuku_client", "shizuku_diagnostics", "privileged_channel")),
   "#1512 no channel block in build.json (the SuperApp's broker owns the channel)", "#1512 a shizuku_client / shizuku_diagnostics / privileged_channel block is back")
dep = mods["app"]["depends_on"]
ok("libs:shell-client" in dep and "libs:fleetconfig-model" in dep and "libs:shizuku-adb-debug-tools" not in json.dumps(mods),
   "the app module depends on the broker client and fleetconfig-model; no module is the shizuku lib",
   "the app module lacks libs:shell-client / fleetconfig-model, or a shizuku module is back")
gr = rd("ac_cloud-store/app/build.gradle")
ok("project(':libs:shell-client')" in gr and "shizuku-adb-debug-tools" not in gr, "app/build.gradle links the broker client and not the shizuku lib", "app/build.gradle does not link libs:shell-client / still links the shizuku lib")
mf = open(f"{R}/ac_cloud-store/app/src/main/AndroidManifest.xml", encoding="utf-8").read()
mfc = re.sub(r"<!--.*?-->", "", mf, flags=re.S)
ok("adbdebug" not in mfc and "ShizukuProvider" not in mfc and "moe.shizuku" not in mfc and "SPECIAL_USE" not in mfc,
   "#1512 no pairing / keep-alive service, no Shizuku provider or query, no special-use foreground service", "#1512 the manifest declares a channel service or provider again")
ok("RECEIVE_BOOT_COMPLETED" not in mf and "<receiver" not in mfc, "#1502 no boot receiver", "#1502 a boot receiver is back")
app = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt")
ok("HostShell" not in app and "CloudStoreShell" not in app and "adbdebug" not in app, "#1512 App.onCreate arms no channel of its own", "#1512 App.onCreate still names HostShell / CloudStoreShell / adbdebug")
cfg = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt")
ok("ShellBrokerPage.lines(status)" in cfg and "ShellClient.arm(ctx)" in cfg and "adbdebug" not in cfg, "Configs > ADB Shell reports the SuperApp's broker and opens its arm flow", "Configs does not offer the broker page")
ok(not __import__("os").path.exists(f"{R}/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell"), "#1512 the Store's shell package (HostShell glue, ShellChannelWorker) is gone", "#1512 the Store's shell package is back")
up = rd("ab_cloud-libs-shared/libs/updater/src/broker/java/com/diegonmarcos/superapp/updater/ShellChannelAccess.kt")
ok("client.execStdin(" in up and "ShellClient.status(ctx).brokerInstalled" in up, "the updater's broker variant streams the APK over the stdin door", "the updater's broker variant does not use the stdin door")
ug = rd("ab_cloud-libs-shared/libs/updater/build.gradle")
ok("src/broker/java" in ug and "findProject(':libs:shell-client')" in ug, "libs:updater picks its broker source set when libs:shell-client is present", "libs:updater has no broker source set")
fl = rd("ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt")
ok("listOf(ShellInstall, SessionInstall)" in fl and "listOf(ShellInstall)" in fl,
   "Fleet.commit tries the shell install first and the session second", "Fleet.commit's ladder is not shell-first")
ok("if (SilentReadiness.prefersSession(verdict)) listOf(SessionInstall, ShellInstall) else channels(ctx)" in fl,
   "#1675 a package the native silent path covers goes through the public PackageInstaller session first, else the shell-first ladder",
   "#1675 Fleet.commit no longer puts the native silent session first where it applies")
hand = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt")
ok("fun ownsInstalls(" in hand, "SuperApp has one question: does Cloud Store own installs", "CloudStoreHandoff.ownsInstalls is gone")
sa = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt")
ok(sa.count("CloudStoreHandoff.ownsInstalls(applicationContext)") >= 1 and "Fleet.installAll" not in sa and "Updater.checkNow" not in sa,
   "SuperApp's schedule stands down; its check and Update-all only open Cloud Store", "a SuperApp install path ignores Cloud Store")
ok("else Updater.start(applicationContext)" in sa, "SuperApp schedules no self-update with Cloud Store present", "SuperApp still schedules its own self-update")
pf = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt")
ok("if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(ctxAny()))" in pf, "the Auto-update toggle does not restart SuperApp's own updater", "the Auto-update toggle restarts SuperApp's updater")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-store/build.json ac_cloud-store/app/build.gradle ac_cloud-store/app/src/main/AndroidManifest.xml
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt
ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt
ab_cloud-libs-shared/libs/updater/src/broker/java/com/diegonmarcos/superapp/updater/ShellChannelAccess.kt
ab_cloud-libs-shared/libs/updater/build.gradle
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt"
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
S=ac_cloud-store; K=ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore
A=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
P=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore
M=0
U=ab_cloud-libs-shared/libs/updater
mutate "a channel block returns" $S/build.json '"name": "cloud-store",' '"name": "cloud-store", "shizuku_client": {},' || M=$((M+1))
mutate "broker client dropped from the app module" $S/build.json '"libs:shell-client",
        "libs:fleetconfig-model"' '"libs:fleetconfig-model"' || M=$((M+1))
mutate "app no longer links the broker client" $S/app/build.gradle "implementation project(':libs:shell-client')" '' || M=$((M+1))
mutate "the shizuku lib is linked again" $S/app/build.gradle "implementation project(':libs:shell-client')" "implementation project(':libs:shizuku-adb-debug-tools')" || M=$((M+1))
mutate "a pairing service returns" $S/app/src/main/AndroidManifest.xml '<application' '<application><service android:name="com.diegonmarcos.superapp.adbdebug.AdbPairingService"/>' || M=$((M+1))
mutate "the Shizuku provider returns" $S/app/src/main/AndroidManifest.xml '<application' '<application><provider android:name="rikka.shizuku.ShizukuProvider" android:authorities="x"/>' || M=$((M+1))
mutate "a boot receiver returns" $S/app/src/main/AndroidManifest.xml '<application' '<application><receiver android:name="x"/>' || M=$((M+1))
mutate "HostShell returns in App" $K/App.kt 'StoreDns.start(this)' 'StoreDns.start(this); HostShell.install(this)' || M=$((M+1))
mutate "no broker page in Configs" $K/ConfigsPage.kt 'ShellBrokerPage.lines(status)' 'emptyList<String>()' || M=$((M+1))
mutate "the updater stops using the stdin door" $U/src/broker/java/com/diegonmarcos/superapp/updater/ShellChannelAccess.kt 'client.execStdin(' 'client.exec(' || M=$((M+1))
mutate "the updater has no broker source set" $U/build.gradle "'src/broker/java'" "'src/noshell/java'" || M=$((M+1))
mutate "session install first" ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt 'listOf(ShellInstall, SessionInstall)' 'listOf(SessionInstall, ShellInstall)' || M=$((M+1))
mutate "SuperApp schedules its own self-update" $A/ShellActivity.kt 'else Updater.start(applicationContext)' 'Updater.start(applicationContext)' || M=$((M+1))
mutate "SuperApp's Update-all installs again" $A/ShellActivity.kt "// #894 Installs are Cloud Store's: this opens it (or the Install Cloud Store button).
                routeTarget(\"page:config/store-cloud\")" "routeTarget(\"page:config/store-cloud\"); kotlin.concurrent.thread { com.diegonmarcos.superapp.updater.Fleet.installAll(applicationContext, emptyList(), com.diegonmarcos.superapp.updater.Fleet.Mode.UPDATES) }" || M=$((M+1))
mutate "toggle restarts SuperApp's updater" $A/configs/PermissionsFragment.kt 'if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(ctxAny()))' '' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
