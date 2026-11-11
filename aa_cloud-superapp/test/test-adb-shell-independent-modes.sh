#!/usr/bin/env bash
# Configs > ADB Shell: the connection modes are INDEPENDENT channels and nothing bootstraps a server through the channel it provides.
# Static (no build, no device). The behaviour itself is proven by the JVM suites named in M7; this pins the wiring they cannot see.
#  M1 the Connect plan has no server-start step: StepId holds no START_SERVER, ConnectPlan names no server launch.
#  M2 the loopback app_process server is launched from ONE place, the Restart-server button (ChannelOps.startServer): never from the
#     channel lookup (ShellChannels.active), the reconnect (ShellAccess), the pairing service, or the app.
#  M3 the channel lookup is a pure read: ChannelSelector.select takes no relaunch callback.
#  M4 Reconnect is gated by the mode: ShellAccess decides from rungs(mode) and reaches Shizuku only behind that gate; Local server and
#     Embedded modes hold the adb rung alone, Shizuku mode the Shizuku rung alone.
#     A direct Shizuku fallback outside the selection (pm grant, logcat, the Perms plane line) asks the mode first.
#  M5 the Restart-server launch line goes over the mode's own channel: Local server mode lists embedded adb only.
#  M6 a mode's Connect / Auto-fix ends on a real shell round trip, and the adb connect itself is proven by one (connectLive).
#  M7 the JVM tests that prove the behaviour exist.
#  M8 planted violations turn red (each rule is shown to bite).
# Usage: ./test-adb-shell-independent-modes.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys
R = sys.argv[1]
LIB = "ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/"
LIBT = "ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/test/java/com/diegonmarcos/superapp/adbdebug/"
SA = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/"
SAT = "aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/configs/"
NEED = [LIB + f for f in ("ConnectPlan.kt", "ChannelMode.kt", "ShellChannel.kt", "ShellAccess.kt", "ChannelDevice.kt", "AdbShellBootstrap.kt",
                          "AdbPairingService.kt", "EmbeddedAdbChannel.kt")] + [SA + "configs/AdbCockpitModel.kt", SA + "configs/AdbCockpitOps.kt"]
missing = [p for p in NEED if not os.path.isfile(os.path.join(R, p))]
if missing:
    print("  FAIL: missing file(s), the tree is not what this tester was written against: " + ", ".join(missing))
    print("== RESULT: 0 passed, 1 failed =="); sys.exit(1)

def strip(t): return re.sub(r"/\*.*?\*/|//[^\n]*", "", t, flags=re.S)
def load():
    out = {}
    for base in ("ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main", "aa_cloud-superapp/app/src/main"):
        for d, _, fs in os.walk(os.path.join(R, base)):
            for f in fs:
                if f.endswith((".kt", ".java")):
                    p = os.path.relpath(os.path.join(d, f), R)
                    out[p] = strip(open(os.path.join(R, p), encoding="utf-8").read())
    return out

def body(src, head):
    """The text of the declaration starting at [head] up to its matching closing brace."""
    i = src.find(head)
    if i < 0: return ""
    j = src.find("{", i); depth = 0
    for k in range(j, len(src)):
        if src[k] == "{": depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0: return src[i:k + 1]
    return src[i:]

