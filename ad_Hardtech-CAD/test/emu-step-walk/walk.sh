#!/bin/bash
set -u
mkdir -p screens
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb install -r -g app.apk || exit 2
# sendevent (the two-finger pinch) is denied to the shell domain: userdebug emulator, so root + permissive for the walk only
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device; adb shell setenforce 0 || true
PKG=com.diegonmarcos.hardtechcad
adb logcat -v threadtime "*:W" chromium:I CadHost:I > screens/logcat.txt 2>&1 &
python3 walk.py "$PKG"; rc=$?
for id in house-boat dji-s900; do adb exec-out run-as $PKG cat cache/cad-snapshot-$id.png > screens/canvas-$id.png || true; done
adb logcat -d -s chromium:I CadHost:I | grep -iE "console|error|cad" | tail -120 > screens/console.txt || true
adb shell ls -l /data/data/$PKG/cache/cad-models >> screens/package.txt 2>&1 || true
adb shell dumpsys package "$PKG" | grep -E "versionName" >> screens/package.txt || true
exit $rc
