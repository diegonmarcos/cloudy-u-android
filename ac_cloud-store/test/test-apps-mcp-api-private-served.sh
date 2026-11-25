#!/usr/bin/env bash
# #1566 apps-mcp-api (the endpoints every app declares) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no apps-mcp-api.json under libs:appstore assets; the dataset is in the CI materialise map
#   S2 AppsMcpApi.load reads fleet/apps-mcp-api through FleetData and answers null while not served
#   S3 the Inspect pages handle null; the generator writes and checks the PRIVATE copy (schemaVersion first), never the public tree
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
L=ab_cloud-libs-shared/libs/appstore/src/main
check() { local r="$1"
  [ -e "$r/$L/assets/apps-mcp-api.json" ] && echo "S1 apps-mcp-api.json is an APK asset again"
  grep -q '"apps-mcp-api"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 apps-mcp-api is not in the CI materialise map"
  local K="$r/$L/java/com/diegonmarcos/superapp/appstore"
  grep -q 'DATASET = "fleet/apps-mcp-api"' "$K/AppsMcpApi.kt" || echo "S2 AppsMcpApi does not name fleet/apps-mcp-api"
  grep -q 'fun load(ctx: Context): Decl? = runCatching { FleetData.get' "$K/AppsMcpApi.kt" || echo "S2 AppsMcpApi.load is not a nullable FleetData read"
  [ "$(grep -c 'AppsMcpApi.load(ctx)?.let' "$K/StoreInspect.kt")" -ge 2 ] || echo "S3 StoreInspect does not handle a not-served declaration"
  local G="$r/1_cicd/src/scripts/cloud-android-apps-mcp-api.py"
  grep -q '^OUT = "fleet-private-data/apps-mcp-api.json"' "$G" || echo "S3 the generator does not target the private copy"
  grep -q '"schemaVersion", 1' "$G" || echo "S3 the generator does not emit schemaVersion"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: apps-mcp-api is a served private dataset"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m/$L" "$T/m/1_cicd/src/data" "$T/m/1_cicd/src/scripts"
  cp -r "$ROOT/$L/java" "$T/m/$L/java"; cp "$ROOT/1_cicd/src/data/fleet-private-paths.json" "$T/m/1_cicd/src/data/"; cp "$ROOT/1_cicd/src/scripts/cloud-android-apps-mcp-api.py" "$T/m/1_cicd/src/scripts/"
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
mutant "the asset creeps back" "mkdir -p $L/assets; echo '{}' > $L/assets/apps-mcp-api.json" "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/apps-mcp-api#x#' $L/java/com/diegonmarcos/superapp/appstore/AppsMcpApi.kt" "S2"
mutant "load is not nullable" "sed -i 's#Decl? = runCatching#Decl = runCatching#' $L/java/com/diegonmarcos/superapp/appstore/AppsMcpApi.kt" "S2"
mutant "the generator writes the public tree again" "sed -i 's#^OUT = .*#OUT = \"$L/assets/apps-mcp-api.json\"#' 1_cicd/src/scripts/cloud-android-apps-mcp-api.py" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
