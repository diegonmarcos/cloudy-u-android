#!/usr/bin/env bash
#
# Wireless Debugging keep-alive — the Android wiring around WirelessDebugKeepAlive.
#
# #290 found that nothing re-armed `adb_wifi_enabled` between launches. The fix
# was a 15-minute keeper that wrote the setting back unconditionally, which left
# two holes: it never reconnected the embedded adb client (the setting came
# back, the shell channel did not), and the owner could not turn Wireless
# Debugging off at all — the keeper put it back within the quarter hour.
#
# The decisions (switch gate, Wi-Fi wait, re-read after write, stale-socket
# drop, no prompt loop) are JVM-tested against a fake phone in
# app/src/test/.../system/WirelessDebugKeepAliveTest.kt. This tester pins what
# that test cannot reach — the wiring that makes those decisions run at all:
#
#   T1  doWork runs the tick with the OWNER'S switch, not a constant. Passing
#       `true` compiles, passes the JVM test, and makes the switch decorative.
#   T2  doWork asks shouldTick first; without it the prompt-loop guard is dead code.
#   T3  the periodic pass: unique, PERIODIC, 15 MINUTES (WorkManager's floor —
#       less is silently clamped), KEEP (REPLACE restarts the period on every
#       cold start and an often-opened app never reaches a run).
#   T4  switching off cancels the periodic work, the queued ticks and the notice.
#   T5  event ticks APPEND, never REPLACE: a replaced worker's thread keeps
#       running, and two overlapping ticks drop each other's fresh connection.
#   T6  both in-process triggers exist: Wi-Fi becoming available, and the
#       setting itself changing (a BSSID roam is not a new network).
#   T7  App start and BOOT_COMPLETED both go through sync().
#   T8  PrivilegedPlaneWorker's own boot-time write honours the switch — else
#       OFF is undone on every launch — and it is never on a period.
#   T9  nothing posts a "Wireless debugging is off" alert: doWork only withdraws
#       stale ones (cancelNotice); the needs-owner state is recorded as status.
#   T10 switching Wireless debugging itself from the panel moves the keep-alive
#       with it, so the watchdog never fights the owner's hand.
#   T11 the switch is declared in build.json with its shipped default.
#   T12 doWork never returns Result.retry() — backoff on top of the period.
#   T13 (#1467) the SHARED keeper (libs:shizuku-adb-debug-tools WdKeeper, also Cloud Store's) is started by this
#       switch's sync() and stopped by its OFF branch.
#   T14 WdKeeper writes only on the REARM verdict, and asks the leader election first.
#   T15 the keeper's foreground service is declared specialUse with its justification, and in the perms catalogue.
#   T16 the shared keeper has every trigger: setting observer, Wi-Fi callback, boot (HostShell), periodic backstop.
#   T17 a peer app can read this app's claim (provider method), so the election has both sides.
#   T18 audit log + chips on the ADB Shell page (both apps), and the debug API route / endpoint.
#   T19 the battery prompt goes through PermsLink, never a settings intent of the lib's own.
#
# FAIL CLOSED: a missing tool or a moved file proves nothing and says so.

set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

SYS="$APP/app/src/main/java/com/diegonmarcos/superapp/system"
KSYS="$APP/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system"  # #1682 P3 step 4d: the keeper lives in libs:adb-shell-core
KEEPER="$KSYS/WirelessDebugKeeper.kt"
CORE="$KSYS/WirelessDebugKeepAlive.kt"
PPW="$KSYS/PrivilegedPlaneWorker.kt"
BOOT="$KSYS/PrivilegedPlaneBootReceiver.kt"
APPKT="$APP/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"   # #1682 P3 step 5: the owner hooks live in the AdbSide facade (src/owner)
CONTROLS="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/DeviceControls.kt"
JVMTEST="$APP/../ab_cloud-libs-shared/libs/adb-shell-core/src/test/java/com/diegonmarcos/superapp/system/WirelessDebugKeepAliveTest.kt"
BJ="$APP/build.json"

for tool in python3 jq; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "  FAIL: $tool is not on PATH — this tester proves nothing without it"
    echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done
