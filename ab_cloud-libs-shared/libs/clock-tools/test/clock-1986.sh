#!/usr/bin/env bash
# #1986 the Clock in the owner's reference look, the ring's notification card, World clock pinned; #1987 Calc keeps only the
# World clock. Six source tests, each with mutations on a scratch copy that must turn it red (a test that no mutation kills
# proves nothing). The rules behind them (ClockLook tokens, RingCard buttons, ClockSubpages.CALC) are JVM-tested in
# ClockLookTest with their own mutants (test-clock-1970.yml jvm job).
#   t1_alarm_card         a ringing alarm's card: high-priority alarm channel, full-screen intent to the ring screen, Stop /
#                         Snooze (with the time it rings again) / Dismiss, ongoing; exact alarm clocks (setAlarmClock)
#   t2_timer_card         a finished timer's card: Stop and +1 min
#   t3_world_unchanged    World clock code pinned by hash (WorldClockScreen and its helpers, AnalogDial, the shared readers),
#                         drawn without the dark frame
#   t4_parity_1970        rule 16: every string, tag, ClockEngine call and op each screen used before #1986 is still used
#   t5_visual_tokens      black background, the indigo / purple glow, the lilac accent, dark sheets, the icon island, wired
#   t6_calc_world_only    #1987 Calc's Tools > Clock = [world]; Tasks' pages unchanged; the moved declarations; the hand-over
# Usage: clock-1986.sh   JUNIT_OUT=<file> also writes a JUnit XML (the CI verdict reads it, never this script's exit code)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="$(cd "$here/../../../.." && pwd)" JUNIT_OUT="${JUNIT_OUT:-}"
exec python3 -I - <<'PY'
import hashlib, json, os, re, sys
from xml.sax.saxutils import quoteattr

ROOT, JUNIT = os.environ["ROOT"], os.environ.get("JUNIT_OUT", "")
LIB = "ab_cloud-libs-shared/libs/clock-tools/"
K = LIB + "src/main/java/com/diegonmarcos/clocktools/"
C = LIB + "core/src/main/kotlin/com/diegonmarcos/clocktools/"
FILES = {
    "screens": K + "ui/ClockScreens.kt", "world": K + "ui/WorldClock.kt", "widgets": K + "ui/ClockWidgets.kt", "lookui": K + "ui/ClockLookUi.kt",
    "service": K + "ClockService.kt", "ring": K + "RingActivity.kt", "engine": K + "ClockEngine.kt", "link": K + "ClockLink.kt",
    "manifest": LIB + "src/main/AndroidManifest.xml", "look": C + "ClockLook.kt", "subpages": C + "ClockSubpages.kt",
    "base": LIB + "test/data/clock-1986-baseline.json",
    "calc": "ac_cloud-calc/build.json", "calc_state": "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/ui/CalcState.kt",
    "calc_main": "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/MainActivity.kt",
    "calc_jev": "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/decide/JevStore.kt",
    "tasks": "ac_cloud-tasks/build.json", "tasks_main": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/MainActivity.kt",
    "tasks_app": "ac_cloud-tasks/app/src/main/java/com/diegonmarcos/cloudagenda/ui/TasksApp.kt",
}

def load(root):
    out = {}
    for k, p in FILES.items():
        with open(os.path.join(root, p), encoding="utf-8") as h: out[k] = h.read()
    return out

def code(t): return re.sub(r"//[^\n]*|/\*.*?\*/", "", t, flags=re.S)

def body(src, fun):
    mm = re.search(r"fun (?:\w+\.)?" + re.escape(fun) + r"\(", src)
    if not mm: return ""
    i = mm.start()
    m = re.search(r"\n(?:@Composable\n)?(?:private |internal )?(?:fun|object|class|val) ", src[i + 1:])
    return src[i: i + 1 + m.start()] if m else src[i:]

def need(where, text, tokens, what): return ["%s lacks %s: %s" % (where, what, t) for t in tokens if t not in text]

def const(src, name):
    m = re.search(r"const val %s = (0x[0-9A-Fa-f]+)L" % name, src)
    return int(m.group(1), 16) if m else None

