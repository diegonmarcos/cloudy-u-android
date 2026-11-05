#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1484 — the Editor tab: five shared libs, linked both ends, an Office-   ║
# ║ free type table, the new navigation shape, app-side glue only            ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   E1  libs:editor-core, wysiwyg, media-viewer, pdf, odf, json-viewer and local-serve are in build.json::modules (a dir that exists),
#       in app.depends_on, linked with `implementation project(...)` in app/build.gradle, and watched by
#       the ship workflow source.
#   E2  editor-core's type table (editor-types.json) is data: the Microsoft Office formats are `unsupported`
#       and claimed by no viewer, media is view only, every viewer is one of the seven libs' ids.
#   E3  the module boundary: no lib of the five imports this application's package, and the app's Editor
#       glue (editor/) holds no viewer logic (no PdfBox, no zip/XML, no codec).
#   E4  the navigation: bottom nav is explorer/projects/editor/home/git/fsync/mounts, Home = home_apps|manage (Manage
#       keeps apps|data), Fsync = folders|agents (#1491: Projects is a bottom-nav page), no `manage` or `agents` section.
#   E5  the Projects page is DECLARED sample data (named sample, never the banned word).
#
# OWN-SOURCE ONLY. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
SHIP="$ROOT/1_cicd/src/cicd/ship-cloud-drive.yml"
NAMES="editor-core wysiwyg media-viewer pdf odf json-viewer local-serve"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$APP/build.json" "$APP/app/build.gradle" "$SRC/editor/EditorScreen.kt" "$LIBS/editor-core/src/main/resources/editor-types.json"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

echo "── E1 the seven libs are declared, linked and watched ──"
python3 - "$APP/build.json" "$APP/app/build.gradle" "$SHIP" "$LIBS" $NAMES <<'PYTHON' && pass "map, depends_on, implementation and ship paths agree for all seven" || fail "a lib is missing from the module map, the app's links or the ship paths"
import json, os, re, sys
bj, gradle, ship, libs, *names = sys.argv[1:]
j = json.load(open(bj, encoding="utf-8")); g = open(gradle, encoding="utf-8").read(); s = open(ship, encoding="utf-8").read() if os.path.exists(ship) else None
bad = 0
for n in names:
    m = j["modules"].get("libs:" + n)
    if not m or not os.path.isdir(os.path.normpath(os.path.join(os.path.dirname(bj), m.get("dir", "/nonexistent")))):
        print("    libs:%s is not in build.json::modules with a real dir" % n); bad = 1
    if "libs:" + n not in j["modules"]["app"]["depends_on"]:
        print("    libs:%s is not in app.depends_on" % n); bad = 1
    if not re.search(r"^\s*implementation project\(':libs:%s'\)" % re.escape(n), g, re.M):
        print("    app/build.gradle does not link libs:%s" % n); bad = 1
    if s is not None and ('"ab_cloud-libs-shared/libs/%s/**"' % n) not in s:
        print("    the ship workflow does not watch libs/%s/**" % n); bad = 1
sys.exit(bad)
PYTHON

echo "── E2 the type table is data and Office-free ──"
python3 - "$LIBS/editor-core/src/main/resources/editor-types.json" <<'PYTHON' && pass "Office formats unsupported, media view only, five known viewers" || fail "editor-types.json breaks the rules"
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
bad = 0
office = {"doc", "docx", "xls", "xlsx", "ppt", "pptx"}
refused = set(d["unsupported"]["extensions"])
if not office <= refused:
    print("    unsupported misses %s" % sorted(office - refused)); bad = 1
claimed = {e for v in d["viewers"] for exts in v["formats"].values() for e in exts}
if claimed & refused:
    print("    a viewer claims a refused extension: %s" % sorted(claimed & refused)); bad = 1
ids = {v["id"] for v in d["viewers"]}
if ids != {"wysiwyg", "json", "media", "pdf", "odf"}:
    print("    viewers are %s" % sorted(ids)); bad = 1
html = next(v for v in d["viewers"] if v["id"] == "wysiwyg")
if html.get("format_modes", {}).get("html") != ["View", "Edit", "Served"] or "Served" in html["modes"]:
    print("    html must offer View/Edit/Served and no other text type Served"); bad = 1
if any(v["id"] == "media" and "Edit" in v["modes"] for v in d["viewers"]):
    print("    media is editable: it is view only"); bad = 1
sys.exit(bad)
PYTHON

echo "── E3 the boundary: libs import no app, the glue holds no viewer logic ──"
hits="$(for n in $NAMES; do grep -rl 'com\.diegonmarcos\.clouddrive' "$LIBS/$n" 2>/dev/null; done)"
if [ -z "$hits" ]; then pass "no lib imports com.diegonmarcos.clouddrive"; else fail "a lib reaches back into the app: $hits"; fi
if grep -rlE 'com\.tom_roush|java\.util\.zip|javax\.xml|MarkdownCodec|HtmlCodec' "$SRC/editor" >/dev/null 2>&1; then fail "the Editor glue holds viewer logic (PdfBox, zip/XML or a codec)"; else pass "the Editor glue only picks the lib for a route"; fi

echo "── E4 the navigation shape ──"
python3 - "$APP/build.json" <<'PYTHON' && pass "bottom nav explorer/editor/home/git/fsync/mounts; Home and Fsync sub-pages as declared" || fail "the navigation is not the #1484 shape"
import json, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
sec = {s["id"]: s for s in ui["sections"]}
bad = 0
if ui["bottom_nav"] != ["explorer", "projects", "editor", "home", "git", "fsync", "mounts"]:
    print("    bottom_nav is %s" % ui["bottom_nav"]); bad = 1
if "manage" in sec or "agents" in sec:
    print("    manage/agents are sections again"); bad = 1
home = [p["id"] for p in sec["home"].get("pages", [])]
if home != ["home_apps", "manage"]: print("    home pages %s" % home); bad = 1
mp = [p["id"] for p in next((p for p in sec["home"].get("pages", []) if p["id"] == "manage"), {}).get("pages", [])]
if mp != ["apps", "data"]: print("    manage pages %s" % mp); bad = 1
fs = [p["id"] for p in sec["fsync"].get("pages", [])]
if fs != ["folders", "agents"]: print("    fsync pages %s" % fs); bad = 1
if sec["projects"].get("pages"): print("    projects declares sub-pages"); bad = 1
if sec["editor"].get("pages"): print("    editor declares sub-pages"); bad = 1
ids = [p["id"] for s in ui["sections"] for p in s.get("pages", [])]
for s in ui["sections"]:
    for p in s.get("pages", []):
        ids += [q["id"] for q in p.get("pages", [])]
dup = sorted({i for i in ids if ids.count(i) > 1} - {"cloud"})
if dup: print("    page ids used twice (N12): %s" % dup); bad = 1
sys.exit(bad)
PYTHON

echo "── E5 Projects is declared sample data ──"
P="$SRC/sync/ProjectsPage.kt"
if grep -q 'SAMPLE_PROJECTS' "$P" && ! grep -qi 'mock' "$P"; then pass "ProjectsPage draws SAMPLE_PROJECTS and says sample, never the banned word"; else fail "ProjectsPage is not declared sample data"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-editor: all checks passed"; exit 0; fi
echo "test-drive-editor: $FAILURES check(s) FAILED"; exit 1
