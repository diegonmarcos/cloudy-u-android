#!/usr/bin/env bash
# #857 Wi-Fi + Auto-update ON => every pending update is queued and downloaded
# FIRST, at the highest priority, before the Store page's catalogue refresh,
# icons or details.
#
# The rule is DECLARED in libs:appstore/assets/appstore-priority.json and
# evaluated by StorePriority; the Store page (StoreCloudFragment's per-tab effect)
# starts the StoreAuto chain through StorePriority.startUpdatesAsync and then
# runs its catalogue check (checkAll) AT ONCE: #861, the check never awaits,
# joins or gates on the update chain (it held the Store on "Checking..." forever).
#
# #861 proven red by mutation: the #857 wait loop restored, checkAll moved into a
# callback of the chain, or a sleep/join/await on the chain in the check path.
# Proven red by mutation: priority "normal", network "metered", `first` dropped,
# or a bare checkAll(ctx, rows) back in the tab effect each fail a check below.
#
# Usage: ./test-store-updates-first.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="${STORE_LIB:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main}"
SRC="$LIB/java/com/diegonmarcos/superapp/appstore"
DECL="$LIB/assets/appstore-priority.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$DECL" "$SRC/StorePriority.kt" "$SRC/StoreCloudFragment.kt" "$SRC/StoreAuto.kt"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

# Strip comments so prose quoting the rule cannot satisfy it.
strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# ── 1. the declaration: Wi-Fi + auto-update => updates first, highest ──────
if python3 - "$DECL" <<'PY'
import json, sys
u = json.load(open(sys.argv[1]))["updates_first"]
w = u.get("when", {})
assert u.get("enabled") is True, "enabled"
assert u.get("priority") == "highest", "priority must be highest"
assert w.get("auto_update") is True, "when.auto_update must be true"
assert w.get("network") == "unmetered", "when.network must be unmetered"
assert u.get("first") == "download_all_updates", "first must be download_all_updates"
after = u.get("after", [])
for k in ("catalogue_refresh", "icons", "details"):
    assert k in after, k + " must wait in `after`"
assert u["first"] not in after
PY
then ok "appstore-priority.json: Wi-Fi + Auto-update queues all updates first at the highest priority"
else bad "appstore-priority.json does not declare updates first on Wi-Fi + Auto-update"; fi

# ── 2. the evaluator is the declaration's, and gates on both conditions ────
PRI="$(strip "$SRC/StorePriority.kt")"
FN="$(printf '%s' "$PRI" | sed -n '/fun updatesFirst(/,/^$/p')"
if printf '%s' "$PRI" | grep -q 'appstore-priority.json' &&
   printf '%s' "$FN" | grep -q 'autoOn' && printf '%s' "$FN" | grep -q 'unmetered' &&
   printf '%s' "$FN" | grep -q 'HIGHEST'; then
  ok "StorePriority.updatesFirst reads the asset and requires auto-update, unmetered and highest"
else bad "StorePriority.updatesFirst does not gate on auto-update + unmetered + highest"; fi

RUN="$(printf '%s' "$PRI" | sed -n '/fun startUpdatesAsync(/,/^    }/p')"
if printf '%s' "$RUN" | grep -q 'StoreAuto.run(' && printf '%s' "$RUN" | grep -q 'MAX_PRIORITY' &&
   printf '%s' "$RUN" | grep -q 'thread('; then
  ok "startUpdatesAsync runs the auto chain on its own max-priority thread"
else bad "startUpdatesAsync does not run the update chain async at max priority"; fi

# ── 3. #861 the check path never awaits, joins or gates on the chain ───────
BLOCK='Thread\.sleep|\.join\(|\.get\(\)|await|CountDownLatch|runBlocking|while *\(|isRunning\(|phaseNow\(|maxWait|max_wait'
if ! printf '%s' "$RUN" | grep -Eq "$BLOCK" &&
   ! printf '%s' "$RUN" | grep -Eq 'then *:|\(\) -> Unit' &&
   ! python3 -c 'import json,sys; sys.exit(0 if "max_wait_s" in json.load(open(sys.argv[1]))["updates_first"] else 1)' "$DECL"; then
  ok "#861 startUpdatesAsync takes no continuation and never waits: nothing is gated on the chain"
else bad "#861 the update chain is waited on / gates work again (Store stuck on Checking)"; fi

FRAG="$(strip "$SRC/StoreCloudFragment.kt")"
RF="$(printf '%s' "$FRAG" | sed -n '/LaunchedEffect(tab) {/,/^        }$/p')"
START_L="$(printf '%s' "$RF" | grep -n 'StorePriority.startUpdatesAsync(ctx, fleet) *$' | head -1 | cut -d: -f1)"
CHECK_L="$(printf '%s' "$RF" | grep -nE '^ *checkAll\(ctx, rows(, force = false)?\) *$' | head -1 | cut -d: -f1)"
if [ -n "$START_L" ] && [ -n "$CHECK_L" ] && [ "$START_L" -lt "$CHECK_L" ] &&
   ! printf '%s' "$RF" | grep -Eq "$BLOCK|runUpdatesFirst"; then
  ok "#861 the tab's effect starts the updates async, then runs the catalogue check at once"
else bad "#861 the tab's catalogue check awaits or is gated on the update chain"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
