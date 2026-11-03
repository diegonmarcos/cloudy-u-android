#!/bin/bash
# Emulator flow (#1390), run by the nav walk after its page walk (test-nav-runtime.yml, apps=ac_cloud-me): the Health redesign.
#   0. the global cloud_nav_ui_mode is CLEARED (the page walk pins it to classic) and the phone is in dark mode;
#   1. section:health opens on Tracked (the goal rings, "Your metrics", Browse): the default page;
#   2. every Health page, deep-linked (page:health/<id>), is photographed at the top and on each scrolled frame; a page that
#      crashes, says "Unknown Health page", or lacks its own markers fails the flow;
#   3. Tracked's detail: Steps on Day / Week / Month / Year, Heart rate (a line) on Year, Sleep on Month.
# Usage: emulator-walk-health.sh <cloud-me.apk> <outdir>. Screens: <outdir>/shots/<nn>-<page>-<frame>.png; a summary in <outdir>/health.md.
set -u
APK=${1:?cloud-me apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudme
mkdir -p "$OUT/shots"
FAILS=0
note() { echo "$*" | tee -a "$OUT/health.md"; }
bad() { echo "::error title=Cloud Me Health::$*"; note "- FAIL: $*"; FAILS=$((FAILS + 1)); }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml 2>/dev/null; }
texts() { grep -oE '(text|content-desc)="[^"]+"' | sed -E 's/^(text|content-desc)="//;s/"$//'; }
# centre of the first node whose text matches the regex ($1)
where() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1])
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and t and pat.search(t.group(1)):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1"; }
tap() { where "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
front() { adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PKG"; }
# a permission sheet (Health Connect's own, or the start-up walk) is answered "no" so the page underneath is what is shot
unblock() { for _ in 1 2 3; do front && return 0; tap "^(Cancel|Don.t allow|Deny|Not now|No thanks)$" || adb shell input keyevent 4; sleep 2; done; front; }
act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
go() { timeout 60 adb shell am start -W -n "$act" --es shortcut_action "$1" >/dev/null 2>&1; sleep 4; unblock; sleep 1; }
crashes() { adb logcat -d -b crash 2>/dev/null | grep -c "Process: $PKG" || true; }
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
up() { adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 3)) 450; sleep 2; }
# shoot <nn-name> <markers regex|-> : top + every scrolled frame until the screen stops changing (at most 6)
shoot() {
  local name=$1 want=$2 all="" prev="" cur i
  for i in 0 1 2 3 4 5; do
    cur=$(dump); adb exec-out screencap -p > "$OUT/shots/$name-$i.png"; echo "$cur" > "$OUT/shots/$name-$i.xml"
    [ "$cur" = "$prev" ] && { rm -f "$OUT/shots/$name-$i.png" "$OUT/shots/$name-$i.xml"; break; }
    all="$all
$(echo "$cur" | texts)"; prev=$cur
    up
  done
  front || bad "$name: the app left the foreground (crash?)"
  echo "$all" | grep -q "Unknown Health page" && bad "$name: Unknown Health page"
  echo "$all" | grep -q "content files are missing" && bad "$name: Health content missing"
  if [ "$want" != "-" ]; then echo "$all" | grep -Eq "$want" || bad "$name: none of /$want/ on screen"; fi
  local mock; echo "$all" | grep -q "^mock data$" && mock=" (mock data badge shown)" || mock=""
  note "- $name: $(ls "$OUT/shots/$name"-*.png 2>/dev/null | wc -l) frame(s)$mock"
}

note "## Cloud Me Health walk (#1390)"
adb shell settings delete global cloud_nav_ui_mode >/dev/null 2>&1 || true
m=$(adb shell settings get global cloud_nav_ui_mode | tr -d '\r'); [ "$m" = "null" ] || bad "cloud_nav_ui_mode is still '$m'"
adb shell cmd uimode night yes >/dev/null 2>&1
note "- cloud_nav_ui_mode: $m; night mode: $(adb shell cmd uimode night | tr -d '\r')"
adb shell am force-stop "$PKG"; sleep 1
c0=$(crashes)
go "section:health"
d=$(dump | texts)
echo "$d" | grep -q "^Today$" || bad "Health did not open on Tracked (no 'Today' title): $(echo "$d" | head -30 | tr '\n' ';')"
echo "$d" | grep -Eq "^STEPS$" || bad "the Tracked rings show no Steps"
shoot 00-default "^YOUR METRICS$"
i=1
for spec in \
  "tracked|^YOUR METRICS$|^BROWSE$" "activities|^Journal, last 30 days$" "bodycomp|^Body fat$" \
  "hr|^Heart-rate zones, last 7 days$" "sleep|^Hours asleep per night$" \
  "workout|^THIS WEEK$" "home|^TRACKERS$" \
  "bio|-" "circadian|-" "biomarkers|-" "timeline|-" "objectives|-" "matrix|-" "culinary|-" "clash|-" "thermal|-" "hemo|-" "shop|-" "dossier|-"; do
  id=${spec%%|*}; want=${spec#*|}
  go "page:health/$id"
  shoot "$(printf %02d $i)-$id" "$want"; i=$((i + 1))
done
detail() { # <metric label> <ranges...>
  local label=$1; shift
  go "page:health/tracked"
  for _ in 1 2 3 4; do tap "^$label\$" && break; up; done
  sleep 3
  dump | texts | grep -q "^< Tracked$" || { bad "the $label card opened no detail"; return; }
  for r in "$@"; do
    tap "^$r\$" || { bad "$label detail has no $r toggle"; continue; }
    sleep 3; shoot "$(printf %02d $i)-detail-$(echo "$label" | tr 'A-Z ' 'a-z_')-$r" "^MIN$"; i=$((i + 1))
    for _ in 1 2 3 4 5 6; do adb shell input swipe $((SW / 2)) $((SH / 3)) $((SW / 2)) $((SH * 3 / 4)) 300; done; sleep 2
  done
}
detail Steps Day Week Month Year
detail "Heart rate" Year
detail Sleep Month
c1=$(crashes); [ "$c1" -gt "$c0" ] && bad "Cloud Me crashed $((c1 - c0)) time(s) during the Health walk"
adb shell cmd uimode night no >/dev/null 2>&1 || true
note ""; [ "$FAILS" -eq 0 ] && { note "OK: every Health page drawn in dark mode, Health opens on Tracked"; exit 0; } || { note "FAILED: $FAILS"; exit 1; }
