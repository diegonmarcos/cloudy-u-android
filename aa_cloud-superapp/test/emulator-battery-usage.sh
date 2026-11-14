#!/usr/bin/env bash
# Emulator proof of Resources › Battery use per app (BatteryCycles), the Shizuku-free battery measurement, and (#1380)
# the Cloud ▸ Apps groups (cloud-apps.png), and the home battery popup + Battery stats page sections (home-battery-popup.png,
# battery-stats-N.png).
#   bash aa_cloud-superapp/test/emulator-battery-usage.sh <superapp.apk> <out dir>
# Installs the APK, clears the nav-mode override (global cloud_nav_ui_mode), drives one charge cycle with
# `dumpsys battery` (plugged at 90 % -> unplug -> 62 % -> plug in -> unplug), so PowerStateReceiver snapshots
# each edge, then photographs Resources (Cycle and Month) and About › Battery Usage Details. Fails when a cycle
# was not recorded or any screen it shows says "Shizuku". The emulator has no paired adb channel, so the per-app
# rows read "Not paired" and that row opens the ADB Shell page; the per-app mAh needs the channel (phone check).
set -u
APK=$1; OUT=$2; PKG=com.diegonmarcos.superapp
mkdir -p "$OUT"
fail=0
adb wait-for-device
# The screen must stay on and unlocked: a lock screen hid every page in run 41237928817.
wake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; }
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
wake
adb install -r -g "$APK" || { echo "::error::install failed"; exit 2; }
# Same setup as screenshot-baseline.yml: the SuperApp's own idle Screensaver (an overlay with an Unlock button) covered every
# shot of run 41298521502, so it gets no overlay grant; the SuperApp is the home app and system error dialogs stay hidden.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny || true
adb shell cmd package set-home-activity "$PKG/.HomeActivity" || true
# The home status strip (and its battery) draws only while the SuperApp HOLDS the launcher role (41306928951 had none).
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings delete global cloud_nav_ui_mode || true
adb shell settings put global adb_wifi_enabled 0 || true

