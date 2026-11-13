#!/usr/bin/env bash
# #1712 emulator walk of Cloud C3 Webserver: (A) the Configs > Server page (screenshot, dark theme), (B) the app stays in Android
# recents and can be switched back to after another app was used, including when it was opened cold by another app's ADB button
# (the Store's chip starts the owner's ShellArmActivity with NEW_TASK; that start must land in the webserver's OWN, listed task).
# Usage: emulator-server-recents.sh STORE_APK WEBSERVER_APK OUT_DIR
set -u
STORE_APK="$1"; WS_APK="$2"; OUT="$3"; S=com.diegonmarcos.cloudstore; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
for a in "$WS_APK" "$STORE_APK"; do adb install -r -g "$a" >>"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }; done
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
top() { adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "topResumedActivity|mResumedActivity"; }
tap_re() {  # tap the first node whose text or content-desc matches the regex $1; 0 when found
  dump cur
  b=$(grep -oE "(text|content-desc)=\"($1)[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
rc=0
fail() { echo "::error::$*"; rc=1; }
recents() { adb shell dumpsys activity recents | tr -d '\r' > "$OUT/$1-recents.txt"; }
in_recents() {  # the webserver task is listed in Recents and its root intent is not marked exclude-from-recents (0x00800000)
  recents "$1"
  awk -v w="$W" '/Recent #/{blk=($0 ~ w)} blk' "$OUT/$1-recents.txt" > "$OUT/$1-recents-webserver.txt"
  [ -s "$OUT/$1-recents-webserver.txt" ] || return 1
  flg=$(grep -m1 -oE "intent=\{[^}]*flg=0x[0-9a-f]+" "$OUT/$1-recents-webserver.txt" | grep -oE "0x[0-9a-f]+$")
  echo "$1: webserver task root intent flags ${flg:-none}" | tee -a "$OUT/flags.txt"
  [ $(( ${flg:-0x0} & 0x00800000 )) -eq 0 ]
}
overview() {  # open Overview: the screenshot is the evidence (Overview exposes no label to uiautomator); the checks are the Recents task list and the real switch back
  adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3; shoot "$1"; dump "$1"
}
open_store() {
  adb shell am start -W -n "$S/$(adb shell cmd package resolve-activity --brief "$S" | tr -d '\r' | tail -1 | sed 's#.*/##')" >/dev/null; sleep 8
  for i in 1 2 3 4; do tap_re "Wait|Cancel|Not now" && sleep 3; done
}

# ---- A. Configs > Server page
adb shell am force-stop "$W"; adb shell am force-stop "$S"; adb shell input keyevent KEYCODE_HOME
adb logcat -c; adb shell am start -W -n "$W/.MainActivity" > "$OUT/A-start.txt" 2>&1; sleep 10; shoot A0-home; top | tee "$OUT/A0-top.txt"
adb logcat -d | grep -E "AndroidRuntime|FATAL|cloudwebserver.*(died|crash|Exception)" | head -40 > "$OUT/A0-crash.txt" || true
for i in 1 2 3; do tap_re "Not now|Skip|Later|Dismiss|Cancel|Allow|While using" && sleep 2; done; shoot A0b-home
tap_re "Configs" && sleep 3 || fail "A: no Configs gear on the Home page"
shoot A1-configs
tap_re "Server" && sleep 3 || fail "A: no Server tab on the Configs page"
shoot A2-server-page; dump A2-server-page
top | tee "$OUT/A-top.txt"

# ---- B. the Store's ADB chip opens the webserver cold; the webserver must then be a normal task in Recents
adb shell am force-stop "$W"; adb shell am force-stop "$S"; adb shell input keyevent KEYCODE_HOME; sleep 2
open_store; shoot B0-store
tap_re "ADB|Shell broker" || fail "B: no ADB chip on the Store screen"
sleep 6; shoot B1-webserver-after-adb-tap; top | tee "$OUT/B1-top.txt"
grep -q "$W" "$OUT/B1-top.txt" || fail "B: the webserver is not in front after the ADB tap"
adb shell dumpsys activity activities | tr -d '\r' | grep -E "Task\{|affinity|taskAffinity|realActivity=.*($W|$S)" > "$OUT/B1-tasks.txt" 2>&1 || true
# the trampoline must not have put the webserver into the Store's task
st=$(adb shell dumpsys activity activities | tr -d '\r' | awk '/\* Task\{|TASK /{t=$0} /Hist|ActivityRecord/{ if ($0 ~ /cloudstore/ && t ~ /cloudwebserver/) print "mixed: " t }' | head -1)
[ -z "$st" ] || fail "B: the webserver and the Store share one task ($st)"
in_recents B1 || fail "B: the webserver task is missing from (or excluded in) Recents after the ADB-button open"
overview B1-overview-after-adb-open
adb shell input keyevent KEYCODE_HOME; sleep 2

# ---- C. swap to another app, then switch back to the webserver from Recents
open_store; shoot C0-other-app-in-front
in_recents C0 || fail "C: the webserver task left Recents once another app was used"
adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3; shoot C1-recents; dump C1-recents
# switch back with the quick switch (a second APP_SWITCH press selects the previous app: the webserver)
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 4; shoot C2-back-in-webserver; top | tee "$OUT/C2-top.txt"
grep -q "$W" "$OUT/C2-top.txt" || fail "C: could not switch back to the webserver from Recents"
adb logcat -d -t 800 > "$OUT/logcat.txt" 2>/dev/null || true
exit $rc
