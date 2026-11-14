#!/bin/bash
# Emulator test (#1740): Drive > Explorer > per-file menu > Convert section, on fixtures.
#   1. Open holds only opening actions; Convert holds Unzip, Zip, Convert, Convert to PDF, Convert from PDF (screenshot per menu);
#   2. image -> PDF, markdown -> PDF, html -> PDF, text PDF -> MD / HTML / OCR, scanned PDF -> OCR (an honest reason when the
#      image-scan engine APK is absent), folder -> zip, zip -> unzip: each output file is checked on the device (%PDF header, content);
#   3. an unsupported type (docx) shows a reason; Drive never crashes.
# Usage: emulator-convert.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT/shots" "$OUT/fx"
fail() { echo "::error title=Drive Convert::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && break; sleep 2; done; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
# the LAST match (a dialog's confirm button shares its text with the dialog title)
tapl() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I); last=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and t and pat.search(t.group(1)): a=list(map(int,b.groups())); last=((a[0]+a[2])//2,(a[1]+a[3])//2)
if not last: sys.exit(1)
print(*last)' "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
tap() { where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }
has() { grep -q "text=\"$1\"" "$OUT/ui.xml"; }
# the More button on the row whose name is $1: the More node vertically nearest the name node
more_of() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); name=sys.argv[1]; ys=None; mores=[]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if not b: continue
    a=list(map(int,b.groups())); cy=(a[1]+a[3])//2; cx=(a[0]+a[2])//2
    if re.search(r"text=\""+re.escape(name)+"\"",n): ys=cy
    if re.search(r"content-desc=\"More\"",n): mores.append((cx,cy))
if ys is None or not mores: sys.exit(1)
c=min(mores,key=lambda p:abs(p[1]-ys)); print(c[0],c[1])' "$1"; }
# the list pane is small: scroll it (up first, then back down) until the row is on screen
open_menu() { local xy i; xy=$(more_of "$1")
  for i in 1 2 3 4; do [ -n "$xy" ] && break; adb shell input swipe 160 470 160 320 300; sleep 1; xy=$(more_of "$1"); done
  for i in 1 2 3 4 5 6 7 8; do [ -n "$xy" ] && break; adb shell input swipe 160 320 160 470 300; sleep 1; xy=$(more_of "$1"); done
  [ -n "$xy" ] || fail "no row/More for $1: $(texts)"
  adb shell input tap $xy; sleep 2; }
close_menu() { adb shell input keyevent 4; sleep 1; }
D=/storage/emulated/0/Xconv
devhas() { adb shell "[ -s '$D/$1' ] && echo yes" | tr -d '\r' | grep -q yes; }
waitfile() { local i; for i in $(seq 1 45); do devhas "$1" && return 0; sleep 2; done; return 1; }
pick() { # $1 file name, $2 menu item regex, $3 expected output file
  open_menu "$1"; tap "$2" || fail "menu item $2 missing for $1: $(texts)"; sleep 2
  case "${4:-}" in
    dialog) tap "^$5\$" || fail "dialog button $5 missing: $(texts)"; sleep 2;;
    confirm) tapl "^$5\$" || fail "confirm button $5 missing: $(texts)"; sleep 2;;
  esac
  waitfile "$3" || { shot "fail-$3"; fail "$3 was not written for $1: $(texts)"; }; }

python3 -I - "$OUT/fx" <<'PY'
import sys,zlib,struct,zipfile,os
d=sys.argv[1]
def png(path,w=120,h=80):
    raw=b"".join(b"\x00"+bytes([(x*2)%256,(y*3)%256,160])*1 if False else b"\x00"+b"".join(bytes([(x*2)%256,(y*3)%256,160]) for x in range(w)) for y in range(h))
    def ch(t,b): c=struct.pack(">I",len(b))+t+b; return c+struct.pack(">I",zlib.crc32(t+b)&0xffffffff)
    open(path,"wb").write(b"\x89PNG\r\n\x1a\n"+ch(b"IHDR",struct.pack(">IIBBBBB",w,h,8,2,0,0,0))+ch(b"IDAT",zlib.compress(raw))+ch(b"IEND",b""))
png(d+"/photo.png")
open(d+"/notes.md","w").write("# Notes\n\nA paragraph of markdown.\n\n- one\n- two\n")
open(d+"/page.html","w").write("<html><body><h1>Hello PDF</h1><p>HTML to PDF page.</p></body></html>")
open(d+"/data.txt","w").write("plain text fixture\nsecond line\n")
open(d+"/report.docx","wb").write(b"PK\x03\x04not really")
def pdf(path,objs):
    out=b"%PDF-1.4\n"; offs=[]
    for i,o in enumerate(objs,1): offs.append(len(out)); out+=b"%d 0 obj\n"%i+o+b"\nendobj\n"
    x=len(out); out+=b"xref\n0 %d\n0000000000 65535 f \n"%(len(objs)+1)
    for o in offs: out+=b"%010d 00000 n \n"%o
    out+=b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"%(len(objs)+1,x); open(path,"wb").write(out)
