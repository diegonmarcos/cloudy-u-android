#!/usr/bin/env bash
# Tester: the status bar's calendar popup is the TASKS popup (was "Agenda | Calendar").
#
#   P1  it is called Tasks everywhere: build.json::tasks_popup.label, the tasks_label string in values and values-es, the popup's
#       title and the clock's content description; the old name and the old object are gone
#   P2  it opens on the Calendar view, declared (default_view), and the popup opens on the declared one
#   P3  the calendar shows 3 months, previous + current + next: months.count == 3 == before + 1 + after, and the popup reads it
#   P4  the Agenda and Todo tables are declared, fed from the cal engine (engines.cal; the methods exist in the engine), and
#       the popup draws them from that feed
#   P5  every size is declared and used; no literal popup width is left
#   P6  tapping a day filters the Agenda; the Todo checkbox follows the declared `complete`
#
# Usage: ./test-tasks-popup.sh   (static only, no network, no device)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$APP" <<'PY'
import json, re, sys, os
app = sys.argv[1]
fails = []
def ok(c, good, bad):
    print(("ok   " if c else "FAIL ") + (good if c else bad))
    if not c: fails.append(bad)
def read(p): return open(f"{app}/{p}", encoding="utf-8").read()
def code(t): return re.sub(r'/\*.*?\*/|//[^\n]*', '', t, flags=re.S)
b = json.load(open(f"{app}/build.json", encoding="utf-8"))
tp = b.get("tasks_popup", {})
kt = "app/src/main/java/com/diegonmarcos/superapp/cal"
pop = read(f"{kt}/TasksPopup.kt"); pc = code(pop)
ok(tp.get("label") == "Tasks", "P1: build.json::tasks_popup.label is Tasks", f"P1: label is {tp.get('label')!r}")
en = re.search(r'name="tasks_label">([^<]*)<', read("app/src/main/res/values/strings.xml"))
es = re.search(r'name="tasks_label">([^<]*)<', read("app/src/main/res/values-es/strings.xml"))
ok(en and en.group(1) == tp.get("label") and es, "P1: tasks_label is Tasks in values and present in values-es", "P1: tasks_label missing or different from the declared label")
ok("stringResource(R.string.tasks_label)" in pc, "P1: the popup title is the string resource", "P1: the popup title is not R.string.tasks_label")
stripsrc = read("app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt")
ok("R.string.tasks_label" in stripsrc and "contentDescription" in stripsrc, "P1: the clock's content description names Tasks", "P1: the clock has no Tasks content description")
leftover = []
for root, _, files in os.walk(f"{app}/app/src"):
    for f in files:
        if f.endswith((".kt", ".xml")):
            t = code(open(os.path.join(root, f), encoding="utf-8", errors="ignore").read())
            if "CalendarAgendaPopup" in t or "Agenda | Calendar" in t: leftover.append(f)
ok(not leftover and not os.path.exists(f"{app}/{kt}/CalendarAgendaPopup.kt"), "P1: the old CalendarAgendaPopup name is gone", f"P1: the old name is still in {leftover}")
ok(tp.get("default_view") == "calendar" and any(v["id"] == "calendar" for v in tp.get("views", [])), "P2: default_view is calendar and a declared view", "P2: default_view is not calendar")
ok("mutableStateOf(decl.defaultView)" in pc, "P2: the popup opens on the declared view", "P2: the popup does not open on decl.defaultView")
m = tp.get("months", {})
ok(m.get("count") == 3 and m.get("count") == m.get("before", -9) + 1 + m.get("after", -9), "P3: months.count is 3 = before + 1 + after", f"P3: months {m}")
ok("TasksModel.months(today, decl.monthsBefore, decl.monthsAfter)" in pc, "P3: the popup stacks the declared months", "P3: the popup does not draw the declared months")
d = read(f"{kt}/TasksPopupDecl.kt")
ok("count == before + 1 + after" in d, "P3: the parser refuses a count that is not before + 1 + after", "P3: the parser does not enforce the month count")
tb = tp.get("tables", {})
ok(set(tb) == {"agenda", "todo"} and tb["agenda"]["columns"] == ["date", "time", "title"], "P4: Agenda (date, time, title) and Todo are declared", f"P4: tables {list(tb)}")
eng = b.get("engines", {}).get("cal", {})
fleet = json.load(open(f"{app}/data/constellation-fleet.json"))
ok(eng.get("fleet") == "lib-cal" and any(a["id"] == "lib-cal" for a in fleet["apps"]), "P4: engines.cal binds the Store's Cloud-Lib-Cal row", "P4: engines.cal is missing or names a row the fleet does not have")
svc = read("../ab_cloud-libs-shared/libs/cal/src/main/java/com/diegonmarcos/superapp/cal/CalBackendService.kt")
meths = re.search(r'arrayOf\(([^)]*)\)', svc, re.S).group(1)
feed = read(f"{kt}/TasksFeed.kt")
called = re.findall(r'const val (?:EVENTS|TODOS) = "([a-z]+)"', feed)
ok(sorted(called) == ["events", "todos"] and all(f'"{c}"' in meths for c in called), "P4: the feed calls events and todos, both methods of the engine", f"P4: feed calls {called}")
ok("TasksModel.agenda(events" in pc and "TasksModel.openTodos(todos" in pc and "source.events(" in pc and "source.todos()" in pc, "P4: both tables are drawn from the fed lists", "P4: a table is not fed from the source")
ok("DataBackendClient(" in feed and "CONTRACT_KEY" in feed and "CalendarStore" not in feed + pc, "P4: the data is reached through the engine; no store of its own", "P4: the popup keeps its own store or skips the handshake")
for k in ("width_dp", "max_height_dp", "month_cell_dp", "table_height_dp", "table_height_agenda_view_dp"):
    ok(k in tp.get("layout", {}), f"P5: layout.{k} declared", f"P5: layout.{k} missing")
ok("decl.widthDp.dp" in pc and "decl.maxHeightDp" in pc and "decl.monthCellDp" in pc and "decl.tableHeightDp" in pc and not re.search(r'width\(\d+\.dp\)\.testTag|KitBubble\(Modifier\.width\(\d', pc), "P5: the popup is sized by the declaration", "P5: a literal popup size is left")
ok("onDay: (LocalDate) -> Unit" in pc and "picked = if (picked == d) null else d" in pc and "TasksModel.agenda(events, today, day" in pc and "TasksModel.agendaDay(view, picked, today)" in pc, "P6: tapping a day filters the Agenda (tap again clears)", "P6: the day tap does not filter the Agenda")
ok(tb.get("todo", {}).get("complete") is False and "enabled = decl.todoComplete" in pc, "P6: the Todo checkbox follows the declared `complete`", "P6: the Todo checkbox ignores the declaration")
ok("TASKS_POPUP_B64" in read("app/build.gradle"), "P6: the declaration is baked", "P6: TASKS_POPUP_B64 not baked")
sys.exit(1 if fails else 0)
PY
