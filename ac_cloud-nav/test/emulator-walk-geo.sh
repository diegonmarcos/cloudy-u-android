#!/usr/bin/env bash
# #1851 the walk the emulator test needs: home -> walk -> cafe -> tram -> office -> walk home -> home, injected with `adb emu geo fix`
# (one fix every STEP seconds; stays are 11 min so the 9 min heartbeat of the tracker gate stores a second fix in each).
# Run it in parallel to TimelineWalkTest (`walk=1`); it writes /data/local/tmp/walk-done when the last fix is out.
#   bash ac_cloud-nav/test/emulator-walk-geo.sh [step seconds, default 20]
set -u
STEP=${1:-20}
adb wait-for-device
adb shell rm -f /data/local/tmp/walk-done /data/local/tmp/walk-start || true
# the build and the app install take a while: start injecting only when TimelineWalkTest has started the tracker
until adb shell test -e /data/local/tmp/walk-start; do sleep 5; done
echo "tracker started, walking"
# metres east/north of home (52.5 N, 13.4 E) -> lon/lat
python3 - "$STEP" <<'PY' > /tmp/walk-fixes.txt
import math, sys
step = int(sys.argv[1]); lat0, lon0 = 52.5, 13.4
def ll(e, n): return lon0 + e / (111195.0 * math.cos(math.radians(lat0))), lat0 + n / 111195.0
HOME, CAFE, OFFICE = (0, 0), (500, 0), (0, 4000)
# realistic speeds (walk 1.4 m/s, tram/bus ~11 m/s) and 11 min stays: the tracker's passive gate stores a fix per 25 m, and a still heartbeat every 9 min
legs = [("home", 660, HOME, HOME), ("walk", 360, HOME, CAFE), ("cafe", 660, CAFE, CAFE), ("tram", 360, CAFE, OFFICE),
        ("office", 660, OFFICE, OFFICE), ("bus-home", 360, OFFICE, HOME), ("home", 660, HOME, HOME)]
for name, dur, a, b in legs:
    n = max(1, dur // step)
    for i in range(n + 1):
        f = i / n; j = 3.0 if name in ("home", "cafe", "office") and i % 2 else 0.0   # a little jitter while still
        lon, lat = ll(a[0] + (b[0] - a[0]) * f + j, a[1] + (b[1] - a[1]) * f)
        print(f"{lon:.6f} {lat:.6f} {name}")
PY
total=$(wc -l < /tmp/walk-fixes.txt); n=0
while read -r lon lat name; do
  adb emu geo fix "$lon" "$lat" >/dev/null || echo "::warning::geo fix failed at $n"
  n=$((n + 1)); [ $((n % 15)) = 0 ] && echo "walk: $n/$total ($name)"
  sleep "$STEP"
done < /tmp/walk-fixes.txt
adb shell "touch /data/local/tmp/walk-done; chmod 666 /data/local/tmp/walk-done"
echo "walk done: $total fixes"
