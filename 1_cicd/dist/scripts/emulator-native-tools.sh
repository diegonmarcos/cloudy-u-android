# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-native-tools.sh ───
#!/bin/bash
# #1708 every native tool an app ships as lib*.so (jniLibs, useLegacyPackaging) must exist in applicationInfo.nativeLibraryDir
# and LAUNCH from there under the app's own uid and SELinux domain, at the app's targetSdk (>= 29 forbids exec from the data dir).
# Runs on an emulator with `adb` attached; the APK must be debuggable (run-as).
#   emulator-native-tools.sh APK PKG OUT_DIR SPEC...      SPEC = file:mode, mode one of
#       present                 the file is in nativeLibraryDir (a library, not run)
#       run:<args>              executed with <args>, exit 0 required (args may use $NLD and $FILES)
#       prootrun                proot + its loader from nativeLibraryDir runs /system/bin/sh -c 'echo OK' in a "/" root
set -u
APK="$1"; PKG="$2"; OUT="$3"; shift 3
mkdir -p "$OUT"; : > "$OUT/result.txt"
adb install -r -g "$APK" >/dev/null || { echo "install failed"; exit 2; }
tsdk=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/.*targetSdk=\([0-9]*\).*/\1/p' | head -1)
echo "targetSdk=$tsdk" | tee -a "$OUT/result.txt"
[ "${tsdk:-0}" -ge 35 ] || { echo "FAIL targetSdk $tsdk < 35"; exit 1; }
base=$(adb shell pm path "$PKG" | tr -d '\r' | head -1 | sed 's/^package://')
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
NLD="$(dirname "$base")/lib/$(case "$abi" in x86_64) echo x86_64;; arm64*) echo arm64;; *) echo "$abi";; esac)"
FILES="/data/data/$PKG/files"
adb shell "ls -l $NLD" | tee "$OUT/nld.txt"
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >/dev/null 2>&1 || true
sleep 5
fail=0
for spec in "$@"; do
  f="${spec%%:*}"; rest="${spec#*:}"
  case "$rest" in
    present)
      if adb shell "[ -f $NLD/$f ]"; then echo "ok   $f present"; else echo "FAIL $f missing from $NLD"; fail=1; fi ;;
    run:*)
      args="${rest#run:}"
      out=$(adb shell "run-as $PKG sh -c 'cd $FILES && $NLD/$f $args' 2>&1; echo RC=\$?" | tr -d '\r'); rc=$(echo "$out" | sed -n 's/^RC=//p' | tail -1)
      echo "$out" | head -5
      if [ "$rc" = 0 ]; then echo "ok   $f runs"; else echo "FAIL $f rc=$rc"; fail=1; fi ;;
    prootrun)
      out=$(adb shell "run-as $PKG sh -c 'mkdir -p $FILES/tmp && cd $FILES && PROOT_TMP_DIR=$FILES/tmp PROOT_LOADER=$NLD/libproot.so LD_LIBRARY_PATH=$NLD $NLD/$f --kill-on-exit -r / /system/bin/sh -c \"echo PROOT_OK\"' 2>&1; echo RC=\$?" | tr -d '\r')
      echo "$out" | head -8
      if echo "$out" | grep -q PROOT_OK; then echo "ok   $f ran a command through proot"; else echo "FAIL $f proot run"; fail=1; fi ;;
  esac
done 2>&1 | tee -a "$OUT/result.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null || true
grep -c "^FAIL" "$OUT/result.txt" | grep -qx 0 || fail=1
[ "$fail" = 0 ] && echo "native tools: all launch" || { echo "native tools: FAILED"; exit 1; }
