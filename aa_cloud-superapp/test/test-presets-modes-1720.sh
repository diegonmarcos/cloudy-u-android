#!/usr/bin/env bash
# #1720 Quick actions > Presets: one active choice per group, working Off, ADB status line, four mode bundles, Notify badge.
#
# The behaviour (single choice, restore-before-apply, the upgrade repair, the journal, reasons, batching, capability detection,
# the notification path, the badge, the page) is JVM-tested in app/src/test (PresetModesTest, ModeCommandsAllowlistTest,
# SuperSavingOffPathTest, PresetsBadgeTest, PresetsModesScreenTest). This tester pins the wiring that makes those run, and
# rule 16: nothing the page or the modes declared before #1720 was lost.
#
#   P1  FIELD PARITY against the previous version (app/src/test/resources/parity/presets-modes-1720-parity.json): every
#       group, mode (label, subtitle, toggles, cmd, engine), sandbox, theme, Super Saving step (label, kind, adb, settings,
#       packages), page string, page tag and page call is still there; the Notify producers and the Actions members are a superset.
#   P2  the parity check is not vacuous: with one mode toggle, one Super Saving setting or one page string removed it goes red.
#   S1  ONE state source: the picked mode is written only by PresetState.setMode; Focus and Super Saving read it; the old
#       second stores are no longer an "on" flag.
#   S2  every mode pick on the page goes through PresetModes (restore the previous first); no mode work on the main thread.
#   N1  the Super Saving notification: Off = explicit immutable broadcast to the manifest receiver, a content intent on the
#       body, the outcome written back; the receiver does not hold the broadcast open.
#   A1  the ADB status line is drawn first on the page from the broker's live status, with the link to the ADB Shell page.
#   M1  the four modes: each declares steps in the one vocabulary; Samsung steps are gated (`when` + skip_if_unset); no step
#       declares a pipe, a redirect or svc; the sync permissions of the auto-sync step are in the manifest.
#   B1  the Presets badge: declared in Actions, a data widget (no foreground service), service and receiver in the manifest,
#       fed by PresetState.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
BJ="$APP/build.json"; SSJ="$APP/app/src/main/assets/super-saving.json"; MAN="$APP/app/src/main/AndroidManifest.xml"
PAR="$APP/app/src/test/resources/parity/presets-modes-1720-parity.json"
PRE="$SRC/settings/LauncherPresetsFragment.kt"; PM="$SRC/settings/PresetModes.kt"; RT="$SRC/bgpolicy/SuperSavingRuntime.kt"
pass=0; fail=0
ok()  { echo "  PASS: $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }
for f in "$BJ" "$SSJ" "$MAN" "$PAR" "$PRE" "$PM" "$RT" "$SRC/settings/LauncherModes.kt" "$SRC/settings/FocusMode.kt" \
         "$SRC/bgpolicy/SuperSaving.kt" "$SRC/notificationcenter/PresetsBadge.kt"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; exit 1; }
done

