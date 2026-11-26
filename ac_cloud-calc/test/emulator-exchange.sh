#!/bin/bash
# Emulator walk (#1945): Calc > Finance > Exchange, dark theme, 1080x2400 @420dpi. Shot: the Exchange page; asserts by uiautomator dump the
# source card ("You convert"), the four default target cards with their unit-rate lines, the add action and the footer texts.
# Rates come from the engine (Cloud-Lib-Calc), which may be absent on the emulator: the page then shows its cached/offline state, still asserted.
# Usage: emulator-exchange.sh <calc.apk> <outdir>
set -u
APK=${1:?calc apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudcalc
mkdir -p "$OUT/shots"
fail() { echo "::error title=Calc Exchange::$*"; adb exec-out screencap -p > "$OUT/shots/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && break; sleep 2; done; cat "$OUT/ui.xml"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; dump > "$OUT/ui-$1.xml"; }
box() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))): print(*b.groups()); break
else: sys.exit(1)' "$1"; }
tap() { box "$1" | { read -r a b c d && adb shell input tap $(( (a + c) / 2 )) $(( (b + d) / 2 )); }; }
texts() { grep -oE '(text|content-desc)="[^"]+"' "$OUT/ui.xml" | sed 's/^[a-z-]*="//;s/"$//' | sort -u | tr '\n' ';'; }
has() { grep -qiE "(text|content-desc)=\"$1\"" "$OUT/ui.xml"; }
swipe() { local W H; read -r W H < <(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //; s/x/ /')
  adb shell input swipe $((W / 2)) $((H * $1 / 100)) $((W / 2)) $((H * $2 / 100)) 400; }

adb shell cmd uimode night yes; adb shell wm size 1080x2400; adb shell wm density 420
adb install -r -g "$APK" || fail "install failed"
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
  dump | grep -q "package=\"$PKG\"" && break
done
dump | grep -q "package=\"$PKG\"" || fail "Calc never came to the front"
tap '^Exchange$' || fail "no Exchange page pill in Calc: $(texts)"
sleep 12; dump >/dev/null
shot 01-exchange
for w in 'You convert' 'INR' 'Add another currency' 'Mid-market rates'; do has "$w" || fail "Exchange lacks $w: $(texts)"; done
for c in USD EUR GBP BRL; do has "$c" || fail "no target card $c: $(texts)"; grep -qE "text=\"1 INR . [^\"]* $c\"" "$OUT/ui.xml" || fail "no unit-rate line for $c: $(texts)"; done
grep -qE 'text="(Offline . )?Last updated: ' "$OUT/ui.xml" || fail "no last-updated footer: $(texts)"
grep -oE 'text="(Offline . )?Last updated: [^"]*"' "$OUT/ui.xml" | head -1 | tee "$OUT/footer.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null
echo "Exchange walk: source card, 4 target cards with unit rates, add action, footer shown"
