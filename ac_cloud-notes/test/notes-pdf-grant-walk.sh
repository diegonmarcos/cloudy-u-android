#!/bin/bash
# #1949 the owner's path: another app's content:// URI with ONLY a temporary read grant goes into Cloud Notes; Notes must render page 1.
# The sender is the media provider: the PDF is scanned into MediaStore, then `am start --grant-read-uri-permission` hands out
# content://media/external/file/<id> (Notes holds no storage permission, so the grant is its only way in). Needs PKG, adb, python3.
set -u
OUT=walk-out; mkdir -p "$OUT"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb install -r -g app-under-test.apk || { echo "::error::adb install failed"; exit 2; }
adb shell pm revoke "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm revoke "$PKG" android.permission.READ_MEDIA_IMAGES 2>/dev/null || true
adb push ac_cloud-notes/test/fixtures/pdf-1949.pdf /sdcard/Download/pdf-1949.pdf
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/pdf-1949.pdf >/dev/null
ID=""
for _ in $(seq 1 20); do
  ID=$(adb shell content query --uri content://media/external/file --projection _id --where "_display_name='pdf-1949.pdf'" | tr -d '\r' | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$ID" ] && break; sleep 2
done
[ -n "$ID" ] || { echo "::error::the fixture is not in MediaStore"; exit 3; }
URI="content://media/external/file/$ID"
echo "URI=$URI"
adb logcat -c
adb shell am start --grant-read-uri-permission -a android.intent.action.VIEW -t application/pdf -d "$URI" -n "$PKG/app.cloudnotes.pro.MainActivity"
sleep 25
adb exec-out screencap -p > "$OUT/pdf-1949-notes-grant.png"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null
timeout 60 adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
rc=0
grep -q "pdf-1949.pdf" "$OUT/ui.xml" || { echo "::error::the reader title is not on screen"; rc=1; }
grep -q 'text="Save"' "$OUT/ui.xml" || { echo "::error::the reader chrome (Save) is not on screen"; rc=1; }
grep -qE 'Cannot (read|open|show)|it is not a PDF' "$OUT/ui.xml" && { echo "::error::the reader shows an error"; rc=1; }
python3 -m pip install -q pillow
python3 - "$OUT/pdf-1949-notes-grant.png" <<'PY' || rc=1
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
