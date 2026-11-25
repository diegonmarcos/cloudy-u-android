#!/usr/bin/env bash
# #1566 appstore-feeds (the repositories the Cloud page reads) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no appstore-feeds.json under libs:appstore assets; the dataset is in the CI materialise map
#   S2 FeedViewer.feeds reads fleet/appstore-feeds through FleetData (the test asset path is the only other source)
#   S3 not served = no feeds and therefore no tabs (runCatching to emptyList), never a half-built tab
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
L=ab_cloud-libs-shared/libs/appstore/src/main
check() { local r="$1" K="$1/$L/java/com/diegonmarcos/superapp/appstore/FeedViewer.kt"
  [ -e "$r/$L/assets/appstore-feeds.json" ] && echo "S1 appstore-feeds.json is an APK asset again"
  grep -q '"appstore-feeds"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 appstore-feeds is not in the CI materialise map"
  grep -q 'DATASET = "fleet/appstore-feeds"' "$K" || echo "S2 FeedViewer does not name fleet/appstore-feeds"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$K" || echo "S2 FeedViewer does not read the dataset through FleetData"
  grep -q '}.getOrDefault(emptyList())' "$K" || echo "S3 feeds() does not answer no feeds while not served"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: appstore-feeds is a served private dataset"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m/$L" "$T/m/1_cicd/src/data"
  cp -r "$ROOT/$L/java" "$T/m/$L/java"; cp "$ROOT/1_cicd/src/data/fleet-private-paths.json" "$T/m/1_cicd/src/data/"
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
F=$L/java/com/diegonmarcos/superapp/appstore/FeedViewer.kt
mutant "the asset creeps back" "mkdir -p $L/assets; echo '{}' > $L/assets/appstore-feeds.json" "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/appstore-feeds#x#' $F" "S2"
mutant "the FleetData read is dropped" "sed -i 's#FleetData.get(ctx.applicationContext, DATASET)#null#' $F" "S2"
mutant "a failed read throws" "sed -i 's#}.getOrDefault(emptyList())#}.getOrThrow()#' $F" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