# Swipes in fractions of the real screen: the CI AVD is 320x640, and 1080-px coordinates never scrolled (run 41299866795).
read -r SW SH < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
scroll() { adb shell input swipe $((SW / 2)) $((SH * $1 / 100)) $((SW / 2)) $((SH * $2 / 100)) 400; }
# A fresh dump every time (the old file removed first), so a failed dump cannot pass a stale screen.
dump() { local i; for i in 1 2 3; do adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; grep -q '<node' "$OUT/ui.xml" && return 0; sleep 2; done; }
# Centre of the first node whose text or content-desc equals $1 (empty when absent).
centre() { python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml, want = open(sys.argv[1], encoding="utf-8", errors="ignore").read(), sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if want in ((t.group(1) if t else None), (d.group(1) if d else None)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
# Scroll down until $1 is on screen; tap $2 (a collapsed card's heading) first when given and $1 is not found.
find_text() {
  local want=$1 opener=${2:-} i xy
  for i in $(seq 1 14); do
    dump; xy=$(centre "$want"); [ -n "$xy" ] && { echo "$xy"; return 0; }
    if [ -n "$opener" ]; then xy=$(centre "$opener"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 2; opener=""; continue; }; fi
    scroll 78 40; sleep 1.2
  done
  return 1
}
tap_text() { local xy; xy=$(find_text "$@") || return 1; adb shell input tap $xy; sleep 2; }
shot() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"
  if grep -qi shizuku "$OUT/$1.xml"; then echo "::error::$1 shows Shizuku"; fail=1; else echo "$1: no Shizuku text"; fi; }
open_page() { wake; adb shell am start -n "$PKG/.MainActivity" --es shortcut_action "$1" >/dev/null; sleep 7; }

# A launch first: a package that never ran is stopped and its manifest receivers get nothing.
adb shell dumpsys battery set level 90; adb shell dumpsys battery set ac 1
wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 10
# One cycle: unplug at 90 %, drain to 62 %, plug in (closes it); unplug again (opens the current one).
# 20 s per edge: the emulator delivers the plug broadcasts seconds late, and a level set before one arrives is booked
# at that edge (41310047797 booked 90% -> 62% as 62% -> 62%); a phone's level does not move that fast.
adb shell dumpsys battery unplug; sleep 20
adb shell dumpsys battery set level 62; sleep 3
adb shell dumpsys battery set ac 1; sleep 20
adb shell dumpsys battery set level 70; sleep 2
adb shell dumpsys battery unplug; sleep 6
adb shell dumpsys battery set level 66; sleep 2

open_page "page:boards/resources"
shot resources-top
if find_text "Battery use per app" "Battery" >/dev/null; then
  scroll 70 50; sleep 1.5
  shot resources-battery-cycle
  dump; grep -q "90% → 62% · −28%" "$OUT/ui.xml" || { echo "::error::the unplug-to-plug-in cycle (90% → 62%) is not listed"; fail=1; }
  if tap_text "Month"; then shot resources-battery-month; else echo "::error::no Month segment"; fail=1; fi
  # Not paired -> the row opens the ADB Shell page (the one place that pairs). That page is the channel's own and may
  # name Shizuku as a ladder option, so it is checked for the pairing controls instead of the no-Shizuku rule.
  if tap_text "Per-app mAh needs the ADB channel"; then
    sleep 3; adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/resources-battery-pair-opens-adb-shell.png" >/dev/null
    dump; cp "$OUT/ui.xml" "$OUT/resources-battery-pair-opens-adb-shell.xml"
    grep -qi "wireless debugging" "$OUT/ui.xml" || { echo "::error::the Not paired row did not open the ADB Shell page"; fail=1; }
    adb shell input keyevent KEYCODE_BACK; sleep 2
  else
    echo "::error::no 'Not paired' row"; fail=1
  fi
else
  echo "::error::Resources has no 'Battery use per app' block"; shot resources-missing; fail=1
fi

# About › Battery & Usage › Battery Usage Details: the exact per-app card is the ADB channel's, not Shizuku's.
open_page "page:config/about"
if tap_text "Battery Usage Details" "Battery & Usage"; then
  find_text "Per-app exact  ·  ADB channel" >/dev/null || { echo "::error::Usage Details has no ADB-channel card"; fail=1; }
  shot about-usage-details
else
  echo "::warning::About › Battery Usage Details not reached"; shot about-missing
fi

# #1380/#1627 Cloud ▸ Apps: groups render Primary, Communication, Data, Me (top to bottom). Primary leads with Drive;
# Communication carries Contacts; Data reads Note, Tasks, Office | Gallery, Media Center || Me, Vault.
open_page "page:cloud/apps"
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/cloud-apps.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/cloud-apps.xml"
scroll 70 40; sleep 1.5
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/cloud-apps-scrolled.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/cloud-apps-scrolled.xml"
# Each row is one horizontal strip of six tiles: Vault, the seventh Data tile (#1627), is reached by swiping that row left.
open_page "page:cloud/apps"; dump; xy=$(centre "Gallery")
if [ -n "$xy" ]; then set -- $xy; adb shell input swipe $((SW * 9 / 10)) "$2" $((SW / 10)) "$2" 400; sleep 1.5; fi
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/cloud-apps-data-right.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/cloud-apps-data-right.xml"
python3 - "$OUT/cloud-apps.xml" "$OUT/cloud-apps-scrolled.xml" "$OUT/cloud-apps-data-right.xml" <<'PY' || fail=1
import re, sys
seq = []
for p in sys.argv[1:3]:
    for t in re.findall(r' text="([^"]+)"', open(p, encoding="utf-8", errors="ignore").read()):
        if not seq or seq[-1] != t: seq.append(t)
def after(head, want, stop):
    if head not in seq: return None
    i = seq.index(head) + 1
    got = []
    while i < len(seq) and seq[i] != stop:
        if seq[i] in want and seq[i] not in got: got.append(seq[i])
        i += 1
    return got
# #1627 group order is Primary, Communication, Data, Me. Primary leads with Drive; Communication is followed by the
# Data header; Data's app tiles read Notes, Tasks, Office, Gallery, Media Center (the 'Me' tile + Vault close it after
# the '||' divider — note the Data 'Me' tile shares the Me group's header text, so Data stops at the header's sub-tile).
headers = [h for h in ("Primary", "Communication", "Data", "Me") if h in seq]
order_ok = headers == ["Primary", "Communication", "Data", "Me"]
prim = after("Primary", {"Drive", "Browser", "Calc", "Navigation", "Camera", "Keyboard", "Wallet"}, "Communication")
com = after("Communication", {"Mail", "Chat", "Messenger", "Dialer", "C3-Bot", "Contacts"}, "Data")
# Data ends on the 'Me' tile then Vault; the Me GROUP header is the 'Me' immediately followed by 'Me-Health'.
data = after("Data", {"Notes", "Tasks", "Office", "Gallery", "Media Center"}, "Me-Health")
print("Primary:", prim); print("Communication:", com); print("Data:", data)
right = re.findall(r' text="([^"]+)"', open(sys.argv[3], encoding="utf-8", errors="ignore").read())
# #1627 the Data row holds 7 app tiles (Notes, Tasks, Office, Gallery, Media Center, Me, Vault); Vault is the overflow
# tile past the sixth, reached by swiping the row left — the swipe above centres on Gallery and drags its row.
vault_revealed = "Vault" in right
print("Data swiped (overflow):", [t for t in right if t in {"Media Center", "Me", "Vault"}])
ok = (order_ok
      and prim is not None and prim[:1] == ["Drive"]
      and com is not None and "Contacts" in com
      and data is not None and data[:5] == ["Notes", "Tasks", "Office", "Gallery", "Media Center"]
      and vault_revealed)
if not ok: print("::error::Cloud > Apps groups are not as declared (#1380/#1627)")
sys.exit(0 if ok else 1)
PY

# The home-screen battery tap (status strip, opens: battery_popup) and its "Battery stats ›" page: every section is on
# screen (BatteryTruthTest pins them in source). The texts of every frame are kept, so a section below the fold counts.
cap() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null; dump; cp "$OUT/ui.xml" "$OUT/$1.xml"; }
has_all() { python3 - "$@" <<'PY'
import re, sys
files, want = [a for a in sys.argv[1:] if a.endswith(".xml")], [a for a in sys.argv[1:] if not a.endswith(".xml")]
# the dump is XML: "Temperature & voltage" is stored as &amp; (run 41302878487)
import html
text = "\n".join(html.unescape(t) for f in files for t in re.findall(r' text="([^"]+)"', open(f, encoding="utf-8", errors="ignore").read()))
miss = [w for w in want if not any(alt in text for alt in w.split("|"))]
print("present:", [w for w in want if w not in miss], "missing:", miss)
sys.exit(1 if miss else 0)
PY
}
wake; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 8; dump
# The relaunch resumes the last page (Cloud > Apps in 41302878487): the bottom bar's Home first, then the strip's battery
# (BatteryIconView, labelled "Battery status"; geometry picked the toolbar's buttons in 41299866795).
xy=$(centre "Home"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 3; dump; }
xy=$(centre "Battery status")
if [ -n "$xy" ]; then
  adb shell input tap $xy; sleep 2.5; cap home-battery-popup
  has_all "$OUT/home-battery-popup.xml" "Battery stats ›" "Now" "Since last charge|Since plugged in" || { echo "::error::the home battery popup is missing a section"; fail=1; }
  grep -q "Battery state unavailable" "$OUT/home-battery-popup.xml" && { echo "::error::the home battery popup says Battery state unavailable"; fail=1; }
  adb shell input keyevent KEYCODE_BACK; sleep 1
