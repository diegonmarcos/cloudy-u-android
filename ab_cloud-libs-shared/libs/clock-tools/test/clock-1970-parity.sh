#!/usr/bin/env bash
# #1970 rule 16 for the Clock redesign: every control and feature of the screens before #1970 still exists (P1-P6), the new
# ones are wired (N1-N5), and each test has a mutation on a scratch copy that must turn it red.
# Usage: clock-1970-parity.sh   (from anywhere; reads the monorepo the script lives in)
#   JUNIT_OUT=<file>   also writes a JUnit XML (the CI job reads the verdict from it, never from this script's exit code)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT="$(cd "$here/../../../.." && pwd)" JUNIT_OUT="${JUNIT_OUT:-}"
exec python3 -I - <<'PY'
import json, os, re, sys
from xml.sax.saxutils import quoteattr

ROOT, JUNIT = os.environ["ROOT"], os.environ.get("JUNIT_OUT", "")
LIB = "ab_cloud-libs-shared/libs/clock-tools/"
K = LIB + "src/main/java/com/diegonmarcos/clocktools/"
C = LIB + "core/src/main/kotlin/com/diegonmarcos/clocktools/"
T = LIB + "core/src/test/kotlin/com/diegonmarcos/clocktools/"
FILES = {
    "screens": K + "ui/ClockScreens.kt", "world": K + "ui/WorldClock.kt", "widgets": K + "ui/ClockWidgets.kt",
    "engine": K + "ClockEngine.kt", "service": K + "ClockService.kt", "ring": K + "RingActivity.kt", "manifest": LIB + "src/main/AndroidManifest.xml",
    "model": C + "ClockModel.kt", "logic": C + "ClockLogic.kt", "subpages": C + "ClockSubpages.kt", "pit": LIB + "core/build.gradle",
    "t_wheel": T + "WheelPresetsTest.kt", "t_pomo": T + "PomodoroTest.kt", "t_cal": T + "ClockCalendarTest.kt",
    "calc": "ac_cloud-calc/build.json", "calc_modes": "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/ui/ModeScreens.kt",
}

def load(root):
    out = {}
    for k, p in FILES.items():
        with open(os.path.join(root, p), encoding="utf-8") as h: out[k] = h.read()
    return out

def code(t): return re.sub(r"//[^\n]*|/\*.*?\*/", "", t, flags=re.S)

def body(src, fun):
    """The text of `fun <name>` up to the next top-level declaration."""
    i = src.find("fun " + fun + "(")
    if i < 0: return ""
    m = re.search(r"\n(?:@Composable\n)?(?:private |internal )?(?:fun|object|class) ", src[i + 1:])
    return src[i: i + 1 + m.start()] if m else src[i:]

def need(where, text, tokens, what):
    return ["%s lost %s: %s" % (where, what, t) for t in tokens if t not in text]

