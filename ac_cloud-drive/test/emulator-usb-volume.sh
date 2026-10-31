#!/bin/bash
# Emulator test (#1375): a removable volume in Cloud Drive. An emulator cannot attach real USB, so the removable drive is
# vold's virtual disk (sm set-virtual-disk true, partitioned public): a real StorageVolume, mounted by Android with
# the media broadcasts a USB-C drive sends.
#   1. the chooser's query: `cmd package query-activities -a USB_DEVICE_ATTACHED` resolves Cloud Drive's UsbDriveActivity;
#   2. Explorer > Local > Go to lists the volume as a place with its size line ("... free of ..."), and a tap browses it;
#   3. the attach hand-off: MainActivity with the open-path extra opens Explorer straight on the volume;
#   4. UsbDriveActivity with no USB device says so (no crash), proving the activity the chooser starts;
#   2b. each drive is an Explorer tab of its own; 2c. Git: Cloud-Store / Emulated / External Drive find their git folder (#1376);
#   5. detach: `sm unmount` -> MEDIA_EJECT/UNMOUNTED -> Explorer > Cloud shows "Safe to remove the drive".
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-usb-volume.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive USB volume::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
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

# ── the removable volume ──
adb shell sm set-virtual-disk true || fail "sm set-virtual-disk refused"
DISK=""
for i in $(seq 1 30); do DISK=$(adb shell sm list-disks | tr -d '\r' | grep -m1 'disk:'); [ -n "$DISK" ] && break; sleep 2; done
[ -n "$DISK" ] || fail "no virtual disk appeared"
adb shell sm partition "$DISK" public || fail "sm partition $DISK public failed"
VOL=""; UUID=""
for i in $(seq 1 45); do
  line=$(adb shell sm list-volumes public | tr -d '\r' | grep -m1 ' mounted ')
  if [ -n "$line" ]; then VOL=$(echo "$line" | awk '{print $1}'); UUID=$(echo "$line" | awk '{print $3}'); break; fi
  sleep 2
done
[ -n "$UUID" ] && [ "$UUID" != "null" ] || fail "the virtual volume never mounted: $(adb shell sm list-volumes all | tr -d '\r' | tr '\n' ';')"
ROOTV=/storage/$UUID
echo "removable volume: $VOL at $ROOTV"
adb shell sm list-volumes all > "$OUT/volumes.txt" 2>&1
adb shell "mkdir -p '$ROOTV/UsbFixture' '$ROOTV/GIT/usb-demo/.git' && echo usb > '$ROOTV/usb-fixture.txt'" || echo "note: the shell could not write a fixture on $ROOTV (the listing is still asserted by path)"
# #1376 a git folder on internal storage too (lower-case "Git": the match is case-insensitive)
adb shell "mkdir -p /sdcard/Git/emu-demo/.git" || fail "could not write the internal git fixture"

adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true

# 1. the chooser's own query
adb shell cmd package query-activities --brief -a android.hardware.usb.action.USB_DEVICE_ATTACHED | tr -d '\r' > "$OUT/usb-attached-activities.txt"
grep -q "$PKG/.usb.UsbDriveActivity\|$PKG/com.diegonmarcos.clouddrive.usb.UsbDriveActivity" "$OUT/usb-attached-activities.txt" || fail "USB_DEVICE_ATTACHED does not resolve Cloud Drive: $(cat "$OUT/usb-attached-activities.txt" | tr '\n' ';')"
echo "USB_DEVICE_ATTACHED resolves: $(grep "$PKG" "$OUT/usb-attached-activities.txt")"

# 2. Explorer > Local > Go to lists the volume with its size
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 2
for i in 1 2 3; do tap '^Explorer$' && break; sleep 2; done || fail "no Explorer tab"
sleep 2; tap '^Local$' || true; sleep 3
shot 01-explorer
tap '^Go to$' || fail "no Go to (Places) action: $(texts)"
sleep 3; shot 02-places
has 'free of' || fail "no removable place with a size line in Places: $(texts)"
tap 'free of' || fail "the volume place is not tappable"
sleep 3; shot 03-volume-listing
has "$ROOTV" || has "$UUID" || fail "tapping the volume place did not open $ROOTV: $(texts)"

