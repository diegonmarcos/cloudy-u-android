#!/usr/bin/env bash
# #1566 The APIs and Cost declarations (endpoint and store names) are fleet-private tier-2 datasets served by Cloud Account: never an APK asset.
#   S1 no apis.json / cost.json under src/main/assets/ai     S2 AiData maps both to fleet/superintel-apis and fleet/superintel-cost through FleetData
#   S3 not served = a labelled page / no key, never a crash (blank text is checked before parsing)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
check() { local a="$1" K="$1/app/src/main/java/com/diegonmarcos/cloudsuperintel"
  for f in apis cost; do [ -e "$a/app/src/main/assets/ai/$f.json" ] && echo "S1 $f.json is an APK asset again"; done
  grep -q '"ai/apis.json" to "fleet/superintel-apis"' "$K/AiData.kt" || echo "S2 apis is not mapped to fleet/superintel-apis"
  grep -q '"ai/cost.json" to "fleet/superintel-cost"' "$K/AiData.kt" || echo "S2 cost is not mapped to fleet/superintel-cost"
  grep -q 'FleetData.get(ctx, ds) { t -> t }' "$K/AiData.kt" || echo "S2 AiData does not read the private datasets through FleetData"
  grep -q 'if (it.isBlank()) NotServedPage()' "$K/ui/AiScreens.kt" || echo "S3 the pages do not handle a not-served dataset"
  grep -q 'text.isBlank()' "$K/OpenRouterKey.kt" || echo "S3 OpenRouterKey does not handle a not-served declaration"
  return 0; }
FAIL=0
out="$(check "$APP")"; if [ -z "$out" ]; then echo "  PASS: apis and cost are served private datasets"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"; cp -r "$APP/app" "$T/m/app"; ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
mutant "the asset creeps back" 'echo "{}" > app/src/main/assets/ai/cost.json' "S1"
mutant "the mapping is dropped" "sed -i 's#\"ai/apis.json\" to \"fleet/superintel-apis\"##' app/src/main/java/com/diegonmarcos/cloudsuperintel/AiData.kt" "S2"
mutant "the blank guard is dropped" "sed -i 's/text.isBlank()/false/' app/src/main/java/com/diegonmarcos/cloudsuperintel/OpenRouterKey.kt" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
