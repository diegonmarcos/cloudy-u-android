#!/bin/bash
# #1837 offline proof (workflow test-mail-core-offline-emulator.yml): the test variant is pointed at a fake JMAP server on the runner
# (test/fake-jmap-server.py, reached at 10.0.2.2), syncs it ONLINE through the Rust mail core, then the network is cut (the server is
# killed by pid and the emulator goes to airplane mode), the app is relaunched, and the Inbox, a second folder and an opened message
# are screenshotted and asserted through uiautomator dumps: the seeded subjects show and the reader shows "España" (the Latin-1 message).
# Needs $PKG and a running emulator. Evidence in $OUT (the workflow uploads it). Exit 1 when any assertion failed, after ALL evidence is taken.
set -u
OUT=${OUT:-walk-out-core}
PORT=8099
UI="python3 -I ac_cloud-mail/test/mail-offline-ui.py"
FAIL=0
mkdir -p "$OUT"
fail() { echo "::error::$1"; echo "FAIL: $1" >> "$OUT/summary.txt"; FAIL=1; }
note() { echo "$1" | tee -a "$OUT/summary.txt"; }

python3 -I ac_cloud-mail/test/fake-jmap-server.py --port $PORT > "$OUT/fake-jmap.log" 2>&1 &
SRV=$!
sleep 2
curl -s -m 5 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-00-start.json" || { fail "the fake JMAP server did not start"; exit 1; }

adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
sleep 45 # let the launcher settle after boot
adb install -r -g app-under-test.apk || { echo "::error::adb install failed"; exit 2; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb logcat -v threadtime "*:W" MailCore:I MailSync:I NavWalkSeed:I MailOpen:I MailPrefetch:I > "$OUT/logcat-online.txt" 2>&1 &
LP=$!

# the app's own database, copied out through run-as (a debug build), printed by mail-offline-db.py: the cached rows and the memberships
dbdump() { # $1 = name
  mkdir -p "$OUT/db-$1"
  for f in sterna.db sterna.db-wal sterna.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$OUT/db-$1/$f" 2>/dev/null || true; done
  python3 -I ac_cloud-mail/test/mail-offline-db.py "$OUT/db-$1" > "$OUT/db-$1.txt" 2>&1 || true
}
shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; adb exec-out screencap -p > "$OUT/$1.png"; }
dump() {
  for round in 1 2 3; do
    for t in 1 2 3 4 5 6; do
      adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      adb exec-out cat /sdcard/ui.xml > "$OUT/$1.xml"; grep -q "<hierarchy" "$OUT/$1.xml" && break; sleep 3
    done
    $UI texts "$OUT/$1.xml" > "$OUT/$1.texts.txt" 2>/dev/null || true
    # a system ANR dialog (the emulator's launcher is slow) hides the app from uiautomator: press Wait and dump again
    if grep -q "isn't responding" "$OUT/$1.texts.txt"; then
      pt=$($UI tap "$OUT/$1.xml" "Wait" exact) && adb shell input tap $pt; sleep 2
    else
      break
    fi
  done
}
# tap the first node starting with $1 ("exact" as $2: equal to it) in a fresh dump
tapt() { dump scan; pt=$($UI tap "$OUT/scan.xml" "$1" "${2:-}") || return 1; adb shell input tap $pt; }
skip_welcome() { if tapt "Skip" exact; then sleep 4; fi; }
# The folders are a vertical rail with three-letter labels (Inb, Dra, Sen, Tra, Spa, Arc, Rec), not a drawer.
go_folder() { # $1 = the folder's name; $2 = seconds to let it load
  case "$1" in Inbox) lbl=Inb;; Archive) lbl=Arc;; Receipts) lbl=Rec;; *) lbl="$1";; esac
  tapt "$lbl" exact || { fail "no $1 ($lbl) entry in the folder rail"; return 1; }
  sleep "${2:-8}"
}

adb shell am broadcast -n "$PKG/app.sterna.navwalk.NavWalkSeedReceiver" -a app.sterna.test.navwalk.SEED --es jmap "http://10.0.2.2:$PORT"
sleep 3
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 15
skip_welcome
# ---- online: the sync through the Rust core fills the list --------------------------------------------------------------------
synced=0
for i in $(seq 1 24); do
  dump 01-online-inbox
  if $UI has "$OUT/01-online-inbox.xml" "Weekly Digest 42" >/dev/null 2>&1; then synced=1; break; fi
  skip_welcome; sleep 5
done
shot 01-online-inbox
[ "$synced" = 1 ] || fail "the online sync never showed the seeded Inbox (see 01-online-inbox)"
sleep 20 # the core's body prefetch and the worker's pass over every folder
go_folder "Archive" 10; shot 02-online-archive; dump 02-online-archive
go_folder "Receipts" 10; shot 03-online-receipts; dump 03-online-receipts
$UI has "$OUT/03-online-receipts.xml" "Label only: Warranty card" "Receipt 1001 from Shop" \
  || fail "the online Receipts label must list its own message AND the Inbox message that is also in the label"
