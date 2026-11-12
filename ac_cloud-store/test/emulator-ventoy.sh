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
shot() { [ "${1#03a}" = "$1" ] && calm; adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
tap() { calm; where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
has() { grep -q "$1" "$OUT/ui.xml"; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }
# The Store's wireless-debug probe makes the system ask "Allow wireless debugging on this network?" on an emulator:
# not part of this proof, so it is cancelled wherever it appears.
# The Store's own first-run question (keep update checks running?) is answered "Not now" for the same reason.
calm() { for k in 1 2 3 4; do dump >/dev/null
  if grep -q 'Allow wireless debugging' "$OUT/ui.xml"; then where '^Cancel$' | { read -r px py && adb shell input tap "$px" "$py"; }
  elif grep -q "allowed to install unknown apps" "$OUT/ui.xml"; then where '^Cancel$' | { read -r px py && adb shell input tap "$px" "$py"; }
  elif grep -q 'text="Not now"' "$OUT/ui.xml"; then where '^Not now$' | { read -r px py && adb shell input tap "$px" "$py"; }
  else return 0; fi; sleep 2; done; }
# Swipes are relative to the emulator's real screen (wm size), not a fixed 1080p.
read -r SW SH <<<"$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')"
SW=${SW:-1080}; SH=${SH:-2400}; X=$((SW/2)); YLO=$((SH*78/100)); YHI=$((SH*35/100)); YTOP=$((SH*22/100))
up() { adb shell input swipe $X $YLO $X $YHI 400; }
down() { adb shell input swipe $X $YHI $X $YLO 300; }
# Scroll until [pattern] is on screen (max [n] swipes).
seek() { for i in $(seq 1 "${2:-25}"); do calm; grep -qE "$1" "$OUT/ui.xml" && return 0; up; sleep 1; done; return 1; }
# Bring the node matching [pattern] near the top so the shot shows what follows it.
# Never start a drag in the bottom 20% (the home gesture): scroll first, then drag the heading from inside the page.
top() { for k in 1 2 3; do where "$1" | { read -r px py; echo "$py" > "$OUT/.py"; }; py=$(cat "$OUT/.py"); [ -z "$py" ] && return 0
    if [ "$py" -gt $((SH*70/100)) ]; then up; sleep 1; continue; fi
    [ "$py" -gt "$YTOP" ] && adb shell input swipe $X "$py" $X $YTOP 600; break; done; sleep 1; }

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
# vold's virtual disk is an SD-card-class volume: Android refuses a tree on its ROOT ("Can't use this folder"), so the
# app also accepts its top-level ISO folder (a real USB-C drive grants the root). The folder is made here.
adb shell "mkdir -p '$ROOTV/ISO'" || echo "note: the shell could not create $ROOTV/ISO"

# A small test image, picked from Downloads (Install a local image): bytes we know, so the copy is checked by hash.
python3 -I -c 'import sys; open(sys.argv[1],"wb").write(b"VENTOY-1405-TEST-IMAGE\n"*4096)' "$OUT/ventoy-test.iso"
SHA=$(sha256sum "$OUT/ventoy-test.iso" | awk '{print $1}')
adb push "$OUT/ventoy-test.iso" /sdcard/Download/ventoy-test.iso >/dev/null || fail "push of the test image failed"
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true

adb install -r -g "$APK" || fail "install failed"
timeout 120 adb shell am start -W -n "$PKG/.MainActivity" --es tab cloud >/dev/null; sleep 10
# First run may open system pages (battery optimisation, permission grants) over the Store: back out to it.
front() { for k in 1 2 3 4 5 6; do adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG" && return 0; adb shell input keyevent 4; sleep 2; done; timeout 60 adb shell am start -W -n "$PKG/.MainActivity" --es tab cloud >/dev/null; sleep 5; }
for i in 1 2 3 4; do front; calm; has 'text="Ventoy"' && break; tap '^Cloud$' || true; sleep 3; done
shot 01-cloud-tabs
has 'text="Tiny-ML"' || fail "no Tiny-ML tab on the Cloud page: $(texts)"
python3 -I -c '
import re,sys
t=[m for m in re.findall(r"text=\"([^\"]+)\"",open(sys.argv[1]).read())]
# StoreStrip order: the declared groups, then right after Tiny-ML only the non-group slots Fleet Data, "|" and Ventoy.
i=t.index("Tiny-ML") if "Tiny-ML" in t else -1
j=t.index("Ventoy",i+1) if i>=0 and "Ventoy" in t[i+1:] else -1
sys.exit(0 if j>i>=0 and all(x in ("Fleet Data","|") for x in t[i+1:j]) else 1)' "$OUT/ui.xml" || fail "Ventoy is not the tab after Tiny-ML (only Fleet Data and the | separator may sit between): $(texts)"
tap '^Ventoy$' || fail "the Ventoy tab is not tappable"; sleep 3
shot 02-ventoy-section
has 'Drive' || fail "no Drive heading: $(texts)"
has "$ROOTV" || fail "the drive picker does not list the virtual drive $ROOTV: $(texts)"
has '/storage/emulated' && fail "the phone's own storage is offered as a target"

# ── grant exactly that drive (the system's own per-volume dialog) ──────────────────────────────────────────────────
tap '^Grant access$' || fail "no Grant access on the selected drive: $(texts)"; sleep 4
shot 03a-grant-dialog
dump >/dev/null
if has "Can.t use this folder"; then
  tap '^ISO$' || fail "the root is refused and there is no ISO folder in the picker: $(texts)"; sleep 3; shot 03b-grant-iso-folder
fi
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
grep -q 'flash, not Ventoy' "$OUT/ui-06-table-android-mobile.xml" || fail "Android Mobile rows are not marked flash, not Ventoy"
seek 'text="Ubuntu Desktop LTS"' 1 || true

# ── install the test image onto the virtual drive ──────────────────────────────────────────────────────────────────
for i in $(seq 1 60); do dump >/dev/null; has 'text="Install a local image"' && break; down; sleep 1; done
tap '^Install a local image$' || fail "no Install a local image: $(texts)"; sleep 4
shot 08a-picker
if ! tap '^ventoy-test\.iso$'; then
  tap 'Show roots' || true; sleep 2; tap '^Downloads$' || true; sleep 3
  tap '^ventoy-test\.iso$' || fail "the test image is not in the picker: $(texts)"
fi
for i in $(seq 1 30); do dump >/dev/null; has 'ventoy-test.iso installed' && break; sleep 2; done
for i in $(seq 1 20); do dump >/dev/null; has 'text="/ISO/ventoy-test.iso"' && break; adb shell input swipe $X $((SH*60/100)) $X $((SH*45/100)) 300; sleep 1; done
shot 08-installed
has 'ventoy-test.iso installed' || fail "no installed message: $(texts)"
has "$SHA" || fail "the page does not show the image's sha256 $SHA: $(texts)"
GOT=$(adb shell "sha256sum '$ROOTV/ISO/ventoy-test.iso'" | tr -d '\r' | awk '{print $1}')
[ "$GOT" = "$SHA" ] || fail "the drive's /ISO/ventoy-test.iso has sha256 '$GOT', expected $SHA"
adb shell "ls -la '$ROOTV' '$ROOTV/ISO'" > "$OUT/drive-listing.txt" 2>&1
adb shell "ls /sdcard/ISO" >/dev/null 2>&1 && fail "an ISO folder appeared on the phone's own storage"

# ── #1444 the erase gate and the refusal. A vold virtual disk is not a USB device the Store can write raw, so the typed-confirm
# gate must open, a wrong phrase must erase nothing, and the right phrase must be refused BEFORE any write, saying why; the
# drive's files must be untouched. (The real install needs a USB mass-storage drive and cld.termux: CHECK ON THE PHONE.)
for i in $(seq 1 60); do dump >/dev/null; has 'Ventoy on this drive' && break; down; sleep 1; done
has 'Ventoy on this drive' || fail "no 'Ventoy on this drive' row: $(texts)"
tap 'Ventoy on this drive'; sleep 2
shot 09-install-row
has 'Erase .*install Ventoy' || fail "the install row does not offer Erase & install Ventoy: $(texts)"
has 'Cloud Terminal' || fail "the install row does not name the Cloud Terminal: $(texts)"
tap '^Erase .*install Ventoy$' || fail "cannot tap Erase & install Ventoy"; sleep 2
dump >/dev/null; shot 10-gate-dialog
has 'ALL DATA WILL BE ERASED' || fail "the gate does not warn ALL DATA WILL BE ERASED: $(texts)"
PHRASE=$(python3 -I -c '
import html,re,sys
x=open(sys.argv[1]).read()
# uiautomator escapes the quotes as &quot; or, when the text holds a double quote, quotes the attribute with apostrophes
m=re.search(r"Type (?:&quot;|\")(ERASE [^&\"]*)(?:&quot;|\") to continue",x)
print(html.unescape(m.group(1)) if m else "")' "$OUT/ui.xml")
[ -n "$PHRASE" ] || fail "the gate shows no phrase to type: $(texts)"
field() { dump >/dev/null; python3 -I -c '
import re,sys
x=open(sys.argv[1]).read()
for n in re.findall(r"<node [^>]*>",x):
    if "EditText" in n:
        a=list(map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break' "$OUT/ui.xml" | { read -r px py && adb shell input tap "$px" "$py"; }; }
field; sleep 1; adb shell input text "nope"; sleep 1
tap '^Erase$' || fail "no Erase button in the gate"; sleep 3
shot 11-gate-wrong-phrase
dump >/dev/null; has 'Not confirmed: nothing was erased' || fail "a wrong phrase was not refused: $(texts)"
for i in $(seq 1 20); do dump >/dev/null; has 'Erase .*install Ventoy' && break; up; sleep 1; done
tap '^Erase .*install Ventoy$' || fail "cannot reopen the gate"; sleep 2
field; sleep 1; adb shell input text "$(printf '%s' "$PHRASE" | sed 's/ /%s/g')"; sleep 1
tap '^Erase$' || fail "no Erase button in the gate (2)"; sleep 8
shot 12-gate-refused
dump >/dev/null; has 'Nothing was erased: no USB mass-storage drive' || fail "the right phrase on a non-USB drive was not refused before any write: $(texts)"
GOT=$(adb shell "sha256sum '$ROOTV/ISO/ventoy-test.iso'" | tr -d '\r' | awk '{print $1}')
[ "$GOT" = "$SHA" ] || fail "the refused erase touched the drive: $ROOTV/ISO/ventoy-test.iso is now '$GOT'"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Store crashed"
adb shell sm set-virtual-disk false || true
echo "Store Ventoy: tab after Tiny-ML, virtual drive picked and granted, four tables as Apps-style rows, test image installed ($SHA) on $ROOTV/ISO, erase gate: wrong phrase refused, right phrase refused before any write on a non-USB drive."
