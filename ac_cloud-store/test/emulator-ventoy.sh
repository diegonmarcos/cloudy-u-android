#!/usr/bin/env bash
# #1405 Cloud Store > Cloud > Ventoy on an emulator, with vold's virtual disk as the USB-C drive (the #1375 method):
#   01 the Cloud page with the Ventoy tab right after Tiny-ML      02 the Ventoy section (drive picker, the virtual disk)
#   03 the SAF grant of exactly that drive                        04-07 the four tables (Linux, Android Desktop, Android
#   Mobile, Customised ISOs)                                       08 a small test image installed onto the virtual drive
# and asserts the file landed in /ISO on the drive (adb ls), never in the phone's own storage.
# Usage: emulator-ventoy.sh <store.apk> <out-dir>. Not run by `shell` (no emulator there): test-store-ventoy-emulator.yml.
set -u
APK=${1:?store apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudstore
mkdir -p "$OUT/shots"
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
fail() { echo "::error title=Store Ventoy::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; dump > "$OUT/ui-fail.xml"; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
tap() { where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
has() { grep -q "$1" "$OUT/ui.xml"; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }
# Scroll until [pattern] is on screen (max [n] swipes).
seek() { for i in $(seq 1 "${2:-25}"); do dump >/dev/null; grep -qE "$1" "$OUT/ui.xml" && return 0; adb shell input swipe 540 1700 540 700 400; sleep 1; done; return 1; }
# Bring the node matching [pattern] near the top so the shot shows what follows it.
top() { where "$1" | { read -r px py && [ "$py" -gt 500 ] && adb shell input swipe 540 "$py" 540 420 600; }; sleep 1; }

# ── the drive: vold's virtual disk, a public (removable) volume ────────────────────────────────────────────────────
adb shell sm set-virtual-disk true || fail "sm set-virtual-disk refused"
DISK=""
for i in $(seq 1 30); do DISK=$(adb shell sm list-disks | tr -d '\r' | grep -m1 'disk:'); [ -n "$DISK" ] && break; sleep 2; done
[ -n "$DISK" ] || fail "no virtual disk appeared"
adb shell sm partition "$DISK" public || fail "sm partition $DISK public failed"
UUID=""
for i in $(seq 1 45); do
  line=$(adb shell sm list-volumes public | tr -d '\r' | grep -m1 ' mounted ')
  [ -n "$line" ] && { UUID=$(echo "$line" | awk '{print $3}'); break; }; sleep 2
done
[ -n "$UUID" ] && [ "$UUID" != "null" ] || fail "the virtual volume never mounted"
ROOTV=/storage/$UUID
echo "virtual drive: $ROOTV"

# A small test image, picked from Downloads (Install a local image): bytes we know, so the copy is checked by hash.
python3 -I -c 'import sys; open(sys.argv[1],"wb").write(b"VENTOY-1405-TEST-IMAGE\n"*4096)' "$OUT/ventoy-test.iso"
SHA=$(sha256sum "$OUT/ventoy-test.iso" | awk '{print $1}')
adb push "$OUT/ventoy-test.iso" /sdcard/Download/ventoy-test.iso >/dev/null || fail "push of the test image failed"
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true

adb install -r -g "$APK" || fail "install failed"
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" --es tab cloud >/dev/null; sleep 10
for i in 1 2 3; do dump >/dev/null; has 'text="Ventoy"' && break; tap '^Cloud$' || true; sleep 3; done
shot 01-cloud-tabs
has 'text="Tiny-ML"' || fail "no Tiny-ML tab on the Cloud page: $(texts)"
python3 -I -c '
import re,sys
t=[m for m in re.findall(r"text=\"([^\"]+)\"",open(sys.argv[1]).read())]
i=t.index("Tiny-ML") if "Tiny-ML" in t else -1
sys.exit(0 if i>=0 and i+1<len(t) and t[i+1]=="Ventoy" else 1)' "$OUT/ui.xml" || fail "Ventoy is not the tab right after Tiny-ML: $(texts)"
tap '^Ventoy$' || fail "the Ventoy tab is not tappable"; sleep 3
shot 02-ventoy-section
has 'Drive' || fail "no Drive heading: $(texts)"
has "$ROOTV" || fail "the drive picker does not list the virtual drive $ROOTV: $(texts)"
has '/storage/emulated' && fail "the phone's own storage is offered as a target"

# ── grant exactly that drive (the system's own per-volume dialog) ──────────────────────────────────────────────────
tap '^Grant access$' || fail "no Grant access on the selected drive: $(texts)"; sleep 4
shot 03a-grant-dialog
for i in 1 2 3 4; do tap '^(USE THIS FOLDER|Use this folder)$' && break; sleep 2; done || fail "no 'Use this folder' in the system picker: $(texts)"
sleep 2; tap '^(ALLOW|Allow)$' || true; sleep 4
shot 03-drive-granted
has 'image\(s\) on it\|Not a Ventoy drive yet' || fail "the drive did not open after the grant: $(texts)"

# ── the four tables ────────────────────────────────────────────────────────────────────────────────────────────────
n=4
for t in 'Linux distros' 'Android Desktop' 'Android Mobile' 'Customised ISOs'; do
  seek "text=\"$t\"" 40 || fail "no '$t' table: $(texts)"
  top "^$t\$"; sleep 4
  shot "0$n-table-$(echo "$t" | tr 'A-Z ' 'a-z-' | tr -cd 'a-z-')"; n=$((n+1))
done
has 'flash, not Ventoy' || { seek 'flash, not Ventoy' 10; } || fail "Android Mobile rows are not marked flash, not Ventoy"
seek 'text="Ubuntu Desktop LTS"' 1 || true

# ── install the test image onto the virtual drive ──────────────────────────────────────────────────────────────────
for i in $(seq 1 60); do dump >/dev/null; has 'text="Install a local image"' && break; adb shell input swipe 540 700 540 1700 300; sleep 1; done
tap '^Install a local image$' || fail "no Install a local image: $(texts)"; sleep 4
shot 08a-picker
if ! tap '^ventoy-test\.iso$'; then
  tap 'Show roots' || true; sleep 2; tap '^Downloads$' || true; sleep 3
  tap '^ventoy-test\.iso$' || fail "the test image is not in the picker: $(texts)"
fi
for i in $(seq 1 30); do dump >/dev/null; has 'ventoy-test.iso installed' && break; sleep 2; done
for i in $(seq 1 20); do dump >/dev/null; has 'text="/ISO/ventoy-test.iso"' && break; adb shell input swipe 540 1500 540 1100 300; sleep 1; done
shot 08-installed
has 'ventoy-test.iso installed' || fail "no installed message: $(texts)"
has "$SHA" || fail "the page does not show the image's sha256 $SHA: $(texts)"
GOT=$(adb shell "sha256sum '$ROOTV/ISO/ventoy-test.iso'" | tr -d '\r' | awk '{print $1}')
[ "$GOT" = "$SHA" ] || fail "the drive's /ISO/ventoy-test.iso has sha256 '$GOT', expected $SHA"
adb shell "ls -la '$ROOTV' '$ROOTV/ISO'" > "$OUT/drive-listing.txt" 2>&1
adb shell "ls /sdcard/ISO" >/dev/null 2>&1 && fail "an ISO folder appeared on the phone's own storage"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Store crashed"
adb shell sm set-virtual-disk false || true
echo "Store Ventoy: tab after Tiny-ML, virtual drive picked and granted, four tables, test image installed ($SHA) on $ROOTV/ISO."
