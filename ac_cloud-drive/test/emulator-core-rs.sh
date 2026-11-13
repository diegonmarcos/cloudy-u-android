#!/bin/bash
# Emulator smoke test (#1729): the Rust core of Cloud Drive (libs:drive-core-rs) really loads in the app and runs the hashing, walk and diff engines.
#   1. the APK carries libdrive_core_rs.so for the emulator's ABI;
#   2. cold start: logcat (tag DriveCore) says `startup: core=drive-core-rs <version> switches=hash=rust,walk=rust,diff=rust`;
#   3. MainActivity with the self-test extra: logcat shows the engines answering through Rust, equal to the Kotlin reference, and `selftest: PASS rust`
#      (including a deliberate Rust panic caught at the JNI boundary);
#   4. the process is still alive: nothing crashed.
# Evidence: <outdir>/core-rs-logcat.txt (the DriveCore lines) and <outdir>/logcat.txt. Usage: emulator-core-rs.sh <drive.apk> <outdir>
set -u
APK=${1:?drive apk}; OUT=${2:-out}
PKG=com.diegonmarcos.clouddrive
mkdir -p "$OUT"
fail() { echo "::error title=Drive Rust core::$*"; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; adb logcat -d -s DriveCore:I > "$OUT/core-rs-logcat.txt" 2>/dev/null; exit 1; }

# 1. the library is in the APK
unzip -l "$APK" | awk '{print $4}' | grep -E '^lib/[^/]+/libdrive_core_rs\.so$' | tee "$OUT/apk-core-libs.txt"
[ -s "$OUT/apk-core-libs.txt" ] || fail "the APK carries no libdrive_core_rs.so"

adb install -r -g "$APK" || fail "install failed"
adb logcat -c
# 2. cold start
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || fail "the app did not start"
for i in $(seq 1 30); do adb logcat -d -s DriveCore:I | grep -q 'startup: core=' && break; sleep 2; done
adb logcat -d -s DriveCore:I | grep 'startup: core=' | tee "$OUT/startup-line.txt"
grep -q 'startup: core=drive-core-rs' "$OUT/startup-line.txt" || fail "the Rust core did not load: $(cat "$OUT/startup-line.txt")"
grep -q 'switches=hash=rust,walk=rust,diff=rust' "$OUT/startup-line.txt" || fail "the switches are not the declared defaults: $(cat "$OUT/startup-line.txt")"

# 3. the JNI smoke test
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" --ez com.diegonmarcos.clouddrive.EXTRA_CORE_SELFTEST true >/dev/null 2>&1 || fail "the app did not start for the self-test"
for i in $(seq 1 45); do adb logcat -d -s DriveCore:I | grep -qE 'selftest: (PASS|FAIL)' && break; sleep 2; done
adb logcat -d -s DriveCore:I > "$OUT/core-rs-logcat.txt"
cat "$OUT/core-rs-logcat.txt"
grep -q 'selftest: PASS rust' "$OUT/core-rs-logcat.txt" || fail "the self-test did not pass through Rust"
grep -q 'engine=hash impl=rust' "$OUT/core-rs-logcat.txt" || fail "no hash engine call ran through Rust"
grep -q 'engine=walk impl=rust' "$OUT/core-rs-logcat.txt" || fail "no walk engine call ran through Rust"
grep -q 'engine=diff impl=rust' "$OUT/core-rs-logcat.txt" || fail "no diff engine call ran through Rust"
grep -q 'fallback to Kotlin' "$OUT/core-rs-logcat.txt" && fail "an engine fell back to Kotlin: $(grep 'fallback to Kotlin' "$OUT/core-rs-logcat.txt" | head -3)"

# 4. alive, no crash
adb shell pidof "$PKG" >/dev/null || fail "the app is not running after the self-test"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" "$OUT/logcat.txt" && fail "the app crashed"
echo "Rust core smoke test passed"