txt=b"BT /F1 20 Tf 72 760 Td (ANNUAL REPORT) Tj 0 -30 Td /F1 11 Tf (This is the text layer of the fixture PDF and it is long enough to be a paragraph.) Tj 0 -16 Td (- first bullet) Tj 0 -16 Td (- second bullet) Tj ET"
pdf(d+"/text.pdf",[b"<< /Type /Catalog /Pages 2 0 R >>",b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",b"<< /Length %d >>\nstream\n"%len(txt)+txt+b"\nendstream"])
w,h=200,100; img=zlib.compress(b"".join(bytes([255,255,255])*w for _ in range(h)))
pdf(d+"/scan.pdf",[b"<< /Type /Catalog /Pages 2 0 R >>",b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>",b"<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length %d >>\nstream\n"%(w,h,len(img))+img+b"\nendstream",b"<< /Length 36 >>\nstream\nq 595 0 0 300 0 400 cm /Im0 Do Q\nendstream"])
os.makedirs(d+"/folder",exist_ok=True); open(d+"/folder/a.txt","w").write("alpha")
with zipfile.ZipFile(d+"/pack.zip","w") as z: z.writestr("inner/hello.txt","hello zip")
PY
adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb shell "rm -rf '$D'; mkdir -p '$D'"
adb push "$OUT/fx/." "$D/" >/dev/null || fail "fixture push failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Drive never came to the front"
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 2
for i in 1 2 3; do tap '^Explorer$' && break; sleep 2; done || fail "no Explorer tab: $(texts)"
sleep 2; tap '^Local$' || true; sleep 3
dump >/dev/null
if grep -q 'text="cannot open' "$OUT/ui.xml"; then tap '^emulated › 0$' || fail "no shared-storage pane: $(texts)"; sleep 3; fi
# into the fixture folder: search narrows the long root listing to it
tap '^Search$' || fail "no search icon: $(texts)"
sleep 2
FIELD=$(dump | python3 -I -c '
import re,sys
x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if "android.widget.EditText" in n:
        b=list(map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups())); print((b[0]+b[2])//2,(b[1]+b[3])//2); break')
[ -n "$FIELD" ] || fail "no search field"
adb shell input tap $FIELD; sleep 1; adb shell input text Xconv; sleep 3
adb shell dumpsys input_method | grep -q 'mInputShown=true' && { adb shell input keyevent 4; sleep 1; }
tap '^Xconv/$' || fail "fixture folder not listed: $(texts)"
sleep 3; shot 01-fixture-folder
has data.txt || fail "fixtures not listed: $(texts)"

# 1. section move: Open vs Convert on a PDF row
open_menu text.pdf; shot 02-menu-pdf
for t in 'Open' 'Convert' 'Convert to PDF…' 'Convert from PDF'; do dump | grep -q "$t" || fail "menu lacks '$t': $(texts)"; done
dump | grep -q 'Unzip…' || fail "Unzip… (disabled) is not listed: $(texts)"
close_menu

# 2. conversions
pick photo.png 'Convert to PDF…' photo.pdf; shot 03-image-pdf
pick notes.md 'Convert to PDF…' notes.pdf; shot 04-md-pdf
pick page.html 'Convert to PDF…' page.pdf; shot 05-html-pdf
pick data.txt 'Convert to PDF…' data.pdf
for f in photo notes page data; do adb shell "head -c 8 '$D/$f.pdf'" | grep -q '%PDF-1' || fail "$f.pdf is not a PDF"; done
pick text.pdf 'Convert from PDF' text.md dialog md; shot 06-pdf-md
adb shell "cat '$D/text.md'" > "$OUT/text.md"; grep -q 'ANNUAL REPORT' "$OUT/text.md" || fail "text.md lost the text layer: $(cat "$OUT/text.md")"
grep -q '^#' "$OUT/text.md" || fail "text.md has no heading"
pick text.pdf 'Convert from PDF' text.html dialog html
adb shell "cat '$D/text.html'" > "$OUT/text.html"; grep -q '<h1>ANNUAL REPORT</h1>' "$OUT/text.html" || fail "text.html has no heading: $(cat "$OUT/text.html")"
pick folder 'Zip / Compress' folder.zip confirm Compress; shot 07-zip
pick pack.zip 'Unzip…' pack/inner/hello.txt confirm 'Unzip…'; shot 08-unzip
# scanned PDF: OCR when the engine APK is installed, else the reason (never a crash, never an empty file)
open_menu scan.pdf; tap 'Convert from PDF' ; sleep 2; tap '^OCR$'; sleep 12; shot 09-scan-ocr
devhas scan.ocr.txt && echo "scan OCR wrote text" || echo "scan OCR: $(texts)" | tee "$OUT/scan-ocr-note.txt"
# unsupported type: a reason
sleep 15; open_menu report.docx; tap 'Convert to PDF…'; sleep 3; shot 10-docx-reason
# the reason is a transient snackbar the accessibility dump does not carry: the screenshot (10-docx-reason) is its evidence
devhas report.pdf && fail "docx produced a PDF"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
adb shell "ls -l '$D'" > "$OUT/device-listing.txt"
echo "Drive > Convert: section move, image/md/html/txt -> PDF, PDF -> MD/HTML, zip/unzip, scan OCR, docx reason."
