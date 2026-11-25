#!/usr/bin/env bash
# Tester (#1907): the Persistent Weather card draws NOTHING between its title and the graphic, then the seven declared rows
# (two columns) below it, in order, with the declared labels; what left the gap lives in the detail view (nothing dropped).
# Static contract over the sources (the render is pinned by WeatherRowsTest); each check is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
D=app/src/main/java/com/diegonmarcos/superapp
check() { python3 - "$1" <<'PY'
import os, re, sys
app = sys.argv[1]; D = "app/src/main/java/com/diegonmarcos/superapp"
rd = lambda p: open(os.path.join(app, D, p)).read()
rows, card, tl = rd("daytimelines/WeatherRows.kt"), rd("launcher/BadgeCards.kt"), rd("daytimelines/DayTimelinesCard.kt")
want = [("sun_moon", "Sun: Dawn / Sunrise / Sunset / Dusk", "Moon: Rise / Set"), ("temp", "Temp: min / max", "Feels like: min / max"),
        ("humid_wind", "Humidity: min / max", "Wind: min / max"), ("precip_uv", "Precipitation: probability / amount", "UV index"),
        ("pressure_dew", "Pressure: min / max", "Dew point: min~max (comfort)"), ("phase_zodiac", "Moon phase", "Sun zodiac / Moon zodiac"), ("update_place", "Last update: time", "Location")]
got = re.findall(r'Triple\("([a-z_]+)", "([^"]*)", "([^"]*)"\)', rows)
if got != want: print("W1 rows/labels/order differ from the ticket: %s" % got)
i = {k: card.find(k) for k in ("testTag(BadgeCardTags.title(", "if (!weather) {", "Text(d.headline", "if (weather)", "WeatherRowsBlock(d.badge.id, snap)", "WeatherDetail(d)")}
if min(i.values()) < 0: print("W2 missing: %s" % [k for k, v in i.items() if v < 0])
elif not (i["testTag(BadgeCardTags.title("] < i["if (!weather) {"] < i["Text(d.headline"] < i["if (weather)"] < i["WeatherRowsBlock(d.badge.id, snap)"] < i["WeatherDetail(d)"]):
    print("W2 the title, the guarded headline block, the graphic and the rows are not in that order")
j = (tl.find("LegendRow(m.legend"), tl.find("footer?.invoke(snap)"), tl.find("val notes ="))
if min(j) < 0 or not (j[0] < j[1] < j[2]): print("W3 the footer rows are not drawn after the graphic")
if "17.625 * tC / (243.04 + tC)" not in rows or "243.04 * g / (17.625 - g)" not in rows: print("W5 dew point is not Magnus-Tetens with a = 17.625, b = 243.04")
if not re.search(r'DEW_BANDS.*?Double\.NEGATIVE_INFINITY to "Dry and crisp".*?10\.0 to "Comfortable".*?16\.0 to "Noticeably humid".*?21\.0 to "Muggy and oppressive"', rows, re.S): print("W6 dew point bands differ from the declared 10 / 16 / 21 C bands")
svc = rd("notificationcenter/WeatherBadgeService.kt")
if "WeatherRows.notificationText(" not in svc or "WeatherRows.rows(snap)" not in svc: print("W7 the notification's expanded text is not built from WeatherRows")
det = card[card.find("private fun WeatherDetail"):].split("\n/**")[0]
for need in ("d.headline", "d.sub", "d.subText", "d.progress", "d.lines"):
    if need not in det: print("W4 the detail view dropped %s" % need)
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "W1-W4 rows, labels, order, empty gap, detail view"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/$D/daytimelines" "$T/app/$D/launcher" "$T/app/$D/notificationcenter"
  for f in daytimelines/WeatherRows.kt daytimelines/DayTimelinesCard.kt launcher/BadgeCards.kt notificationcenter/WeatherBadgeService.kt; do cp "$APP/$D/$f" "$T/app/$D/$f"; done
  python3 -c "$3" "$T/app/$D/$2"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
R=daytimelines/WeatherRows.kt; B=launcher/BadgeCards.kt; C=daytimelines/DayTimelinesCard.kt
rep() { echo "import sys;p=sys.argv[1];s=open(p).read();assert '$1' in s;open(p,'w').write(s.replace('$1','$2',1))"; }
mut "a label renamed" $R "$(rep 'Temp: min / max' 'Temperature')"
mut "two rows swapped" $R "import sys;p=sys.argv[1];s=open(p).read();a=s.index('        Triple(\"temp\"');b=s.index('        Triple(\"humid_wind\"');c=s.index('        Triple(\"precip_uv\"');open(p,'w').write(s[:a]+s[b:c]+s[a:b]+s[c:])"
mut "max / min order restored" $R "$(rep 'Temp: min / max' 'Temp: max / min')"
mut "wind pair back to max / min" $R "$(rep 'Wind: min / max' 'Wind: max / min')"
mut "dew point pair back to a single value" $R "$(rep 'Dew point: min~max (comfort)' 'Dew point')"
mut "Magnus constant changed" $R "$(rep '17.625 * tC' '17.62 * tC')"
mut "dew band boundary moved" $R "$(rep '16.0 to "Noticeably humid"' '17.0 to "Noticeably humid"')"
mut "notification no longer built from WeatherRows" notificationcenter/WeatherBadgeService.kt "$(rep 'WeatherRows.notificationText(' 'String.format(')"
mut "pressure row dropped" $R "$(rep '        Triple("pressure_dew", "Pressure: min / max", "Dew point: min~max (comfort)"),' '')"
mut "pressure row moved before precipitation" $R "import sys;p=sys.argv[1];s=open(p).read();a=s.index('        Triple(\"precip_uv\"');b=s.index('        Triple(\"pressure_dew\"');c=s.index('        Triple(\"phase_zodiac\"');open(p,'w').write(s[:a]+s[b:c]+s[a:b]+s[c:])"
mut "a row dropped" $R "$(rep '        Triple("update_place", "Last update: time", "Location"),' '')"
mut "headline drawn between title and graphic again" $B "$(rep 'if (!weather) {' 'if (true) {')"
mut "rows drawn before the graphic" $C "$(rep 'footer?.invoke(snap)' '')"
mut "the detail view loses the hourly lines" $B "$(rep 'for (l in d.lines) Row(Modifier.fillMaxWidth().padding(top = 4.dp))' 'for (l in emptyList<BadgeCardData.Line>()) Row(Modifier.fillMaxWidth().padding(top = 4.dp))')"
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
