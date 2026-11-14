#!/usr/bin/env bash
# Tester: the 2026-11 Configs restructure into ONE page with SIX top-level groups (#1623, which REVERSES #1610's Device/Setup
# split). A REORGANISE (pages moved, renamed, split or added as placeholders — none deleted). The six groups are the config
# section's horizontal_two_rows groups: Launcher, Network, Apps, Connect, Tools, Configs.
#
#   R1 Launcher = Quick Actions, UI, Folders, One-Hand | Notify (the | is a separator page before Notify); the Perms page keeps
#      its id `perms`, its icon and its PermissionsFragment route (it lives in the Configs group, label unchanged)
#   R2 the app Logcat (page logcat) is single-app (this app's uid, polled online) and its FILTERS (level, tag, search, autoscroll)
#      and ACTIONS (clear, pause/resume, copy, export, share) are two separate blocks
#   R3 X-Ray App (page xray-app) is the same DevControlFragment under APP_XRAY_MACROS, which names app groups only: no device-wide
#      group (Device, Security, Network, API, Mesh, Dev Tools, Webserver, Diagnostics, Export All, Fleet agents)
#   R4 Tools = Watchdog | X-Ray Device, Logcat Fleet, Agents (the | is a separator); Configs = Perms, Logcat, X-Ray App, About;
#      X-Ray Device is the unfiltered X-Ray (all 14 macro groups, #1649 added ENGINE PROFILES); Logcat Fleet is the multi-select fleet reader; Battery stays
#      a hidden Configs-group page (page:config/battery)
#   R5 the Agents hub is the page agents-watchdog, relabelled Agents, in the Tools group, hub_sections = [Network]; watchtower and
#      agents-watchtower are page_aliases of a DECLARED page (never a 404) and agents-watchtower is retired; watchdog alias lands on it
#   R6 Network = Firewall, DNS, Webserver, Cloud Mesh, Apps Access, ADB Shell, WiFi, Bluetooth; Bluetooth and its seven tabs
#      belong to the Network group
#   R7 Connect = Account, Cloudy C3, Peer Control; Apps = Store; Tools holds Watchdog (the c3-watchdog launch page →
#      extapp:c3-watchdog); exactly six groups launcher/network/apps/connect/tools/configs, and no page wears Device/Setup/Fleet
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

echo "== R1: Launcher = Quick Actions, UI, Folders, One-Hand | Notify; Perms kept its id, icon and route =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
def run(g):
    return [p['id'] for p in sec['pages'] if p.get('group') == g and not p.get('hidden') and not p.get('separator')]
probs = []
if run('Launcher') != ['controls', 'panels-ui', 'app-folders', 'onehand', 'notify']: probs.append('Launcher tiles = %r' % run('Launcher'))
if [by[i]['label'] for i in ('controls', 'panels-ui', 'app-folders', 'onehand', 'notify')] != ['Quick Actions', 'UI', 'Folders', 'One-Hand', 'Notify']: probs.append('Launcher labels drifted')
full = [p['id'] for p in sec['pages'] if p.get('group') == 'Launcher' and not p.get('hidden')]
if full != ['controls', 'panels-ui', 'app-folders', 'onehand', 'launcher-sep-1', 'notify']: probs.append('Launcher order (with |) = %r' % full)
if by['perms'].get('label') != 'Perms' or by['perms'].get('icon') != 'ic_key': probs.append('perms = %r' % by['perms'])
if (by['perms'].get('group'), by['perms'].get('subgroup')) != ('Configs', ''): probs.append('perms not under the Configs group')
print('; '.join(probs) or 'OK')
PY
)" "Launcher = Quick Actions, UI, Folders, One-Hand | Notify (| before Notify); Perms keeps id perms and ic_key, in the Configs group"
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

echo "== R4: Tools = Watchdog | X-Ray Device, Logcat Fleet, Agents; Configs = Perms, Logcat, X-Ray App, About =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
tools = [p for p in sec['pages'] if p.get('group') == 'Tools' and not p.get('hidden')]
cfg = [p for p in sec['pages'] if p.get('group') == 'Configs' and not p.get('hidden') and not p.get('is_action')]
probs = []
want_tools = [('c3-watchdog', 'Watchdog'), ('tools-sep-1', '|'), ('xray', 'X-Ray Device'), ('logcat-fleet', 'Logcat Fleet'), ('agents-watchdog', 'Agents')]
if [(p['id'], p['label']) for p in tools] != want_tools:
    probs.append('Tools = %r' % [(p['id'], p['label']) for p in tools])
