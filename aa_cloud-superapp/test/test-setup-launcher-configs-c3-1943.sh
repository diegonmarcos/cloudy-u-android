#!/usr/bin/env bash
# Tester (#1943): Setup page reorganised, nothing removed (rule 16, REORGANISE = new display, no entry lost).
#   P1  parity: every Setup + Launcher entry of the previous layout (listed below, with label, icon and action) is still declared and
#       reachable at its NEW place: Launcher group -> Setup Launcher row; Connect + Profiling -> Setup C3 section; Configs group -> the
#       Launcher page's Configs section (derived exactly as Sections.hubSections derives it); Actions row unchanged on Setup.
#   P2  the Setup Connect section is named C3 and reads Cloudy C3 | Peer Control (label unchanged; #1967 reverted the interim Fleet Control) | Watchdog, X-Ray Device, Logcat Fleet; no Profiling
#       group or heading remains; groups[] carries the same pages.
#   P4  the Home Apps Configs row, Canopus arc and Sirius tree (all list Section.pages) still list every page they did before: the Configs pages are
#       declared `hidden_on: [setup]` (only the Setup grid skips them), never `hidden`.
#   P3  the Launcher row reads ... | Notify | Configs, and Configs opens the Launcher page (redirect to config/launcher-hub); the Launcher page
#       has two sections, Launcher and Configs; the Setup page no longer draws the Configs group.
# Each is then mutated and must turn red. Static, no device, no build.   bash aa_cloud-superapp/test/test-setup-launcher-configs-c3-1943.sh
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
bj0 = json.load(open(sys.argv[1] + '/aa_cloud-superapp/build.json'))
sec = lambda bj: [s for s in bj['ui']['sections'] if s['id'] == 'config'][0]
# (id, label, old group, action, icon) of every Setup entry before #1943 (non-action pages; separators have no label word)
BEFORE = [
    ('launcher-hub', 'Launcher', 'Launcher', '', 'ic_apps'), ('launcher-sep-0', '|', 'Launcher', '', ''),
    ('controls', 'Quick Actions', 'Launcher', '', 'ic_home'), ('panels-ui', 'UI', 'Launcher', '', 'ic_ctl_brightness'),
    ('app-folders', 'Folders', 'Launcher', '', 'ic_p_folder'), ('onehand', 'One-Hand', 'Launcher', '', 'ic_onehand'),
    ('launcher-sep-1', '|', 'Launcher', '', ''), ('notify', 'Notify', 'Launcher', '', 'ic_rss'),
    ('ng-firewall', 'NG Firewall', 'Network', '', 'ic_act_shield'), ('wifi', 'Wi-Fi', 'Network', '', 'ic_wifi'),
    ('bluetooth', 'Bluetooth', 'Network', '', 'ic_bluetooth'), ('dns', 'DNS', 'Network', '', 'ic_world'),
    ('wg', 'VPN', 'Network', 'section:wg', 'ic_wg'), ('firewall', 'Firewall', 'Network', '', 'ic_lock'),
    ('os-perms', 'OS Perms', 'Network', 'action:open_os_perms', 'ic_act_key'), ('webserver', 'Webserver', 'Network', 'action:open_webserver', 'ic_web'),
    ('proxy', 'Proxy', 'Network', 'action:open_proxy_settings', 'ic_ctl_mobile_data'),
    ('store', 'Store', 'Apps', 'extapp:cloud-store', 'ic_storefront'), ('profile', 'Account', 'Apps', 'extapp:cloud-account', 'ic_person'),
    ('c3', 'Cloudy C3', 'Connect', 'extapp:cloud-c3', 'ic_cloud_control'), ('kde', 'Peer Control', 'Connect', '', 'ic_hub'),
    ('c3-watchdog', 'Watchdog', 'Profiling', 'extapp:c3-watchdog', 'ic_watchtower'), ('tools-sep-1', '|', 'Profiling', '', ''),
    ('xray', 'X-Ray Device', 'Profiling', '', 'ic_monitor_heart'), ('logcat-fleet', 'Logcat Fleet', 'Profiling', '', 'ic_p_logs'),
    ('profile-app', 'Profile', 'Configs', 'extapp:cloud-account', 'ic_p_accounts'), ('configs-sep-a', '|', 'Configs', '', ''),
    ('perms', 'Perms', 'Configs', '', 'ic_key'), ('agents-watchdog', 'Agents', 'Configs', '', 'ic_p_watchdog'),
    ('logcat', 'Logcat app', 'Configs', '', 'ic_subject'), ('xray-app', 'X-Ray App', 'Configs', '', 'ic_p_c3_health'),
    ('app-about', 'About', 'Configs', '', 'ic_info'),
]
ACTIONS = [('update', 'Update All', 'action:update_all'), ('kde_connect', 'KDE Connect', 'action:kde_connect_now'),
           ('animations', 'Animations', 'action:toggle_animations'), ('sync-clipboard', 'Sync Clipboard', 'action:sync_clipboard'),
           ('saving-mode', 'Saving Mode', 'action:saving_mode'), ('focus-mode', 'Focus Mode', 'page:config/focus')]