# P1-P6: what each screen had before #1970 (ClockScreens.kt at adc7f9976), by the calls / strings / tags that made it.
OLD = {
    "AlarmsScreen": ["RingingBar(d)", "PermissionBanners()", "ClockLogic.nextAlarm(", "R.string.clock_no_alarm", "R.string.clock_next_alarm",
                     "R.string.clock_snoozed_until", "ClockLogic.daysText(", "Switch(checked = a.enabled", "ClockEngine.saveAlarm(ctx, a.copy(enabled = on))",
                     "ClockTags.ADD_ALARM", "cfg.vibrateDefault", "cfg.snoozeDefault", "AlarmEditor("],
    "AlarmEditor": ["ClockEngine.saveAlarm(", "ClockEngine.deleteAlarm(ctx, initial.id)", "ClockTags.SAVE", "R.string.clock_cancel", "R.string.clock_delete",
                    "label = label.trim()", "days = days", "vibrate = vibrate", "sound = sound", "snoozeMinutes = snooze", "enabled = true",
                    "ClockLogic.dayBit(day)", "days xor bit", "RingtoneManager.ACTION_RINGTONE_PICKER", "EXTRA_RINGTONE_SHOW_SILENT",
                    "EXTRA_RINGTONE_SHOW_DEFAULT", "EXTRA_RINGTONE_EXISTING_URI", "Alarm.SILENT", "snoozeChoices", "soundTitle(ctx, sound)"],
    "PermissionBanners": ["PermsFixChip(listOf(\"notifications\", \"exact_alarm\")", "ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT", "R.string.clock_full_screen_off"],
    "TimersScreen": ["RingingBar(d)", "PermsFixChip(listOf(\"notifications\")", "ClockTools.config(ctx).timerPresets", "durationWords(sec)",
                     "ClockEngine.addTimer(ctx, label.trim(), total, start = true)", "enabled = total > 0", "ClockTags.START_TIMER", "R.string.clock_label",
                     "ClockEngine.timer(ctx, t.id, \"pause\")", "ClockEngine.timer(ctx, t.id, \"start\")", "ClockEngine.timer(ctx, t.id, \"add\")",
                     "ClockEngine.timer(ctx, t.id, \"reset\")", "ClockEngine.timer(ctx, t.id, \"delete\")", "ClockEngine.dismiss(ctx, ClockLogic.timerKey(t.id))",
                     "R.string.clock_times_up", "ClockLogic.countdown(ClockLogic.remaining(t, now))"],
    "StopwatchScreen": ["ClockEngine.stopwatch(ctx, \"toggle\")", "ClockEngine.stopwatch(ctx, \"lap\")", "ClockEngine.stopwatch(ctx, \"reset\")",
                        "ClockLogic.stopwatchText(elapsed)", "ClockLogic.lapTimes(sw)", "R.string.clock_lap_n", "ClockLogic.stopwatchText(sw.laps[i])",
                        "R.string.clock_pause", "R.string.clock_start", "ClockTags.STOPWATCH"],
    "IntervalScreen": ["RingingBar(d)", "PermsFixChip(listOf(\"notifications\")", "ClockLogic.phaseAt(", "ClockNotifications.phaseName(", "R.string.clock_round",
                       "R.string.clock_session_left", "ClockEngine.stopInterval(ctx)", "intervalPresets", "ClockEngine.startInterval(ctx, p)",
                       "R.string.clock_work_min", "R.string.clock_rest_min", "R.string.clock_rounds", "R.string.clock_custom", "ClockTags.INTERVAL"],
    "BedtimeScreen": ["R.string.clock_sleep_timer", "R.string.clock_sleep_timer_doc", "ClockEngine.cancelSleep(ctx)", "sleepChoices", "ClockEngine.startSleep(ctx, m)",
                      "R.string.clock_bedtime", "quiet = true", "days = ClockLogic.ALL_DAYS", "bedtimeDefault", "ClockLogic.sleepWindow(", "R.string.clock_sleep_window", "ClockTags.BEDTIME"],
}
OLD_TAGS = ["WORLD", "ALARMS", "ADD_ALARM", "SAVE", "TIMERS", "START_TIMER", "STOPWATCH", "INTERVAL", "BEDTIME", "REF_NOW", "REF_DATE", "REF_TIME", "TABLE",
            "ADD_CITY", "EDIT_DEFAULT", "FULL", "fun row(", "fun bar(", "fun setDefault(", "fun tab("]
OLD_WORLD = ["ClockTags.REF_NOW", "ClockTags.REF_DATE", "ClockTags.REF_TIME", "ClockTags.ADD_CITY", "ClockTags.EDIT_DEFAULT", "host.WorldMap(", "ClockEngine.setZones(",
             "ClockEngine.setHome(", "WorldTable.rows(", "R.string.clock_reference", "HHMMSS.format(t)", "DAY.format(t)"]
