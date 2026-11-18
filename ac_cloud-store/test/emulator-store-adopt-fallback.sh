#!/usr/bin/env bash
# #1792 Adopt's fallback, on an emulator: a fleet-signed package installed through adb with ANOTHER app as its installer of record
# (`pm install -i <other installer>`), whose `pm set-installer <pkg> <store>` Android refuses, is adopted by an in-place reinstall
# through Cloud Store's own PackageInstaller session (data kept: a marker file in the package's storage survives). Red unless the
# installer reads back as Cloud Store afterwards. The package is the Termux:Boot fork (cld.termux.nix.boot), the one the phone refused.
# Usage: emulator-store-adopt-fallback.sh STORE_APK TARGET_APK OUT_DIR
set -u
STORE_APK="$1"; TARGET_APK="$2"; OUT="$3"; STORE=com.diegonmarcos.cloudstore
mkdir -p "$OUT"
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
PKG=$("$aapt2" dump packagename "$TARGET_APK" 2>/dev/null || echo cld.termux.nix.boot)
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
tap_text() {
  dump cur
  b=$(grep -oiE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | sed -n 1p | grep -oE 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
installer_of() { adb shell pm list packages -i "$1" | tr -d '\r' | grep -E "^package:$1 " | grep -oE 'installer=[^ ]*' | cut -d= -f2; }
adb install -r -g "$STORE_APK" > "$OUT/install-store.txt" 2>&1 || { cat "$OUT/install-store.txt"; exit 2; }
adb shell appops set "$STORE" REQUEST_INSTALL_PACKAGES allow || true
adb shell cmd deviceidle whitelist +"$STORE" >/dev/null 2>&1 || true
# another installer of record than the shell or the Store: the platform package installer (AOSP or Google name)
OTHER=$(adb shell pm list packages | tr -d '\r' | grep -oE 'com\.(google\.)?android\.packageinstaller$' | head -1)
[ -n "$OTHER" ] || OTHER=com.android.settings
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -i "$OTHER" "$TARGET_APK" > "$OUT/install-target.txt" 2>&1 || { cat "$OUT/install-target.txt"; exit 2; }
echo "installed $PKG with installer $(installer_of "$PKG")" | tee "$OUT/steps.txt"
# data that must survive the reinstall: the package's own external files dir
adb shell "mkdir -p /sdcard/Android/data/$PKG/files && echo keep-1792 > /sdcard/Android/data/$PKG/files/marker.txt"
# the real refusal, as the ADB owner would see it
SETOUT=$(adb shell pm set-installer "$PKG" "$STORE" 2>&1 | tr -d '\r')
printf '%s\n' "$SETOUT" > "$OUT/set-installer.txt"
echo "set-installer said: $SETOUT" | tee -a "$OUT/steps.txt"
NOW=$(installer_of "$PKG")
if [ "$NOW" = "$STORE" ]; then echo "::error::pm set-installer was NOT refused on this image (installer is already the Store): the fallback cannot be exercised"; exit 1; fi
# the Store in front (the confirmation, if Android asks, needs a foreground installer), then the probe
ACT=$(adb shell cmd package resolve-activity --brief "$STORE" | tr -d '\r' | tail -1)
adb shell am start -W -n "$ACT" >/dev/null; sleep 8
adb shell "mkdir -p /sdcard/Android/data/$STORE/files/adopt"
adb push "$TARGET_APK" "/sdcard/Android/data/$STORE/files/adopt/target.apk" >/dev/null
adb shell "run-as $STORE rm -f files/adopt-fallback-result.json" 2>/dev/null || true
adb logcat -c || true
B64=$(printf '%s' "$SETOUT" | base64 -w0)
adb shell am broadcast --include-stopped-packages -n "$STORE/com.diegonmarcos.superapp.appstore.UnpackTestReceiver" \
  -a com.diegonmarcos.cloud.action.TEST_ADOPT --es pkg "$PKG" --es apk "/sdcard/Android/data/$STORE/files/adopt/target.apk" --es setout "$B64" \
  | tee -a "$OUT/steps.txt"
r=none; shot=0
for i in $(seq 1 75); do
  sleep 2
  if tap_text "Update" || tap_text "Install" || tap_text "Reinstall"; then shot=$((shot+1)); shoot "confirm-$shot"; echo "tapped the confirmation" | tee -a "$OUT/steps.txt"; fi
  r=$(adb shell "run-as $STORE cat files/adopt-fallback-result.json" 2>/dev/null | tr -d '\r' || true)
  case "$r" in *'"ok":'*) break;; esac
  r=none
done
shoot after; dump after
AFTER=$(installer_of "$PKG")
MARK=$(adb shell cat "/sdcard/Android/data/$PKG/files/marker.txt" 2>/dev/null | tr -d '\r')
adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb shell dumpsys package "$PKG" | grep -iE "installerPackageName|installInitiator|updateOwner|installSource|versionCode" > "$OUT/dumpsys.txt" || true
echo "$r" > "$OUT/result.json"
{ echo "result: $r"; echo "installer after: $AFTER"; echo "marker after: $MARK"; } | tee -a "$OUT/steps.txt"
fail=0
case "$r" in *'"ok":true'*) ;; *) echo "::error title=adopt::the fallback did not adopt $PKG (result '$r')"; fail=1;; esac
[ "$AFTER" = "$STORE" ] || { echo "::error title=adopt::installer reads back as '$AFTER', not $STORE"; fail=1; }
[ "$MARK" = keep-1792 ] || { echo "::error title=adopt::the package's data did not survive the reinstall"; fail=1; }
[ $fail = 0 ] || { grep -E "StoreAdopt|UpdateInstaller|PackageInstaller|AndroidRuntime" "$OUT/logcat.txt" | tail -60; exit 1; }
echo "adopted by the in-place reinstall: $PKG installer=$AFTER, data kept"
