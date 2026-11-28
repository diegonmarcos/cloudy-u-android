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
WARM_LIMIT_MS=${WARM_LIMIT_MS:-300}
# scroll: p90 frame time over ten flings (dumpsys gfxinfo), with a sync running and in airplane mode (no sync)
# The absolute targets (the phone's, with a real GPU) are reported. The emulator renders with a software GPU: the system Settings list, scrolled
# the same way in the same run, is the floor (run 46095017985: p90 250 ms, 95 % janky frames, cmd+swap 90 % of a frame). The run FAILS when the
# app is more than JANK_FLOOR_SYNC_X / JANK_FLOOR_QUIET_X times that floor, or one row takes more than a fraction of the control's frame time to compose (warm windows).
JANK_P90_SYNC_MS=${JANK_P90_SYNC_MS:-100}
JANK_P90_QUIET_MS=${JANK_P90_QUIET_MS:-50}
JANK_FLOOR_SYNC_X=${JANK_FLOOR_SYNC_X:-3}
JANK_FLOOR_QUIET_X=${JANK_FLOOR_QUIET_X:-2}
# a row may take at most 1/ROW_FRACTION of the frame time the idle system list takes on this emulator (its p50), never less than ROW_COMPOSE_FLOOR_MS
ROW_FRACTION=${ROW_FRACTION:-4}
ROW_COMPOSE_FLOOR_MS=${ROW_COMPOSE_FLOOR_MS:-4}
BULK=${FAKE_JMAP_BULK:-40000}
FILL_MINUTES=${FILL_MINUTES:-30}
READING_ENTRY=${READING_ENTRY:-Reading}
case "$BULK$FILL_MINUTES" in *[!0-9]*) echo "::error::FAKE_JMAP_BULK and FILL_MINUTES are numbers"; exit 2 ;; esac
mkdir -p "$OUT"
fail() { echo "::error::$1"; echo "FAIL: $1" >> "$OUT/summary.txt"; FAIL=1; }
note() { echo "$1" | tee -a "$OUT/summary.txt"; }
# ten flings of the list with the evidence of WHAT is slow: JankProbe lines (stages per slow frame, named sections), framestats, a best-effort Perfetto trace
# $1 label, $2 p90 limit (ms), $3 file for the gfxinfo summary lines
scroll_measure() {
  local label=$1 limit=$2 sumfile=$3 l0 p90
  l0=$(grep -c "" "$OUT/logcat.txt")
  adb shell "rm -f /data/misc/perfetto-traces/scroll-$label.pb" >/dev/null 2>&1 || true
  adb shell "perfetto -o /data/misc/perfetto-traces/scroll-$label.pb -t 14s -b 32mb sched freq gfx view wm am input -a $PKG" >/dev/null 2>&1 &
  local PF=$!
  sleep 1
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
  for j in $(seq 1 10); do adb shell input swipe 700 1600 700 500 250; sleep 1; done
  adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" | head -6 > "$sumfile"
  adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT/framestats-$label.txt" 2>&1 || true
  wait $PF 2>/dev/null || true
  adb pull "/data/misc/perfetto-traces/scroll-$label.pb" "$OUT/scroll-$label.pb" >/dev/null 2>&1 || note "perfetto: no trace for $label (best effort)"
  sleep 1
  tail -n +$((l0 + 1)) "$OUT/logcat.txt" | grep "JankProbe" > "$OUT/jankprobe-$label.txt" || true
  {
    echo ""; echo "what is slow while scrolling ($label), from the app's own JankProbe lines:"
    python3 -I - "$OUT/jankprobe-$label.txt" <<'PY'
import re, sys
secs, slow_total, stages = {}, 0, {k: [] for k in ("input", "anim", "layout", "draw", "sync", "cmd", "swap")}
frames = 0
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"frames=(\d+) slow=(\d+)", line)
    if not m: continue
    frames += int(m.group(1)); n = int(m.group(2)); slow_total += n
    if n:
        for k in stages:
            v = re.search(k + r"=(\d+)", line.split("slow-mean(")[1] if "slow-mean(" in line else "")
            if v: stages[k].append((int(v.group(1)), n))
    for name, cnt, ssum, smax in re.findall(r"\| ([\w.]+) n=(\d+) sum=(\d+) max=(\d+)", line):
        a = secs.setdefault(name, [0, 0, 0]); a[0] += int(cnt); a[1] += int(ssum); a[2] = max(a[2], int(smax))
print("- frames seen: %d, slower than 48 ms: %d" % (frames, slow_total))
if slow_total:
    print("- mean stage time over the slow frames (ms): " + ", ".join("%s=%d" % (k, sum(v * n for v, n in vs) // max(1, sum(n for _, n in vs))) for k, vs in stages.items() if vs))
for name, (cnt, ssum, smax) in sorted(secs.items(), key=lambda kv: -kv[1][1]):
    print("- section %s: %d calls, %d ms in all, %d ms the longest" % (name, cnt, ssum, smax))
PY
  } >> "$OUT/timing.md"
  p90=$(sed -n 's/.*90th percentile: \([0-9]*\)ms.*/\1/p' "$sumfile" | head -1)
  [ -n "$p90" ] || { fail "scroll ($label): no 90th percentile in gfxinfo"; return; }
  eval "P90_$label=$p90"
  [ "$p90" -le "$limit" ] || note "scroll ($label): p90 frame time $p90 ms, above the phone target $limit ms (judged against the control below)"
  # the mean composition of one list row on the main thread, from the section lines
  local rc
  # WARM windows only: the second half of the one-second windows (the first half runs code the runtime has not compiled yet)
  rc=$(grep -o "row.compose n=[0-9]* sum=[0-9]*" "$OUT/jankprobe-$label.txt" | awk -F'[= ]' '{n[NR]=$3; s[NR]=$5} END{h=int(NR/2); for(i=h+1;i<=NR;i++){N+=n[i]; S+=s[i]}; a=0; b=0; for(i=1;i<=h;i++){a+=n[i]; b+=s[i]}; if(N>0) printf "%d %d", int(S/N), (a>0? int(b/a):0); else printf "0 0"}')
  eval "ROWMS_$label=${rc%% *}"
  echo "- mean row composition ($label): ${rc%% *} ms per row over the warm windows (${rc##* } ms over the first half)" >> "$OUT/timing.md"
}

FAKE_JMAP_BULK=$BULK python3 -I ac_cloud-mail/test/fake-jmap-server.py --port $PORT > "$OUT/fake-jmap.log" 2>&1 &
SRV=$!
sleep 5
curl -s -m 20 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-00-start.json" || { fail "the fake JMAP server did not start"; exit 1; }

adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
sleep 45
# OLD_APK (the schema v34 build, set by the upgrade run) is installed first and updated in place after the fill: the update walk below
adb install -r -g "${OLD_APK:-app-under-test.apk}" || { echo "::error::adb install failed"; exit 2; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell settings put global hide_error_dialogs 1
adb logcat -c
adb logcat -v threadtime "*:W" MailList:I MailCore:I MailSync:I MailOpen:I NavWalkSeed:I JankProbe:I MailMigration:I > "$OUT/logcat.txt" 2>&1 &
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
if [ "$synced" != 1 ]; then
  # the schema v34 build (update run) shows an empty Inbox for the whole first sync: the defect 585a3e045 fixed, so it cannot pass this check
  if [ -n "${OLD_APK:-}" ]; then note "update run: the v34 build never showed the seeded Inbox during its first sync (expected, fixed in the new build)"; else fail "the online sync never showed the seeded Inbox"; fi
fi
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

# ---- U: the update from schema v34 (only when OLD_APK is set): the new build installed over the old one, which holds the filled store -----
# What an owner sees after updating with about 40 000 messages on the phone: the time to the first row of the first launch, and the covering
# index (schema v35) build, from the app's own MailMigration lines.
if [ -n "${OLD_APK:-}" ]; then
  adb shell am force-stop "$PKG"; sleep 3
  sizes "before-update"
  l0=$(grep -c "" "$OUT/logcat.txt")
  adb install -r -g app-under-test.apk || { fail "update: adb install over the v34 build failed"; }
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 25
  sleep 5
  upid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  [ -n "$upid" ] || upid=$(tail -n +$((l0 + 1)) "$OUT/logcat.txt" | grep -m1 "first-row ms=" | awk '{print $3}')
  grep -E "MailList|MailMigration|MailCore: sync|JankProbe" "$OUT/logcat.txt" | awk -v p="$upid" '$3 == p' > "$OUT/update-first-launch.log" || true
  grep -q "cold-start armed" "$OUT/update-first-launch.log" || fail "update: no 'cold-start armed' line for pid ${upid:-?} (the process-start marks are not being captured)"
  ums=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\1/p' "$OUT/update-first-launch.log" | head -1)
  urows=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\2/p' "$OUT/update-first-launch.log" | head -1)
  ixms=$(sed -n 's/.*covering index build end ms=\([0-9]*\).*/\1/p' "$OUT/update-first-launch.log" | head -1)
  # wall clock from the activity arming the cold start (the first line of the launch) to each event, from the logcat timestamps
  wall() { awk -v pat="$1" 'function t(s,  a){split(s,a,/[:.]/); return ((a[1]*60+a[2])*60+a[3])*1000+a[4]} /cold-start armed/ && !z {z=t($2)} $0 ~ pat && z && !e {e=t($2)} END{ if (z && e) print e - z }' "$OUT/update-first-launch.log"; }
  wall_row=$(wall "first-row ms="); wall_ix=$(wall "covering index build end"); wall_ix0=$(wall "covering index build start")
  {
    echo ""; echo "first launch after updating from schema v34 (the filled store):"
    echo "- time to the first row: ${ums:-missing} ms (${urows:-?} rows)"
    echo "- covering index build: ${ixms:-no migration line} ms"
    echo "- from the activity arming the cold start (the first line of the launch): index build starts at ${wall_ix0:-?} ms and ends at ${wall_ix:-?} ms, the first row is at ${wall_row:-?} ms"
    grep -E "MailMigration|cold-start (armed|mark=list-key|rows-painted)|first-row" "$OUT/update-first-launch.log" | sed 's/^/  /'
    echo "- from the PROCESS start (Process.getStartElapsedRealtime), what the owner feels: activity armed at $(sed -n 's/.*cold-start armed.*process-age=\([0-9-]*\).*/\1/p' "$OUT/update-first-launch.log" | head -1) ms, first rows painted at $(sed -n 's/.*cold-start rows-painted.*process-age=\([0-9-]*\).*/\1/p' "$OUT/update-first-launch.log" | head -1) ms"
    echo "- the process's own start, step by step (process age in ms):"
    grep "app-init step=" "$OUT/update-first-launch.log" | sed 's/^.*app-init step=/  /' | cut -c1-120
    echo "- first-frame shell sections (JankProbe, the first windows):"
    grep "JankProbe" "$OUT/update-first-launch.log" | head -3 | sed 's/^.*JankProbe: /  /' | cut -c1-400
  } >> "$OUT/timing.md"
  [ -n "$ums" ] || fail "update: the first launch after updating logged no first row"
  [ -n "$ixms" ] || fail "update: no covering index build line (was the old build really schema v34?)"
  [ "${ums:-99999}" -le "${UPDATE_LIMIT_MS:-1500}" ] 2>/dev/null || fail "update: first row after ${ums:-?} ms on the first launch after updating (limit ${UPDATE_LIMIT_MS:-1500}); the index build took ${ixms:-?} ms"
  [ "${ixms:-99999}" -le "${UPDATE_INDEX_LIMIT_MS:-500}" ] 2>/dev/null || fail "update: the covering index build took ${ixms:-?} ms (limit ${UPDATE_INDEX_LIMIT_MS:-500}); it must not hold the first paint"
  sleep 5
fi

# ---- S: the Configs section with the two cache settings and the usage line ----------------------------------------------------------
found=0
# up to three attempts: a run found the app inside an open message (screenshot 03-settings-cache of run 46098363318), so each attempt first
# leaves whatever page is open (two Back presses) and brings the app to the front; the row itself is declared in SettingsScreen (title
# settings_body_cache_limit_title) and is not moved
for attempt in 1 2 3; do
  adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2
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
  [ "$found" = 1 ] && break
  note "settings: attempt $attempt did not reach the Body cache limit row"
  cp "$OUT/03-settings.xml" "$OUT/03-settings-attempt$attempt.xml" 2>/dev/null || true
done
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
  # PID-based, not a line-count slice: a round's log is every line of the app process started for it. The count-based slice began 2.6 to 3.5 s
  # into the process (run 46129508730), which cut off the process-start marks (`armed`, the first process-age lines, the open-time optimize line).
  rpid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  [ -n "$rpid" ] || rpid=$(tail -n +$((n0 + 1)) "$OUT/logcat.txt" | grep -m1 "first-row ms=" | awk '{print $3}')
  grep -E "MailList|MailMigration|MailCore: sync|ForegroundPush: foreground" "$OUT/logcat.txt" | awk -v p="$rpid" '$3 == p' > "$OUT/cold-$round.log" || true
  grep -q "cold-start armed" "$OUT/cold-$round.log" || fail "cold start $round: no 'cold-start armed' line for pid ${rpid:-?} (the process-start marks are not being captured)"
  apa=$(sed -n 's/.*cold-start armed.*process-age=\([0-9-]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  ms=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  rows=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=\([0-9]*\).*/\2/p' "$OUT/cold-$round.log" | head -1)
  lp=$(grep -n "cold-start rows-painted" "$OUT/cold-$round.log" | head -1 | cut -d: -f1)
  ln=$(grep -n -E "cold-start first-network|MailCore: sync .* requests=[1-9]|ForegroundPush: foreground" "$OUT/cold-$round.log" | head -1 | cut -d: -f1)
  order=unknown
  if [ -n "$lp" ] && [ -z "$ln" ]; then order="yes (no server call in 12 s)"; fi
  if [ -n "$lp" ] && [ -n "$ln" ]; then
    if [ "$lp" -lt "$ln" ] && grep -q "cold-start first-network.*after-rows=true" "$OUT/cold-$round.log"; then order=yes; else order=NO; fi
  fi
  q=$(sed -n 's/.*page-load kind=Refresh ms=\([0-9]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  echo "| cold start $round at the fixture | ${ms:-missing} | ${rows:-} | $order | ${q:-} |" >> "$OUT/timing.md"
  pa=$(sed -n 's/.*cold-start rows-painted.*process-age=\([0-9-]*\).*/\1/p' "$OUT/cold-$round.log" | head -1)
  if [ "$round" = 1 ]; then { echo "  - cold start 1, the process's own start (process age ms):"; grep "app-init step=" "$OUT/cold-1.log" | sed 's/^.*app-init step=/      /' | cut -c1-110; } >> "$OUT/timing.md"; fi
  echo "  - cold start $round (pid ${rpid:-?}): the activity armed ${apa:-?} ms and the first rows were painted ${pa:-?} ms after the process started" >> "$OUT/timing.md"
  oms=$(grep "MailMigration: open optimize ms=" "$OUT/cold-$round.log" | sed -n 's/.*open optimize ms=\([0-9]*\).*/\1/p' | head -1)
  echo "  - cold start $round (pid ${rpid:-?}): the open-time PRAGMA optimize took ${oms:-no line} ms" >> "$OUT/timing.md"
  [ -n "$oms" ] || fail "cold start $round: no open-time PRAGMA optimize line for pid ${rpid:-?} (the timing is not being captured)"
  [ "${oms:-0}" -le "${OPEN_OPTIMIZE_MAX_MS:-25}" ] 2>/dev/null || fail "cold start $round: the open-time PRAGMA optimize took ${oms} ms (limit ${OPEN_OPTIMIZE_MAX_MS:-25})"
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
  l0=$(grep -c "" "$OUT/logcat.txt")
  tapt "Mail" exact || fail "warm $w: no Mail item in the bottom bar"
  sleep 1; shot "05-warm$w-after-1s"; sleep 3
  # store queries that finished between the tap and the first row (0 = the return was served from the kept rows)
  wq=$(tail -n +$((l0 + 1)) "$OUT/logcat.txt" | grep -E "MailList.*(page-load kind|first-row)" | awk '/first-row/{exit} /page-load kind/{n++} END{print n+0}')
  n1=$(grep -c -E "MailList.*first-row" "$OUT/logcat.txt")
  wms=missing; [ "$n1" -gt "$n0" ] && wms=$(grep -E "MailList.*first-row" "$OUT/logcat.txt" | tail -1 | sed -n 's/.*first-row ms=\([0-9]*\).*/\1/p')
  echo "| warm return $w from Alerts | $wms | | - | $wq store queries before the row |" >> "$OUT/timing.md"
  [ "$wms" != missing ] || fail "warm $w: the list logged no first row"
  [ "${wms:-99999}" -le "$WARM_LIMIT_MS" ] 2>/dev/null || fail "warm $w: first row after $wms ms (limit $WARM_LIMIT_MS)"
done

adb shell top -b -n 1 -m 8 2>/dev/null | tail -9 > "$OUT/top-online.txt"
# scroll jank over ten flings of the list
echo "" >> "$OUT/timing.md"; echo "scroll (10 flings):" >> "$OUT/timing.md"
scroll_measure sync "$JANK_P90_SYNC_MS" "$OUT/jank.txt"
cat "$OUT/jank.txt" | tee -a "$OUT/summary.txt"
sed 's/^ */- /' "$OUT/jank.txt" >> "$OUT/timing.md.tmp"; cat "$OUT/timing.md.tmp" >> "$OUT/timing.md"; rm -f "$OUT/timing.md.tmp"
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
# quiet (no network, so no background sync): the same return and the same flings, to tell the list's own cost from the sync's
adb shell input keyevent KEYCODE_BACK; sleep 3; front 8; tapt "Mail" exact; sleep 3
for w in 1 2; do
  n0=$(grep -c -E "MailList.*first-row" "$OUT/logcat.txt")
  tapt "Alerts" exact; sleep 4; tapt "Mail" exact; sleep 4
  n1=$(grep -c -E "MailList.*first-row" "$OUT/logcat.txt")
  wms=missing; [ "$n1" -gt "$n0" ] && wms=$(grep -E "MailList.*first-row" "$OUT/logcat.txt" | tail -1 | sed -n 's/.*first-row ms=\([0-9]*\).*/\1/p')
  echo "| warm return $w, airplane mode (no sync running) | $wms | | - | |" >> "$OUT/timing.md"
done
echo "" >> "$OUT/timing.md"; echo "scroll (10 flings), airplane mode (no sync running):" >> "$OUT/timing.md"
scroll_measure quiet "$JANK_P90_QUIET_MS" "$OUT/jank-quiet.txt"
sed 's/^ */- /' "$OUT/jank-quiet.txt" >> "$OUT/timing.md"
adb shell top -b -n 1 -m 8 2>/dev/null | tail -9 > "$OUT/top-quiet.txt"
adb shell cmd connectivity airplane-mode disable
# ---- P: baseline profile (a) generated from this run's usage, (b) what AOT compiling it does to a cold start ----------------------------------
# (a) the JIT profile this process collected (cold start, the list, flings), written out as the profile's text form: the same method as the
#     S7 profile in app/src/main/baseline-prof.txt (snapshot-profile + profman). The artifact baseline-prof.generated.txt is merged into that file.
# (b) `cmd package compile -m speed-profile` compiles the profiled methods ahead of time, as an install with a baseline profile does; three
#     cold starts after it are the "after" of the JIT-only cold starts above (the emulator CPU differs from a phone, the direction is the point).
ppid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
[ -n "$ppid" ] && adb shell "kill -s SIGUSR1 $ppid" >/dev/null 2>&1; sleep 3
base_apk=$(adb shell pm path "$PKG" | head -1 | sed 's/^package://' | tr -d '\r')
adb shell cmd package snapshot-profile "$PKG" > "$OUT/snapshot-profile.txt" 2>&1 || true
# the emulator image allows root; profman lives in the ART apex and is not on the shell user's path (run 46141678620: "profman: inaccessible or not found")
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell "ls -la /data/misc/profman/ /data/misc/profiles/cur/0/$PKG/ 2>&1" > "$OUT/profile-files.txt" 2>&1 || true
prof_file=$(adb shell "ls /data/misc/profman/*.prof 2>/dev/null | head -1" | tr -d '\r')
[ -n "$prof_file" ] || prof_file="/data/misc/profiles/cur/0/$PKG/primary.prof"
PROFMAN=$(adb shell "ls /apex/com.android.art/bin/profman 2>/dev/null" | tr -d '\r')
adb shell "${PROFMAN:-profman} --dump-classes-and-methods --profile-file=$prof_file --apk=$base_apk --dex-location=$base_apk" > "$OUT/baseline-prof.generated.txt" 2>"$OUT/profman.err" || true
note "baseline profile: $(grep -c . "$OUT/baseline-prof.generated.txt" 2>/dev/null || echo 0) lines generated from this run (profman: $(head -c 160 "$OUT/profman.err" | tr '\n' ' '))"
adb shell cmd package compile -m speed-profile -f "$PKG" > "$OUT/compile-speed-profile.txt" 2>&1 || true
note "cmd package compile -m speed-profile: $(head -c 120 "$OUT/compile-speed-profile.txt" | tr '\n' ' ')"
echo "" >> "$OUT/timing.md"; echo "cold start after AOT compiling the profiled methods (speed-profile), airplane mode off:" >> "$OUT/timing.md"
adb shell cmd connectivity airplane-mode disable
for pround in 1 2 3; do
  adb shell am force-stop "$PKG"; sleep 3
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12
  qpid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  grep -E "MailList" "$OUT/logcat.txt" | awk -v p="$qpid" '$3 == p' > "$OUT/compiled-$pround.log" || true
  pfr=$(sed -n 's/.*first-row ms=\([0-9]*\) rows=.*/\1/p' "$OUT/compiled-$pround.log" | head -1)
  parm=$(sed -n 's/.*cold-start armed.*process-age=\([0-9-]*\).*/\1/p' "$OUT/compiled-$pround.log" | head -1)
  ppaint=$(sed -n 's/.*cold-start rows-painted.*process-age=\([0-9-]*\).*/\1/p' "$OUT/compiled-$pround.log" | head -1)
  echo "- compiled cold start $pround (pid ${qpid:-?}): first row ${pfr:-?} ms, the activity armed ${parm:-?} ms and the first rows were painted ${ppaint:-?} ms after the process started" >> "$OUT/timing.md"
done
# control: the same flings on the system Settings list, same emulator, same renderer (what the emulator itself costs, not the app)
adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 4
adb shell dumpsys gfxinfo com.android.settings reset >/dev/null 2>&1
for j in $(seq 1 10); do adb shell input swipe 700 1600 700 500 250; sleep 1; done
{ echo ""; echo "control, system Settings list, same flings:"; adb shell dumpsys gfxinfo com.android.settings | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile" | head -5 | sed 's/^ */- /'; } >> "$OUT/timing.md"
adb shell input keyevent KEYCODE_BACK; sleep 1
CP90=$(adb shell dumpsys gfxinfo com.android.settings | sed -n 's/.*90th percentile: \([0-9]*\)ms.*/\1/p' | head -1)
note "control p90 ${CP90:-missing} ms; app p90 sync ${P90_sync:-?} ms, quiet ${P90_quiet:-?} ms; mean row composition sync ${ROWMS_sync:-?} ms, quiet ${ROWMS_quiet:-?} ms"
if [ -n "$CP90" ] && [ -n "$P90_sync" ] && [ -n "$P90_quiet" ]; then
  awk -v a="$P90_sync" -v c="$CP90" -v x="$JANK_FLOOR_SYNC_X" 'BEGIN{exit !(a <= c * x)}' || fail "scroll with a sync: p90 $P90_sync ms is above ${JANK_FLOOR_SYNC_X}x the control's $CP90 ms"
  awk -v a="$P90_quiet" -v c="$CP90" -v x="$JANK_FLOOR_QUIET_X" 'BEGIN{exit !(a <= c * x)}' || fail "scroll in airplane mode: p90 $P90_quiet ms is above ${JANK_FLOOR_QUIET_X}x the control's $CP90 ms"
else
  fail "scroll: the control or an app p90 is missing"
fi
CP50=$(adb shell dumpsys gfxinfo com.android.settings | sed -n 's/.*50th percentile: \([0-9]*\)ms.*/\1/p' | head -1)
ROW_MAX=$(awk -v c="${CP50:-0}" -v f="$ROW_FRACTION" -v m="$ROW_COMPOSE_FLOOR_MS" 'BEGIN{x=c/f; print int(x > m ? x : m)}')
note "row composition limit ${ROW_MAX} ms (control p50 ${CP50:-missing} ms / $ROW_FRACTION, at least $ROW_COMPOSE_FLOOR_MS); warm mean sync ${ROWMS_sync:-?}, quiet ${ROWMS_quiet:-?}"
[ "${ROWMS_sync:-99999}" -le "$ROW_MAX" ] && [ "${ROWMS_quiet:-99999}" -le "$ROW_MAX" ] || fail "a list row takes more than $ROW_MAX ms to compose on the main thread over the warm windows (sync ${ROWMS_sync:-?}, quiet ${ROWMS_quiet:-?})"

sleep 20
sizes "end"
HE=$(grep "^headers|" "$OUT/sizes.txt" | tail -1 | cut -d"|" -f2)
[ "${HE:-0}" -ge "${H:-0}" ] 2>/dev/null || fail "headers were evicted: $HE at the end, ${H:-?} after the fill"
cat "$OUT/timing.md" | tee -a "$OUT/summary.txt"
cat "$OUT/sizes.txt"
grep -E "MailList" "$OUT/logcat.txt" > "$OUT/maillist-log.txt" || true
kill "$LP" 2>/dev/null || true
kill "$SRV" 2>/dev/null || true
exit $FAIL