parity() { # <build.json> <super-saving.json> <dir with the page sources>
python3 - "$1" "$2" "$3" "$PAR" <<'PY'
import json, sys, os
bj = json.load(open(sys.argv[1])); ss = json.load(open(sys.argv[2])); src = sys.argv[3]; par = json.load(open(sys.argv[4]))
ui = bj["ui"]; p = []
def by(l): return {x["id"]: x for x in l}
now = by(ui["launcher_presets"]["groups"])
for g in par["groups"]:
    n = now.get(g["id"])
    if n is None: p.append("group %s is gone" % g["id"]); continue
    for k in ("kind", "label", "subtitle"):
        if n.get(k) != g[k]: p.append("group %s: %s changed" % (g["id"], k))
if [g["id"] for g in ui["launcher_presets"]["groups"]][:len(par["groups"])] != [g["id"] for g in par["groups"]]: p.append("the group order changed")
now = by(ui["launcher_modes"])
for m in par["modes"]:
    n = now.get(m["id"])
    if n is None: p.append("mode %s is gone" % m["id"]); continue
    for k in ("label", "subtitle", "cmd", "engine"):
        if k in m and n.get(k) != m[k]: p.append("mode %s: %s changed" % (m["id"], k))
    for t, v in m.get("toggles", {}).items():
        if n.get("toggles", {}).get(t) != v: p.append("mode %s: toggle %s lost or changed" % (m["id"], t))
if [m["id"] for m in ui["launcher_modes"]] != [m["id"] for m in par["modes"]]: p.append("the mode list or order changed")
for key, decl in (("profiles", "launcher_profiles"), ("themes", "launcher_themes")):
    now = by(ui[decl])
    for m in par[key]:
        n = now.get(m["id"])
        if n is None: p.append("%s %s is gone" % (key, m["id"])); continue
        for k in ("label", "subtitle"):
            if n.get(k) != m[k]: p.append("%s %s: %s changed" % (key, m["id"], k))
        for t, v in m.get("toggles", {}).items():
            if n.get("toggles", {}).get(t) != v: p.append("%s %s: toggle %s lost or changed" % (key, m["id"], t))
if ss.get("indicator") != par["super_saving_indicator"]: p.append("the Super Saving notification words changed")
now = by(ss["steps"])
for s in par["super_saving_steps"]:
    n = now.get(s["id"])
    if n is None: p.append("Super Saving step %s is gone" % s["id"]); continue
    for k in ("label", "kind"):
        if n.get(k) != s[k]: p.append("step %s: %s changed" % (s["id"], k))
    if n.get("adb", True) != s["adb"] or n.get("optional", False) != s["optional"]: p.append("step %s: adb / optional changed" % s["id"])
    have = [[i["ns"], i["key"], i["value"]] for i in n.get("items", [])]
    if n.get("kind") == "setting_cap": have.append([n["ns"], n["key"], "max=" + str(n["max"])])
    for it in s["settings"]:
        if it not in have: p.append("step %s: setting %s lost or changed" % (s["id"], "/".join(it)))
    for pk in s["packages"]:
        if pk not in n.get("packages", []): p.append("step %s: package %s lost" % (s["id"], pk))
    if n.get("kind") == "shell_toggle":
        for k in ("read", "read_re", "apply", "revert_by_old", "verify", "skip_if_old"):
            if k not in n: p.append("step %s: %s is gone" % (s["id"], k))
ids = [s["id"] for s in ss["steps"]]
want = [s["id"] for s in par["super_saving_steps"]]
if [i for i in ids if i in want] != want: p.append("the Super Saving step order changed")
text = "".join(open(os.path.join(src, f), encoding="utf-8").read() for f in ("LauncherPresetsFragment.kt", "PresetModes.kt", "LauncherModes.kt"))
for s in par["page_strings"]:
    if s not in text: p.append("page string lost: %r" % s)
for t in par["page_tags"]:
    if 'testTag("' + t not in text: p.append("page tag lost: %s" % t)
for c in par["page_calls"]:
    if c not in text: p.append("page call lost: %s" % c)
nc = ui["notification_center"]
for i in par["notify_producers"]:
    if i not in [x["id"] for x in nc["producers"]]: p.append("Notify producer %s is gone" % i)
act = [g for g in nc["groups"] if g["id"] == "actions"][0]["members"]
if act[:len(par["notify_actions_members"])] != par["notify_actions_members"]: p.append("the Actions members before #1720 are not kept in order")
print("; ".join(p[:6]) or "OK")
PY
}

echo "== P1: field parity against the previous version =="
R="$(parity "$BJ" "$SSJ" "$SRC/settings")"
[ "$R" = "OK" ] && ok "P1 every pre-#1720 group, mode, sandbox, theme, step, string, tag and call is still declared" || bad "P1 $R"

