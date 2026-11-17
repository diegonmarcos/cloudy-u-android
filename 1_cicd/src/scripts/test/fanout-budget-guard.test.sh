#!/usr/bin/env bash
# #1783 P2 fan-out budget guard: proves the guard goes red on each way the budget can be dodged
# or silently raised, then holds the real tree to it. python3, bash, git only; no network.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
G="$ROOT/1_cicd/src/scripts/cloud-android-fanout-budget-guard.py"
DATA="$ROOT/1_cicd/src/data/fanout-budget.json"
MAN="ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
cd "$ROOT"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

green() { python3 "$G" "$@" >/dev/null 2>&1; }

# the budget is pinned here: raising it in the data file alone goes red
[ "$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["budget"])' "$DATA")" = "5" ] \
    && ok "the budget is 5 (a raise needs this test edited by the owner)" || fail "fanout-budget.json budget is not 5"

# real tree, real data
green --files "$MAN" && ok "a fleet-config.json edit stays within budget (only its planners start)" || fail "a fleet-config.json edit exceeds the budget"
green --files ac_cloud-mail/app/src/main/x.kt && ok "an app-only edit is within budget" || fail "an app-only edit exceeds the budget"
green --files ab_cloud-libs-shared/libs/core/src/main/x.kt && ok "a genuine shared-lib edit passes through its allowlist entry" || fail "an allowlisted lib edit went red"
green --files "$MAN" ab_cloud-libs-shared/libs/core/src/main/x.kt && ok "manifest + allowlisted lib edit together are within budget" || fail "manifest + allowlisted lib edit went red"

# M1: a wide change that is not on the allowlist (libs/shell-client: 7 consumers)
green --files ab_cloud-libs-shared/libs/shell-client/src/main/x.kt \
    && fail "M1 a non-allowlisted wide lib edit stayed green" || ok "M1 a non-allowlisted wide lib edit goes red"

# M2: without its allowlist entry a lib edit is judged like any other change
python3 - "$DATA" "$T/noshell.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d["allow"] = [a for a in d["allow"] if "libs/core/" not in a["glob"]]
json.dump(d, open(sys.argv[2], "w"))
PY
green --data "$T/noshell.json" --files ab_cloud-libs-shared/libs/core/src/main/x.kt \
    && fail "M2 removing the core allowlist entry stayed green" || ok "M2 a lib edit without its allowlist entry goes red"

# M3: the fleet-config exclusion removed from every ship workflow (the 2026-11-17 regression)
mkdir "$T/wf" && cp -r 1_cicd/src/cicd/. "$T/wf/"
python3 - "$T/wf" "$MAN" <<'PY'
import glob, sys
d, man = sys.argv[1:3]
for w in glob.glob(d + "/ship-*.yml"):
    t = open(w).read()
    open(w, "w").write(t.replace('      - "!%s"\n' % man, ""))
PY
green --wf "$T/wf" --files "$MAN" && fail "M3 a manifest edit starting every app stayed green" || ok "M3 dropping the manifest exclusions (42 apps per edit) goes red"

# M4: an allowlist entry with no reason / no ticket is refused
for k in reason ticket; do
    python3 - "$DATA" "$T/no$k.json" "$k" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["allow"][0][sys.argv[3]] = ""
json.dump(d, open(sys.argv[2], "w"))
PY
    green --data "$T/no$k.json" --files ac_cloud-mail/x.kt && fail "M4 an allow entry without a $k stayed green" || ok "M4 an allow entry without a $k goes red"
done

# M5: the allowlist covering the manifest itself would hide the regression
python3 - "$DATA" "$T/hide.json" "$MAN" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["allow"].append({"glob": sys.argv[3], "reason": "x", "ticket": "#0"})
json.dump(d, open(sys.argv[2], "w"))
PY
python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
bad=[a["glob"] for a in d["allow"] if a["glob"]=="ab_cloud-libs-shared/libs/fleetconfig-model/**" or a["glob"].endswith("/assets/**") or a["glob"]==sys.argv[2]]
sys.exit(1 if bad else 0)' "$DATA" "$MAN" && ok "the real allowlist does not cover the manifest" || fail "the real allowlist covers the manifest and would hide its fan-out"

echo
[ "$FAILURES" -eq 0 ] && { echo "== RESULT(#1783 fan-out budget): held =="; exit 0; }
echo "== RESULT(#1783 fan-out budget): $FAILURES check(s) failed =="; exit 1