OLD_KEYS = ['"minute"', '"days"', '"enabled"', '"label"', '"vibrate"', '"sound"', '"snooze"', '"quiet"', '"snoozed_until"', '"duration"', '"state"', '"ends_at"',
            '"remaining"', '"started_at"', '"accumulated"', '"laps"', '"work"', '"rest"', '"rounds"', '"long_rest"', '"long_every"', '"sleep_until"', '"zones"',
            '"home"', '"ringing"', '"seq"']
OLD_IDS = ["world", "alarms", "timers", "stopwatch", "interval", "bedtime"]

def p1_screens(f):
    s = code(f["screens"]); bad = []
    for fun, toks in OLD.items():
        b = body(s, fun)
        if not b: bad.append("%s is gone" % fun); continue
        bad += need(fun, b, toks, "a control")
    return bad

def p2_tags(f):
    s = code(f["screens"]); i = s.find("object ClockTags")
    return need("ClockTags", s[i:] if i >= 0 else "", OLD_TAGS, "a tag")

def p3_world(f): return need("WorldClockScreen", body(code(f["world"]), "WorldClockScreen"), OLD_WORLD, "a control")

def p4_store(f):
    """Each old key is still written (encode) and still read (decode)."""
    m = code(f["model"]); enc = m[m.find("fun encode"):m.find("fun decode")]; dec = m[m.find("fun decode"):]
    return need("ClockCodec.encode", enc, [".put(" + k for k in OLD_KEYS], "a stored key") + need("ClockCodec.decode", dec, ["(" + k for k in OLD_KEYS], "a read key")

def p5_subpages(f):
    m = re.search(r"IDS[^=]*=\s*listOf\(([^)]*)\)", code(f["subpages"])); ids = re.findall(r'"(\w+)"', m.group(1)) if m else []
    full = body(code(f["world"]), "FullClock"); bad = []
    for i in OLD_IDS:
        if i not in ids: bad.append("ClockSubpages.IDS lost %s" % i)
        if not re.search(r'"%s"\s*->' % i, full): bad.append("FullClock no longer draws %s" % i)
    if ids[:len(OLD_IDS)] != OLD_IDS: bad.append("the old subpages moved: %s" % ids)
    return bad

def p6_service(f):
    s = code(f["service"]); e = code(f["engine"])
    return need("ClockService", s, ["\"interval_stop\"", "\"stopwatch_lap\"", "\"stopwatch_toggle\"", "\"sleep_cancel\"", "\"dismiss_all\"", "\"snooze\"", "\"timer_add\"",
                                    "setFullScreenIntent("], "a notification action") + \
           need("ClockEngine.control", body(e, "control"), ["\"timer_start\"", "\"timer_pause\"", "\"timer_reset\"", "\"interval_stop\"", "\"sleep_cancel\""], "an op")

# N1-N5: the #1970 features
def n1_alarm_sheet(f):
    b = body(code(f["screens"]), "AlarmEditor"); bad = need("AlarmEditor", b, [
        "WheelPicker(hour, 0, 23", "WheelPicker(minute, 0, 59", "Wheel.minuteOfDay(hour, minute)", "ClockTags.ALARM_DATE", "CalendarGlyph()", "DatePicker(",
        "ClockTags.ALARM_NAME", "ClockTags.ROW_SOUND", "ClockTags.ROW_VIBRATION", "ClockTags.ROW_SNOOZE", "ClockTags.ROW_GROUP", "ClockTags.ROW_CALENDAR",
        "BackgroundPreview(", "ClockDesign.BACKGROUNDS", "ClockTags.CANCEL", "VerticalDivider(", "ClockDesign.snoozeText(", "ClockDesign.SNOOZE_TIMES",
        "ClockDesign.VIBRATIONS", "calendar = calendar", "group = group.trim()", "snoozeTimes = snoozeTimes"], "a sheet part")
    if not re.search(r"fun setCalendar\(on: Boolean\)[^}]*if \(on && !ClockCalendarProvider\.granted\(ctx\)\)", b, re.S):
        bad.append("WRITE_CALENDAR is not asked only when Add to calendar is switched on")
    return bad

