#!/usr/bin/env bash
# #1755 Field-parity tester (rule 16, REORGANISE): the Configs page and the Cloud > Apps > Configs row were rearranged
# (Launcher hub, Account into Apps, Profile, Tools -> Profiling, Agents into Configs, Logcat -> Logcat app, row order).
# Nothing may be lost: every visible page of the previous layout is still declared and visible with the same icon and
# the same action, differing ONLY by the declared moves/renames below; every item of the previous row is still on the row
# or (Watchdog, which the new owner order leaves off the row) on the Configs page with the same target.
# Static (no device, no build).  bash aa_cloud-superapp/test/test-configs-1755-parity.sh
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$APP/build.json" <<'PY'
import json, sys
secs = json.load(open(sys.argv[1]))['ui']['sections']
sec = next(s for s in secs if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
BEFORE_PAGES = [   # (id, label, group, action, icon) of every visible, non-action page before #1755
    ('controls', 'Quick Actions', 'Launcher', '', 'ic_home'),
    ('panels-ui', 'UI', 'Launcher', '', 'ic_ctl_brightness'),
    ('app-folders', 'Folders', 'Launcher', '', 'ic_p_folder'),
    ('onehand', 'One-Hand', 'Launcher', '', 'ic_onehand'),
    ('notify', 'Notify', 'Launcher', '', 'ic_rss'),
    ('ng-firewall', 'NG Firewall', 'Network', '', 'ic_act_shield'),
    ('wifi', 'Wi-Fi', 'Network', '', 'ic_wifi'),
    ('bluetooth', 'Bluetooth', 'Network', '', 'ic_bluetooth'),
    ('dns', 'DNS', 'Network', '', 'ic_world'),
    ('wg', 'VPN', 'Network', 'section:wg', 'ic_wg'),
    ('firewall', 'Firewall', 'Network', '', 'ic_lock'),
    ('os-perms', 'OS Perms', 'Network', 'action:open_os_perms', 'ic_act_key'),
    ('webserver', 'Webserver', 'Network', 'action:open_webserver', 'ic_web'),
    ('proxy', 'Proxy', 'Network', 'action:open_proxy_settings', 'ic_ctl_mobile_data'),
    ('store', 'Store', 'Apps', 'extapp:cloud-store', 'ic_storefront'),
    ('c3', 'Cloudy C3', 'Connect', 'extapp:cloud-c3', 'ic_cloud_control'),
    ('kde', 'Peer Control', 'Connect', '', 'ic_hub'),
    ('c3-watchdog', 'Watchdog', 'Tools', 'extapp:c3-watchdog', 'ic_watchtower'),
    ('xray', 'X-Ray Device', 'Tools', '', 'ic_monitor_heart'),
    ('logcat-fleet', 'Logcat Fleet', 'Tools', '', 'ic_p_logs'),
    ('agents-watchdog', 'Agents', 'Tools', '', 'ic_p_watchdog'),
    ('profile', 'Account', 'Configs', 'extapp:cloud-account', 'ic_person'),
    ('perms', 'Perms', 'Configs', '', 'ic_key'),
    ('logcat', 'Logcat', 'Configs', '', 'ic_subject'),
    ('xray-app', 'X-Ray App', 'Configs', '', 'ic_p_c3_health'),
    ('app-about', 'About', 'Configs', '', 'ic_info'),
]
BEFORE_ROW = [   # (label, target) of the Cloud > Apps > Configs row before #1755
    ('Quick Actions', 'page:config/controls'),
    ('UI', 'page:config/panels-ui'),
    ('NG Firewall', 'page:config/ng-firewall'),
    ('Store', 'extapp:cloud-store'),
    ('C3', 'extapp:cloud-c3'),
    ('Watchdog', 'extapp:c3-watchdog'),
    ('Account', 'extapp:cloud-account'),
    ('About', 'page:config/app-about'),
]
RENAMED = {'logcat': 'Logcat app'}                                  # #1755 item 4
MOVED = {'profile': 'Apps', 'agents-watchdog': 'Configs'}          # #1755 items 2 and 3 (Account -> Apps, Agents -> Configs)
GROUP_RENAMED = {'Tools': 'Profiling'}                              # #1755 item 3
probs = []
for pid, label, group, action, icon in BEFORE_PAGES:
    p = by.get(pid)
    if p is None: probs.append('LOST page %s' % pid); continue
    if p.get('hidden'): probs.append('page %s went hidden' % pid)
    want_label = RENAMED.get(pid, label)
    if p['label'] != want_label: probs.append('%s label %r != %r' % (pid, p['label'], want_label))
    want_group = MOVED.get(pid, GROUP_RENAMED.get(group, group))
    if p.get('group') != want_group: probs.append('%s group %r != %r' % (pid, p.get('group'), want_group))
    if p.get('action', '') != action: probs.append('%s action %r != %r' % (pid, p.get('action', ''), action))
    if p.get('icon', '') != icon: probs.append('%s icon %r != %r' % (pid, p.get('icon', ''), icon))
# the Launcher hub lists every Launcher icon (derived from the group, no second list)
hub = by.get('launcher-hub', {})
if hub.get('hub_sections') != ['Launcher'] or hub.get('group') != 'Launcher': probs.append('launcher-hub is not the Launcher hub: %r' % hub)
launcher = [p['id'] for p in sec['pages'] if p.get('group') == 'Launcher' and not p.get('hidden') and not p.get('separator') and p['id'] != 'launcher-hub']
if launcher != ['controls', 'panels-ui', 'app-folders', 'onehand', 'notify']: probs.append('Launcher group icons = %r' % launcher)
# Profile launches the Cloud Account app
if by.get('profile-app', {}).get('action') != 'extapp:cloud-account': probs.append('profile-app does not launch extapp:cloud-account')
# the Cloud > Apps > Configs row
row = next(g for s in secs for g in s.get('tile_groups', []) if g.get('title') == 'Setup')['tiles']
labels = [t['label'] for t in row]
want = ['Launcher', '|', 'NG Firewall', '|', 'Store', 'Account', '|', 'C3', '|', 'Profile', 'About']   # #1929 MOVED: Quick Actions and UI merged into Launcher (page:config/launcher-hub); BEFORE_ROW proves both pages stay reachable
if labels != want: probs.append('row = %r' % labels)
tg = {t['label']: t.get('target', '') for t in row}
page_actions = {p['label']: p.get('action') or 'page:config/' + p['id'] for p in sec['pages'] if not p.get('hidden') and not p.get('separator')}
for label, target in BEFORE_ROW:
    if tg.get(label) == target: continue
    if label in page_actions and page_actions[label] == target: continue      # still visible on the Configs page (Watchdog)
    probs.append('row item %s (%s) lost' % (label, target))
print('; '.join(probs) or 'OK')
sys.exit(1 if probs else 0)
PY
rc=$?
[ $rc -eq 0 ] && echo "  PASS: #1755 field parity: no Configs page and no Cloud Apps Configs row item lost" || echo "  FAIL: #1755 field parity"
exit $rc
