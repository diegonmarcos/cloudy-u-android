#!/usr/bin/env bash
# #1724 emulator run of the keep-alive path: cold launch, Keep alive ON (the service starts through startForegroundService and must call
# startForeground), repeated start requests (each one needs its own startForeground), Stop action, Keep alive OFF, a force-stop and a
# cold relaunch with Keep alive ON ("restart"), and the boot receiver's BOOT_COMPLETED. The verdict: no FATAL EXCEPTION of the app and no
# ForegroundServiceDidNotStartInTimeException anywhere in logcat. Usage: emulator-keepalive.sh WEBSERVER_APK OUT_DIR
set -u
WS_APK="$1"; OUT="$2"; W=com.diegonmarcos.cloudwebserver; SVC="$W/.KeepAliveService"
mkdir -p "$OUT"
rc=0; fail() { echo "::error::$*"; rc=1; }
adb install -r -g "$WS_APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
adb logcat -c
shoot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
power() { adb forward tcp:18002 tcp:8002 >/dev/null; curl -s -m 10 -X POST -H 'content-type: application/json' -d "$1" http://127.0.0.1:18002/__app__/api/power >>"$OUT/power.txt" 2>&1; echo >>"$OUT/power.txt"; }
fg_state() { adb shell dumpsys activity services "$W" 2>/dev/null | tr -d '\r'; }
launch() { adb shell am force-stop "$W"; adb shell input keyevent KEYCODE_HOME; adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 12; }
waitfor() { for i in 1 2 3 4 5 6 7 8; do fg_state | grep -q "isForeground=true" && return 0; sleep 2; done; return 1; }

echo "== 1 cold launch (Keep alive default OFF)"; launch; shoot 1-cold
echo "== 2 Keep alive ON"; power '{"keep_alive":true}'; sleep 6
waitfor && echo "service is foreground" || fail "Keep alive ON: the service never became foreground"
shoot 2-on; fg_state > "$OUT/2-services.txt"
echo "== 3 repeated start requests (each must call startForeground again)"
for i in 1 2 3 4 5; do adb shell am start-foreground-service -n "$SVC" >/dev/null 2>&1; sleep 1; done; sleep 3
waitfor && echo "still foreground" || fail "repeated start requests: the service is not foreground"
echo "== 4 Stop action"; adb shell am start-foreground-service -n "$SVC" -a com.diegonmarcos.cloudwebserver.STOP >/dev/null 2>&1; sleep 5; shoot 4-stop
echo "== 5 Keep alive OFF, then a start request while OFF (gate refuses: startForeground then stop)"
power '{"keep_alive":false}'; sleep 3
adb shell am start-foreground-service -n "$SVC" >/dev/null 2>&1; sleep 7; shoot 5-off
echo "== 6 restart: force-stop, cold launch with Keep alive ON"
power '{"keep_alive":true}'; sleep 3; launch; waitfor && echo "foreground after the restart" || fail "restart: the service is not foreground"; shoot 6-restart
echo "== 7 BOOT_COMPLETED to the boot receiver"
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n "$W/.BootReceiver" >"$OUT/7-boot.txt" 2>&1; sleep 8
waitfor && echo "foreground after boot" || fail "BOOT_COMPLETED: the service is not foreground"
echo "== 8 Keep alive OFF again, cold launch"; power '{"keep_alive":false}'; sleep 3; launch; shoot 8-final
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
grep -qE "ForegroundServiceDidNotStartInTimeException|Context.startForegroundService\(\) did not then call" "$OUT/logcat.txt" && fail "logcat holds ForegroundServiceDidNotStartInTimeException"
grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$W" && fail "the app crashed (FATAL EXCEPTION)"
grep -E "C3KeepAlive|C3BootReceiver|foreground start refused" "$OUT/logcat.txt" > "$OUT/keepalive-log.txt" || true
[ "$rc" = 0 ] && echo "keep-alive emulator run: no crash" || echo "keep-alive emulator run: FAILED"
exit $rc
