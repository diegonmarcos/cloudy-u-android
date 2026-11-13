#!/usr/bin/env bash
#
# Device tuning — the declarative, shell-applied settings the SuperApp keeps
# true on this phone, first of all the Android 12+ phantom-process killer that
# SIGKILLs the Cloud Terminal's proot sessions ("[Process completed (signal 9)]";
# memory is not the cause). The documented fix needs shell uid, which this app
# has through its own embedded adb channel — so the app applies it, declared in
# ONE data file, and nothing else.
#
#   T1  data/device-tuning.json is valid JSON whose entries all carry
#       {id, title, why, apply[], verify{cmd, expect}}.
#   T2  the two Termux-documented phantom entries are declared: the monitor
#       switch (settings_enable_monitor_phantom_procs=false) and the cap
#       (max_phantom_processes=2147483647, with device_config sync pinned).
#   T3  the file is baked: app/build.gradle reads it through leanJson into
#       BuildConfig.DEVICE_TUNING_B64, and the engine decodes exactly that.
#   T4  the engine is data-driven — no `settings put` / `device_config` command
#       string anywhere in the superapp's Kotlin.
#   T5  the engine verifies before it applies, and records every outcome
#       (APPLIED / PENDING / FAILED); one run at a time.
#   T6  it is triggered on the plane's successful connect (PrivilegedPlaneWorker,
#       which App.kt enqueues on AdbPairingService.onConnected AND once at start).
#   T7  the Permissions page renders the declared entries (rows + why).
#   T8  GET /api/devcontrol/tuning is routed and catalogued in /api/docs.
#
# FAIL CLOSED: a missing tool or a moved file proves nothing and says so.

set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

DATA="$APP/data/device-tuning.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
ENGINE="$SRC/system/DeviceTuning.kt"
PPW="$APP/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/PrivilegedPlaneWorker.kt"  # #1682 P3 step 4d: the boot worker is the owner lib's; the tuning pass is the host hook PrivilegedPlaneHost
APPKT="$APP/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"   # #1682 P3 step 5: the owner hooks live in the AdbSide facade (src/owner)
PAGE="$SRC/configs/PermissionsFragment.kt"
SERVER="$SRC/devcontrol/DevControlServer.kt"

for tool in python3 jq; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "  FAIL: $tool is not on PATH — this tester proves nothing without it"
    echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done
