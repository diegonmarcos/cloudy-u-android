#!/usr/bin/env bash
# #1566 fleet-config (the new-phone migration manifest: every store, its class, its keys, every app's declared stores) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no fleet-config.json under libs:fleetconfig-model assets and the dataset is in the CI materialise map
#   S2 FleetPolicy and FleetConfig read fleet/fleet-config through FleetData; not served = an EMPTY manifest (nothing migrates, nothing is exported), never a crash
#   S3 both libs link the fleet data client; the guards and the Configs generator read the materialised copy
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local r="$1" L="$1/ab_cloud-libs-shared/libs"
  local P="$L/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig/FleetPolicy.kt" C="$L/core/src/main/java/com/diegonmarcos/superapp/core/FleetConfig.kt"
  [ -e "$L/fleetconfig-model/src/main/assets/fleet-config.json" ] && echo "S1 fleet-config.json is an APK asset again"
  grep -q '"fleet-config"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 fleet-config is not in the CI materialise map"
  grep -q 'DATASET = "fleet/fleet-config"' "$P" || echo "S2 FleetPolicy does not name fleet/fleet-config"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$P" || echo "S2 FleetPolicy does not read the dataset through FleetData"
  grep -q 'fun manifest(ctx: Context): Manifest = manifestOrNull(ctx) ?: EMPTY' "$P" || echo "S2 not served is not the empty manifest"
  grep -q 'val EMPTY: Manifest by lazy { Manifest(JSONObject()) }' "$P" || echo "S2 the empty manifest declares something"
  grep -q 'DATASET = "fleet/fleet-config"' "$C" || echo "S2 FleetConfig does not name fleet/fleet-config"
  grep -q "fleet-data-client" "$L/fleetconfig-model/build.gradle" || echo "S3 fleetconfig-model lacks the fleet-data-client dependency"
  grep -q "fleet-data-client" "$L/core/build.gradle" || echo "S3 core lacks the fleet-data-client dependency"
  grep -q '"manifest": "fleet-private-data/fleet-config.json"' "$r/1_cicd/src/data/fleet-config-guard.json" || echo "S3 the fleet config guard does not read the materialised copy"
  grep -q "fleet-private-data/fleet-config.json" "$L/ui-nav-bars/fleet-configs.gradle" || echo "S3 the Configs generator does not read the materialised copy"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: fleet-config is a served private dataset, an empty manifest while not served"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"
  for f in 1_cicd/src/data/fleet-private-paths.json 1_cicd/src/data/fleet-config-guard.json ab_cloud-libs-shared/libs/ui-nav-bars/fleet-configs.gradle ab_cloud-libs-shared/libs/fleetconfig-model/build.gradle ab_cloud-libs-shared/libs/core/build.gradle ab_cloud-libs-shared/libs/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig/FleetPolicy.kt ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core/FleetConfig.kt; do
    mkdir -p "$T/m/$(dirname "$f")"; cp "$ROOT/$f" "$T/m/$f"; done
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
P=ab_cloud-libs-shared/libs/fleetconfig-model/src/main/java/com/diegonmarcos/superapp/fleetconfig/FleetPolicy.kt
mutant "the asset creeps back" 'mkdir -p ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets; echo "{}" > ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json' "S1"
mutant "the dataset read is dropped" "sed -i 's#FleetData.get(ctx.applicationContext, DATASET)#FleetData.get(ctx.applicationContext, \"x\")#' $P" "S2 FleetPolicy does not read"
mutant "not served throws again" "sed -i 's#manifestOrNull(ctx) ?: EMPTY#manifestOrNull(ctx)!!#' $P" "S2 not served is not"
mutant "the empty manifest declares a store" "sed -i 's#Manifest(JSONObject())#Manifest(JSONObject(\"{\\\\\"migrate\\\\\":[\\\\\"config\\\\\"]}\"))#' $P" "S2 the empty manifest declares"
mutant "core drops the client edge" "sed -i '/fleet-data-client/d' ab_cloud-libs-shared/libs/core/build.gradle" "S3 core lacks"
mutant "the guard reads a missing path" "sed -i 's#fleet-private-data/fleet-config.json#x.json#' 1_cicd/src/data/fleet-config-guard.json" "S3 the fleet config guard"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
