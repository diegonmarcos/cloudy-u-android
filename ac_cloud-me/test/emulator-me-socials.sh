#!/usr/bin/env bash
# emulator-me-socials (#1771, #1901) screenshots on a booted emulator: the SuperApp's Cloud > Apps Me row (Lnktree and Socials
# tiles), Cloud Me's three reorganised tabs Pers, Prof and Proj (section:<id> opens each default page: Pers > Profiles > Personal,
# Prof > Profiles > Professional, Proj > Lnktree) and the ids of the layout before the move (page:socials/<tab>, page:health/home,
# page:lnktree/leafy), which keep resolving through ui.renamed.
# Usage: emulator-me-socials.sh ME_APK SUPERAPP_APK OUT_DIR
set -uo pipefail
ME_APK="$1"; SA_APK="$2"; OUT="$3"; mkdir -p "$OUT"
T="timeout 120"
ME=com.diegonmarcos.cloudme; SA=com.diegonmarcos.superapp
rc=0
$T adb wait-for-device
$T adb install -r -g "$ME_APK" || rc=1
$T adb install -r -g "$SA_APK" || rc=1
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
wake
launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() { wake; local comp; comp="$(launcher "$1")"; $T adb shell am start -W -n "$comp" -f 0x14000000 --es shortcut_action "$2" >/dev/null; }
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; echo "shot $1"; }

# first start runs Cloud Me's start-up permission walk (usage access, overlay, files: the run of 44062126507 and 45491054390 shot its
# "Apps with usage access" Settings page instead of the tab): grant those appops first, then back out until Cloud Me is on top
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE REQUEST_INSTALL_PACKAGES; do $T adb shell appops set "$ME" "$op" allow; done
open_page "$ME" "page:configs/general"; sleep 12
for i in 1 2 3 4 5 6; do
    focus="$($T adb shell dumpsys window 2>/dev/null)"
    case "$(grep -m1 mCurrentFocus <<<"$focus" || true)" in *"$ME"*) break ;; esac
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
done
# #1901 the three tabs, each on its default page; the selected bar item names the section (a missing one fails the walk)
walk_tab() {  # TARGET SHOT LABEL
    open_page "$ME" "$1"; shot "$2" 12
    grep -qE "(text|content-desc)=\"$3\"" "$OUT/$2.xml" || { echo "$2: the screen does not show $3"; rc=1; }
    echo "$2 text nodes: $(grep -o 'text="[^"]\+"' "$OUT/$2.xml" | wc -l)"
}
walk_tab section:pers me-pers Pers
walk_tab section:prof me-prof Prof
walk_tab section:proj me-proj Proj
# the ids of the layout before the move still land on a page (page:health/home -> Pers, page:lnktree/leafy -> Prof > Leaf.y)
open_page "$ME" "page:health/home"; shot me-old-health-home 8
open_page "$ME" "page:lnktree/leafy"; shot me-old-lnktree-leafy 10
open_page "$ME" "page:socials/socials-professional"; shot me-socials-professional 10
open_page "$ME" "page:socials/socials-personal"; shot me-socials-personal 10
# the Professional body must hold page text, not only the chrome (#1771: it was blank)
for t in professional personal; do echo "me-socials-$t text nodes: $(grep -o 'text="[^"]\+"' "$OUT/me-socials-$t.xml" | wc -l)"; done

$T adb shell appops set "$SA" SYSTEM_ALERT_WINDOW deny
open_page "$SA" "page:cloud/apps"; sleep 10
$T adb shell am force-stop "$SA"; $T adb shell appops set "$SA" SYSTEM_ALERT_WINDOW deny
open_page "$SA" "page:cloud/apps"; sleep 10
for i in 1 2; do
    $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/.sa.xml" >/dev/null 2>&1
    xy=$(python3 -c "import re,sys;x=open(sys.argv[1],errors='ignore').read();m=re.search(r'text=\"Unlock\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x);print('' if not m else '%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2))" "$OUT/.sa.xml" 2>/dev/null)
    [ -n "$xy" ] || break
    $T adb shell input tap $xy; sleep 4; open_page "$SA" "page:cloud/apps"; sleep 6
done
wh=$($T adb shell wm size | tr -d '\r' | sed 's/.*: //'); W=${wh%x*}; H=${wh#*x}
for i in 1 2 3 4 5; do $T adb shell input tap $((W*61/100)) $((H*58/100)); sleep 2; done
shot superapp-cloud-apps 3
# the Me row (Lnktree first, Socials fifth) sits at ~80% height; scroll the page so it is fully in view
$T adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*40/100)) 500; shot superapp-cloud-apps-me-row 3
$T adb logcat -d > "$OUT/logcat.txt"
ls -la "$OUT"
exit $rc
