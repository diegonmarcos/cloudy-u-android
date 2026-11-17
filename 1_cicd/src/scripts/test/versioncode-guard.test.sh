#!/usr/bin/env bash
# #1783 P5: the version code guard goes red on each way a version code can turn non-deterministic or go down.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
G="$ROOT/1_cicd/src/scripts/cloud-android-versioncode-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
OLD_SRC="ac_cloud-wallet/app/build.gradle"
git -C "$ROOT" show "HEAD:$OLD_SRC" > "$W/old.gradle"
grep -q "def codeFinal" "$W/old.gradle" || { fail "fixture source $OLD_SRC has no codeFinal"; exit 1; }

mk() {  # mk <dir>: a fixture repo with one old-block app (pending) and one migrated app
    local d="$1"; mkdir -p "$d/1_cicd/src/data" "$d/a/app" "$d/b/app"
    cp "$ROOT/1_cicd/src/data/version-floor.json" "$d/1_cicd/src/data/"
    python3 - "$d" "$W/old.gradle" <<'PY'
import json, sys
d, old = sys.argv[1:3]
cfg = json.load(open(d + "/1_cicd/src/data/version-floor.json")); cfg["pending"] = ["a/app/build.gradle"]
json.dump(cfg, open(d + "/1_cicd/src/data/version-floor.json", "w"))
PY
    cp "$W/old.gradle" "$d/a/app/build.gradle"; cp "$W/old.gradle" "$d/b/app/build.gradle"
    python3 "$G" --root "$d" apply b/app/build.gradle >/dev/null
    git -C "$d" init -q . && git -C "$d" add -A
}
red() { python3 "$G" --root "$1" check >/dev/null 2>&1 && fail "$2 stayed green" || ok "$2 goes red"; }

mk "$W/ok"; python3 "$G" --root "$W/ok" check >/dev/null && ok "a pending old block plus a migrated block is green" || fail "the baseline fixture is red"
grep -q "FLEET_SOURCE_TIME" "$W/ok/b/app/build.gradle" && ok "apply rewrites the block (source commit time)" || fail "apply did not rewrite"
! grep -q "format(new java.util.Date())" "$W/ok/b/app/build.gradle" && ok "apply removes the wall clock from BUILD_TIMESTAMP" || fail "BUILD_TIMESTAMP still reads the clock"

mk "$W/m1"; sed -i 's/Math.max(3462240L/Math.max(3000000L/' "$W/m1/b/app/build.gradle"; git -C "$W/m1" add -A; red "$W/m1" "M1 a lowered floor (a code could go down)"
mk "$W/m2"; python3 - "$W/m2" <<'PY'
import json, sys
p = sys.argv[1] + "/1_cicd/src/data/version-floor.json"; c = json.load(open(p)); c["pending"].append("b/app/build.gradle"); json.dump(c, open(p, "w"))
PY
git -C "$W/m2" add -A; red "$W/m2" "M2 a migrated file left in pending"
mk "$W/m3"; python3 - "$W/m3" <<'PY'
import json, sys
p = sys.argv[1] + "/1_cicd/src/data/version-floor.json"; c = json.load(open(p)); c["pending"] = []; json.dump(c, open(p, "w"))
PY
git -C "$W/m3" add -A; red "$W/m3" "M3 an app on the wall-clock block that is not pending"
mk "$W/m4"; sed -i "s/return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)/return java.time.LocalDateTime.ofEpochSecond(System.currentTimeMillis() \/ 1000, 0, java.time.ZoneOffset.UTC)/" "$W/m4/b/app/build.gradle"; git -C "$W/m4" add -A; red "$W/m4" "M4 a wall clock call in the version block"
mk "$W/m5"; sed -i 's/"floor": 3462240/"floor": 3462241/' "$W/m5/1_cicd/src/data/version-floor.json"; git -C "$W/m5" add -A; red "$W/m5" "M5 a floor that no longer matches floor_utc"

python3 "$G" --root "$ROOT" check >/dev/null && ok "the real tree is held" || fail "the real tree violates the version code guard"
echo
[ "$FAILURES" -eq 0 ] && { echo "== RESULT(#1783 version code guard): held =="; exit 0; }
echo "== RESULT(#1783 version code guard): $FAILURES check(s) failed =="; exit 1
