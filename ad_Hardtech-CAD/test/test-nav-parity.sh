#!/usr/bin/env bash
# #1777 Hardtech-CAD nav parity (rule 16: a reorganisation drops nothing). The bottom nav became Models | Home | Sim2Real; the former MCAD,
# BIM and SolidWorks sections are sub-pages of Models and the pages of the former simulation app (SAP2000, MATLAB, Ansys) are sub-pages of
# Sim2Real. This holds the new build.json to the frozen BEFORE state (test/data/nav-before-1777.json):
#   N1 the bottom nav is exactly models, home, sim2real in that order; each is a declared section with a label and an icon of the shared catalogue
#   N2 every former CAD section (mcad, bim, solidworks) is a sub-page of Models with the same label and icon (nothing dropped, nothing renamed)
#   N3 every former simulation page (sap2000, matlab, ansys) is a sub-page of Sim2Real with the same label and icon
#   N4 every former stack entry is still there, field for field, under the same page id (CAD pages) / its page id (simulation pages, where the
#      planned placeholder gained its integration cards but kept its own entry unchanged); the model libraries (ui.libraries) and the models repo
#      are unchanged
#   N5 Home opens every page: one `nav` entry per sub-page, each pointing at a declared section and page
#   N6 the sub-pages are drawn by the SHARED second row: AppShell calls NavBarTopSecondRows and Decl/Kotlin names no page id
#   N7 every sub-page id is unique in the app and every page has a stack
#   N8 the hidden About section is still declared and off the bar
# then plants a mutation per rule in a scratch copy and requires RED (a tester that cannot fail does not count).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/build.json" ] && [ -f "$HERE/data/nav-before-1777.json" ] || { echo "ERROR: build.json or the baseline is missing - zero checks ran"; exit 1; }

