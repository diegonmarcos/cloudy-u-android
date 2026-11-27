#!/usr/bin/env bash
# Tester #1936: Phone > Apps lists third-party phone apps only. Static, no device.
#  P parity: every group, folder and third-party package of the page BEFORE (test/data/phone-page-before-1936.json) is still
#    declared, in the same order, and no baseline third-party package is a fleet package (the predicate removes only Cloud apps);
#  R reachability: every fleet package the old page showed (the icon_pkg of each folder) is a declared fleet app, so Cloud > Apps
#    lists it (GroupedTilesFragment lists PhonePageFleet.forContext, the set this page excludes);
#  S source: every section of the page takes its list from PhonePageFleet, with mutations that must go red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
SEARCH="$APP/app/src/main/java/com/diegonmarcos/superapp/search/SuperappSearchIndex.kt"
for f in "$FRAG" "$SEARCH" "$APP/build.json" "$APP/test/data/phone-page-before-1936.json"; do [ -s "$f" ] || { echo "FATAL: $f missing"; exit 2; }; done

python3 -I - "$APP" <<'PY' && ok "P parity: groups, folders and every third-party package of the previous page kept, same order, none of them a fleet app" || bad "parity failed (see above)"
import json, sys
A = sys.argv[1] + '/'
ui = json.load(open(A + 'build.json'))['ui']
before = json.load(open(A + 'test/data/phone-page-before-1936.json'))['groups']
pk = lambda x: x if isinstance(x, str) else x['pkg']
now = [s for s in ui['sections'] if s['id'] == 'phone'][0]['phone_app_groups']
fleet = set()
for e in ui['external_apps']:
    fleet |= {e.get('hub_package'), e.get('alt_package'), e.get('install_package')} | set(e.get('forks', {}).values())
fleet.discard(None); fleet.discard('')
errs = []
SOC = {'com.instagram.android', 'com.instagram.barcelona', 'com.facebook.katana', 'com.linkedin.android', 'com.pinterest', 'com.bereal.ft', 'com.tinder', 'com.bumble.app', 'co.hinge.app'}
MOVED = {'Me-Prj': SOC, 'Code': {'com.github.android'}, 'Media Center': {'com.amazon.avod.thirdpartyclient'}}
anynow = {pk(p) for g in now for p in g.get('packages', [])} | {pk(p) for g in now for f in g.get('folders', []) for p in f.get('packages', [])}
lostany = [p for g in before for f in g['folders'] for p in f['packages'] if p not in anynow and p != 'com.amazon.avod.thirdpartyclient']
if lostany: errs.append('packages gone from every folder: %s' % lostany)
if [g['title'] for g in now][:len(before)] != [g['title'] for g in before]: errs.append('group order')
for bg in before:
    g = next((x for x in now if x['title'] == bg['title']), None)
    if not g: errs.append('group lost ' + bg['title']); continue
    if [pk(p) for p in g.get('packages', [])][:len(bg['packages'])] != bg['packages']: errs.append(bg['title'] + ' loose packages')
    nf = [f['label'] for f in g.get('folders', []) if f['label'] != 'Lnktree']   # #1956 Lnktree is the one new folder
    if nf[:len(bg['folders'])] != [f['label'] for f in bg['folders']]: errs.append(bg['title'] + ' folder order')
    for bf in bg['folders']:
        f = next((x for x in g.get('folders', []) if x['label'] == bf['label']), None)
        if not f: continue
        mem = [pk(p) for p in f.get('packages', [])]
        gone = MOVED.get(bf['label'], set())   # #1956 moves: socials Me-Prj -> Me-Pers, GitHub Code -> Drive, Prime Video removed from Media Center
        if mem[:len([p for p in bf['packages'] if p not in gone])] != [p for p in bf['packages'] if p not in gone]: errs.append('%s/%s members' % (bg['title'], bf['label']))
        bad = [p for p in bf['packages'] if p in fleet]
        if bad: errs.append('%s/%s baseline holds fleet apps %s' % (bg['title'], bf['label'], bad))
print('; '.join(errs)) if errs else None
sys.exit(1 if errs else 0)
PY

python3 -I - "$APP" <<'PY' && ok "R reachability: every Cloud app the old folders showed (icon_pkg) is a declared fleet app, listed by Cloud > Apps" || bad "a removed Cloud package is not a declared fleet app"
import json, sys
A = sys.argv[1] + '/'
ui = json.load(open(A + 'build.json'))['ui']
before = json.load(open(A + 'test/data/phone-page-before-1936.json'))['groups']
fleet = set()
for e in ui['external_apps']:
    fleet |= {e.get('hub_package'), e.get('alt_package'), e.get('install_package')} | set(e.get('forks', {}).values())
removed = {f['icon_pkg'] for g in before for f in g['folders'] if f.get('icon_pkg')}
miss = sorted(removed - fleet)
print('not fleet apps: %s' % miss) if miss else None
sys.exit(1 if miss or len(removed) < 20 else 0)
PY

check() { # $1 fragment $2 search
    grep -q 'PhonePageFleet.groups(Quickmarks.apply(' "$1" || echo "Quickmarks unfiltered"
    grep -q 'allApps(ctx, exclude = PhonePageFleet.exclude(ourApps))' "$1" || echo "All Apps unfiltered"
    grep -q 'loadSmartFolders(ctx, PhonePageFleet.exclude(ourApps))' "$1" || echo "Smart Folders unfiltered"
    grep -q 'PhonePageFleet.apps(activeLoaded' "$1" || echo "Active Apps unfiltered"
    grep -q 'PhonePageFleet.apps(lastLoaded' "$1" || echo "Last Apps unfiltered"
    grep -q 'PhonePageFleet.isFleet(it.packageName, fleet)' "$2" || echo "search unfiltered"
}
OUT="$(check "$FRAG" "$SEARCH")"; [ -z "$OUT" ] && ok "S every section takes its list from PhonePageFleet" || bad "source: $OUT"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mut() { cp "$FRAG" "$T/f.kt"; cp "$SEARCH" "$T/s.kt"; sed -i "$3" "$T/$2.kt"
    if cmp -s "$FRAG" "$T/f.kt" && cmp -s "$SEARCH" "$T/s.kt"; then bad "mutation '$1' changed nothing"; return; fi
    [ -n "$(check "$T/f.kt" "$T/s.kt")" ] && ok "mutation '$1' goes red" || bad "mutation '$1' stayed green"; }
mut "Quickmarks predicate removed" f 's/PhonePageFleet.groups(Quickmarks.apply(\(.*\)), ourApps) }/Quickmarks.apply(\1) }/'
mut "All Apps predicate removed" f 's/allApps(ctx, exclude = PhonePageFleet.exclude(ourApps))/allApps(ctx)/'
mut "Smart Folders predicate removed" f 's/loadSmartFolders(ctx, PhonePageFleet.exclude(ourApps))/loadSmartFolders(ctx, emptySet())/'
mut "Active Apps predicate removed" f 's/PhonePageFleet.apps(activeLoaded?.packages.orEmpty(), ourApps)/activeLoaded?.packages.orEmpty()/'
mut "Last Apps predicate removed" f 's/PhonePageFleet.apps(lastLoaded, ourApps)/lastLoaded/'
mut "search predicate removed" s 's/filterNot { PhonePageFleet.isFleet(it.packageName, fleet) }/toList()/'
echo; echo "-- test-phone-page-no-cloud-1936: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
