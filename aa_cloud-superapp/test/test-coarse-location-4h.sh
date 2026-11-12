#!/usr/bin/env bash
# Tester (#1654): the app takes ONE coarse location fix per 4 h, for Weather, and holds no location subscription.
#
# WHY THIS EXISTS. The app drained the battery with active location: Weather asked the GPS provider as a last
# resort, the status strip kept a passive requestLocationUpdates listener and the popup's GPS section asked
# GPS + network every second. Closed here, statically (no device, no build):
#   T1 Weather requests only the NETWORK (coarse) provider: no GPS provider, no fine-only gate, no PASSIVE
#   T2 (#1690) no location-class call at all in WeatherLocation: the one fix per 4 h is Cloud Nav's (NavLocation over
#      the fleet IPC door, WeatherLocation.resolveFromNav), never requestLocationUpdates
#   T3 location + weather are gated to >= 4 h: FRESH_MS = REFRESH_MS = 4 h, the service floors its alarm at
#      4 h and skips location AND fetch while the cached forecast is inside the window (not on every open)
#   T4 the declared "Refresh every" option is 240 min only
#   T5 no persistent location listener remains anywhere in the app (no requestLocationUpdates, no
#      LocationListener, no GPS-provider request)
#   T6 nothing requests location when off: Weather is retired through DataWidgetRuntime.retiredIfOff before
#      any location work and the next alarm is armed only when it is on
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
SRC="$APP/app/src/main/java"
WL="$SRC/com/diegonmarcos/superapp/notificationcenter/WeatherLocation.kt"
WS="$SRC/com/diegonmarcos/superapp/notificationcenter/WeatherBadgeService.kt"
code() { sed -e 's://.*$::' -e '/^[[:space:]]*\*/d' -e '/^[[:space:]]*\/\*/d' "$1"; }

echo "== T1: Weather asks the coarse network provider only =="
if code "$WL" | grep -Eq 'GPS_PROVIDER|"gps"|PASSIVE|"passive"|"fused"|GPS_TIMEOUT|PRIORITY_HIGH|FusedLocation'; then bad "Weather location names GPS/passive/fused/high accuracy"; else ok "no GPS/passive/fused/high-accuracy in WeatherLocation"; fi
if code "$WL" | grep -Eq 'REQUEST_ORDER = listOf\(NETWORK\)$' && code "$WL" | grep -Eq 'PEEK_ORDER = listOf\(NETWORK\)$'; then ok "PEEK_ORDER and REQUEST_ORDER are [NETWORK]"; else bad "provider order is not [NETWORK] only"; fi
if code "$WL" | grep -Eq 'GPS -> env\.fine'; then bad "a fine-permission gate for GPS remains"; else ok "no fine-only gate"; fi

echo "== T2: one single-shot fix, no updates =="
# #1690 stronger than the old one-shot rule: Weather makes NO location-class call; its one fix per 4 h is Cloud Nav's.
if code "$WL" | grep -Eq 'LocationManager|getLastKnownLocation|getCurrentLocation'; then bad "WeatherLocation still reads location itself"; else ok "WeatherLocation makes no location-class call"; fi
if code "$WS" | grep -q 'WeatherLocation.resolveFromNav' && code "$WS" | grep -q 'NavLocation.ask'; then ok "the fix is asked from Cloud Nav (NavLocation over the fleet IPC door)"; else bad "Weather does not read Cloud Nav's fix"; fi
if code "$WL" | grep -Eq 'requestLocationUpdates|LocationListener|requestSingleUpdate'; then bad "WeatherLocation subscribes to updates"; else ok "no updates subscription in WeatherLocation"; fi

echo "== T3: gated to >= 4 h =="
if code "$WL" | grep -Eq 'REFRESH_MS = 4 \* 60 \* 60_000L' && code "$WL" | grep -Eq 'FRESH_MS = REFRESH_MS'; then ok "REFRESH_MS = FRESH_MS = 4 h"; else bad "4 h constants missing"; fi
if code "$WS" | grep -Eq 'coerceIn\(WeatherLocation\.REFRESH_MS'; then ok "service alarm floored at 4 h"; else bad "alarm not floored at 4 h"; fi
if code "$WS" | grep -Eq 'savedMs in 0 until WeatherLocation\.REFRESH_MS'; then ok "cached forecast inside 4 h skips location and fetch"; else bad "no 4 h gate in compute()"; fi
if code "$WS" | grep -Eq 'DEFAULT_REFRESH_MIN = 240L'; then ok "default refresh 240 min"; else bad "default refresh is not 240 min"; fi

echo "== T4: declared option =="
if python3 - "$APP/build.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
def walk(o):
    if isinstance(o, dict):
        if o.get('id') == 'weather_today' and 'customization' in o: yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)
ws = list(walk(d)); assert ws
for w in ws:
    c = next(c for c in w['customization'] if c['key'] == 'refresh_minutes')
    assert c['options'] == ['240'] and c['default'] == '240', c
PY
then ok "weather refresh_minutes options [240], default 240"; else bad "weather refresh option is not 240 only"; fi

echo "== T5: no persistent location listener in the app =="
HITS="$(grep -rnE 'requestLocationUpdates|requestSingleUpdate|: LocationListener|LocationCallback|FusedLocationProviderClient' "$SRC" --include=*.kt | grep -vE '^[^:]+:[0-9]+:\s*(//|\*|/\*)' || true)"
if [ -z "$HITS" ]; then ok "no requestLocationUpdates / LocationListener / fused client in the app"; else bad "location subscription remains: $HITS"; fi

echo "== T6: nothing requests location when off =="
if code "$WS" | grep -Eq 'retiredIfOff\(this, WeatherBadgeService::class\.java\)\) \{ stopSelf'; then ok "onStartCommand retires before any location work"; else bad "no retiredIfOff gate at start"; fi
if code "$WS" | grep -Eq 'if \(!DataWidgetRuntime\.retiredIfOff\(this@WeatherBadgeService' && code "$WS" | grep -q 'schedule()'; then ok "next alarm armed only when on"; else bad "re-arm not gated on retiredIfOff"; fi

echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
