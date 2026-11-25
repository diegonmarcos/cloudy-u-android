#!/usr/bin/env bash
# #1566 super-saving (the declared device settings and shell toggles of the mode) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no super-saving.json under src/main/assets and the dataset is in the CI materialise map
#   S2 SuperSavingRuntime reads fleet/super-saving through FleetData; null = not served
#   S3 not served = no engine, no steps, no indicator (never a crash, never a half-run mode)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() { local a="$1" R="$1/app/src/main/java/com/diegonmarcos/superapp/bgpolicy/SuperSavingRuntime.kt"
  [ -e "$a/app/src/main/assets/super-saving.json" ] && echo "S1 super-saving.json is an APK asset again"
  grep -q '"super-saving"' "$ROOT/1_cicd/src/data/fleet-private-paths.json" || echo "S1 super-saving is not in the CI materialise map"
  grep -q 'DATASET = "fleet/super-saving"' "$R" || echo "S2 the runtime does not name fleet/super-saving"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$R" || echo "S2 the runtime does not read the dataset through FleetData"
  grep -q 'decl(app) == null) return null' "$R" || echo "S3 engine() does not answer null while not served"
  grep -q 'decl(app)?.let { SsDecl.parse(it) }.orEmpty()' "$R" || echo "S3 stepsOf does not answer empty while not served"
  grep -q 'SsDecl.indicator(decl(app) ?: return@runCatching)' "$R" || echo "S3 the indicator does not handle not served"
  return 0; }
FAIL=0
out="$(check "$APP")"; if [ -z "$out" ]; then echo "  PASS: super-saving is a served private dataset"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m"; cp -r "$APP/app" "$T/m/app"; ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
mutant "the asset creeps back" 'mkdir -p app/src/main/assets; echo "{}" > app/src/main/assets/super-saving.json' "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/super-saving#x#' app/src/main/java/com/diegonmarcos/superapp/bgpolicy/SuperSavingRuntime.kt" "S2"
mutant "engine runs on an empty declaration" "sed -i 's#decl(app) == null) return null#false) return null#' app/src/main/java/com/diegonmarcos/superapp/bgpolicy/SuperSavingRuntime.kt" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
