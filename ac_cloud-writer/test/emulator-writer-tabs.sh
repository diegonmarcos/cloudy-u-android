#!/usr/bin/env bash
# #1418 Cloud Writer on an emulator: one screenshot per bottom-nav tab, the WYSIWYG box, Configs (OCR
# section) and one OCR result (test/fixtures/ocr-sample.png shared into Writer -> Enhancer input box).
# Usage: emulator-writer-tabs.sh <apk> <out-dir>. Run by .github/workflows/writer-tabs-emulator.yml.
# Exit 1 when the app does not start, a tab does not draw, or the OCR text never reaches the box.
set -u
APK="$1"; OUT="$2"
PKG=com.diegonmarcos.cloudwriter
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
mkdir -p "$OUT"
fail=0
bad() { echo "FAIL  $*"; fail=1; }

adb wait-for-device
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell svc power stayon true; adb shell input keyevent 82 || true
adb install -r -g "$APK" >/dev/null || { echo "FAIL  install"; exit 1; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
adb logcat -c || true

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; adb exec-out uiautomator dump /dev/tty > "$OUT/$1.xml" 2>/dev/null || true; }
has() { grep -q -- "$2" "$OUT/$1.xml"; }

start_section() { adb shell am start -W -n "$PKG/.MainActivity" --es section "$1" >/dev/null 2>&1; }

# First start (a first-run card, if any, is captured as it is).
start_section home; shot 00-first-start 6
for s in enhancer drafter home translator grammar; do
  start_section "$s"; shot "tab-$s"
done
# The bar: the five labels in the owner's order on every tab shot.
for s in enhancer drafter home translator grammar; do
  for l in Enhancer Drafter Home Translator Grammar; do has "tab-$s" "text=\"$l\"" || has "tab-$s" "$l" || bad "tab-$s: bar label $l missing"; done
done
has tab-enhancer "WYSIWYG" || bad "tab-enhancer: no WYSIWYG mode selector"
has tab-enhancer "Load image" || bad "tab-enhancer: no Load image action"
has tab-translator "Engine" || bad "tab-translator: no engine picker"

# Configs with its OCR section (scroll to it).
start_section settings; shot configs-top
for _ in 1 2 3 4 5 6; do adb shell input swipe 500 1600 500 500 300; sleep 1; done
shot configs-ocr 2
has configs-ocr "OCR engine" || bad "configs: no OCR engine row"

# OCR: push the fixture into MediaStore and share it into Writer.
adb push "$HERE/fixtures/ocr-sample.png" /sdcard/Pictures/ocr-sample.png >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/ocr-sample.png >/dev/null 2>&1
sleep 3
ID=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name 2>/dev/null | tr -d '\r' | grep 'ocr-sample' | sed -E 's/.*_id=([0-9]+).*/\1/' | tail -1)
echo "media id: ${ID:-none}"
adb shell am force-stop "$PKG"
if [ -n "$ID" ]; then
  adb shell am start -W -a android.intent.action.SEND -t image/png --grant-read-uri-permission \
    --eu android.intent.extra.STREAM "content://media/external/images/media/$ID" -n "$PKG/.MainActivity" >/dev/null 2>&1
else
  adb shell am start -W -a android.intent.action.SEND -t image/png \
    --eu android.intent.extra.STREAM file:///sdcard/Pictures/ocr-sample.png -n "$PKG/.MainActivity" >/dev/null 2>&1
fi
shot ocr-result 15
has ocr-result "Meeting moved" || has ocr-result "Friday" || bad "OCR text did not reach the Enhancer input box"
# WYSIWYG mode: the same box, with the formatting strip, after the OCR text landed.
adb shell input swipe 500 1200 500 900 300; shot wysiwyg-mode 2

adb logcat -d -t 2000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 "$OUT/logcat.txt" | head -60 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && bad "a crash in logcat (crash.txt)"
ls -la "$OUT"
exit $fail