def n2_calendar(f):
    e = code(f["engine"]); bad = []
    if "calendarSync(ctx, alarm)" not in body(e, "saveAlarm"): bad.append("saveAlarm does not sync the calendar event")
    if "ClockCalendar.remove(" not in body(e, "deleteAlarm"): bad.append("deleteAlarm does not delete the calendar event")
    if "android.permission.WRITE_CALENDAR" not in f["manifest"]: bad.append("the lib manifest lacks WRITE_CALENDAR")
    return bad

def n3_timer_stopwatch(f):
    s = code(f["screens"])
    return need("TimersScreen", body(s, "TimersScreen"), ["ClockTags.WHEEL_H", "ClockTags.WHEEL_M", "ClockTags.WHEEL_S", "Wheel.hmsToMs(h, m, s)",
                "TimerPresets.effective(", "TimerPresets.text(sec)", "onLongClick = { editPreset = i }", "RoundButton("], "a part") + \
           need("StopwatchScreen", body(s, "StopwatchScreen"), ["StopwatchDial(elapsed", "PagerDots(2", "HorizontalPager(", "shareLaps(ctx, sw)", "copyLaps(ctx, sw)"], "a part")

def n4_pomodoro(f):
    bad = []
    m = re.search(r"IDS[^=]*=\s*listOf\(([^)]*)\)", code(f["subpages"]))
    if not m or '"pomodoro"' not in m.group(1): bad.append("pomodoro is not in ClockSubpages.IDS")
    if not re.search(r'"pomodoro"\s*->\s*PomodoroScreen\(\)', body(code(f["world"]), "FullClock")): bad.append("FullClock does not draw Pomodoro")
    if not re.search(r'"pomodoro"\s*->\s*PomodoroScreen\(\)', code(f["calc_modes"])): bad.append("Cloud Calc has no Pomodoro renderer")
    calc = [x for x in json.loads(f["calc"])["ui"]["modes"] if x.get("kind") == "pomodoro"]
    if not calc or calc[0].get("clock", {}).get("work") != 25: bad.append("Cloud Calc does not declare the Pomodoro mode with its lengths")
    if "Wakeup(POMODORO" not in code(f["logic"]): bad.append("the Pomodoro is not on the timer engine's wakeups")
    if "d.pomodoro.running" not in code(f["service"]): bad.append("the service does not keep a Pomodoro alive")
    bad += need("PomodoroScreen", body(code(f["screens"]), "PomodoroScreen"), ["ProgressRing(PomodoroLogic.progress", "R.string.clock_pomodoro_counts",
                "PomodoroLogic.todayCount(", "ClockTags.POMODORO_AUTO", "\"skip\"", "\"reset\""], "a part")
    return bad

def n5_suites(f):
    bad = []
    pit = code(f["pit"])
    for c in ("Wheel*", "TimerPresets*", "Pomodoro*", "ClockCalendar*"):
        if "com.diegonmarcos.clocktools." + c not in pit: bad.append("PIT does not mutate %s" % c)
    for k, tok in (("t_wheel", "Wheel.valueAt("), ("t_wheel", "TimerPresets.parse("), ("t_pomo", "PomodoroLogic.advance("), ("t_cal", "class FakeProvider")):
        if tok not in f[k]: bad.append("%s lacks %s" % (FILES[k], tok))
    return bad

TESTS = [("p1_old_screen_controls", p1_screens), ("p2_old_tags", p2_tags), ("p3_world_clock", p3_world), ("p4_store_keys", p4_store),
         ("p5_old_subpages", p5_subpages), ("p6_notification_ops", p6_service), ("n1_alarm_sheet", n1_alarm_sheet), ("n2_calendar_sync", n2_calendar),
         ("n3_timer_stopwatch", n3_timer_stopwatch), ("n4_pomodoro", n4_pomodoro), ("n5_jvm_suites_and_pit", n5_suites)]

