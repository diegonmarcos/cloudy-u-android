#!/usr/bin/env bash
# #1360 emulator proof of the shared Perms mechanism on the key apps (Drive, Me, Notes, FinTerminal, Calc, Browser, Gallery).
# Per app, on a clean install (no -g, so nothing is pre-granted):
#   1 first run: launched from the launcher, the app opens its Perms page by itself when a declared grant is missing
#   2 the Perms page (libs:perms PermsActivity, started as root) lists every declared grant with its live state,
#     Grant all on top, helper apps (cld.termux for Drive and Notes) in their own section
#   3 grant one entry from a shell -> the row reads Granted on resume
#   4 revoke it -> the row's chip reads Missing again, with its Grant / Open button (the in-place prompt)
#   5 #1372 the same page in dark mode (the Perms UI is drawn for both)
# Each step: a screenshot and a uiautomator dump in OUT, and a verdict line in OUT/verdicts.txt.
# Usage: perms-emulator-proof.sh APK_DIR OUT   (APK_DIR/<key>.apk, keys below)
set -u
APKS="$1"; OUT="$2"; mkdir -p "$OUT"
AAPT2="$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/*/aapt2 2>/dev/null | tail -1)"
fails=0
say() { echo "$*" | tee -a "$OUT/verdicts.txt"; }
dump1() { # one fresh uiautomator dump to $1 (the old /sdcard/ui.xml is removed first: a failed dump must not pass a stale one)
  local i; for i in 1 2 3; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 && [ -s "$1" ] && return 0
    sleep 2
  done; : > "$1"; }
shot() { # screenshot of the top, then the dump of every screenful (rows below the fold count too)
  adb exec-out screencap -p > "$OUT/$1.png"
  dump1 "$OUT/$1.xml"
  local i; for i in 1 2 3 4 5 6; do   # Me's page holds 11 rows: three screenfuls did not reach the last
    adb shell input swipe 160 560 160 160 300 >/dev/null 2>&1; sleep 1
    dump1 "$OUT/$1.more.xml"; cat "$OUT/$1.more.xml" >> "$OUT/$1.xml"
  done; rm -f "$OUT/$1.more.xml"; }
tap_text() { # tap the centre of the first node whose text is $1, from a fresh dump; 1 when absent
  dump1 "$OUT/tap.xml"
  local b; b="$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" "$OUT/tap.xml" | head -1 | grep -o 'bounds="[^"]*"' | tr -dc '0-9,[]')"
  [ -z "$b" ] && return 1
  set -- $(echo "$b" | tr '[],' '   ')
  adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1; }
has() { grep -q -- "$2" "$OUT/$1.xml" 2>/dev/null; }
check() { # name file text...
  local name="$1" f="$2"; shift 2
  local miss=""
  for t in "$@"; do has "$f" "$t" || miss="$miss [$t]"; done
  if [ -z "$miss" ]; then say "PASS $name"; else say "FAIL $name: missing$miss (see $f.png)"; fails=$((fails+1)); fi
}
perms_page() { # pkg tag
  adb shell am force-stop "$1"
  adb shell am start -W -n "$1/com.diegonmarcos.superapp.perms.PermsActivity" >/dev/null 2>&1
  sleep 5; shot "$2"
}
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell settings put global cloud_perms_first_run 2   # ask even though the AVD is a test harness (PermsFirstRun.global)
say "device: ro.test_harness=$(adb shell getprop ro.test_harness | tr -d '\r') monkey=$(adb shell getprop ro.monkey | tr -d '\r')"

