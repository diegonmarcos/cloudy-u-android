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

dump() { adb exec-out uiautomator dump /dev/tty > "$1" 2>/dev/null || true; }
# The system "Allow wireless debugging" prompt (the app's own adb channel asking) can sit over a page: answer Cancel, then shoot.
clear_prompt() {
  for _ in 1 2 3; do
    dump "$OUT/_prompt.xml"
    grep -q 'Allow wireless debugging' "$OUT/_prompt.xml" || return 0
    tap_in "$OUT/_prompt.xml" "Cancel"; sleep 2
  done
}
shot() { sleep "${2:-4}"; clear_prompt; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; dump "$OUT/$1.xml"; }
tap_in() {  # tap the centre of the first node in dump $1 whose text is $2
  local b; b=$(grep -o "text=\"$2\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$1" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
}
read -r W H < <(adb shell wm size | tr -d '\r' | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -1)
up() { adb shell input swipe $((W/2)) $((H*65/100)) $((W/2)) $((H*30/100)) 300; sleep 1; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }
open_page() { adb shell am start -W -n "$PKG/.MainActivity" --es section "$1" --es page "$2" >/dev/null 2>&1; }

open_page vault ids; shot 00-first-start 6
# section/page  wait  labels the strips must show
while read -r sec page wait labels; do
  [ -z "$sec" ] && continue
  open_page "$sec" "$page"; shot "$sec-$page" "$wait"
  for l in ${labels//,/ }; do has "$sec-$page" "${l//_/ }" || bad "$sec-$page: label '${l//_/ }' missing"; done
done <<'PAGES'
vault ids 3 IDs,PayCards,Secrets,Autocomplete
vault cards 3 PayCards,PAYMENT_CARDS
vault entries 3 Providers,Entries
devices me 4 Mesh,Peer_Control,Backup
vault personal 3 Personal,Sites,Snippets
devices peer-control 3 Sample_peer_A
config cloud-apps 6 Android,Desktop,Cloud_Apps,Phone_Apps,Runbook
config phone-apps 6 Cloud_Apps,Phone_Apps,Runbook
config runbook 4 Switches,Setup_steps,Animations_off
config desktop 15 Desktop
config infra 20 C3,Infra,Containers
config containers 30 Infra,Containers
config repos 12 Repos,Pull
PAGES

# Pay Cards: add a fake card through the form; the list must show •••• 0002 and never the full number.
open_page vault cards; sleep 3
tap_text() { dump "$OUT/_tap.xml"; tap_in "$OUT/_tap.xml" "$1"; }
if tap_text "Add"; then
  # Fields in order Label, Holder, Number: TAB moves focus, so no tap can land on the wrong field.
  tap_text "Label" && adb shell input text "Test"
  adb shell input keyevent 61; adb shell input keyevent 61; sleep 1
  adb shell input text "4000000000000002"
  adb shell input keyevent 4; sleep 1   # Back with the keyboard up only closes the keyboard
  saved=0; for _ in 1 2 3 4 5; do tap_text "Save" && { saved=1; break; }; up; done; [ "$saved" = 1 ] || bad "cards: no Save"
  shot vault-cards-added 3
  has vault-cards-added "0002" || bad "cards: the masked last four are not shown"
  has vault-cards-added "4000000000000002" && bad "cards: the full number is on screen"
  has vault-cards-added "4000 0000 0000 0002" && bad "cards: the full number is on screen"
else
  bad "cards: no Add action"
fi

adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
grep -q "4000000000000002" "$OUT/logcat.txt" && bad "the card number reached logcat"
ls -la "$OUT"
exit $fail
