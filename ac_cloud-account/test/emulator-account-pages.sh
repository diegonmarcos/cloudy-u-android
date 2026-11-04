#!/usr/bin/env bash
# #1438 Cloud Account on an emulator: one screenshot (+ ui dump) per Personal Info and Configs page, opened through the
# OPEN intent's section / page extras (MainActivity.EXTRA_SECTION / EXTRA_PAGE). On Pay Cards a fake card is typed in
# through the page's own form and the shot must show its last four digits only.
# Usage: emulator-account-pages.sh <apk> <out-dir>. Run by .github/workflows/account-pages-emulator.yml.
# Exit 1 when the app does not start, a page does not draw its strip labels, the card number shows unmasked, or it crashes.
set -u
APK="$1"; OUT="$2"
PKG=com.diegonmarcos.cloudaccount
mkdir -p "$OUT"
# adb reads stdin: inside the `while read` loop below it would swallow the page list, so it never gets one.
adb() { command adb "$@" </dev/null; }
fail=0
bad() { echo "FAIL  $*"; fail=1; }

adb wait-for-device
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell svc power stayon true; adb shell input keyevent 82 || true
adb install -r -g "$APK" >/dev/null || { echo "FAIL  install"; exit 1; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c || true

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es section "$1" --es page "$2" >/dev/null 2>&1; }

open_page personal ids; shot 00-first-start 6
# section/page  wait  labels the strips must show
while read -r sec page wait labels; do
  [ -z "$sec" ] && continue
  open_page "$sec" "$page"; shot "$sec-$page" "$wait"
  for l in ${labels//,/ }; do has "$sec-$page" "${l//_/ }" || bad "$sec-$page: label '${l//_/ }' missing"; done
done <<'PAGES'
personal ids 3 IDs,Pay_Cards,Others,Autocomplete
personal cards 3 Pay_Cards,Payment_cards
personal entries 3 Entries,Me
personal me 4 Entries,Me
personal personal 3 Personal,Sites,Snippets
configs cloud-apps 6 Android,Desktop,C3,Cloud_Apps,Phone_Apps,Runbook
configs phone-apps 6 Cloud_Apps,Phone_Apps,Runbook
configs runbook 4 Switches,Setup_steps,Animations_off
configs desktop 15 Desktop
configs infra 20 Infra,Containers
configs containers 30 Infra,Containers
PAGES

# Pay Cards: add a fake card through the form; the list must show •••• 0002 and never the full number.
open_page personal cards; sleep 3
tap_text() {  # tap the centre of the first node whose text is $1
  adb exec-out uiautomator dump /dev/tty > "$OUT/_tap.xml" 2>/dev/null || true
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$OUT/_tap.xml" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
}
if tap_text "Add"; then
  # Fields in order Label, Holder, Number: TAB moves focus, so no tap can land on the wrong field.
  tap_text "Label" && adb shell input text "Test"
  adb shell input keyevent 61; adb shell input keyevent 61; sleep 1
  adb shell input text "4000000000000002"
  adb shell input keyevent 4; sleep 1   # Back with the keyboard up only closes the keyboard
  for _ in 1 2 3; do tap_text "Save" && break; adb shell input swipe 500 1100 500 400 300; sleep 1; done || bad "cards: no Save"
  shot personal-cards-added 3
  has personal-cards-added "0002" || bad "cards: the masked last four are not shown"
  has personal-cards-added "4000000000000002" && bad "cards: the full number is on screen"
  has personal-cards-added "4000 0000 0000 0002" && bad "cards: the full number is on screen"
else
  bad "cards: no Add action"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | grep -B2 -A15 "$PKG" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
grep -q "4000000000000002" "$OUT/logcat.txt" && bad "the card number reached logcat"
ls -la "$OUT"
exit $fail