go_folder "Inbox" 5; shot 04-online-inbox-again
dbdump online
curl -s -m 5 "http://127.0.0.1:$PORT/__stats" > "$OUT/stats-01-online.json"
note "fake JMAP server after the online sync: $(cat "$OUT/stats-01-online.json")"
python3 -I - "$OUT/stats-01-online.json" <<'PY' || fail "the app did not sync against the fake server (no API calls or no raw message downloads)"
import json, sys
s = json.load(open(sys.argv[1]))
sys.exit(0 if s["api"] > 0 and s["download"] > 0 else 1)
PY

# ---- cut the network, relaunch --------------------------------------------------------------------------------------------------
kill "$SRV" 2>/dev/null; wait "$SRV" 2>/dev/null
if curl -s -m 3 "http://127.0.0.1:$PORT/__stats" >/dev/null 2>&1; then fail "the fake server is still answering"; fi
adb shell cmd connectivity airplane-mode enable || true
adb shell svc wifi disable || true
adb shell svc data disable || true
sleep 6
note "network cut: the fake JMAP server is killed (connection refused) and the emulator is in airplane mode"
kill "$LP" 2>/dev/null
adb logcat -c
adb logcat -v threadtime "*:W" MailCore:I MailSync:I MailOpen:I > "$OUT/logcat-offline.txt" 2>&1 &
LP=$!
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 14
skip_welcome
dump 05-offline-inbox; shot 05-offline-inbox
$UI has "$OUT/05-offline-inbox.xml" "España: reunión de equipo" "Weekly Digest 42 - Newsletter" "Receipt 1001 from Shop" "Plain hello 1" \
  || fail "the offline Inbox does not show every seeded subject"
go_folder "Archive" 6; dump 06-offline-archive; shot 06-offline-archive
$UI has "$OUT/06-offline-archive.xml" "Archived: Tax documents 2025" || fail "the offline Archive does not show its seeded message"
dbdump offline-before-receipts
go_folder "Receipts" 6; dump 07-offline-receipts; shot 07-offline-receipts
sleep 20; dump 07b-offline-receipts-later; shot 07b-offline-receipts-later; dbdump offline-after-receipts
# #1837 membership table: the label lists its own message AND the one that is also in the Inbox (filed under the Inbox)
$UI has "$OUT/07-offline-receipts.xml" "Label only: Warranty card" "Receipt 1001 from Shop" \
  || fail "the offline Receipts label must list its own message AND the Inbox message that is also in the label"
# #1837: offline, the list ends quietly (no "Couldn't load more" / Retry footer), and the top bar's path names the open folder
adb shell input swipe 540 1800 540 400 300; sleep 3; dump 07c-offline-receipts-bottom; shot 07c-offline-receipts-bottom
for d in 07-offline-receipts 07b-offline-receipts-later 07c-offline-receipts-bottom; do
  $UI lacks "$OUT/$d.xml" "load more" "Retry" || fail "offline Receipts ($d) shows the load-more error footer"
done
$UI has "$OUT/07-offline-receipts.xml" "Receipts" || fail "the top bar's breadcrumb does not name the open Receipts label (07)"
go_folder "Inbox" 5
if tapt "España: reunión"; then
  sleep 6
  dump 08-offline-reader-espana; shot 08-offline-reader-espana
  $UI has "$OUT/08-offline-reader-espana.xml" "España" || fail "the offline reader does not show España"
  if grep -q $'\xef\xbf\xbd' "$OUT/08-offline-reader-espana.texts.txt"; then fail "the Latin-1 message shows a replacement character"; fi
  $UI has "$OUT/08-offline-reader-espana.xml" "Bienvenidos a España" >/dev/null 2>&1 \
    && note "the reader body text itself is in the dump: Bienvenidos a España" \
    || echo "::warning::the body paragraph is not in the dump (a WebView body may not be exposed); the screenshot 08 shows it"
  adb shell input keyevent KEYCODE_BACK; sleep 2
else
  fail "the España message is not on screen to open offline"
fi
if tapt "Weekly Digest 42"; then
  sleep 6; dump 09-offline-reader-newsletter; shot 09-offline-reader-newsletter
  adb shell input keyevent KEYCODE_BACK; sleep 2
else
  fail "the newsletter is not on screen to open offline"
fi
timeout 60 adb logcat -d -v threadtime > "$OUT/logcat-offline-full.txt" || true
kill "$LP" 2>/dev/null
grep -h "MailCore" "$OUT/logcat-online.txt" "$OUT/logcat-offline.txt" | sort -u | head -60 > "$OUT/mailcore-log.txt" || true
grep -q "MailCore.*sync " "$OUT/logcat-online.txt" || fail "no Rust core sync line in the app's log: the core did not run (libmail_core.so missing?)"
if grep -q "core pass failed" "$OUT/logcat-online.txt"; then echo "::warning::a core pass failed in the online run (see mailcore-log.txt)"; fi
note "done: failures=$FAIL"
exit $FAIL
