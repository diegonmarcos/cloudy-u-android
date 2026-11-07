#!/usr/bin/env bash
# Tester: the 2026-11 Configs restructure (a REORGANISE: pages moved, renamed or split, none deleted).
#
#   R1 Setup ▸ Launcher reads Quick Actions, One-Hand, Folders, Notify | Perms, Logcat, X-Ray App, About (the | is a separator
#      page right after Notify); the Permissions page is labelled Perms and keeps its id and fragment
#   R2 the app Logcat (page logcat) is single-app (this app's uid, polled online) and its FILTERS (level, tag, search, autoscroll)
#      and ACTIONS (clear, pause/resume, copy, export, share) are two separate blocks
#   R3 X-Ray App (page xray-app) is the same DevControlFragment under APP_XRAY_MACROS, which names app groups only: no device-wide
#      group (Device, Security, Network, API, Mesh, Dev Tools, Webserver, Diagnostics, Export All, Fleet agents)
#   R4 Setup ▸ Watchtower - Obsv (was Observability) holds X-Ray Device (the unfiltered X-Ray, all 13 macro groups) and Logcat
#      Fleet (multi-select app picker, per-app view, export / share / copy / post)
#   R5 Agents has Watchdog only; watchtower and agents-watchtower are page_aliases of a DECLARED page (never a 404) and
#      agents-watchtower is retired; Watchdog's hub shows Network and Watchtower - Obsv
#   R6 Bluetooth and its seven tabs are under Setup ▸ Network (page and group row), none under Cloudy C3
#   R7 the Fleet group is Cloudy C3 (group id fleet) with C3 and Peer Control
#   R8 COVERAGE: every config page id of the tree before the restructure is still declared, retired or removed; every alias and
#      retirement lands on a declared page; every declared page is in exactly one group
# Static (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }
BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
PAGES="$KT/launcher/SectionPages.kt"
DEV="$KT/devcontrol/DevControlFragment.kt"
LOG="$KT/devcontrol/LogcatFragment.kt"
FLEET="$KT/devcontrol/FleetLogcatFragment.kt"
for f in "$BJ" "$PAGES" "$DEV" "$LOG" "$FLEET" "$KT/devcontrol/LogcatModel.kt"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }; done

echo "== R1: Setup ▸ Launcher = ... Notify | Perms, Logcat, X-Ray App, About =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
run = [p for p in sec['pages'] if p.get('group') == 'Setup' and p.get('subgroup') == 'Launcher']
probs = []
if [p['id'] for p in run] != ['controls', 'onehand', 'app-folders', 'notify', 'launcher-sep-1', 'perms', 'logcat', 'xray-app', 'app-about']:
    probs.append('Launcher = %r' % [p['id'] for p in run])
sep = by.get('launcher-sep-1', {})
if not sep.get('separator') or sep.get('label') != '|': probs.append('launcher-sep-1 is not a "|" separator page: %r' % sep)
if [p['label'] for p in run if not p.get('separator')][4:] != ['Perms', 'Logcat', 'X-Ray App', 'About']: probs.append('labels = %r' % [p['label'] for p in run])
if by['perms'].get('label') != 'Perms' or by['perms'].get('icon') != 'ic_key': probs.append('perms = %r' % by['perms'])
if any(p.get('hidden') for p in run): probs.append('a Launcher page is hidden')
print('; '.join(probs) or 'OK')
PY
)" "Launcher order and labels; the separator is a | page; Perms keeps id perms and its icon"
grep -qE 'pageId == "perms" ->' "$PAGES" && grep -A1 'pageId == "perms" ->' "$PAGES" | grep -q 'PermissionsFragment' \
  && ok "page:config/perms still opens PermissionsFragment (label-only rename, nothing removed)" || bad "perms no longer routes to PermissionsFragment"

echo "== R2: the app Logcat is single-app, filters apart from actions =="
python3 - "$LOG" <<'PY' && ok "AppLogcatScreen: own uid only, polled online while RESUMED; filters block and actions block are separate, each holding its own controls" || bad "AppLogcatScreen is not a single-app logcat with separate filters and actions blocks"
import re, sys
src = open(sys.argv[1]).read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S); src = re.sub(r'(?m)^\s*//.*$', '', src)
assert 'AboutLogcat.SCOPE_THIS_APP' in src and 'SCOPE_ALL_APPS' not in src and 'installedApps' not in src, 'not single-app'
assert 'repeatOnLifecycle(Lifecycle.State.RESUMED)' in src and 'delay(' in src, 'not online'
i_f = src.index('testTag("logcat:filters")'); i_a = src.index('testTag("logcat:actions")')
assert i_f < i_a, 'order'
filters, actions = src[i_f:i_a], src[i_a:]
for need in ('logcat-level', 'logcat:filter:tag', 'logcat:filter:search', 'logcat:filter:autoscroll'): assert need in filters, need
for need in ('logcat:act:clear', 'logcat:act:pause', 'logcat:act:copy', 'logcat:act:export', 'logcat:act:share'): assert need in actions, need
for bad in ('logcat:act:',): assert bad not in filters, 'an action sits in the filters block'
for bad in ('logcat:filter:', 'KitFilterChips', 'OutlinedTextField'): assert bad not in actions.split('LogPane(')[0], 'a filter sits in the actions block'
PY
grep -q 'LogcatModel.filter(' "$LOG" && grep -q 'fun filter(' "$KT/devcontrol/LogcatModel.kt" && ok "the Filters run through LogcatModel.filter (unit-tested in LogcatPagesTest)" || bad "no LogcatModel.filter"

