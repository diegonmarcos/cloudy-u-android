#!/usr/bin/env bash
# #1710 emulator walk of Cloud C3 Webserver > Pages: All, File Explorer, Pages (screenshots), a root's action set, the File Explorer in the
# framework view (the shared server's own UI) and in the tree view, and the Open Folder Browser / Embedded chooser. Evidence is the PNG set.
# Usage: emulator-pages-explorer.sh WEBSERVER_APK OUT_DIR
set -u
WS_APK="$1"; OUT="$2"; W=com.diegonmarcos.cloudwebserver
mkdir -p "$OUT"
adb install -r -g "$WS_APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
shoot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
tap_re() {  # tap the nth (default first) node whose text / content-desc starts with the regex $1; 0 when found
  dump cur
  b=$(grep -oE "(text|content-desc)=\"($1)[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/cur.xml" | { if [ "${2:-1}" = L ]; then tail -1; else sed -n "${2:-1}p"; fi; } | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
has() { dump cur; grep -q "$1" "$OUT/cur.xml"; }
rc=0; fail() { echo "::error::$*"; rc=1; }
# shared storage readable by the app, and files for every renderer: Markdown, JSON, text
adb shell appops set "$W" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
adb shell mkdir -p /sdcard/c3-proof/docs
printf '# Proof page\n\nA **Markdown** file rendered by the shared server.\n\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n' > "$OUT/README.md"
printf '{"app":"c3-webserver","roots":["termux","nix","emulated"],"ok":true,"n":3}\n' > "$OUT/data.json"
adb push "$OUT/README.md" /sdcard/c3-proof/README.md >/dev/null; adb push "$OUT/data.json" /sdcard/c3-proof/data.json >/dev/null
adb shell am force-stop "$W"; adb shell input keyevent KEYCODE_HOME
adb logcat -c; adb shell am start -W -n "$W/.MainActivity" >/dev/null; sleep 10
for i in 1 2 3; do tap_re "Not now|Skip|Later|Dismiss|Cancel|Allow|While using" && sleep 2; done
tap_re "Pages" L || fail "no Pages tab"; sleep 4
shoot 01-pages-all; dump 01-pages-all
has "Open in Browser" || fail "All: a root row has no Open in Browser"
has "Open Embedded" || fail "All: a root row has no Open Embedded"
has "Open Folder" || fail "All: the drive root has no Open Folder (Drive)"
# a swipe up shows the served pages below the roots
adb shell input swipe 540 1700 540 600 300; sleep 2; shoot 02-pages-all-served
adb shell input swipe 540 600 540 1700 300; sleep 1
# the Open Folder chooser (Browser vs Embedded)
tap_re "Open Folder" && sleep 3 || fail "no Open Folder (Drive) chip"
shoot 03-link-chooser; has "Embedded" || fail "the chooser does not offer Embedded"
tap_re "Cancel" && sleep 1
# File Explorer: the list, then the framework view of Emulated storage
tap_re "File Explorer" 1 && sleep 3 || fail "no File Explorer sub page"
shoot 04-explorer-roots
tap_re "▶ Explore" L || fail "no Explore chip on a ready root"; sleep 9
shoot 05-explorer-framework; dump 05-explorer-framework
has "Tree view" || fail "the explorer has no Tree view switch (framework view not the default)"
# the tree view
tap_re "Tree view" && sleep 4; shoot 06-explorer-tree
has "c3-proof\|Android\|DCIM" || echo "note: tree shows no known folder"
tap_re "‹ Roots" && sleep 2
# Pages sub page (second row; the label is "Pages" and is the first match on that row)
tap_re "Pages" 1 && sleep 1; shoot 07-pages-browser
adb logcat -d -t 1500 > "$OUT/logcat.txt" 2>/dev/null || true
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$W" && fail "the app crashed during the walk"
exit $rc
