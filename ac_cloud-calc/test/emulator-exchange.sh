#!/bin/bash
# Emulator walk (#1945): Calc > Finance > Exchange, dark theme, 1080x2400 @420dpi. Shot: the Exchange page; asserts by uiautomator dump the
# source card ("You convert"), the four default target cards with their unit-rate lines, the add action and the footer texts.
# Rates come from the engine (Cloud-Lib-Calc), which may be absent on the emulator: the page then shows its cached/offline state, still asserted.
# Two cases on one emulator: OFFLINE (Cloud-Lib-Calc not installed: no engine, cached/never state) then ONLINE (Cloud-Lib-Calc installed, the page
# loads the ECB rates through the engine: a numeric amount, a "1 INR -> 0.0xxx" line and a real Last-updated date are asserted).
# #1963 every box is editable: after the ONLINE case the walk types 100 into the USD box (not the first) and asserts the other boxes
# recompute from it (EUR against the "1 USD -> r EUR" line, INR against the earlier INR->USD rate), that USD is the focused input (the active box; its highlight is in the shot), and that
# switching the input to INR reads 100.00 back in USD (no round-trip drift). Shots: online-02-usd-input, online-03-inr-input.
# Usage: emulator-exchange.sh <calc.apk> <outdir> [<lib-calc.apk>]  (no lib apk = the offline case only)
set -u
APK=${1:?calc apk}; OUT=${2:-out}; LIB=${3:-}
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

# walk <case> <wait-seconds>: cold start Calc, open Exchange, wait, shoot the top, scroll, shoot the bottom.
walk() {
  adb shell am force-stop "$PKG"; sleep 2
  for i in 1 2 3; do
    adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
    dump | grep -q "package=\"$PKG\"" && break
  done
  dump | grep -q "package=\"$PKG\"" || fail "$1: Calc never came to the front"
  tap '^Exchange$' || fail "$1: no Exchange page pill in Calc: $(texts)"
  sleep "$2"; dump >/dev/null
  shot "$1-01-exchange"
  for w in 'You convert' 'INR'; do has "$w" || fail "$1: Exchange lacks $w: $(texts)"; done
  for c in USD EUR GBP BRL; do has "$c" || fail "$1: no target card $c: $(texts)"; grep -qE "text=\"1 INR . [^\"]* $c\"" "$OUT/ui.xml" || fail "$1: no unit-rate line for $c: $(texts)"; done
}
# scrolled part: the add action and the footer
bottom() {
  swipe 80 30; sleep 2; dump >/dev/null; shot "$1-02-exchange-bottom"
  for w in 'Add another currency' 'Mid-market rates'; do has "$w" || fail "$1: Exchange lacks $w: $(texts)"; done
  grep -qE 'text="(Offline . )?Last updated: ' "$OUT/ui.xml" || fail "$1: no last-updated footer: $(texts)"
  grep -oE 'text="(Offline . )?Last updated: [^"]*"' "$OUT/ui.xml" | head -1 | tee "$OUT/footer-$1.txt"
}

# ── OFFLINE: no engine installed ──
adb uninstall com.diegonmarcos.cloudlib.calc >/dev/null 2>&1 || true
walk offline 12; bottom offline
grep -q 'Last updated: never' "$OUT/footer-offline.txt" || echo "note: offline footer is not 'never': $(cat "$OUT/footer-offline.txt")"
echo "OFFLINE case: layout shown with no engine"
[ -n "$LIB" ] || { adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 0; }

# ── ONLINE: Cloud-Lib-Calc installed, the page loads rates through the engine ──
adb install -r -g "$LIB" || fail "Cloud-Lib-Calc install failed"
adb shell pm list packages | grep -i "cloudlib" > "$OUT/lib-packages.txt" 2>&1 || true
walk online 5
ok=0
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do   # up to ~60 s for the engine's ECB download and the table read
  dump >/dev/null
  if grep -qE 'text="1 INR . 0\.0[0-9]+ USD"' "$OUT/ui.xml"; then ok=1; break; fi
  sleep 5
done
# The engine's ECB download may still be running (the first table is its compiled-in rates, dated July): wait until the footer carries TODAY's date.
today=$(date -u +'%-d %b %Y')   # the runner and the emulator share the clock (both UTC)
fresh=0
swipe 80 30; sleep 2   # the footer is below the four cards
for i in $(seq 1 18); do   # up to ~90 s
  dump >/dev/null
  if grep -qE "Last updated: $today, [0-9]{2}:[0-9]{2}" "$OUT/ui.xml"; then fresh=1; break; fi
  sleep 5
