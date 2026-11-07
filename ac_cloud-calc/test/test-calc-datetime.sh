#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1329 Cloud Calc > Tools > Date & Time: working days, holidays, calendar   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   D1  the forms are DECLARED: Days between (a date RANGE from/to, an "end included" toggle, a weekend choice defaulting to
#       Sat + Sun and offering Fri + Sat, a region choice defaulting to the device country), the engine's Days and Weeks kept,
#       Working days / Weekend days / Holidays counted with both end conventions in their labels; Add days (a date field) and
#       Add working days (a date, a count, weekend, region); each form's `bind` names fields it has.
#   D2  the declaration is WIRED: ModeScreens dispatches the date, toggle and choice field types to the widgets and counts the
#       `calc` outputs with DateForms; Declarations parses type, range_end, options, default_from; the holiday asset is in the
#       data/assets srcDir the datetime spec names.
#   D3  the HOLIDAYS are declared rules, offline: >= 10 regions, Spain national with its ten days, every `extends` resolves,
#       each rule is exactly one of fixed / Easter offset / nth weekday, a community adds days.
#   D4  OFFLINE and PURE: no address in the data or the three Kotlin files, WorkingDays.kt and DateForms.kt import nothing of
#       Android or Compose (they are the JVM-tested part), and no View widget (compose ratchet) in the calendar.
#   D5  the licence: NOTICE.md states the approach (declared rules, no library), names Jollyday and why it was not taken.
#   D6  the JVM suite exists: WorkingDaysTest names the properties (ES 2026 count, weekend range, end convention, custom weekend,
#       region None, range taps, date field type).
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# OWN-SOURCE ONLY (reads ac_cloud-calc): python3 + grep. No pipe into `grep -q` under pipefail.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 required"; exit 2; }

J='app/src/main/java/com/diegonmarcos/cloudcalc'
FILES="build.json NOTICE.md app/build.gradle $J/WorkingDays.kt $J/DateForms.kt $J/Declarations.kt $J/ui/ModeScreens.kt $J/ui/DateFields.kt app/src/test/java/com/diegonmarcos/cloudcalc/WorkingDaysTest.kt"

CHECK="$(mktemp)"
cat > "$CHECK" <<'PY'
import json, os, re, sys
app = sys.argv[1]
J = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcalc")
bad = []
def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except OSError:
        bad.append("missing file %s" % os.path.relpath(p, app)); return ""
# #1547 the holiday rules are a runtime dataset (Cloud Store), the repo file data/fleet/app/<pkg>/<dataset>.json: in the tree one level up, in a mutation copy beside the app.
def holp(h):
    c = [os.path.join(b, "data/fleet/app/com.diegonmarcos.cloudcalc", h) for b in (app, os.path.join(app, ".."))]
    return next((x for x in c if os.path.isfile(x)), c[0])
def code(s):
    return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
bj = json.loads(read(os.path.join(app, "build.json")) or "{}")
mode = next((m for m in bj.get("ui", {}).get("modes", []) if m.get("id") == "datetime"), {})
forms = {f.get("id"): f for f in mode.get("forms", [])}
def fld(form, i):
    return next((x for x in forms.get(form, {}).get("fields", []) if x.get("id") == i), {})

# D1
for f in ("between", "add", "add_working"):
    if f not in forms:
        bad.append("D1 the datetime mode has no form %s" % f)
b = forms.get("between", {})
if fld("between", "from").get("type") != "date" or fld("between", "from").get("range_end") != "to" or fld("between", "to").get("type") != "date":
    bad.append("D1 Days between must declare from (type date, range_end to) and to (type date)")
if fld("between", "inclusive").get("type") != "toggle":
    bad.append("D1 Days between must declare the inclusive toggle")
wk = fld("between", "weekend")
if wk.get("type") != "choice" or wk.get("default") != "SAT,SUN" or "FRI,SAT" not in [o.get("value") for o in wk.get("options", [])]:
    bad.append("D1 the weekend choice must default to SAT,SUN and offer FRI,SAT")
rg = fld("between", "region")
if rg.get("type") != "choice" or rg.get("default_from") != "locale_country" or rg.get("options_from") != "holiday_regions":
    bad.append("D1 the region choice must default from locale_country and take holiday_regions")
outs = b.get("outputs", [])
engine = [o for o in outs if "calc" not in o]
if [o.get("label") for o in engine] != ["Days", "Weeks"] or not all(str(o.get("expr", "")).startswith("days(") for o in engine):
    bad.append("D1 Days between must keep the engine's Days and Weeks (days())")
counted = [o for o in outs if o.get("calc") == "span"]
if [o.get("metric") for o in counted] != ["working", "weekend", "holidays"]:
    bad.append("D1 Days between must count working, weekend and holidays")
for o in counted:
    if "end included" not in o.get("label_on", "") or "end excluded" not in o.get("label_off", ""):
        bad.append("D1 output %s must state the end convention in label_on / label_off" % o.get("label"))
