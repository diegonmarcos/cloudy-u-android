#!/usr/bin/env bash
# #1772 Cloud Store Search icon grids on an emulator: Phone (recommendations, then a search result) and Torrent (two categories).
# Screenshots only (dispatch-only workflow); the tiles must show their real icons, so it waits for the run-time image loads.
# Usage: emulator-store-grid.sh STORE_APK OUT_DIR
set -u
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT"
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -1)
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
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
read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
X=$((W/2)); Y1=$((H*3/5)); Y2=$((H*3/10))
down() { for i in $(seq 1 "$1"); do adb shell input swipe $X $Y1 $X $Y2 250; sleep 0.5; done; sleep 1; }
up() { for i in $(seq 1 8); do adb shell input swipe $X $Y2 $X $Y1 100; done; }
rc=0
# Phone: recommendations (wait for the dataset and the icon downloads), a scrolled page, then a typed search
tap_text Phone || { echo "::error::no Phone subpage"; rc=1; }
for i in $(seq 1 12); do dump phone; grep -q 'store-search:grid\|Popular open-source apps' "$OUT/phone.xml" && ! grep -q 'No recommendations yet\|Loading' "$OUT/phone.xml" && break; sleep 8; done
sleep 12; shoot phone-recs-1
down 2; sleep 6; shoot phone-recs-2; up
dump search; b=$(grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$OUT/search.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
if [ -n "$b" ]; then
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1
  adb shell input text newpipe; sleep 1; adb shell input keyevent KEYCODE_ENTER; sleep 25
  shoot phone-search-result; dump phone-search
else echo "::error::no search field"; rc=1; fi
# Torrent: the catalogue categories as image grids
adb shell input keyevent KEYCODE_BACK; sleep 1
# the query is shared by the subpages: empty the field so Torrent shows its category recommendations, not the typed search
if [ -n "$b" ]; then
  adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1
  adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DEL; done
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
tap_text Torrent || { echo "::error::no Torrent subpage"; rc=1; }
for i in $(seq 1 12); do dump torrent; grep -q 'Movies (' "$OUT/torrent.xml" && break; sleep 8; done
sleep 15; shoot torrent-1
down 3; sleep 8; shoot torrent-2
down 3; sleep 8; shoot torrent-3; dump torrent-end
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
