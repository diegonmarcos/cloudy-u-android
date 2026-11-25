#!/usr/bin/env bash
# #858 A missed, dismissed or aborted install prompt never leaves a Store row
# on "Installing…": the handover goes back to a retryable state that re-installs
# from the already-downloaded APK, and a still-pending prompt is shown again
# when the Store returns to the foreground.
#
# StoreInstallWatch.decide is EXECUTED here: its `when` arms are translated to
# Python and run against the scenarios the bug report names (aborted, abandoned
# session, timeout) plus landed and still-waiting. The wiring around it is
# checked statically, with comments stripped.
#
# Proven red by mutation: an arm that keeps WAITING for an aborted/abandoned
# session or after the timeout, the record() call dropped from installCached,
# the sweep dropped from stage(), the onResume hook removed, or the Retry
# label not drawn.
#
# Usage: ./test-store-install-retry.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="${STORE_LIB:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main}"
SRC="$LIB/java/com/diegonmarcos/superapp/appstore"
FLEET="${STORE_FLEET:-$ROOT/data/fleet}"   # #1562 the declaration is a served dataset (data/fleet), not an APK asset

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$SRC/StoreInstallWatch.kt" "$SRC/StoreStages.kt" "$SRC/StoreCloudFragment.kt" "$FLEET/appstore-install-watch.json"; do
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

# ── 1. decide(): aborted / abandoned / timed out → RETRY ───────────────────
WATCH="$(strip "$SRC/StoreInstallWatch.kt")"
OUT="$(printf '%s' "$WATCH" | python3 -c '
import re, sys
src = sys.stdin.read()
m = re.search(r"fun decide\(.*?\): String =\s*when \{(.*?)\n\s*\}", src, re.S)
if not m: print("NO_DECIDE"); sys.exit()
arms = []
for line in m.group(1).strip().splitlines():
    cond, res = [x.strip() for x in line.split("->")]
    if cond != "else":
        cond = cond.replace("||", " or ").replace("&&", " and ").replace("p.", "p_")
        cond = re.sub(r"!(?!=)", " not ", cond)
    arms.append((cond, res))
def decide(p_before, p_since, now, installed, failed, sessionAlive, timeoutMs, settleMs=10000):
    for c, r in arms:
        if c == "else" or eval(c): return r
T = 180000
cases = {
  "aborted":   decide("1/1", 0, 1000, "1/1", True,  False, T) == "RETRY",
  "dismissed": decide("1/1", 0, 1000, "1/1", True,  True,  T) == "RETRY",
  "abandoned": decide("1/1", 0, 15000, "1/1", False, False, T) == "RETRY",
  "settling":  decide("1/1", 0, 1000, "1/1", False, False, T) == "WAITING",
  "timeout":   decide("1/1", 0, T + 1, "1/1", False, True, T) == "RETRY",
  "waiting":   decide("1/1", 0, 1000, "1/1", False, True,  T) == "WAITING",
  "landed":    decide("1/1", 0, 15000, "2/9", False, False, T) == "LANDED",
}
print(" ".join(k for k, v in cases.items() if not v) or "ALL_OK")
' 2>&1)"
if [ "$OUT" = "ALL_OK" ]; then
  ok "decide(): aborted, dismissed, abandoned and timed-out handovers are RETRY; a live prompt waits; a new build is LANDED"
else bad "decide() keeps a stuck install (wrong for: $OUT)"; fi

# ── 2. the declared timeout and Retry label ────────────────────────────────
if python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
assert 0 < d["prompt_timeout_s"] <= 600 and d["resurface_grace_s"] > 0 and d["retry_label"]
' "$FLEET/appstore-install-watch.json" 2>/dev/null; then
  ok "appstore-install-watch.json declares the prompt timeout, the re-surface grace and the Retry label"
else bad "appstore-install-watch.json does not declare timeout / grace / retry_label"; fi

# ── 3. RETRY makes the row retryable from the cache ────────────────────────
SW="$(printf '%s' "$WATCH" | sed -n '/fun sweep(/,/^    }/p')"
if printf '%s' "$SW" | grep -q 'ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL' &&
   printf '%s' "$SW" | grep -q 'UpdateProgress.State.Failed('; then
  ok "a RETRY verdict writes the Install-stage note (row: Retry from the cache) and unsticks the bar"
else bad "a RETRY verdict leaves the row or the bar on Installing"; fi

ST="$(strip "$SRC/StoreStages.kt")"
IC="$(printf '%s' "$ST" | sed -n '/private fun installCachedGated(/,/^    }/p')"
STG="$(printf '%s' "$ST" | sed -n '/private fun stageBase(ctx: Context, app: Fleet.App/,/^    }/p')"   # #1121: stage() wraps stageBase(), which holds the row stages
if printf '%s' "$IC" | grep -q 'StoreInstallWatch.record(' && printf '%s' "$STG" | grep -q 'StoreInstallWatch.sweep('; then
  ok "every handover is recorded, and every row stage resolves it"
else bad "handovers are not recorded or not resolved when a row is drawn"; fi
if printf '%s' "$STG" | grep -q 'install did not finish' && printf '%s' "$STG" | grep -q 'listOf(INSTALL, DOWNLOAD, CLEAR), act, failedAt = INSTALL'; then
  ok "a stopped install keeps the cached APK and offers Install as the next verb"
else bad "the stopped-install stage no longer offers Install from the cache"; fi

# ── 4. the Retry button and the foreground re-surface ──────────────────────
FR="$(strip "$SRC/StoreCloudFragment.kt")"
RES="$(printf '%s' "$FR" | sed -n '/override fun onResume()/,/^    }/p')"
if printf '%s' "$RES" | grep -q 'StoreInstallWatch.onForeground('; then
  ok "returning to the Store re-surfaces a pending prompt"
else bad "onResume does not re-surface pending prompts"; fi
FG="$(printf '%s' "$WATCH" | sed -n '/fun onForeground(/,/^    }/p')"
if printf '%s' "$FG" | grep -q 'StoreStages.install(ctx, app)'; then
  ok "the re-surfaced prompt installs from the cached APK through the row's own Install"
else bad "onForeground does not re-launch the install"; fi
if printf '%s' "$FR" | grep -q 'retryLabel'; then ok "the quick button reads Retry after a stopped install"
else bad "no Retry label on a stopped install"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
