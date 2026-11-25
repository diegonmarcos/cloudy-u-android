#!/usr/bin/env bash
# #1566 shell-allowlist (the ADB broker's default-deny gate) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no shell-allowlist.json under src/main/assets and the dataset is in the CI materialise map
#   S2 the broker reads fleet/shell-allowlist through FleetData and the not-served state is DENY-ALL (never allow-all)
#   S3 the broker lib links the fleet data client; the ADB terminal palette reads the served declaration
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local r="$1" L="$1/ab_cloud-libs-shared/libs"
  local B="$L/shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellBroker.kt"
  [ -e "$L/shell-broker/src/main/assets/shell-allowlist.json" ] && echo "S1 shell-allowlist.json is an APK asset again"
  grep -q '"shell-allowlist"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 shell-allowlist is not in the CI materialise map"
  grep -q 'DATASET = "fleet/shell-allowlist"' "$B" || echo "S2 ShellBroker does not name fleet/shell-allowlist"
  grep -q 'FleetData.get(app, DATASET)' "$B" || echo "S2 ShellBroker does not read the dataset through FleetData"
  grep -q 'val DENY_ALL: Allowlist = Allowlist(emptyMap())' "$B" || echo "S2 the not-served default is not an empty (deny-all) allowlist"
  grep -q '?: return DENY_ALL' "$B" || echo "S2 not served does not fall to DENY_ALL"
  grep -q "fleet-data-client" "$L/shell-broker/build.gradle" || echo "S3 shell-broker lacks the fleet-data-client dependency"
  grep -q 'ShellBroker.declaration(ctx)' "$L/adb-shell-core/src/main/java/com/diegonmarcos/superapp/configs/AdbTerminal.kt" || echo "S3 the ADB terminal does not read the served declaration"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: shell-allowlist is a served private dataset, deny-all while not served"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m/1_cicd/src/data" "$T/m/ab_cloud-libs-shared"; cp "$ROOT/1_cicd/src/data/fleet-private-paths.json" "$T/m/1_cicd/src/data/"
  mkdir -p "$T/m/ab_cloud-libs-shared/libs"; for l in shell-broker adb-shell-core; do mkdir -p "$T/m/ab_cloud-libs-shared/libs/$l"; cp -r "$ROOT/ab_cloud-libs-shared/libs/$l/build.gradle" "$ROOT/ab_cloud-libs-shared/libs/$l/src" "$T/m/ab_cloud-libs-shared/libs/$l/" 2>/dev/null; done
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
BR=ab_cloud-libs-shared/libs/shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellBroker.kt
mutant "the asset creeps back" 'mkdir -p ab_cloud-libs-shared/libs/shell-broker/src/main/assets; echo "{}" > ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json' "S1"
mutant "not served becomes allow-all" "sed -i 's#Allowlist(emptyMap())#Allowlist(mapOf(\"*\" to Allowlist.Entry(listOf(Allowlist.Rule(\"x\", Regex(\".*\"))))))#' $BR" "S2 the not-served default"
mutant "the fall to deny-all is dropped" "sed -i 's#?: return DENY_ALL#?: return parsed!!.second#' $BR" "S2 not served"
mutant "the dataset read is dropped" "sed -i 's#FleetData.get(app, DATASET)#FleetData.get(app, \"x\")#' $BR" "S2 ShellBroker does not read"
mutant "the client dependency edge is dropped" "sed -i '/fleet-data-client/d' ab_cloud-libs-shared/libs/shell-broker/build.gradle" "S3 shell-broker lacks"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
