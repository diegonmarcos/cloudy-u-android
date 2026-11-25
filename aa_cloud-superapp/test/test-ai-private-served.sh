#!/usr/bin/env bash
# #1566 ai (the shared AI contract: gateway service and routes, OpenRouter endpoints, protocol knobs) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no ai.json under libs:ai assets and the dataset is in the CI materialise map
#   S2 AiConfig reads fleet/ai through FleetData; not served = the NOT_SERVED default (empty endpoints, served=false), never null, never a crash
#   S3 build-time consumers (gateway gradle, Cloud Browser, Cloud Code, the chat guard) read the materialised copy, not the lib's assets
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local r="$1" L="$1/ab_cloud-libs-shared/libs/ai"
  local C="$L/src/main/kotlin/com/diegonmarcos/superapp/ai/AiConfig.kt"
  [ -e "$L/src/main/assets/ai/ai.json" ] && echo "S1 ai.json is an APK asset again"
  grep -q '"ai"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 ai is not in the CI materialise map"
  grep -q 'DATASET = "fleet/ai"' "$C" || echo "S2 AiConfig does not name fleet/ai"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$C" || echo "S2 AiConfig does not read the dataset through FleetData"
  grep -q 'fun load(ctx: Context): AiConfig = loadOrNull(ctx) ?: NOT_SERVED' "$C" || echo "S2 load does not fall back to NOT_SERVED"
  grep -q 'served = false' "$C" || echo "S2 the not-served default is not marked served=false"
  grep -q "fleet-private-data/ai.json" "$L/ai-gateway.gradle" || echo "S3 ai-gateway.gradle does not read the materialised copy"
  grep -q "fleet-private-data/ai.json" "$r/ac_cloud-browser/app/build.gradle" || echo "S3 Cloud Browser does not bake the materialised copy"
  grep -q '"ai": "fleet-private-data/ai.json"' "$r/ac_cloud-code/src/cloud/nav.json" || echo "S3 Cloud Code nav.json does not point at the materialised copy"
  grep -q '"repo_file": "fleet-private-data/ai.json"' "$r/1_cicd/src/data/ai-chat-guard.json" || echo "S3 the chat guard does not hold the materialised contract"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: ai is a served private dataset, a safe default while not served"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"
  for f in 1_cicd/src/data/fleet-private-paths.json 1_cicd/src/data/ai-chat-guard.json ac_cloud-browser/app/build.gradle ac_cloud-code/src/cloud/nav.json ab_cloud-libs-shared/libs/ai/ai-gateway.gradle ab_cloud-libs-shared/libs/ai/src/main/kotlin/com/diegonmarcos/superapp/ai/AiConfig.kt; do
    mkdir -p "$T/m/$(dirname "$f")"; cp "$ROOT/$f" "$T/m/$f"; done
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
C=ab_cloud-libs-shared/libs/ai/src/main/kotlin/com/diegonmarcos/superapp/ai/AiConfig.kt
mutant "the asset creeps back" 'mkdir -p ab_cloud-libs-shared/libs/ai/src/main/assets/ai; echo "{}" > ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai.json' "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/ai#x#' $C" "S2 AiConfig does not name"
mutant "load stops falling back" "sed -i 's#loadOrNull(ctx) ?: NOT_SERVED#loadOrNull(ctx)!!#' $C" "S2 load does not fall back"
mutant "the default stops saying it is not served" "sed -i 's#served = false#served = true#' $C" "S2 the not-served default"
mutant "the gateway gradle reads the lib assets" "sed -i 's#fleet-private-data/ai.json#ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai.json#' ab_cloud-libs-shared/libs/ai/ai-gateway.gradle" "S3 ai-gateway.gradle"
mutant "Cloud Browser bakes the lib assets" "sed -i 's#fleet-private-data/ai.json#x/ai.json#' ac_cloud-browser/app/build.gradle" "S3 Cloud Browser"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
