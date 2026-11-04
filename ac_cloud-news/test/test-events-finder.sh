#!/usr/bin/env bash
# #1465 Cloud News Events finder. Holds, statically (no network, no device):
#  1. the node suites pass under three time zones (date ranges are local-time maths): events-core (filters, When ranges, city search,
#     request building, robots.txt, prefs, month grid, the source parsers against tests/fixtures/, runFinder), plus the swipe and
#     event-date suites the Events tab shares code with;
#  2. data/event-finder.json declares what the owner asked for (the 8 categories, the 5 When rules, the 3 views, one row per
#     ticketer with its fetch kind / hosts / terms / cache, a ticketer from each of Eventim, RA, Ticketmaster, Dice, Eventbrite);
#  3. the page wires it: news.html loads events-core.js, events-ui.js and events-finder.css, and every NewsBridge method the UI
#     calls exists in NewsBridge.kt; the bundle assets exist.
# Usage: ./test-events-finder.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }

for tz in Europe/Berlin America/Los_Angeles Asia/Tokyo; do
  for t in events-core swipe eventdate; do
    if out="$(cd "$APP" && TZ="$tz" node "tests/$t.test.js" 2>&1)"; then ok "$t.test.js under $tz"; else bad "$t.test.js under $tz"; echo "$out" | head -20; fi
  done
done

D="$APP/data/event-finder.json"
jq -e . "$D" >/dev/null 2>&1 && ok "event-finder.json is valid JSON" || { bad "event-finder.json is not valid JSON"; exit 1; }
[ "$(jq -r '[.categories[].id] | join(",")' "$D")" = "all,sports,raves,art,music,theatre,food,other" ] && ok "categories" || bad "categories"
[ "$(jq -r '[.when[].rule] | join(",")' "$D")" = "today,week,weekend,month,custom" ] && ok "when rules" || bad "when rules"
[ "$(jq -r '[.views[].id] | join(",")' "$D")" = "calendar,list,cards" ] && ok "views" || bad "views"
for id in eventim ra ticketmaster dice eventbrite; do
  jq -e --arg id "$id" '.ticketers[] | select(.id == $id) | .fetch.kind and .terms_url and (.cache_hours > 0)' "$D" >/dev/null 2>&1 && ok "ticketer $id declared" || bad "ticketer $id declared"
done
[ "$(jq '[.ticketers[].id] | length == (unique | length)' "$D")" = "true" ] && ok "one row per ticketer" || bad "ticketer ids repeat"

H="$APP/app/src/main/assets/news.html"
for f in events-core.js events-ui.js events-finder.css; do
  [ -s "$APP/app/src/main/assets/$f" ] && grep -q "$f" "$H" && ok "$f ships and news.html loads it" || bad "$f ships and news.html loads it"
done
for m in eventFinder eventFetchStart eventFetchResult eventSetKey eventHasKey; do
  grep -q "bcall('$m'" "$APP/app/src/main/assets/events-ui.js" && grep -q "fun $m(" "$APP/app/src/main/java/com/diegonmarcos/cloudnews/NewsBridge.kt" && ok "bridge method $m is called and implemented" || bad "bridge method $m is called and implemented"
done
grep -q 'EVENT_FINDER_B64' "$APP/app/build.gradle" && ok "build.gradle bakes the finder data" || bad "build.gradle bakes the finder data"

echo; echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