def check(S):
    """Every rule against the sources [S]; returns the list of (id, ok, text)."""
    r = []
    plan, mode, chan, acc, dev = S[LIB + "ConnectPlan.kt"], S[LIB + "ChannelMode.kt"], S[LIB + "ShellChannel.kt"], S[LIB + "ShellAccess.kt"], S[LIB + "ChannelDevice.kt"]
    steps = body(plan, "enum class StepId")
    r.append(("M1a", "START_SERVER" not in steps and not re.search(r'"[^"]*server[^"]*"', steps, re.I), "StepId has no server-start step"))
    r.append(("M1b", not re.search(r"startServer|ensureServer|START_SERVER|Launch the server", plan), "ConnectPlan plans and names no server launch"))
    r.append(("M1c", not re.search(r"\bSTART_SERVER\b", "".join(S.values())), "no code refers to a START_SERVER step"))
    callers = sorted(p for p, t in S.items() if re.search(r"AdbShellBootstrap\.ensureServer\(", t))
    r.append(("M2a", callers == [LIB + "ChannelDevice.kt"], "ensureServer is called from ChannelDevice.kt only (found: %s)" % (", ".join(callers) or "none")))
    r.append(("M2b", len(re.findall(r"AdbShellBootstrap\.ensureServer\(", dev)) == 1 and "ensureServer(" in body(dev, "fun startServer("),
              "the one ensureServer call sits in ChannelOps.startServer (the Restart-server button)"))
    r.append(("M2c", not re.search(r"ensureServer|shellCommand\(|startServer\(", body(dev, "class DeviceConnectExecutor")), "the Connect executor starts no server"))
    r.append(("M2d", not re.search(r"AdbShellBootstrap|shellCommand\(", S[LIB + "AdbPairingService.kt"]), "the pairing service launches nothing through the session"))
    launchers = sorted(p for p, t in S.items() if re.search(r"AdbShellBootstrap\.shellCommand\(|[^.\w]shellCommand\(ctx\)", t) and p != LIB + "AdbShellBootstrap.kt")
    r.append(("M2e", launchers == [], "nothing outside AdbShellBootstrap sends the launch line (found: %s)" % (", ".join(launchers) or "none")))
    sel = body(mode, "fun <T> select(")
    r.append(("M3a", sel != "" and "relaunch" not in sel and "relaunch" not in body(chan, "fun active("), "ChannelSelector.select / ShellChannels.active take no relaunch"))
    r.append(("M3b", not re.search(r"ensureServer|autoConnect|connectLive|requestPermission|bindBlocking", body(chan, "fun active(")), "ShellChannels.active is a pure read"))
    rungs = body(acc, "fun rungs(")
    r.append(("M4a", re.search(r"ChannelMode\.SHIZUKU\s*->\s*listOf\(EnsureRung\.SHIZUKU\)", rungs) is not None, "Shizuku mode reconnects Shizuku only"))
    r.append(("M4b", re.search(r"ChannelMode\.EMBEDDED_ONLY,\s*ChannelMode\.LOCAL_SERVER\s*->\s*listOf\(EnsureRung\.EMBEDDED_ADB\)", rungs) is not None,
              "Local server and Embedded modes reconnect adb only"))
    ens = body(acc, "fun ensure(")
    gate = ens.find("EnsureRung.SHIZUKU !in rungs")
    first_shz = min([i for i in (ens.find("ShizukuAdb."), ens.find("SHIZUKU_PKG")) if i >= 0] or [-1])
    r.append(("M4c", gate >= 0 and first_shz > gate and re.search(r"if \(EnsureRung\.SHIZUKU !in rungs\)\s*return", ens) is not None,
              "ShellAccess.ensure returns before any Shizuku call when the mode has no Shizuku rung"))
    adb_at = ens.find("EmbeddedAdbChannel.")
    r.append(("M4d", adb_at >= 0 and "if (EnsureRung.EMBEDDED_ADB in rungs)" in ens[:adb_at] and "ensureServer" not in ens,
              "ShellAccess.ensure touches the adb client only behind the adb rung, and launches no server"))
    ls = body(mode, "fun launchSources(")
    r.append(("M5a", re.search(r"ChannelMode\.LOCAL_SERVER\s*->\s*ladder\.filter\s*\{\s*it == EMBEDDED\s*\}", ls) is not None, "Local server mode launches over embedded adb only"))
    r.append(("M5b", "ChannelSelector.launchSources(" in body(dev, "fun launchSources(") and "ShellChannels.all.firstOrNull" not in body(dev, "fun stopServer("),
              "the server's Start / Stop go over the mode's own channel"))
    eo = body(mode, "fun execOrder(")
    r.append(("M5c", re.search(r"ChannelMode\.LOCAL_SERVER\s*->\s*listOf\(LOCAL, EMBEDDED\)", eo) is not None and
              re.search(r"ChannelMode\.SHIZUKU\s*->\s*ladder\.filter\s*\{\s*it == SHIZUKU\s*\}", eo) is not None,
              "Local server mode runs commands over its own two layers, Shizuku mode over Shizuku only"))
    r.append(("M6a", re.search(r"\+\s*StepId\.VERIFY", body(plan, "fun steps(")) is not None and "ShellUid.of(" in dev[dev.find("StepId.VERIFY ->"):],
              "every plan ends on VERIFY, a real `id` round trip"))
    live = body(S[LIB + "EmbeddedAdbChannel.kt"], "fun connectLive(")
    r.append(("M6b", "probe(ctx)" in live and "disconnect(ctx)" in live and "EmbeddedAdbChannel.connectLive(" in body(dev, "class DeviceConnectExecutor") and
              "EmbeddedAdbChannel.connectLive(" in ens, "Connect and Reconnect count the adb session only when it answers a round trip"))
    cm = S[SA + "configs/AdbCockpitModel.kt"]
    r.append(("M6c", "ChannelMode.LOCAL_SERVER -> setOf(LOCAL)" in body(cm, "fun active(") and "SHIZUKU" not in body(cm, "fun local(") and
              "shizuku" not in body(cm, "fun local(").lower() and re.search(r"LOCAL\s*->\s*f\.serverRunning \|\| f\.adbPaired[ \t]*$", body(cm, "fun modeReady("), re.M) is not None,
              "the cockpit's Local Server row reads its own channel only"))
    # Direct Shizuku fallbacks outside the selection: each sits behind the mode's own rule.
    grant = body(S[LIB + "ShizukuAdb.kt"], "fun grant(")
    logcat = S[SA + "devcontrol/AboutLogcat.kt"]
    r.append(("M4e", "ShellChannels.modeUses(ctx, ChannelSelector.SHIZUKU)) exec(ctx, cmd)" in grant and
              re.search(r"ShellChannels\.active\(ctx\)\?\.exec\(ctx, cmd\)\s*\?:\s*(com\.diegonmarcos\.superapp\.adbdebug\.)?ShizukuAdb\.exec\(", logcat) is None and
              "modeUses(ctx, com.diegonmarcos.superapp.adbdebug.ChannelSelector.SHIZUKU)" in logcat and
              "modeUses(" in body(S[SA + "configs/PermissionsFragment.kt"], "private fun shellPlane("),
              "a direct Shizuku fallback (pm grant, logcat, the Perms plane line) asks the mode first"))
    return r

