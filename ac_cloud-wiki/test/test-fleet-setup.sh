#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-fleet-setup — this app answers the fleet setup contract      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #873 THE SHARED TEMPLATE: 1_cicd/src/templates/test-fleet-setup.sh, copied verbatim to
# <app>/test/test-fleet-setup.sh (it derives the app from its own location, so the copies are
# identical). Static, no build, no network. It certifies, for THIS app:
#   1. the app links the setup provider: build.json modules carry libs:core and
#      libs:fleetconfig-model, a hand-written settings file includes both, libs:core still
#      `api`s the module, and the provider is exported behind CONSTELLATION_DATA with the
#      authority ${applicationId}.fleetsetup and re-checks its caller;
#   2. every migrating store fleet-config.json declares for it has a handler (the default for
#      prefs / encrypted, a registered one, or an `unserved` line with its reason);
# then plants each mutation below in a scratch copy and requires RED, so a check that cannot
# fail does not count as a check:
#   M1 the app drops libs:fleetconfig-model from its modules      M2 the provider is not exported
#   M3 the provider is not behind the permission
# The lib's own round trip (describe -> apply -> export) is SetupProviderTest in
# libs:fleetconfig-model, run by `:libs:fleetconfig-model:testDebugUnitTest`.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
MODULE="$(basename "$APP")"
ROOT="$(cd "$APP/.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-fleet-setup-guard.py"
POLICY="$ROOT/1_cicd/src/data/fleet-setup-guard.json"
[ -f "$GUARD" ] && [ -f "$POLICY" ] || { echo "ERROR: the fleet setup guard is not in this checkout ($GUARD) - zero checks ran"; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[$MODULE] fleet setup contract"
out="$(python3 "$GUARD" --root "$ROOT" --app "$MODULE" 2>&1)"
if [ $? -eq 0 ]; then ok "links the provider and every declared migrating store has a handler"; else bad "the guard is red for $MODULE: $out"; fi

# mutations on a scratch copy of just what the guard reads for this app
scratch() {
  local T="$1" M=ab_cloud-libs-shared/libs
  rm -rf "$T"; mkdir -p "$T/$M/core" "$T/$M/fleetconfig-model/src/main/assets" "$T/$M/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig" "$T/$MODULE" "$T/1_cicd/src/data"
  cp "$ROOT/$M/core/build.gradle" "$T/$M/core/"
  cp "$ROOT/$M/fleetconfig-model/src/main/AndroidManifest.xml" "$T/$M/fleetconfig-model/src/main/"
  cp "$ROOT/$M/fleetconfig-model/src/main/assets/fleet-config.json" "$T/$M/fleetconfig-model/src/main/assets/"
  cp "$ROOT/$M/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig/SetupProvider.kt" "$T/$M/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig/"
  cp "$APP/build.json" "$T/$MODULE/"
  for s in settings.gradle settings.gradle.kts; do [ -f "$APP/$s" ] && cp "$APP/$s" "$T/$MODULE/"; done
  cp "$POLICY" "$T/1_cicd/src/data/"
}
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
plant() {   # plant <name> <expected-text> <python statement acting on the scratch root T>
  scratch "$S/t"
  python3 - "$S/t" "$MODULE" <<PY
import json, re, sys
T, MOD = sys.argv[1], sys.argv[2]
M = T + "/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/AndroidManifest.xml"
$3
PY
  local o; o="$(python3 "$GUARD" --root "$S/t" --policy "$S/t/1_cicd/src/data/fleet-setup-guard.json" --app "$MODULE" 2>&1)"
  if [ $? -ne 0 ] && printf '%s' "$o" | grep -q "$2"; then ok "$1 turns it red"; else bad "$1 stays green or is red for another reason ($o)"; fi
}
if python3 -c "import json,sys; m=json.load(open(sys.argv[1]+'/build.json')).get('modules',{}); sys.exit(0 if 'libs:core' in m else 1)" "$APP"; then
  plant "M1 the app drops libs:fleetconfig-model" "S1 $MODULE" '
import glob
p = T + "/" + MOD + "/build.json"; d = json.load(open(p)); d["modules"].pop("libs:fleetconfig-model", None); json.dump(d, open(p, "w"))
for sf in glob.glob(T + "/" + MOD + "/settings.gradle*"):
    open(sf, "w").write("".join(l for l in open(sf) if "libs:fleetconfig-model" not in l))'
else
  echo "  (this app wires libs:core by hand: M1 is exercised by the guard's own tester)"
fi
plant "M2 the provider is not exported" "S0 " 's = open(M).read().replace("android:exported=\"true\"", "android:exported=\"false\""); open(M, "w").write(s)'
plant "M3 the provider is not behind the permission" "S0 " 's = re.sub(r"android:permission=\"[^\"]*\"", "", open(M).read()); open(M, "w").write(s)'

echo
if [ "$FAILS" -eq 0 ]; then echo "test-fleet-setup ($MODULE): all checks passed"; else echo "test-fleet-setup ($MODULE): $FAILS FAILED"; exit 1; fi
