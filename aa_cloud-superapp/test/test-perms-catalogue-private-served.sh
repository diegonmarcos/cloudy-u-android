#!/usr/bin/env bash
# #1566 perms-catalogue (every runtime / special / app-op / adb-only grant with its SDK window, settings targets and adb lines) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no perms-catalogue.json under libs:perms assets and the dataset is in the CI materialise map
#   S2 Perms.catalogue reads fleet/perms-catalogue through FleetData; not served = an empty catalogue that is NOT cached (retried), never a crash
#   S3 build-time consumers (the perms guard, the Configs generator) read the materialised copy
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local r="$1" L="$1/ab_cloud-libs-shared/libs"
  local P="$L/perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt" C="$L/perms/src/main/java/com/diegonmarcos/superapp/perms/PermCatalogue.kt"
  [ -e "$L/perms/src/main/assets/perms-catalogue.json" ] && echo "S1 perms-catalogue.json is an APK asset again"
  grep -q '"perms-catalogue"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 perms-catalogue is not in the CI materialise map"
  grep -q 'DATASET = "fleet/perms-catalogue"' "$C" || echo "S2 PermCatalogue does not name fleet/perms-catalogue"
  grep -q 'FleetData.get(ctx.applicationContext, PermCatalogue.DATASET)' "$P" || echo "S2 Perms.catalogue does not read the dataset through FleetData"
  grep -q '?.also { cached = it } ?: PermCatalogue(emptyList(), emptyList())' "$P" || echo "S2 not served is not an uncached empty catalogue"
  grep -q 'CAT_REL = "fleet-private-data/perms-catalogue.json"' "$r/1_cicd/src/scripts/cloud-android-perms-guard.py" || echo "S3 the perms guard does not read the materialised copy"
  grep -q "fleet-private-data/perms-catalogue.json" "$L/ui-nav-bars/fleet-configs.gradle" || echo "S3 the Configs generator does not read the materialised copy"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: perms-catalogue is a served private dataset, an empty uncached catalogue while not served"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"
  for f in 1_cicd/src/data/fleet-private-paths.json 1_cicd/src/scripts/cloud-android-perms-guard.py ab_cloud-libs-shared/libs/ui-nav-bars/fleet-configs.gradle ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/PermCatalogue.kt; do
    mkdir -p "$T/m/$(dirname "$f")"; cp "$ROOT/$f" "$T/m/$f"; done
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
P=ab_cloud-libs-shared/libs/perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt
mutant "the asset creeps back" 'mkdir -p ab_cloud-libs-shared/libs/perms/src/main/assets; echo "{}" > ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json' "S1"
mutant "the dataset read is dropped" "sed -i 's#PermCatalogue.DATASET)#\"x\")#' $P" "S2 Perms.catalogue does not read"
mutant "the empty catalogue gets cached" "sed -i 's#?.also { cached = it } ?: PermCatalogue(emptyList(), emptyList())#?: PermCatalogue(emptyList(), emptyList()).also { cached = it }#' $P" "S2 not served is not"
mutant "the perms guard reads the lib assets" "sed -i 's#fleet-private-data/perms-catalogue.json#ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json#' 1_cicd/src/scripts/cloud-android-perms-guard.py" "S3 the perms guard"
mutant "the Configs generator reads the lib assets" "sed -i 's#fleet-private-data/perms-catalogue.json#x.json#' ab_cloud-libs-shared/libs/ui-nav-bars/fleet-configs.gradle" "S3 the Configs generator"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
