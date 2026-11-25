#!/usr/bin/env bash
# #1566 The probe registry names internal hosts: it must be a served tier-2 dataset (fleet/morpheus-probes), never an APK asset or a public copy.
#   P1 no probes.json under src/main/assets        P2 MainActivity reads it through FleetData (no assets.open("probes.json"))
#   P3 the app depends on libs:fleet-data-client   P4 a mutation (the asset creeps back / the reader opens the asset) turns this tester red
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
check() { # <app dir> -> prints one line per defect
  local a="$1" bad=""
  [ -e "$a/app/src/main/assets/probes.json" ] && echo "P1 probes.json is an APK asset again"
  grep -q 'FleetData.get(this, PROBES_DATASET)' "$a/app/src/main/java/com/diegonmarcos/morpheus/MainActivity.kt" || echo "P2 MainActivity does not read the dataset through FleetData"
  grep -q 'assets.open("probes.json")' "$a/app/src/main/java/com/diegonmarcos/morpheus/MainActivity.kt" && echo "P2 MainActivity opens the asset"
  grep -q "project(':libs:fleet-data-client')" "$a/app/build.gradle" || echo "P3 app/build.gradle lacks libs:fleet-data-client"
  grep -q 'fleet/morpheus-probes' "$a/app/src/main/java/com/diegonmarcos/morpheus/MainActivity.kt" || echo "P2 dataset name fleet/morpheus-probes missing"
  return 0
}
FAIL=0; PASS=0
out="$(check "$APP")"
if [ -z "$out" ]; then echo "  PASS: probe registry is a served tier-2 dataset"; PASS=1; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { # <name> <shell snippet run in the copy> <expected token>
  rm -rf "$T/m"; mkdir -p "$T/m"; cp -r "$APP/app" "$T/m/app"
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi
}
mutant "the asset creeps back" 'echo "{}" > app/src/main/assets/probes.json 2>/dev/null || { mkdir -p app/src/main/assets && echo "{}" > app/src/main/assets/probes.json; }' "P1"
mutant "the reader opens the asset" "sed -i 's/FleetData.get(this, PROBES_DATASET)/assets.open(\"probes.json\")/' app/src/main/java/com/diegonmarcos/morpheus/MainActivity.kt" "P2"
mutant "the dependency is dropped" "sed -i \"/fleet-data-client/d\" app/build.gradle" "P3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
