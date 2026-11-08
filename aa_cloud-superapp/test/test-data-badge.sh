#!/usr/bin/env bash
# Tester: the "Data" badge (Network group, with Mesh) keeps its contract.
#
# It must read data usage through the Data Manager engine (libs:datamanager),
# never run its own NetworkStatsManager queries; it must say so when Usage
# Access is missing and offer the grant; it must not poll; it must share the
# "network" shade group with Mesh; and its Data Manager button must land on a
# handler that exists. The figures -> text model is proven by DataBadgeModelTest.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }

BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
SVC="$SRC/notificationcenter/DataBadgeService.kt"
MODEL="$SRC/network/DataBadgeModel.kt"
SHELL_="$SRC/ShellActivity.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"

echo "== T1: declared, in the Network group beside Mesh, with an off switch =="
check "$(python3 - "$BJ" "$SVC" <<'PY'
import json, re, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
src = open(sys.argv[2]).read()
p = next((x for x in nc['producers'] if x['id'] == 'network_data'), None)
g = next((x for x in nc['groups'] if x['id'] == 'network'), None)
if p is None:                                   print('no network_data producer')
elif not (p.get('badge') and p.get('persistent') and p.get('enabled')): print('not an enabled persistent badge')
elif not p.get('service', '').endswith('.DataBadgeService'): print('no owning service')
elif 'notifications' not in p.get('requires', []): print('does not declare the notification grant')
elif not any(o['key'] == 'enabled' for o in p.get('customization', [])): print('no owner off switch')
elif not any(o['key'] == 'persistent' for o in p.get('customization', [])): print('no pin switch')
elif g is None or g['label'] != 'Device Infos' or g['members'] != ['network_mesh', 'network_data', 'battery_status', 'ai_usage']:
                                                print('group network is not [network_mesh, network_data, battery_status, ai_usage]')
elif p.get('channel') != 'network_data' or 'CHANNEL_ID = "network_data"' not in src: print('channel mismatch')
elif not re.search(r'IMPORTANCE_LOW', src):     print('channel is not low importance')
else:                                           print('OK')
PY
)" "network_data is a declared persistent badge in the Network group with its own switches"

echo "== T2: the service is registered, and is a data widget: NOT a foreground service =="
check "$(python3 - "$MANIFEST" "$SVC" <<'PY'
import re, sys
m = open(sys.argv[1]).read()
src = '\n'.join(l for l in open(sys.argv[2]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
t = [x for x in re.findall(r'<service\b[^>]*>', m) if 'DataBadgeService' in x]
if not t:                                       print('DataBadgeService is not in the manifest')
elif 'android:exported="false"' not in t[0]:    print('must not be exported')
elif 'foregroundServiceType' in t[0]:           print('still declares a foregroundServiceType')
elif re.search(r'\bstartForeground\s*\(|startForegroundService', src): print('calls startForeground: it is a data widget')
elif 'FleetNotify.dataWidgetBuilder(' not in src: print('not built by the shared data-widget builder')
else:                                           print('OK')
PY
)" "DataBadgeService is a non-exported plain service that never goes foreground (data widget)"

echo "== T3: figures come from the Data Manager engine, not a second NetworkStats reader =="
check "$(python3 - "$SVC" "$MODEL" <<'PY'
import re, sys
def code(p): return '\n'.join(l for l in open(p).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
s, m = code(sys.argv[1]), code(sys.argv[2])
if 'DataUsageProvider.' not in s:               print('does not use DataUsageProvider')
elif re.search(r'NetworkStatsManager|querySummary|queryDetails|NetworkStats\b', s + m): print('queries NetworkStats itself')
elif re.search(r'^import android\.', m, re.M):  print('the model must stay free of Android types')
else:                                           print('OK')
PY
)" "usage read through libs:datamanager; the model is pure"

echo "== T4: no polling; screen-on and Refresh are the events =="
check "$(python3 - "$SVC" <<'PY'
import re, sys
s = '\n'.join(l for l in open(sys.argv[1]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
if 'ACTION_SCREEN_ON' not in s:                 print('not refreshed on screen-on')
elif 'ACTION_REFRESH' not in s:                 print('no Refresh action')
elif re.search(r'postDelayed\(', s) and not re.search(r'val r = Runnable \{\s*pending = null', s): print('an unexpected timer')
elif len(re.findall(r'postDelayed\(', s)) != 1: print('only the debounce may be delayed')
elif not re.search(r'if \(!NetworkBadgeService\.canPost\((this|app)\)\) return', s): print('reads without the notification grant')
else:                                           print('OK')
PY
)" "no timer beyond the debounce; no grant, no read"

echo "== T5: Usage Access missing is said and fixable =="
check "$(python3 - "$SVC" "$MODEL" <<'PY'
import sys
s, m = open(sys.argv[1]).read(), open(sys.argv[2]).read()
if 'hasUsageAccess' not in s:                   print('never checks the grant')
elif 'Usage access needed' not in m:            print('the badge does not say the grant is missing')
elif 'PermsActivity.intent(ctx, DataUsageProvider.PERMS_FEATURE, listOf("usage_access"))' not in s or 'Grant access' not in m: print('no grant button (the Permissions page, #1274)')
else:                                           print('OK')
PY
)" "missing grant -> titled, explained, with a Grant access button"

echo "== T6: the Data Manager button lands on a handler =="
check "$(python3 - "$SVC" "$SHELL_" <<'PY'
import re, sys
s, sh = open(sys.argv[1]).read(), open(sys.argv[2]).read()
m = re.search(r'OPEN_ACTION = "action:([a-z_]+)"', s)
if not m:                                       print('no OPEN_ACTION')
elif 'actionType == "%s"' % m.group(1) not in sh: print('ShellActivity does not handle action:%s' % m.group(1))
elif 'DataUsageDialog' not in sh:               print('the handler does not open the Data Manager')
else:                                           print('OK')
PY
)" "action:open_data_manager is handled and opens DataUsageDialog"

echo "== T7: per-SIM comes from the engine, the phone grant is fixable, the forecast is data =="
check "$(python3 - "$SVC" "$MODEL" <<'PY'
import sys
s, m = open(sys.argv[1]).read(), open(sys.argv[2]).read()
if 'mobilePerSubscription' not in s:            print('per-SIM split is not read from the engine')
elif 'hasPhoneState' not in s:                  print('never checks READ_PHONE_STATE')
elif 'SubscriptionManager' in s or 'getSubscriberId' in s or 'subscriberId' in s: print('re-implements the SIM lookup')
elif 'GRANT_PHONE' not in m or 'Grant phone' not in m: print('no phone grant button')
elif '"learning"' not in m or 'fun forecastBytes' not in m: print('no forecast / learning state')
elif 'avg30Estimate' not in m or '30-day avg' not in m or 'this month avg' not in m: print('the two labelled estimates are not both there')
elif 'startOfDaysAgo(30' not in s:               print('the 30-day window is not asked of the engine')
elif 'exact' not in m:                          print('the model ignores the engine\'s exact flag')
else:                                           print('OK')
PY
)" "SIMs via the engine; Phone grant button; forecast with a learning state; never an invented split"

echo
echo "data-badge: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
