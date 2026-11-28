#!/bin/bash
# #1986 / #1987 emulator walk (test-clock-1970.yml job `shots`, API 34 pixel_5, 1080 x 2340 like the owner's references).
# Cloud Tasks: every Clock page in the reference states (Timer at 00:00:00, Stopwatch at 00:00.00, the alarm editor set to
# 07:05 every day, Sound off, Snooze 5 minutes), then the alarm ringing (screen off: the full-screen ring screen, then the
# notification card in the shade with Stop / Snooze / Dismiss) and a finished timer's card (Stop / +1 min).
# Cloud Calc: Tools > Clock (World clock only) and an old link to Alarms (handed to Cloud Tasks).
# Writes walk-out/shots/clock-1986-*.png, walk-out/summary.txt, walk-out/ui-*.xml for the item-by-item review.
set -u
mkdir -p walk-out/shots
adb root >/dev/null 2>&1; sleep 3
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global auto_time 0 || true
adb shell locksettings set-disabled true || true
sleep 45
n=0
shot() { adb exec-out screencap -p > "walk-out/shots/clock-1986-$1.png"; echo "shot $1" >> walk-out/summary.txt; }
dump() { for t in 1 2 3 4; do adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > walk-out/ui.xml; grep -q "<hierarchy" walk-out/ui.xml && break; sleep 2; done; }
# prints "x y" of the [n]th (default 1st) node whose text or content-desc is exactly $1 (optionally starting with it: $3=prefix)
find_xy() {
  python3 -I - walk-out/ui.xml "$1" "${2:-1}" "${3:-exact}" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read(); want, nth, mode = sys.argv[2], int(sys.argv[3]), sys.argv[4]
hits = []
for m in re.finditer(r'<node\b[^>]*>', x):
    n = m.group(0)
    t = re.search(r'\btext="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    vals = [v.group(1) for v in (t, d) if v]
    ok = any(v == want for v in vals) if mode == "exact" else any(v.startswith(want) for v in vals)
    if b and ok:
        a, bb, c, dd = map(int, b.groups())
        if c > a and dd > bb: hits.append(((a + c) // 2, (bb + dd) // 2))
if len(hits) >= nth: print(*hits[nth - 1])
PY
}
tap() { # tap node $1 (nth $2)
  dump
  if grep -q "isn't responding" walk-out/ui.xml; then echo "ANR dialog dismissed" >> walk-out/summary.txt; adb shell input keyevent KEYCODE_BACK; sleep 2; dump; fi
  xy=$(find_xy "$1" "${2:-1}" "${3:-exact}")
  echo "tap '$1' -> '${xy:-NOT FOUND}'" | tee -a walk-out/summary.txt
  [ -n "$xy" ] && adb shell input tap $xy
  sleep "${TAP_WAIT:-3}"
}
keep_ui() { n=$((n + 1)); dump; cp walk-out/ui.xml "walk-out/ui-$n-$1.xml"; }

if [ -f tasks.apk ]; then
  PKG=com.diegonmarcos.cloudagenda
  adb install -r -g tasks.apk || echo "::error::tasks install failed"
  adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow || true
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 15
  tap Clock; shot tasks-01-world; keep_ui world
  tap Timers; shot tasks-02-timer; keep_ui timer
  tap Stopwatch; shot tasks-03-stopwatch; keep_ui stopwatch
  tap Start; sleep 4; tap Lap; sleep 2; tap Lap; shot tasks-04-stopwatch-running
  adb shell input swipe 900 1000 150 1000 300; sleep 2; shot tasks-05-stopwatch-laps; adb shell input swipe 150 1000 900 1000 300; sleep 1
  tap Pause; tap Reset
  tap Alarms; shot tasks-06-alarms
  tap "Add alarm"; sleep 2
  # the reference's state: 07:05, every day, Sound off, Snooze 5 minutes
  TAP_WAIT=1
  for v in 01 02 03 04 05; do tap "$v"; done
  dump
  for i in 1 2 3 4 5 6 7; do
    xy=$(python3 -I - walk-out/ui.xml "$i" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read(); i = int(sys.argv[2]); hits = []
for m in re.finditer(r'<node\b[^>]*>', x):
    n = m.group(0); t = re.search(r'\btext="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and t.group(1) in ("M", "T", "W", "F", "S") and b:
        a, bb, c, dd = map(int, b.groups()); hits.append(((a + c) // 2, (bb + dd) // 2))
hits.sort()
if len(hits) >= i: print(*hits[i - 1])
PY
)
    [ -n "$xy" ] && adb shell input tap $xy && sleep 1
  done
  dump; sy=$(find_xy Sound); [ -n "$sy" ] && adb shell input tap 985 "${sy#* }" && sleep 1
  TAP_WAIT=2
  tap Snooze; tap "5 min"; tap OK
  TAP_WAIT=3
  shot tasks-07-alarm-editor; keep_ui alarm-editor
  adb shell input swipe 540 2000 540 1300 500; sleep 2; shot tasks-08-alarm-editor-sheet-scrolled
  tap Save; sleep 2; shot tasks-09-alarms-saved
  tap Interval; shot tasks-10-interval
  tap Bedtime; shot tasks-11-bedtime
  tap Pomodoro; shot tasks-12-pomodoro; tap Start; sleep 4; shot tasks-13-pomodoro-running; tap Pause; tap Reset
  # the alarm rings: the clock set to 07:04:40, the screen off -> the full-screen ring screen, then the card in the shade
  tap Alarms
  ts=$(date -u -d "$(date -u +%Y-%m-%d) 07:04:40" +%s 2>/dev/null)
  adb shell setprop persist.sys.timezone UTC || true
  adb shell cmd alarm set-timezone UTC >/dev/null 2>&1 || true
  adb shell cmd alarm set-time "$((ts * 1000))" >/dev/null 2>&1 || adb shell date -u "$(date -u -d @"$ts" +%m%d%H%M%Y.%S)"
  adb shell date | tee -a walk-out/summary.txt
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  adb shell input keyevent KEYCODE_SLEEP
  sleep 40
  shot tasks-14-ringing-screen; keep_ui ring-screen
  adb shell input keyevent KEYCODE_WAKEUP; sleep 2
  shot tasks-15-ringing-screen-awake
  adb shell cmd statusbar expand-notifications; sleep 3
  shot tasks-16-ringing-card; keep_ui ringing-card
  adb shell dumpsys notification --noredact > walk-out/notifications-ringing.txt 2>&1 || true
  # the card expanded: a two-finger-free expand, the notification's own expand affordance
  dump; ex=$(find_xy "Expand"); [ -z "$ex" ] && ex=$(find_xy "Expand button")
  [ -n "$ex" ] && adb shell input tap $ex && sleep 2
  shot tasks-17-ringing-card-expanded; keep_ui ringing-card-expanded
  tap Stop; sleep 3
  adb shell cmd statusbar collapse; sleep 2
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true; sleep 2
  # a finished timer's card: the 1 min quick start, wait, the shade
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
  tap Timers
  adb shell input swipe 540 1800 540 600 400; sleep 2
  tap "1 min"; shot tasks-18-timer-running-below
  sleep 66
  adb shell cmd statusbar expand-notifications; sleep 3
  shot tasks-19-timer-card; keep_ui timer-card
  adb shell dumpsys notification --noredact > walk-out/notifications-timer.txt 2>&1 || true
  tap Stop; adb shell cmd statusbar collapse
  timeout 30 adb logcat -d -v threadtime "*:E" > walk-out/logcat-tasks.txt || true
fi
if [ -f calc.apk ]; then
  PKG=com.diegonmarcos.cloudcalc
  adb install -r -g calc.apk || echo "::error::calc install failed"
  ACT=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG | tail -1 | tr -d '\r')
  adb shell am start -n "$ACT" --es clock_mode world >/dev/null 2>&1; sleep 12; shot calc-01-world-only; keep_ui calc-world
  # an old link to Calc's Alarms: handed to Cloud Tasks' Clock > Alarms
  adb shell am start -n "$ACT" --es clock_mode alarms >/dev/null 2>&1; sleep 8; shot calc-02-alarms-link-opens-tasks; keep_ui calc-alarms-link
  adb shell dumpsys activity activities | grep -m3 "mResumedActivity\|topResumedActivity" | tee -a walk-out/summary.txt
  timeout 30 adb logcat -d -v threadtime "*:E" > walk-out/logcat-calc.txt || true
fi