check() {   # check <app dir> -> FAIL lines, exit 1 on any
python3 - "$1" "$HERE/data/nav-before-1777.json" "$ROOT" <<'PY'
import json, os, re, sys
app, base_p, root = sys.argv[1:4]
bad = []
new = json.load(open(os.path.join(app, "build.json")))["ui"]
base = json.load(open(base_p))
secs = {s["id"]: s for s in new["sections"]}
def page(sec, pid):
    return next((p for p in secs.get(sec, {}).get("pages", []) if p["id"] == pid), None)
if new["bottom_nav"] != ["models", "home", "sim2real"]: bad.append("N1 bottom_nav is %s, want models, home, sim2real" % new["bottom_nav"])
labels = {"models": "Models", "home": "Home", "sim2real": "Sim2Real"}
icons = set(re.findall(r'^\s*"([a-z0-9_]+)"\s+to', open(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt")).read(), re.M))
for k, v in labels.items():
    if secs.get(k, {}).get("label") != v: bad.append("N1 section %s is labelled %r, want %r" % (k, secs.get(k, {}).get("label"), v))
    if secs.get(k, {}).get("icon") not in icons: bad.append("N1 section %s icon %r is not in the libs:ui-icons catalogue" % (k, secs.get(k, {}).get("icon")))
if new.get("default_section") not in new["bottom_nav"]: bad.append("N1 default_section is not on the bottom bar")
old_pages = {}
for s in base["cad"]["sections"]:
    if s["id"] != "about": old_pages[s["id"]] = ("models", s)
for s in base["simulation"]["sections"]:
    if s["id"] != "about": old_pages[s["id"]] = ("sim2real", s)
for pid, (sec, s) in old_pages.items():
    p = page(sec, pid)
    if p is None: bad.append("N2/N3 %s is no sub-page of %s (the former page was dropped)" % (pid, sec)); continue
    if p.get("label") != s.get("label") or p.get("icon") != s.get("icon"): bad.append("N2/N3 sub-page %s is %r/%r, was %r/%r" % (pid, p.get("label"), p.get("icon"), s.get("label"), s.get("icon")))
for pid in base["cad"]["stacks"]:
    if pid == "about": continue
    got = new["stacks"].get(pid)
    if got is None: bad.append("N4 the stack of %s is gone" % pid); continue
    for i, e in enumerate(base["cad"]["stacks"][pid]):
        if e not in got: bad.append("N4 %s entry %s %r is no longer there unchanged" % (pid, i, e.get("title")))
    if [e for e in base["cad"]["stacks"][pid]] != got[:len(base["cad"]["stacks"][pid])]: bad.append("N4 %s entries changed order or were interleaved" % pid)
for pid in ("sap2000", "matlab", "ansys"):
    old = base["simulation"]["stacks"][pid][0]
    got = new["stacks"].get(pid, [])
    keep = {k: v for k, v in old.items() if k not in ("subtitle", "note")}
    if not got or any(got[0].get(k) != v for k, v in keep.items()): bad.append("N4 the %s entry (kind, title, planned) of the former simulation app is not the first entry of its page" % pid)
    if not got or not got[0].get("subtitle") or not got[0].get("note"): bad.append("N4 the %s entry lost its description or its planned note" % pid)
if base["cad"]["libraries"] != new.get("libraries"): bad.append("N4 ui.libraries changed")
if base["cad"]["model_repo"] != new.get("model_repo"): bad.append("N4 ui.model_repo changed")
homes = [e for e in new["stacks"].get("home", []) if e.get("kind") == "nav"]
want = [(s, p["id"]) for s in ("models", "sim2real") for p in secs.get(s, {}).get("pages", [])]
if [(e.get("section"), e.get("page")) for e in homes] != want: bad.append("N5 Home opens %s, want every sub-page %s" % ([(e.get("section"), e.get("page")) for e in homes], want))
for e in homes:
    if page(e.get("section"), e.get("page")) is None: bad.append("N5 Home entry %r points at no declared page" % e.get("title"))
    if not e.get("subtitle"): bad.append("N5 Home entry %r has no description" % e.get("title"))
ids = [p["id"] for s in new["sections"] for p in s.get("pages", [])]
if len(ids) != len(set(ids)): bad.append("N7 a sub-page id is used twice: %s" % sorted(i for i in set(ids) if ids.count(i) > 1))
for i in ids + [s["id"] for s in new["sections"] if not s.get("pages")]:
    if i not in new["stacks"]: bad.append("N7 page %s has no stack" % i)
if "about" not in secs or "about" in new["bottom_nav"]: bad.append("N8 the hidden about section is missing or on the bar")
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/hardtechcad")
shell = open(os.path.join(kt, "ui/AppShell.kt")).read()
if "NavBarTopSecondRows(" not in shell: bad.append("N6 AppShell does not draw the sub-pages with the shared NavBarTopSecondRows")
for dp, _, fs in os.walk(kt):
    for f in fs:
        if f.endswith(".kt"):
            t = open(os.path.join(dp, f)).read()
            for pid in ids:
                if re.search(r'"%s"' % re.escape(pid), t) and f not in ("ModelsPage.kt", "LibraryPage.kt", "OpenedModel.kt"):
                    if pid in ("mcad", "bim", "solidworks", "sap2000", "matlab", "ansys", "home", "models", "sim2real"): bad.append("N6 %s names the page id %r (ids belong to build.json)" % (f, pid))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] nav parity (#1777)"
out="$(check "$APP")" && ok "N1-N8 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <python edit of d (build.json)> <needle>
  rm -rf "${W:?}/app"; mkdir -p "$W/app"; cp -r "$APP/build.json" "$W/app/"; mkdir -p "$W/app/app/src/main/java/com/diegonmarcos"; cp -r "$APP/app/src/main/java/com/diegonmarcos/hardtechcad" "$W/app/app/src/main/java/com/diegonmarcos/"
  ( cd "$W/app" && python3 -c "import json;p='build.json';d=json.load(open(p));u=d['ui'];$2;json.dump(d,open(p,'w'),indent=2)" ) || { bad "mutant '$1' could not be planted"; return; }
  if o="$(check "$W/app" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
mutant "the bar loses Home"                  "u['bottom_nav'].remove('home')"                                                                        "N1 bottom_nav"
mutant "Models is renamed"                   "[s.__setitem__('label','Mod') for s in u['sections'] if s['id']=='models']"                           "N1 section models"
mutant "BIM is dropped from Models"          "[s['pages'].pop(1) for s in u['sections'] if s['id']=='models']"                                       "N2/N3 bim"
mutant "Ansys is dropped from Sim2Real"      "[s['pages'].pop() for s in u['sections'] if s['id']=='sim2real']"                                      "N2/N3 ansys"
mutant "a sub-page changes its icon"         "[p.__setitem__('icon','speed') for s in u['sections'] for p in s['pages'] if p['id']=='mcad']"          "N2/N3 sub-page mcad"
mutant "an MCAD entry vanishes"              "u['stacks']['mcad'].pop(1)"                                                                            "N4 mcad"
mutant "an MCAD entry is edited"             "u['stacks']['mcad'][0]['subtitle']='x'"                                                                "N4 mcad entry 0"
mutant "a model library is dropped"          "u['libraries'].pop('boats')"                                                                           "N4 ui.libraries changed"
mutant "a simulation entry loses its note"   "u['stacks']['matlab'][0].pop('note')"                                                                  "N4 the matlab entry lost"
mutant "Home forgets a page"                 "u['stacks']['home'].pop(4)"                                                                            "N5 Home opens"
mutant "Home points at nothing"              "[e.__setitem__('page','nope') for e in u['stacks']['home'] if e.get('title')=='BIM']"                  "N5 Home entry"
mutant "a page id is used twice"             "u['sections'][2]['pages'][0]['id']='mcad'"                                                             "N7 a sub-page id is used twice"
mutant "a page loses its stack"              "u['stacks'].pop('ansys')"                                                                              "N7 page ansys has no stack"
mutant "about leaves the declaration"        "u['sections']=[s for s in u['sections'] if s['id']!='about']"                                          "N8"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
