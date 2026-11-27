#!/bin/bash
# #1952 fully cached, the emulator walk (workflow test-mail-cache-1952b-emulator.yml). A fake JMAP server on the runner holds
# FAKE_JMAP_BULK older Inbox messages (default 40000). The first full sync runs for FILL_MINUTES, then:
#   S  the Configs section with "Keep message bodies for", "Body cache limit" and the usage line is screenshotted
#   C  the app is killed and started COLD at that fixture: rows from the store BEFORE the first server call (logcat order of the app's
#      own MailList lines), time to the first row against COLD_LIMIT_MS, scroll jank from dumpsys gfxinfo
#   A  airplane mode: an old message is opened and its body shows from the store
# Needs $PKG and a running emulator. Evidence in $OUT (screenshots mail-1952b-*.png, timing.md, sizes.txt). No message data is printed.
set -u
OUT=${OUT:-walk-out-1952b}
PORT=8099
UI="python3 -I ac_cloud-mail/test/mail-offline-ui.py"
FAIL=0
COLD_LIMIT_MS=${COLD_LIMIT_MS:-1500}
WARM_LIMIT_MS=${WARM_LIMIT_MS:-800}
BULK=${FAKE_JMAP_BULK:-40000}
FILL_MINUTES=${FILL_MINUTES:-30}
READING_ENTRY=${READING_ENTRY:-Reading}
case "$BULK$FILL_MINUTES" in *[!0-9]*) echo "::error::FAKE_JMAP_BULK and FILL_MINUTES are numbers"; exit 2 ;; esac
mkdir -p "$OUT"
fail() { echo "::error::$1"; echo "FAIL: $1" >> "$OUT/summary.txt"; FAIL=1; }
note() { echo "$1" | tee -a "$OUT/summary.txt"; }

FAKE_JMAP_BULK=$BULK python3 -I ac_cloud-mail/test/fake-jmap-server.py --port $PORT > "$OUT/fake-jmap.log" 2>&1 &
SRV=$!
sleep 5
curl -s -m 20 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-00-start.json" || { fail "the fake JMAP server did not start"; exit 1; }

adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
sleep 45
adb install -r -g app-under-test.apk || { echo "::error::adb install failed"; exit 2; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb logcat -v threadtime "*:W" MailList:I MailCore:I MailSync:I MailOpen:I NavWalkSeed:I > "$OUT/logcat.txt" 2>&1 &
LP=$!

shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; adb exec-out screencap -p > "$OUT/mail-1952b-$1.png"; }
dump() {
  for t in 1 2 3 4 5 6; do
    adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > "$OUT/$1.xml"; grep -q "<hierarchy" "$OUT/$1.xml" && break; sleep 3
  done
  $UI texts "$OUT/$1.xml" > "$OUT/$1.texts.txt" 2>/dev/null || true
  if grep -q "isn't responding" "$OUT/$1.texts.txt"; then pt=$($UI tap "$OUT/$1.xml" "Wait" exact) && adb shell input tap $pt; sleep 2; fi
}
# the app on screen, whatever happened before (a first full sync of 40k may get the process killed by the system: counted, see DEATHS)
front() {
  if ! adb shell dumpsys activity activities 2>/dev/null | grep -E "ResumedActivity|topResumedActivity" | grep -q "$PKG"; then
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep "${1:-8}"
  fi
}
DEATHS=0; LASTPID=""
pidwatch() {
  p=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
  if [ -n "$LASTPID" ] && [ "$p" != "$LASTPID" ]; then DEATHS=$((DEATHS + 1)); echo "process changed at $(date +%T): '$LASTPID' -> '$p'" >> "$OUT/process.txt"; fi
  [ -n "$p" ] && LASTPID=$p
}
mem() { { echo "== $1"; adb shell dumpsys meminfo "$PKG" 2>/dev/null | grep -E "TOTAL PSS|Java Heap|Native Heap|TOTAL RSS" | head -4; adb shell cat /proc/meminfo | grep -E "MemAvailable"; } >> "$OUT/memory.txt" 2>&1; }
tapt() { dump scan; pt=$($UI tap "$OUT/scan.xml" "$1" "${2:-}") || return 1; adb shell input tap $pt; }
skip_welcome() { if tapt "Skip" exact; then sleep 4; fi; }
# what the app holds on the device: the Room store and its body table (sizes only)
sizes() {
  {
    echo "== $1"
    adb shell "run-as $PKG sh -c 'ls -l databases/sterna.db databases/sterna.db-wal 2>/dev/null; du -sk databases files cache 2>/dev/null'"
    for q in "select 'headers', count(*) from emails" "select 'bodies', count(*) from email_bodies"; do
      adb shell "run-as $PKG sqlite3 databases/sterna.db \"$q\"" 2>&1 | head -2
    done
  } >> "$OUT/sizes.txt" 2>&1
}

