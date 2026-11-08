#!/usr/bin/env bash
# Configs > ADB Shell: FOUR TABS and the Status cockpit. Static (no build, no device).
#  1. build.json: adb-shell declares tabs [adb-status, adb-term, adb-perms, adb-all] (Status, Shell, Perms, All, in that order),
#     each declared once as a HIDDEN page, each in exactly one group, the label ADB Shell unchanged, and the page still after Apps Access.
#  2. SectionPages routes every tab to AdbShellFragment; the fragment hosts the cockpit, the terminal, the perms checker and (All) AdbShellBrokerPage.
#  3. AdbCockpitModel holds the chain in dependency order: perms, setup, shizuku, local, embedded, connection; the Setup sub-lights;
#     the mode selector (Shizuku, Local Server, Embedded); Auto-fix walks ConnectPlan and stops at the consent gates.
#  4. the cockpit is drawn from the model with Kit primitives (no sp literal, no bare MaterialTheme / Scaffold / Typography()), binds to the
#     real state (ChannelReader, ChannelLog.shared, ChannelModePrefs, readPermRows) and has Logs filter / copy / clear.
#  5. the JVM tests exist.
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
vis = [p['id'] for p in sec['pages'] if p.get('subgroup') == 'Network' and not p.get('hidden')]
if vis[vis.index('adb-shell') - 1] != 'apps-access': bad.append('not after apps-access')
sys.exit(1 if bad else 0) if not print('; '.join(bad)) or True else 0
PY
for t in adb-status:STATUS adb-term:SHELL adb-perms:PERMS adb-all:ALL; do
  grep -q "pageId == \"${t%%:*}\".*AdbShellFragment.newInstance(.*Tab.${t##*:})" "$D/launcher/SectionPages.kt" && ok "${t%%:*} routed to AdbShellFragment (${t##*:})" || bad "${t%%:*} is not routed"
done
F="$(code "$C/AdbShellFragment.kt")"
for w in 'AdbStatusTab()' 'AdbShellTermTab()' 'AdbPermsTab()' 'AdbShellBrokerPage()'; do echo "$F" | grep -qF "$w" && ok "the fragment hosts $w" || bad "the fragment does not host $w"; done
M="$(code "$C/AdbCockpitModel.kt")"
python3 - "$C/AdbCockpitModel.kt" <<'PY' && ok "the chain is perms, setup, shizuku, local, embedded, connection" || bad "the chain is missing or out of order"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'val STEP_ORDER = listOf\(([^)]*)\)', s)
sys.exit(0 if m and [x.strip() for x in m.group(1).split(',')] == ['PERMS', 'SETUP', 'SHIZUKU', 'LOCAL', 'EMBEDDED', 'CONNECTION'] else 1)
PY
for w in '"Developer Options"' '"Wireless Debugging"' 'ChannelMode.SHIZUKU, ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY' 'ConnectPlan.steps' 'USER_GATES' 'switch mode to use' 'waiting on '; do
  echo "$M" | grep -qF "$w" && ok "model: $w" || bad "model is missing: $w"
done
P="$(code "$C/AdbCockpit.kt")"; O="$(code "$C/AdbCockpitOps.kt")"; S="$(code "$C/AdbShellTabs.kt")"
echo "$P$O$S" | grep -E '[0-9]\.sp\b|[0-9]sp\b' >/dev/null && bad "an sp literal in the cockpit" || ok "no sp literal"
echo "$P$S" | grep -E 'MaterialTheme\(|\bScaffold\(|Typography\(\)|Button\(|minHeight|defaultMinSize' >/dev/null && bad "bare MaterialTheme / Scaffold / Typography / Button in the cockpit" || ok "no bare MaterialTheme, Scaffold, Typography or Button"
echo "$P" | grep -q 'KitStoreRow' && echo "$P" | grep -q 'LocalKitPalette' && ok "drawn from the Kit primitives and the palette" || bad "not drawn from the Kit"
echo "$P" | grep -q 'ChannelReader.read' && echo "$P" | grep -q 'ChannelLog.shared' && echo "$P" | grep -q 'ChannelModePrefs.set' && echo "$P$O" | grep -q 'readPermRows' && ok "bound to the real state: ChannelReader, ChannelLog.shared, ChannelModePrefs, the perms catalogue" || bad "not bound to the real state"
echo "$P" | grep -q 'ON_RESUME' && ! echo "$P$S" | grep -Eq 'while \(true\)|delay\(' && ok "loads on open and resume, no polling" || bad "polls, or does not reload on resume"
for w in '"filter' '"copy"' '"clear"' 'Auto-fix' 'adbc:mode' 'adbc:toggle' 'adbc:reconnect' 'adbc:master'; do echo "$P" | grep -qF "$w" && ok "cockpit: $w" || bad "cockpit is missing: $w"; done
echo "$O" | grep -q 'ConnectPlan.run' && echo "$O" | grep -q 'needsUser = true' && ok "Auto-fix walks ConnectPlan and hands over at the consent gates" || bad "Auto-fix does not stop for the person"
for f in configs/AdbCockpitModelTest configs/AdbCockpitPageTest configs/AdbShellInFrameTest; do [ -f "$T/$f.kt" ] && ok "tests: $f" || bad "missing tests: $f"; done
echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
