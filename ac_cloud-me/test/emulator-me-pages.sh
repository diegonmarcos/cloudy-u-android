#!/usr/bin/env bash
# emulator-me-pages — #1439/#1440 screenshots on a booted emulator: Cloud Me's Configs tabs (General | Perms | About |
# Navigation | Apps), its Profile page (libs:account-ui: Personal Info and Configs), and the SuperApp's Cloud > Apps
# page scrolled to the Me row (Profile tile). Pages are opened by the fleet's deep link (extra shortcut_action, the
# grammar page:<section>/<page>). Usage: emulator-me-pages.sh ME_APK SUPERAPP_APK OUT_DIR
set -uo pipefail
ME_APK="$1"; SA_APK="$2"; OUT="$3"; mkdir -p "$OUT"
T="timeout 120"
ME=com.diegonmarcos.cloudme; SA=com.diegonmarcos.superapp
rc=0

$T adb wait-for-device
$T adb install -r -g "$ME_APK" || rc=1
[ -n "$SA_APK" ] && [ -f "$SA_APK" ] && { $T adb install -r -g "$SA_APK" || rc=1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell locksettings set-disabled true; $T adb shell settings put system screen_off_timeout 1800000
wake() { $T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard; }
wake

launcher() { $T adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -1 | tr -d '\r'; }
open_page() {  # open_page PKG TARGET
    wake; local comp; comp="$(launcher "$1")"
    $T adb shell am start -W -n "$comp" -f 0x14000000 --es shortcut_action "$2" >/dev/null
}
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; echo "shot $1"; }