echo "== P2: the parity check goes red when a field is removed =="
T="$(mktemp -d)"; mkdir -p "$T/settings"
cp "$SRC/settings/LauncherPresetsFragment.kt" "$SRC/settings/PresetModes.kt" "$SRC/settings/LauncherModes.kt" "$T/settings/"
python3 - "$BJ" "$T/bj.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); m = [x for x in b["ui"]["launcher_modes"] if x["id"] == "focus"][0]; m["toggles"].pop("ntfy_poll")
json.dump(b, open(sys.argv[2], "w"))
PY
python3 - "$SSJ" "$T/ss.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); s["steps"] = [x for x in s["steps"] if x["id"] != "screen_timeout"]
json.dump(s, open(sys.argv[2], "w"))
PY
m1="$(parity "$T/bj.json" "$SSJ" "$T/settings")"; m2="$(parity "$BJ" "$T/ss.json" "$T/settings")"
python3 - "$T/settings/LauncherPresetsFragment.kt" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read().replace("Shell action: runnable", "Shell action: fine"); open(p, "w", encoding="utf-8").write(s)
PY
m3="$(parity "$BJ" "$SSJ" "$T/settings")"
rm -r "$T"
if grep -q "toggle ntfy_poll" <<<"$m1" && grep -q "screen_timeout is gone" <<<"$m2" && grep -q "page string lost" <<<"$m3"; then ok "P2 a removed toggle, step or page string each turns the parity red"
else bad "P2 a removal went unnoticed: [$m1] [$m2] [$m3]"; fi

echo "== S1: one state source =="
W="$(grep -rn 'LauncherModePrefs(.*)\.selected *=' "$SRC" | grep -v 'settings/PresetModes.kt' || true)"
[ -z "$W" ] && ok "S1 the picked mode is written only in PresetState.setMode" || bad "S1 the picked mode is written outside PresetState: $W"
grep -q 'fun active(ctx: Context): Boolean = PresetState.mode(ctx) == MODE_ID' "$SRC/settings/FocusMode.kt" \
  && ok "S1 Focus is the picked mode, not its own boolean" || bad "S1 FocusMode.active does not read PresetState"
grep -q 'fun active(ctx: Context): Boolean = PresetState.mode(ctx.applicationContext) == ENGINE' "$RT" \
  && ok "S1 Super Saving is the picked mode, not its snapshot" || bad "S1 SuperSavingRuntime.active does not read PresetState"
if grep -q 'putBoolean("active"' "$SRC/settings/FocusMode.kt"; then bad "S1 Focus still writes its own on/off boolean"; else ok "S1 the old Focus store is only read once, for the repair"; fi
grep -q 'PresetModes.migrate(app)' "$RT" && grep -q 'fun migrate(legacyFocus: Boolean = false)' "$PM" \
  && ok "S1 the double state is repaired at app start and before every operation" || bad "S1 no upgrade repair"

echo "== S2: every pick goes through the one switch, off the main thread =="
if grep -q 'PresetModes.select(' "$PRE" && grep -q 'PresetModes.off(' "$PRE" && ! grep -q 'LauncherModes.apply(' "$PRE" \
   && ! grep -q 'rt.turnOn\|rt.turnOff' "$PRE"; then ok "S2 the page picks and leaves a mode only through PresetModes"
else bad "S2 the page still applies a mode by itself"; fi
grep -q 'val restored = if (cur != null) leave(cur) else emptyList()' "$PM" && grep -q 'val r = target.on()' "$PM" \
  && ok "S2 a pick restores the previous mode before it applies the next" || bad "S2 the switch does not restore first"
grep -q 'Thread({' "$PRE" && grep -q 'fun async(block: () -> Unit)' "$PM" && grep -q 'PresetModes.async {' "$SRC/settings/FocusMode.kt" \
  && ok "S2 mode work runs on a worker (page thread, the mode worker for Focus)" || bad "S2 mode work may run on the caller's thread"

echo "== N1: the Super Saving notification =="
if grep -q 'Intent(app, SuperSavingOffReceiver::class.java).setAction(SuperSavingOffReceiver.ACTION)' "$RT" && grep -q 'FLAG_IMMUTABLE' "$RT" \
   && grep -q 'android:name=".bgpolicy.SuperSavingOffReceiver"' "$MAN" && grep -q '.setContentIntent(' "$RT" \
   && grep -q 'SuperSavingRuntime.syncIndicator(app, if (SuperSavingRuntime.active(app))' "$RT"; then ok "N1 Off is an explicit broadcast to the declared receiver, the body opens Presets, the outcome is written back"