if fld("add", "date").get("type") != "date":
    bad.append("D1 Add days must use a date field")
aw = forms.get("add_working", {})
if [o.get("calc") for o in aw.get("outputs", [])] != ["add_working"] or fld("add_working", "date").get("type") != "date":
    bad.append("D1 Add working days must be a date plus an add_working output")
for fid, f in forms.items():
    for role, target in f.get("bind", {}).items():
        if not fld(fid, target):
            bad.append("D1 form %s binds %s to the missing field %s" % (fid, role, target))
hol = mode.get("spec", {}).get("holidays", "")
if not hol or not os.path.isfile(holp(hol)):
    bad.append("D1 the datetime spec must name a holiday asset (a dataset under data/fleet/app/<package>), found %r" % hol)

# D2
ms = code(read(os.path.join(J, "ui/ModeScreens.kt")))
for t in ('"date" ->', '"toggle" ->', '"choice" ->'):
    if t not in ms:
        bad.append("D2 ModeScreens.kt does not dispatch the field type %s" % t.split('"')[1])
if "DateForms.result(" not in ms:
    bad.append("D2 ModeScreens.kt does not count the calc outputs with DateForms")
dc = code(read(os.path.join(J, "Declarations.kt")))
for k in ('"range_end"', '"default_from"', '"options_from"', '"label_on"', '"calc"'):
    if k not in dc:
        bad.append("D2 Declarations.kt does not parse %s" % k)
if "assets.srcDirs += ['../data/assets']" not in read(os.path.join(app, "app/build.gradle")):
    bad.append("D2 app/build.gradle does not add the data/assets srcDir")

# D3
data = json.loads(read(holp("datetime/holidays.json")) or '{"regions": []}')
regions = {r["id"]: r for r in data.get("regions", [])}
if len(regions) < 10:
    bad.append("D3 the holiday data declares %d regions, wants >= 10" % len(regions))
es = regions.get("ES", {})
if len(es.get("rules", [])) != 10 or es.get("country") != "ES":
    bad.append("D3 Spain (national) must declare its ten national days and country ES")
if not any(r.get("easter") == -2 for r in es.get("rules", [])):
    bad.append("D3 Spain must have Good Friday as an Easter offset of -2")
if not any(r.get("extends") == "ES" for r in regions.values()):
    bad.append("D3 no autonomous community extends ES")
for r in regions.values():
    if r.get("extends") and r["extends"] not in regions:
        bad.append("D3 region %s extends the undeclared %s" % (r["id"], r["extends"]))
    for rule in r.get("rules", []):
        kinds = [k for k in ("day", "easter", "nth") if k in rule]
        if len(kinds) != 1 or not rule.get("name"):
            bad.append("D3 a rule of %s is not exactly one of fixed / easter / nth, or unnamed: %s" % (r["id"], rule))

# D4
for p in (holp("datetime/holidays.json"), J + "/WorkingDays.kt", J + "/DateForms.kt", J + "/ui/DateFields.kt"):
    full = p if os.path.isabs(p) else os.path.join(app, p)
    if re.search(r"https?://|java\.net\.|HttpURLConnection", read(full)):
        bad.append("D4 %s names an address or a network class" % os.path.basename(p))
for k in ("WorkingDays.kt", "DateForms.kt"):
    if re.search(r"^import (android|androidx)\.", read(os.path.join(J, k)), flags=re.M):
        bad.append("D4 %s imports Android or Compose: it is the JVM-tested part" % k)
if re.search(r"TextView\(|AndroidView\(|CalendarView|DatePickerDialog", code(read(os.path.join(J, "ui/DateFields.kt")))):
    bad.append("D4 DateFields.kt builds a View widget (the compose ratchet counts them)")

# D5
notice = read(os.path.join(app, "NOTICE.md"))
if "## Date & Time: working days and holidays" not in notice or "holidays.json" not in notice or "Jollyday (Apache-2.0)" not in notice:
    bad.append("D5 NOTICE.md has no Date & Time holiday section naming holidays.json and Jollyday (Apache-2.0)")

# D6
t = read(os.path.join(app, "app/src/test/java/com/diegonmarcos/cloudcalc/WorkingDaysTest.kt"))
for want in ("Spain 2026 has 254 working days", "a range inside one weekend", "end date is excluded unless", "custom weekend", "region None", "first tap is the start", "tapping before the start swaps", "the date field type parses"):
    if want not in t:
        bad.append("D6 WorkingDaysTest has no test named like %r" % want)

for b_ in bad:
    print("  FAIL  " + b_)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── D1-D6 against the tree ──"
if python3 -I "$CHECK" "$APP"; then echo "  PASS  D1-D6"; else FAILURES=$((FAILURES + 1)); fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK" "$CHECK"' EXIT
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    (cd "$APP" && cp --parents $FILES "$copy/")
    mkdir -p "$copy/$(dirname "$H")" && cp "$APP/../$H" "$copy/$H"
    if ! python3 -I - "$copy/$rel" "$expr" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
t = eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land — the mutation targets text that moved"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 -I "$CHECK" "$copy" 2>&1)"
    if [ $? -eq 0 ]; then
        echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then
        echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else
        echo "  PASS  MUT $name"
    fi
}
H='data/fleet/app/com.diegonmarcos.cloudcalc/datetime/holidays.json'
mutate range-end-dropped build.json 's.replace("\"range_end\": \"to\"", "\"range_endx\": \"to\"")' "D1 Days between must declare from"
mutate toggle-not-a-toggle build.json 's.replace("\"type\": \"toggle\"", "\"type\": \"text\"")' "D1 Days between must declare the inclusive toggle"
mutate weekend-default-changed build.json 's.replace("\"default\": \"SAT,SUN\"", "\"default\": \"FRI,SAT\"")' "D1 the weekend choice must default to SAT,SUN"
mutate region-default-gone build.json 's.replace("\"default_from\": \"locale_country\"", "\"default_from\": \"\"")' "D1 the region choice must default from locale_country"
mutate days-output-dropped build.json 's.replace("\"label\": \"Weeks\",\n                \"expr\"", "\"label\": \"Weeksx\",\n                \"expr\"")' "D1 Days between must keep the engine"
mutate end-convention-unstated build.json 's.replace("Working days (end included)", "Working days")' "D1 output Working days must state the end convention"
mutate add-working-form-gone build.json 's.replace("\"id\": \"add_working\"", "\"id\": \"add_workingx\"")' "D1 the datetime mode has no form add_working"
mutate bind-dangling build.json 's.replace("\"region\": \"region\"", "\"region\": \"area\"", 1)' "D1 form"
mutate spec-asset-moved build.json 's.replace("datetime/holidays.json", "datetime/gone.json")' "D1 the datetime spec must name a holiday asset"
mutate date-type-undispatched $J/ui/ModeScreens.kt 's.replace("\"date\" ->", "\"dat\" ->")' "D2 ModeScreens.kt does not dispatch the field type date"
mutate calc-not-counted $J/ui/ModeScreens.kt 's.replace("DateForms.result(", "Logic.result(")' "D2 ModeScreens.kt does not count the calc outputs"
mutate range-end-unparsed $J/Declarations.kt 's.replace("\"range_end\"", "\"range\"")' "D2 Declarations.kt does not parse \"range_end\""
mutate assets-not-wired app/build.gradle 's.replace("assets.srcDirs += [\x27../data/assets\x27]", "")' "D2 app/build.gradle does not add the data/assets srcDir"
mutate regions-collapsed $H 's.replace("\"id\": \"GB\"", "\"id\": \"IT\"").replace("\"id\": \"US\"", "\"id\": \"IT\"").replace("\"id\": \"DE\"", "\"id\": \"IT\"").replace("\"id\": \"FR\"", "\"id\": \"IT\"")' "D3 the holiday data declares 9 regions"
mutate spain-loses-a-day $H '__import__("re").sub(r"\{\s*\"name\": \"Todos los Santos\",[^}]*\},", "", s, count=1)' "D3 Spain (national) must declare its ten national days"
mutate spain-country-lost $H 's.replace("\"country\": \"ES\"", "\"country\": \"XX\"")' "D3 Spain (national) must declare its ten national days and country ES"
mutate good-friday-moved $H 's.replace("\"easter\": -2", "\"easter\": -1", 1)' "D3 Spain must have Good Friday"
mutate extends-dangling $H 's.replace("\"extends\": \"ES\"", "\"extends\": \"XX\"", 1)' "D3 region ES-MD extends the undeclared XX"
mutate rule-ambiguous $H 's.replace("{\n     \"name\": \"Jueves Santo\",\n     \"easter\": -3\n    }", "{\n     \"name\": \"Jueves Santo\",\n     \"easter\": -3,\n     \"day\": 1\n    }", 1)' "D3 a rule of"
mutate data-phones-home $H 's.replace("\"_doc\": \"", "\"_doc\": \"see https://example.org ")' "D4 holidays.json names an address"
mutate counter-reaches-android $J/WorkingDays.kt 's.replace("import java.time.DayOfWeek", "import android.util.Log\nimport java.time.DayOfWeek")' "D4 WorkingDays.kt imports Android"
mutate calendar-uses-a-view $J/ui/DateFields.kt 's + "\nprivate fun v(c: android.content.Context) = android.widget.CalendarView(c)\n"' "D4 DateFields.kt builds a View widget"
mutate notice-section-gone NOTICE.md 's.replace("## Date & Time: working days and holidays", "## Date")' "D5 NOTICE.md has no Date & Time holiday section"
mutate test-dropped app/src/test/java/com/diegonmarcos/cloudcalc/WorkingDaysTest.kt 's.replace("custom weekend", "weekday")' "D6 WorkingDaysTest has no test named like"

if [ "$FAILURES" -eq 0 ]; then echo "== RESULT: all date & time checks green, every mutation proven red =="; exit 0; fi
echo "== RESULT: $FAILURES failure(s) =="; exit 1
