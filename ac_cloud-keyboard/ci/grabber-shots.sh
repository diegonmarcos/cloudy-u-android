#!/usr/bin/env bash
# #1754 dispatch-only emulator screenshots of the Cloud Keyboard Grabber (workflow keyboard-grabber-shots.yml; never part of a push).
#
#   usage: grabber-shots.sh <Cloud-Keyboard.apk (debug)> <out-dir>
#
#   01-grabber-tab               Grabber > Text Selection (the tab's first page, empty)
#   02-grabber-text-typed        the same page with a typed word grabbed: the three boxes loaded
#   10-sheet-word                the selection-menu sheet over another app, a single word
#   11-sheet-phrase              ... a phrase
#   12-sheet-address-map         ... an address, with the map in box C
#   13-sheet-editable-word       ... an editable field (the result would replace the selection)
#   20-screen-selection-page     Grabber > Screen Selection
#   21-screen-picker             the captured screen with the drag overlay
#   22-screen-result             after dragging around a line: the text read on the device and the same panel
#
# Made-up text only. The sheet is started the way the selection menu starts it (ACTION_PROCESS_TEXT, EXTRA_PROCESS_TEXT[_READONLY]).
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?out dir}"
PKG=com.diegonmarcos.cloudkeyboard
mkdir -p "$OUT"
FAILS=0
ok()   { echo "ok     $1"; }
bad()  { echo "FAIL   $1"; FAILS=$((FAILS + 1)); }
shot() { sleep 1.5; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot   $1 ($(wc -c < "$OUT/$1.png") bytes)"; }

adb logcat -c
adb shell wm size 1080x2400 >/dev/null 2>&1; adb shell wm density 420 >/dev/null 2>&1; sleep 6
echo "screen: $(adb shell wm size | tr '\n' ' ') $(adb shell wm density | tr '\n' ' ')"
adb install -r -g "$APK" >/dev/null && ok "installed" || { bad "install"; exit 1; }
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
sleep 30
# the network the boxes need: say so in the log, the shots show the offline state when it is missing
adb shell "ping -c 1 -W 4 duckduckgo.com" 2>&1 | tail -2 | tee "$OUT/ping.txt"
# no dialog on the capture consent in the walk: the screen-capture app-op is the shell-side grant of it (a phone user taps the dialog)
adb shell appops set "$PKG" PROJECT_MEDIA allow >/dev/null 2>&1 || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# the keyboard must be the enabled, current input method or Settings opens its welcome wizard instead of the pages
IME="$PKG/helium314.keyboard.latin.LatinIME"
for _ in $(seq 1 15); do
    adb shell ime enable "$IME" >/dev/null 2>&1; adb shell ime set "$IME" >/dev/null 2>&1
    [ "$(adb shell settings get secure default_input_method | tr -d '\r')" = "$IME" ] && break
    sleep 4
done
echo "input method: $(adb shell settings get secure default_input_method | tr -d '\r')"
adb shell settings put secure show_ime_with_hard_keyboard 1

dumpui() {
    for _ in 1 2 3 4 5; do
        adb shell uiautomator dump /sdcard/gr.xml >/dev/null 2>&1 && adb pull /sdcard/gr.xml "$1" >/dev/null 2>&1 && grep -q "<node" "$1" && return 0
        sleep 2
    done; return 1
}
# the centre of the first node whose text or content-desc is $2 (exact) in dump $1
centre() { python3 - "$1" "$2" <<'PY'
import re, sys, xml.etree.ElementTree as ET
hit = None
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if sys.argv[2] in ((n.get("text") or "").strip(), (n.get("content-desc") or "").strip()):
        a = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
        if len(a) == 4: hit = ((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
if hit: print(*hit)
PY
}
tap_text() { dumpui "$OUT/t.xml" || return 1; read -r X Y < <(centre "$OUT/t.xml" "$1"); [ -n "${X:-}" ] || return 1; adb shell input tap "$X" "$Y"; return 0; }

menu() {   # $1 text, $2 readonly (true|false): what the selection menu sends (one string for the device shell, so spaces survive)
    adb shell "am start -n $PKG/.grabber.GrabberActivity -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT '$1' --ez android.intent.extra.PROCESS_TEXT_READONLY $2" >/dev/null
}
# wait (up to $2 s) until the screen dump holds text $1
waitfor() { local i; for i in $(seq 1 $(( ${2:-60} / 4 ))); do dumpui "$OUT/w.xml" && grep -q "$1" "$OUT/w.xml" && return 0; sleep 2; done; return 1; }
scroll_sheet() { adb shell input swipe 540 1900 540 900 500; sleep 1.5; }
settings_behind() { adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 4; }

# ── the tab ───────────────────────────────────────────────────────────────
adb shell am start -f 0x10008000 -n "$PKG/helium314.keyboard.settings.SettingsActivity" --es open_at grabber >/dev/null; sleep 8
dumpui "$OUT/tab.xml" && grep -q 'Text Selection' "$OUT/tab.xml" && ok "the Grabber tab opens on Text Selection" || bad "the Grabber tab did not open"
grep -q 'Screen Selection' "$OUT/tab.xml" && ok "Screen Selection is the second subpage" || bad "no Screen Selection subpage"
grep -q 'text="Grabber"' "$OUT/tab.xml" && ok "Grabber is on the bottom bar" || bad "no Grabber on the bottom bar"
shot 01-grabber-tab
if tap_text "Type or paste the text to grab"; then
    sleep 1; adb shell input text "serendipity"; sleep 1; adb shell input keyevent 4; sleep 1
    tap_text "Grab" || bad "no Grab key on the Text Selection page"
    waitfor "MS" 40 && ok "boxes B and C answered" || bad "the boxes never answered"
    shot 02-grabber-text-typed
else bad "no text field on the Text Selection page"; fi
adb shell input keyevent 4; adb shell input keyevent 4

# ── the sheet over another app ────────────────────────────────────────────
settings_behind
menu "serendipity" true; waitfor "Copy link" 60
dumpui "$OUT/sheet-word.xml" && grep -q 'text="Copy"' "$OUT/sheet-word.xml" && ok "the sheet shows the actions toolbar" || bad "no actions toolbar on the sheet"
for w in Clipboard Text Search AI Drive Writer; do grep -qi "text=\"$w\"" "$OUT/sheet-word.xml" && ok "group $w" || bad "group $w missing"; done
shot 10-sheet-word
scroll_sheet; shot 10b-sheet-word-boxes
adb shell input keyevent 4; sleep 2
menu "Why is the sky blue during the day" true; waitfor "Copy link" 60; shot 11-sheet-phrase
scroll_sheet; shot 11b-sheet-phrase-boxes
adb shell input keyevent 4; sleep 2
menu "1600 Pennsylvania Avenue NW, Washington, DC 20500" true; waitfor "Open in maps" 75; shot 12-sheet-address-map
scroll_sheet; shot 12b-sheet-address-map-box
dumpui "$OUT/sheet-address.xml" && grep -q 'MEDIA · MAP' "$OUT/sheet-address.xml" && ok "an address gets the map box" || bad "no map box for the address"
adb shell input keyevent 4; sleep 2
menu "teh quick brwn fox" false; waitfor "Copy link" 60; shot 13-sheet-editable-word
dumpui "$OUT/sheet-editable.xml" && grep -q 'FIELD · REPLACES SELECTION' "$OUT/sheet-editable.xml" && ok "an editable field says the result replaces the selection" || bad "no editable marker"
adb shell input keyevent 4; sleep 2

# ── screen selection ──────────────────────────────────────────────────────
adb shell am start -f 0x10008000 -n "$PKG/helium314.keyboard.settings.SettingsActivity" --es open_at grabber_screen >/dev/null; sleep 8
shot 20-screen-selection-page
settings_behind
adb shell am start -n "$PKG/.grabber.GrabberScreenActivity" --el grabber.delay_ms 1500 >/dev/null; sleep 5
# a phone shows the system consent; the emulator walk answers it when the app-op did not
for label in "Start now" "Share screen" "Next" "Start"; do tap_text "$label" && sleep 2; done
sleep 6
dumpui "$OUT/picker.xml" && grep -q 'Drag around the text' "$OUT/picker.xml" && ok "the picker draws over the captured screen" || bad "no picker: capture did not return a frame"
shot 21-screen-picker
adb shell input swipe 90 650 990 900 1800; sleep 4
shot 22-screen-reading
waitfor "Copy" 150 && ok "the text read from the screen feeds the panel" || bad "no panel after the region (the text reader gave nothing in 150 s)"
sleep 6
shot 23-screen-result
scroll_sheet; shot 23b-screen-result-boxes

adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
adb logcat -d | grep -q "FATAL EXCEPTION" && { bad "a crash is in the log"; adb logcat -d | grep -A14 "FATAL EXCEPTION" | head -40; } || ok "no crash in the log"
echo; echo "== $FAILS failed =="
[ "$FAILS" = 0 ]