want_cfg = [('perms', 'Perms'), ('logcat', 'Logcat'), ('xray-app', 'X-Ray App'), ('app-about', 'About')]
if [(p['id'], p['label']) for p in cfg] != want_cfg:
    probs.append('Configs = %r' % [(p['id'], p['label']) for p in cfg])
for sid in ('launcher-sep-1', 'tools-sep-1'):
    sep = by.get(sid, {})
    if not sep.get('separator') or sep.get('label') != '|': probs.append('%s is not a "|" separator page: %r' % (sid, sep))
if any(p.get('subgroup') == 'Watchtower - Obsv' for p in sec['pages']): probs.append('a page still wears the retired subgroup Watchtower - Obsv')
if (by['battery'].get('group'), by['battery'].get('subgroup')) != ('Configs', '') or not by['battery'].get('hidden'): probs.append('battery left the Configs group or stopped being hidden')
print('; '.join(probs) or 'OK')
PY
)" "Tools holds Watchdog, |, X-Ray Device, Logcat Fleet, Agents; Configs holds Perms, Logcat, X-Ray App, About; Battery is hidden in the Configs group; no Watchtower - Obsv subgroup remains"
python3 - "$DEV" "$PAGES" <<'PY' && ok "X-Ray Device is DevControlFragment.newInstance(label) with no filter and all 14 macro groups are still declared" || bad "X-Ray Device lost its macro groups or got a filter"
import re, sys
dev = open(sys.argv[1]).read(); pages = open(sys.argv[2]).read()
assert re.search(r'pageId == "xray" ->\s*\n?\s*com\.diegonmarcos\.superapp\.devcontrol\.DevControlFragment\.newInstance\(label\)\n', pages)
macros = re.findall(r'^\s+macro\("([^"]+)"\)', dev, re.M)
assert len(macros) == 14, '%d macro groups: %r' % (len(macros), macros)
PY
grep -qE 'pageId == "logcat-fleet" +-> .*FleetLogcatFragment' "$PAGES" && ok "config/logcat-fleet routes to FleetLogcatFragment" || bad "logcat-fleet is not routed"
python3 - "$FLEET" <<'PY' && ok "Logcat Fleet: multi-select app picker (checkboxes), fleet shortcut, per-app view, export / copy / share / post c3-api, reader mechanism with the one-time grant" || bad "Logcat Fleet lacks the picker, per-app view, export or the reader mechanism"
import re, sys
src = open(sys.argv[1]).read()
for need in ('Checkbox(', 'logfleet:pick', 'logfleet:fleet', 'logfleet:view:', 'logfleet:act:export', 'logfleet:act:share', 'logfleet:act:copy',
             'logfleet:act:post', 'logfleet:grant', 'logfleet:reader', 'FleetLogcat.read(', 'LogcatModel.combine('):
    assert need in src, need
PY

echo "== R5: the Agents hub (agents-watchdog, relabelled Agents); the old Watchtower ids resolve =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
probs = []
w = by.get('agents-watchdog', {})
if w.get('label') != 'Agents': probs.append('agents-watchdog label = %r (want Agents)' % w.get('label'))
if (w.get('group'), w.get('subgroup')) != ('Tools', ''): probs.append('agents-watchdog not in the Tools group')
if w.get('hub_sections') != ['Network']: probs.append('Agents hub = %r (want [Network])' % w.get('hub_sections'))
if 'agents-watchtower' in by: probs.append('agents-watchtower is still declared')
al = sec['page_aliases']
for old in ('watchtower', 'agents-watchtower'):
    if al.get(old) not in by: probs.append('alias %s -> %r is not a declared page (a 404)' % (old, al.get(old)))
