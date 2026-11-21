#!/usr/bin/env bash
# emulator-markz.sh <apk> <out-dir> (#1832) — the CI emulator walk of what #1832 shipped: CB Yield Curves with the five EM panels
# (India, Brazil, Indonesia, South Africa, Mexico) and the MARKZ section (its Sensy Matrix home at the page's defaults, Overview,
# Markovitz). A screenshot and a uiautomator dump per step, scrolled shots of the curves, a verdict of what each dump contains, the
# crash count. Runs inside reactivecircus/android-emulator-runner (CI only); the emulator has no mesh, so curves come from the publishers.
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.capitalfinterm
adb install -r -g "$APK" || exit 1
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
W=${W:-1080}; H=${H:-2400}
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 || true; }
open() { adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$1" >/dev/null; sleep "${FT_WAIT:-30}"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; dump "$OUT/ui-$1.xml"; }
scroll() { adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 4)) 500; sleep 3; }
has() { grep -qF "$2" "$OUT"/ui-$1*.xml 2>/dev/null && echo "$1: '$2' present" || echo "$1: '$2' MISSING"; }
open yield_curves; shot curves-1
for i in 2 3 4 5 6 7 8; do scroll; shot "curves-$i"; done
for c in India Brazil Indonesia "South Africa" Mexico; do has curves "$c"; done | tee -a "$OUT/verdict.txt"
open markz; shot markz-home
for t in "Sensy" "677"; do has markz-home "$t"; done | tee -a "$OUT/verdict.txt"
scroll; shot markz-home-2
open markz-overview; shot markz-overview
open markz-markovitz; shot markz-markovitz
has markz-markovitz "Markovitz" | tee -a "$OUT/verdict.txt"
tapt() { adb shell uiautomator dump /sdcard/t.xml >/dev/null 2>&1; adb pull /sdcard/t.xml "$OUT/t.xml" >/dev/null 2>&1
  local n; n=$(grep -oiE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/t.xml" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tr -c '0-9\n' ' ')
  set -- $n; [ -n "${4:-}" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) && sleep 4; }
open markz-volatility; shot markz-volatility
tapt "EWMA"; shot markz-volatility-fw-ewma
tapt "GARCH\\(1,1\\)"; shot markz-volatility-fw-garch
tapt "Implied"; shot markz-volatility-fw-implied
tapt "Historical SD"
for i in 2 3; do scroll; shot "markz-volatility-$i"; done
for t in "GARCH" "Persistence" "Half-life" "Realised vol" "Bad prints dropped" "MLE" "var-target" "defaults = defaults (short history)"; do has markz-volatility "$t"; done | tee -a "$OUT/verdict.txt"
adb logcat -d -t 5000 > "$OUT/logcat.txt" 2>/dev/null || true
echo "crashes: $(grep -c "FATAL EXCEPTION" "$OUT/logcat.txt")" | tee -a "$OUT/verdict.txt"
ls -la "$OUT"
