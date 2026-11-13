#!/usr/bin/env bash
# Configs > ADB Shell: FOUR TABS and the Status cockpit. Static (no build, no device).
#  1. build.json: adb-shell declares tabs [adb-status, adb-term, adb-perms, adb-all] (Status, Shell, Perms, All, in that order),
#     each declared once as a HIDDEN page, each in exactly one group, the label ADB Shell unchanged, and the page still after Apps Access.
#  2. SectionPages routes every tab to AdbShellFragment; the fragment hosts the cockpit, the terminal, the perms checker and (All) AdbShellBrokerPage.
#  3. AdbCockpitModel holds the chain in the page's order (#1647): perms, setup, embedded, local, shizuku, connection, and the page's
#     cards (GROUP_ORDER: perms, setup, embedded, local, shizuku) as rows of light / step name / message / action icons; the Setup
#     flips; the mode selector (Shizuku, Local Server, Embedded).
#  4. the Status page is drawn from the model with the Kit palette at its own roomy measures (CockpitRoom; no sp literal, no bare
#     MaterialTheme / Scaffold / Typography()), binds to the real state (ChannelReader, ChannelLog.shared, ChannelModePrefs,
#     readPermRows) and has Logs filter / copy / clear.
#  5. #1647 Connect IS the auto-fix: the one Connect verb walks ConnectPlan with the consent gates opened for the person, there is
#     no plain single-step connect and no separate Auto-fix control; a run that stopped for the person resumes from the page.
#  6. the JVM tests exist.
# Usage: ./test-adb-shell-cockpit.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
D="$ROOT/app/src/main/java/com/diegonmarcos/superapp"; C="$D/configs"; T="$ROOT/app/src/test/java/com/diegonmarcos/superapp"
PASS=0; FAIL=0; ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }; bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
code(){ python3 -c 'import re,sys;print(re.sub(r"/\*.*?\*/|//[^\n]*","",open(sys.argv[1],encoding="utf-8").read(),flags=re.S))' "$1"; }
python3 - "$ROOT/build.json" <<'PY' && ok "build.json: adb-shell tabs Status, Shell, Perms, All; hidden, declared once, one group each; label and place kept" || bad "build.json: the ADB Shell tabs are wrong"
import json, sys
b = json.load(open(sys.argv[1], encoding='utf-8'))
sec = next(s for s in b['ui']['sections'] if s['id'] == 'config')
by = {}
for p in sec['pages']: by.setdefault(p['id'], []).append(p)
tabs = ['adb-status', 'adb-term', 'adb-perms', 'adb-all']
bad = []
page = by['adb-shell'][0]
if page.get('tabs') != tabs: bad.append('tabs %r' % page.get('tabs'))
if page.get('label') != 'ADB Shell': bad.append('label')
if [by[t][0]['label'] for t in tabs if t in by and len(by[t]) == 1] != ['Status', 'Shell', 'Perms', 'All']: bad.append('tab labels')
for t in tabs:
    if len(by.get(t, [])) != 1: bad.append('%s declared %d times' % (t, len(by.get(t, [])))); continue
    if not by[t][0].get('hidden'): bad.append('%s is not hidden' % t)
    if sum(t in g['pages'] for g in sec['groups']) != 1: bad.append('%s not in exactly one group' % t)
if '_doc' not in page or 'FOUR TABS' not in page['_doc']: bad.append('_doc does not describe the tabs')
vis = [p['id'] for p in sec['pages'] if p.get('group') == 'Network' and not p.get('hidden')]
if vis[vis.index('adb-shell') - 1] != 'apps-access': bad.append('not after apps-access')
sys.exit(1 if bad else 0) if not print('; '.join(bad)) or True else 0
PY
for t in adb-status:STATUS adb-term:SHELL adb-perms:PERMS adb-all:ALL; do
  # #1682 P3 step 5: SectionPages asks the AdbSide facade, whose owner variant maps the page id to AdbShellFragment's tab
  grep -q "pageId == \"${t%%:*}\".*AdbSide.page(\"${t%%:*}\")" "$D/launcher/SectionPages.kt" && ok "${t%%:*} routed through AdbSide.page" || bad "${t%%:*} is not routed"
done
grep -q 'AdbShellFragment.newInstance(AdbCockpitModel.Tab.of(pageId))' "$ROOT/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt" && ok "the owner AdbSide opens AdbShellFragment on the tab" || bad "the owner AdbSide does not open the tab fragment"
F="$(code "$ROOT/app/src/owner/java/com/diegonmarcos/superapp/configs/AdbShellFragment.kt")"
for w in 'AdbStatusTab()' 'AdbTermTab()' 'AdbPermsTab()' 'AdbShellBrokerPage()'; do echo "$F" | grep -qF "$w" && ok "the fragment hosts $w" || bad "the fragment does not host $w"; done
LC="$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/configs"; LT="$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/test/java/com/diegonmarcos/superapp"  # #1682 P2a the model moved to libs:adb-shell-core
M="$(code "$LC/AdbCockpitModel.kt")"
python3 - "$LC/AdbCockpitModel.kt" <<'PY' && ok "the chain is perms, setup, embedded, local, shizuku, connection; the cards are the first five" || bad "the chain or the cards are missing or out of order"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
def lst(name):
    m = re.search(r'val %s = listOf\(([^)]*)\)' % name, s)
    return [x.strip() for x in m.group(1).split(',')] if m else None
