#!/usr/bin/env bash
# #1682 the Cloud Store's ADB chip lands on the Cloud C3 Webserver's ADB Shell page (not its Home), cold and warm.
# Usage: emulator-adb-link.sh STORE_APK WEBSERVER_APK OUT_DIR
set -u
STORE_APK="$1"; WS_APK="$2"; OUT="$3"; S=com.diegonmarcos.cloudstore; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
for a in "$WS_APK" "$STORE_APK"; do adb install -r -g "$a" >>"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }; done
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
top() { adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "topResumedActivity|mResumedActivity" ; }
tap_re() {  # tap the first node whose text matches the regex $1; 0 when found
  dump cur; b=$(grep -oE "text=\"($1)[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
open_store() {
  adb shell am start -W -n "$S/$(adb shell cmd package resolve-activity --brief "$S" | tr -d '\r' | tail -1 | sed 's#.*/##')" >/dev/null; sleep 8
  for i in 1 2 3 4; do tap_re "Wait|Cancel" && sleep 3; done
}
rc=0
attempt() {  # attempt <label>: tap the Store's ADB chip, then judge what the webserver shows
  local L="$1"
  open_store; shoot "$L-1-store"
  if ! tap_re "ADB|Shell broker"; then echo "::error::$L: no ADB chip on the Store screen"; dump "$L-nochip"; return 1; fi
  sleep 6; shoot "$L-2-after-tap"; dump "$L-after-tap"; top | tee "$OUT/$L-top.txt"
  grep -q "$W" "$OUT/$L-top.txt" || { echo "::error::$L: the webserver is not in front after the tap"; return 1; }
  # Home shows its own cards; the ADB Shell section is the one with the owner page and the selected bottom tab 'ADB Shell'
  if grep -qE 'text="(Pair|Connect|Pairing|Tester|Status)[^"]*"' "$OUT/$L-after-tap.xml" && grep -q 'ADB Shell' "$OUT/$L-after-tap.xml"; then echo "$L: landed on the ADB Shell page"; else echo "::error::$L: the webserver is NOT on its ADB Shell page"; return 1; fi
}
adb shell am force-stop "$W"; adb shell am force-stop "$S"
attempt cold || rc=1
# warm: the webserver is alive and sits on another section
adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 5; shoot warm-0-webserver-home
attempt warm || rc=1
adb logcat -d -t 600 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
