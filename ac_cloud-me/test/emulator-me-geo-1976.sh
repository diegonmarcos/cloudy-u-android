#!/usr/bin/env bash
# emulator-me-geo-1976 — #1976 on a booted emulator, from the Ship APK: Pers is two rows (Profiles | Media Health Astro DD over Achieves | Geo ... By 50), Media shows its three tabs
# (ONE flat strip Personal | ig0 | ig1 | ig2 | face0 | Pinterest | ...), Geo shows Long Stay, the Short Stay Places table (Continent / Region, Cities, NM, Countries, Days), the visited-countries map with a continent
# switcher (no world button) and MyTrips; each is screenshotted (pers-1976-nav.png, pers-1976-media.png, geo-1976-*.png).
# Usage: emulator-me-geo-1976.sh ME_APK OUT_DIR. Exit 1 on any failed assertion.
set -uo pipefail
ME_APK="$1"; OUT="$2"; mkdir -p "$OUT"
T="timeout 120"; ME=com.diegonmarcos.cloudme; rc=0
$T adb wait-for-device
$T adb install -r -g "$ME_APK" || { echo "install failed"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
$T adb shell cmd uimode night yes
$T adb shell wm size 1080x2400; $T adb shell wm density 420
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; $T adb shell am start -W -n "$(launcher "$ME")" -f 0x14000000 --es shortcut_action "$1" >/dev/null; }
dump() { rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; dump "$1"; echo "shot $1"; }
tap_text() {  # TEXT SHOT: tap the centre of the first node whose text or content-desc is TEXT in the dump of SHOT
    local xy; xy=$(python3 -I - "$OUT/$2.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: sys.exit(1)
for n in nodes:
    if (n.get("text") or n.get("content-desc") or "") == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m: a = list(map(int, m.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); sys.exit(0)
sys.exit(1)
PY
    ) || { echo "FAIL no node '$1' in $2.xml" | tee -a "$OUT/verdict.txt"; return 1; }
    $T adb shell input tap $xy; echo "tapped $1 at $xy"
}
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
has() { grep -q "text=\"$1\"\|content-desc=\"$1\"" "$OUT/$2.xml"; }
hasp() { grep -q "text=\"$1[ \"]\|content-desc=\"$1[ \"]" "$OUT/$2.xml"; }   # a group label draws "Profiles ▾"
want() { if has "$1" "$2"; then echo "PASS $3: '$1' on screen" | tee -a "$OUT/verdict.txt"; else echo "FAIL $3: '$1' missing in $2.xml" | tee -a "$OUT/verdict.txt"; rc=1; fi; }
wantp() { if hasp "$1" "$2"; then echo "PASS $3: '$1' on screen" | tee -a "$OUT/verdict.txt"; else echo "FAIL $3: '$1' missing in $2.xml" | tee -a "$OUT/verdict.txt"; rc=1; fi; }
unwant() { if has "$1" "$2"; then echo "FAIL $3: '$1' must not be on screen" | tee -a "$OUT/verdict.txt"; rc=1; else echo "PASS $3: no '$1'" | tee -a "$OUT/verdict.txt"; fi; }
swipe_up() { $T adb shell input swipe 70 1750 70 750 600; sleep 2; }   # x=70: clear of the system back-gesture edge
open_page "page:configs/general"; sleep 12
open_page "page:pers/pers-profiles"; sleep 12
shot pers-1976-nav 6
wantp Profiles pers-1976-nav "nav row 1 label"; wantp Achieves pers-1976-nav "nav row 2 label"
for t in Media Health Astro DD Geo Endurance "Personal Growth"; do want "$t" pers-1976-nav "nav"; done
unwant "Personal Archives" pers-1976-nav "nav"
python3 -I - "$OUT/pers-1976-nav.xml" <<'PY' | tee -a "$OUT/verdict.txt" || rc=1
import re, sys, xml.etree.ElementTree as ET
pos = {}
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    t = re.sub(r"^[^A-Za-z0-9]+", "", (n.get("text") or n.get("content-desc") or "").strip()); m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
    if t in ("Media", "Health", "Astro", "DD", "Geo", "Endurance") and m and 150 < int(m.group(2)) < 700: pos.setdefault(t, (int(m.group(2)), int(m.group(1))))
row1 = [pos.get(k) for k in ("Media", "Health", "Astro", "DD")]; row2 = [pos.get(k) for k in ("Geo", "Endurance")]
ok = all(row1) and all(row2) and max(r[0] for r in row1) < min(r[0] for r in row2) and row1[0][1] < row1[1][1] < row1[2][1]
print("%s two rows: Media Health Astro DD above Geo Endurance (y,x: %s %s)" % ("PASS" if ok else "FAIL", row1, row2)); sys.exit(0 if ok else 1)
PY
for t in Personal ig0 ig1 ig2 face0 Pinterest; do want "$t" pers-1976-nav "media tab (one flat strip)"; done; unwant Instagram pers-1976-nav "media: no Instagram grouping"
cp "$OUT/pers-1976-nav.png" "$OUT/pers-1976-media.png"
$T adb shell input swipe 950 380 150 380 500; sleep 2; shot pers-1976-nav-row2-scrolled 3
for t in "By 30" "By 40" "By 50"; do want "$t" pers-1976-nav-row2-scrolled "nav row 2 end"; done
open_page "page:pers/pers-social"; sleep 10; shot pers-1976-social 6
for t in Pinterest YouTube Tidal LinkedIn Orkut face0; do want "$t" pers-1976-social "social"; done
open_page "page:pers/pers-profiles"; sleep 10; shot pers-1976-legacy-profiles 4; want "Personal" pers-1976-legacy-profiles "legacy id pers/pers-profiles resolves to Media"
open_page "page:pers/geo"; sleep 14; shot geo-1976-long 6
for t in "B3) International Experience (Dec 2020 – Present)" "Long Stay" "Cities and the time spent in each"; do want "$t" geo-1976-long "long stay"; done
unwant "Places" geo-1976-long "geo: the Places ledger view is gone"
unwant "Languages" geo-1976-long "geo: Languages is gone"
long=0; short=0; mapd=0; trips=0
for i in 1 2 3 4 5 6 7 8 9 10; do
    swipe_up; shot "geo-1976-scroll-$i" 3
    x="$OUT/geo-1976-scroll-$i.xml"
    if [ $long = 0 ] && has "São Paulo" "geo-1976-scroll-$i" && has "Time" "geo-1976-scroll-$i"; then long=1; cp "$OUT/geo-1976-scroll-$i.png" "$OUT/geo-1976-long-cities.png"; want "Berlin" "geo-1976-scroll-$i" "long stay city"; fi
    if [ $short = 0 ] && has "Continent / Region" "geo-1976-scroll-$i"; then short=1; cp "$OUT/geo-1976-scroll-$i.png" "$OUT/geo-1976-short-table.png"
        for t in "Continent / Region" "Cities" "NM" "Countries" "Days"; do want "$t" "geo-1976-scroll-$i" "places column"; done; fi
    if [ $mapd = 0 ] && has "Europe" "geo-1976-scroll-$i" && has "Oceania" "geo-1976-scroll-$i"; then mapd=1; cp "$OUT/geo-1976-scroll-$i.png" "$OUT/geo-1976-map-europe.png"
        for t in Europe Asia Africa "North America" "South America" Oceania; do want "$t" "geo-1976-scroll-$i" "map continent switcher"; done
        unwant "World" "geo-1976-scroll-$i" "map: no global view"; unwant "Global" "geo-1976-scroll-$i" "map: no global view"
        tap_text "Asia" "geo-1976-scroll-$i" && shot geo-1976-map-asia 4 || rc=1
        dump geo-1976-map-asia; tap_text "South America" geo-1976-map-asia && shot geo-1976-map-south-america 4 || rc=1
        dump geo-1976-map-south-america; tap_text "Europe" geo-1976-map-south-america && sleep 2 || rc=1; fi
    if [ $trips = 0 ] && has "Collections" "geo-1976-scroll-$i" && has "Timeline" "geo-1976-scroll-$i"; then trips=1; cp "$OUT/geo-1976-scroll-$i.png" "$OUT/geo-1976-mytrips.png"
        for t in Home Atlas Collections Stats Timeline; do want "$t" "geo-1976-scroll-$i" "mytrips tab"; done
        tap_text "Stats" "geo-1976-scroll-$i" && shot geo-1976-mytrips-stats 5 || rc=1
        dump geo-1976-mytrips-stats; tap_text "Timeline" geo-1976-mytrips-stats && shot geo-1976-mytrips-timeline 5 || rc=1
        dump geo-1976-mytrips-timeline; tap_text "Atlas" geo-1976-mytrips-timeline && shot geo-1976-mytrips-atlas 5 || rc=1
        break; fi
done
[ $long = 1 ] || { echo "FAIL long stay cities never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
[ $short = 1 ] || { echo "FAIL Places columns never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
[ $mapd = 1 ] || { echo "FAIL the map switcher never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
[ $trips = 1 ] || { echo "FAIL MyTrips tabs never on screen" | tee -a "$OUT/verdict.txt"; rc=1; }
$T adb logcat -d > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && { echo "FAIL crash in logcat" | tee -a "$OUT/verdict.txt"; rc=1; }
echo "rc=$rc" | tee -a "$OUT/verdict.txt"
exit $rc