# First start runs Cloud Me's start-up permission walk (special pages open once); come back on top of it.
open_page "$ME" "page:configs/general"; sleep 12; $T adb shell input keyevent KEYCODE_BACK; sleep 2
for p in general perms about nav apps; do open_page "$ME" "page:configs/$p"; shot "me-configs-$p"; done
open_page "$ME" "page:profile/profile"; shot me-profile-personal 8
# the Configs half of the profile page: tap its section tab (by text)
python3 - "$OUT/me-profile-personal.xml" > "$OUT/.tap" <<'PY' || true
import re, sys
x = open(sys.argv[1], errors="ignore").read()
for m in re.finditer(r'<node [^>]*text="Configs"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2); break
PY
if [ -s "$OUT/.tap" ]; then $T adb shell input tap $(cat "$OUT/.tap"); shot me-profile-configs; else echo "no Configs tab found on the profile page"; rc=1; fi
# #1445 Configs > Repos (the shared GitHub connect, libs:git-ui): tap its page tab, no token on a fresh emulator = read only
python3 - "$OUT/me-profile-configs.xml" > "$OUT/.tap" <<'PY' || true
import re, sys
x = open(sys.argv[1], errors="ignore").read()
for m in re.finditer(r'<node [^>]*text="Repos"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2); break
PY
if [ -s "$OUT/.tap" ]; then $T adb shell input tap $(cat "$OUT/.tap"); shot me-profile-configs-repos 10; else echo "no Repos tab on the profile Configs page"; rc=1; fi

# #1387/#1391 Experiences: every tab in Done and in Planned, top and one swipe down (page:experiences/<tab>)
tap_text() {  # tap_text SUBSTRING: tap the first node whose text contains it
    $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/.exp.xml" >/dev/null 2>&1
    local xy; xy=$(python3 -c "import re,sys;x=open(sys.argv[1],errors='ignore').read();m=re.search(r'text=\"[^\"]*'+re.escape(sys.argv[2])+r'[^\"]*\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x);print('' if not m else '%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2))" "$OUT/.exp.xml" "$1" 2>/dev/null)
    [ -n "$xy" ] && $T adb shell input tap $xy
}
$T adb shell settings delete global cloud_nav_ui_mode
ewh=$($T adb shell wm size | tr -d '\r' | sed 's/.*: //'); EW=${ewh%x*}; EH=${ewh#*x}
for t in work roadmap geo endurance growth community by30 by40 by50; do
    open_page "$ME" "page:experiences/$t"; shot "me-exp-$t-done" 5
    $T adb shell input swipe $((EW/2)) $((EH*70/100)) $((EW/2)) $((EH*25/100)) 600; shot "me-exp-$t-done-scrolled" 2
    open_page "$ME" "page:experiences/$t"; sleep 5
    # the Done | Planned switch sits below the fold on Geo and the By tabs: scroll until its label is on screen
    hit=0; for i in 1 2 3 4; do tap_text "Planned (" && { hit=1; break; }; $T adb shell input swipe $((EW/2)) $((EH*60/100)) $((EW/2)) $((EH*35/100)) 400; sleep 1; done
    if [ "$hit" = 1 ]; then shot "me-exp-$t-plan" 2; $T adb shell input swipe $((EW/2)) $((EH*70/100)) $((EW/2)) $((EH*25/100)) 600; shot "me-exp-$t-plan-scrolled" 2
    else echo "no Planned segment on experiences/$t"; rc=1; fi
done

# #1390 Health redesign, dark mode: opens on Tracked (section:health), then every redesigned page (page:health/<id>), top and scrolled
$T adb shell cmd uimode night yes
open_page "$ME" "section:health"; shot me-health-default 6
for h in tracked activities bodycomp hr sleep workout home; do
    open_page "$ME" "page:health/$h"; shot "me-health-$h" 5
    $T adb shell input swipe $((EW/2)) $((EH*70/100)) $((EW/2)) $((EH*25/100)) 600; shot "me-health-$h-scrolled" 2
    $T adb shell input swipe $((EW/2)) $((EH*70/100)) $((EW/2)) $((EH*25/100)) 600; shot "me-health-$h-scrolled2" 2
done
$T adb shell cmd uimode night no

if [ -n "$SA_APK" ] && [ -f "$SA_APK" ]; then
    # the SuperApp's Screensaver is a black SYSTEM_ALERT_WINDOW overlay (floatingnav/ScreensaverService): no overlay for the shot
    $T adb shell appops set "$SA" SYSTEM_ALERT_WINDOW deny
    open_page "$SA" "page:cloud/apps"; sleep 10
    $T adb shell am force-stop "$SA"; $T adb shell appops set "$SA" SYSTEM_ALERT_WINDOW deny
    open_page "$SA" "page:cloud/apps"; sleep 10
    # the SuperApp draws its own lock screen over the launcher: tap its Unlock, then open the page again
    for i in 1 2; do
        $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/.sa.xml" >/dev/null 2>&1
        xy=$(python3 -c "import re,sys;x=open(sys.argv[1],errors='ignore').read();m=re.search(r'text=\"Unlock\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x);print('' if not m else '%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2))" "$OUT/.sa.xml" 2>/dev/null)
        [ -n "$xy" ] || break
        $T adb shell input tap $xy; sleep 4; open_page "$SA" "page:cloud/apps"; sleep 6
    done
    shot superapp-cloud-apps 4
    # tap a first-run dialog's Skip, then swipe the Me row (found by its Me-Buro tile) left to its last tile, Profile
    tapxy() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/.sa.xml" >/dev/null 2>&1
        python3 -c "import re,sys;x=open(sys.argv[1],errors='ignore').read();m=re.search(r'text=\"'+sys.argv[2]+r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x);print('' if not m else '%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2))" "$OUT/.sa.xml" "$1" 2>/dev/null; }
    # the SuperApp's Compose views expose no text to uiautomator: scale from the screen size. Its first-run dialogs
    # (install source, default launcher, ...) chain; each has Skip at ~(61%, 58%). The Me row sits at ~80% height.
    wh=$($T adb shell wm size | tr -d '\r' | sed 's/.*: //'); W=${wh%x*}; H=${wh#*x}
    for i in 1 2 3 4 5; do $T adb shell input tap $((W*61/100)) $((H*58/100)); sleep 2; done
    y=$((H*80/100)); $T adb shell input swipe $((W*95/100)) $y $((W*5/100)) $y 600; sleep 1; $T adb shell input swipe $((W*95/100)) $y $((W*5/100)) $y 600
    shot superapp-cloud-apps-me-row 3
fi
$T adb logcat -d > "$OUT/logcat.txt"
ls -la "$OUT"
exit $rc