MUTATIONS = [  # name, the test it must turn red, file key, old text, new text
    ("m1-editor-loses-delete", "p1_old_screen_controls", "screens", "ClockEngine.deleteAlarm(ctx, initial.id); onDone()", "onDone()"),
    ("m2-editor-loses-ringtone-picker", "p1_old_screen_controls", "screens", "RingtoneManager.ACTION_RINGTONE_PICKER", "RingtoneManager.ACTION_PICK"),
    ("m3-timer-loses-plus-minute", "p1_old_screen_controls", "screens", 'ClockEngine.timer(ctx, t.id, "add")', "Unit"),
    ("m4-timer-quick-start-gone", "p1_old_screen_controls", "screens", "ClockTools.config(ctx).timerPresets", "emptyList<Long>()"),
    ("m5-stopwatch-loses-lap", "p1_old_screen_controls", "screens", 'ClockEngine.stopwatch(ctx, "lap")', "Unit"),
    ("m6-interval-loses-presets", "p1_old_screen_controls", "screens", "ClockTools.config(ctx).intervalPresets.forEach", "emptyList<ClockConfig.IntervalPreset>().forEach"),
    ("m7-bedtime-loses-sleep-chips", "p1_old_screen_controls", "screens", "ClockEngine.startSleep(ctx, m)", "Unit"),
    ("m8-tag-dropped", "p2_old_tags", "screens", '    const val REF_NOW = "clock_ref_now"\n', ""),
    ("m9-world-loses-add-city", "p3_world_clock", "world", "ClockTags.ADD_CITY", "ClockTags.TABLE"),
    ("m10-store-drops-snooze", "p4_store_keys", "model", '.put("snoozed_until", a.snoozedUntil)', ""),
    ("m11-subpage-dropped", "p5_old_subpages", "subpages", '"interval", ', ""),
    ("m12-notification-loses-lap", "p6_notification_ops", "service", '"stopwatch_lap"', '"stopwatch_x"'),
    ("m13-calendar-asked-always", "n1_alarm_sheet", "screens", "if (on && !ClockCalendarProvider.granted(ctx))", "if (!ClockCalendarProvider.granted(ctx))"),
    ("m14-save-drops-calendar", "n2_calendar_sync", "engine", "val a = calendarSync(ctx, alarm)", "val a = alarm"),
    ("m15-delete-keeps-event", "n2_calendar_sync", "engine", "ClockCalendar.remove(a, it)", "Unit"),
    ("m16-timer-wheels-gone", "n3_timer_stopwatch", "screens", "ClockTags.WHEEL_S", "ClockTags.TIMERS"),
    ("m17-stopwatch-no-dial", "n3_timer_stopwatch", "screens", "StopwatchDial(elapsed", "Box(Modifier.size(1.dp)) //"),
    ("m18-pomodoro-not-declared", "n4_pomodoro", "subpages", ', "pomodoro")', ")"),
    ("m19-pomodoro-off-engine", "n4_pomodoro", "logic", "Wakeup(POMODORO", "Wakeup(SLEEP"),
    ("m20-pit-skips-pomodoro", "n5_jvm_suites_and_pit", "pit", "'com.diegonmarcos.clocktools.Pomodoro*'", "'com.diegonmarcos.clocktools.Nothing*'"),
]

def run(f): return {n: fn(f) for n, fn in TESTS}

base = load(ROOT); results = run(base); fails = 0; cases = []
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
        h.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="clock-1970-parity" tests="%d" failures="%d">\n' % (len(cases), sum(1 for c in cases if c[2])))
        for cl, n, m in cases:
            h.write('  <testcase classname=%s name=%s>%s</testcase>\n' % (quoteattr(cl), quoteattr(n), "" if not m else "<failure message=%s/>" % quoteattr(m)))
        h.write("</testsuite>\n")
print("all passed" if not fails else "%d failed" % fails)
sys.exit(1 if fails else 0)
PY