if 'agents-watchtower' not in sec['retired_pages']: probs.append('agents-watchtower is not in retired_pages')
if al.get('watchdog') != 'agents-watchdog': probs.append('the watchdog alias changed')
print('; '.join(probs) or 'OK')
PY
)" "agents-watchdog is the Agents hub in the Tools group, hub_sections [Network]; watchtower and agents-watchtower alias to a declared page; retired; the watchdog alias is unchanged"

echo "== R6: Network = Firewall, DNS, Webserver, Cloud Mesh, Apps Access, ADB Shell, WiFi, Bluetooth =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
net = [p['id'] for p in sec['pages'] if p.get('group') == 'Network' and p['id'] in ('ng-firewall','wifi','bluetooth','dns','wg','firewall','os-perms','webserver','proxy','apps-access','adb-shell')]   # #1737 declared order: individual icons plus the NG Firewall hub, OS Perms and Proxy
tabs = by['bluetooth']['tabs']
probs = []
if net != ['ng-firewall', 'wifi', 'bluetooth', 'dns', 'wg', 'firewall', 'os-perms', 'webserver', 'proxy', 'apps-access', 'adb-shell']: probs.append('Network = %r' % net)
network = next(g for g in sec['groups'] if g['id'] == 'network')['pages']
others = [i for g in sec['groups'] if g['id'] != 'network' for i in g['pages']]
if not set(['bluetooth'] + tabs) <= set(network): probs.append('network group lacks bluetooth pages')
if any(i.startswith('bluetooth') for i in others): probs.append('another group still lists bluetooth pages %r' % [i for i in others if i.startswith('bluetooth')])
if len(tabs) != 7: probs.append('%d tabs' % len(tabs))
print('; '.join(probs) or 'OK')
PY
)" "Network reads Firewall … Bluetooth; bluetooth and its seven tabs belong to the network group"

echo "== R7: Connect / Apps / Tools; six groups launcher/network/apps/connect/tools/configs =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
def run(g):
    return [p['label'] for p in sec['pages'] if p.get('group') == g and not p.get('hidden') and not p.get('separator') and not p.get('is_action')]
probs = []
gids = [g['id'] for g in sec['groups']]
if gids != ['launcher', 'network', 'apps', 'connect', 'tools', 'configs']: probs.append('group ids = %r (want the six #1623 groups)' % gids)
if run('Connect') != ['Account', 'Cloudy C3', 'Peer Control']: probs.append('Connect = %r' % run('Connect'))
if run('Apps') != ['Store']: probs.append('Apps = %r' % run('Apps'))
if 'Watchdog' not in run('Tools'): probs.append('Tools lacks Watchdog: %r' % run('Tools'))
cw = by.get('c3-watchdog', {})
if cw.get('action') != 'extapp:c3-watchdog': probs.append('c3-watchdog action = %r' % cw.get('action'))
if by['c3'].get('action') != 'extapp:cloud-c3': probs.append('Cloudy C3 lost its extapp target')
for stale in ('Device', 'Setup', 'Agents', 'Cloudy C3', 'Fleet'):
    if any(p.get('group') == stale for p in sec['pages']): probs.append('a page still wears the group %r' % stale)
print('; '.join(probs) or 'OK')
PY
)" "six groups launcher/network/apps/connect/tools/configs; Connect = Account, Cloudy C3, Peer Control; Apps = Store; Tools holds Watchdog (c3-watchdog → extapp:c3-watchdog); no page wears Device/Setup/Fleet as a group"

echo "== R8: coverage — nothing was dropped =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
BEFORE = """profile store controls onehand app-folders notify firewall dns webserver wg apps-access adb-shell wifi xray logcat perms app-about
agents-watchdog agents-watchtower c3 kde bluetooth presets panels panels-ui panels-tools battery bluetooth-find bluetooth-analyze
bluetooth-locate bluetooth-connect bluetooth-interact bluetooth-debug bluetooth-dev wifi-scan wifi-channels wifi-link wifi-tools wifi-log
store-cloud store-phone update kde_connect animations xray-app logcat-fleet launcher-sep-1 adb-status adb-term adb-perms adb-all panels-all panels-battery focus""".split()
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
)" "every pre-restructure page id is declared, retired or removed; every alias and retirement lands on a declared page; each page is in exactly one group"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
