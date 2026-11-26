#!/usr/bin/env bash
# Tester (#1929): the Setup group's Quick Actions and UI tiles are ONE Launcher tile (a MERGE, rule 16: nothing removed), per surface.
#   L1  Cloud quickmarks (ui.sections[cloud].tile_groups[Setup]): the row reads Launcher | NG Firewall | Store, Account | C3 | Profile, About; the Launcher tile
#       opens page:config/launcher-hub and carries the old tile ids (configs-controls, configs-ui) as aliases; no tile keeps an old id.
#   L2  Quick Actions (page:config/controls) and UI (page:config/panels-ui) stay visible Configs pages of the Launcher group the Launcher tile opens.
#   L3  Phone quickmarks (phone_app_groups[Setup]): one Launcher folder holding every package of the two old folders, aliased to their labels.
# Each is then mutated and must turn red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
bj0 = json.load(open(sys.argv[1] + '/aa_cloud-superapp/build.json'))
sec = lambda bj, i: [s for s in bj['ui']['sections'] if s['id'] == i][0]
ROW = ['Launcher', '|', 'NG Firewall', '|', 'Store', 'Account', '|', 'C3', '|', 'Profile', 'About']
PKGS = ['com.android.settings', 'com.samsung.android.oneconnect', 'com.samsung.android.goodlock']

def check(n, bj):
    f = []
    if n == 'L1':
        g = [x for x in sec(bj, 'cloud')['tile_groups'] if x['title'] == 'Setup'][0]['tiles']
        if [t['label'] for t in g] != ROW: f.append('row %s' % [t['label'] for t in g])
        l = [t for t in g if t['label'] == 'Launcher']
        if not l: return f + ['no Launcher tile']
        if l[0].get('target') != 'page:config/launcher-hub': f.append('Launcher target %r' % l[0].get('target'))
        if sorted(l[0].get('aliases', [])) != ['configs-controls', 'configs-ui']: f.append('aliases %r' % l[0].get('aliases'))
        if {'configs-controls', 'configs-ui'} & {t.get('id') for t in g}: f.append('an old tile id is still a tile')
    elif n == 'L2':
        pages = {p['id']: p for p in sec(bj, 'config')['pages']}
        hub = pages.get('launcher-hub', {})
        if hub.get('hub_sections') != ['Launcher', 'Configs'] or hub.get('group') != 'Launcher': f.append('launcher-hub is not the Launcher hub')
        for pid, label in (('controls', 'Quick Actions'), ('panels-ui', 'UI')):
            p = pages.get(pid)
            if not p or p.get('hidden') or p.get('group') != 'Launcher' or p.get('label') != label: f.append('%s (%s) is not a visible Launcher page' % (label, pid))
    elif n == 'L3':
        g = [x for x in sec(bj, 'phone')['phone_app_groups'] if x['title'] == 'Setup'][0]['folders']
        labs = [x['label'] for x in g]
        if labs != ['Launcher', 'NG Firewall', 'Store', 'C3', 'About']: f.append('folders %s' % labs)
        l = [x for x in g if x['label'] == 'Launcher']
        if l:
            if [p['pkg'] for p in l[0]['packages']] != PKGS: f.append('Launcher packages %s' % [p['pkg'] for p in l[0]['packages']])
            if sorted(l[0].get('aliases', [])) != ['Quick Actions', 'UI']: f.append('Launcher aliases %r' % l[0].get('aliases'))
    return f

fails = 0
for n, t in (('L1', 'Cloud Setup row and Launcher tile'), ('L2', 'Quick Actions and UI reachable from the Launcher group'), ('L3', 'Phone Setup Launcher folder')):
    r = check(n, bj0); print('  %s: %s %s' % ('PASS' if not r else 'FAIL', n, t + ('' if not r else ' -> ' + '; '.join(r)))); fails += bool(r)
print('== mutants: each must turn its surface red ==')
tile = lambda bj: [t for x in sec(bj, 'cloud')['tile_groups'] if x['title'] == 'Setup' for t in x['tiles'] if t['label'] == 'Launcher'][0]
pf = lambda bj: [x for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'Setup' for x in g['folders'] if x['label'] == 'Launcher'][0]
page = lambda bj, i: [p for p in sec(bj, 'config')['pages'] if p['id'] == i][0]
def mut(name, s, fn):
    global fails
    bj = copy.deepcopy(bj0); fn(bj)
    red = bool(check(s, bj)); print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
mut('Launcher tile loses its aliases', 'L1', lambda b: tile(b).pop('aliases'))
mut('Launcher tile opens Quick Actions only', 'L1', lambda b: tile(b).__setitem__('target', 'page:config/controls'))
mut('the old Quick Actions tile returns', 'L1', lambda b: tile(b).__setitem__('label', 'Quick Actions'))
mut('UI leaves the Launcher group', 'L2', lambda b: page(b, 'panels-ui').__setitem__('group', 'Display'))
mut('Quick Actions goes hidden', 'L2', lambda b: page(b, 'controls').__setitem__('hidden', True))
mut('the hub stops listing the Launcher group', 'L2', lambda b: page(b, 'launcher-hub').__setitem__('hub_sections', []))
mut('Good Lock is dropped from the phone Launcher folder', 'L3', lambda b: pf(b)['packages'].pop())
mut('the phone Launcher folder loses its aliases', 'L3', lambda b: pf(b).pop('aliases'))
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
