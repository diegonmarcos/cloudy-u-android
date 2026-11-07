#!/usr/bin/env bash
#
# #1506 Background policy + Battery Hunger (+ Super Saving, see the later tests) — the wiring the JVM tests cannot reach.
#
# The decisions (state machine, snapshot/restore, allowlist, no-ADB degradation) are JVM-tested against a fake phone in
# app/src/test/.../bgpolicy/. This tester pins what makes them run, and the battery-safety rules of the feature:
#
#   T1  the declaration (assets/bg-policy.json) is valid and names the six essential roles and both fleet prefixes.
#   T2  NOTHING polls or keeps alive: no WorkManager, AlarmManager, JobScheduler, Handler.postDelayed, Timer, foreground service,
#       or loop in the feature's code. It runs on a tap, or once on PACKAGE_REPLACED.
#   T3  no sp literal, no hand-made Text: the rows are KitStoreRowLine / KitStoreQuick and the ui-kit type ramp.
#   T4  Battery Hunger is drawn in Quick Actions > Panels > Battery Hungers (the battery scope of LauncherConfigFragment; All shows it too).
#   T5  PACKAGE_REPLACED: registered at run time in App.onCreate, MY_PACKAGE_REPLACED in the manifest, the receiver ends its async work.
#   T6  Battery Hunger ranks with X-Ray's own analyser and probe, not a second copy.
#   T7  the allowlist is checked in the engine for restrict and force-stop, so no caller can skip it.
#   T8  every action's verdict is a read-back (the engine reads after each action), and a failed read is never a pass.
#   T9  every shell command is built in BgCmd, which validates names; the UI holds no command string.
#   T10 Super Saving Battery Mode is declared right after Saving Energy as an `engine` mode, and the steps are DATA (assets/super-saving.json):
#       no settings key is a Kotlin literal, and every step that touches the shell is marked adb:true (the ones that do not are sync and keeper).
#   T11 the indicator is ONE ongoing notification posted on, cancelled off, with an Off action; the screen-off hook exists only while on.
#   T13 the c3 peers-control tools have their phone routes (bgpolicy/get, bgpolicy/set, supersaving/on, supersaving/off), routed AND documented.
#   T12 the Presets tile hands the pick to the engine, hosts the arm prompt, and the Off receiver is declared.
#
# FAIL CLOSED: a missing file proves nothing and says so.
set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
BG="$SRC/bgpolicy"
CFG="$APP/app/src/main/assets/bg-policy.json"
for f in "$CFG" "$BG/BgEngine.kt" "$BG/BgCmd.kt" "$BG/BatteryHunger.kt" "$BG/BgReplacedReceiver.kt" "$BG/BgUi.kt"; do
  [ -f "$f" ] || { echo "FAIL: $f is missing, nothing here proves anything"; exit 1; }
done
ENGINE="$(cat "$BG/BgEngine.kt")"; HUNGER="$(cat "$BG/BatteryHunger.kt")"; REPL="$(cat "$BG/BgReplacedReceiver.kt")"
CMD="$(cat "$BG/BgCmd.kt")"; UI="$(cat "$BG/BgUi.kt")"
ALL="$(cat "$BG"/*.kt)"

# T1
R="$(python3 - "$CFG" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
roles = d["essential"]["roles"]
need = ["self", "dialer", "sms", "launcher", "keyboard", "systemui"]
print("OK" if roles == need and "com.diegonmarcos." in d["fleet_prefixes"] and "cld." in d["fleet_prefixes"] and "com.diegonmarcos.superapp" in d["essential"]["packages"] else "roles=%s prefixes=%s" % (roles, d["fleet_prefixes"]))
PY
)"
if [ "$R" = "OK" ]; then ok "T1 the declaration names the six essential roles and the fleet prefixes"; else bad "T1 $R"; fi

