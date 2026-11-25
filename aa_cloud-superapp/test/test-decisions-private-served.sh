#!/usr/bin/env bash
# #1566 decisions (the engine declaration: every use, threshold, cap, consent default, relay providers) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no decisions.json under src/main/assets and the dataset is in the CI materialise map
#   S2 EngineHolder reads fleet/decisions through FleetData; not served = no engine (null, retried on the next call), never a default policy
#   S3 the engine lib links the fleet data client; the use guard and the rebuild scenario read the materialised copy / the reader
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local r="$1" L="$1/ab_cloud-libs-shared/libs/decisions-engine"
  local H="$L/src/main/java/com/diegonmarcos/superapp/decisions/engine/EngineHolder.kt"
  [ -e "$L/src/main/assets/decisions.json" ] && echo "S1 decisions.json is an APK asset again"
  grep -q '"decisions"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 decisions is not in the CI materialise map"
  grep -q 'DATASET = "fleet/decisions"' "$H" || echo "S2 EngineHolder does not name fleet/decisions"
  grep -q 'FleetData.get(ctx, DATASET)' "$H" || echo "S2 EngineHolder does not read the dataset through FleetData"
  grep -q 'is not served (no engine until it is)"); return null' "$H" || echo "S2 not served does not leave the engine absent"
  grep -q "fleet-data-client" "$L/build.gradle" || echo "S3 decisions-engine lacks the fleet-data-client dependency"
  grep -q '"declaration": "fleet-private-data/decisions.json"' "$r/1_cicd/src/data/decisions-use-guard.json" || echo "S3 the decisions-use guard does not read the materialised copy"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: decisions is a served private dataset, no engine while not served"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; local D="$T/m/ab_cloud-libs-shared/libs/decisions-engine"; mkdir -p "$T/m/1_cicd/src/data" "$D"
  cp "$ROOT/1_cicd/src/data/fleet-private-paths.json" "$ROOT/1_cicd/src/data/decisions-use-guard.json" "$T/m/1_cicd/src/data/"
  cp -r "$ROOT/ab_cloud-libs-shared/libs/decisions-engine/build.gradle" "$ROOT/ab_cloud-libs-shared/libs/decisions-engine/src" "$D/"
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
H=ab_cloud-libs-shared/libs/decisions-engine/src/main/java/com/diegonmarcos/superapp/decisions/engine/EngineHolder.kt
mutant "the asset creeps back" 'mkdir -p ab_cloud-libs-shared/libs/decisions-engine/src/main/assets; echo "{}" > ab_cloud-libs-shared/libs/decisions-engine/src/main/assets/decisions.json' "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/decisions#x#' $H" "S2 EngineHolder does not name"
mutant "the dataset read is dropped" "sed -i 's#FleetData.get(ctx, DATASET)#FleetData.get(ctx, \"x\")#' $H" "S2 EngineHolder does not read"
mutant "not served stops leaving the engine absent" "sed -i 's#no engine until it is)\"); return null#no engine until it is)\"); return emptyHolder()#' $H" "S2 not served"
mutant "the client dependency edge is dropped" "sed -i '/fleet-data-client/d' ab_cloud-libs-shared/libs/decisions-engine/build.gradle" "S3 decisions-engine lacks"
mutant "the use guard reads a missing path" "sed -i 's#fleet-private-data/decisions.json#x.json#' 1_cicd/src/data/decisions-use-guard.json" "S3 the decisions-use guard"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
