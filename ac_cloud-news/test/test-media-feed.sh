#!/usr/bin/env bash
# Cloud News Media feed. Holds, statically (no network, no device):
#  1. the node suite passes under three time zones (relative times and dates): tests/media-core.test.js, which parses the yt: namespace
#     fixture videos.xml, resolves a handle, orders the feed, maps videos to cards, builds the embed URL and FAILS an empty feed with valid sources;
#  2. data/media.json declares the channels, the chips and the cache / auto-refresh rules the page reads;
#  3. the page wires it: news.html loads media-core.js and media-ui.js, every MediaBridge method the UI calls exists in MediaBridge.kt,
#     the player is the official nocookie embed in MainActivity's overlay, rotation does not recreate the activity;
#  4. the battery rules hold: no timers in the Media UI, no sp literal, the fetch door allows only YouTube feeds, pages and the Data API.
# Usage: ./test-media-feed.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
A="$APP/app/src/main/assets"; K="$APP/app/src/main/java/com/diegonmarcos/cloudnews"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }

for tz in Europe/Berlin America/Los_Angeles Asia/Tokyo; do
  if out="$(cd "$APP" && TZ="$tz" node tests/media-core.test.js 2>&1)"; then ok "media-core.test.js under $tz ($out)"; else bad "media-core.test.js under $tz"; echo "$out" | head -20; fi
done

D="$APP/data/media.json"
jq -e . "$D" >/dev/null 2>&1 && ok "media.json is valid JSON" || { bad "media.json is not valid JSON"; exit 1; }
[ "$(jq '([.channels[] | select((.channel_id // .playlist_id // .handle) != null)] | length) == (.channels | length)' "$D")" = "true" ] && ok "every channel has a channel_id, playlist_id or handle" || bad "a channel has no source id"
[ "$(jq '[.channels[].id] | length == (unique | length)' "$D")" = "true" ] && ok "one row per channel" || bad "channel ids repeat"
[ "$(jq -r '.chips | join(",")' "$D")" = "all,shorts,live,category,channel" ] && ok "chips: all, shorts, live, category, channel" || bad "chips order"
[ "$(jq -r '.feed.auto_refresh.require_unmetered_network' "$D")" = "true" ] && ok "automatic refresh needs an unmetered network" || bad "automatic refresh gate"

H="$A/news.html"
for f in media-core.js media-ui.js; do
  [ -s "$A/$f" ] && grep -q "$f" "$H" && ok "$f ships and news.html loads it" || bad "$f ships and news.html loads it"
done
for m in config fetchStart fetchResult hasKey setKey resume play setMode; do
  grep -q "bcall('$m'" "$A/media-ui.js" && grep -q "fun $m(" "$K/MediaBridge.kt" && ok "bridge method $m is called and implemented" || bad "bridge method $m is called and implemented"
done
grep -q '"MediaBridge"' "$K/MainActivity.kt" && ok "MainActivity registers MediaBridge" || bad "MainActivity registers MediaBridge"
grep -q 'https://www.youtube-nocookie.com/embed/' "$K/MediaPlayerOverlay.kt" && grep -q 'youtube-nocookie.com/embed/' "$A/media-core.js" && ok "the player and the URL builder use the official nocookie embed" || bad "embed host"
grep -q 'android:configChanges="[^"]*orientation' "$APP/app/src/main/AndroidManifest.xml" && ok "rotation does not recreate the activity (fullscreen keeps playing)" || bad "configChanges"

grep -qE 'setInterval|requestAnimationFrame' "$A/media-ui.js" && bad "the Media UI has a timer loop" || ok "no polling loop in the Media UI (only a running fetch job is polled)"
grep -E '[0-9]\s*sp\b|\.sp\b' "$K"/Media*.kt "$A/media-ui.js" "$A/media-core.js" >/dev/null 2>&1 && bad "an sp literal in the Media code" || ok "no sp literal in the Media code"
hosts="$(grep -oE 'u\.host (in|==) [A-Za-z_"./]+' "$K/MediaFetcher.kt" | tr '\n' ' ')"
case "$hosts" in *YT_HOSTS*'www.googleapis.com'*) ok "the fetch door names YouTube and the Data API only";; *) bad "fetch door hosts: $hosts";; esac
grep -q 'autoAllowed' "$K/MediaFetcher.kt" && grep -q 'NET_CAPABILITY_NOT_METERED' "$K/MediaBridge.kt" && ok "the automatic refresh is gated (unmetered, charging when asked)" || bad "auto gate"

echo; echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