# T2
if grep -nE 'WorkManager|AlarmManager|JobScheduler|postDelayed|scheduleAtFixedRate|java\.util\.Timer|ForegroundService|startForeground|while \(true\)|CountDownTimer' "$BG"/*.kt; then
  bad "T2 the feature polls, schedules or keeps itself alive (lines above)"
else ok "T2 no polling, scheduling or keep-alive anywhere in the feature"; fi

# T3
if grep -nE '[0-9]\.?[0-9]*\.sp\b|[0-9]\.?[0-9]*f?\.sp\b|TextUnit|fontSize *=' "$BG"/*.kt; then bad "T3 a text size is set by hand (lines above)"
elif grep -q 'KitStoreRowLine' <<<"$HUNGER" && grep -q 'KitStoreQuick' <<<"$HUNGER"; then ok "T3 rows are KitStoreRowLine/KitStoreQuick with the kit's type ramp, no sp literal"
else bad "T3 Battery Hunger does not use KitStoreRowLine and KitStoreQuick"; fi

# T4
LCF="$(cat "$SRC/settings/LauncherConfigFragment.kt")"
IN_TOOLS="$(awk '/if \(showBattery\) \{/{f=1} f&&/BatteryHungerPanel/{print "yes"; exit}' "$SRC/settings/LauncherConfigFragment.kt")"
if grep -q 'BatteryHungerPanel' <<<"$LCF" && [ "$IN_TOOLS" = "yes" ]; then
  ok "T4 Battery Hunger is drawn inside the battery scope"
else bad "T4 Battery Hunger is not drawn in the Battery Hungers tab"; fi

# T5
MAN="$(cat "$APP/app/src/main/AndroidManifest.xml")"; APPKT="$(cat "$SRC/App.kt")"
if grep -q 'BgReplacedReceiver.register' <<<"$APPKT" && grep -q 'bgpolicy.BgReplacedReceiver' <<<"$MAN" && grep -q 'MY_PACKAGE_REPLACED' <<<"$REPL" \
   && grep -q 'ACTION_PACKAGE_REPLACED' <<<"$REPL" && grep -q 'pending.finish()' <<<"$REPL" && ! grep -qE 'Service|Worker' <<<"$REPL"; then
  ok "T5 PACKAGE_REPLACED applies once and ends (no service, no worker)"
else bad "T5 the update re-apply is missing a piece or keeps something alive"; fi

# T6
if grep -q 'XrayAnalyze.suspects' <<<"$HUNGER" && grep -q 'XrayProbes.all' <<<"$HUNGER"; then ok "T6 the ranking is X-Ray's analyser over X-Ray's probe"
else bad "T6 Battery Hunger does not share X-Ray's ranking code"; fi

# T7
if [ "$(grep -c 'essential.isEssential' <<<"$ENGINE")" -ge 3 ]; then ok "T7 restrict, force-stop and component-disable all check the allowlist in the engine"
else bad "T7 the engine does not check the allowlist on every destructive action"; fi

# T8
if [ "$(grep -c 'read(pkg)' <<<"$ENGINE")" -ge 5 ] && grep -q 'could not read the state back' <<<"$ENGINE"; then ok "T8 each action reads the state back and a failed read is a failure"
else bad "T8 an action does not verify by reading back"; fi

# T9
if grep -nE '(cmd appops|am set-standby|am force-stop|pm (en|dis)able|deviceidle|settings (put|get|delete)|svc )' "$BG/BatteryHunger.kt" "$BG/BgUi.kt" "$BG/BgHost.kt" "$BG/BgReplacedReceiver.kt" "$BG/SuperSaving.kt" "$BG/SuperSavingRuntime.kt" "$BG/BgDialogs.kt"; then bad "T9 a shell command is written outside BgCmd (lines above)"
elif grep -q 'safePkg' <<<"$CMD" && grep -q 'safeComp' <<<"$CMD"; then ok "T9 shell commands are built in BgCmd, which validates names"
else bad "T9 BgCmd does not validate names"; fi

# T10
SSJ="$APP/app/src/main/assets/super-saving.json"
R="$(python3 - "$APP/build.json" "$SSJ" "$SRC" <<'PY'
import json, sys, glob
bj = json.load(open(sys.argv[1])); ss = json.load(open(sys.argv[2]))
ids = [m["id"] for m in bj["ui"]["launcher_modes"]]
p = []
if ids.index("super_saving") != ids.index("saving_energy") + 1: p.append("super_saving is not right after saving_energy")
if [m for m in bj["ui"]["launcher_modes"] if m["id"] == "super_saving"][0].get("engine") != "super_saving": p.append("no engine")
for st in ss["steps"]:
    if st["kind"] in ("sync", "keeper"):
        if st.get("adb", True): p.append(st["id"] + " needs no shell but is marked adb")
    elif st.get("adb") is not True: p.append(st["id"] + " uses the shell but is not marked adb:true")
src = "".join(open(f).read() for f in glob.glob(sys.argv[3] + "/bgpolicy/*.kt"))
keys = {i["key"] for st in ss["steps"] for i in st.get("items", [])}
for k in keys:
    if '"' + k + '"' in src: p.append("settings key " + k + " is a Kotlin literal")
need = ["low_power", "wifi_scan_always_enabled", "ble_scan_always_enabled", "peak_refresh_rate", "min_refresh_rate", "window_animation_scale", "transition_animation_scale", "animator_duration_scale"]
for k in need:
    if k not in keys: p.append("missing step key " + k)
print("; ".join(p) or "OK")
PY
)"
if [ "$R" = "OK" ]; then ok "T10 the mode is declared after Saving Energy, steps are data, shell steps are marked adb"; else bad "T10 $R"; fi

# T11
RT="$(cat "$BG/SuperSavingRuntime.kt")"
if [ "$(grep -c 'nm.notify(' <<<"$RT")" -eq 1 ] && grep -q 'setOngoing(true)' <<<"$RT" && grep -q 'addAction(' <<<"$RT" && grep -q 'ACTION_SCREEN_OFF' <<<"$RT" \
   && grep -q 'unregisterReceiver' <<<"$RT" && grep -q 'armIdle(on: Boolean)' <<<"$RT"; then ok "T11 one ongoing notification with Off, screen-off hook registered only while on"
else bad "T11 the indicator or the screen-off hook is not as declared"; fi

# T12
PRE="$(cat "$SRC/settings/LauncherPresetsFragment.kt")"
if grep -q 'SuperSavingRuntime.ENGINE' <<<"$PRE" && grep -q 'BgDialogHost(dialogs)' <<<"$PRE" && grep -q 'bgpolicy.SuperSavingOffReceiver' <<<"$MAN" \
   && grep -q 'SuperSavingRuntime.onAppStart' <<<"$APPKT"; then ok "T12 the tile hands the pick to the engine, hosts the arm prompt, Off receiver declared"
else bad "T12 the Presets wiring of Super Saving is incomplete"; fi

# T13
DCS="$(cat "$SRC/devcontrol/DevControlServer.kt")"; n=0
for r in bgpolicy/get bgpolicy/set supersaving/on supersaving/off; do
  if grep -q "\"$r\" -> " <<<"$DCS" && grep -q "Spec(\"$r\"" <<<"$DCS"; then n=$((n+1)); fi
done
if [ "$n" -eq 4 ]; then ok "T13 the four peers-control routes are served and documented"; else bad "T13 only $n of 4 peers-control routes are both routed and documented"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
