#!/usr/bin/env bash
# Cloud Code Perms: every grant a code editor needs is DECLARED, listed in Configs > Perms (build.json::perms.needs)
# and ROUTED by the libs:perms catalogue (settings intent + live-state check), and the built APK's merged manifest is
# asserted by the engine (build.json::release.artifact_assert.required_permissions). Static, no build.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
CAT="$ROOT/ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json"
PERMS_KT="$ROOT/ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt"
F=0; ok() { echo "  PASS: $1"; }; bad() { echo "  FAIL: $1"; F=$((F+1)); }
P=android.permission
for n in PACKAGE_USAGE_STATS MANAGE_EXTERNAL_STORAGE READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_EXTERNAL_STORAGE; do
  grep -q "<uses-permission android:name=\"$P.$n\"" "$APP/config.xml" && ok "config.xml declares $n" || bad "config.xml does not declare $n"
  [ "$n" = READ_EXTERNAL_STORAGE ] && continue
  jq -e --arg p "$P.$n" '.release.artifact_assert.required_permissions | index($p)' "$APP/build.json" >/dev/null \
    && ok "merged-manifest assertion lists $n" || bad "required_permissions lacks $n"
done
grep -q 'READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"' "$APP/config.xml" && ok "READ_EXTERNAL_STORAGE capped at 32" || bad "READ_EXTERNAL_STORAGE not capped"
for id in all_files usage_access storage_read media_images media_video media_audio notifications microphone; do
  jq -e --arg i "$id" '.perms.needs | index($i)' "$APP/build.json" >/dev/null && ok "Perms lists $id" || bad "build.json::perms.needs lacks $id"
done
jq -e '.perms.manifest=="config.xml" and .perms.first_run=="libs:perms"' "$APP/build.json" >/dev/null && ok "perms block reads config.xml, first run via libs:perms" || bad "perms block malformed"
jq -e '.entries[]|select(.id=="usage_access")|.settings.action=="android.settings.USAGE_ACCESS_SETTINGS" and .settings.package_uri==true and .check=="appop" and .appop=="android:get_usage_stats"' "$CAT" >/dev/null \
  && ok "usage_access routes to USAGE_ACCESS_SETTINGS, state via AppOps get_usage_stats" || bad "usage_access routing changed"
jq -e '.entries[]|select(.id=="all_files")|.settings.action=="android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" and (.settings.fallbacks|index("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")) and .check=="all_files"' "$CAT" >/dev/null \
  && ok "all_files routes to MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (+ fallback)" || bad "all_files routing changed"
grep -q 'isExternalStorageManager' "$PERMS_KT" && grep -q 'AppOpsManager' "$PERMS_KT" && ok "Perms.kt reads all-files and app-op state" || bad "Perms.kt state detection missing"
grep -q '_assert_apk_permissions "\$apk"' "$APP/build.sh" && ok "engine asserts the merged manifest" || bad "engine does not assert the merged manifest"
python3 "$ROOT/1_cicd/src/scripts/cloud-android-perms-guard.py" "$ROOT" >/dev/null && ok "fleet perms guard green" || bad "fleet perms guard red"
[ $F -eq 0 ] && echo "PASS" || { echo "FAIL ($F)"; exit 1; }