want = ['PERMS', 'SETUP', 'EMBEDDED', 'LOCAL', 'SHIZUKU']
sys.exit(0 if lst('STEP_ORDER') == want + ['CONNECTION'] and lst('GROUP_ORDER') == want else 1)
PY
for w in '"Developer Options"' '"Wireless Debugging"' '"Developer Options Flipped"' '"Wireless Debugging Flipped"' 'ChannelMode.SHIZUKU, ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY' 'ConnectPlan.steps' 'USER_GATES' 'switch mode to use' 'waiting on ' 'fun groups(' 'fun resumes('; do
  echo "$M" | grep -qF "$w" && ok "model: $w" || bad "model is missing: $w"
done
P="$(code "$LC/AdbCockpit.kt")"; O="$(code "$LC/AdbCockpitOps.kt")"; S="$(code "$LC/AdbShellTabs.kt")"  # #1682 P3 step 4c moved to libs:adb-shell-core
echo "$P$O$S" | grep -E '[0-9]\.sp\b|[0-9]sp\b' >/dev/null && bad "an sp literal in the cockpit" || ok "no sp literal"
echo "$P$S" | grep -E 'MaterialTheme\(|\bScaffold\(|Typography\(\)|Button\(|minHeight|defaultMinSize' >/dev/null && bad "bare MaterialTheme / Scaffold / Typography / Button in the cockpit" || ok "no bare MaterialTheme, Scaffold, Typography or Button"
echo "$P" | grep -q 'KitStoreRow' && echo "$P" | grep -q 'LocalKitPalette' && echo "$P" | grep -q 'object CockpitRoom' && ok "drawn from the Kit palette at the page's own roomy measures (CockpitRoom)" || bad "not drawn from the Kit, or the roomy measures are gone"
echo "$P" | grep -q 'ChannelReader.read' && echo "$P" | grep -q 'ChannelLog.shared' && echo "$P" | grep -q 'ChannelModePrefs.set' && echo "$P$O" | grep -q 'readPermRows' && ok "bound to the real state: ChannelReader, ChannelLog.shared, ChannelModePrefs, the perms catalogue" || bad "not bound to the real state"
echo "$P" | grep -q 'ON_RESUME' && ! echo "$P$S" | grep -Eq 'while \(true\)|delay\(' && ok "loads on open and resume, no polling" || bad "polls, or does not reload on resume"
for w in '"filter' '"copy"' '"clear"' 'adbc:control' 'adbc:mode' 'adbc:toggle' 'adbc:reconnect' 'adbc:master' 'adbc:step:' 'adbc:row:' 'adbc:light:' 'adbc:name:' 'adbc:msg:' 'adbc:acts:' 'adbc:logs' 'adbc:keepalive' 'AdbCockpitModel.groups('; do echo "$P" | grep -qF "$w" && ok "status page: $w" || bad "status page is missing: $w"; done
echo "$O" | grep -q 'ConnectPlan.run' && echo "$O" | grep -q 'needsUser = true' && ok "Connect walks ConnectPlan and hands over at the consent gates" || bad "Connect does not stop for the person"
# 5. Connect IS the auto-fix.
[ "$(echo "$O" | grep -c 'ConnectPlan.run(')" = 1 ] && echo "$O" | grep -q 'AutoFixExecutor(app)) { grantPerms(app) }' && echo "$O" | grep -q 'Do.CONNECT -> connect(app)' \
  && ok "one Connect path: perms, then the whole plan with the gates opened (no plain single-step connect)" || bad "Connect is not the one full chain"
echo "$P" | grep -Eq 'adbc:autofix|autoFix' && bad "a separate Auto-fix control is back on the Status page" || ok "no separate Auto-fix control"
echo "$P" | grep -q 'Do.CONNECT -> connect()' && echo "$P" | grep -q 'AdbCockpitModel.resumes(waiting, r.facts)' && ok "the top Connect runs the chain, and a run that stopped for the person resumes" || bad "the top Connect does not run / resume the chain"
[ -f "$LT/configs/AdbCockpitModelTest.kt" ] && ok "tests: configs/AdbCockpitModelTest (libs:adb-shell-core)" || bad "missing tests: configs/AdbCockpitModelTest in libs:adb-shell-core"
for f in configs/AdbCockpitPageTest configs/AdbStatusPageTest configs/AdbShellInFrameTest; do [ -f "$ROOT/app/src/owner/test/java/com/diegonmarcos/superapp/$f.kt" ] && ok "tests: $f" || bad "missing tests: $f"; done
echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
