#!/usr/bin/env bash
# emulator-assert.sh <out-dir> — the screenshot gate of the FinTerm emulator walk (#1370 "Compose screenshot tests"): the walk's Compose screens must
# exist as real (non-blank) captures and carry the UI the feature declares, read from the uiautomator dumps and the walk's own log. Exits 1 on any miss.
set -u
OUT="$1"; bad=0
fail() { echo "FAIL: $1"; bad=1; }
big() { [ -f "$OUT/$1" ] && [ "$(wc -c < "$OUT/$1")" -gt 30000 ] || fail "$1 is missing or blank (under 30 kB)"; }
for f in apk-watchlist-1.png apk-watchlist-menu.png apk-watchlist-moved.png apk-holdings-sheet.png apk-remote-search.png apk-deeplink.png; do big "$f"; done
# the walk found each control it drives (a "none" means the screen did not draw it)
for k in "watchlist AAPL xy=" "move down xy=" "holdings Add xy=" "search field xy=" "remote result xy="; do
  grep -q "^$k[0-9]" "$OUT/nav-strip.txt" || fail "walk log has no '$k<x> <y>' (control not drawn)"
done
[ "$(cat "$OUT/predictive-back-warnings.txt" 2>/dev/null)" = "0" ] || fail "logcat still warns OnBackInvokedCallback is not enabled"
if [ -f "$OUT/ui-rate_probabilities-first.xml" ]; then grep -q '0 (0 bps)' "$OUT/ui-rate_probabilities-first.xml" || fail "rate path: no '0 (0 bps)' segment in the stacked bar legend"; fi
[ "$bad" = 0 ] && echo "emulator screenshot gate: ALL GREEN"
exit "$bad"
