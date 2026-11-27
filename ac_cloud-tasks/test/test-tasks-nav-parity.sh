#!/usr/bin/env bash
# Cloud Tasks #1757 REORGANISE parity (owner rule 16: new UI, NO data field lost, fields only rearranged).
#
#   The navigation went from Inboxes | Backlogs | Tasks | Library | Projects to Inbox | Backlog | Home | Tasks | Library. This holds the NEW
#   declaration (build.json::ui) and the NEW fixture (assets/mock-tasks.json) against the frozen PREVIOUS ones (test/data/previous-nav-1757.json,
#   test/data/previous-mock-1757.json): every old section is still declared or mapped, every old page id is still reachable (same section, or the
#   place build.json::ui.nav_mapping says), every page keeps its label and icon, every old Calendar view and every old fixture row with every
#   old field is still there. It also checks the mapping is real (each target exists) and complete (nothing unmapped).
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export APP="$here/.."
exec python3 - <<'PY'
import json, os, sys

APP = os.environ["APP"]
fails = 0

def ok(msg): print("ok   " + msg)
def bad(msg):
    global fails
    fails += 1
    print("FAIL " + msg)
def check(cond, msg): (ok if cond else bad)(msg)

new = json.load(open(os.path.join(APP, "build.json")))["ui"]
old = json.load(open(os.path.join(APP, "test/data/previous-nav-1757.json")))
old_mock = json.load(open(os.path.join(APP, "test/data/previous-mock-1757.json")))
new_mock = json.load(open(os.path.join(APP, "app/src/debug/assets/mock-tasks.json")))

new_sections = {s["id"]: s for s in new["sections"]}
mapping = {k: v for k, v in new.get("nav_mapping", {}).items() if not k.startswith("_")}

# ── every old page is mapped, and the target exists with its label and icon ─────────────────────────────────
old_pages = {(s["id"], p["id"]): (s, p) for s in old["sections"] for p in s["pages"]}
check(set(mapping) == {"%s/%s" % k for k in old_pages}, "nav_mapping holds every previous page and nothing else (%d pages)" % len(old_pages))
for (sid, pid), (s, p) in sorted(old_pages.items()):
    tgt = mapping.get("%s/%s" % (sid, pid), "")
    ts, _, tp = tgt.partition("/")
    page = next((x for x in new_sections.get(ts, {}).get("pages", []) if x["id"] == tp), None)
    check(page is not None, "previous page %s/%s is reachable at %s" % (sid, pid, tgt))
    if page:
        check(page["label"] == p["label"] and page["icon"] == p["icon"], "  %s keeps its label (%s) and icon (%s)" % (tgt, p["label"], p["icon"]))

# ── every old section still declared, or folded into a named section ─────────────────────────────────────────
for s in old["sections"]:
    targets = {v.split("/")[0] for k, v in mapping.items() if k.startswith(s["id"] + "/")}
    check(len(targets) >= 1 and all(t in new_sections for t in targets), "previous section %s lives on in %s" % (s["id"], sorted(targets)))
# sections that kept their id keep their page order
for s in old["sections"]:
    if s["id"] in new_sections:
        # #1852 the shared pages (General, Perms, About) now follow the app's own pages; every page and the own pages' relative order stay
        SHARED = ("general", "perms", "about")
        nids = [p["id"] for p in new_sections[s["id"]]["pages"]]
        # #1964 a page that moved to another section (tasks/clock, library/library) is held by nav_mapping above, not by this order
        oids = [p["id"] for p in s["pages"] if mapping.get("%s/%s" % (s["id"], p["id"]), "").split("/")[0] == s["id"]]
        nids = [i for i in nids if i in oids]
        check([i for i in oids if i not in SHARED] + [i for i in oids if i in SHARED] == nids, "section %s keeps its pages in the same order (the shared ones last, #1852)" % s["id"])

# ── the new island: the five asked for, every old tab still one tap away ─────────────────────────────────────
check(new["bottom_nav"] == ["inboxes", "backlogs", "home", "tasks", "clock"], "#1964 island = Inbox | Backlog | Home | Tasks | Clock (Library is Home's third page)")
labels = [new_sections[i]["label"] for i in new["bottom_nav"]]
check(labels == ["Inbox", "Backlog", "Home", "Tasks", "Clock"], "island labels %s" % labels)
reach = {tuple(v.split("/")) for v in mapping.values()}
check(all(s in new["bottom_nav"] or s == "settings" for s, _ in reach), "every previous page lives in a section on the island (or Settings, off the island as before)")
check(set(new_sections) - set(new["bottom_nav"]) == {"settings"}, "Settings is the only section off the island, as before")
check(old["default_section"] == "tasks" and new["default_section"] == "home", "#1964 the app opens on Home (it opened on Tasks in #1757)")

# ── calendar views and the new styles ───────────────────────────────────────────────────────────────────────
check(all(v in new["calendar_views"] for v in old["calendar_views"]), "the Month and Year views of the Calendar are kept")
check([c["id"] for c in new["calendar_styles"]][:1] == ["circles"], "Circles Day (the previous Month circles view) is the first and default style")

# ── the fixture: every row, every field ─────────────────────────────────────────────────────────────────────
new_rows = {i["id"]: i for i in new_mock["items"]}
check(set(old_mock["kinds"]) <= set(new_mock["kinds"]) and old_mock["statuses"] == new_mock["statuses"], "the fixture keeps its kinds and statuses")
for row in old_mock["items"]:
    n = new_rows.get(row["id"])
    check(n is not None and all(n.get(k) == v for k, v in row.items()), "fixture row %s keeps all %d fields" % (row["id"], len(row)))
check(len(new_rows) >= len(old_mock["items"]), "no fixture row was removed (%d now, %d before)" % (len(new_rows), len(old_mock["items"])))
check(all(i.get("project") for i in new_mock["items"] if i["kind"] != "tasks"), "every non-task row names its project, so the Home selector can scope it")

print("all passed" if fails == 0 else "%d failed" % fails)
sys.exit(1 if fails else 0)
PY