else bad "N1 the notification path is not wired end to end"; fi
A="$(sed -n '/^class SuperSavingOffReceiver/,/^}/p' "$RT" | grep -v '^ *//' | grep -v '^ *\*')"
if grep -q 'goAsync' <<<"$A"; then bad "N1 the Off receiver holds the broadcast open over a long restore"; else ok "N1 the Off receiver does not hold the broadcast open"; fi

echo "== A1: the ADB status line =="
first="$(grep -n 'AdbStatusLine(shell' "$PRE" | head -1 | cut -d: -f1)"; groups="$(grep -n 'page.forEachIndexed' "$PRE" | head -1 | cut -d: -f1)"
if [ -n "$first" ] && [ -n "$groups" ] && [ "$first" -lt "$groups" ] && grep -q 'st.armed -> UP to st.line()' "$PRE" \
   && grep -q 'AdbReadiness.openAdbShell(requireContext())' "$PRE" && grep -q 'ShellClient.status(it)' "$PRE"; then ok "A1 the line is drawn before the groups, from the broker's live status, with the ADB Shell link"
else bad "A1 the ADB status line is missing, not first, or not the live status"; fi

echo "== M1: the four modes =="
R="$(python3 - "$BJ" "$SSJ" "$MAN" <<'PY'
import json, sys, re
bj = json.load(open(sys.argv[1])); ss = json.load(open(sys.argv[2])); man = open(sys.argv[3]).read()
p = []
modes = bj["ui"]["launcher_modes"]
if [m["id"] for m in modes] != ["power", "focus", "saving_energy", "super_saving"]: p.append("the four modes are not power, focus, saving_energy, super_saving")
kinds = {"setting", "setting_cap", "shell_toggle", "bg_policy", "sync", "idle_screen_off", "keeper", "fleet_stop"}
allsteps = {}
for m in modes:
    st = ss["steps"] if m.get("engine") else m.get("steps", [])
    if not st: p.append(m["id"] + " declares no step")
    allsteps[m["id"]] = st
    for s in st:
        if s["kind"] not in kinds: p.append("%s/%s: unknown kind %s" % (m["id"], s["id"], s["kind"]))
        if not s.get("label"): p.append("%s/%s: no label" % (m["id"], s["id"]))
        if "samsung" in s["id"]:
            if s.get("when") != "samsung": p.append("%s/%s is a Samsung step without when=samsung" % (m["id"], s["id"]))
            if not all(i.get("skip_if_unset") for i in s.get("items", [])): p.append("%s/%s: a Samsung key is not skip_if_unset" % (m["id"], s["id"]))
        for k in ("read", "apply", "require_cmd", "skip_if_cmd"):
            c = s.get(k, "")
            if re.search(r"[|<>&`$]", c) or c.startswith("svc "): p.append("%s/%s: %s is not a plain allowlisted line: %s" % (m["id"], s["id"], k, c))
        for c in (s.get("revert_by_old") or {}).values():
            if re.search(r"[|<>&`$]", c) or c.startswith("svc "): p.append("%s/%s: a revert line is not plain: %s" % (m["id"], s["id"], c))
        if s["kind"] == "shell_toggle" and not s.get("revert_by_old"): p.append("%s/%s cannot be reverted" % (m["id"], s["id"]))
def keys(mid): return {i["key"] + "=" + i["value"] for s in allsteps[mid] for i in s.get("items", [])}
if "low_power=0" not in keys("power"): p.append("Power does not switch the saver off")
if "low_power=1" not in keys("saving_energy") or "low_power=1" not in keys("super_saving"): p.append("a saving mode does not switch the saver on")
if any(k.startswith("low_power") for k in keys("focus")): p.append("Focus pulls a power lever")
heavy = {"bg_policy", "idle_screen_off", "nfc", "bluetooth", "mobile_data", "sync", "fleet_stop"}
for mid in ("power", "focus", "saving_energy"):
    if heavy & {s["id"] for s in allsteps[mid]}: p.append(mid + " carries a step that belongs to Super Saving only")
for perm in ("READ_SYNC_SETTINGS", "WRITE_SYNC_SETTINGS"):
    if 'android.permission.' + perm not in man: p.append("the manifest lacks " + perm + " (the auto-sync step throws without it)")
