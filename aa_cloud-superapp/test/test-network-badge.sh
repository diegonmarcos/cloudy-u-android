#!/usr/bin/env bash
# Tester: the persistent "Network" badge (mesh state in the shade) keeps its contract.
#
# WHY THIS EXISTS. The badge reads the mesh from the engine APK over INetBackend
# and draws it in a foreground-service notification. Four ways it goes wrong
# without anyone noticing, and this tester closes each:
#   1. it is posted by hand-written start code instead of the declared restart
#      path, so it is gone after the next update (the #515 asymmetry);
#   2. it duplicates WireGuard logic (parses a tunnel, builds a Config, binds
#      the engine) instead of going through WgState / AidlBackend;
#   3. it polls on a short timer, or while DISconnected, and drains the battery;
#   4. it posts without the Android 13+ notification grant, or has no off switch.
# The state -> labels/actions/expanded-text model is proven by
# NetworkBadgeModelTest (a unit test); this is the static half.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# and the manifest are checked for the contract that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }

BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
SVC="$SRC/notificationcenter/NetworkBadgeService.kt"
MODEL="$SRC/network/NetworkBadgeModel.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"

echo "== T1: declared as a persistent badge with its own low-importance channel =="
check "$(python3 - "$BJ" "$SVC" <<'PY'
import json, re, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
p = next((x for x in nc['producers'] if x['id'] == 'network_mesh'), None)
src = open(sys.argv[2]).read()
if p is None:                                   print('no network_mesh producer')
elif not (p.get('badge') and p.get('persistent') and p.get('enabled')):
                                                print('not an enabled persistent badge')
elif not p.get('service', '').endswith('.NetworkBadgeService'):
                                                print('no owning service')
elif 'notifications' not in p.get('requires', []):
                                                print('does not declare the notification grant')
elif not any(o['key'] == 'enabled' for o in p.get('customization', [])):
                                                print('no owner off switch')
elif not any('network_mesh' in g.get('members', []) for g in nc.get('groups', [])):
                                                print('in no shade group')
elif p.get('channel') != 'network_mesh' or 'CHANNEL_ID = "network_mesh"' not in src:
                                                print('declared channel is not the one the service posts on')
elif not re.search(r'NotificationChannel\(CHANNEL_ID,\s*"Network",\s*NotificationManager\.IMPORTANCE_LOW', src):
                                                print('channel is not named Network at IMPORTANCE_LOW')
else:                                           print('OK')
PY
)" "network_mesh is a declared persistent badge on its own LOW channel with an off switch"

echo "== T2: the service is registered and foreground =="
check "$(python3 - "$MANIFEST" <<'PY'
import re, sys
m = open(sys.argv[1]).read()
b = [x for x in re.findall(r'<service\b.*?</service>', m, re.S) if 'NetworkBadgeService' in x]
if not b:                                       print('NetworkBadgeService is not in the manifest')
elif 'android:exported="false"' not in b[0]:    print('must not be exported')
elif 'specialUse' not in b[0]:                  print('no foregroundServiceType')
else:                                           print('OK')
PY
)" "NetworkBadgeService is a non-exported specialUse foreground service"

echo "== T3: the data comes over INetBackend, not a second WireGuard =="
check "$(python3 - "$SVC" "$MODEL" <<'PY'
import re, sys
def code(p):
    return '\n'.join(l for l in open(p).read().split('\n')
                     if not l.strip().startswith(('*', '//', '/*')))
s, m = code(sys.argv[1]), code(sys.argv[2])
if 'WgState.backend(' not in s:                 print('does not use WgState.backend')
elif re.search(r'AidlBackend\s*\(|GoBackend|bindService|Config\.parse|wgGetConfig|Statistics\.parse', s):
                                                print('duplicates engine/WireGuard logic')
elif re.search(r'^import android\.', m, re.M):  print('the model must stay free of Android types')
else:                                           print('OK')
PY
)" "state read through WgState/AidlBackend; the model is pure"

echo "== T4: refresh is event-driven, with a light tick only while connected and the screen is on =="
check "$(python3 - "$SVC" <<'PY'
import re, sys
s = '\n'.join(l for l in open(sys.argv[1]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
t = re.search(r'TICK_MS\s*=\s*([\d_ *]+)L', s)
if not t:                                       print('no TICK_MS')
elif eval(t.group(1).replace('_', '')) < 300_000: print('tick under 5 minutes')
elif 'TRANSPORT_VPN' not in s or 'registerNetworkCallback' not in s:
                                                print('not driven by VPN network events')
elif 'stateListener' not in s:                  print('not driven by the app\'s own connect/disconnect')
elif not re.search(r'if \(!shouldTick\(connected, interactive\(\)\)\) return', s): print('the tick is not gated on being connected and the screen being on')
elif 'ACTION_SCREEN_ON' not in s or 'ACTION_SCREEN_OFF' not in s: print('no screen on/off handling')
elif len(re.findall(r'postDelayed\(', s)) > 2:  print('more timers than debounce + tick')
else:                                           print('OK')
PY
)" "VPN events + app state hook; the only timer is >=30s and only while connected"

echo "== T5: Android 13+ POST_NOTIFICATIONS is respected =="
check "$(python3 - "$SVC" "$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter/BadgeServices.kt" <<'PY'
import re, sys
s = open(sys.argv[1]).read(); b = open(sys.argv[2]).read()
if 'POST_NOTIFICATIONS' not in s:               print('never checks the grant')
elif not re.search(r'if \(!canPost\(this\)\) return', s):
                                                print('refresh posts without checking the grant')
elif '"notifications"' not in b:                print('BadgeServices cannot report a missing grant')
else:                                           print('OK')
PY
)" "no grant -> no post and no polling, and the Notify pane says why"

echo "== T6: three buttons, More opens the mesh page, Always On opens VPN settings =="
check "$(python3 - "$SVC" "$MODEL" "$APP/build.json" <<'PY'
import json, re, sys
s, m = open(sys.argv[1]).read(), open(sys.argv[2]).read()
bj = open(sys.argv[3]).read()
if not all(x in m for x in ('ALWAYS_ON', 'TOGGLE', 'MORE', '"Disconnect"', '"Connect"', '"More"')):
                                                print('model lacks the three labelled actions')
elif 'Settings.ACTION_VPN_SETTINGS' not in s:   print('Always On does not reach the VPN settings')
elif 'page:config/wg' not in s:                 print('More does not open the Cloud Mesh page')
elif '"id": "wg"' not in bj:                    print('the mesh page id wg is not declared')
elif not re.search(r'BigTextStyle\(\)\.bigText', s): print('no expanded text')
else:                                           print('OK')
PY
)" "Always On / Connect|Disconnect / More wired to real targets"

echo
echo "network-badge: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
