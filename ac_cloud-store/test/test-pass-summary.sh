#!/usr/bin/env bash
# #894 One alert per fleet pass, and Cloud Store can actually show it.
#
# Held statically (no build, no device, no network):
#  1. PackageInstallerReceiver raises no success alert, files results of a pass
#     under PassLedger instead of one alert each, raises an outside-the-pass
#     failure only the first time (package + build + reason), and the tap target
#     is the host's (UpdaterHost.alertLink), not a hard-coded SuperApp page.
#  2. Fleet.autoPass opens and closes the pass ledger; the host chain (StoreAuto)
#     files every package's outcome; ConstellationWorker posts no alert of its own.
#  3. Cloud Store asks for POST_NOTIFICATIONS (13+ ships it denied: "the
#     notification carrying it is blocked") and points the summary at itself.
#
# Then each mutation below is planted in a scratch copy and must turn a check red.
# The pure logic itself is tested by PassLedgerTest (cloud-superapp :app:testDebugUnitTest).
#
# Usage: ./test-pass-summary.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
UPD="$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
APPST="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
STORE="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore"

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <updater-dir> <appstore-dir> <store-dir> -> PASS/FAIL lines, exit = #fails
check() {
  local U="$1" A="$2" S="$3" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local R F L SA W AP MA
  R="$(strip "$U/PackageInstallerReceiver.kt")"; F="$(strip "$U/Fleet.kt")"; L="$(strip "$U/PassLedger.kt")"
  SA="$(strip "$A/StoreAuto.kt")"; W="$(strip "$A/ConstellationWorker.kt")"
  AP="$(strip "$S/App.kt")"; MA="$(strip "$S/MainActivity.kt")"

  # 1. the receiver
  printf '%s' "$R" | grep -q 'quiet = inPass, raise = false' && ok "a success is filed in the ledger and never raised as an alert" || bad "a success can still raise its own alert"
  printf '%s' "$R" | grep -q 'deepLink = UpdaterHost.alertLink' && ok "the result alert's tap is the host's (UpdaterHost.alertLink)" || bad "the result alert's tap target is not the host's"
  printf '%s' "$R" | grep -q 'page:config/store-cloud' && bad "the receiver still hard-codes SuperApp's Store page" || ok "no hard-coded SuperApp page in the receiver"
  printf '%s' "$R" | grep -q 'quiet = inPass || (!isUninstall && !isNew(' && ok "failures: filed under the pass, else raised once per package+build+reason" || bad "a repeated identical failure is raised again"
  printf '%s' "$R" | grep -q 'PassLedgerStore.record(context, outcome, gateKey, build, reason)' && ok "results are filed per package and build" || bad "results are not filed in the pass ledger"
  printf '%s' "$R" | sed -n '/fun surface(/,/^    }/p' | grep -q 'if (quiet) return' && ok "surface() says nothing for a filed or repeated result" || bad "surface() ignores quiet"
  # 2. the pass
  printf '%s' "$F" | grep -q 'PassLedgerStore.begin(ctx)' && printf '%s' "$F" | grep -q 'PassLedgerStore.end(ctx)' && ok "Fleet.autoPass opens and closes the pass ledger" || bad "autoPass does not open/close the ledger"
  [ "$(printf '%s' "$SA" | grep -c 'PassLedgerStore.record(ctx')" -eq 3 ] && ok "the host chain files every package's outcome" || bad "StoreAuto.pass does not file outcomes (Cloud Store's pass would show no summary)"
  printf '%s' "$W" | grep -q 'FleetAlerts.raise\|alert(applicationContext' && bad "ConstellationWorker still raises alerts of its own" || ok "ConstellationWorker raises no alert of its own"
  printf '%s' "$L" | grep -q 'if (!s.running) flush(ctx)' && ok "the summary waits for the pass to end, then follows late results" || bad "the summary is raised mid-pass"
  printf '%s' "$L" | grep -q 'dedupeKey = KEY' && ok "one summary key: each update replaces the last" || bad "the summary has no dedupe key"
  # 3. Cloud Store
  printf '%s' "$(strip "$S/UpdateChecks.kt")" | grep -q 'Need.NOTIFICATIONS -> "notifications"' && ! printf '%s' "$MA" | grep -q 'requestPermissions(' && ok "Cloud Store's notifications are its Configs > Perms row (#1515), not an ask of its own" || bad "Cloud Store lost its notifications Perms row, or asks for POST_NOTIFICATIONS itself"
  printf '%s' "$AP" | grep -q 'alertLink = "intent:#Intent;action=com.diegonmarcos.cloudstore.OPEN;package=com.diegonmarcos.cloudstore;S.tab=cloud;end"' && ok "the summary opens Cloud Store on the fleet tab" || bad "Cloud Store's summary does not open Cloud Store"
  return $fails
}

echo "── real tree ──"
check "$UPD" "$APPST" "$STORE"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <upd|ast|store>/<file> <old> <new>
  local label="$1" which="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"; cp -r "$UPD" "$d/upd"; cp -r "$APPST" "$d/ast"; cp -r "$STORE" "$d/store"
  python3 - "$d/$which" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d/upd" "$d/ast" "$d/store" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
mutate "success raises an alert again" upd/PackageInstallerReceiver.kt 'quiet = inPass, raise = false' 'quiet = inPass, raise = true' || M=$((M+1))
mutate "tap goes back to SuperApp's page" upd/PackageInstallerReceiver.kt 'deepLink = UpdaterHost.alertLink' 'deepLink = "page:config/store-cloud"' || M=$((M+1))
mutate "a repeated failure is raised every pass" upd/PackageInstallerReceiver.kt 'quiet = inPass || (!isUninstall && !isNew(context, gateKey, build, label))' 'quiet = inPass' || M=$((M+1))
mutate "results not filed" upd/PackageInstallerReceiver.kt 'PassLedgerStore.record(context, outcome, gateKey, build, reason)' 'false' || M=$((M+1))
mutate "surface ignores quiet" upd/PackageInstallerReceiver.kt 'if (quiet) return' '' || M=$((M+1))
mutate "pass never opens its ledger" upd/Fleet.kt 'PassLedgerStore.begin(ctx)' '' || M=$((M+1))
mutate "pass never closes its ledger" upd/Fleet.kt 'PassLedgerStore.end(ctx)' '' || M=$((M+1))
mutate "host chain stops filing outcomes" ast/StoreAuto.kt 'INSTALLED -> com.diegonmarcos.superapp.updater.PassLedgerStore.record(ctx,' 'INSTALLED -> com.diegonmarcos.superapp.updater.PassLedgerStore.recordx(ctx,' || M=$((M+1))
mutate "worker raises its own alert again" ast/ConstellationWorker.kt 'FleetAlerts.withdraw(applicationContext, KEY_INSTALLED)' 'FleetAlerts.raise(applicationContext, FleetAlerts.Alert("x"))' || M=$((M+1))
mutate "summary raised mid-pass" upd/PassLedger.kt 'if (!s.running) flush(ctx)' 'flush(ctx)' || M=$((M+1))
mutate "summary loses its key" upd/PassLedger.kt 'dedupeKey = KEY' 'dedupeKey = ""' || M=$((M+1))
mutate "Cloud Store loses its notifications Perms row" store/UpdateChecks.kt 'Need.NOTIFICATIONS -> "notifications"' 'Need.NOTIFICATIONS -> "notificationz"' || M=$((M+1))
mutate "summary opens SuperApp" store/App.kt 'package=com.diegonmarcos.cloudstore;S.tab=cloud' 'package=com.diegonmarcos.superapp;S.tab=cloud' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
