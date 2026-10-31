#!/usr/bin/env bash
# Tester: #1221 Configs > Fleet > Bluetooth over libs:bluetooth. Static (no device, no build); the rules themselves are JVM-tested in
# app/src/test/.../bluetooth/BluetoothLibTest.kt.
#
#   B1 the page is declared: Fleet group, five hidden tabs Find | Connect | Interact | Debug | Dev, every tab routed to BluetoothFragment
#   B2 Configs declares horizontal_two_rows with the Fleet row (Bluetooth, C3 Fleet Control) and the Observability row (X-Ray, LogCat, Permissions, About; was Agents,
#      Diagnostics, Logcat, About), every page of the section in exactly one group
#   B3 the page is the lib's: BluetoothFragment is a KitComposeFragment (its scrolling reaches the shell) over BluetoothHub; the five
#      tabs and the ATT console, hex write, notify stream and permission rows exist in the lib's Compose page
#   B4 the vocabulary is data: assets/bluetooth/gatt-catalog.json parses, every UUID is valid and unique, every decoder it names is
#      implemented in GattDecoders, the lib has no Kotlin table of services
#   B5 permissions: the lib manifest declares scan / connect (API 31+) and the legacy pair + location (to API 30), scan never for location
#   B6 registered everywhere a shared lib must be: build.json module, lib-apks (excluded + described), gradle dependency, ship / test path triggers
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
LIB="$ROOT/ab_cloud-libs-shared/libs/bluetooth"
LK="$LIB/src/main/java/com/diegonmarcos/superapp/bluetooth"
for f in "$BJ" "$KT/configs/BluetoothFragment.kt" "$KT/launcher/SectionPages.kt" "$LIB/build.gradle" "$LIB/src/main/AndroidManifest.xml" "$LK/BluetoothUi.kt" "$LK/GattDecoders.kt" "$LIB/src/main/assets/bluetooth/gatt-catalog.json"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done
command -v python3 >/dev/null 2>&1 || { echo "  ABORT: python3 missing"; exit 2; }

echo "== B1: the page and its five tabs =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = {p['id']: p for p in next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']}
probs = []
b = pages.get('bluetooth')
if not b: print('no bluetooth page'); sys.exit()
if b.get('group') != 'Fleet' or b.get('label') != 'Bluetooth' or b.get('hidden'): probs.append('page = %r' % b)
want = ['bluetooth-find', 'bluetooth-analyze', 'bluetooth-locate', 'bluetooth-connect', 'bluetooth-interact', 'bluetooth-debug', 'bluetooth-dev']
if b.get('tabs') != want: probs.append('tabs = %r' % b.get('tabs'))
for t, label in zip(want, ['Find', 'Analyze', 'Locate', 'Connect', 'Interact', 'Debug', 'Dev']):
    p = pages.get(t)
    if not p or p.get('label') != label or not p.get('hidden') or not p.get('icon'): probs.append('tab %s = %r' % (t, p))
print('; '.join(probs) or 'OK')
PY
)" "bluetooth: Fleet, label Bluetooth, tabs Find | Analyze | Locate | Connect | Interact | Debug | Dev, each a hidden page with an icon"
for tab in find analyze locate connect interact debug dev; do
  grep -qE "pageId == \"bluetooth-$tab\" +-> .*BluetoothFragment.newInstance\(.*BtTab\." "$KT/launcher/SectionPages.kt" \
    && ok "config/bluetooth-$tab routes to BluetoothFragment" || bad "config/bluetooth-$tab is not routed"
done
python3 - "$LK/BluetoothUi.kt" <<'PY' && ok "BtTab ids are exactly find, analyze, locate, connect, interact, debug, dev" || bad "BtTab ids drifted from the declared tabs"
import re, sys
ids = re.findall(r'(?:Find|Analyze|Locate|Connect|Interact|Debug|Dev)\("(\w+)"', open(sys.argv[1]).read())
sys.exit(0 if ids == ['find', 'analyze', 'locate', 'connect', 'interact', 'debug', 'dev'] else 1)
PY

