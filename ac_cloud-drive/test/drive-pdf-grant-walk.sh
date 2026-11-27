#!/bin/bash
# #1949 the same walk as Cloud Notes' (ac_cloud-notes/test/notes-pdf-grant-walk.sh) against Cloud Drive's PdfReaderActivity, with the SAME PDF: a content:// URI with ONLY a temporary read grant must render page 1 in the shared libs:pdf reader, the controls must be on screen, the Edit toggle must add text, and Save must write a changed copy.
# The sender is the media provider: the PDF is scanned into MediaStore, then `am start --grant-read-uri-permission` hands out
# content://media/external/file/<id> (the grant is the reader's only way in). Needs PKG, adb, python3.
set -u
OUT=walk-out; mkdir -p "$OUT"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb shell settings put global hide_error_dialogs 1 || true  # the slow emulator raises "Pixel Launcher isn't responding" over the screenshots
adb install -r app-under-test.apk || { echo "::error::adb install failed"; exit 2; }
adb shell pm revoke "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm revoke "$PKG" android.permission.READ_MEDIA_IMAGES 2>/dev/null || true
adb push ac_cloud-notes/test/fixtures/pdf-1949.pdf /sdcard/Download/pdf-1949.pdf
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/pdf-1949.pdf >/dev/null
adb shell content call --uri content://media/ --method scan_file --arg /sdcard/Download/pdf-1949.pdf || true  # MediaStore.scanFile's own call
adb shell cmd media_provider scan 2>/dev/null | head -2 || true
ID=""
for _ in $(seq 1 20); do
  ID=$(adb shell content query --uri content://media/external/file --projection _id:_display_name | tr -d '\r' | grep 'pdf-1949.pdf' | sed -n 's/.*_id=\([0-9]*\),.*/\1/p' | head -1)
  [ -n "$ID" ] && break; sleep 2
done
if [ -z "$ID" ]; then  # the scan broadcast did not index it: insert the row through the Downloads collection and stream the bytes in
  echo "scan did not index the fixture; inserting through content://media/external/downloads"
  adb shell content insert --uri content://media/external/downloads --bind _display_name:s:pdf-1949.pdf --bind mime_type:s:application/pdf --bind relative_path:s:Download/ 2>&1 | head -5 || true
  adb shell content query --uri content://media/external/file --projection _id:_display_name:_data 2>&1 | head -20 || true
  DID=$(adb shell content query --uri content://media/external/downloads --projection _id:_display_name | tr -d '\r' | grep 'pdf-1949.pdf' | sed -n 's/.*_id=\([0-9]*\),.*/\1/p' | tail -1)
  [ -n "$DID" ] && adb shell "content write --uri content://media/external/downloads/$DID" < ac_cloud-notes/test/fixtures/pdf-1949.pdf
  ID="$DID"; URI="content://media/external/downloads/$ID"
fi
[ -n "$ID" ] || { echo "::error::the fixture is not in MediaStore"; exit 3; }
URI="${URI:-content://media/external/file/$ID}"
echo "URI=$URI"
adb logcat -c
adb shell am start --grant-read-uri-permission -a android.intent.action.VIEW -t application/pdf -d "$URI" -n "$PKG/com.diegonmarcos.clouddrive.PdfReaderActivity"
# 3, 6 and 10 s after the launch: a screenshot and the resumed activity each (the reader must stay on top)
sleep 3; adb exec-out screencap -p > "$OUT/pdf-1949-drive-t03.png"; adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2 | tee "$OUT/top-t03.txt"
sleep 3; adb exec-out screencap -p > "$OUT/pdf-1949-drive-t06.png"; adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2 | tee "$OUT/top-t06.txt"
sleep 4; adb exec-out screencap -p > "$OUT/pdf-1949-drive-t10.png"; adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2 | tee "$OUT/top-t10.txt"
sleep 12
adb logcat -d -v threadtime -s Notes1949:V > "$OUT/lifecycle.txt" || true
cat "$OUT/lifecycle.txt" | head -150
adb exec-out screencap -p > "$OUT/pdf-1949-drive-grant.png"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null
timeout 60 adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
rc=0
grep -q "pdf-1949.pdf" "$OUT/ui.xml" || { echo "::error::the reader title is not on screen"; rc=1; }
grep -q 'content-desc="Edit"' "$OUT/ui.xml" || { echo "::error::the reader chrome (the Edit toggle) is not on screen"; rc=1; }
for d in Search Outline "Night mode" "Continuous or paged" More; do
  grep -q "content-desc=\"$d\"" "$OUT/ui.xml" || { echo "::error::the shared reader control '$d' is not on screen"; rc=1; }
done
grep -qE 'Cannot (read|open|show)|it is not a PDF' "$OUT/ui.xml" && { echo "::error::the reader shows an error"; rc=1; }
# EDIT + SAVE: the temporary grant is read-only, so Save must fall back to a copy in Downloads, and the copy must differ.
tap() { # tap the first node whose text or content-desc equals $1 (uiautomator bounds centre)
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml "$OUT/ui-step.xml" >/dev/null
  python3 - "$OUT/ui-step.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if sys.argv[2] in (n.get("text"), n.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
PY
}
EXY=$(tap "Edit") && { adb shell input tap $EXY; sleep 3; } || { echo "::error::no Edit toggle in the reader"; rc=1; }
adb exec-out screencap -p > "$OUT/pdf-1949-drive-editmode.png"
XY=$(tap "Text") || { echo "::error::no Text tool in the reader"; rc=1; XY=""; }
if [ -n "$XY" ]; then
  adb shell input tap $XY; sleep 2
  adb shell input tap 540 1000; sleep 3
  adb shell input text "Edited1949"; sleep 1
  adb exec-out screencap -p > "$OUT/pdf-1949-drive-edit-dialog.png"
  OXY=$(tap "OK") && adb shell input tap $OXY; sleep 4
  adb exec-out screencap -p > "$OUT/pdf-1949-drive-edit.png"
  SXY=$(tap "Save") && adb shell input tap $SXY; sleep 5
  adb exec-out screencap -p > "$OUT/pdf-1949-drive-saved.png"
  adb shell ls -l /sdcard/Download/ | tee "$OUT/downloads.txt"
  adb pull /sdcard/Download/pdf-1949-edited.pdf "$OUT/edited.pdf" >/dev/null 2>&1 || { echo "::error::no saved copy in Downloads"; rc=1; }
  if [ -f "$OUT/edited.pdf" ]; then
    cmp -s "$OUT/edited.pdf" ac_cloud-notes/test/fixtures/pdf-1949.pdf && { echo "::error::the saved file did not change"; rc=1; }
    [ "$(stat -c %s "$OUT/edited.pdf")" -gt "$(stat -c %s ac_cloud-notes/test/fixtures/pdf-1949.pdf)" ] || { echo "::error::the saved file did not grow"; rc=1; }
    head -c 5 "$OUT/edited.pdf" | grep -q '%PDF-' || { echo "::error::the saved file is not a PDF"; rc=1; }
  fi
fi
python3 -m pip install -q pillow
python3 - "$OUT/pdf-1949-drive-grant.png" <<'PY' || rc=1
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("L"); w, h = im.size
body = im.crop((0, int(h * 0.30), w, h))
dark = sum(1 for p in body.getdata() if p < 80)
print("dark pixels in the page area:", dark)
sys.exit(0 if dark > 400 else 1)
PY
[ $rc -eq 0 ] || echo "::error::page 1 was not rendered"
exit $rc