print("; ".join(p[:6]) or "OK")
PY
)"
[ "$R" = "OK" ] && ok "M1 four non-overlapping bundles in one step vocabulary, Samsung steps gated, plain command lines, sync permissions declared" || bad "M1 $R"
grep -q '"launcher_toggles" -> applyLauncher(s, entry)' "$SRC/bgpolicy/SuperSaving.kt" && grep -q 'fun unsupported(s: SsStep): String?' "$SRC/bgpolicy/SuperSaving.kt" \
  && grep -q 'fun reapply(): List<SsResult>' "$SRC/bgpolicy/SuperSaving.kt" && grep -q 'private fun prefetch()' "$SRC/bgpolicy/SuperSaving.kt" \
  && ok "M1 the engine journals the launcher switches, detects capability, re-applies idempotently and batches its reads" || bad "M1 an engine capability is missing"

echo "== B1: the Presets badge =="
R="$(python3 - "$BJ" "$MAN" "$SRC/notificationcenter/PresetsBadge.kt" "$SRC/notificationcenter/DataWidgetRuntime.kt" "$SRC/settings/PresetModes.kt" <<'PY'
import json, sys, re
nc = json.load(open(sys.argv[1]))["ui"]["notification_center"]; man = open(sys.argv[2]).read()
src = open(sys.argv[3], encoding="utf-8").read(); rt = open(sys.argv[4]).read(); pm = open(sys.argv[5], encoding="utf-8").read()
code = "\n".join(l for l in src.splitlines() if not l.strip().startswith(("*", "//", "/*")))
p = []
b = [x for x in nc["producers"] if x["id"] == "presets_state"]
if len(b) != 1: p.append("presets_state is not declared exactly once")
else:
    b = b[0]
    if not (b.get("badge") and b.get("persistent") and b.get("enabled")): p.append("not a persistent, enabled badge")
    if b.get("service") != "com.diegonmarcos.superapp.notificationcenter.PresetsBadgeService": p.append("wrong service")
    if b.get("channel") != "presets_state" or 'CHANNEL_ID = "presets_state"' not in src: p.append("channel mismatch")
    if b.get("owner") != "notificationcenter/PresetsBadge.kt": p.append("wrong owner")
if "presets_state" not in [g for g in nc["groups"] if g["id"] == "actions"][0]["members"]: p.append("not a member of Actions")
svc = re.findall(r"<service\b[^>]*notificationcenter\.PresetsBadgeService[^>]*>", man)
if not svc or "foregroundServiceType" in svc[0] or 'android:exported="false"' not in svc[0]: p.append("the service is missing, exported or a foreground type")
if not re.search(r'<receiver\b[^>]*notificationcenter\.PresetsBadgeReceiver[^>]*android:exported="false"', man): p.append("the receiver is missing or exported")
if re.search(r"\bstartForeground\s*\(", code) or "startForegroundService" in code: p.append("the badge goes foreground")
if "FleetNotify.dataWidgetBuilder(" not in code or "NotifyGate.postCtx(" not in code: p.append("not built as a data widget")
if re.search(r"AlarmManager|postDelayed|scheduleNext|Timer", code): p.append("the badge schedules work: it must be state-driven only")
if "PresetState.now(ctx)" not in code: p.append("the badge does not read PresetState")
if "PresetsBadge.refresh(" not in pm: p.append("a state change does not redraw the badge")
if "PresetsBadgeService::class.java, PresetsBadge.NOTIF_ID, PresetsBadge.BADGE_ID" not in rt: p.append("not registered as a data widget")
for a in ("ACT_MODE_OFF", "ACT_MODE_NEXT", "ACT_SANDBOX_NEXT", "ACT_THEME_NEXT"):
    if code.count(a) < 3: p.append("action %s is not declared, offered and handled" % a)
print("; ".join(p[:6]) or "OK")
PY
)"
[ "$R" = "OK" ] && ok "B1 the badge is declared in Actions, a state-driven data widget fed by PresetState, with its four actions and Show" || bad "B1 $R"

echo "== RESULT: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
