#!/usr/bin/env bash
# Tester (#531): Cloud Me's bottom bar is libs:bottomnav's Compose island, the
# stock Material BottomNavigationView is gone, and the bar reads
# Pers | Prof | Proj | Lnktree | Inventory | Buro | LLC (#1901: Health became Pers, Experiences Prof, Socials Proj and the Lnktree slot went into them; #1367; #1144, was #504). Structure only: the
# rendered order, pill, tap routing, clearance and scroll-collapse are MEASURED on
# the real shell body (MeBody) by app/src/test/.../MeBottomNavTest (CI tests.unit).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$APP/app/src/main"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

echo "== T1: the shell hosts the shared island (libs:ui-nav-bars's NavBarBottom, #1154) in MeBody =="
check "$(python3 - "$MAIN/java/com/diegonmarcos/cloudme" "$MAIN/res" <<'PY'
import os, re, sys
d = sys.argv[1]
def code(f):
    t = open(os.path.join(d, f), encoding='utf-8').read()
    t = re.sub(r'/\*.*?\*/', '', t, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$', '', t)
nav, act, shell = code('MeBottomNav.kt'), code('MainActivity.kt'), code('MeShell.kt')
p = []
if not re.search(r'\bNavBarBottom\s*\(', nav):
    p.append('MeBottomNav.kt does not draw libs:ui-nav-bars NavBarBottom')
if not re.search(r'\bMeShell\s*\(', act) or not re.search(r'\bMeBody\s*\(', shell):
    p.append('MainActivity does not lay the section out through MeShell > MeBody (content above the island)')
if os.path.exists(os.path.join(sys.argv[2], 'layout', 'activity_main.xml')):
    p.append('activity_main.xml is back: the shell is Compose')
print('; '.join(p) or 'OK')
PY
)" "the bar is libs:ui-nav-bars's NavBarBottom, drawn by MeBody under MainActivity"

echo "== T2: no Material bottom-navigation class in any source or layout =="
check "$(python3 - "$MAIN" <<'PY'
import os, sys
hits = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith(('.kt', '.java', '.xml')): continue
        p = os.path.join(root, f)
        for n, line in enumerate(open(p, encoding='utf-8', errors='replace'), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*', '<!--')): continue
            if 'com.google.android.material.bottomnavigation' in s:
                hits.append('%s:%d' % (os.path.relpath(p, sys.argv[1]), n))
print('; '.join(hits) or 'OK')
PY
)" "the stock BottomNavigationView is gone"

echo "== T3: the bar sections, in order, are Pers | Prof | Proj | Lnktree | Inventory | Buro | LLC (#1901) =="
check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
# #868 the bar is ui.bottom_nav (ids, in order), each one a ui.sections id.
secs = {s['id']: s for s in d['ui']['sections']}
ids = d['ui'].get('bottom_nav', [])
got = [secs[i]['label'] for i in ids if i in secs]
want = ['Pers', 'Prof', 'Proj', 'Lnktree', 'Inventory', 'Buro', 'LLC']
p = []
if len(got) != len(ids):
    p.append('ui.bottom_nav names an id that is not a ui.sections id')
if any(k in s for s in secs.values() for k in ('bottom_nav', 'order', 'default')):
    p.append('a section still carries bottom_nav/order/default - the bar is ui.bottom_nav')
if d['ui'].get('default_section') not in ids:
    p.append('ui.default_section is not one of ui.bottom_nav')
if got != want:
    p.append('the bar reads %s, #1144 asked for %s' % (' | '.join(got), ' | '.join(want)))
mod = d.get('modules', {}).get('libs:bottomnav', {})
if mod.get('dir') != '../ab_cloud-libs-shared/libs/bottomnav':
    p.append('modules.libs:bottomnav does not point at the ONE shared module')
print('; '.join(p) or 'OK')
PY
)" "build.json bar order is the #504 order and libs:bottomnav is linked by reference"

echo "== T4: the measured proof exists and CI runs it =="
check "$(python3 - "$APP" <<'PY'
import json, os, sys
app = sys.argv[1]
p = []
unit = json.load(open(os.path.join(app, 'build.json'))).get('tests', {}).get('unit', {})
if not unit.get('task') or unit.get('enabled') is False:
    p.append('build.json::tests.unit is off - MeBottomNavTest never runs in CI')
f = os.path.join(app, 'app/src/test/java/com/diegonmarcos/cloudme/MeBottomNavTest.kt')
t = open(f).read() if os.path.exists(f) else ''
if '@Test' not in t or 'MeBody(' not in t or 'createAndroidComposeRule' not in t:
    p.append('MeBottomNavTest is missing or does not drive the real shell body (MeBody)')
print('; '.join(p) or 'OK')
PY
)" "MeBottomNavTest drives the real shell body and tests.unit runs it"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
