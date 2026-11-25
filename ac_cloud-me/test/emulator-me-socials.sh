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
# the stale dump of the previous page is removed first: an animated page (Lnktree's loader) can make uiautomator fail, and a left-over file would read as this page's
shot() { sleep "${2:-6}"; $T adb exec-out screencap -p > "$OUT/$1.png"; rm -f "$OUT/$1.xml"; $T adb shell rm -f /sdcard/ui.xml
    for _ in 1 2 3; do $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && break; sleep 2; done; echo "shot $1"; }
# on_screen XML LABEL: the page's own dump names LABEL (a missing dump is no verdict: the screenshot is the evidence)
on_screen() { if [ -s "$1" ]; then grep -qE "(text|content-desc)=\"$2\"" "$1"; else echo "$1: no ui dump (the page animates), the screenshot is the evidence"; fi; }

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
    on_screen "$OUT/$2.xml" "$3" || { echo "$2: the screen does not show $3"; rc=1; }
    echo "$2 text nodes: $(grep -o 'text="[^"]\+"' "$OUT/$2.xml" | wc -l)"
}
walk_tab section:pers me-pers Pers
walk_tab section:prof me-prof Prof
# Proj opens on Lnktree, whose animated loader makes uiautomator and the software-rendered emulator slow (a first run lost the emulator there): it is walked last, so no other evidence depends on it
# the ids of the layout before the move still land on a page (page:health/home -> Pers, page:lnktree/leafy -> Prof > Leaf.y)
open_page "$ME" "page:health/home"; shot me-old-health-home 8
on_screen "$OUT/me-old-health-home.xml" Pers || { echo "me-old-health-home: page:health/home did not resolve to Pers"; rc=1; }
open_page "$ME" "page:lnktree/leafy"; shot me-old-lnktree-leafy 10
on_screen "$OUT/me-old-lnktree-leafy.xml" Prof || { echo "me-old-lnktree-leafy: page:lnktree/leafy did not resolve to Prof"; rc=1; }
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
walk_tab section:proj me-proj Proj
open_page "$ME" "page:proj/pj-home"; shot me-proj-home 8   # #1914 the icon grid of every group and leaf
# #1918 the Home grid wears the emoji of Linktree and the banners of its categories; Repos (the bundled repos tree) loads in the page WebView
python3 -c "import html,sys;sys.exit(0 if '\U0001F427' in html.unescape(open(sys.argv[1],errors='ignore').read()) else 1)" "$OUT/me-proj-home.xml" 2>/dev/null || { echo "me-proj-home: the Linktree emoji is not on the Proj tabs"; rc=1; }
$T adb shell am force-stop "$ME" </dev/null; $T adb logcat -c </dev/null
open_page "$ME" "page:proj/pj-repos" </dev/null; shot me-proj-repos 12 </dev/null
$T adb logcat -d -s CloudMeWeb:V < /dev/null > "$OUT/tab-proj-repos.log"
grep -q "MAINFRAME_OK page=pj-repos " "$OUT/tab-proj-repos.log" && echo "proj-repos: loaded the bundled repos tree" || { echo "proj-repos: the repos tree did not load"; rc=1; }
# #1911 every Pers/Prof/Proj tab of data/web/me-links.json: open it, screenshot it, and assert its main frame did not hit an error page
# (WebPage logs MAINFRAME_ERROR / MAINFRAME_OK page=<id> url=<url> title=<title>; the title must not be "Web page not available")
TABLES="$(dirname "$0")/../data/web/me-links.json"
python3 - "$TABLES" > "$OUT/.tabs" <<'PY'
import json, sys
# the Lnktree site (a heavy animated page) is walked last: a lost emulator then costs no other tab
for e in sorted(json.load(open(sys.argv[1]))["entries"], key=lambda e: e["section"] == "lnktree"):
    if "path" in e: print(e["section"], e["path"][-1], e["url"], e["id"])
PY
[ -s "$OUT/.tabs" ] || { echo "no tabs in me-links.json"; rc=1; }
while read -r sec page url id <&3; do
    $T adb shell am force-stop "$ME" </dev/null   # a fresh process: the page loads (and logs) again even when it is the one already on top
    $T adb logcat -c </dev/null
    open_page "$ME" "page:$sec/$page" </dev/null; shot "me-tab-$id" 12 </dev/null
    # a heavy page (Warehouse's 3D) finishes late and the image check runs 8 s after the finish: poll the log up to 40 s for both lines
    for _ in $(seq 1 8); do
        $T adb logcat -d -s CloudMeWeb:V < /dev/null > "$OUT/tab-$id.log"
        grep -q IMG_CHECK "$OUT/tab-$id.log" && break
        grep -q MAINFRAME_ERROR "$OUT/tab-$id.log" && break
        sleep 5
    done
    # the page loaded when its own id reports a finished main frame; a redirect (LinkedIn's auth wall) changes the final url, which is printed
    if grep -q MAINFRAME_ERROR "$OUT/tab-$id.log"; then echo "$id: main frame error: $(grep -m1 MAINFRAME_ERROR "$OUT/tab-$id.log")"; rc=1
    elif grep -q "title=Web page not available" "$OUT/tab-$id.log"; then echo "$id: error page title"; rc=1
    elif ! grep -q "MAINFRAME_OK page=$page " "$OUT/tab-$id.log"; then echo "$id: the page did not load $url ($(tail -1 "$OUT/tab-$id.log"))"; rc=1
    else echo "$id: loaded $url as $(grep -m1 -o "MAINFRAME_OK page=$page url=[^ ]*" "$OUT/tab-$id.log")"; fi
    # #1913 no broken image on our own pages: WebPage logs IMG_CHECK page=<id> images_broken="<images> <broken> <first broken src>" (naturalWidth 0 on a finished img).
    # A third-party site (github.com, linkedin.com) is checked for the error page only: its tracking pixels and avatars are not ours to fix.
    case "$url" in https://diegonmarcos.*|https://*.diegonmarcos.com/*|https://linktree.diegonmarcos.com/*)
    if ! grep -q IMG_CHECK "$OUT/tab-$id.log"; then echo "$id: no IMG_CHECK line (the page was not checked for images)"; rc=1
    elif ! grep -q 'images_broken="[0-9]* 0 ' "$OUT/tab-$id.log"; then echo "$id: broken images: $(grep -m1 IMG_CHECK "$OUT/tab-$id.log")"; rc=1; fi ;; esac
    if grep -q "Web page not available" "$OUT/me-tab-$id.xml" 2>/dev/null; then echo "$id: the screen shows Web page not available"; rc=1; fi
done 3< "$OUT/.tabs"   # fd 3: adb inside the loop must not eat the tab list from stdin
# the Lnktree bottom-nav entry (the remote site, an animated loader that kills the emulator under a UI dump) is covered by the lnktree-default tab above: its main frame must load
$T adb logcat -d > "$OUT/logcat.txt"
ls -la "$OUT"
exit $rc