echo "== B2: Configs declares the two rows =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
probs = []
if sec.get('nav_mode') != 'horizontal_two_rows': probs.append('nav_mode = %r' % sec.get('nav_mode'))
groups = {g['id']: g for g in sec.get('groups', [])}
vis = lambda ids: [i for i in ids if not next(p for p in sec['pages'] if p['id'] == i).get('hidden')]
if vis(groups['fleet']['pages'])[:2] != ['bluetooth', 'c3']: probs.append('fleet row = %r' % vis(groups['fleet']['pages']))
grid_obs = [p['id'] for p in sec['pages'] if p.get('group') == 'Observability' and not p.get('hidden')]
if vis(groups['observability']['pages']) != grid_obs: probs.append('observability row = %r, grid = %r' % (vis(groups['observability']['pages']), grid_obs))
label = {p['id']: p['label'] for p in sec['pages']}
if label['c3'] != 'C3 Fleet Control': probs.append('c3 label = %r' % label['c3'])
flat = [p['id'] for p in sec['pages']]
seen = [i for g in sec['groups'] for i in g['pages']]
if sorted(flat) != sorted(seen): probs.append('pages not in exactly one group: %r' % sorted(set(flat) ^ set(seen)))
for g in sec['groups']:
    if not g.get('icon') or not g.get('label'): probs.append('group %s lacks an icon or label' % g['id'])
print('; '.join(probs) or 'OK')
PY
)" "horizontal_two_rows: Fleet = Bluetooth | C3 Fleet Control, Observability = its grid order (X-Ray | LogCat Permissions About); every page in one group"

echo "== B3: the page is the lib's, scroll wired =="
grep -q 'class BluetoothFragment : KitComposeFragment()' "$KT/configs/BluetoothFragment.kt" && ! grep -q 'override val reportsScroll.*false' "$KT/configs/BluetoothFragment.kt" \
  && ok "BluetoothFragment is a KitComposeFragment that reports its scroll to the shell" || bad "BluetoothFragment is not a scroll-reporting KitComposeFragment"
grep -q 'BluetoothPanel(controller, tab)' "$KT/configs/BluetoothFragment.kt" && grep -q 'BluetoothHub.controller' "$KT/configs/BluetoothFragment.kt" \
  && ok "the fragment draws BluetoothPanel over the shared BluetoothHub controller" || bad "the fragment does not draw the lib's panel"