# 2b. #1376 the drive has its own Explorer tab, named by its volume (any tab beyond Local and Cloud)
sleep 1
dump >/dev/null; shot 03b-drive-tab
DRIVETAB=$(python3 -I -c '
import re,sys
x=open(sys.argv[1]).read()
t=[m for m in re.findall(r"text=\"([^\"]+)\"",x)]
i=[k for k,v in enumerate(t) if v=="Cloud"]
print(t[i[0]+1] if i and i[0]+1<len(t) else "")' "$OUT/ui.xml")
echo "drive tab: $DRIVETAB"
[ -n "$DRIVETAB" ] && [ "$DRIVETAB" != "Local" ] || fail "no drive tab after Cloud on the Explorer strip: $(texts)"
tap "^$DRIVETAB\$" || fail "the drive tab is not tappable"; sleep 3; shot 03c-drive-tab-open
has "$ROOTV" || has "$UUID" || fail "the drive tab did not open $ROOTV: $(texts)"

# 2c. #1376 Git: the storage selector, a repo found in each storage's git folder
for i in 1 2 3; do tap '^Git$' && break; sleep 2; done || fail "no Git tab"
sleep 3; shot 07a-git-store
for c in 'Cloud-Store (Termux)' 'Emulated' 'External Drive'; do has "$c" || fail "Git storage chip '$c' missing: $(texts)"; done
tap '^Emulated$'; sleep 3; shot 07b-git-emulated
has 'emu-demo' || fail "Emulated did not list the repo in /sdcard/Git: $(texts)"
tap '^External Drive$'; sleep 3; shot 07c-git-external
has 'usb-demo' || fail "External Drive did not list the repo in $ROOTV/GIT: $(texts)"

# 3. the attach hand-off opens Explorer straight on the drive
adb shell input keyevent 3; sleep 1
timeout 60 adb shell am start -W -n "$PKG/.MainActivity" --es com.diegonmarcos.clouddrive.extra.OPEN_PATH "$ROOTV" >/dev/null; sleep 5
shot 04-opened-on-drive
has "$ROOTV" || has "$UUID" || fail "Explorer did not open on $ROOTV: $(texts)"

# 4. the chooser's target: no USB device here, so it must say so (and not crash)
timeout 60 adb shell am start -W -n "$PKG/.usb.UsbDriveActivity" -a android.hardware.usb.action.USB_DEVICE_ATTACHED >/dev/null; sleep 4
shot 05-usb-activity
has 'No USB drive is plugged in' || fail "UsbDriveActivity did not report the missing USB device: $(texts)"
tap '^Close$' || adb shell input keyevent 4; sleep 2

# 5. detach: Android unmounts, the broadcasts reach the receiver, the Cloud page says it is safe to remove
adb shell sm unmount "$VOL" || fail "sm unmount $VOL failed"
sleep 4
timeout 60 adb shell am start -W -n "$act" >/dev/null; sleep 3
for i in 1 2 3; do tap '^Explorer$' && break; sleep 2; done
sleep 1; tap '^Cloud$' || fail "no Cloud page"; sleep 3
shot 06-safe-to-remove
has 'Safe to remove the drive' || fail "no Safe to remove state after unmount: $(texts)"
[ -n "$DRIVETAB" ] && has "text=\"$DRIVETAB\"" && fail "the drive tab '$DRIVETAB' stayed after detach"
for i in 1 2 3; do tap '^Git$' && break; sleep 2; done; sleep 3; shot 08-git-no-drive
has 'No drive attached' || fail "External Drive shows no empty state after detach: $(texts)"
tap '^Cloud-Store \(Termux\)$' || true; sleep 1

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
adb shell sm set-virtual-disk false || true
echo "Drive USB volume: chooser resolves, place with size, browse, open-on-attach, no-device state, safe to remove."
