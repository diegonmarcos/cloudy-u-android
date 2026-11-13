#!/bin/bash
# Emulator test (#1714): Drive > Editor > hamburger > file explorer panel.
#   1. the Editor shows the hamburger at the top-left of its content, menu closed (screenshot);
#   2. tapping it opens the panel with the four root chips (Emulated, Termux home, Nix home, External drive);
#   3. each root is photographed: Emulated lists the fixture, the others list or print their reason row;
#   4. tapping the fixture file in Emulated opens it in the Editor (a tab with its name) and the panel closes.
# Usage: emulator-editor-explorer.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT/shots"
fail() { echo "::error title=Drive Explorer search::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
# A fresh dump every time: the old file is removed first, so a failed dump cannot pass a stale screen (as in perms-emulator-proof).
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && break; sleep 2; done; cat "$OUT/ui.xml"; }
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
# A chip past the right edge of the chip row (four chips do not fit 320 px): swipe that row left, or right, until it shows.
reveal() { local xy d; dump >/dev/null; has "$1" && return 0
  for d in left right; do xy=$(where '^(Only Folders Here|Only Folders Root|All From Here|All From Root)$') || return 1; set -- "$1" $xy
    if [ $d = left ]; then adb shell input swipe 280 "$3" 40 "$3" 300; else adb shell input swipe 40 "$3" 280 "$3" 300; fi
    sleep 1; dump >/dev/null; has "$1" && return 0; done; return 1; }
texts() { grep -oE 'text="[^"]+"' "$OUT/ui.xml" | sed 's/text="//;s/"$//' | sort -u | tr '\n' ';'; }


adb install -r -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb shell "mkdir -p /storage/emulated/0/Documents/editor-demo && echo '# editor demo' > /storage/emulated/0/Documents/editor-demo/hello1714.md" || fail "fixture write failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Drive never came to the front"
for i in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 2
for i in 1 2 3; do tap '^Editor$' && break; sleep 2; done || fail "no Editor tab: $(texts)"
sleep 3
shot 01-editor-menu-closed
has 'Open file explorer' || grep -q 'content-desc="Open file explorer"' "$OUT/ui.xml" || fail "no hamburger in the Editor: $(texts)"
tap '^Open file explorer$' || fail "hamburger not tappable"
sleep 3
shot 02-explorer-open-emulated
for r in 'Emulated' 'Termux home' 'Nix home' 'External drive'; do grep -q "text=\"$r\"" "$OUT/ui.xml" || fail "root chip '$r' missing: $(texts)"; done
tap '^Termux home$' || fail "no Termux chip"; sleep 3; shot 03-explorer-termux
tap '^Nix home$' || fail "no Nix chip"; sleep 3; shot 04-explorer-nix
tap '^External drive$' || fail "no External chip"; sleep 3; shot 05-explorer-external
tap '^Emulated$' || fail "no Emulated chip"; sleep 3
# the listing is longer than the panel: swipe it up until the folder shows
for i in 1 2 3 4; do dump >/dev/null; has Documents && break; adb shell input swipe 150 480 150 250 300; sleep 1; done
tap '^Documents$' || fail "no Documents folder: $(texts)"; sleep 2
tap '^editor-demo$' || fail "no fixture folder: $(texts)"; sleep 2
shot 06-explorer-fixture-folder
tap '^hello1714.md$' || fail "no fixture file: $(texts)"; sleep 4
shot 07-file-open-in-editor
has 'Open a file' && fail "the panel did not close after opening a file"
grep -q 'hello1714' "$OUT/ui.xml" || fail "the file did not open in the Editor: $(texts)"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -A5 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "Drive crashed"
echo "Drive > Editor: hamburger, explorer panel, four roots, file opens in the Editor."