def t1_alarm_card(f):
    look, svc, eng = code(f["look"]), code(f["service"]), code(f["engine"])
    card = body(look, "of")
    bad = need("RingCard.of", card, ['Action("dismiss", key, STOP)', 'Action("snooze", key, "$SNOOZE:$snoozeAt")', 'if (snoozable) acts += Action("snooze", key, "$SNOOZE:$snoozeAt")\n                acts += Action("dismiss_all", "", DISMISS)',
                                     "fullScreen = true", "ongoing = true"], "a part of the alarm card")
    ong = body(svc, "ongoing")
    bad += need("ClockNotifications.ongoing", ong, ["RingCard.of(top.key", "RingCard.snoozeAt(", "CH_RING", "setFullScreenIntent(ringScreen(ctx), true)",
                "NotificationCompat.CATEGORY_ALARM", "NotificationCompat.PRIORITY_MAX", "setOngoing(card.ongoing)", "card.actions.forEach",
                "R.string.clock_stop", "R.string.clock_snooze_until", "R.string.clock_dismiss", "b.addAction(0, label, control(ctx, a.op, a.key))"], "a card part")
    if not re.search(r"NotificationChannel\(CH_RING,[^\n]*NotificationManager\.IMPORTANCE_HIGH\)", svc): bad.append("the ring channel is not high priority")
    if "AlarmManagerCompat.setAlarmClock(" not in eng: bad.append("alarms are not alarm clocks (doze)")
    if "android.permission.USE_FULL_SCREEN_INTENT" not in f["manifest"] or "android.permission.USE_EXACT_ALARM" not in f["manifest"]: bad.append("manifest lacks full-screen / exact alarm")
    if "RingActivity::class.java" not in body(svc, "ringScreen"): bad.append("the full-screen intent does not open the ring screen")
    return bad

def t2_timer_card(f):
    look, svc = code(f["look"]), code(f["service"])
    bad = need("RingCard.of", body(look, "of"), ['key.startsWith("timer:") -> acts += Action("timer_add", key, PLUS_MINUTE)'], "the timer's +1 min")
    bad += need("ClockNotifications.ongoing", body(svc, "ongoing"), ["a.label == RingCard.PLUS_MINUTE -> ctx.getString(R.string.clock_plus_minute)",
                                                                      "a.label == RingCard.STOP -> ctx.getString(R.string.clock_stop)"], "a timer button")
    if not re.search(r'name="clock_plus_minute">\+1 min<', open(os.path.join(ROOT, LIB + "src/main/res/values/strings.xml"), encoding="utf-8").read()):
        bad.append("the +1 min label is not declared")
    return bad

# t3: the World clock's code as it was before #1986 (sha256 of the comment-stripped, whitespace-normalised text).
WORLD_PINS = {
    "WorldClock.kt up to FullClock": "1f8ff407ea35cf288656f041c4b6cb43fe45e39aecc1e5bad3ccff1e53711ad3",
    "AnalogDial": "d131b529f7665c31ae330074a5232f23c2c10deeb2cd0e109baecfbf3c1a4860",
    "shared readers (ClockMetrics, rememberClock, rememberNow, HHMM, HHMMSS, DAY)": "de6e767348a59e8aff382ebec2952b0c25b442b2e002e5e71af3ad0dd9ba66cd",
}
def world_parts(f):
    w = f["world"]; s = code(f["screens"])
    i = s.find("internal object ClockMetrics"); j = s.find("private fun firstDay()")
    return {
        "WorldClock.kt up to FullClock": w[: w.find("const val CITY_LIST_TAG")],
        "AnalogDial": body(code(f["widgets"]), "AnalogDial"),
        "shared readers (ClockMetrics, rememberClock, rememberNow, HHMM, HHMMSS, DAY)": s[i:j] if i >= 0 and j > i else "",
    }
def digest(t): return hashlib.sha256(re.sub(r"\s+", " ", code(t)).strip().encode()).hexdigest()

def t3_world(f):
    bad = []
    for name, part in world_parts(f).items():
        if digest(part) != WORLD_PINS[name]: bad.append("World clock code changed: %s" % name)
    full = code(f["world"]); fc = body(full, "FullClock")
    if '"world" -> WorldClockScreen()' not in fc: bad.append("FullClock does not draw the World clock as it was")
    if "if (world) it else it.clockBackdrop()" not in fc: bad.append("the World clock is drawn on the dark backdrop")
    if re.search(r"ClockFrame|ClockScheme|clockBackdrop", body(full, "WorldClockScreen")): bad.append("WorldClockScreen takes the dark look")
    return bad

