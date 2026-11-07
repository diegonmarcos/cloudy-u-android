#!/usr/bin/env bash
# Tester: the persistent "Battery" badge (time to full / to empty + battery details) keeps its contract.
#
# WHY THIS EXISTS. The badge shows the battery in a foreground-service
# notification. Five ways it goes wrong without anyone noticing, closed here:
#   1. it is posted by hand-written start code instead of the declared restart
#      path, so it is gone after the next update (the #515 asymmetry);
#   2. it computes the battery itself (a current, a rate, an EMA, a store)
#      instead of reading the ONE battery Source of Truth, libs:battery
#      BatteryRepository/BatteryTruth, that the popup and About › Battery read;
#   3. it polls on a timer instead of reacting to ACTION_BATTERY_CHANGED, or
#      statically registers that sticky broadcast, and drains the battery;
#   4. it posts without the Android 13+ notification grant, or has no off switch;
#   5. it keeps a preference store of its own (the SoT's history is battery_sot.db).
# The SoT's arithmetic and the card are proven by BatteryMathTest,
# BatteryHistoryTest, BatteryTruthTest and BatteryBadgeModelTest (unit tests);
# this is the static half.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# and the manifest are checked for the contract that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }

BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"
SVC="$SRC/BatteryBadgeService.kt"
MODEL="$SRC/BatteryBadgeModel.kt"
SOT="$ROOT/ab_cloud-libs-shared/libs/battery/src/main/java/com/diegonmarcos/superapp/battery"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
FLEET="$ROOT/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"

echo "== T1: declared as a persistent badge with its own low-importance channel =="
check "$(python3 - "$BJ" "$SVC" <<'PY'
import json, re, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
p = next((x for x in nc['producers'] if x['id'] == 'battery_status'), None)
src = open(sys.argv[2]).read()
if p is None:                                   print('no battery_status producer')
elif not (p.get('badge') and p.get('persistent') and p.get('enabled')):
                                                print('not an enabled persistent badge')
elif not p.get('service', '').endswith('.BatteryBadgeService'):
                                                print('no owning service')
elif 'notifications' not in p.get('requires', []):
                                                print('does not declare the notification grant')
elif not any(o['key'] == 'enabled' for o in p.get('customization', [])):
                                                print('no owner off switch')
elif not any(o['key'] == 'persistent' for o in p.get('customization', [])):
                                                print('no pin switch')
elif not any(g['id'] == 'network' and 'battery_status' in g.get('members', []) for g in nc.get('groups', [])):
                                                print('not in the network shade group')
elif p.get('channel') != 'battery_status' or 'CHANNEL_ID = "battery_status"' not in src:
                                                print('declared channel is not the one the service posts on')
elif not re.search(r'NotificationChannel\(CHANNEL_ID,\s*"Battery",\s*NotificationManager\.IMPORTANCE_LOW', src):
                                                print('channel is not named Battery at IMPORTANCE_LOW')
else:                                           print('OK')
PY
)" "battery_status is a declared persistent badge on its own LOW channel with off and pin switches"

echo "== T2: the service is registered, and is a data widget: NOT a foreground service =="
check "$(python3 - "$MANIFEST" "$SVC" <<'PY'
import re, sys
m = open(sys.argv[1]).read()
src = '\n'.join(l for l in open(sys.argv[2]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
t = [x for x in re.findall(r'<service\b[^>]*>', m) if 'BatteryBadgeService' in x]
if not t:                                       print('BatteryBadgeService is not in the manifest')
elif 'android:exported="false"' not in t[0]:    print('must not be exported')
elif 'foregroundServiceType' in t[0]:           print('still declares a foregroundServiceType: Android lists the app as running in the background')
elif re.search(r'\bstartForeground\s*\(|startForegroundService', src):
                                                print('calls startForeground: it is a data widget, not a keep-alive')
elif 'FleetNotify.dataWidgetBuilder(' not in src:
                                                print('not built by the shared data-widget builder')
else:                                           print('OK')
PY
)" "BatteryBadgeService is a non-exported plain service that never goes foreground (data widget)"

echo "== T3: the badge reads the battery SoT and computes nothing; the SoT's math is pure =="
check "$(python3 - "$SVC" "$MODEL" "$SOT" <<'PY'
import os, re, sys
def code(p):
    return '\n'.join(l for l in open(p).read().split('\n')
                     if not l.strip().startswith(('*', '//', '/*')))
s, m = code(sys.argv[1]), code(sys.argv[2])
pure = [os.path.join(sys.argv[3], f) for f in ('BatteryMath.kt', 'BatteryHistory.kt', 'BatteryTruth.kt', 'BatteryRows.kt')]
if os.path.exists(os.path.join(os.path.dirname(sys.argv[1]), 'BatteryEstimator.kt')):
                                                print('BatteryEstimator.kt is back: a second battery calculation')
elif 'BatteryRepository.report(' not in s:      print('does not read the battery SoT')
elif re.search(r'getIntProperty|CURRENT_NOW|getSharedPreferences|BatterySessionStats|ACTION_POWER_(DIS)?CONNECTED', s):
                                                print('the service reads or tracks the battery itself')
elif re.search(r'\b(msToEmpty|msToFull|pctPerHour\(|ema\()', m):
                                                print('the badge model computes instead of formatting the report')
elif any(not os.path.exists(p) for p in pure):  print('a SoT source is missing')
elif any(re.search(r'^import android\.|System\.currentTimeMillis', code(p), re.M) for p in pure + [sys.argv[2]]):
                                                print('the model / SoT math must stay free of Android types and the clock')
else:                                           print('OK')
PY
)" "the badge draws BatteryRepository.report(); model and SoT math are pure"