C3_ORDER = ['Cloudy C3', '|', 'Peer Control', '|', 'Watchdog', 'X-Ray Device', 'Logcat Fleet']

def setup_runs(bj):   # the Setup grid: visible (not hidden) non-action pages, by group, in declaration order
    runs = {}
    for p in sec(bj)['pages']:
        if p.get('hidden') or p.get('is_action') or 'setup' in p.get('hidden_on', []): continue   # Sections drops hidden; the Setup grid drops hidden_on setup
        runs.setdefault(p.get('group', ''), []).append(p)
    return runs

def hub_sections(bj, hub_id='launcher-hub'):   # mirror of Sections.hubSections
    s = sec(bj); pages = s['pages']; hub = next(p for p in pages if p['id'] == hub_id)
    listed = {i for p in pages if not p.get('hidden') for i in p.get('hub_pages', [])}   # #1719 (unused by the Configs pages since #1943 follow-up: they are not hidden)
    out = {}
    for sub in hub.get('hub_sections', []):
        tiles = [p for p in pages if (p.get('subgroup') or p.get('group', '')) == sub and p['id'] != hub_id
                 and p.get('redirect', '') != 'config/' + hub_id and (not p.get('hidden') or p['id'] in listed) and not p.get('separator')]
        if tiles: out[sub] = tiles
    return out

NEW_LABEL = {}   # #1967: kde reads Peer Control again (label only; id, icon, action unchanged); 'Fleet Control' kept as a search synonym (settings)

def check(n, bj):
    f = []; by = {p['id']: p for p in sec(bj)['pages']}; runs = setup_runs(bj); hub = hub_sections(bj)
    if n == 'P1':
        for pid, label, grp, action, icon in BEFORE:
            p = by.get(pid)
            if p is None: f.append('LOST %s' % pid); continue
            if p['label'] != NEW_LABEL.get(pid, label): f.append('%s label %r != %r' % (pid, p['label'], label))
            if p.get('action', '') != action: f.append('%s action %r != %r' % (pid, p.get('action', ''), action))
            if p.get('icon', '') != icon: f.append('%s icon %r != %r' % (pid, p.get('icon', ''), icon))
            if p.get('separator'):
                if grp == 'Configs':
                    if p.get('group') != 'Configs' or p['id'] != 'configs-sep-a': f.append('%s moved' % pid)
                continue
            if grp in ('Launcher', 'Network', 'Apps'):
                if pid not in [q['id'] for q in runs.get(p.get('group', ''), [])]: f.append('%s no longer on the Setup grid' % pid)
            elif grp in ('Connect', 'Profiling'):
                if pid not in [q['id'] for q in runs.get('C3', [])]: f.append('%s not in the Setup C3 section' % pid)
            elif grp == 'Configs':
                if pid not in [q['id'] for q in hub.get('Configs', [])]: f.append('%s not in the Launcher page Configs section' % pid)
        acts = [(p['id'], p['label'], p.get('action')) for p in sec(bj)['pages'] if p.get('is_action')]
        if acts != ACTIONS: f.append('Actions row changed: %r' % acts)
        if 'Fleet Control' not in by['kde'].get('settings', []): f.append('kde lost the Fleet Control search synonym')
    elif n == 'P5':   # #1967 the Setup C3 row and the page title (the page label, nothing hardcodes it) read Peer Control, never Fleet Control
        if by['kde']['label'] != 'Peer Control': f.append('kde page title/label = %r' % by['kde']['label'])
        if 'Peer Control' not in [q['label'] for q in runs.get('C3', [])]: f.append('Setup C3 row lacks Peer Control')
        if any(q['label'] == 'Fleet Control' for q in sec(bj)['pages']): f.append('a Fleet Control label is still shown')
    elif n == 'P4':   # Home Apps Configs row, Canopus arc, Sirius tree all list Section.pages (= every page that is not `hidden`); the previous lists
        vis = {p['id'] for p in sec(bj)['pages'] if not p.get('hidden')}
        for pid, label, grp, action, icon in BEFORE:
            if pid not in vis: f.append('%s dropped from the Home row / Canopus arc / Sirius tree' % pid)
        for aid, _, _ in ACTIONS:
            if aid not in vis: f.append('action %s dropped' % aid)
    elif n == 'P2':
        gl = {g['id']: g for g in sec(bj)['groups']}
        if gl.get('connect', {}).get('label') != 'C3': f.append('group connect label %r' % gl.get('connect', {}).get('label'))
        if 'Connect' in runs or 'Profiling' in runs: f.append('a Connect or Profiling heading remains')
        if any(g.get('label') in ('Connect', 'Profiling') for g in sec(bj)['groups']) or 'tools' in gl: f.append('groups[] still has Connect/Profiling')
        c3 = [p['label'] for p in sec(bj)['pages'] if p.get('group') == 'C3' and not p.get('hidden')]
        if c3 != C3_ORDER: f.append('C3 section = %r' % c3)
        decl = [by[i]['label'] for i in gl.get('connect', {}).get('pages', []) if i in by]
        if decl != C3_ORDER: f.append('groups[connect].pages = %r' % decl)
        if [p['id'] for p in runs.get('C3', []) if not p.get('separator')] != ['c3', 'kde', 'c3-watchdog', 'xray', 'logcat-fleet']: f.append('C3 ids drifted')
    elif n == 'P3':
        row = [p['label'] for p in runs.get('Launcher', [])]
        if row != ['Launcher', '|', 'Quick Actions', 'UI', 'Folders', 'One-Hand', '|', 'Notify', '|', 'Configs']: f.append('Launcher row = %r' % row)
        c = by.get('launcher-configs', {})
        if c.get('label') != 'Configs' or c.get('redirect') != 'config/launcher-hub' or c.get('hidden'): f.append('Configs entry = %r' % c)
        if by.get('launcher-hub', {}).get('hub_sections') != ['Launcher', 'Configs']: f.append('Launcher page sections = %r' % by.get('launcher-hub', {}).get('hub_sections'))
        if list(hub) != ['Launcher', 'Configs']: f.append('derived Launcher page sections = %r' % list(hub))
        if [p['label'] for p in hub.get('Configs', [])] != ['Profile', 'Perms', 'Agents', 'Logcat app', 'X-Ray App', 'About']: f.append('Configs section = %r' % [p['label'] for p in hub.get('Configs', [])])
        if 'Configs' in runs: f.append('the Setup page still draws the Configs group')
        if 'Configs' in [p['label'] for p in hub.get('Launcher', [])]: f.append('the Launcher page lists a Configs tile on itself')
    return f

