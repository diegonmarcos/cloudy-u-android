#!/usr/bin/env bash
# #1783 P3 + P1b: edits that cannot change an APK move no publish identity.
# A doc-only edit of the fleet manifest, a README, a test source set: 0 publishes. Another app's
# declaration in the manifest: 0 publishes for this app (its slice did not move). A real change: publishes.
# Drives the REAL cloud-android-source-identity.sh on a fixture git repo. bash, python3, git, jq; no network.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
SRC="$ROOT/1_cicd/src"
ID="$SRC/scripts/cloud-android-source-identity.sh"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
F="$W/fx"
MAN="ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
LIB="ab_cloud-libs-shared/libs"

mkdir -p "$F/1_cicd/src/data" "$F/1_cicd/src/scripts" "$F/1_cicd/src/cicd" "$F/ac_cloud-a" "$F/ac_cloud-b" "$F/ac_cloud-plan" \
         "$F/$LIB/fleetconfig-model/src/main/assets" "$F/$LIB/fleetconfig-model/src/test" "$F/$LIB/other/src/main/assets" "$F/$LIB/other/src/test" "$F/$LIB/other/src/main/java"
cp "$SRC/data/identity-exclusions.json" "$F/1_cicd/src/data/"
cp "$SRC/scripts/cloud-android-fleet-slice.py" "$F/1_cicd/src/scripts/"
echo "{\"manifest_watchers\": {\"$MAN\": [\"ac_cloud-plan\"]}}" > "$F/1_cicd/src/data/rebuild-isolation.json"
for a in a b plan; do echo '{}' > "$F/ac_cloud-$a/build.json"; echo "class X$a" > "$F/ac_cloud-$a/X.kt"; done
echo 'plugins { }' > "$F/$LIB/fleetconfig-model/build.gradle"; echo 'plugins { }' > "$F/$LIB/other/build.gradle"
echo 'class T' > "$F/$LIB/fleetconfig-model/src/test/T.kt"; echo 'class T' > "$F/$LIB/other/src/test/T.kt"
echo '# readme' > "$F/$LIB/other/README.md"; echo '# asset doc' > "$F/$LIB/other/src/main/assets/shipped.md"; echo 'class O' > "$F/$LIB/other/src/main/java/O.kt"
cat > "$F/$MAN" <<'J'
{"_doc":"d","schema_version":1,"contract":1,"classes":{"config":"c"},"migrate":["config"],"kinds":{"prefs":"p"},
 "stores":{"sa":{"kind":"prefs","class":"config","doc":"store a","used_by":["ac_cloud-a"]},"sb":{"kind":"prefs","class":"config","doc":"store b","used_by":["ac_cloud-b"]}},
 "bundle":{"_doc":"b","k":1},"resolve":{},
 "apps":{"a":{"package":"p.a","module":"ac_cloud-a","libs":[]},"b":{"package":"p.b","module":"ac_cloud-b","libs":[]},"plan":{"package":"p.plan","module":"ac_cloud-plan","libs":[]}},"libs":{}}
J
mkwf() {  # mkwf <app> <slug> [with-exclusion]
    { echo 'name: x'; echo 'env:'; echo "  WORK_DIR: ac_cloud-$1"; echo 'on:'; echo '  push:'; echo '    paths:'
      echo "      - \"ac_cloud-$1/**\""; echo "      - \"$LIB/fleetconfig-model/**\""; echo "      - \"$LIB/other/**\""
      [ -z "${2:-}" ] || echo "      - \"!$MAN\""; } > "$F/1_cicd/src/cicd/ship-cloud-$1.yml"
}
mkwf a ex; mkwf b ex; mkwf plan
git -C "$F" init -q . && git -C "$F" add -A && git -C "$F" -c user.email=t@t -c user.name=t commit -qm base
export CLOUD_ANDROID_ROOT="$F"
ident() { sh "$ID" compute "ac_cloud-$1" 2>/dev/null; }
snap() { A="$(ident a)"; B="$(ident b)"; P="$(ident plan)"; }
commit() { git -C "$F" add -A && git -C "$F" -c user.email=t@t -c user.name=t commit -qm "$1"; }
pubs() {  # pubs <label> <expected-count> ; compares to the last snap, then re-snaps
    local a="$A" b="$B" p="$P"; snap; local n=0
    [ "$a" = "$A" ] || n=$((n+1)); [ "$b" = "$B" ] || n=$((n+1)); [ "$p" = "$P" ] || n=$((n+1))
    if [ "$n" -eq "$2" ]; then ok "$1: $n publish(es)"; else fail "$1: expected $2 publish(es), got $n"; fi
}
snap
[ -n "$A" ] && [ "$A" != "$B" ] || fail "fixture: identities are empty or equal"

# manifest: doc-only edit (every doc / _doc string)
python3 - "$F/$MAN" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p))
d["_doc"] = "reworded"; d["stores"]["sa"]["doc"] = "reworded store a"; d["bundle"]["_doc"] = "x"
json.dump(d, open(p, "w"), indent=2)
PY
commit "manifest doc-only"; pubs "doc-only edit of the fleet manifest (doc + _doc keys, reformatted)" 0

# manifest: another app's declaration -> only the planner (whole manifest) moves
python3 - "$F/$MAN" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["stores"]["sb"]["keys"] = ["new_pref"]
json.dump(d, open(p, "w"), indent=2)
PY
commit "b declares a pref"; pubs "a new pref key in app b's store: b and the planner move, app a does not" 2

# manifest: a declaration for app a only
python3 - "$F/$MAN" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["stores"]["sa"]["keys"] = ["a_pref"]
json.dump(d, open(p, "w"), indent=2)
PY
commit "a declares a pref"; pubs "a new pref key in app a's store: a and the planner move" 2

# README and test sources in a shared lib
echo "more words" >> "$F/$LIB/other/README.md"; commit "readme"; pubs "README edit inside a shared lib" 0
echo "class T2" >> "$F/$LIB/other/src/test/T.kt"; echo "class T2" >> "$F/$LIB/fleetconfig-model/src/test/T.kt"; commit "tests"; pubs "test source edit inside shared libs" 0

# mutants: what can reach an APK must still publish
echo "changed" >> "$F/$LIB/other/src/main/assets/shipped.md"; commit "asset md"; pubs "M1 a .md under assets/ (ships in the APK) still publishes all 3" 3
echo "class O2" >> "$F/$LIB/other/src/main/java/O.kt"; commit "code"; pubs "M2 a lib code edit still publishes all 3" 3
echo "class XA2" >> "$F/ac_cloud-a/X.kt"; commit "app a"; pubs "M3 an app's own code edit publishes that app only" 1
python3 - "$F/$MAN" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["stores"]["sa"]["kind"] = "encrypted"
json.dump(d, open(p, "w"), indent=2)
PY
commit "a kind"; pubs "M4 a real (non-doc) field change in app a's store still publishes a and the planner" 2

echo
[ "$FAILURES" -eq 0 ] && { echo "== RESULT(#1783 doc-only publish identity): held =="; exit 0; }
echo "== RESULT(#1783 doc-only publish identity): $FAILURES check(s) failed =="; exit 1
