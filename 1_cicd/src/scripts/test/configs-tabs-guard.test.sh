#!/usr/bin/env bash
# configs-tabs-guard.test — prove the Configs General | Perms | About guard (#1439) passes on the real tree and FAILS,
# naming the rule, on each way an app or the shared lib can break the order. python3 and coreutils only; no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-configs-tabs-guard.py"
DATA=1_cicd/src/data/configs-pages.json
LIB=ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsPages.kt
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$LIB"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1
FAILURES=0; PROVEN=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
BASE="$WORK/base"; T="$WORK/t"

out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"; else fail "the real tree is red"; printf '%s\n' "$out"; fi

mkdir -p "$BASE"
( cd "$ROOT" && for f in a[acd]_*/build.json "$DATA" "$LIB"; do mkdir -p "$BASE/$(dirname "$f")"; cp "$f" "$BASE/$f"; done )
python3 "$GUARD" "$BASE" >/dev/null || fail "the fixture is red before any mutation"

# mutant NAME RULE PYTHON — PYTHON edits the fixture (cwd = fixture) and must change a file; the guard must go red naming RULE.
mutant() {
    local name="$1" rule="$2" code="$3"
    rm -rf "$T"; cp -r "$BASE" "$T"
    (cd "$T" && python3 -c "$code") || { fail "$name: the mutation did not apply"; return; }
    if diff -rq "$BASE" "$T" >/dev/null; then fail "$name: the mutation changed nothing"; return; fi
    local o; o="$(python3 "$GUARD" "$T")"
    if [ $? -ne 0 ] && grep -q "FAIL  $rule" <<<"$o"; then ok "mutation proven red: $name ($rule)"; PROVEN=$((PROVEN + 1))
    else fail "$name: the guard stayed green or did not name $rule"; printf '%s\n' "$o" | tail -3; fi
}
J='import json
def load(p): return json.load(open(p))
def save(p, d): json.dump(d, open(p, "w"), indent=2)
'
mutant "Cloud Me Configs opens on Perms" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'][0], s['pages'][1] = s['pages'][1], s['pages'][0]; save('ac_cloud-me/build.json', d)"
mutant "Cloud Me Configs loses About" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
s['pages'] = [p for p in s['pages'] if p['id'] != 'about']; save('ac_cloud-me/build.json', d)"
mutant "Calc Configs mode order" T1 "$J
d = load('ac_cloud-calc/build.json'); m = [x for x in d['ui']['modes'] if x.get('tab') == 'configs']
i, j = d['ui']['modes'].index(m[1]), d['ui']['modes'].index(m[2]); d['ui']['modes'][i], d['ui']['modes'][j] = m[2], m[1]
save('ac_cloud-calc/build.json', d)"
mutant "Dialer About back at the end" T1 "$J
d = load('ac_cloud-dialer/build.json'); t = d['configs_pages']['top_nav']; a = [x for x in t if x['id'] == 'about'][0]
t.remove(a); t.append(a); save('ac_cloud-dialer/build.json', d)"
mutant "two Configs declarations disagree" T1 "$J
d = load('ac_cloud-me/build.json'); s = [x for x in d['ui']['sections'] if x['id'] == 'configs'][0]
d['configs_pages']['top_nav'] = s['pages'][:3]; save('ac_cloud-me/build.json', d)"
mutant "the shared screen puts About before Perms" T0 "
p = '$LIB'; s = open(p).read()
a = '    Perms(\"perms\", \"Perms\", \"security\"),\n'; b = '    About(\"about\", \"About\", \"info\"),\n'
assert a in s and b in s; open(p, 'w').write(s.replace(a + b, b + a))"
mutant "the shared screen drops its tab strip" T0 "
p = '$LIB'; s = open(p).read(); n = 'FleetConfigsTabs(shown, dark)'
assert n in s; open(p, 'w').write(s.replace(n, 'Unit // tabs gone'))"
mutant "a pending app is forgiven without an entry" T1 "$J
d = load('$DATA'); del d['tabs']['pending']['ac_cloud-browser']; save('$DATA', d)"
mutant "a conforming app stays pending (stale)" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-me'] = 'x' * 40; save('$DATA', d)"
mutant "pending names no app" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-nope'] = 'x' * 40; save('$DATA', d)"
mutant "pending reason too short" T3 "$J
d = load('$DATA'); d['tabs']['pending']['ac_cloud-drive'] = 'later'; save('$DATA', d)"
mutant "an app that no longer reaches the shared screen" T1 "$J
d = load('$DATA'); d['pending']['ac_cloud-tasks'] = 'x' * 40; save('$DATA', d)"

echo "configs-tabs-guard.test: $PROVEN mutation(s) proven red, $FAILURES failure(s)"
[ "$FAILURES" -eq 0 ]
