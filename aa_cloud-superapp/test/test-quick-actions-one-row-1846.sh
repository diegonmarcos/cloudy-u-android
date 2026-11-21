#!/usr/bin/env bash
# #1846 Quick actions is ONE tab row: Preset | Battery Security UI Network Others All.
#  Q1  the row, its labels, the "|" (separator_before on Battery only), every page routed to a LauncherConfigFragment scope.
#  Q2  RULE 16 PARITY against test/panels-quick-baseline-1846.json: every control (every field), group (every field but tab),
#      toggle group (every field but tab) and toggle of the frozen declaration is still declared; each control's group is drawn on a tab.
#  Q3  All lists everything: every tab's scope is also on in the All scope; every drawn tab has a page.
#  Q4  the new UI and Network tiles exist (declared, implemented in DeviceControls.byId with a live probe naming source and value, observed).
#  Q5  every tile on the two new tabs has a detail line (its own probe, or QuickControls.SOURCES for a moved tile).
#  Q6  the new group has its strings in en and es.
set -u
cd "$(dirname "$0")/.."
BJ=${BUILD_JSON:-build.json}
KT=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = OK ]; then ok "$2"; else bad "$2 — $1"; fi; }
BASE=${BASELINE:-test/panels-quick-baseline-1846.json}

echo "== Q1: the one row =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
by = {p['id']: p for p in next(s for s in b['ui']['sections'] if s['id'] == 'config')['pages']}
t = by['controls']['tabs']
want = ['presets', 'panels-battery', 'panels-security', 'panels-quick-ui', 'panels-network', 'panels-tools', 'panels-all']
labels = [by[i]['label'] for i in t]
seps = [i for i in t if by[i].get('separator_before')]
launcher = next(g for g in next(s for s in b['ui']['sections'] if s['id'] == 'config')['groups'] if g['id'] == 'launcher')['pages']
probs = []
if t != want: probs.append('tabs %r' % t)
if labels != ['Preset', 'Battery', 'Security', 'UI', 'Network', 'Others', 'All']: probs.append('labels %r' % labels)
if seps != ['panels-battery']: probs.append('separators %r' % seps)
probs += ['%s not in Launcher pages' % i for i in want if i not in launcher]
sec = next(s for s in b['ui']['sections'] if s['id'] == 'config')
if 'panels' in by or sec['page_aliases'].get('panels') != 'controls' or sec['retired_pages'].get('panels') != ['controls']: probs.append('the old panels page is not retired into controls')
print('; '.join(probs) or 'OK')
PY
)" "Preset | Battery Security UI Network Others All, the | before Battery only"
for pair in "panels-battery:SCOPE_BATTERY" "panels-security:SCOPE_SECURITY" "panels-quick-ui:SCOPE_QUICK_UI" "panels-network:SCOPE_NETWORK" "panels-tools:SCOPE_TOOLS" "panels-all:SCOPE_ALL"; do
  p=${pair%%:*}; sc=${pair##*:}
  if grep -q "pageId == \"$p\" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.$sc)" $KT/launcher/SectionPages.kt; then ok "$p routes to $sc"; else bad "$p does not route to $sc"; fi
done

echo "== Q2: rule 16 parity with the frozen declaration =="
check "$(python3 - "$BJ" "$BASE" <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); base = json.load(open(sys.argv[2]))
cp = b['ui']['control_panel']['groups']
ls = b['ui']['launcher_settings']
probs = []
now_groups = {g['id']: g for g in cp}
ctl = {c['id']: (g, c) for g in cp for c in g.get('controls', [])}
for g in base['groups']:
    n = now_groups.get(g['id'])
    if n is None: probs.append('group %s gone' % g['id']); continue
    for k, v in g.items():
        if k in ('controls', 'tab'): continue
        if n.get(k) != v: probs.append('group %s.%s changed' % (g['id'], k))
    for c in g.get('controls', []):
        if c['id'] not in ctl: probs.append('control %s gone' % c['id']); continue
        for k, v in c.items():
            if ctl[c['id']][1].get(k) != v: probs.append('%s.%s changed/lost' % (c['id'], k))
drawn_tabs = {'tools', 'security', 'ui', 'network'}
for cid, (g, c) in ctl.items():
    if g.get('derive'): continue
    tab = g.get('tab', 'tools')
    if g.get('draw', True) is False: continue
    if tab not in drawn_tabs: probs.append('control %s sits on unknown tab %s' % (cid, tab))
now_tg = {g['id']: g for g in ls['toggle_groups']}
for g in base['toggle_groups']:
    n = now_tg.get(g['id'])
    if n is None: probs.append('toggle group %s gone' % g['id']); continue
    for k, v in g.items():
        if k != 'tab' and n.get(k) != v: probs.append('toggle group %s.%s changed' % (g['id'], k))
    if n.get('tab') not in drawn_tabs | {'battery'}: probs.append('toggle group %s on unknown tab %s' % (g['id'], n.get('tab')))
now_t = {t['id']: t for t in ls['toggles']}
for t in base['toggles']:
    n = now_t.get(t['id'])
    if n is None: probs.append('toggle %s gone' % t['id']); continue
    for k, v in t.items():
        if n.get(k) != v: probs.append('toggle %s.%s changed' % (t['id'], k))
print('; '.join(probs) or 'OK')
PY
)" "every control, group, toggle group and toggle of the frozen baseline is still declared with its fields; each is drawn on a tab"

