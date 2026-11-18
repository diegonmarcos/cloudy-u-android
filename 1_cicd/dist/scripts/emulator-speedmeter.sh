# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-speedmeter.sh ───
#!/usr/bin/env bash
# emulator-speedmeter — #1446 screenshots on a booted emulator of the SuperApp's one-hand home with the speedmeter in the
# top-left status strip: the home as it opens, then after a tap on the meter (the bounded download test).
# Exit 1 when the meter is not on the screen. Usage: emulator-speedmeter.sh APK OUT_DIR
set -uo pipefail
APK="$1"; OUT="$2"; PKG=com.diegonmarcos.superapp; mkdir -p "$OUT"
T="timeout 120"; rc=0
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true
dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/speed-$1.png"; dump "speed-$1"; echo "shot speed-$1"; }
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 25
dump cur
if ! grep -q "package=\"$PKG\"" "$OUT/cur.xml"; then
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 12
fi
# the app's screensaver overlay (neon clock + a single Unlock button) can sit over the home: tap Unlock
for _ in 1 2 3; do
    dump cur
    grep -q 'text="Unlock"' "$OUT/cur.xml" || break
    b="$(python3 - "$OUT/cur.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="Unlock"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: l, t, r, d = map(int, m.groups()); print((l + r) // 2, (t + d) // 2)
PY
)"
    [ -n "$b" ] && $T adb shell input tap $b; sleep 4
done
# the SuperApp's first-run asks ("Install unknown apps", "Default home app (launcher)": Skip | Allow) can sit over the home: Skip it
for _ in 1 2 3; do
    dump cur
    grep -q -e 'text="Install unknown apps"' -e 'text="Default home app (launcher)"' "$OUT/cur.xml" || break
    b="$(python3 - "$OUT/cur.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="Skip"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: l, t, r, d = map(int, m.groups()); print((l + r) // 2, (t + d) // 2)
PY
)"
    [ -n "$b" ] && $T adb shell input tap $b; sleep 4
done
# the status strip is drawn ONLY when the SuperApp is the default launcher (activity_main.xml: visibility gone until
# applyLauncherChrome sees isDefaultLauncher), so make it the HOME role holder and bring it up with HOME, as a phone does.
$T adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" 0 >/dev/null 2>&1 || echo "note: could not take the HOME role"
# The command can exit 0 without granting the role (e.g. a package that does not qualify), and the strip only draws when
# HomeActivity resolves CATEGORY_HOME to this package: read the holder back and fail with that reason instead of a
# missing chip later (run 44225173645 showed the system status bar, i.e. launcher mode never engaged).
holders="$($T adb shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')"
echo "HOME role holders: ${holders:-<none>}"
resolved="$($T adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | tail -1)"
echo "HOME resolves to: ${resolved:-<none>}"
case "$holders" in *"$PKG"*) ;; *) echo "FAIL: $PKG is not the HOME role holder, so the launcher strip cannot draw"; rc=1;; esac
# bring the launcher door up explicitly (HomeActivity, the CATEGORY_HOME activity), then HOME as a phone does
$T adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.HomeActivity" >/dev/null 2>&1 || echo "note: could not start HomeActivity"
$T adb shell input keyevent KEYCODE_HOME; sleep 8
echo "resumed: $($T adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -d '\r' | sed 's/^ *//')"
# coming up as the launcher shows the screensaver overlay again (clock + Unlock): tap it away, then the home is on screen
for _ in 1 2 3; do
    dump cur
    grep -q 'text="Unlock"' "$OUT/cur.xml" || break
    b="$(python3 - "$OUT/cur.xml" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
m = re.search(r'text="Unlock"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m: l, t, r, d = map(int, m.groups()); print((l + r) // 2, (t + d) // 2)
PY
)"
    [ -n "$b" ] && $T adb shell input tap $b; sleep 5
done
shot home 3
# #1446 the speed test lives in the network popup: open it by the WiFi chip of the status strip (content-desc "Network status: WiFi")
bounds() { # <xml> <attr> <value-regex>: print the centre of the first node whose attr matches
python3 - "$1" "$2" "$3" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    if re.search(sys.argv[2] + r'="' + sys.argv[3] + '"', a):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        l, t, r, d = map(int, b.groups()); print((l + r) // 2, (t + d) // 2); break
PY
}
# The SuperApp's screensaver overlay (clock + Unlock) comes back within seconds of being dismissed in launcher mode
# (run 44246477193: the strip with "Network status: WiFi" was in cur.xml, then the overlay covered it at the shot).
# Dismiss it right before each lookup and look again, up to three times.
p="$(bounds "$OUT/speed-home.xml" content-desc 'Network status: WiFi')"
for _ in 1 2 3; do
    [ -n "$p" ] && break
    dump home-retry
    u="$(bounds "$OUT/home-retry.xml" text 'Unlock')"
    [ -n "$u" ] && { $T adb shell input tap $u; sleep 2; dump home-retry; }
    p="$(bounds "$OUT/home-retry.xml" content-desc 'Network status: WiFi')"
done
[ -n "$p" ] && $T adb exec-out screencap -p > "$OUT/speed-home-unlocked.png"
if [ -z "$p" ]; then echo "FAIL: no network chip in the status strip"; rc=1; else
    $T adb shell input tap $p
    sleep 3; dump popup
    # the popup scrolls: swipe up until the Speed test action is on screen
    q=""
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        q="$(bounds "$OUT/popup.xml" text 'Speed test ›')"
        [ -n "$q" ] && break
        sz="$($T adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"; w=${sz%x*}; h=${sz#*x}
        $T adb shell input swipe $((w / 2)) $((h * 7 / 10)) $((w / 2)) $((h * 3 / 10)) 400; sleep 1; dump popup
    done
    if [ -z "$q" ]; then echo "FAIL: no Speed test action in the network popup"; rc=1; else
        $T adb shell input tap $q
        shot testing 2
        # the result: the download row reads "<n> MB/s <n> Mbps" and the last-run row "... N in 24 h"; a failure reads "test failed: ..."
        ok=0
        for _ in $(seq 1 24); do
            sleep 5; dump done
            grep -q 'text="test failed' "$OUT/done.xml" && { echo "FAIL: speed test failed in the popup"; break; }
            if grep -Eq 'text="[0-9.]+ MB/s +[0-9.]+ Mbps"' "$OUT/done.xml" && grep -q 'in 24 h"' "$OUT/done.xml"; then ok=1; break; fi
        done
        shot done 1
        [ "$ok" = 1 ] && echo "OK: speed test ran and shows a result" || { echo "FAIL: no speed test result in the popup"; rc=1; }
    fi
fi
$T adb logcat -d -t 3000 > "$OUT/speed-logcat.txt"
grep -E "FATAL EXCEPTION" -A12 "$OUT/speed-logcat.txt" | grep -B2 -A12 "$PKG" | head -40 > "$OUT/speed-crash.txt" || true
[ -s "$OUT/speed-crash.txt" ] && { echo "FAIL: crash in logcat"; rc=1; }
exit $rc
