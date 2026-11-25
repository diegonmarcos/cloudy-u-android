#!/usr/bin/env bash
# #1566 bg-policy (fleet prefixes, essential packages) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no bg-policy.json under src/main/assets and the dataset is in the CI materialise map
#   S2 BgHost.config reads fleet/bg-policy through FleetData and returns null while not served
#   S3 every caller handles null (nothing is restricted without the essential list): API get/set, Battery Hunger, reapply, Super Saving
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local a="$1" B="$1/app/src/main/java/com/diegonmarcos/superapp/bgpolicy"
  [ -e "$a/app/src/main/assets/bg-policy.json" ] && echo "S1 bg-policy.json is an APK asset again"
  grep -q '"bg-policy"' "$ROOT/1_cicd/src/data/fleet-private-paths.json" || echo "S1 bg-policy is not in the CI materialise map"
  grep -q 'DATASET = "fleet/bg-policy"' "$B/BgHost.kt" || echo "S2 BgHost does not name fleet/bg-policy"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$B/BgHost.kt" || echo "S2 BgHost does not read the dataset through FleetData"
  grep -q 'fun config(ctx: Context): BgConfig?' "$B/BgHost.kt" || echo "S2 config is not nullable (not served state)"
  [ "$(grep -c 'NOT_SERVED' "$B/BgPolicyApi.kt")" -ge 2 ] || echo "S3 BgPolicyApi get/set do not answer not served"
  grep -q 'BgOutcome.NOT_SERVED' "$B/BatteryHunger.kt" || echo "S3 Battery Hunger does not handle not served"
  grep -q 'BgOutcome.NOT_SERVED' "$B/BgReplacedReceiver.kt" || echo "S3 reapply does not handle not served"
  grep -q 'BgHost.config(app) ?: return null' "$B/SuperSavingRuntime.kt" || echo "S3 Super Saving does not handle not served"
  return 0; }
FAIL=0
out="$(check "$APP")"; if [ -z "$out" ]; then echo "  PASS: bg-policy is a served private dataset"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"; cp -r "$APP/app" "$T/m/app"; ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
mutant "the asset creeps back" 'mkdir -p app/src/main/assets; echo "{}" > app/src/main/assets/bg-policy.json' "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/bg-policy#x#' app/src/main/java/com/diegonmarcos/superapp/bgpolicy/BgHost.kt" "S2"
mutant "config stops being nullable" "sed -i 's#BgConfig?#BgConfig#' app/src/main/java/com/diegonmarcos/superapp/bgpolicy/BgHost.kt" "S2"
mutant "Super Saving ignores not served" "sed -i 's#BgHost.config(app) ?: return null#BgHost.config(app)!!#' app/src/main/java/com/diegonmarcos/superapp/bgpolicy/SuperSavingRuntime.kt" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
