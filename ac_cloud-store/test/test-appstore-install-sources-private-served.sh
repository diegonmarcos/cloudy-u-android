#!/usr/bin/env bash
# #1566 appstore-install-sources (stores, endpoints, pinned signing certs) is a fleet-private tier-2 dataset served by Cloud Account's private route: never an APK asset.
#   S1 no appstore-install-sources.json under libs:appstore assets; the dataset is in the CI materialise map
#   S2 PhoneAppActions.sourcesOrNull reads fleet/appstore-install-sources through FleetData (the test asset path is the only other source)
#   S3 not served = null / "not served" (callers that cannot draw wait, never crash): sources() raises, Account tabs and the runbook take null
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
L=ab_cloud-libs-shared/libs
check() { local r="$1" K="$1/$L/appstore/src/main/java/com/diegonmarcos/superapp/appstore/PhoneAppActions.kt"
  [ -e "$r/$L/appstore/src/main/assets/appstore-install-sources.json" ] && echo "S1 appstore-install-sources.json is an APK asset again"
  grep -q '"appstore-install-sources"' "$r/1_cicd/src/data/fleet-private-paths.json" || echo "S1 appstore-install-sources is not in the CI materialise map"
  grep -q 'DATASET = "fleet/appstore-install-sources"' "$K" || echo "S2 PhoneAppActions does not name fleet/appstore-install-sources"
  grep -q 'FleetData.get(ctx.applicationContext, DATASET)' "$K" || echo "S2 PhoneAppActions does not read the dataset through FleetData"
  grep -q 'fun sourcesOrNull(ctx: Context): JSONObject?' "$K" || echo "S3 there is no not-served answer"
  grep -q 'PhoneAppActions.sourcesOrNull(ctx)' "$r/$L/account/src/main/java/com/diegonmarcos/superapp/profile/AccountTabs.kt" || echo "S3 Account tabs do not take the not-served answer"
  grep -q 'PhoneAppActions.sourcesOrNull(ctx) ?: return null' "$r/$L/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt" || echo "S3 the setup runbook does not take the not-served answer"
  return 0; }
FAIL=0
out="$(check "$ROOT")"; if [ -z "$out" ]; then echo "  PASS: appstore-install-sources is a served private dataset"; else echo "$out" | sed 's/^/  FAIL: /'; FAIL=1; fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() { rm -rf "$T/m"; mkdir -p "$T/m/$L" "$T/m/1_cicd/src/data"
  cp -r "$ROOT/$L/appstore" "$T/m/$L/appstore"; mkdir -p "$T/m/$L/account"; cp -r "$ROOT/$L/account/src" "$T/m/$L/account/src"; cp "$ROOT/1_cicd/src/data/fleet-private-paths.json" "$T/m/1_cicd/src/data/"
  ( cd "$T/m" && eval "$2" )
  if check "$T/m" | grep -q "$3"; then echo "  PASS: mutant '$1' is red ($3)"; else echo "  FAIL: mutant '$1' stayed green"; FAIL=1; fi; }
P=$L/appstore/src/main/java/com/diegonmarcos/superapp/appstore/PhoneAppActions.kt
mutant "the asset creeps back" "mkdir -p $L/appstore/src/main/assets; echo '{}' > $L/appstore/src/main/assets/appstore-install-sources.json" "S1"
mutant "the dataset name is dropped" "sed -i 's#fleet/appstore-install-sources#x#' $P" "S2"
mutant "the FleetData read is dropped" "sed -i 's#FleetData.get(ctx.applicationContext, DATASET)#null#' $P" "S2"
mutant "the runbook ignores not served" "sed -i 's# ?: return null##' $L/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt" "S3"
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || { echo "RED"; exit 1; }
