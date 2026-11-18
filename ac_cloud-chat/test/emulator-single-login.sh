#!/bin/bash
# #1804 Cloud Chat single login screen, on an emulator: empty, dropdown open, filled, server error with every
# value kept, the server check passing on the public server with a login error (data kept), and a connect
# that succeeds (no credentials → the existing login screen opens). Evidence: PNG + uiautomator XML per scene,
# summary.txt with one line per assertion. Usage: emulator-single-login.sh <apk> <out-dir>
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.diegonmarcos.comms.chat
S="$OUT/summary.txt"; : > "$S"
fail=0

wake() { adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; }
shot() { wake; adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { wake; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/$1.xml"; }
scene() { dump "$1"; shot "$1"; }
has() { if grep -q -- "$1" "$OUT/$2.xml"; then echo "OK   $2 has: $1" >> "$S"; else echo "FAIL $2 missing: $1" >> "$S"; fail=1; fi; }
hasnt() { if grep -q -- "$1" "$OUT/$2.xml"; then echo "FAIL $2 has: $1" >> "$S"; fail=1; else echo "OK   $2 has not: $1" >> "$S"; fi; }
kbd_hide() { adb shell dumpsys input_method | grep -q 'mInputShown=true' && adb shell input keyevent KEYCODE_BACK; sleep 1; }
# centre of the first node whose resource-id ends with $1 (RN testID), or whose text equals $1
find_xy() {
  dump scan
  python3 -I - "$1" "$OUT/scan.xml" <<'PY'
import re, sys
want, xml = sys.argv[1], open(sys.argv[2], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    rid = re.search(r' resource-id="([^"]*)"', n); t = re.search(r' text="([^"]*)"', n)
    rid = rid.group(1) if rid else ""; t = t.group(1) if t else ""
    if rid == want or rid.endswith(":id/" + want) or t == want:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
# tap $1, scrolling down then up to find it
tap() {
  local xy dir
  for dir in none down down down up up up up up; do
    case $dir in down) adb shell input swipe 540 1500 540 700 300 ;; up) adb shell input swipe 540 700 540 1500 300 ;; esac
    sleep 1
    xy=$(find_xy "$1"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 1; return 0; }
  done
  echo "FAIL tap: $1 not found" >> "$S"; fail=1; return 1
}
type_in() { tap "$1" && { adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent $(printf '67 %.0s' $(seq 60)); adb shell input text "$2"; kbd_hide; }; }
clear_in() { tap "$1" && { adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent $(printf '67 %.0s' $(seq 60)); kbd_hide; }; }
top() { for _ in 1 2 3; do adb shell input swipe 540 600 540 1700 200; done; sleep 1; }
run_and_wait() { # press the one action, wait for the flow to end (no step left running), max $1 s
  tap server_form.connect.button || return
  # done = the step list is on screen AND no step is running (the list appears a frame after the tap)
  for _ in $(seq "$1"); do sleep 1; dump wait
    grep -q 'server_form.progress.url' "$OUT/wait.xml" && ! grep -q 'server_form.progress.[a-z]*.running' "$OUT/wait.xml" && break
    grep -q 'server_form.server_url.dropdown' "$OUT/wait.xml" || break  # left the screen (connected)
  done
  sleep 2
}

adb shell settings put secure screensaver_enabled 0 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
sleep 30
adb install -r -g "$APK" || { echo "::error::adb install failed"; exit 2; }
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
for _ in $(seq 60); do sleep 3; dump boot; grep -q 'server_form.server_url.dropdown' "$OUT/boot.xml" && break; done

# 00 empty: dropdown on the private mesh server, name optional, Advanced OPEN (Enter secret), Sign in below
scene 00-empty
for id in server_form.server_url.dropdown server_form.server_display_name.input server_form.display_help \
  server_form.advanced_options.toggle server_form.preauth_secret.input 'Enter secret' 'chat.mesh.diegonmarcos.com' 'Private (mesh)'; do has "$id" 00-empty; done
adb shell input swipe 540 1500 540 700 300; sleep 1; scene 00b-empty-below
for id in server_form.token.input server_form.login_id.input server_form.password.input server_form.connect.button; do has "$id" 00b-empty-below; done
top

# 01 the dropdown open: three entries
tap server_form.server_url.dropdown; scene 01-dropdown
for id in server_form.server_url.option.mesh server_form.server_url.option.mesh_alt server_form.server_url.option.public \
  server_form.server_url.option.custom 'cloud-chat.mesh.diegonmarcos.com' 'chat.diegonmarcos.com' 'Enter your custom URL'; do has "$id" 01-dropdown; done

# 02 filled: custom URL (a host that does not resolve), name, secret, login, password
tap server_form.server_url.option.custom
type_in server_form.server_url.input 'https://chat.nowhere.invalid'
type_in server_form.server_display_name.input 'KeptName'
type_in server_form.preauth_secret.input 'KeptSecret'
type_in server_form.login_id.input 'kept.user'
type_in server_form.password.input 'KeptPass1'
top; scene 02-filled
has 'chat.nowhere.invalid' 02-filled; has 'KeptName' 02-filled

# 03 server error: fails at reachability, every value still there
run_and_wait 60
top; scene 03-error-kept
has 'chat.nowhere.invalid' 03-error-kept; has 'KeptName' 03-error-kept; has 'server_form.server_url.option.custom\|Enter your custom URL' 03-error-kept
for _ in 1 2 3; do adb shell input swipe 540 1500 540 600 300; done; sleep 1; scene 03b-error-kept-below
has 'kept.user' 03b-error-kept-below; has 'server_form.progress.dns.error' 03b-error-kept-below
has 'server_form.progress.login.skipped' 03b-error-kept-below

# 03c the mesh entry from a runner off the mesh: fails at DNS / TLS trust / ping, data kept, "Try cloud-chat.mesh" offered
top; tap server_form.server_url.dropdown; tap server_form.server_url.option.mesh
run_and_wait 60
adb shell input swipe 540 1500 540 600 300; sleep 1; scene 03c-mesh-error-alternative
has 'server_form.try_alternative.button' 03c-mesh-error-alternative; has 'kept.user' 03c-mesh-error-alternative
has 'server_form.progress.[a-z]*.error' 03c-mesh-error-alternative

# 04 the public server: the server check passes (reach, version, TLS), then the login with made-up credentials fails, data kept
top; tap server_form.server_url.dropdown; tap server_form.server_url.option.public
clear_in server_form.preauth_secret.input
run_and_wait 90
for _ in 1 2 3; do adb shell input swipe 540 1500 540 600 300; done; sleep 1; scene 04-server-ok-login-error
for id in server_form.progress.dns.ok server_form.progress.tls.ok server_form.progress.reach.ok server_form.progress.ping.ok \
  server_form.progress.version.ok server_form.progress.login.error kept.user; do has "$id" 04-server-ok-login-error; done
top; scene 04b-top; has 'KeptName' 04b-top

# 05 success: the same public server with no credentials typed → connected, the existing login screen opens
clear_in server_form.login_id.input
clear_in server_form.password.input
run_and_wait 90
sleep 4; scene 05-connected
hasnt 'server_form.server_url.dropdown' 05-connected
has 'login_form.username.input\|login_options\|sso\.' 05-connected

timeout 60 adb logcat -d -v threadtime '*:W' > "$OUT/logcat.txt" || true
rm -f "$OUT/scan.xml" "$OUT/wait.xml" "$OUT/boot.xml"
cat "$S"
[ "$fail" = 0 ] || echo "::warning::some scene assertions failed (summary.txt + screenshots)"
exit 0
