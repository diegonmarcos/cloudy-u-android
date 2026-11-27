#!/bin/bash
# #1952 tab-switch proof (workflow test-mail-tab-switch-1952-emulator.yml). The test variant syncs a fake JMAP server on the runner
# (test/fake-jmap-server.py with FAKE_JMAP_BULK older Inbox messages), the list is scrolled, then Mail -> Alerts -> Mail is walked three
# times: each return must show the same rows at the same scroll position, from the SAME list ViewModel, and the app's own stopwatch
# (logcat tag MailList, "first-row ms=") gives the time to the first painted row. Then the server answers 502 to everything, the app is
# killed and relaunched: the cached list must show, under a banner with the failure, the last sync and Retry.
# EXPECT_BROKEN=1 (the workflow's mutation input, the code before the fix): nothing is asserted but that the walk REPRODUCES the defect
# (more than one list ViewModel). Needs $PKG and a running emulator. Evidence in $OUT.
set -u
OUT=${OUT:-walk-out-1952}
PORT=8099
UI="python3 -I ac_cloud-mail/test/mail-offline-ui.py"
FAIL=0
LIMIT_MS=${LIMIT_MS:-300}
mkdir -p "$OUT"
fail() { echo "::error::$1"; echo "FAIL: $1" >> "$OUT/summary.txt"; FAIL=1; }
note() { echo "$1" | tee -a "$OUT/summary.txt"; }

FAKE_JMAP_BULK=${FAKE_JMAP_BULK:-300} python3 -I ac_cloud-mail/test/fake-jmap-server.py --port $PORT > "$OUT/fake-jmap.log" 2>&1 &
SRV=$!
sleep 2
curl -s -m 5 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-00-start.json" || { fail "the fake JMAP server did not start"; exit 1; }

adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
sleep 45 # let the launcher settle after boot
adb install -r -g app-under-test.apk || { echo "::error::adb install failed"; exit 2; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb logcat -v threadtime "*:W" MailList:I MailCore:I MailSync:I NavWalkSeed:I > "$OUT/logcat.txt" 2>&1 &
LP=$!

shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; adb exec-out screencap -p > "$OUT/mail-1952-$1.png"; }
dump() {
  for round in 1 2 3; do
    for t in 1 2 3 4 5 6; do
      adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      adb exec-out cat /sdcard/ui.xml > "$OUT/$1.xml"; grep -q "<hierarchy" "$OUT/$1.xml" && break; sleep 3
    done
    $UI texts "$OUT/$1.xml" > "$OUT/$1.texts.txt" 2>/dev/null || true
    if grep -q "isn't responding" "$OUT/$1.texts.txt"; then
      pt=$($UI tap "$OUT/$1.xml" "Wait" exact) && adb shell input tap $pt; sleep 2
    else
      break
    fi
  done
}
tapt() { dump scan; pt=$($UI tap "$OUT/scan.xml" "$1" "${2:-}") || return 1; adb shell input tap $pt; }
skip_welcome() { if tapt "Skip" exact; then sleep 4; fi; }
# the n-th "first-row" line of the app's stopwatch: "ms rows view-models"
row() { grep -E "MailList.*first-row" "$OUT/logcat.txt" | sed -n "${1}p" | sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\) view-models=\([0-9]*\).*/\1 \2 \3/p'; }
rows_logged() { grep -c -E "MailList.*first-row" "$OUT/logcat.txt"; }
# the subjects on screen, in order
subjects() { grep -E "^(Bulk message|Plain hello|Weekly Digest|Receipt 1001|Espa)" "$OUT/$1.texts.txt" | head -3 | tr '\n' '|'; }

adb shell am broadcast -n "$PKG/app.sterna.navwalk.NavWalkSeedReceiver" -a app.sterna.test.navwalk.SEED --es jmap "http://10.0.2.2:$PORT"
sleep 3
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 15
skip_welcome
synced=0
for i in $(seq 1 24); do
  dump 01-inbox
  if $UI has "$OUT/01-inbox.xml" "Weekly Digest 42" >/dev/null 2>&1; then synced=1; break; fi
  skip_welcome; sleep 5
done
[ "$synced" = 1 ] || fail "the online sync never showed the seeded Inbox"
sleep 40 # the backlog pages of the bulk messages and the body prefetch
shot 01-inbox-synced
curl -s -m 5 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-01-synced.json" || true
# scroll, so a kept position is distinguishable from a list rebuilt at the top
for j in 1 2 3; do adb shell input swipe 700 1500 700 600 400; sleep 1; done
adb shell input swipe 700 900 700 1150 600 # a short drag back: the bottom bar hides while the list moves down and returns on this
sleep 2
dump 02-scrolled; shot 02-scrolled
BEFORE=$(subjects 02-scrolled)
note "rows on screen after the scroll: $BEFORE"
[ -n "$BEFORE" ] || fail "no seeded row on screen after the scroll"