adb shell am broadcast -n "$PKG/app.sterna.navwalk.NavWalkSeedReceiver" -a app.sterna.test.navwalk.SEED --es jmap "http://10.0.2.2:$PORT"
sleep 3
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 15
skip_welcome
synced=0
for i in $(seq 1 60); do
  front 10
  dump 01-inbox
  if $UI has "$OUT/01-inbox.xml" "Weekly Digest 42" >/dev/null 2>&1; then synced=1; break; fi
  skip_welcome; sleep 5
done
[ "$synced" = 1 ] || fail "the online sync never showed the seeded Inbox"
shot 01-first-sync

# ---- the first full sync: every minute a pull to refresh (one backfill page of the Inbox each), the store's size on record ----------
pidwatch
for m in $(seq 1 "$FILL_MINUTES"); do
  front 8; pidwatch
  adb shell input swipe 700 700 700 1500 300
  sleep 50
  if [ $((m % 5)) = 0 ]; then
    sizes "minute $m"; mem "minute $m"
    curl -s -m 20 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-fill-$m.json" || true
  fi
done
pidwatch
note "process deaths during the fill: $DEATHS"
[ "$DEATHS" = 0 ] || echo "::warning::the app process was replaced $DEATHS time(s) during the first full sync (see process.txt, memory.txt)"
H=$(grep -A99 "minute" "$OUT/sizes.txt" | grep "^headers|" | tail -1 | cut -d"|" -f2)
[ "${H:-0}" -ge "$BULK" ] 2>/dev/null || fail "the first full sync did not converge: ${H:-?} headers of $BULK after $FILL_MINUTES min"
front 8
sizes "after the fill ($FILL_MINUTES min, server holds $BULK bulk messages)"
shot 02-inbox-filled
grep -E "inbox backlog" "$OUT/logcat.txt" | tail -3 | sed 's/.*inbox backlog [^:]*:/inbox backlog:/' | tee -a "$OUT/summary.txt"

# ---- S: the Configs section with the two cache settings and the usage line ----------------------------------------------------------
found=0
front 8
if tapt "Configs" exact || tapt "Settings" exact; then
  sleep 3
  # the Offline section is on the Reading page of the Configs tree
  for s in 1 2 3 4 5 6; do
    if tapt "$READING_ENTRY"; then sleep 3; break; fi
    adb shell input swipe 700 1500 700 900 400; sleep 1
  done
  for s in $(seq 1 14); do
    dump 03-settings
    if $UI has "$OUT/03-settings.xml" "Body cache limit" >/dev/null 2>&1; then found=1; break; fi
    adb shell input swipe 700 1500 700 700 400; sleep 1
  done
fi
shot 03-settings-cache
if [ "$found" = 1 ]; then
  grep -E "Keep message bodies for|Body cache limit| of .* bodies" "$OUT/03-settings.texts.txt" | head -4 | tee -a "$OUT/summary.txt"
  grep -q -E " of 3 GB .* bodies" "$OUT/03-settings.texts.txt" || fail "settings: no usage line next to the limit ('… of 3 GB · N bodies')"
  grep -q "Keep message bodies for" "$OUT/03-settings.texts.txt" || fail "settings: no 'Keep message bodies for' row"
else
  fail "settings: the 'Body cache limit' row was not found"
fi

