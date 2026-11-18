#!/usr/bin/env bash
# #1774 Cloud Store Search > Phone as four subpages (Open source, Non open source, Play Integrity, Games) on an emulator, #1791 plus the five filter rows.
# Per subpage: the page before a query, then a typed search; the search bar is on top, the icon grid below. Screenshots only.
# Usage: emulator-store-phone4.sh STORE_APK OUT_DIR
set -u
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT"
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -1)
hide_kb() { if adb shell dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; then adb shell input keyevent KEYCODE_BACK; sleep 2; fi; }
shoot() { hide_kb; adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
tap_text() {
  dump cur
  b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
adb shell am start -W -n "$ACT" --es tab search >/dev/null; sleep 10
for i in 1 2 3 4 5 6; do   # first-run system dialogs
  if tap_text Wait || tap_text Cancel; then sleep 3
  elif ! grep -qE '(text|content-desc)="Torrent"' "$OUT/cur.xml" 2>/dev/null; then adb shell input keyevent KEYCODE_BACK; sleep 3
  else break; fi
done
rc=0
tap_text Phone || { echo "::error::no Phone subpage"; rc=1; }
sleep 3
# type [query] into the search field (cleared first) and press search
ask() {
  q="$1"; dump field; b=$(grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$OUT/field.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || { echo "::error::no search field"; rc=1; return; }
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1
  adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 24); do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "$q" >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_ENTER
}
sub() {  # name, label, ready-regex, query
  tap_text "$2" || { echo "::error::no $2 pill"; rc=1; return; }
  for i in $(seq 1 10); do dump "$1"; grep -qE "$3" "$OUT/$1.xml" && break; sleep 8; done
  sleep 12; shoot "$1-1-browse"
  read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
  adb shell input swipe $((W/2)) $((H*3/5)) $((W/2)) $((H*3/10)) 250; sleep 3; shoot "$1-1b-browse-scrolled"
  adb shell input swipe $((W/2)) $((H*3/10)) $((W/2)) $((H*4/5)) 100; adb shell input swipe $((W/2)) $((H*3/10)) $((W/2)) $((H*4/5)) 100; sleep 2
  ask "$4"; sleep 25; shoot "$1-2-search"; dump "$1-search"
  # clear the query so the next page opens on its own landing
  adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 24); do adb shell input keyevent KEYCODE_DEL; done; sleep 2
}
# #1791 the pages are filtered views of ONE catalogue: the count line "n / total" is drawn once the catalogue is read
sub open "Open source" '[0-9]+ / [0-9]+' newpipe
sub closed "Non open source" '[0-9]+ / [0-9]+' chase
sub integrity "Play Integrity" 'Play Mid \(|Play Basic \(' chase
sub games "Games" '[0-9]+ / [0-9]+' minecraft
# #1791 the five filter rows, one chip selected per row (a screenshot each), then one combined; every row starts with All.
tap_nth() {  # exact text, n-th match on screen (1-based); a chip scrolled off its row is swiped in once
  for try in 1 2; do
    dump cur
    b=$(grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n "$2p" | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
    if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3; return 0; fi
    read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
    adb shell input swipe $((W*4/5)) $((H*3/10)) $((W/5)) $((H*3/10)) 200; sleep 1
  done
  return 1
}
chip() {  # scene name, row number 1..5, chip text, occurrence of that text on screen
  tap_nth "$3" "$4" || { echo "::error::no chip $3 (row $2)"; rc=1; return; }
  sleep 3; shoot "$1"; dump "$1"
  tap_nth "All" "$2" || true   # back to All on that row
}
tap_text "Play Integrity" || { echo "::error::no Play Integrity pill"; rc=1; }
sleep 4
chip filter-row1-category 1 "Games" 2   # occurrence 1 is the Games subpage pill
chip filter-row2-licence 2 "Closed" 1
chip filter-row3-play-integrity 3 "Play Mid" 1
chip filter-row4-game-dimension 4 "Games 3D" 1
chip filter-row5-source 5 "Aurora" 1
# combined: Games AND Closed AND Games 3D AND Aurora
tap_nth "Games" 2 || rc=1; tap_nth "Closed" 1 || rc=1; tap_nth "Games 3D" 1 || rc=1; tap_nth "Aurora" 1 || rc=1
sleep 4; shoot filter-combined; dump filter-combined
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