echo "| step | time to first row (ms) | rows | list view models | same rows as before |" > "$OUT/timing.md"
echo "|---|---|---|---|---|" >> "$OUT/timing.md"
set -- $(row 1); echo "| first open (cold, online) | ${1:-missing} | ${2:-} | ${3:-} | - |" >> "$OUT/timing.md"
for i in 1 2 3; do
  n0=$(rows_logged)
  tapt "Alerts" exact || fail "round $i: no Alerts item in the bottom bar"
  sleep 4
  shot "03-round$i-a-alerts"
  tapt "Mail" exact || fail "round $i: no Mail item in the bottom bar"
  sleep 1
  shot "03-round$i-b-mail-after-1s"
  sleep 3
  dump "03-round$i-mail"; shot "03-round$i-c-mail-settled"
  AFTER=$(subjects "03-round$i-mail")
  same=no; [ "$AFTER" = "$BEFORE" ] && same=yes
  n1=$(rows_logged)
  if [ "$n1" -gt "$n0" ]; then set -- $(row "$n1"); else set -- missing "" ""; fi
  echo "| return $i from Alerts | ${1:-missing} | ${2:-} | ${3:-} | $same |" >> "$OUT/timing.md"
  if [ "${EXPECT_BROKEN:-0}" != 1 ]; then
    [ "${1:-missing}" != missing ] || fail "round $i: the list logged no first row after the return"
    [ "${1:-99999}" -le "$LIMIT_MS" ] 2>/dev/null || fail "round $i: first row after ${1:-?} ms (limit $LIMIT_MS)"
    [ "${3:-0}" = 1 ] || fail "round $i: ${3:-?} list view models (the return rebuilt the list)"
    [ "$same" = yes ] || fail "round $i: the rows or the scroll position changed: '$AFTER' was '$BEFORE'"
  fi
done
VMS=$(grep -c -E "MailList.*view-model created" "$OUT/logcat.txt")
note "list view models created over three round trips: $VMS"

# ---- the server answers 502 to everything; kill and relaunch ---------------------------------------------------------------------
curl -s -m 5 "http://127.0.0.1:$PORT/__fail?code=502" > "$OUT/stats-02-fail.json"
adb shell am force-stop "$PKG"
sleep 2
n0=$(rows_logged)
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 3
shot 04-502-relaunch-after-3s
banner=0
for i in $(seq 1 15); do
  sleep 4
  dump 04-502-relaunch
  if $UI has "$OUT/04-502-relaunch.xml" "Retry" >/dev/null 2>&1; then banner=1; break; fi
done
shot 04-502-relaunch-banner
n1=$(rows_logged)
if [ "$n1" -gt "$n0" ]; then set -- $(row "$n1"); else set -- missing "" ""; fi
echo "| cold relaunch, server answers 502 | ${1:-missing} | ${2:-} | ${3:-} | - |" >> "$OUT/timing.md"
grep -E "Sync failed|offline|Last sync|Never synced|Retry" "$OUT/04-502-relaunch.texts.txt" | head -6 | tee "$OUT/banner.txt"
if [ "${EXPECT_BROKEN:-0}" != 1 ]; then
  $UI has "$OUT/04-502-relaunch.xml" "Weekly Digest 42" >/dev/null 2>&1 || fail "502 relaunch: the cached Inbox is not on screen"
  [ "$banner" = 1 ] || fail "502 relaunch: no banner with Retry over the cached list"
  grep -q -E "Last sync" "$OUT/banner.txt" || fail "502 relaunch: the banner does not say the last sync"
  grep -q -E "Sync failed|offline" "$OUT/banner.txt" || fail "502 relaunch: the banner does not say why"
  [ "${1:-missing}" != missing ] || fail "502 relaunch: the list logged no first row"
fi
# one more round trip with the server failing
n0=$(rows_logged)
tapt "Alerts" exact; sleep 4; tapt "Mail" exact; sleep 3
dump 05-502-return; shot 05-502-return
n1=$(rows_logged)
if [ "$n1" -gt "$n0" ]; then set -- $(row "$n1"); else set -- missing "" ""; fi
echo "| return from Alerts, server answers 502 | ${1:-missing} | ${2:-} | ${3:-} | - |" >> "$OUT/timing.md"
if [ "${EXPECT_BROKEN:-0}" != 1 ]; then
  $UI has "$OUT/05-502-return.xml" "Weekly Digest 42" >/dev/null 2>&1 || fail "502 return: the cached Inbox is not on screen"
  [ "${1:-99999}" -le "$LIMIT_MS" ] 2>/dev/null || fail "502 return: first row after ${1:-?} ms (limit $LIMIT_MS)"
else
  [ "$VMS" -gt 1 ] || fail "the mutation did not reproduce the defect: one list view model only"
fi
curl -s -m 5 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-03-end.json" || true
cat "$OUT/timing.md" | tee -a "$OUT/summary.txt"
grep -E "MailList" "$OUT/logcat.txt" > "$OUT/maillist-log.txt" || true
kill "$LP" 2>/dev/null || true
kill "$SRV" 2>/dev/null || true
exit $FAIL