def tests_exist():
    want = {LIBT + "ConnectPlanTest.kt": ["modesAreIndependentChains", "noStepStartsAServer", "localServerModeConnectsOnTheAdbSessionAlone"],
            LIBT + "ChannelModeTest.kt": ["modesNeverCrossCall", "localServerModeRunsOverTheAdbSessionWhenNoServerWasStarted", "reconnectRungsPerMode"],
            LIBT + "ChannelStateTest.kt": ["launchSourceRules"], LIBT + "ChannelActionsTest.kt": ["noServerIsStillUpOverAdbAndDownOnlyWhenNothingAnswers"],
            SAT + "AdbCockpitModelTest.kt": ["C11 the modes are independent channels", "C12 a mode row is green only on a real round trip"],
            SAT + "AdbShellParityTest.kt": ["every field of the richest ADB Shell page is present"]}
    bad = []
    for p, names in want.items():
        t = open(os.path.join(R, p), encoding="utf-8").read() if os.path.isfile(os.path.join(R, p)) else ""
        bad += ["%s: %s" % (os.path.basename(p), n) for n in names if n not in t]
    return bad

S = load()
fails = 0
for rid, ok, text in check(S):
    print(("  PASS: " if ok else "  FAIL: ") + rid + " " + text); fails += 0 if ok else 1
