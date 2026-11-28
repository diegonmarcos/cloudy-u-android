# walk-no-autoupdate.sh — sourced by the Cloud Me emulator walks (#1957): before the first launch, the app's auto-update is switched off
# in its own SharedPreferences (auto_update.xml, key enabled, AutoUpdatePrefs), as root. Why: the in-app updater replaced the CI APK with
# the published release mid-walk and drew its "Installing..." overlay over the page under test (render walks 46087570817, 46088492613).
# Needs: $T, $ME, $OUT, a google_apis image (adb root).
no_autoupdate() {
    $T adb root >/dev/null 2>&1 || true; $T adb wait-for-device
    printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '    <boolean name="enabled" value="false" />' '</map>' > "$OUT/auto_update.xml"
    $T adb push "$OUT/auto_update.xml" /data/local/tmp/auto_update.xml >/dev/null
    $T adb shell "d=/data/data/$ME/shared_prefs; mkdir -p \$d; cp /data/local/tmp/auto_update.xml \$d/auto_update.xml; u=\$(stat -c %u /data/data/$ME); chown -R \$u:\$u \$d; chmod 771 \$d; chmod 660 \$d/auto_update.xml; restorecon -R \$d"
    echo "auto-update off: $($T adb shell cat /data/data/$ME/shared_prefs/auto_update.xml | tr -d '\r' | grep -c 'enabled\" value=\"false')"
}