# ---- C: cold start at the fixture ---------------------------------------------------------------------------------------------------
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; sleep 2
front 8; tapt "Mail" exact; sleep 3
mem "before the cold starts"
echo "| step | time to first row (ms) | rows | rows painted before the first server call | store query of the first page (ms) |" > "$OUT/timing.md"
echo "|---|---|---|---|---|" >> "$OUT/timing.md"
for round in 1 2 3; do
  adb shell am force-stop "$PKG"
  sleep 3
  n0=$(grep -c "" "$OUT/logcat.txt")
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2
  [ "$round" = 1 ] && shot 04-cold-start-after-2s
  sleep 10
  tail -n +$((n0 + 1)) "$OUT/logcat.txt" | grep -E "MailList" > "$OUT/cold-$round.log" || true
  ms=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  rows=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\2/p' "$OUT/cold-$round.log" | head -1)
  lp=$(grep -n "cold-start rows-painted" "$OUT/cold-$round.log" | head -1 | cut -d: -f1)
  ln=$(grep -n "cold-start first-network" "$OUT/cold-$round.log" | head -1 | cut -d: -f1)
  order=unknown
  if [ -n "$lp" ] && [ -z "$ln" ]; then order="yes (no server call in 12 s)"; fi
  if [ -n "$lp" ] && [ -n "$ln" ]; then
    if [ "$lp" -lt "$ln" ] && grep -q "cold-start first-network.*after-rows=true" "$OUT/cold-$round.log"; then order=yes; else order=NO; fi
  fi
  q=$(sed -n 's/.*page-load kind=Refresh ms=\([0-9]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  echo "| cold start $round at the fixture | ${ms:-missing} | ${rows:-} | $order | ${q:-} |" >> "$OUT/timing.md"
  [ -n "$ms" ] || fail "cold start $round: the list logged no first row"
  case "$order" in yes*) ;; *) fail "cold start $round: rows were not painted before the first server call ($order)" ;; esac
  [ "${ms:-99999}" -le "$COLD_LIMIT_MS" ] 2>/dev/null || fail "cold start $round: first row after ${ms:-?} ms (limit $COLD_LIMIT_MS)"
done
shot 05-cold-start-settled
# warm: leave Mail for Alerts and come back, twice
for w in 1 2; do
  n0=$(grep -c -E "MailList.*first-row" "$OUT/logcat.txt")
  tapt "Alerts" exact || fail "warm $w: no Alerts item in the bottom bar"
  sleep 4
  tapt "Mail" exact || fail "warm $w: no Mail item in the bottom bar"
  sleep 4
  n1=$(grep -c -E "MailList.*first-row" "$OUT/logcat.txt")
  wms=missing; [ "$n1" -gt "$n0" ] && wms=$(grep -E "MailList.*first-row" "$OUT/logcat.txt" | tail -1 | sed -n 's/.*first-row ms=\([0-9]*\).*/\1/p')
  echo "| warm return $w from Alerts | $wms | | - | |" >> "$OUT/timing.md"
  [ "$wms" != missing ] || fail "warm $w: the list logged no first row"
  [ "${wms:-99999}" -le "$WARM_LIMIT_MS" ] 2>/dev/null || fail "warm $w: first row after $wms ms (limit $WARM_LIMIT_MS)"
done

# scroll jank over ten flings of the list
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
for j in $(seq 1 10); do adb shell input swipe 700 1600 700 500 250; sleep 1; done
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" | head -6 > "$OUT/jank.txt"
cat "$OUT/jank.txt" | tee -a "$OUT/summary.txt"
echo "" >> "$OUT/timing.md"; echo "scroll (10 flings):" >> "$OUT/timing.md"; sed 's/^ */- /' "$OUT/jank.txt" >> "$OUT/timing.md"
shot 06-scrolled

# ---- A: airplane mode, an old message opens with its body ----------------------------------------------------------------------------
adb shell cmd connectivity airplane-mode enable
sleep 5
# an explicit relaunch after the toggle: the body must come from the store of a process that never had a network
adb shell am force-stop "$PKG"; sleep 2
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
front 8
for j in 1 2 3 4; do adb shell input swipe 700 1600 700 500 250; sleep 1; done
opened=0
for s in 1 2 3; do
  dump 07-list
  pt=$($UI tap "$OUT/07-list.xml" "Bulk message") && { adb shell input tap $pt; opened=1; break; }
  adb shell input swipe 700 1600 700 800 400; sleep 1
done
[ "$opened" = 1 ] || fail "airplane: no bulk row on screen to open"
t0=$(date +%s); body=0
for k in $(seq 1 15); do
  sleep 3
  dump 07-reader
  if grep -q "Bulk body" "$OUT/07-reader.texts.txt" && ! grep -q "Conversation, " "$OUT/07-reader.texts.txt"; then body=1; break; fi
done
note "airplane: the reader showed the body after about $(( $(date +%s) - t0 )) s"
shot 07-airplane-old-body
if [ "$body" = 1 ]; then note "airplane: the body of an old message shows from the store"; else fail "airplane: the opened old message shows no body"; fi
grep -E "MailOpen" "$OUT/logcat.txt" | tail -4 | sed 's/id=[^ ]*/id=…/' >> "$OUT/summary.txt"
adb shell cmd connectivity airplane-mode disable

sizes "end"
cat "$OUT/timing.md" | tee -a "$OUT/summary.txt"
cat "$OUT/sizes.txt"
grep -E "MailList" "$OUT/logcat.txt" > "$OUT/maillist-log.txt" || true
kill "$LP" 2>/dev/null || true
kill "$SRV" 2>/dev/null || true
exit $FAIL