bad = tests_exist()
print(("  PASS: " if not bad else "  FAIL: ") + "M7 the JVM behaviour tests exist" + ("" if not bad else " - missing: " + "; ".join(bad))); fails += 1 if bad else 0

# M8: plant each violation in memory; the named rule must turn red.
MUT = [
    ("a server-start step comes back", LIB + "ConnectPlan.kt", 'VERIFY("Round trip"),', 'START_SERVER("Start local server"),\n    VERIFY("Round trip"),', "M1a"),
    ("the channel lookup launches the server again", LIB + "ShellChannel.kt", "return ChannelSelector.select(", "AdbShellBootstrap.ensureServer(ctx, all); return ChannelSelector.select(", "M2a"),
    ("the pairing service launches the server again", LIB + "AdbPairingService.kt", "val live = EmbeddedAdbChannel.probe(this)", "EmbeddedAdbChannel.exec(this, AdbShellBootstrap.shellCommand(this)); val live = EmbeddedAdbChannel.probe(this)", "M2d"),
    ("the Connect executor starts the server", LIB + "ChannelDevice.kt", "StepId.VERIFY -> {", "StepId.VERIFY -> { ChannelOps.startServer(app, restart = false);", "M2c"),
    ("Local server mode reconnects through Shizuku", LIB + "ShellAccess.kt", "ChannelMode.EMBEDDED_ONLY, ChannelMode.LOCAL_SERVER -> listOf(EnsureRung.EMBEDDED_ADB)", "ChannelMode.EMBEDDED_ONLY, ChannelMode.LOCAL_SERVER -> listOf(EnsureRung.EMBEDDED_ADB, EnsureRung.SHIZUKU)", "M4b"),
    ("the reconnect drops its Shizuku gate", LIB + "ShellAccess.kt", "if (EnsureRung.SHIZUKU !in rungs)", "if (false)", "M4c"),
    ("Local server mode launches through Shizuku", LIB + "ChannelMode.kt", "ChannelMode.LOCAL_SERVER -> ladder.filter { it == EMBEDDED }", "ChannelMode.LOCAL_SERVER -> ladder.filter { it != LOCAL }", "M5a"),
    ("Local server mode runs commands on the server only", LIB + "ChannelMode.kt", "ChannelMode.LOCAL_SERVER -> listOf(LOCAL, EMBEDDED)", "ChannelMode.LOCAL_SERVER -> listOf(LOCAL)", "M5c"),
    ("the pm grant falls back to Shizuku in any mode", LIB + "ShizukuAdb.kt", "(if (ShellChannels.modeUses(ctx, ChannelSelector.SHIZUKU)) exec(ctx, cmd) else null)", "exec(ctx, cmd)", "M4e"),
    ("the adb connect trusts the socket flag", LIB + "ChannelDevice.kt", "EmbeddedAdbChannel.connectLive(app)", "EmbeddedAdbChannel.autoConnect(app)", "M6b"),
    ("the Local Server row reads Shizuku", SA + "configs/AdbCockpitModel.kt", "LOCAL -> f.serverRunning || f.adbPaired", "LOCAL -> f.serverRunning || f.adbPaired || f.shizuku == ShizukuState.UP", "M6c"),
]
print("== planted violations must turn red")
for name, path, a, b, rule in MUT:
    if a not in S[path]:
        print("  MUTATION NOT APPLIED: " + name); fails += 1; continue
    M = dict(S); M[path] = S[path].replace(a, b, 1)
    red = [rid for rid, ok, _ in check(M) if not ok]
    if rule in red: print("  mutation caught: %s (%s)" % (name, rule))
    else: print("  MUTATION NOT CAUGHT: %s (expected %s, red: %s)" % (name, rule, red)); fails += 1
print("== RESULT: %d failure(s) ==" % fails)
sys.exit(1 if fails else 0)
PY
