#!/usr/bin/env bash
# Mount lifetime: mounts are owned by a sticky foreground service, never by the UI.
#   K1 the browser screen re-attaches through MountSessions and never closes the connection on dispose
#   K2 the keeper is a specialUse foreground service, not exported, stopWithTask=false, START_STICKY
#   K3 boot / update restore: receiver on BOOT_COMPLETED + MY_PACKAGE_REPLACED, gated by the declaration
#   K4 permissions declared: FGS + FGS_SPECIAL_USE, battery exemption, boot, wake lock, notifications
#   K5 declarative: build.json::mount_keeper exists, is baked, and no Activity stops the keeper
#   K6 the engine is held by a persistent bind while the keeper runs (MountLink.hold)
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:mounts"]["dir"])))' "$APP/build.json" "$APP")"
LIB="$MOD/src/main/java/com/diegonmarcos/cloudlib/mounts"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAN="$APP/app/src/main/AndroidManifest.xml"
SVC="$SRC/mounts/MountKeeperService.kt"; RCV="$SRC/mounts/MountBootReceiver.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
chk() { local what="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$what"; else fail "$what"; fi; }

echo "── K1 UI re-attaches, never owns the connection ──"
chk "browser acquires through MountSessions" grep -q 'MountSessions.get' "$LIB/MountsScreen.kt"
if grep -nE 'onDispose \{.*Thread|MountFsFactory\.open\(context, mount' "$LIB/MountsScreen.kt"; then fail "browser still opens/closes its own connection"; else pass "browser does not open or close the connection itself"; fi
chk "lease close() only releases" grep -q 'release only' "$LIB/MountSessions.kt"

echo "── K2 the keeper service ──"
chk "service declared" grep -q 'android:name=".mounts.MountKeeperService"' "$MAN"
chk "foregroundServiceType specialUse" grep -q 'android:foregroundServiceType="specialUse"' "$MAN"
chk "special-use subtype property" grep -q 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$MAN"
chk "stopWithTask=false" grep -q 'android:stopWithTask="false"' "$MAN"
chk "START_STICKY" grep -q 'return START_STICKY' "$SVC"
chk "notification has a Stop action" grep -q 'addAction(0, getString(R.string.mount_keeper_stop)' "$SVC"
chk "wake lock only while transfers run" grep -q 'transfers.collectLatest' "$SVC"

echo "── K3 restore on boot / update ──"
for a in BOOT_COMPLETED MY_PACKAGE_REPLACED; do chk "receiver handles $a" grep -q "android.intent.action.$a" "$MAN"; done
chk "boot restore gated by restore_on_boot" grep -q 'p.restoreOnBoot' "$RCV"
chk "update restore gated by restore_on_update" grep -q 'p.restoreOnUpdate' "$RCV"

echo "── K4 permissions ──"
for p in FOREGROUND_SERVICE FOREGROUND_SERVICE_SPECIAL_USE RECEIVE_BOOT_COMPLETED REQUEST_IGNORE_BATTERY_OPTIMIZATIONS WAKE_LOCK POST_NOTIFICATIONS; do
  chk "uses-permission $p" grep -q "android.permission.$p\"" "$MAN"
done
chk "battery exemption is the Perms battery_opt row (granted in Configs > Perms, #1515)" grep -q 'listOf("battery_opt")' "$SRC/mounts/MountPermissions.kt"
chk "the mount card asks nothing itself" bash -c "! grep -q 'ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS\|RequestPermission' '$SRC/mounts/MountKeeperCard.kt' '$SRC/mounts/MountPermissions.kt'"
chk "permission card on Sync > Mounts" grep -q 'MountKeeperCard()' "$SRC/sync/RcloneMountsScreens.kt"

echo "── K5 declarative ──"
chk "build.json::mount_keeper" python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["mount_keeper"]; assert d["enabled"] and d["backoff_max_seconds"]>=d["backoff_initial_seconds"]>0' "$APP/build.json"
chk "baked as MOUNT_KEEPER_B64" grep -q 'MOUNT_KEEPER_B64' "$APP/app/build.gradle"
if grep -rnE 'stopService\(|MountKeeper\.stop\(' "$SRC" --include=*Activity.kt; then fail "an Activity stops the keeper"; else pass "no Activity stops the keeper"; fi

echo "── K6 engine held while mounts are on ──"
chk "MountLink.hold() exists" grep -q 'fun hold()' "$LIB/MountLink.kt"
chk "keeper holds the engine" grep -q 'MountLink(applicationContext).hold()' "$SVC"

echo; [ "$FAILURES" -eq 0 ] && { echo "OK mount keeper"; exit 0; } || { echo "FAILED $FAILURES"; exit 1; }