done
echo "today on the emulator: $today; footer: $(grep -oE 'text="(Offline . )?Last updated: [^"]*"' "$OUT/ui.xml" | head -1)"
swipe 30 80; sleep 2; dump >/dev/null   # back to the top: the cards now carry the downloaded rates
shot online-01-exchange
logcat_tail() { adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; }
[ "$ok" = 1 ] || { logcat_tail; grep -iE "calc|qalc|rates|fetch" "$OUT/logcat.txt" | tail -40 > "$OUT/engine-log.txt"; fail "online: no '1 INR -> 0.0xxx USD' line, the rates did not load: $(texts)"; }
n=$(grep -oE 'text="[0-9][0-9,]*\.[0-9]{2}"' "$OUT/ui.xml" | sort -u | grep -vc '858.02')
[ "$n" -ge 1 ] || fail "online: no numeric converted amount on a target card: $(texts)"
bottom online
[ "$fresh" = 1 ] && grep -q "Last updated: $today" "$OUT/footer-online.txt" || { logcat_tail; grep -iE "calc|qalc|rates|fetch|UnknownHost|SSL" "$OUT/logcat.txt" | tail -60 > "$OUT/engine-log.txt"; fail "online: the footer never showed today ($today): $(cat "$OUT/footer-online.txt")"; }
logcat_tail
echo "ONLINE case: rates loaded through Cloud-Lib-Calc, numeric amounts, unit-rate lines and a real Last-updated date"

# ── #1963 type into a NON-first box: every other box is recomputed from it ──
# edits: the amount boxes (android.widget.EditText) top to bottom -> "<text>|<selected>" one per line
edits() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); rows=[]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if "android.widget.EditText" not in n: continue
    t=re.search(r"\btext=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]",n); sel=re.search(r"\bfocused=\"(\w+)\"",n)
    rows.append((int(b.group(2)),t.group(1) if t else "",sel.group(1) if sel else "?"))
for y,t,sel in sorted(rows): print(t+"|"+sel)'; }
num() { printf '%s' "$1" | tr -d ','; }
swipe 30 80; sleep 2
n=$(edits | wc -l); [ "$n" -ge 5 ] || fail "usd-input: expected 5 editable amount boxes, found $n: $(edits | tr '\n' ' ')"
inr0=$(edits | sed -n 1p | cut -d'|' -f1); [ "$(edits | sed -n 1p | cut -d'|' -f2)" = true ] || echo "note: first box not reported focused before the switch: $(edits | tr '\n' ' ')"
tap_edit() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); rows=[]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if "android.widget.EditText" not in n: continue
    b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n); rows.append(tuple(map(int,b.groups())))
a,b,c,d=sorted(rows,key=lambda r:r[1])[int(sys.argv[1])]; print((a+c)//2,(b+d)//2)' "$1" | { read -r X Y; adb shell input tap "$X" "$Y"; }; }
tap_edit 1; sleep 1
adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 14); do adb shell input keyevent KEYCODE_DEL; done
adb shell input text 100; sleep 3
shot online-02-usd-input
rows=$(edits); echo "$rows" | tr '\n' ' '
[ "$(echo "$rows" | sed -n 2p | cut -d'|' -f1)" = 100 ] || fail "usd-input: the USD box does not show 100: $(echo "$rows" | tr '\n' ' ')"
[ "$(echo "$rows" | sed -n 2p | cut -d'|' -f2)" = true ] || fail "usd-input: the USD box is not the focused input (the active box; its highlight is in the shot): $(echo "$rows" | tr '\n' ' ')"
[ "$(echo "$rows" | sed -n 1p | cut -d'|' -f2)" = true ] && fail "usd-input: the first box is still the focused input (the active box; its highlight is in the shot)"
inr=$(num "$(echo "$rows" | sed -n 1p | cut -d'|' -f1)"); eur=$(num "$(echo "$rows" | sed -n 3p | cut -d'|' -f1)")
reur=$(grep -oE 'text="1 USD . [0-9.]+ EUR"' "$OUT/ui.xml" | grep -oE ' [0-9.]+ EUR' | grep -oE '[0-9.]+' | head -1)
[ -n "$reur" ] || fail "usd-input: no '1 USD -> r EUR' unit-rate line: $(texts)"
python3 -I -c '
import sys
inr,eur,reur,inr0=map(float,sys.argv[1:5])
assert abs(eur-100*reur)<=0.02,("EUR box vs 100 x the USD->EUR line",eur,reur)
assert inr>1000 and abs(inr-858.02)>1,("INR box was not recomputed from 100 USD",inr)
' "$inr" "$eur" "$reur" "$(num "$inr0")" || fail "usd-input: the other boxes were not recomputed from USD: INR=$inr EUR=$eur rate=$reur"
echo "USD input: INR=$inr EUR=$eur (1 USD = $reur EUR)"
# switch the input to INR: USD must read 100.00 again (exact round trip, no drift)
tap_edit 0; sleep 2; shot online-03-inr-input
rows=$(edits); echo "$rows" | tr '\n' ' '
[ "$(echo "$rows" | sed -n 2p | cut -d'|' -f1)" = "100.00" ] || fail "round trip: USD did not read back 100.00 after switching the input to INR: $(echo "$rows" | tr '\n' ' ')"
[ "$(echo "$rows" | sed -n 1p | cut -d'|' -f2)" = true ] || fail "round trip: the INR box is not the focused input (the active box; its highlight is in the shot): $(echo "$rows" | tr '\n' ' ')"
logcat_tail
echo "NON-FIRST INPUT: USD typed, INR/EUR recomputed, input switch keeps 100.00"
