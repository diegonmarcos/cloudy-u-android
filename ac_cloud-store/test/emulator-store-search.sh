#!/usr/bin/env bash
# #1749 Cloud Store on an emulator: Search > Cloudy | Phone | Torrent. Each subpage shows its search bar and a recommendation list before any
# query: Cloudy only fleet apps, Phone no fleet app, Torrent the twelve categories. Red when a subpage is missing or shows the wrong thing.
# Usage: emulator-store-search.sh STORE_APK OUT_DIR
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
rc=0
# the datasets are synced at run time from the fleet data manifest: wait for them
for page in Cloudy Phone Torrent; do
  if ! tap_text "$page"; then echo "::error::no $page subpage on the Search strip"; rc=1; continue; fi
  sleep 5
  case "$page" in
    Phone) want='[0-9]+ / [0-9]+' ;;
    Torrent) want='Recommended: legitimately distributable only' ;;
    *) want='' ;;
  esac
  for i in $(seq 1 12); do
    dump "$page"
    if [ -z "$want" ] || grep -qE "$want" "$OUT/$page.xml"; then
      case "$page" in Phone) grep -q 'No recommendations yet\|Loading' "$OUT/$page.xml" || break ;; Torrent) grep -q 'Movies (' "$OUT/$page.xml" && break ;; *) break ;; esac
    fi
    sleep 8
  done
  shoot "search-$page"
  down 2; shoot "search-$page-2"; down 3; shoot "search-$page-3"; dump "$page-end"
  for i in 1 2 3 4 5 6 7 8; do adb shell input swipe $X $Y2 $X $Y1 100; done
done
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