def t4_parity(f):
    base = json.loads(f["base"]); bad = []
    srcs = {"ring": code(f["ring"]), "world": code(f["world"]), "screens": code(f["screens"])}
    # the new look moved some parts into helpers: a screen's text is its body plus its helpers'
    helpers = {"FullClock": [("lookui", "ClockIsland")], "AlarmEditor": [("screens", "SheetRow"), ("screens", "DayChip")]}
    for fn, want in base.items():
        if fn.startswith("_") or fn == "ClockTags": continue
        src = srcs["ring"] if fn == "RingScreen" else srcs["world"] if fn == "FullClock" else srcs["screens"]
        b = body(src, fn)
        if not b: bad.append("%s is gone" % fn); continue
        b += "".join(body(code(f[k]), h) for k, h in helpers.get(fn, []))
        bad += ["%s lost R.string.%s" % (fn, x) for x in want["strings"] if "R.string." + x not in b]
        bad += ["%s lost ClockTags.%s" % (fn, x) for x in want["tags"] if "ClockTags." + x not in b]
        bad += ["%s lost ClockEngine.%s" % (fn, x) for x in want["engine"] if "ClockEngine." + x + "(" not in b]
        bad += ["%s lost op %s" % (fn, x) for x in want["ops"] if '"%s"' % x not in b]
    s = srcs["screens"]; tags = s[s.find("object ClockTags"):]
    bad += ["ClockTags lost %s" % t for t in base["ClockTags"] if not re.search(r"(?:const val|fun) %s\b" % t, tags)]
    return bad

def t5_tokens(f):
    look, ui, w, scr = code(f["look"]), code(f["lookui"]), code(f["widgets"]), code(f["screens"])
    bad = []
    if const(look, "BACKGROUND") != 0xFF000000: bad.append("the background is not black")
    gi, gp, acc = const(look, "GLOW_INDIGO"), const(look, "GLOW_PURPLE"), const(look, "ACCENT")
    rgb = lambda c: ((c >> 16) & 255, (c >> 8) & 255, c & 255)
    if gi is None or not (rgb(gi)[2] > rgb(gi)[0] and rgb(gi)[2] > rgb(gi)[1]): bad.append("the high glow is not indigo")
    if gp is None or not (rgb(gp)[2] > rgb(gp)[0] > rgb(gp)[1]): bad.append("the low glow is not purple")
    if acc is None or not (rgb(acc)[2] > rgb(acc)[0] > rgb(acc)[1] and sum(rgb(acc)) > 450): bad.append("the accent is not lilac")
    if const(look, "SHEET") is None or max(rgb(const(look, "SHEET"))) > 0x30: bad.append("the sheet is not dark")
    g = body(ui, "drawGlow")
    bad += need("drawGlow", g, ["drawRect(look(ClockLook.BACKGROUND))", "glow(ClockLook.GLOW_INDIGO", "glow(ClockLook.GLOW_PURPLE", "Brush.radialGradient("], "a glow part")
    bad += need("RoundButton", body(w, "RoundButton"), ["ClockLook.ACCENT", "ClockLook.LAP", "CircleShape"], "a round-button colour")
    bad += need("ClockSwitch", body(ui, "ClockSwitch"), ["ClockLook.ACCENT", "ClockLook.SWITCH_OFF"], "a switch colour")
    for fn in ("AlarmsScreen", "TimersScreen", "StopwatchScreen", "IntervalScreen", "BedtimeScreen", "PomodoroScreen"):
        if "ClockFrame(" not in body(scr, fn): bad.append("%s is not in the dark frame" % fn)
    ed = body(scr, "AlarmEditor")
    bad += need("AlarmEditor", ed, ["ClockFrame(", "glow = false", "look(ClockLook.SHEET)", "ClockBar(", "ClockLook.SHEET_RADIUS"], "a sheet part")
    bad += need("StopwatchScreen", body(scr, "StopwatchScreen"), ["ClockLook.dialText(elapsed)", "PagerDots(2"], "a dial part")
    bad += need("drawStopwatchDial", body(ui, "drawStopwatchDial"), ["ClockLook.HAND", "ClockLook.TICK_MAJOR", "ClockLook.TICK_MINOR", "ClockLook.SHEET"], "a dial colour")
    fc = body(code(f["world"]), "FullClock")
    bad += need("FullClock", fc, ["ClockIsland(", "ClockLook.islandOrder(", "clockBackdrop()"], "the island / backdrop")
    if "com.diegonmarcos.clocktools.ui.ClockFrame(" not in code(f["ring"]): bad.append("the ring screen is not in the dark look")
    return bad