echo "== Q3: All lists everything =="
F=$KT/settings/LauncherConfigFragment.kt
for sc in showUi showBattery showTools showSecurity showQuickUi showNetwork; do
  if grep -q "val $sc = all || " $F; then ok "All turns $sc on"; else bad "All does not turn $sc on"; fi
done
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
cp = b['ui']['control_panel']['groups']
tabs = {g.get('tab', 'tools') for g in cp if g.get('draw', True) is not False}
tg = {g.get('tab') for g in b['ui']['launcher_settings']['toggle_groups']}
scopes = {'tools', 'security', 'ui', 'network', 'battery'}
bad = (tabs | tg) - scopes
print('OK' if not bad else 'tabs with no scope: %r' % bad)
PY
)" "every tab a group or toggle group names has a scope page"

echo "== Q4/Q5: the new tiles =="
NEW="hotspot nfc private_dns always_on_vpn keep_screen_on screen_timeout font_size"
for id in $NEW; do
  if grep -q "^        \"$id\" to Control(" $KT/configs/DeviceControls.kt; then ok "$id implemented"; else bad "$id not implemented in DeviceControls.byId"; fi
  if python3 - "$BJ" "$id" <<'PY'
import json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
sys.exit(0 if any(c['id'] == sys.argv[2] and g.get('tab') in ('ui', 'network') for g in cp for c in g.get('controls', [])) else 1)
PY
  then ok "$id declared on the UI or Network tab"; else bad "$id not declared on a new tab"; fi
done
n=$(awk '/#1846 Quick actions ▸ Network and/{f=1} f&&/observed = true/{c++} END{print c+0}' $KT/configs/DeviceControls.kt)
p=$(awk '/#1846 Quick actions ▸ Network and/{f=1} f&&/probe = /{c++} END{print c+0}' $KT/configs/DeviceControls.kt)
[ "$n" -ge 7 ] && [ "$p" -ge 7 ] && ok "every new tile is observed and has a probe ($n observed, $p probes)" || bad "observed=$n probes=$p, want >= 7"
check "$(python3 - "$BJ" "$KT/configs/QuickControls.kt" "$KT/configs/DeviceControls.kt" <<'PY'
import json, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
qc = open(sys.argv[2]).read(); dc = open(sys.argv[3]).read()
sources = set(re.findall(r'"([a-z_]+)" to "', qc[qc.index('val SOURCES'):qc.index('fun detailed')]))
flagged = {c['id'] for g in cp for c in g.get('controls', []) if g.get('tab') in ('ui', 'network') or c.get('net_tab')}
probs = []
for cid in sorted(flagged):
    own = re.search(r'"%s" to Control\((?:(?!\n        "[a-z_]+" to ).)*?probe = ' % cid, qc + dc, re.S)
    if not own and cid not in sources: probs.append(cid)
print('OK' if not probs else 'no detail line: %r' % probs)
PY
)" "every tile on UI and Network has a detail line"

echo "== Q6: strings =="
for f in app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml; do
  grep -q 'control_group_network_more"' $f && grep -q 'control_group_network_more_sub"' $f && ok "$f names the Location group" || bad "$f lacks control_group_network_more"
done
echo "passed=$PASS failed=$FAIL"; [ "$FAIL" = 0 ]