fails = 0
for n, t in (('P1', 'every old Setup + Launcher entry reachable at its new place'), ('P2', 'Connect is C3 with Profiling merged in'),
             ('P3', '| Configs after Notify opens the Launcher page; Configs is its second section, off Setup'),
             ('P5', 'the Setup C3 row and the page title read Peer Control (#1967)'),
             ('P4', 'Home Apps Configs row, Canopus arc and Sirius tree list every page they listed before')):
    r = check(n, bj0); print('  %s: %s %s' % ('PASS' if not r else 'FAIL', n, t + ('' if not r else ' -> ' + '; '.join(r)))); fails += bool(r)
print('== mutants: each must turn its check red ==')
page = lambda bj, i: [p for p in sec(bj)['pages'] if p['id'] == i][0]
def drop(bj, i): sec(bj)['pages'].remove(page(bj, i))
def mut(name, s, fn):
    global fails
    bj = copy.deepcopy(bj0); fn(bj)
    red = bool(check(s, bj)); print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
mut('the kde label is Fleet Control again (#1967)', 'P5', lambda b: page(b, 'kde').__setitem__('label', 'Fleet Control'))
mut('Perms goes hidden (drops off Home row, Canopus arc, Sirius tree)', 'P4', lambda b: page(b, 'perms').__setitem__('hidden', True))
mut('About goes hidden', 'P4', lambda b: page(b, 'app-about').__setitem__('hidden', True))
mut('the label is Fleet Control again', 'P1', lambda b: page(b, 'kde').__setitem__('label', 'Fleet Control'))
mut('the Fleet Control synonym is dropped', 'P1', lambda b: page(b, 'kde').pop('settings'))
mut('Perms is dropped', 'P1', lambda b: drop(b, 'perms'))
mut('About goes hidden (leaves the hub)', 'P1', lambda b: page(b, 'app-about').__setitem__('hidden', True))
mut('Logcat Fleet is dropped', 'P1', lambda b: drop(b, 'logcat-fleet'))
mut('Watchdog loses its action', 'P1', lambda b: page(b, 'c3-watchdog').pop('action'))
mut('X-Ray Device leaves C3', 'P1', lambda b: page(b, 'xray').__setitem__('group', 'Profiling'))
mut('an Action is dropped', 'P1', lambda b: drop(b, 'saving-mode'))
mut('the section is still called Connect', 'P2', lambda b: [g.__setitem__('label', 'Connect') for g in sec(b)['groups'] if g['id'] == 'connect'])
mut('Profiling returns as a heading', 'P2', lambda b: page(b, 'xray').__setitem__('group', 'Profiling'))
mut('Peer Control leaves C3', 'P2', lambda b: page(b, 'kde').__setitem__('hidden', True))
mut('C3 order changes', 'P2', lambda b: sec(b)['pages'].append(sec(b)['pages'].pop([p['id'] for p in sec(b)['pages']].index('xray'))))
mut('the Configs entry is dropped', 'P3', lambda b: drop(b, 'launcher-configs'))
mut('the Configs entry opens another page', 'P3', lambda b: page(b, 'launcher-configs').__setitem__('redirect', 'config/controls'))
mut('the Launcher page loses its Configs section', 'P3', lambda b: page(b, 'launcher-hub').__setitem__('hub_sections', ['Launcher']))
mut('a Configs page returns to Setup', 'P3', lambda b: page(b, 'perms').pop('hidden_on'))
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