echo "== R3: X-Ray App is app-scoped =="
python3 - "$DEV" "$PAGES" <<'PY' && ok "APP_XRAY_MACROS names app groups only (App & Build, Repo & Releases, app Resources sections, SuperApp agents); xray-app routes to it; About keeps ABOUT_MACROS" || bad "X-Ray App is not app-scoped"
import re, sys
dev = open(sys.argv[1]).read(); pages = open(sys.argv[2]).read()
m = re.search(r'val APP_XRAY_MACROS = arrayListOf\((.*?)\n        \)', dev, re.S)
assert m, 'APP_XRAY_MACROS missing'
entries = re.findall(r'"([^"]+)"', m.group(1))
groups = {e.split('/')[0] for e in entries}
declared = set(re.findall(r'macro\("[^A-Za-z"]*([A-Z][A-Z &]+[A-Z])"\)', dev))
assert groups <= declared, 'unknown group(s) %r' % (groups - declared)
device_wide = {'DEVICE', 'SECURITY', 'NETWORK', 'API', 'MESH', 'DEV TOOLS', 'WEBSERVER', 'DIAGNOSTICS', 'EXPORT ALL'}
assert not (groups & device_wide), 'device-wide group(s) %r' % (groups & device_wide)
assert 'AGENTS/SuperApp agents' in entries and 'AGENTS' not in entries, 'Fleet agents must stay out'
assert 'APP & BUILD' in entries and 'REPO & RELEASES' in entries
assert all('/' in e for e in entries if e.split('/')[0] in ('RESOURCES', 'AGENTS')), 'a mixed group is not narrowed to sections'
assert re.search(r'pageId == "xray-app" ->\s*\n?\s*com\.diegonmarcos\.superapp\.devcontrol\.DevControlFragment\.newInstance\(label, [^)]*APP_XRAY_MACROS\)', pages), 'route'
assert 'ABOUT_MACROS = arrayListOf("APP & BUILD", "REPO & RELEASES")' in dev
PY

echo "== R4: Watchtower - Obsv = X-Ray Device (unfiltered) + Logcat Fleet =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
obsv = [p for p in sec['pages'] if p.get('subgroup') == 'Watchtower - Obsv' and not p.get('hidden')]
probs = []
if [(p['id'], p['label']) for p in obsv] != [('xray', 'X-Ray Device'), ('logcat-fleet', 'Logcat Fleet')]: probs.append('Watchtower - Obsv = %r' % [(p['id'], p['label']) for p in obsv])
if any(p.get('subgroup') == 'Observability' for p in sec['pages']): probs.append('a page still wears the subgroup Observability')
if by['battery'].get('subgroup') != 'Watchtower - Obsv': probs.append('battery left the subgroup')
print('; '.join(probs) or 'OK')
PY
)" "the subgroup was renamed (no Observability left) and holds X-Ray Device and Logcat Fleet"
python3 - "$DEV" "$PAGES" <<'PY' && ok "X-Ray Device is DevControlFragment.newInstance(label) with no filter and all 13 macro groups are still declared" || bad "X-Ray Device lost its macro groups or got a filter"
import re, sys
dev = open(sys.argv[1]).read(); pages = open(sys.argv[2]).read()
assert re.search(r'pageId == "xray" ->\s*\n?\s*com\.diegonmarcos\.superapp\.devcontrol\.DevControlFragment\.newInstance\(label\)\n', pages)
macros = re.findall(r'^\s+macro\("([^"]+)"\)', dev, re.M)
assert len(macros) == 13, '%d macro groups: %r' % (len(macros), macros)
PY
grep -qE 'pageId == "logcat-fleet" +-> .*FleetLogcatFragment' "$PAGES" && ok "config/logcat-fleet routes to FleetLogcatFragment" || bad "logcat-fleet is not routed"
python3 - "$FLEET" <<'PY' && ok "Logcat Fleet: multi-select app picker (checkboxes), fleet shortcut, per-app view, export / copy / share / post c3-api, reader mechanism with the one-time grant" || bad "Logcat Fleet lacks the picker, per-app view, export or the reader mechanism"
import re, sys
src = open(sys.argv[1]).read()
for need in ('Checkbox(', 'logfleet:pick', 'logfleet:fleet', 'logfleet:view:', 'logfleet:act:export', 'logfleet:act:share', 'logfleet:act:copy',
             'logfleet:act:post', 'logfleet:grant', 'logfleet:reader', 'FleetLogcat.read(', 'LogcatModel.combine('):
    assert need in src, need
