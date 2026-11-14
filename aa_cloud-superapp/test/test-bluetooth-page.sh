#!/usr/bin/env bash
# Tester: #1221 Configs > Setup > Network > Bluetooth (was Fleet) over libs:bluetooth. Static (no device, no build); the rules themselves are JVM-tested in
# app/src/test/.../bluetooth/BluetoothLibTest.kt.
#
#   B1 the page is declared: Setup ▸ Network (moved from Fleet), five hidden tabs Find | Connect | Interact | Debug | Dev, every tab routed to BluetoothFragment
#   B2 Configs declares horizontal_two_rows with the Cloudy C3 row (C3 | Peer Control; Bluetooth moved to Setup ▸ Network) and Setup's Watchtower - Obsv sub-row (X-Ray Device, Logcat Fleet; was a top-level row, before that Agents,
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
for f in "$BJ" "$KT/configs/BluetoothFragment.kt" "$KT/launcher/SectionPages.kt" "$LIB/build.gradle" "$LIB/src/main/AndroidManifest.xml" "$LK/BluetoothUi.kt" "$LK/GattDecoders.kt" "$ROOT/data/fleet/gatt-catalog.json"; do
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
if (b.get('group'), b.get('subgroup')) != ('Network', '') or b.get('label') != 'Bluetooth' or b.get('hidden'): probs.append('page = %r' % b)   # #1737 a visible Network tile again
want = ['bluetooth-find', 'bluetooth-analyze', 'bluetooth-locate', 'bluetooth-connect', 'bluetooth-interact', 'bluetooth-debug', 'bluetooth-dev']
if b.get('tabs') != want: probs.append('tabs = %r' % b.get('tabs'))
for t, label in zip(want, ['Find', 'Analyze', 'Locate', 'Connect', 'Interact', 'Debug', 'Dev']):
    p = pages.get(t)
    if not p or p.get('label') != label or not p.get('hidden') or not p.get('icon'): probs.append('tab %s = %r' % (t, p))
print('; '.join(probs) or 'OK')
PY
)" "bluetooth: Setup ▸ Network (moved from Fleet), label Bluetooth, tabs Find | Analyze | Locate | Connect | Interact | Debug | Dev, each a hidden page with an icon"
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
if set(groups) != {'launcher', 'network', 'apps', 'connect', 'tools', 'configs'}: probs.append('groups = %r (want the six #1623 groups)' % sorted(groups))
# #1623 bluetooth and its tabs live in the Network group (no subgroup)
net_pages = set(groups.get('network', {}).get('pages', []))
if 'bluetooth' not in net_pages or not {'bluetooth-find', 'bluetooth-dev'} <= net_pages: probs.append('bluetooth and its tabs are not in the Network group')
for stale in ('device', 'setup', 'fleet', 'agents', 'observability'):
    if stale in groups: probs.append('%s is still a top-level group (#1623 made Configs the six flat groups)' % stale)
label = {p['id']: p['label'] for p in sec['pages']}
if label['c3'] != 'Cloudy C3': probs.append('c3 label = %r' % label['c3'])  # #1610 C3 moved to Setup ▸ Connect, relabelled Cloudy C3
if label['kde'] != 'Peer Control': probs.append('kde label = %r' % label['kde'])
icon = {p['id']: p.get('icon') for p in sec['pages']}
if icon['c3'] != 'ic_cloud_control': probs.append('c3 icon = %r' % icon['c3'])
if icon['c3'] in [p.get('icon') for p in sec['pages'] if p['id'] != 'c3']: probs.append('c3 icon collides with another Configs page')
cloud = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'cloud')
def icons(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == 'icon': yield v
            else: yield from icons(v)
    elif isinstance(o, list):
        for v in o: yield from icons(v)
# #1552 the Cloud ▸ Apps C3 tile shares the Fleet C3 glyph on purpose; ic_cloud_control may appear in Cloud ONLY on a C3
# entry (id/label C3). Any other Cloud node wearing it is still a collision.
def nodes(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from nodes(v)
    elif isinstance(o, list):
        for v in o: yield from nodes(v)
stray = [n for n in nodes(cloud) if n.get('icon') == 'ic_cloud_control' and n.get('id') != 'c3' and n.get('label') != 'C3']
if stray: probs.append('ic_cloud_control used by a non-C3 Cloud node: %r' % [n.get('id') or n.get('label') for n in stray])
import os  # #1610 Peer Control joined Setup ▸ Connect (Account, Cloudy C3, Peer Control); the old Fleet-group divider was dropped
if not os.path.exists(os.path.join(os.path.dirname(sys.argv[1]), 'app/src/main/res/drawable/ic_cloud_control.xml')): probs.append('ic_cloud_control drawable missing')
flat = [p['id'] for p in sec['pages']]
seen = [i for g in sec['groups'] for i in g['pages']]
if sorted(flat) != sorted(seen): probs.append('pages not in exactly one group: %r' % sorted(set(flat) ^ set(seen)))
for g in sec['groups']:
    if not g.get('icon') or not g.get('label'): probs.append('group %s lacks an icon or label' % g['id'])
print('; '.join(probs) or 'OK')
PY
)" "horizontal_two_rows with groups device + setup (#1610): C3 (label Cloudy C3, icon ic_cloud_control) and Peer Control in Setup ▸ Connect, Bluetooth and its tabs in the Device row; every page in exactly one group"

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
check "$(python3 - "$ROOT/data/fleet/gatt-catalog.json" "$LK/GattDecoders.kt" <<'PY'
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