grep -q 'LazyColumn' "$LK/BluetoothUi.kt" && ok "the panel body is a LazyColumn (a scroll the shell sees)" || bad "the panel body is not scrollable by the shell"
grep -q 'UiIcons.vector' "$LK/BluetoothUi.kt" && ! grep -q 'androidx.compose.material.icons' "$LK"/*.kt && ok "icons come from libs:ui-icons, none from material directly" || bad "an icon is not from libs:ui-icons"
for w in 'Path-loss exponent' 'Classic discovery' 'Export JSON' 'Export CSV' 'Beep: faster when closer' 'Raw hex write' 'Notify stream' 'Test payloads' 'Bluetooth ATT event log' 'Reload services' 'Request MTU' 'Signal floor' 'Subscribe all' 'Open Permissions' 'Bond'; do
  grep -rq "$w" "$LK" && ok "the page offers: $w" || bad "the page lacks: $w"
done

echo "== B4: the vocabulary is data =="
check "$(python3 - "$LIB/src/main/assets/bluetooth/gatt-catalog.json" "$LK/GattDecoders.kt" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1])); src = open(sys.argv[2]).read()
probs = []
def norm(u):
    u = u.lower()
    if re.fullmatch(r'[0-9a-f]{4}', u): return '0000%s-0000-1000-8000-00805f9b34fb' % u
    return u if re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', u) else None
for k in ('services', 'characteristics', 'descriptors'):
    us = [norm(r['uuid']) for r in d[k]]
    if None in us: probs.append('%s: invalid uuid' % k)
    if len(us) != len(set(us)): probs.append('%s: duplicate uuid' % k)
cats = {c['id'] for c in d['categories']}
probs += ['service %s has unknown category %s' % (s['name'], s['category']) for s in d['services'] if s['category'] not in cats]
for r in d['characteristics'] + d['descriptors']:
    dec = r.get('decoder')
    if dec and '"%s" ->' % dec not in src: probs.append('decoder %s is not implemented' % dec)
if not d['probes'] or not d['filters'] or d['filters'][0]['id'] != 'all': probs.append('filters / probes missing')
print('; '.join(probs) or 'OK')
PY
)" "the catalog parses, UUIDs are valid and unique, every decoder it names exists"
grep -rqE '"0000180d|"180d"|"2a37"' "$LK" && bad "a service / characteristic UUID is typed in Kotlin" || ok "no UUID table in Kotlin (the catalog is the only list)"

echo "== B5: permissions =="
M="$LIB/src/main/AndroidManifest.xml"
grep -q 'android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"' "$M" && grep -q 'android.permission.BLUETOOTH_CONNECT' "$M" \
  && grep -q 'ACCESS_FINE_LOCATION" android:maxSdkVersion="30"' "$M" && grep -q 'android.permission.BLUETOOTH" android:maxSdkVersion="30"' "$M" \
  && ok "manifest: SCAN (never for location) + CONNECT, legacy BLUETOOTH + location to API 30" || bad "the lib manifest permissions are wrong"
grep -q 'BlePermissions' "$LK/BluetoothUi.kt" && grep -q 'PermsLink.open' "$LK/BluetoothUi.kt" && ! grep -q 'RequestMultiplePermissions' "$LK/BluetoothUi.kt" && ok "the page sends missing permissions to the Permissions page (#1274), never asks itself" || bad "the page asks for permissions itself or never links to the Permissions page"

echo "== B6: registered like every shared lib =="
check "$(python3 - "$ROOT" <<'PY'
import json, os, sys
r = sys.argv[1]
probs = []
sa = json.load(open(r + '/aa_cloud-superapp/build.json'))
m = sa['modules'].get('libs:bluetooth')
if not m or m.get('dir') != '../ab_cloud-libs-shared/libs/bluetooth': probs.append('superapp module missing / wrong dir')
if 'libs:bluetooth' not in sa['modules']['app']['depends_on']: probs.append('superapp app does not depend on it')
la = json.load(open(r + '/ab_cloud-libs-shared/lib-apks/build.json'))['lib_apks']
if 'bluetooth' not in la['exclude']: probs.append('lib-apks: not excluded')
if 'bluetooth' not in la['static_libs']: probs.append('lib-apks: no description')
br = json.load(open(r + '/ac_cloud-browser/build.json'))
if 'libs:bluetooth' not in br['modules']: probs.append('browser module missing')
for f in ('aa_cloud-superapp/app/build.gradle', 'ac_cloud-browser/app/build.gradle', 'ab_cloud-libs-shared/libs/browser/build.gradle'):
    if "project(':libs:bluetooth')" not in open(os.path.join(r, f)).read(): probs.append(f + ' lacks the dependency')
for f in ('ship-cloud-superapp.yml', 'test-cloud-superapp.yml', 'ship-cloud-browser.yml'):
    for base in ('1_cicd/src/cicd/', '.github/workflows/'):
        if 'ab_cloud-libs-shared/libs/bluetooth/**' not in open(os.path.join(r, base + f)).read(): probs.append(base + f + ' does not watch the lib')
print('; '.join(probs) or 'OK')
PY
)" "module, lib-apks, gradle dependencies and ship / test path triggers name libs:bluetooth"

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