echo "== T4: event-driven on ACTION_BATTERY_CHANGED, no polling =="
check "$(python3 - "$SVC" "$MANIFEST" <<'PY'
import re, sys
s = '\n'.join(l for l in open(sys.argv[1]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
m = open(sys.argv[2]).read()
if 'ACTION_BATTERY_CHANGED' not in s:           print('not driven by ACTION_BATTERY_CHANGED')
elif 'registerReceiver' not in s or 'unregisterReceiver' not in s:
                                                print('the receiver is not registered/unregistered dynamically')
elif re.search(r'android\.intent\.action\.BATTERY_CHANGED', m):
                                                print('BATTERY_CHANGED is statically registered in the manifest')
elif re.search(r'AlarmManager|WorkManager|scheduleAtFixedRate|Timer\(|postDelayed\([^)]*,\s*\w*(TICK|POLL|INTERVAL)', s):
                                                print('polls on a timer')
elif len(re.findall(r'postDelayed\(', s)) != 1: print('more than the one coalescing delay')
else:                                           print('OK')
PY
)" "dynamic receiver only; the single delay coalesces events, nothing polls"

echo "== T5: Android 13+ POST_NOTIFICATIONS is respected =="
check "$(python3 - "$SVC" "$SRC/BadgeServices.kt" <<'PY'
import re, sys
s = open(sys.argv[1]).read(); b = open(sys.argv[2]).read()
if 'POST_NOTIFICATIONS' not in s:               print('never checks the grant')
elif not re.search(r'if \(!canPost\((this|app)\)\) return', s):
                                                print('refresh posts without checking the grant')
elif '"notifications"' not in b:                print('BadgeServices cannot report a missing grant')
else:                                           print('OK')
PY
)" "no grant -> no post, and the Notify pane says why"

echo "== T6: estimates are h:mm, labelled, with a learning state =="
check "$(python3 - "$MODEL" "$SOT" <<'PY'
import re, sys
m = open(sys.argv[1]).read()
rows = open(sys.argv[2] + '/BatteryRows.kt').read()
hist = open(sys.argv[2] + '/BatteryHistory.kt').read()
repo = open(sys.argv[2] + '/BatteryRepository.kt').read()
if '(est)' not in m:                            print('estimates are not labelled')
elif 'learning…' not in m:                      print('no learning state')
elif '"%d:%02d"' not in rows:                   print('durations are not h:mm')
elif 'computeChargeTimeRemaining' not in repo:  print('the system charge estimate is not asked for')
elif not re.search(r'MIN_USED_PCT\s*=\s*2\b', hist) or not re.search(r'MIN_ELAPSED_MS\s*=\s*15\s*\*', hist):
                                                print('the learning gate is not 2% / 15 min')
else:                                           print('OK')
PY
)" "h:mm, (est), learning gate, system charge estimate first"

echo "== T7: no preference store of its own; the old rate store is gone from fleet-config.json =="
check "$(python3 - "$SVC" "$FLEET" <<'PY'
import json, re, sys
s = open(sys.argv[1]).read()
f = json.load(open(sys.argv[2]))
if re.search(r'const val PREFS\s*=', s):        print('the service names a store again')
elif 'battery_badge' in f['stores']:            print('battery_badge is still declared but nothing writes it')
else:                                           print('OK')
PY
)" "the badge keeps no store; its history is the SoT's battery_sot.db"

echo
echo "battery-badge: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
