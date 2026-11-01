#!/bin/bash
# Emulator test (#1357): Drive > Explorer > Local, inline search.
#   1. the search icon expands the bar over the listing (no new page): the four declared chips show, the
#      listing stays, Only Folders Here is selected;
#   2. typing filters live: Only Folders Here lists the fixture folder here and not the file here;
#   3. All From Here adds files below this folder; Only Folders Root and All From Root reach the fixture
#      placed elsewhere under the storage root;
#   4. back collapses the bar and the same folder is shown.
# Every step leaves a screenshot in <outdir>/shots. Usage: emulator-explorer-search.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Explorer search::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
# centre of the first node whose text or content-desc matches the regex ($1)
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
tap() { where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
has() { grep -q "text=\"$1\"" "$OUT/ui.xml"; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }

adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
# Drive must be in front before any tap: runs 41296027953 dumped the home screen after a launch that never came up.
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
  echo "Drive is not in front after launch $i; relaunching"
done
dump | grep -q "package=\"$PKG\"" || fail "Drive never came to the front"
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 2
for i in 1 2 3; do tap '^Explorer$' && break; sleep 2; done || fail "no Explorer tab"
sleep 2; tap '^Local$' || true; sleep 3

# The folder the explorer opened on is the toolbar's subtitle; the fixture goes there, and a second one elsewhere under the root.
dump >/dev/null
HERE=$(grep -oE 'text="/storage/[^"]*"' "$OUT/ui.xml" | head -1 | sed 's/text="//;s/"$//')
[ -n "$HERE" ] || HERE=/storage/emulated/0
# A bare emulator has no Termux, so the first pane (cloud-shared-store) cannot open; the shared-storage pane
# (emulated › 0) is folded under it: open that one, the fixture goes there (run 41295555027).
if grep -q 'text="cannot open' "$OUT/ui.xml"; then tap '^emulated › 0$' || fail "no shared-storage pane: $(texts)"; sleep 3; HERE=/storage/emulated/0; fi
echo "explorer opened on: $HERE"
ROOTDIR=/storage/emulated/0
adb shell "mkdir -p '$HERE/AXsTrip/xstrip-deep' '$ROOTDIR/Music/xstrip-songs' && echo x > '$HERE/xstrip.txt' && echo x > '$HERE/AXsTrip/xstrip-notes.txt' && echo x > '$ROOTDIR/Music/xstrip.mp3'" || fail "fixture write failed"
tap '^More$' && sleep 1 && tap '^Retry$'; sleep 3
shot 01-listing
has AXsTrip || fail "the fixture folder is not in the listing of $HERE: $(texts)"

# 1. expand: chips show, the listing stays, no page replaces it
tap '^Search$' || fail "no search icon"
sleep 2; shot 02-expanded
for c in 'Only Folders Here' 'Only Folders Root' 'All From Here' 'All From Root'; do has "$c" || fail "chip '$c' missing: $(texts)"; done
has AXsTrip || fail "the listing vanished when the bar expanded"

# 2. type: default scope = folders here, filtered live
FIELD=$(dump | python3 -I -c '
import re,sys
x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if "android.widget.EditText" in n:
        b=list(map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups())); print((b[0]+b[2])//2,(b[1]+b[3])//2); break')
[ -n "$FIELD" ] || fail "no search field"
adb shell input tap $FIELD; sleep 1; adb shell input text xstrip; sleep 3
# hide the keyboard for clean shots: a back press closes the IME only while it is shown
adb shell dumpsys input_method | grep -q 'mInputShown=true' && { adb shell input keyevent 4; sleep 1; }
shot 03-folders-here
has AXsTrip || fail "Only Folders Here lost the matching folder: $(texts)"
has xstrip.txt && fail "Only Folders Here lists a file"
has xstrip-songs && fail "Only Folders Here reached outside this folder"

# 3. the recursive scopes
tap '^All From Here$' || fail "no All From Here chip"; sleep 5; shot 04-all-here
has xstrip-notes.txt || fail "All From Here missed a file below this folder: $(texts)"
tap '^Only Folders Root$' || fail "no Only Folders Root chip"; sleep 6; shot 05-folders-root
has xstrip-songs || fail "Only Folders Root missed the folder elsewhere under the root: $(texts)"
has xstrip.mp3 && fail "Only Folders Root lists a file"
tap '^All From Root$' || fail "no All From Root chip"; sleep 6; shot 06-all-root
has xstrip.mp3 || fail "All From Root missed the file elsewhere under the root: $(texts)"

# 4. back collapses in place
adb shell input keyevent 4; sleep 2; shot 07-collapsed
has 'Only Folders Here' && fail "back did not collapse the bar"
has AXsTrip || fail "after collapsing the explorer is not on the same folder: $(texts)"
has Alarms || fail "after collapsing the listing is still filtered (Alarms does not match the query): $(texts)"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
echo "Drive > Explorer: inline bar, four chips, live filter per scope, back collapses in place."
