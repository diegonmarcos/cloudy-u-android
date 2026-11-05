#!/usr/bin/env bash
# Tester for Constellation AppStore v2 fixes (this session's feedback batch).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
# updater is a shared module in ab_cloud-libs-shared, not vendored per-app.
UPD="$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
# the constellation UI moved out of app/configs into the appstore lib module.
# appstore is a shared module in ab_cloud-libs-shared, not vendored per-app.
CFG="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

echo "== T1: real runtime enable toggle gates the workers =="
has "$UPD/AutoUpdatePrefs.kt" "fun enabled(ctx: Context)" "AutoUpdatePrefs.enabled exists"
has "$UPD/UpdateWorker.kt" "AutoUpdatePrefs.enabled(applicationContext)" "self-update worker gates on enabled"
has "$UPD/Updater.kt" "AutoUpdatePrefs.enabled(context)" "Updater.start gates on enabled"
has "$CFG/ConstellationWorker.kt" "AutoUpdatePrefs.enabled" "fleet worker gates on enabled"

echo "== T2: fleet manifest carries the GH release URL per app =="
# The SuperApp's fleet id is its directory name (regen.sh strips only ac_cloud-/ac_c3-).
jq -e '.apps[] | select(.id=="aa_cloud-superapp") | .release_url and .asset' "$APP/data/constellation-fleet.json" >/dev/null 2>&1 \
  && ok "manifest has release_url + asset" || bad "manifest missing release_url/asset"
has "$UPD/Fleet.kt" "val releaseUrl: String" "Fleet.App carries releaseUrl"
has "$CFG/StoreCloudFragment.kt" "app.releaseUrl" "UI shows the release URL link"

echo "== T3: per-app CONCURRENT status (fixes Dialer no-status / Chat loop) =="
has "$CFG/StoreEngine.kt" "thread(name = \"fleet-check-" "each app checked on its own thread (#1517: StoreChecks.pass runs them in StoreEngine)"
grep -q 'StoreChecks.pass(list)' "$CFG/StoreCloudFragment.kt" && grep -q 'thread(name = "fleet-check' "$CFG/StoreEngine.kt" \
  && ok "checkAll spawns a thread per app (non-blocking)" || bad "checkAll not concurrent"
has "$CFG/ConstellationWorker.kt" "Result.success()" "fleet worker returns success (no retry loop)"

echo "== T4: Update All + Cancel =="
has "$UPD/Fleet.kt" "fun installAll(" "Fleet.installAll (Update All)"
# #565: the button lives in the Store's shared bar, its label in strings.xml.
has "$CFG/StoreBar.kt" "R.string.store_bar_update_all" "UI has Update-all button"
has "$UPD/UpdateProgress.kt" "object Cancelled" "UpdateProgress has Cancelled state"
has "$UPD/Updater.kt" "fun cancelNow(" "Updater.cancelNow exists"
has "$UPD/UpdateOverlayFragment.kt" "Updater.cancelNow" "overlay Cancel button wired"
grep -q '"action:update_all"' "$APP/build.json" && ok "Config Update tile → Update All" || bad "Config Update tile not repointed"

echo "== T5: update notification deep-links to Store ▸ Cloud Constellation (not Home) =="
# A library cannot name the host's Activity, so the worker now replays
# AppStoreHost.launchExtras; the host supplies the deep-link in App.kt.
# #563: the target is the store's fleet tab; test-store-identity.sh proves
# that tab id is the one build.json declares.
has "$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt" '"shortcut_action" to "page:config/store-cloud"' "host supplies shortcut_action deep-link"
# #777/#894: the worker posts no alert of its own; the pass raises ONE summary
# fleet alert (PassLedger) whose deep link is the host's (UpdaterHost.alertLink).
has "$UPD/PassLedger.kt" 'deepLink = UpdaterHost.alertLink' "the pass summary deep-links through the host's alertLink"
grep -q 'FleetAlerts.raise' "$CFG/ConstellationWorker.kt" && bad "the worker raises an alert of its own again" || ok "the worker raises no alert of its own"
grep -q '"open_action"' "$CFG/ConstellationWorker.kt" && bad "old open_action extra still present" || ok "old dead open_action extra removed"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
