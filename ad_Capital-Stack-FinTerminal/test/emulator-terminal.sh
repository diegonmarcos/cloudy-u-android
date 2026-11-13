#!/usr/bin/env bash
# emulator-terminal.sh <apk> <out-dir> (#1722) — the CI emulator walk of the terminal-style FinTerm: the section Homepages (CB, FI, EQ), the FI bond
# groups in Table and Graphic mode, the rates / macro pages at their new place under CB, a few EQ > Performance factor portfolios in both modes, the EQ
# Data pages, the function list, and a mnemonic typed into the command line. Each step is a screenshot (plus one or two scrolled) and a uiautomator dump.
# Runs inside reactivecircus/android-emulator-runner (CI only). The emulator has no Cloud Store and no mesh: the dataset and the quotes arrive on their own.
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.capitalfinterm
WAIT="${FT_WAIT:-25}"
adb install -r -g "$APK" || exit 1
adb shell settings delete global cloud_nav_ui_mode || true
# the launcher of an emulator can stop responding under load: its dialog must not cover the app under test
adb shell settings put global hide_error_dialogs 1 || true
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
W=${W:-1080}; H=${H:-2400}
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 || true; }
undialog() { # a system "not responding" dialog over the app: press Wait
  adb shell uiautomator dump /sdcard/dlg.xml >/dev/null 2>&1 && adb pull /sdcard/dlg.xml "$OUT/.dlg.xml" >/dev/null 2>&1 || return 0
  grep -q "t responding" "$OUT/.dlg.xml" 2>/dev/null || return 0
  DXY=$(node_xy "$OUT/.dlg.xml" "Wait"); [ -n "$DXY" ] && { adb shell input tap $DXY; sleep 2; }
  return 0
}
shot() { undialog; adb exec-out screencap -p > "$OUT/$1.png"; }
down() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H * 3 / 10)) 500; sleep 3; }
# node_xy <xml> <text prefix> -> "x y" of the first node whose text starts with the key
node_xy() {
  python3 -I - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read(); k = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1).startswith(k):
        a = [int(v) for v in b.groups()]
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
}
# open <name> <dashboard / page / section id> [mode] [scrolls]: the page as it draws after it settled, then scrolled
open() {
  if [ -n "${3:-}" ]; then adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$2" --es mode "$3" >/dev/null
  else adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard "$2" >/dev/null; fi
  sleep "$WAIT"
  shot "$1-1"; dump "$OUT/ui-$1.xml"
  i=2
  while [ "$i" -le "${4:-2}" ]; do down; shot "$1-$i"; i=$((i + 1)); done
  echo "$1 texts=$(grep -o ' text="[^"]\+"' "$OUT/ui-$1.xml" 2>/dev/null | wc -l)" | tee -a "$OUT/walk.txt"
}
# the first open also fetches the dataset: give it one warm-up
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard fi >/dev/null; sleep "$WAIT"
open home-fi fi "" 3
open home-eq eq "" 4
open home-cb cb "" 2
for g in fi-all fi-gov-dm fi-gov-em fi-aaa fi-aa-a fi-bbb fi-junk; do
  open "$g-table" "$g" table 2
  open "$g-graphic" "$g" graphic 3
done
for d in yield_curves yields_matrix forward_rates rate_probabilities world_bonds bonds_market macro credit; do open "cb-$d" "$d" "" 2; done
for g in eq-top eq-momentum eq-growth eq-dividend eq-small; do
  open "$g-table" "$g" table 2
  open "$g-graphic" "$g" graphic 3
done
for g in eq-value eq-quality eq-vol eq-cyclical eq-defensive; do open "$g-table" "$g" table 1; done
for d in world_equity_indices commodities commodity-prices fx-rates equity_fundamentals earnings_calendar equity_quote watchlist; do open "eq-data-$d" "$d" "" 1; done
for s in nw pm hg my-pm; do open "home-$s" "$s" "" 1; done
open pm-performance pm-performance "" 1
open hg-rates hg-rates "" 1
# the function list (96) and a mnemonic typed into the command line
adb shell am start -S -W -n "$PKG/.MainActivity" --es dashboard eq >/dev/null; sleep 12
dump "$OUT/ui-cmd.xml"
XY=$(node_xy "$OUT/ui-cmd.xml" "Functions"); echo "functions xy=${XY:-none}" | tee -a "$OUT/walk.txt"
if [ -n "$XY" ]; then set -- $XY; adb shell input tap "$1" "$2"; sleep 4; shot functions; dump "$OUT/ui-functions.xml"; adb shell input keyevent 4; sleep 2; fi
XY=$(node_xy "$OUT/ui-cmd.xml" "<GO>"); echo "go xy=${XY:-none}" | tee -a "$OUT/walk.txt"
if [ -n "$XY" ]; then
  set -- $XY
  # the amber field sits left of GO
  adb shell input tap $(( $1 - W / 6 )) "$2"; sleep 2
  adb shell input text GDM; sleep 1
  adb shell input tap "$1" "$2"; sleep "$WAIT"
  adb shell input keyevent 4; sleep 1
  shot command-gdm; dump "$OUT/ui-command-gdm.xml"
  grep -c 'Gov Bonds DM' "$OUT/ui-command-gdm.xml" > "$OUT/command-gdm.txt" 2>/dev/null || true
fi
adb logcat -d -t 5000 > "$OUT/logcat.txt" 2>/dev/null || true
grep -c "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
grep -A12 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crash-traces.txt" 2>/dev/null || true
echo "crashes=$(cat "$OUT/crashes.txt")" | tee -a "$OUT/walk.txt"
ls -la "$OUT" | head -200