def t6_calc(f):
    bad = []
    modes = json.loads(f["calc"])["ui"]["modes"]
    calc = [m["id"] for m in modes if m.get("tab") == "clock"]
    if calc != ["world"]: bad.append("Calc's Tools > Clock is %s, not [world]" % calc)
    sp = code(f["subpages"])
    m = re.search(r"CALC[^=]*=\s*listOf\(([^)]*)\)", sp)
    if not m or re.findall(r'"(\w+)"', m.group(1)) != ["world"]: bad.append("ClockSubpages.CALC is not [world]")
    m = re.search(r"IDS[^=]*=\s*listOf\(([^)]*)\)", sp)
    ids = re.findall(r'"(\w+)"', m.group(1)) if m else []
    if ids != ["world", "alarms", "timers", "stopwatch", "interval", "bedtime", "pomodoro"]: bad.append("Tasks' Clock pages changed: %s" % ids)
    moved = json.loads(f["tasks"])["ui"].get("clock_modes", [])
    if [x.get("id") for x in moved] != ids[1:]: bad.append("Tasks' ui.clock_modes are not the six pages Calc handed over")
    pomo = [x for x in moved if x.get("kind") == "pomodoro"]
    if not pomo or pomo[0].get("clock", {}).get("work") != 25: bad.append("the Pomodoro declaration did not move to Tasks")
    st = body(code(f["calc_state"]), "show")
    bad += need("CalcState.show", st, ["ClockSubpages.MOVED_TO_TASKS", "handOff?.invoke(modeId) != true", "show(com.diegonmarcos.clocktools.ClockSubpages.CALC.first())"], "the hand-over")
    if "ClockLink.handOff(this, id)" not in code(f["calc_main"]): bad.append("Calc's MainActivity does not hand pages to Cloud Tasks")
    if "ClockSubpages.TASKS_PACKAGE" not in body(code(f["link"]), "handOff") or "com.diegonmarcos.cloudagenda" not in f["manifest"]: bad.append("the hand-over cannot see Cloud Tasks")
    if code(f["tasks_main"]).count("ClockLink.offer(intent)") < 2: bad.append("Cloud Tasks does not read a Clock link on start and on a new intent")
    if "go(Ids.CLOCK)" not in code(f["tasks_app"]): bad.append("Cloud Tasks does not open its Clock for a link")
    return bad

TESTS = [("t1_alarm_card", t1_alarm_card), ("t2_timer_card", t2_timer_card), ("t3_world_unchanged", t3_world), ("t4_parity_1970", t4_parity),
         ("t5_visual_tokens", t5_tokens), ("t6_calc_world_only", t6_calc)]