for f in "$KEEPER" "$CORE" "$PPW" "$BOOT" "$APPKT" "$CONTROLS" "$JVMTEST" "$BJ"; do
  [ -f "$f" ] || {
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

# code <file> — comment lines removed, so the KDoc's prose cannot stand in for code.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1"; }

# body <file> <signature-substring> — that function's body, comments stripped,
# scoped by indentation.
body() {
  code "$1" | python3 -c '
import sys
needle = sys.argv[1]
lines = sys.stdin.read().split("\n")
for i, l in enumerate(lines):
    if needle in l:
        col = len(l) - len(l.lstrip()); out = [l]
        for m in lines[i+1:]:
            if m.strip() and (len(m) - len(m.lstrip())) <= col: break
            out.append(m)
        print("\n".join(out)); break
' "$2"
}

echo "== Wireless Debugging keep-alive wiring =="

DOWORK="$(body "$KEEPER" 'fun doWork')"
SYNC="$(body "$KEEPER" 'fun sync(')"
KICK="$(body "$KEEPER" 'private fun kick(')"
CALLBACKS="$(body "$KEEPER" 'private fun registerCallbacks(')"

# T1
n_tick=$(grep -c 'WirelessDebugKeepAlive\.tick(' <<<"$DOWORK")
n_gated=$(grep -c 'WirelessDebugKeepAlive\.tick(Prefs\.enabled(ctx)' <<<"$DOWORK")
if [ "$n_tick" -ge 1 ] && [ "$n_tick" -eq "$n_gated" ]; then
  ok "T1 every tick in doWork is gated by the owner's switch ($n_gated/$n_tick)"
else
  bad "T1 doWork ticks without Prefs.enabled ($n_gated of $n_tick gated) — the switch would be decorative"
fi

# T2
if python3 -c '
import sys; b=sys.argv[1]
g=b.find("WirelessDebugKeepAlive.shouldTick("); t=b.find("WirelessDebugKeepAlive.tick(")
sys.exit(0 if g!=-1 and t!=-1 and g<t else 1)' "$DOWORK"; then
  ok "T2 doWork consults shouldTick before ticking"
else
  bad "T2 doWork ticks without shouldTick — the prompt-loop guard is dead code"
fi

# T3
if grep -q 'enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy\.KEEP' <<<"$SYNC" \
   && grep -qE 'PeriodicWorkRequestBuilder<WirelessDebugKeeper>\(15, TimeUnit\.MINUTES\)' <<<"$SYNC"; then
  ok "T3 periodic, unique, KEEP, 15 minutes"
else
  bad "T3 the periodic pass is not unique/KEEP/15-minute periodic work for WirelessDebugKeeper"
fi

# T4
if python3 -c '
import sys; b=sys.argv[1]
i=b.find("if (!Prefs.enabled(app))"); seg=b[i:b.find("return", i)] if i!=-1 else ""
need=["cancelUniqueWork(UNIQUE_NAME)","cancelUniqueWork(TICK_NAME)","cancelNotice(app)"]
sys.exit(0 if seg and all(n in seg for n in need) else 1)' "$SYNC"; then
  ok "T4 switching off cancels the periodic pass, queued ticks and the notice"
else
  bad "T4 the OFF branch of sync() leaves the watchdog (or its notice) running"
fi

# T5
if grep -q 'ExistingWorkPolicy\.APPEND_OR_REPLACE' <<<"$KICK" && ! grep -qE 'ExistingWorkPolicy\.(REPLACE|KEEP)\b' <<<"$KICK"; then
  ok "T5 event ticks are appended, never replaced"
else
  bad "T5 event ticks are not APPEND_OR_REPLACE — overlapping ticks fight over the channel"
fi

# T6
if grep -q 'registerNetworkCallback' <<<"$CALLBACKS" && grep -q 'TRANSPORT_WIFI' <<<"$CALLBACKS" \
   && grep -q 'Trigger\.NETWORK_AVAILABLE' <<<"$CALLBACKS" \
   && grep -q 'registerContentObserver' <<<"$CALLBACKS" && grep -q '"adb_wifi_enabled"' <<<"$CALLBACKS" \
   && grep -q 'Trigger\.SETTING_CLEARED' <<<"$CALLBACKS"; then
  ok "T6 Wi-Fi-available and setting-changed triggers are both registered"
else
  bad "T6 a trigger is missing — recovery falls back to the 15-minute pass"
fi

# T7
if grep -q 'WirelessDebugKeeper\.sync(' <<<"$(code "$APPKT")" \
   && grep -q 'WirelessDebugKeeper\.sync(context, WirelessDebugKeepAlive\.Trigger\.BOOT)' <<<"$(code "$BOOT")"; then
  ok "T7 App start and BOOT_COMPLETED both sync the keeper"
else
  bad "T7 App.kt or the boot receiver does not sync the keeper"
fi

# T8
if python3 -c '
import sys; b=sys.argv[1]
g=b.find("WirelessDebugKeeper.Prefs.enabled(ctx)"); p=b.find("Settings.Global.putInt")
sys.exit(0 if g!=-1 and p!=-1 and g<p else 1)' "$(body "$PPW" 'private fun enableWirelessDebugging')"; then
  ok "T8a PrivilegedPlaneWorker's write honours the keep-alive switch"
else
  bad "T8a PrivilegedPlaneWorker writes adb_wifi_enabled regardless of the switch — OFF is undone on every launch"
fi
if grep -rq 'PeriodicWorkRequestBuilder<[^>]*PrivilegedPlaneWorker>' "$APP/app/src/main/java"; then
  bad "T8b PrivilegedPlaneWorker is on a period — the pairing/autoconnect loop forever"
else
  ok "T8b PrivilegedPlaneWorker is not periodic"
fi

# T9
if ! grep -q 'notifyOwner\|FleetAlerts\.raise\|ACTION_APPLICATION_DEVELOPMENT_SETTINGS' "$KEEPER" \
   && grep -q 'cancelNotice(ctx)' <<<"$DOWORK" && grep -q 'FleetAlerts.withdraw' "$KEEPER"; then
  ok "T9 nothing posts the Wireless-debugging-off alert; doWork only withdraws stale ones"
else
  bad "T9 the keeper still posts (or can post) an owner alert, or no longer withdraws stale ones"
fi

# T10
if python3 -c '
import sys,re; s=sys.argv[1]
m=re.search(r"\"wireless_debugging\" to Control\((.*?)\n        \),", s, re.S)
sys.exit(0 if m and "AdbSide.setKeeper(ctx, on)" in m.group(1) else 1)' "$(code "$CONTROLS")"; then
  ok "T10 switching Wireless debugging from the panel moves the keep-alive with it"
else
  bad "T10 the panel's Wireless debugging switch leaves the keep-alive behind — the watchdog undoes the owner's OFF"
fi

# T11
decl=$(jq -r '[.ui.control_panel.groups[].controls[]? | select(.id=="wireless_debugging_keepalive") | .default_on | type] | join(",")' "$BJ")
if [ "$decl" = "boolean" ] && grep -q 'declaredFlag(WirelessDebugKeeper.CONTROL_ID, "default_on")' <<<"$(code "$KEEPER")" \
   && grep -q 'CONTROL_ID = "wireless_debugging_keepalive"' <<<"$(code "$KEEPER")"; then
  ok "T11 the switch is declared once in build.json with a boolean default_on, and that is what the keeper reads"
else
  bad "T11 the switch's declaration or default is missing/duplicated (got: '$decl')"
fi

# T12
if grep -q 'Result\.retry' <<<"$DOWORK"; then
  bad "T12 doWork can return Result.retry() — backoff re-runs stack on top of the period"
else
  ok "T12 doWork never retries"
fi

# ── #1467 the shared keeper ──────────────────────────────────────────────────────────────────────────────
LIB="$APP/../ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main"
WD="$LIB/java/com/diegonmarcos/superapp/adbdebug"
for f in "$WD/WdKeeper.kt" "$WD/WdKeeperCore.kt" "$WD/WdKeeperService.kt" "$WD/WdAuditLog.kt" "$WD/AdbShellPage.kt" "$WD/HostShell.kt" \
         "$WD/AdbPeerReader.kt" "$LIB/AndroidManifest.xml" "$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt" \
         "$APP/../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json"; do
  [ -f "$f" ] || { bad "T13 missing $f"; echo "== RESULT: $PASS passed, $FAIL failed =="; exit 1; }
done
WDK="$(code "$WD/WdKeeper.kt")"
EVAL="$(body "$WD/WdKeeper.kt" 'fun evaluate(')"

# T13
if grep -q 'WdKeeper\.start(app)' <<<"$SYNC" && python3 -c '
import sys; b=sys.argv[1]
i=b.find("if (!Prefs.enabled(app))"); seg=b[i:b.find("return", i)] if i!=-1 else ""
sys.exit(0 if "WdKeeper.stop(app)" in seg else 1)' "$SYNC"; then
  ok "T13 sync() starts the shared keeper when armed and stops it when the switch is off"
else
  bad "T13 the shared keeper is not tied to this switch"
fi

# T14
if python3 -c '
import sys; b=sys.argv[1]
d=b.find("WdKeeperCore.decide("); l=b.find("WdKeeperLeader.isLeader("); w=b.find("keepWirelessDebuggingOn(")
r=b.find("Action.REARM")
sys.exit(0 if -1 not in (d,l,w,r) and d<l<w and r<w else 1)' "$EVAL"; then
  ok "T14 evaluate decides, asks the leader election, and only then writes"
else
  bad "T14 WdKeeper.evaluate can write without the decision or the leader election"
fi

# T15
if grep -q 'android:name="com.diegonmarcos.superapp.adbdebug.WdKeeperService"' "$LIB/AndroidManifest.xml" \
   && python3 -c '
import re,sys; m=open(sys.argv[1]).read()
i=m.find("adbdebug.WdKeeperService"); seg=m[i:m.find("</service>", i)]
sys.exit(0 if "foregroundServiceType=\"specialUse\"" in seg and "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in seg else 1)' "$LIB/AndroidManifest.xml" \
   && jq -e '[.foreground_services[]? | select(.id=="wireless-keeper" and .type=="specialUse" and (.why|length>20))] | length==1' \
        "$APP/../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json" >/dev/null; then
  ok "T15 the foreground service is specialUse, justified in the manifest and in the perms catalogue"
else
  bad "T15 the keeper service is not declared specialUse with a justification in both places"
fi

# T16
if grep -q 'registerContentObserver' <<<"$WDK" && grep -q '"adb_wifi_enabled"\|ADB_WIFI_ENABLED' <<<"$WDK" \
   && grep -q 'registerNetworkCallback' <<<"$WDK" && grep -q 'TRANSPORT_WIFI' <<<"$WDK" \
   && grep -q 'PeriodicWorkRequestBuilder<WdKeeperWorker>(15, TimeUnit.MINUTES)' <<<"$WDK" \
   && grep -q 'WdKeeper\.start(context' "$WD/HostShell.kt"; then
  ok "T16 observer, Wi-Fi callback, boot and the 15-minute backstop are all wired"
else
  bad "T16 a trigger of the shared keeper is missing"
fi

# T17
if grep -q 'WdKeeper\.METHOD_CLAIM' "$WD/AdbPeerReader.kt" && grep -q 'fun claimBundle' <<<"$WDK"; then
  ok "T17 this app answers its claim to the peer keeper"
else
  bad "T17 no claim provider: the leader election would see only one side"
fi

# T18
if grep -q 'WdAuditLog\.Entry' <<<"$WDK" && grep -q 'KeeperSection()' "$WD/AdbShellPage.kt" \
   && grep -q 'AppDebugServer\.route("keeper"' <<<"$WDK" && grep -q '"keeper/status"' "$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt"; then
  ok "T18 audit log, Configs chips and the debug API (both apps) exist"
else
  bad "T18 the audit log, the chips or the debug API is missing"
fi

# T19
if grep -q 'PermsLink\.open' <<<"$WDK" && ! grep -q 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS\|IGNORE_BATTERY_OPTIMIZATION_SETTINGS' <<<"$WDK"; then
  ok "T19 the battery prompt goes through PermsLink"
else
  bad "T19 the lib asks for the battery exemption itself (PermsOnePlaceTest would go red)"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
