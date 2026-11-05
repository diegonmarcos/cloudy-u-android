#!/usr/bin/env bash
# Tester (#1151): the "AI" badge of the Resources group keeps its contract.
# Declared + grouped + registered; reads ONLY c3-infra-api GET /ai/usage through AiUsageClient (mesh first, public bearer
# second, base URL from the build, never a literal); no secret in the source; the periodic tick stops with the screen.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"
LIB="$APP/../ab_cloud-libs-shared/libs/ai-usage/src/main/kotlin/com/diegonmarcos/superapp/notificationcenter"
SVC="$SRC/AiBadgeService.kt"; CLI="$LIB/AiUsageClient.kt"; MODEL="$LIB/AiUsageModel.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"

echo "== T1: declared, last in the Resources group, with switches =="
check "$(python3 - "$BJ" "$SVC" <<'PY'
import json, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
src = open(sys.argv[2]).read()
p = next((x for x in nc['producers'] if x['id'] == 'ai_usage'), None)
g = next((x for x in nc['groups'] if x['id'] == 'network'), None)
if p is None:                                   print('no ai_usage producer')
elif not (p.get('badge') and p.get('persistent') and p.get('enabled')): print('not an enabled persistent badge')
elif not p.get('service', '').endswith('.AiBadgeService'): print('no owning service')
elif 'notifications' not in p.get('requires', []): print('does not declare the notification grant')
elif not {'enabled', 'persistent'} <= {o['key'] for o in p.get('customization', [])}: print('missing off / pin switch')
elif g is None or g['label'] != 'Resources' or g['members'][-1] != 'ai_usage': print('not the last member of Resources')
elif p.get('channel') != 'ai_usage' or 'CHANNEL_ID = "ai_usage"' not in src or 'BADGE_ID = "ai_usage"' not in src: print('channel / id mismatch')
else:                                           print('OK')
PY
)" "ai_usage is a declared persistent badge in Resources"

echo "== T2: the service is registered, not exported =="
check "$(python3 - "$MANIFEST" <<'PY'
import re, sys
b = [x for x in re.findall(r'<service\b.*?</service>', open(sys.argv[1]).read(), re.S) if 'AiBadgeService' in x]
if not b:                                       print('AiBadgeService is not in the manifest')
elif 'android:exported="false"' not in b[0] or 'specialUse' not in b[0]: print('must be non-exported specialUse')
else:                                           print('OK')
PY
)" "AiBadgeService is a non-exported specialUse foreground service"

echo "== T3: one source, mesh first, no literal address, no secret =="
check "$(python3 - "$SVC" "$CLI" "$MODEL" <<'PY'
import re, sys
def code(p): return '\n'.join(l for l in open(p).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
s, c, m = (code(p) for p in sys.argv[1:4])
if '/ai/usage' not in c:                        print('client does not read /ai/usage')
elif 'FleetHttp.exchange' not in c or 'Authorization' not in c: print('missing the fleet leg or the public bearer leg')
elif c.index('fleet.get(') > c.index('plain.get('): print('public leg is tried before the fleet (mesh-first) leg')
elif 'BuildConfig.UI_C3_OPS_BASE_URL' not in s: print('base URL is not the build one')
elif re.search(r'https?://|diegonmarcos\.com', s + c + m): print('a literal address in the source')
elif re.search(r'Log\.[a-z]\([^)]*(bearer|token)', s + c, re.I): print('logs a credential')
elif 'openrouter.ai' in (s + c + m):            print('the app must not call OpenRouter itself')
else:                                           print('OK')
PY
)" "reads c3-infra-api only, fleet leg before public leg, build base URL"

echo "== T4: the tick stops with the screen; no grant, no read =="
check "$(python3 - "$SVC" <<'PY'
import re, sys
s = '\n'.join(l for l in open(sys.argv[1]).read().split('\n') if not l.strip().startswith(('*', '//', '/*')))
if 'ACTION_SCREEN_ON' not in s or 'ACTION_SCREEN_OFF' not in s: print('no screen on/off handling')
elif 'isInteractive' not in s:                  print('the tick does not check the screen')
elif 'removeCallbacks(tick)' not in s:          print('the tick is never cancelled')
elif 'TICK_MS = 5 * 60_000L' not in s:          print('tick is not 5 minutes')
elif not re.search(r'if \(!NetworkBadgeService\.canPost\(this\)\) return', s): print('reads without the notification grant')
else:                                           print('OK')
PY
)" "5-minute tick only while the screen is on"

echo
echo "ai-badge: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
