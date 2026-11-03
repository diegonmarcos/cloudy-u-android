#!/bin/bash
# emulator-scrapers.sh (#1426 #1427 #1428) — runs ON a booted emulator (adb) from .github/workflows/test-scrapers-emulator.yml.
#
#   $1 Cloud Browser APK   $2 output dir (screenshots, ui dumps, logcat)   $3 page to visit first (fills the WebView cache)
#
#   S1 the island shows Scrapers (not Configs) and a tap opens the Scrapers section on Site Files     -> 1-scrapers.png
#   S2 the visited site is a folder in Site Files; a tap drops down its cached files                   -> 2-site-files-expanded.png
#   S3 a tapped css/js/json/html file opens in the in-app viewer                                       -> 3-file-open.png
#   S4 Scrap Tools shows the YouTube Download row with the rights notice, As and Download to choices    -> 4-youtube-tool.png
set -u
APK="$1"; OUT="$2"; URL="${3:-https://diegonmarcos.github.io/linktree_icon-view}"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser
HOST=$(echo "$URL" | sed -E 's#^[a-z]+://##; s#/.*##; s#^www\.##')
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=scrapers emulator test::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }

for _ in $(seq 1 60); do
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  timeout 20 adb reconnect offline >/dev/null 2>&1 || true; sleep 5
done
[ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] || { bad "S0 the emulator never came online"; exit 2; }
adb logcat -c || true
adb install -r -g "$APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; dump "$1"; }
# tap the first node whose text or content-desc matches the regex $2 in dump $1; prints the matched text
tap() {
  python3 - "$OUT/$1.xml" "$2" <<'PY' > /tmp/tap.txt
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
rx = re.compile(sys.argv[2])
for n in root.iter("node"):
    for k in ("text", "content-desc"):
        v = n.get(k) or ""
        if v and rx.search(v):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2, v); sys.exit(0)
sys.exit(1)
PY
  [ $? -eq 0 ] || return 1
  read -r X Y T < /tmp/tap.txt; adb shell input tap "$X" "$Y"; echo "$T"
}

# Fill the WebView cache with a real site (html, css, js, json, images).
timeout 120 adb shell am start -W -a android.intent.action.VIEW -d "$URL" -p "$BPKG" >/dev/null || bad "S0 the emulator did not take the start command"
sleep 20
# A first run opens on the tab switcher, not the page: open the tab that holds the URL (a pinned tab), then wait for it.
dump 0-tabs
if grep -q 'Normal Tabs' "$OUT/0-tabs.xml"; then
  tap 0-tabs "^${URL%/}/?\$" >/dev/null || tap 0-tabs "$HOST" >/dev/null || echo "NOTE  no tab for $URL on the switcher"
fi
sleep 40
adb exec-out screencap -p > "$OUT/0-page.png" 2>/dev/null || true
dump 0-page
if tap 0-page '^Scrapers$' >/dev/null; then ok "S1 the island has Scrapers"; else bad "S1 no Scrapers item on the island"; fi
grep -q 'text="Configs"' "$OUT/0-page.xml" && ! grep -q 'text="Scrapers"' "$OUT/0-page.xml" && bad "S1 the island still shows Configs"
sleep 4; shot 1-scrapers
if grep -q 'Site Files' "$OUT/1-scrapers.xml" && grep -q 'Scrap Tools' "$OUT/1-scrapers.xml"; then ok "S1 Scrapers shows the Site Files and Scrap Tools tabs"; else bad "S1 the Site Files / Scrap Tools tabs are missing"; fi

if tap 1-scrapers "^${HOST//./\\.}\$" >/dev/null; then
  sleep 3; shot 2-site-files-expanded
  if grep -q 'Cache ·' "$OUT/2-site-files-expanded.xml"; then ok "S2 $HOST drops down its cached files"; else bad "S2 $HOST shows no cached file after the tap"; fi
  if F=$(tap 2-site-files-expanded '\.(css|js|json|html)$'); then
    sleep 5; shot 3-file-open
    if grep -q '‹ Back' "$OUT/3-file-open.xml"; then ok "S3 '$F' opened in the in-app viewer"; else bad "S3 '$F' did not open the viewer"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
  else bad "S3 no css/js/json/html file listed under $HOST"; shot 3-file-open; fi
else
  bad "S2 the visited site $HOST is not a folder in Site Files"; shot 2-site-files-expanded
fi

dump 4-pre
if tap 4-pre '^Scrap Tools$' >/dev/null; then
  sleep 3; dump 4-tools
  tap 4-tools 'YouTube link' >/dev/null && adb shell input text 'https://youtu.be/dQw4w9WgXcQ' && adb shell input keyevent KEYCODE_BACK
  sleep 2; shot 4-youtube-tool
  X="$OUT/4-youtube-tool.xml"
  if grep -q 'YouTube Download' "$X" && grep -q 'Only download content you have rights to' "$X" && grep -q 'Audio from video' "$X" && grep -q 'Downloads' "$X"; then
    ok "S4 YouTube Download: notice, As and Download to are on screen"
  else bad "S4 the YouTube Download row is incomplete"; fi
else bad "S4 no Scrap Tools tab"; shot 4-youtube-tool; fi

adb logcat -d -v threadtime > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && bad "the app crashed (logcat.txt)"
[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all scrapers emulator checks passed"