else
  # the strip draws only for the default launcher on the Cloud theme, which this AVD does not give it (41310047797);
  # the popup's sections are pinned in source by BatteryTruthTest, so this is a warning with its shot
  echo "::warning::no battery icon on the home status strip (strip not drawn on this AVD)"; cap home-no-battery-icon
fi
open_page "page:config/battery"
frames=""
for i in 1 2 3 4 5 6; do cap "battery-stats-$i"; frames="$frames $OUT/battery-stats-$i.xml"; scroll 80 30; sleep 1.2; done
has_all $frames "Now" "Since last charge|Since plugged in" "History" "Capacity" "Temperature & voltage" "Discharge cycles" "Charge sessions" "Top apps since charge" "Privileged channel" \
  || { echo "::error::the Battery stats page is missing a section"; fail=1; }

# Configs ▸ Observability (X-Ray | LogCat | Permissions | About): the row and each page, plus the old ids landing on X-Ray.
# A separate capture (xray-*.png + .xml); the battery checks above are not touched by it.
xray_capture() {
  local t xy
  open_page "section:config"; cap xray-configs-grid
  xy=$(centre "Observability"); [ -n "$xy" ] && { adb shell input tap $xy; sleep 2; }
  find_text "X-Ray" >/dev/null || echo "::warning::the Observability row (X-Ray) is not on screen"
  cap xray-observability-row
  has_all "$OUT/xray-configs-grid.xml" "$OUT/xray-observability-row.xml" "X-Ray" "LogCat" "Permissions" "About" \
    || { echo "::error::the Observability row does not read X-Ray | LogCat | Permissions | About"; fail=1; }
  open_page "page:config/xray"; cap xray-page
  grep -q "X-Ray · Cloud SuperApp" "$OUT/xray-page.xml" || { echo "::error::page:config/xray is not the X-Ray page"; fail=1; }
  for t in DIAGNOSTICS AGENTS; do
    if tap_text "$t"; then cap "xray-page-$(echo $t | tr A-Z a-z)"; else echo "::warning::X-Ray index has no $t card"; fi
  done
  open_page "page:config/app-about"; cap xray-about-page
  grep -q "About · Cloud SuperApp" "$OUT/xray-about-page.xml" || { echo "::error::page:config/app-about is not the About page"; fail=1; }
  grep -q "DEVICE" "$OUT/xray-about-page.xml" && { echo "::error::About draws X-Ray's DEVICE group"; fail=1; }
  open_page "page:config/perms"; cap xray-permissions-page
  for t in about diagnostics agents; do
    open_page "page:config/$t"; cap "xray-redirect-$t"
    grep -q "X-Ray · Cloud SuperApp" "$OUT/xray-redirect-$t.xml" || { echo "::error::page:config/$t does not land on X-Ray"; fail=1; }
  done
}
xray_capture