for f in "$DATA" "$GRADLE" "$ENGINE" "$PPW" "$APPKT" "$PAGE" "$SERVER"; do
  [ -f "$f" ] || {
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

# code <file> — comment lines removed, so a KDoc cannot stand in for code.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1"; }

echo "== Device tuning =="

# T1
if jq -e '
  (.entries | type == "array" and length > 0) and
  all(.entries[];
      (.id|type=="string" and length>0) and (.title|type=="string" and length>0) and
      (.why|type=="string" and length>0) and
      (.apply|type=="array" and length>0 and all(.[]; type=="string" and length>0)) and
      (.verify.cmd|type=="string" and length>0) and (.verify.expect|type=="string" and length>0))
' "$DATA" >/dev/null 2>&1; then
  ok "T1 device-tuning.json is valid JSON and every entry has id/title/why/apply[]/verify{cmd,expect}"
else
  bad "T1 device-tuning.json is invalid or an entry is missing id/title/why/apply[]/verify{cmd,expect}"
fi

# T2
mon=$(jq -r '.entries[] | select(.apply|index("settings put global settings_enable_monitor_phantom_procs false")) | select(.verify.cmd=="settings get global settings_enable_monitor_phantom_procs" and .verify.expect=="false") | .id' "$DATA" 2>/dev/null)
cap=$(jq -r '.entries[] | select(.apply|index("device_config put activity_manager max_phantom_processes 2147483647")) | select(.apply|index("device_config set_sync_disabled_for_tests persistent")) | select(.verify.cmd=="device_config get activity_manager max_phantom_processes" and .verify.expect=="2147483647") | .id' "$DATA" 2>/dev/null)
if [ -n "$mon" ] && [ -n "$cap" ] && [ "$mon" != "$cap" ]; then
  ok "T2 both phantom entries are declared with their verify ($mon, $cap)"
else
  bad "T2 a phantom entry is missing or its apply/verify drifted (monitor='$mon' cap='$cap')"
fi

# T3
if grep -q 'file("../data/device-tuning.json")' "$GRADLE" \
   && grep -qE 'deviceTuningJson *= *leanJson\(deviceTuningFile' "$GRADLE" \
   && grep -q 'buildConfigField "String", "DEVICE_TUNING_B64"' "$GRADLE" \
   && grep -q 'BuildConfig.DEVICE_TUNING_B64' <<<"$(code "$ENGINE")"; then
  ok "T3 data/device-tuning.json is baked into BuildConfig.DEVICE_TUNING_B64 and the engine decodes it"
else
  bad "T3 the data file is not baked (app/build.gradle) or the engine does not read DEVICE_TUNING_B64"
fi

# T4
hits=$(grep -rnE 'settings (put|get) global settings_enable_monitor|device_config (put|get|set_sync)' "$SRC" --include=*.kt | grep -vE ':[[:space:]]*(//|\*|/\*)' || true)
if [ -z "$hits" ]; then
  ok "T4 no phantom/device_config command string in Kotlin — the data file is the only source"
else
  bad "T4 a tuning command is hardcoded in Kotlin: $(head -1 <<<"$hits")"
fi

# T5
E="$(code "$ENGINE")"
if python3 -c '
import sys; b=sys.argv[1]
v=b.find("e.verifyCmd"); a=b.find("e.apply")
sys.exit(0 if v!=-1 and a!=-1 and v<a else 1)' "$E" \
   && grep -q 'State.APPLIED' <<<"$E" && grep -q 'State.PENDING' <<<"$E" && grep -q 'State.FAILED' <<<"$E" \
   && grep -q 'running.compareAndSet(false, true)' <<<"$E" && grep -q 'System.currentTimeMillis()' <<<"$E"; then
  ok "T5 verify-first, three recorded states with a timestamp, one run at a time"
else
  bad "T5 the engine applies before verifying, misses a state, or can run twice at once"
fi

# T6
if grep -q 'AdbOwnerHost.tune(ctx)' <<<"$(code "$PPW")" \
   && grep -q 'DeviceTuning.run(ctx, exec)' <<<"$(code "$APP/app/src/owner/java/com/diegonmarcos/superapp/system/PrivilegedPlaneHost.kt")" \
   && grep -q 'AdbOwnerHost.plane = PrivilegedPlaneHost' <<<"$(code "$APPKT")" \
   && grep -q 'AdbPairingService.onConnected' <<<"$(code "$APPKT")" \
   && grep -q 'OneTimeWorkRequestBuilder<PrivilegedPlaneWorker>' <<<"$(code "$APPKT")"; then
  ok "T6 the pass runs after the plane connects (pairing success + app start, via PrivilegedPlaneWorker)"
else
  bad "T6 nothing triggers DeviceTuning.run on connect / app start"
fi

# T7
P="$(code "$PAGE")"
if grep -q 'DeviceTuning.entries()' <<<"$P" && grep -q 'DeviceTuning.records(' <<<"$P" \
   && grep -qE 'KvRow\(e\.title' <<<"$P" && grep -q 'Small(e.why)' <<<"$P"; then
  ok "T7 the Permissions page renders every declared entry: title row, state light, why"
else
  bad "T7 the Permissions page does not render the declared entries"
fi

# T8
S="$(code "$SERVER")"
if grep -q '"devcontrol/tuning" ->' <<<"$S" && grep -q 'DeviceTuning.toJson(ctx)' <<<"$S" \
   && grep -q 'Spec("devcontrol/tuning"' <<<"$S" && grep -q 'fun toJson(ctx: Context): String' <<<"$E"; then
  ok "T8 GET /api/devcontrol/tuning is routed to DeviceTuning.toJson and catalogued in /api/docs"
else
  bad "T8 the tuning route is missing from the dispatcher or the /api/docs catalog"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