PY

echo "== R5: Agents has Watchdog only; the old Watchtower ids resolve =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
probs = []
if [p['id'] for p in sec['pages'] if p.get('group') == 'Agents'] != ['agents-watchdog']: probs.append('Agents = %r' % [p['id'] for p in sec['pages'] if p.get('group') == 'Agents'])
if 'agents-watchtower' in by: probs.append('agents-watchtower is still declared')
al = sec['page_aliases']
for old in ('watchtower', 'agents-watchtower'):
    if al.get(old) not in by: probs.append('alias %s -> %r is not a declared page (a 404)' % (old, al.get(old)))
if 'agents-watchtower' not in sec['retired_pages']: probs.append('agents-watchtower is not in retired_pages')
if by['agents-watchdog'].get('hub_sections') != ['Network', 'Watchtower - Obsv']: probs.append('Watchdog hub = %r' % by['agents-watchdog'].get('hub_sections'))
if al.get('watchdog') != 'agents-watchdog': probs.append('the watchdog alias changed')
print('; '.join(probs) or 'OK')
PY
)" "Watchdog alone in Agents; watchtower and agents-watchtower alias to a declared page; retired; Watchdog's hub reads Network + Watchtower - Obsv"

echo "== R6: Bluetooth is under Setup ▸ Network =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
net = [p['id'] for p in sec['pages'] if p.get('subgroup') == 'Network' and not p.get('hidden')]
tabs = by['bluetooth']['tabs']
probs = []
if (by['bluetooth'].get('group'), by['bluetooth'].get('subgroup')) != ('Setup', 'Network'): probs.append('bluetooth is under %r' % ((by['bluetooth'].get('group'), by['bluetooth'].get('subgroup')),))
if net[-2:] != ['wifi', 'bluetooth']: probs.append('Network tail = %r' % net[-2:])
setup = next(g for g in sec['groups'] if g['id'] == 'setup')['pages']; fleet = next(g for g in sec['groups'] if g['id'] == 'fleet')['pages']
if not set(['bluetooth'] + tabs) <= set(setup): probs.append('Setup row lacks bluetooth pages')
if any(i.startswith('bluetooth') for i in fleet): probs.append('Cloudy C3 row still lists bluetooth pages %r' % [i for i in fleet if i.startswith('bluetooth')])
if len(tabs) != 7: probs.append('%d tabs' % len(tabs))
print('; '.join(probs) or 'OK')
PY
)" "bluetooth and its seven tabs belong to Setup ▸ Network and the Setup row, none to Cloudy C3"

echo "== R7: the Fleet group is Cloudy C3 =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
g = next(g for g in sec['groups'] if g['id'] == 'fleet')
probs = []
if g['label'] != 'Cloudy C3' or g['pages'] != ['c3', 'kde']: probs.append('group = %r' % g)
if [p['label'] for p in sec['pages'] if p.get('group') == 'Cloudy C3'] != ['C3', 'Peer Control']: probs.append('pages = %r' % [p['label'] for p in sec['pages'] if p.get('group') == 'Cloudy C3'])
if any(p.get('group') == 'Fleet' for p in sec['pages']): probs.append('a page still wears the group Fleet')
print('; '.join(probs) or 'OK')
PY
)" "group fleet is labelled Cloudy C3 and holds C3 and Peer Control; no page wears Fleet"

echo "== R8: coverage — nothing was dropped =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
BEFORE = """profile store controls onehand app-folders notify firewall dns webserver wg apps-access adb-shell wifi xray logcat perms app-about
agents-watchdog agents-watchtower c3 kde bluetooth presets panels panels-ui panels-tools battery bluetooth-find bluetooth-analyze
bluetooth-locate bluetooth-connect bluetooth-interact bluetooth-debug bluetooth-dev wifi-scan wifi-channels wifi-link wifi-tools wifi-log
store-cloud store-phone update kde_connect animations""".split()
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
now = {p['id'] for p in sec['pages']}
probs = []
lost = [i for i in BEFORE if i not in now and i not in sec['retired_pages'] and i not in sec['removed_pages']]
if lost: probs.append('LOST %r' % lost)
for old, to in sec['page_aliases'].items():
    if to not in now: probs.append('alias %s -> %s is not declared' % (old, to))
for old, succ in sec['retired_pages'].items():
    for s in succ:
        if s not in now: probs.append('retired %s -> %s is not declared' % (old, s))
    if old in now: probs.append('retired %s is still declared' % old)
seen = {}
for g in sec['groups']:
    for i in g['pages']:
        seen.setdefault(i, []).append(g['id'])
        if i not in now: probs.append('group %s names undeclared %s' % (g['id'], i))
for i in now:
    if len(seen.get(i, [])) != 1: probs.append('%s is in groups %r' % (i, seen.get(i, [])))
print('; '.join(probs) or 'OK')
PY
)" "all 44 pre-restructure page ids are declared, retired or removed; every alias and retirement lands on a declared page; each page is in exactly one group"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