# #1403 Configs > Permissions (libs:perms): the filter chips, the actions row, a filter applied, and an action's result.
# The emulator has no paired adb channel, so the adb grant-alls are off (with their reason) and Copy Adb Grants is the
# result shown: the generated commands for everything missing.
perms_capture() {
  open_page "page:config/perms"; cap perms-toolbar
  has_all "$OUT/perms-toolbar.xml" "Missing" "Denied" "Allowed" "Grant all" "Ask Missing" "Adb Connect" \
    || { echo "::error::the Permissions page has no filter chips / actions row (#1403)"; fail=1; }
  if tap_text "Missing"; then cap perms-filter-missing; else echo "::error::no Missing chip"; fail=1; fi
  # the actions row scrolls sideways: swipe it left so the last chips (Grant All Adb, Copy Adb Grants) are on screen
  read -r W H <<<"$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')"
  adb shell input swipe $((W * 85 / 100)) $((H * 23 / 100)) $((W * 5 / 100)) $((H * 23 / 100)) 300; sleep 1; cap perms-actions-scrolled
  if tap_text "Copy Adb Grants"; then cap perms-copy-result
    grep -q 'adb shell ' "$OUT/perms-copy-result.xml" || { echo "::error::Copy Adb Grants shows no adb command"; fail=1; }
  else echo "::error::no Copy Adb Grants action"; fail=1; fi
  has_all "$OUT/perms-toolbar.xml" "$OUT/perms-filter-missing.xml" "$OUT/perms-copy-result.xml" "Grant All Adb missing" "Grant All (denied also)" "Copy Adb Grants" \
    || { echo "::error::the adb actions are missing (#1403)"; fail=1; }
  if tap_text "Grant all"; then sleep 2; cap perms-grant-all; adb shell input keyevent KEYCODE_BACK; sleep 1; fi
}
perms_capture

adb shell dumpsys battery reset
adb shell settings delete global cloud_nav_ui_mode || true
exit $fail
