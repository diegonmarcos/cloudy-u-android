#!/usr/bin/env bash
# emulator-tables.sh <apk> <out-dir> — the CI emulator walk of the FinTerm tables (#1374): installs the APK, clears the adb-only global
# cloud_nav_ui_mode (so the declared chrome shows, #1399 Classic with the one-row sub-page nav), opens each table dashboard through the
# `dashboard` intent extra and screencaps it, swipes the table sideways (later columns) and shoots, then scrolls and shoots again. Runs inside reactivecircus/android-emulator-runner (CI only).
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.capitalfinterm
DASHES="${FT_DASHES:-world_bonds fx yields_matrix bonds_market eq_dm_us eq_em world_equity_indices commodities mp_60_40 mp_70_30_DM_EM}"
# #1399 each section's Homepage (a section id opens it), the one-row sub-page nav scrolled sideways, and the removed Market Monitor's deep link (redirects to the FI Homepage)
HOMES="${FT_HOMES:-fi eq ex nw pm my-pm}"
adb install -r -g "$APK" || exit 1
adb shell settings delete global cloud_nav_ui_mode || true
echo "cloud_nav_ui_mode=$(adb shell settings get global cloud_nav_ui_mode)" | tee "$OUT/nav-mode.txt"
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
W=${W:-1080}; H=${H:-2400}
# #1416 About > Data sources (the hidden about section, opened by its id), shot and scrolled through its five source cards
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard about >/dev/null
sleep 12
for i in 1 2 3; do
  adb exec-out screencap -p > "$OUT/apk-about-data-sources-$i.png"
  adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500
  sleep 2
done
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-about.xml" >/dev/null 2>&1 || true
for s in $HOMES market_monitor; do
  adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$s" >/dev/null
  sleep 15
  adb exec-out screencap -p > "$OUT/apk-home-$s.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-home-$s.xml" >/dev/null 2>&1 || true
  # the one-row nav (resource-id pagetabs_strip): swipe it right-to-left to show its later sub-pages
  Y=$(grep -o 'resource-id="pagetabs_strip"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/ui-home-$s.xml" 2>/dev/null | sed -E 's/.*bounds="\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\]"/\1 \2/' | awk '{print int(($1+$2)/2)}' | head -1)
  echo "$s strip_y=${Y:-none}" | tee -a "$OUT/nav-strip.txt"
  if [ -n "$Y" ]; then
    adb shell input swipe $((W * 9 / 10)) "$Y" $((W / 10)) "$Y" 400
    sleep 3
    adb exec-out screencap -p > "$OUT/apk-home-$s-navscrolled.png"
  fi
done
# #1370 deep link: ACTION_VIEW finterm://dashboard/<id> opens the dashboard; predictive back is on when logcat has no "OnBackInvokedCallback is not enabled"
adb logcat -c
adb shell am start -S -W -a android.intent.action.VIEW -d "finterm://dashboard/${FT_DEEPLINK:-eq_dm_us}" -n "$PKG/.MainActivity" >/dev/null
sleep 20
adb exec-out screencap -p > "$OUT/apk-deeplink.png"
adb logcat -d | grep -c "OnBackInvokedCallback is not enabled" > "$OUT/predictive-back-warnings.txt" || true
# node_xy <text> -> "x y" of the centre of the first uiautomator node whose text or content-desc equals <text> (empty when absent)
node_xy() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-last.xml" >/dev/null 2>&1 || return 0
  python3 -I - "$OUT/ui-last.xml" "$1" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); c = re.search(r' content-desc="([^"]*)"', n)
    if sys.argv[2] in ((t.group(1) if t else None), (c.group(1) if c else None)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a = [int(v) for v in b.groups()]
            print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
}
# #1370 watchlist: seed three symbols through the shell-only receiver, long-press a row (menu), move it down, shoot each step
adb shell am broadcast -a com.diegonmarcos.capitalfinterm.SEED -n "$PKG/.ui.SeedReceiver" --es watchlist AAPL,MSFT,NVDA >/dev/null
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard watchlist >/dev/null
sleep "${FT_WAIT:-30}"
adb exec-out screencap -p > "$OUT/apk-watchlist-1.png"
XY=$(node_xy AAPL); echo "watchlist AAPL xy=${XY:-none}" | tee -a "$OUT/nav-strip.txt"
if [ -n "$XY" ]; then
  set -- $XY
  adb shell input swipe "$1" "$2" "$1" "$2" 1200
  sleep 3
  adb exec-out screencap -p > "$OUT/apk-watchlist-menu.png"
  MV=$(node_xy "Move down"); echo "move down xy=${MV:-none}" | tee -a "$OUT/nav-strip.txt"
  if [ -n "$MV" ]; then set -- $MV; adb shell input tap "$1" "$2"; sleep 3; adb exec-out screencap -p > "$OUT/apk-watchlist-moved.png"; fi
fi
# #1370 remote symbol search: EQ Homepage > the search field > type a company name, the SEC ticker list answers (TSLA is in no panel)
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard eq >/dev/null
sleep 15
SF=$(node_xy "Symbol, index, pair, country, event"); echo "search field xy=${SF:-none}" | tee -a "$OUT/nav-strip.txt"
if [ -n "$SF" ]; then set -- $SF; adb shell input tap "$1" "$2"; sleep 3; adb shell input text tesla; sleep 12; adb exec-out screencap -p > "$OUT/apk-remote-search.png"; TS=$(node_xy "Tesla, Inc."); echo "remote result xy=${TS:-none}" | tee -a "$OUT/nav-strip.txt"; fi
# #1370 add-holding sheet: MY-PM > holdings page, tap Add
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard my-pm >/dev/null
sleep 15
PP=$(node_xy "Personal portfolio"); if [ -n "$PP" ]; then set -- $PP; adb shell input tap "$1" "$2"; sleep 4; fi
AD=$(node_xy Add); echo "holdings Add xy=${AD:-none}" | tee -a "$OUT/nav-strip.txt"
if [ -n "$AD" ]; then set -- $AD; adb shell input tap "$1" "$2"; sleep 3; adb exec-out screencap -p > "$OUT/apk-holdings-sheet.png"; fi
for d in $DASHES; do
  adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$d" >/dev/null
  sleep "${FT_WAIT:-30}"
  adb exec-out screencap -p > "$OUT/apk-$d-1.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-$d-first.xml" >/dev/null 2>&1 || true
  # sideways: the table's later columns (a swipe right-to-left across the first table), then back
  adb shell input swipe $((W * 9 / 10)) $((H / 2)) $((W / 10)) $((H / 2)) 600
  sleep 3
  adb exec-out screencap -p > "$OUT/apk-$d-side.png"
  adb shell input swipe $((W * 9 / 10)) $((H * 35 / 100)) $((W / 10)) $((H * 35 / 100)) 600
  sleep 3
  adb exec-out screencap -p > "$OUT/apk-$d-side2.png"
  adb shell input swipe $((W / 10)) $((H / 2)) $((W * 9 / 10)) $((H / 2)) 600
  sleep 2
  for i in 2 3 4; do
    adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500
    sleep 4
    adb exec-out screencap -p > "$OUT/apk-$d-$i.png"
  done
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-$d.xml" >/dev/null 2>&1 || true
done
adb logcat -d -t 2000 > "$OUT/logcat.txt" 2>/dev/null || true
ls -la "$OUT"
