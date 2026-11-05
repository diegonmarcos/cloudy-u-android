#!/usr/bin/env bash
# #857 "Download all" starts at once: no confirmation dialog, visible progress.
#
# The Store bar's Download all verb (StoreBar.Verbs.downloadAll) is wired in
# StoreCloudFragment's StoreBar.Bar straight to downloadAll(), which must hand
# the fleet to StoreStages.downloadAll on a worker thread with no dialog in
# between. StoreStages.downloadAll drives UpdateProgress (beginBatch), which is
# the progress row the page draws. Android's own install sheet is untouched:
# Download all installs nothing.
#
# Proven red by mutation: an AlertDialog / MaterialAlertDialogBuilder /
# setPositiveButton in downloadAll(), the verb routed through anything but
# downloadAll(), or beginBatch dropped from StoreStages.downloadAll.
#
# Usage: ./test-store-download-all-no-confirm.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="${STORE_SRC:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore}"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$SRC/StoreCloudFragment.kt" "$SRC/StoreBar.kt" "$SRC/StoreStages.kt"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}
DIALOG='AlertDialog|DialogFragment|MaterialAlertDialogBuilder|setPositiveButton|setNegativeButton|Dialog\('

FRAG="$(strip "$SRC/StoreCloudFragment.kt")"
# ── 1. the verb goes straight to downloadAll() ─────────────────────────────
if printf '%s' "$FRAG" | grep -Eq 'downloadAll = \{ downloadAll\(ctx, [a-z]+\) \}'; then
  ok "the Download all button calls downloadAll() directly"
else bad "the Download all button is not wired straight to downloadAll()"; fi

# ── 2. downloadAll() asks nothing: no dialog, the batch starts on a thread ──
DA="$(printf '%s' "$FRAG" | sed -n '/private fun downloadAll(/,/^    }/p')"
if [ -z "$DA" ]; then bad "downloadAll() not found"
elif printf '%s' "$DA" | grep -Eq "$DIALOG"; then
  bad "downloadAll() opens a dialog before downloading"
elif printf '%s' "$DA" | grep -A2 'thread(' | grep -q 'StoreStages.downloadAll('; then
  ok "downloadAll() starts StoreStages.downloadAll immediately with no confirmation"
else bad "downloadAll() does not start StoreStages.downloadAll on tap"; fi

# ── 3. the bar's button has no dialog in its path either ───────────────────
BAR="$(strip "$SRC/StoreBar.kt")"
if printf '%s' "$BAR" | grep -Eq "$DIALOG"; then bad "StoreBar draws a dialog"
else ok "StoreBar's buttons run their verb on tap, no dialog"; fi

# ── 4. visible progress: the batch publishes into UpdateProgress ───────────
ST="$(strip "$SRC/StoreStages.kt")"
SD="$(printf '%s' "$ST" | sed -n '/fun downloadAll(/,/^    }/p')"
PANEL="$(strip "$SRC/StoreProgressPanel.kt")"
if printf '%s' "$SD" | grep -q 'StoreJobs.board.begin(' && printf '%s' "$PANEL" | grep -q 'UpdateProgress.addObserver' \
   && printf '%s' "$FRAG" | grep -q 'StoreActivityPanel('; then
  ok "Download all drives the Store's one Activity panel"
else bad "Download all publishes no progress"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