MUTATIONS = [  # name, the test it must turn red, file key, old text, new text
    ("m1-alarm-card-no-dismiss", "t1_alarm_card", "look", '                acts += Action("dismiss_all", "", DISMISS)\n', ""),
    ("m2-alarm-card-no-snooze", "t1_alarm_card", "look", 'if (snoozable) acts += Action("snooze", key, "$SNOOZE:$snoozeAt")', "Unit"),
    ("m3-alarm-card-not-full-screen", "t1_alarm_card", "service", "if (card.fullScreen) b.setFullScreenIntent(ringScreen(ctx), true)", "Unit"),
    ("m4-ring-channel-default", "t1_alarm_card", "service", "NotificationManager.IMPORTANCE_HIGH", "NotificationManager.IMPORTANCE_DEFAULT"),
    ("m5-timer-card-no-plus", "t2_timer_card", "look", 'key.startsWith("timer:") -> acts += Action("timer_add", key, PLUS_MINUTE)', 'key.startsWith("timer:") -> Unit'),
    ("m6-timer-card-no-stop-label", "t2_timer_card", "service", "a.label == RingCard.STOP -> ctx.getString(R.string.clock_stop)", "a.label == RingCard.STOP -> ctx.getString(R.string.clock_dismiss)"),
    ("m7-world-clock-touched", "t3_world_unchanged", "world", "AnalogDial(ref, homeZone, Modifier.size(112.dp)", "AnalogDial(ref, homeZone, Modifier.size(96.dp)"),
    ("m8-world-on-dark", "t3_world_unchanged", "world", "if (world) it else it.clockBackdrop()", "it.clockBackdrop()"),
    ("m9-analog-dial-touched", "t3_world_unchanged", "widgets", "hand(t.second / 60.0, 0.85f, 2.5f, scheme.primary)", "hand(t.second / 60.0, 0.9f, 2.5f, scheme.primary)"),
    ("m10-timer-loses-pause", "t4_parity_1970", "screens", '{ ClockEngine.timer(ctx, t.id, "pause") }', "{ }"),
    ("m11-stopwatch-loses-share", "t4_parity_1970", "screens", "modifier = Modifier.testTag(ClockTags.SHARE_LAPS)", "modifier = Modifier"),
    ("m12-ring-loses-snooze", "t4_parity_1970", "ring", "{ ClockEngine.snooze(ctx, r.key) }", "{ }"),
    ("m13-background-grey", "t5_visual_tokens", "look", "const val BACKGROUND = 0xFF000000L", "const val BACKGROUND = 0xFF202020L"),
    ("m14-no-purple-glow", "t5_visual_tokens", "lookui", "    glow(ClockLook.GLOW_PURPLE, ClockLook.GLOW_PURPLE_AT)\n", ""),
    ("m15-accent-green", "t5_visual_tokens", "look", "const val ACCENT = 0xFFB5A5C2L", "const val ACCENT = 0xFF66BB6AL"),
    ("m16-timer-light-theme", "t5_visual_tokens", "screens", "    ClockFrame(modifier.fillMaxSize()) {\n    BoxWithConstraints(", "    Box(modifier.fillMaxSize()) {\n    BoxWithConstraints("),
    ("m17-calc-keeps-alarms", "t6_calc_world_only", "subpages", 'val CALC: List<String> = listOf("world")', 'val CALC: List<String> = listOf("world", "alarms")'),
    ("m18-calc-no-hand-over", "t6_calc_world_only", "calc_main", "state.handOff = { id -> com.diegonmarcos.clocktools.ClockLink.handOff(this, id) }", ""),
    ("m19-tasks-loses-pomodoro", "t6_calc_world_only", "subpages", ', "pomodoro")', ")"),
    ("m20-pomodoro-decl-not-moved", "t6_calc_world_only", "tasks", '"id": "pomodoro"', '"id": "pomodorox"'),
]

def run(f): return {n: fn(f) for n, fn in TESTS}

base = load(ROOT); results = run(base); fails = 0; cases = []
if os.environ.get("PRINT_PINS"):
    for n, p in world_parts(base).items(): print("PIN", n, digest(p))
for n, r in results.items():
    print(("ok   " if not r else "FAIL ") + n + ("" if not r else ": " + "; ".join(r)))
    fails += bool(r); cases.append(("tests", n, "; ".join(r) or None))
for name, target, key, old, new in MUTATIONS:
    f = dict(base)
    if old not in f[key]:
        msg = "mutation text not found in %s (tester out of date)" % FILES[key]; print("FAIL " + name + ": " + msg); fails += 1
        cases.append(("mutations", name, msg)); continue
    f[key] = f[key].replace(old, new, 1)
    red = [n for n, r in run(f).items() if r]
    msg = None if target in red else "mutation did not turn %s red (red: %s)" % (target, red)
    print(("ok   " if not msg else "FAIL ") + name + " turns " + target + " red" + ("" if not msg else " -- " + msg))
    fails += bool(msg); cases.append(("mutations", name, msg))
if JUNIT:
    with open(JUNIT, "w", encoding="utf-8") as h:
        h.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="clock-1986" tests="%d" failures="%d">\n' % (len(cases), sum(1 for c in cases if c[2])))
        for cl, n, m in cases:
            h.write('  <testcase classname=%s name=%s>%s</testcase>\n' % (quoteattr(cl), quoteattr(n), "" if not m else "<failure message=%s/>" % quoteattr(m)))
        h.write("</testsuite>\n")
print("all passed" if not fails else "%d failed" % fails)
sys.exit(1 if fails else 0)
PY
