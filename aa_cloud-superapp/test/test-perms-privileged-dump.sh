#!/usr/bin/env bash
# android.permission.DUMP is what the ADB Shell page (dumpsys in-process, Shizuku stopped) and the
# fleet API's adb/grant-dump need. It was granted only by a tap on the Permissions page: the
# privileged plane's auto-grant pass (PrivilegedPlaneWorker step 3) grants ONLY the entries of
# build.json::ui.permissions.privileged, and DUMP was not one. Declared there, the pass grants it
# on every connect and the Permissions page lists it with the other privileged perms.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }

echo "== DUMP is a declared privileged perm"
apps=$(jq -r '.ui.permissions.privileged[] | select(.perm=="android.permission.DUMP") | .apps | join(",")' "$ROOT/build.json")
[ "$apps" = "com.diegonmarcos.superapp" ] && ok "ui.permissions.privileged declares DUMP for the SuperApp" || bad "DUMP is not declared for the SuperApp in ui.permissions.privileged (got '$apps')"
grep -q 'android:name="android.permission.DUMP"' "$ROOT/app/src/main/AndroidManifest.xml" && ok "the manifest requests DUMP (pm grant refuses an unrequested perm)" || bad "the manifest does not request DUMP"
grep -q 'UI_PERMISSIONS_PRIVILEGED_B64' "$ROOT/app/src/main/java/com/diegonmarcos/superapp/system/PrivilegedGrants.kt" && ok "the grant pass reads the declared list (PrivilegedGrants.curatedEntries)" || bad "PrivilegedGrants no longer reads ui.permissions.privileged"
# #1682 P3 step 4d: the boot worker (libs:adb-shell-core) hands the connected channel to the host's grant pass (PrivilegedPlaneHost)
grep -q 'ui.permissions.privileged' "$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/PrivilegedPlaneWorker.kt" && grep -q 'AdbOwnerHost.selfGrant(ctx)' "$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/PrivilegedPlaneWorker.kt" && grep -q 'PrivilegedGrants.resolve(ctx)' "$ROOT/app/src/owner/java/com/diegonmarcos/superapp/system/PrivilegedPlaneHost.kt" && ok "the plane worker grants every declared entry on connect" || bad "PrivilegedPlaneWorker no longer grants the declared list"
m=$(jq '.ui.permissions.privileged | map(select(.perm=="android.permission.DUMP")) | length' "$ROOT/build.json")
[ "$m" -eq 1 ] && ok "declared once" || bad "DUMP declared $m times"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
