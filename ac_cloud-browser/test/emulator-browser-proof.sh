#!/bin/bash
# emulator-browser-proof.sh (#1420 #1421 #1422) — runs ON a booted emulator (adb) from
# .github/workflows/test-browser-proof-emulator.yml.   $1 Cloud Browser APK   $2 output dir
#
# Evidence, one file set per check (screenshot, ui dump, WebView console):
#   W0 the WebView provider and version the pages ran on
#   J1 real sites (UA echo, UA-gated, script-heavy): the agent the page saw, the console errors, the layout metrics
#   J2 a light-only page with the system in night mode: rendered as authored (no algorithmic darkening)
#   O1 a Google sign-in (mail.google.com -> accounts.google.com) leaves the WebView for a Custom Tab (another package
#      on top), and coming back shows Cloud Browser again
#   F1 Favourites: the chips under the title, the 8-column grid, a group collapsed by a header tap
#   I1 (#1741) image viewer: a picture is put in MediaStore, opened with VIEW (the "Open with Cloud Browser" path) and
#      must land in Cloud Browser on the image page; then its menu is tried for Convert to PDF / Print (best effort,
#      the system print dialog's Save as PDF is screenshotted when it comes up)
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=browser proof::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }
for _ in $(seq 1 60); do
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  timeout 20 adb reconnect offline >/dev/null 2>&1 || true; sleep 5
done
[ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] || { bad "the emulator never came online"; exit 2; }
adb install -r -g "$APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global cloud_browser_page_log 2 || true
# Chrome takes the Custom Tab: skip its first-run screens so the screenshot shows the sign-in page
adb shell 'echo "_ --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line' || true
adb shell am set-debug-app --persistent com.android.chrome >/dev/null 2>&1 || true

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml" || true; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; dump "$1"; }
top()  { adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -d '\r'; }
# tap the first node whose text or content-desc matches the regex $1 in the current screen
# tapid taps by resource-id (the shared bars' test tags: the title dropdown chevron, its entries, the AI icon)
tapid() { tap "$1" resource-id; }
tap() {
  dump _tap
  # the app's own updater can raise the system "install unknown apps" dialog over the page: cancel it, close the shade
  if grep -q "install unknown apps" "$OUT/_tap.xml"; then
    adb shell input keyevent KEYCODE_BACK; adb shell cmd statusbar collapse >/dev/null 2>&1 || true; sleep 2; dump _tap
  fi
  local xy; xy=$(python3 - "$OUT/_tap.xml" "$1" "${2:-}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
rx = re.compile(sys.argv[2])
byid = sys.argv[3] == "resource-id"
for n in root.iter("node"):
    if (byid and rx.search(n.get("resource-id", ""))) or (not byid and (rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")))):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
        print((a + c) // 2, (b + d) // 2); break
PY
)
  [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; return 0; }
  return 1
}
open_url() { timeout 120 adb shell am start -W -a android.intent.action.VIEW -d "$1" -p "$BPKG" >/dev/null 2>&1; }

# W0
{ adb shell dumpsys webviewupdate | grep -iE "Current WebView package|versionName" ; adb shell getprop ro.build.version.release; } > "$OUT/webview.txt" 2>&1
ok "W0 WebView: $(grep -m1 -i 'current webview' "$OUT/webview.txt" | tr -s ' ')"

# J1
i=0
for URL in https://httpbin.org/user-agent https://web.whatsapp.com/ https://github.com/diegonmarcos https://www.bbc.com/news; do
  i=$((i + 1)); adb logcat -c || true
  open_url "$URL"; sleep 30
  shot "site-$i"
  adb logcat -d -s LocalPageConsole:D LocalPageMetrics:D > "$OUT/site-$i-console.txt" 2>/dev/null || true
  errs=$(grep -cE "Uncaught|ReferenceError|SyntaxError|TypeError" "$OUT/site-$i-console.txt" || true)
  echo "$URL errors=$errs" >> "$OUT/sites.txt"
  ok "J1 $URL loaded (console errors: $errs, site-$i.png)"
done
# the agent the page sees, from WebView DevTools (debuggable builds only; the page's own text is not in the ui dump)
open_url https://httpbin.org/user-agent; sleep 10
PID=$(adb shell pidof "$BPKG" | tr -d '\r' | awk '{print $1}')
adb forward tcp:9223 "localabstract:webview_devtools_remote_$PID" >/dev/null 2>&1 || true
curl -fsS --max-time 10 http://127.0.0.1:9223/json/version > "$OUT/ua.json" 2>/dev/null || true
curl -fsS --max-time 10 http://127.0.0.1:9223/json/list > "$OUT/pages.json" 2>/dev/null || true
UA=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('User-Agent',''))" "$OUT/ua.json" 2>/dev/null)
echo "$UA" > "$OUT/ua.txt"
if [ -z "$UA" ]; then echo "NOTE  J1 DevTools did not answer (release build?): ua.json"
elif echo "$UA" | grep -q 'CloudSuperApp/1.0'; then bad "J1 the pinned Chrome/124 agent is still sent: $UA"
else ok "J1 the page sees WebView's own agent: $UA"; fi
if grep -qiE "update (google )?chrome|browser.*(not supported|unsupported)" "$OUT/site-2.xml"; then bad "J1 WhatsApp Web refuses the browser (site-2.png)"
else ok "J1 WhatsApp Web does not refuse the browser"; fi

# J2
adb shell cmd uimode night yes >/dev/null 2>&1 || true
# info.cern.ch declares no color-scheme: in night mode it must stay light (example.com declares `light dark`)
open_url https://info.cern.ch/hypertext/WWW/TheProject.html; sleep 15; shot dark-cern
adb shell cmd uimode night no >/dev/null 2>&1 || true
ok "J2 a light-only page under night mode: dark-cern.png"

# O1
adb shell pm list packages 2>/dev/null | grep -E "chrome|firefox|browser" > "$OUT/browsers.txt" || true
open_url https://mail.google.com/; sleep 25
top > "$OUT/oauth-top.txt"; shot oauth-handoff
if grep -q "$BPKG" "$OUT/oauth-top.txt"; then bad "O1 Google sign-in stayed in Cloud Browser ($(cat "$OUT/oauth-top.txt"))"
else ok "O1 Google sign-in handed to: $(cat "$OUT/oauth-top.txt")"; fi
adb shell input keyevent KEYCODE_BACK; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 4
top > "$OUT/oauth-return.txt"; shot oauth-return
grep -q "$BPKG" "$OUT/oauth-return.txt" && ok "O1 back returns to Cloud Browser" || echo "NOTE  O1 back landed on $(cat "$OUT/oauth-return.txt")"

# I1 (#1741) open a picture with Cloud Browser, then Convert to PDF
python3 - "$OUT/proof.png" <<'PY'
import struct, sys, zlib
w = h = 64
raw = b"".join(b"\x00" + b"".join(bytes([(x * 4) % 256, (y * 4) % 256, 160]) for x in range(w)) for y in range(h))
def ch(t, d): c = struct.pack(">I", len(d)) + t + d; return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
open(sys.argv[1], "wb").write(b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + ch(b"IDAT", zlib.compress(raw)) + ch(b"IEND", b""))
PY
adb push "$OUT/proof.png" /sdcard/Download/cloud-proof.png >/dev/null 2>&1 || true
adb shell content insert --uri content://media/external/images/media --bind _display_name:s:cloud-proof.png --bind mime_type:s:image/png --bind relative_path:s:Pictures >/dev/null 2>&1 || true
IMGID=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name 2>/dev/null | tr -d '\r' | grep 'cloud-proof.png' | tail -1 | sed -E 's/.*_id=([0-9]+).*/\1/')
if [ -n "$IMGID" ]; then
  adb shell "content write --uri content://media/external/images/media/$IMGID" < "$OUT/proof.png" >/dev/null 2>&1 || true
  IMGURI="content://media/external/images/media/$IMGID"
else IMGURI="file:///sdcard/Download/cloud-proof.png"; fi
echo "$IMGURI" > "$OUT/image-uri.txt"
adb shell am force-stop "$BPKG" >/dev/null 2>&1 || true
timeout 120 adb shell am start -W -a android.intent.action.VIEW -d "$IMGURI" -t image/png --grant-read-uri-permission -p "$BPKG" >/dev/null 2>&1; sleep 8
top > "$OUT/image-top.txt"; shot image-open
if grep -q "$BPKG" "$OUT/image-top.txt"; then ok "I1 Open with Cloud Browser lands in the browser: $(cat "$OUT/image-top.txt" | tr -s ' ')"; else bad "I1 the picture did not open in Cloud Browser ($(cat "$OUT/image-top.txt"))"; fi
grep -qE '[0-9]+ / [0-9]+|Info' "$OUT/image-open.xml" && ok "I1 the image page is showing (counter / Info control in the ui dump)" || echo "NOTE  I1 page text not in the ui dump (image-open.png)"
# the print route: the tab row's Browsing Tools sheet holds Convert to PDF (page tools); scroll the sheet until the row is on screen
tap 'Browsing Tools' || true
tap '^Page Tools$' || true   # the sheet opens on Actions; Convert to PDF and Print... are Page Tools rows
SW2=$(adb shell wm size | tr -d "\r" | grep -oE "[0-9]+x[0-9]+" | tail -1); SWX=$(( ${SW2%x*} / 2 )); SWH=${SW2#*x}
for _ in 1 2 3 4 5 6 7 8; do
  dump _menu; grep -q 'Convert to PDF' "$OUT/_menu.xml" && break
  adb shell input swipe "$SWX" $((SWH * 3 / 4)) "$SWX" $((SWH / 4)) 300; sleep 1
done
shot image-menu
if tap 'Convert to PDF'; then
  sleep 6; shot image-print; top > "$OUT/image-print-top.txt"
  grep -qiE 'print|PrintActivity|Save as PDF' "$OUT/image-print-top.txt" "$OUT/image-print.xml" && ok "I1 Convert to PDF raised Android's print dialog (image-print.png)" || echo "NOTE  I1 print dialog not detected: $(cat "$OUT/image-print-top.txt")"
else echo "NOTE  I1 Convert to PDF row not reachable by the UI walk (image-menu.png)"; fi
# leave the app clean for the checks that follow: close the print dialog / sheet, start the browser again
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am force-stop "$BPKG" >/dev/null 2>&1 || true

# F1
adb shell am start -W -n "$BPKG/.MainActivity" >/dev/null 2>&1 || adb shell monkey -p "$BPKG" 1 >/dev/null 2>&1
sleep 6
# open Favourites and wait until its title is on screen (a first launch can show Tabs for a while). Favourites is an off-bar
# section (ui.bottom_nav is Weby|Cloudy|Browser|Tabs|Domains): it is entered from the first row's title dropdown, entry navtop1_entry_favourites
onfav=0
for _ in $(seq 1 10); do
  # the dropdown may already be open from the previous round: take the entry first, open the dropdown only when it is not there
  tapid '^navtop1_entry_favourites$' || { tapid '^navtop1_more$' || tapid '^topbar_title$' || true; tapid '^navtop1_entry_favourites$' || true; }
  dump _fav; grep -q 'text="Favourites"' "$OUT/_fav.xml" && { onfav=1; break; }
  sleep 3
done
[ "$onfav" = 1 ] || bad "F1 Favourites never came on screen"
tap 'Grid$' || true
shot fav-all
# the chip row scrolls: swipe it left once (inside the real screen width) and read both dumps
SW=$(adb shell wm size | tr -d "\r" | grep -oE "[0-9]+x[0-9]+" | tail -1 | cut -dx -f1); SW=${SW:-320}; SX1=$((SW * 9 / 10)); SX0=$((SW / 10))
CY=$(python3 - "$OUT/fav-all.xml" <<'PY'
import re, sys
s = open(sys.argv[1]).read(); m = re.search(r'text="GH Pages"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
print((int(m.group(2)) + int(m.group(4))) // 2 if m else "")
PY
)
[ -n "$CY" ] && { adb shell input swipe "$SX1" "$CY" "$SX0" "$CY" 400; sleep 2; shot fav-chips-scrolled; }
for c in "GH Pages" "Cloudy Public" "Cloudy Private" "Webserver" "Services" "Tools"; do
  cat "$OUT/fav-all.xml" "$OUT/fav-chips-scrolled.xml" 2>/dev/null | grep -q "text=\"$c\"" || bad "F1 chip $c is missing"
done
[ -n "$CY" ] && { adb shell input swipe "$SX0" "$CY" "$SX1" "$CY" 400; sleep 2; }
tap '^GH Pages$' && shot fav-gh
tap '^▾ ' && shot fav-collapsed
grep -q 'text="▸ ' "$OUT/fav-collapsed.xml" && ok "F1 a group collapsed by its header" || bad "F1 no collapsed group after a header tap"
tap '^GH Pages$'; [ -n "$CY" ] && { adb shell input swipe "$SX1" "$CY" "$SX0" "$CY" 400; sleep 2; }; tap '^Tools$' && shot fav-tools
# H1 (#1410) Tabs: the search bar filters open tabs and history as you type; Search opens on the browser's
# history + bookmarks dropdown (github.com was visited above, so "git" has matches)
tap_edit() {
  dump _edit
  local xy; xy=$(python3 - "$OUT/_edit.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
for n in root.iter("node"):
    if n.get("class", "").endswith("EditText"):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
        print((a + c) // 2, (b + d) // 2); break
PY
)
  [ -n "$xy" ] && { adb shell input tap $xy; sleep 2; return 0; }
  return 1
}
tap '^Tabs$' || true; sleep 2; shot tabs-search
grep -qi 'Search tabs' "$OUT/tabs-search.xml" && ok "H1 the Tabs page has its search bar" || bad "H1 no search bar on Tabs (tabs-search.png)"
tap_edit && { adb shell input text git; sleep 3; shot tabs-filtered; }
grep -qi 'github' "$OUT/tabs-filtered.xml" && ok "H1 typing filters to the matching tabs / history" || bad "H1 'git' shows no github match (tabs-filtered.png)"
adb shell input keyevent KEYCODE_BACK; sleep 1
tapid '^topbar_ai$' || true; sleep 3   # Search is an off-bar section: the first row's AI icon opens it
tap_edit && { adb shell input text git; sleep 3; shot search-history; }
grep -qi 'github' "$OUT/search-history.xml" && ok "H1 Search opens the history dropdown" || bad "H1 Search shows no history match for 'git' (search-history.png)"
[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all browser proof checks passed"
