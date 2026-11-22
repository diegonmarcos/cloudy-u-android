#!/usr/bin/env bash
# emulator-wiki-screens (#1778): runs INSIDE the android-emulator-runner script step of .github/workflows/wiki-screens-emulator.yml.
# Installs the SuperApp and Cloud Wiki (x86_64), seeds Cloud Wiki's own last-known-good copy of its runtime dataset (the client's fallback when Cloud
# Store has served nothing, libs:fleet-data-client FleetDataReader) from data/fleet, and photographs:
#   projects-row        Cloud > Apps with the Projects row (Wiki | SuperApp, Omniverse | FinTerm, BioMed, CAD | Circusverse)
#   wiki-topic-page     Engineering > Civil engineering: the Markdown explanation with its tag chips
#   wiki-tag-filter     Math Overview filtered by two tag chips
#   wiki-graph          the force-directed graph of every page
#   wiki-chronos        #1789 the Chronos section: the root Chronos page (lib-chronos, baked) in its web view
#   wiki-chronos-menu   the same with the side menu expanded: Chronos and its nested pages in the repo's hierarchy
#   wiki-sacred         #1857 Projects > Sacred Geometry: the Markdown curriculum of lib-chronos Sacred-geo/
#   wiki-chronos-deep   a deep simulator page: 3.1 Quantum Gravity Cycle (Chronos > Quantum Cycles > ...), served offline from the APK
# usage: emulator-wiki-screens.sh <superapp.apk> <wiki.apk> <wiki.json> <outdir>
set -u
SUPER="$1"; WIKI="$2"; DATA="$3"; OUT="$4"
mkdir -p "$OUT"
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
SPKG=$("$aapt2" dump packagename "$SUPER"); WPKG=$("$aapt2" dump packagename "$WIKI")
adb install -r -g "$SUPER" || exit 2
adb install -r -g "$WIKI" || exit 2
act() { adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$1" | tr -d '\r' | tail -1; }
SACT=$(act "$SPKG"); WACT=$(act "$WPKG")
echo "superapp=$SPKG ($SACT) wiki=$WPKG ($WACT)"

# a quiet, awake, unlocked emulator (the same setup as screenshot-baseline.yml)
adb shell appops set "$SPKG" SYSTEM_ALERT_WINDOW deny || true
adb shell cmd package set-home-activity "$SPKG/.HomeActivity" || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell settings put system screen_off_timeout 2147483647; adb shell svc power stayon true
adb shell locksettings set-disabled true || true
adb shell settings put secure lockscreen.disabled 1 || true
awake() { adb shell am stopservice -n "$SPKG/.floatingnav.ScreensaverService" >/dev/null 2>&1 || true; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82; }
shot() { awake; sleep "${2:-6}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }

# Cloud Wiki's dataset: the client's last-known-good file (key = route path with / as __), written as the app's own user
KEY="app__${WPKG}__wiki"
adb push "$DATA" /data/local/tmp/wiki-seed.json >/dev/null
adb shell "run-as $WPKG mkdir -p files/fleetdata-lkg; cat /data/local/tmp/wiki-seed.json | run-as $WPKG sh -c 'cat > files/fleetdata-lkg/$KEY.json'" 2>&1 | tee "$OUT/seed.txt"
adb shell "run-as $WPKG ls -l files/fleetdata-lkg" 2>&1 | tee -a "$OUT/seed.txt"
wiki() { adb shell am force-stop "$WPKG"; adb shell am start -n "$WACT" "$@" >/dev/null; }

wiki --es page eng --es tab eng.civil;                shot wiki-topic-page 9
dump > "$OUT/wiki-topic-page.xml"
wiki --es page math --es tags history,society;        shot wiki-tag-filter 9
wiki --es page math;                                  shot wiki-overview 9
wiki --es page graph;                                 shot wiki-graph 20
dump > "$OUT/wiki-graph.xml"
# #1789 the Chronos pages: the section root, the side menu expanded over it, and a deep simulator (#1830 served from the app's own clone
# of lib-chronos once it has cloned, else the APK's own copy)
wiki --es page chronos.lib;                           shot wiki-chronos 15
dump > "$OUT/wiki-chronos.xml"
# tap the side menu's toggle as a dump shows it (the expanded state is a saved preference, so it is tapped again to put the rail back)
toggle() { local tb; tb=$(grep -o 'resource-id="navside_toggle"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$1" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$tb" ] || { echo "no navside_toggle in $1"; return 1; }; set -- $tb; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }
if toggle "$OUT/wiki-chronos.xml"; then shot wiki-chronos-menu 4; dump > "$OUT/wiki-chronos-menu.xml"; toggle "$OUT/wiki-chronos-menu.xml"; sleep 2
else echo "wiki-chronos-menu not captured"; fi
# #1831 the Chronos topic: ONE side-menu entry, its era pages and then | the simulators as its tab row
wiki --es page chronos;                               shot wiki-chronos-topic 15
dump > "$OUT/wiki-chronos-topic.xml"
wiki --es page chronos.lib.3.1;                       shot wiki-chronos-deep 20
dump > "$OUT/wiki-chronos-deep.xml"
# #1857 Projects > Sacred Geometry: the curriculum (Sacred-geo/topics.md as a wiki page), the walk workflow opens its 3D explorer tab
wiki --es page sacred;                                shot wiki-sacred 12
dump > "$OUT/wiki-sacred.xml"
adb logcat -d | grep -E "chromium|CloudWeb|Console" | tail -200 > "$OUT/wiki-chronos-webview.txt" || true

# the SuperApp's Cloud > Apps page; swipe up until the Projects row (its last tile, Circusverse) is on screen
adb shell am start -n "$SACT" >/dev/null; sleep 8
adb shell am start -n "$SACT" --es shortcut_action page:cloud/apps >/dev/null; awake; sleep 6
sz=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -1); w=${sz% *}; h=${sz#* }
for i in 1 2 3 4 5 6 7 8; do
  dump > "$OUT/cloud-apps.xml"
  if grep -q 'Circusverse' "$OUT/cloud-apps.xml" && grep -q 'text="Wiki"' "$OUT/cloud-apps.xml"; then echo "Projects row on screen after $i look(s)"; break; fi
  adb shell input swipe $((w/2)) $((h*7/10)) $((w/2)) $((h*3/10)) 300; sleep 2
done
shot projects-row 3
timeout 60 adb logcat -d > "$OUT/logcat.txt" || true
ls -l "$OUT"