# key | label of the entry granted and revoked | grant cmd | revoke cmd | texts the page must show
# fd 3: every adb shell below reads stdin and would swallow the rest of the table (only the first app ever ran)
while IFS='|' read -r -u 3 key entry grant revoke texts; do
  [ -z "$key" ] && continue
  apk="$APKS/$key.apk"
  if [ ! -f "$apk" ]; then say "FAIL $key: no APK"; fails=$((fails+1)); continue; fi
  pkg="$("$AAPT2" dump packagename "$apk" 2>/dev/null | tr -d '\r')"
  adb install -r "$apk" >/dev/null 2>&1 || { say "FAIL $key: install"; fails=$((fails+1)); continue; }
  say "== $key ($pkg)"
  # a clean screen: the previous app is uninstalled after its run, so no dialog of its own (an unknown-sources ask) covers this one
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  # 1 first run from the launcher
  act="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tail -1 | tr -d '\r')"
  adb logcat -c >/dev/null 2>&1
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$act" >/dev/null 2>&1
  sleep 8
  # the app's own start screen comes first where it has one (Gallery's onboarding, Drive's storage gate); step past it
  for t in "Get Started" "Continue with local files"; do tap_text "$t" && sleep 5; done
  shot "$key-1-first-run"
  adb logcat -d 2>/dev/null | grep -E "START u0|Displayed|AndroidRuntime|$pkg" | tail -60 > "$OUT/$key-1-logcat.txt"
  if [ "$key" = finterm ]; then check "$key first run: nothing missing, no prompt" "$key-1-first-run" ""
  elif [ "$key" = me ]; then check "$key first run: the app's own start-up ask (first_run own:MePerms.kt)" "$key-1-first-run" ""
  else check "$key first run opens the Perms page on what is missing" "$key-1-first-run" "Before you start" "Grant all"; fi
  # 2 the page with live states
  perms_page "$pkg" "$key-2-page"
  IFS=';' read -r -a want <<< "$texts"
  check "$key Perms page lists the declared grants" "$key-2-page" "${want[@]}"
  [ -z "$entry" ] && { adb shell pm uninstall "$pkg" >/dev/null 2>&1; continue; }
  # 3 grant -> Granted
  eval "${grant//\{pkg\}/$pkg}" >/dev/null 2>&1
  perms_page "$pkg" "$key-3-granted"
  check "$key after the grant: $entry reads Granted" "$key-3-granted" "$entry" "Granted"
  # 4 revoke -> the row asks again
  eval "${revoke//\{pkg\}/$pkg}" >/dev/null 2>&1
  perms_page "$pkg" "$key-4-revoked"
  check "$key after the revoke: $entry reads Missing with its button" "$key-4-revoked" "$entry" "Missing" "Grant all"
  # 5 dark mode
  adb shell cmd uimode night yes >/dev/null 2>&1; sleep 2
  perms_page "$pkg" "$key-5-dark"
  check "$key Perms page in dark mode" "$key-5-dark" "${want[@]}"
  adb shell cmd uimode night no >/dev/null 2>&1; sleep 1
  adb shell pm uninstall "$pkg" >/dev/null 2>&1
done 3<<'TABLE'
drive|All files access|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE allow|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE default|All files access;HELPER APPS;Cloud Terminal Termux;Grant all
me|Notifications|adb shell pm grant {pkg} android.permission.POST_NOTIFICATIONS|adb shell pm revoke {pkg} android.permission.POST_NOTIFICATIONS|Notifications;Health Connect;Grant all
notes|All files access|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE allow|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE default|All files access;HELPER APPS;Cloud Terminal Termux
finterm||||needs no permission
calc|Camera|adb shell pm grant {pkg} android.permission.CAMERA|adb shell pm revoke {pkg} android.permission.CAMERA|Camera;Microphone;Grant all
browser|Camera|adb shell pm grant {pkg} android.permission.CAMERA|adb shell pm revoke {pkg} android.permission.CAMERA|Camera;Microphone;Grant all
gallery|All files access|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE allow|adb shell appops set {pkg} MANAGE_EXTERNAL_STORAGE default|All files access;Photos;Grant all
TABLE
say "perms emulator proof: $fails failure(s)"
[ "$fails" -eq 0 ]
