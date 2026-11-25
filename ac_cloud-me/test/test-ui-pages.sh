#!/usr/bin/env bash
# Cloud Me navigation + data check. build.json::ui.sections holds the shape,
# one file per page under data/ui/ holds the content, data/files/ holds the
# wallet records, and nothing at build time notices a `page:` tile pointing at
# a tab nobody declares. This does — it is the check that fails when the pieces
# drift apart.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import json, os, re, sys

ui   = json.load(open("build.json"))["ui"]
secs = ui["sections"]
apps = {a["id"] for a in ui.get("external_apps", [])}
bad  = []

# section id → every navigable page id (tabs and sub-tabs, at any depth)
def declared(pages, folder):
    """(page id, content file or None, label) for every page below `pages`.

    A container tab holds no content of its own and its children live one
    folder deeper, so the folder path IS the tab path — and it recurses,
    because Projects > Health > Workout > Gym is three deep. It is still a
    valid target id, which is why the container yields None rather than
    nothing at all."""
    for p in pages:
        subs = p.get("pages", [])
        if subs:
            yield p["id"], None, p.get("label", "")
            yield from declared(subs, f"{folder}/{p['id']}")
        else:
            yield p["id"], f"{folder}/{p['id']}.json", p.get("label", "")

pages, files, summaries = {}, {}, {}
for s in secs:
    ids = []
    for pid, f, label in declared(s.get("pages", []), f"data/ui/{s['id']}"):
        ids.append(pid)
        if f is None:
            continue
        if not os.path.exists(f):
            bad.append(f"missing page file {f}")
        else:
            files[f] = json.load(open(f))
            if label == "Summary":
                summaries[f] = files[f]
    # Sections.kt::Section.page() resolves an id against the tab strip AND
    # every sub-strip in ONE flat list, so two pages in a section sharing an
    # id are not two destinations — the second is unreachable and the first
    # answers for both, which silently breaks the shadowed tab rather than
    # failing anywhere. Projects came within one id of this: the new Summary
    # tab would have shadowed Health's Summary sub-page had it been called
    # `summary` instead of `pm`.
    for dupe in sorted({i for i in ids if ids.count(i) > 1}):
        bad.append(f"section {s['id']} declares page id '{dupe}' twice — the second is unreachable")
    pages[s["id"]] = ids
    if not ids and not s.get("target"):
        bad.append(f"section {s['id']} has neither pages nor a target")
    if any(k.startswith("stack_") for k in s):
        bad.append(f"section {s['id']} still inlines a stack in build.json")

for root, _, names in os.walk("data/ui"):
    for n in names:
        if n.endswith(".json") and os.path.join(root, n) not in files:
            bad.append(f"orphan page file {os.path.join(root, n)} — no page declares it")

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

blocks = [o for st in files.values() for o in walk(st)]

# A Summary page is the one place in the app that speaks ABOUT other pages
# instead of showing their data, which is exactly where a page with nothing in
# it gets misreported. Two ways that happens, both silent:
#
#   • a card quotes `0` — but a summary card is JSON baked at build time, so it
#     cannot have measured anything. A zero there is a claim that a count was
#     taken and came back empty, when in fact no source was ever wired up.
#   • a card says nothing about where its subject comes from, so a reader
#     cannot tell a real feed from a hand-written list from a placeholder.
#
# Requiring a `Source` meta row on every summary card fixes both: naming the
# source is what forces "none yet" to be written down rather than shown as a
# zero. Buro > Summary and Projects > Health > Summary > Overview both live by
# this rule; the check is what keeps the next one honest too.
ZERO = re.compile(r"(?<![\w.,])0(?![\w.,])")
for path, stack in sorted(summaries.items()):
    for card in (o for b in walk(stack) if b.get("kind") == "cards"
                 for o in b.get("items", [])):
        who = f"{path}: card '{card.get('title', '?')}'"
        sources = [m.get("value", "") for m in card.get("meta", [])
                   if str(m.get("label", "")).lower() == "source"]
        if not sources:
            bad.append(f"{who} declares no Source meta row — say where it comes from, "
                       f"'none yet' included")
        elif not str(sources[0]).strip():
            bad.append(f"{who} has an empty Source value")
        spoken = [str(card.get(k, "")) for k in ("title", "subtitle", "body")]
        spoken += [f"{m.get('label','')} {m.get('value','')}" for m in card.get("meta", [])]
        if any(ZERO.search(t) for t in spoken):
            bad.append(f"{who} shows a bare 0 — a summary card measures nothing, "
                       f"so say it has no data instead")

for t in {o["target"] for o in list(walk(secs)) + blocks
          if isinstance(o.get("target"), str) and o["target"]}:
    if t.startswith("extapp:"):
        if t.removeprefix("extapp:").split("/")[0] not in apps:
            bad.append(f"target {t} — no such ui.external_apps id")
    elif t.startswith(("page:", "section:")):
        sec, _, pg = t.split(":", 1)[1].partition("/")
        if sec not in pages:              bad.append(f"target {t} — no such section")
        elif pg and pg not in pages[sec]: bad.append(f"target {t} — no such page in {sec}")
    elif not t.startswith("http"):
        bad.append(f"target {t} — unknown grammar")

# Icons are looked up by NAME at runtime (getIdentifier), so a misspelt or
# deleted drawable is not a build error and not a crash — iconRes() answers null
# and the tile, tab or card simply renders without its picture. Projects >
# Health > Gym makes that unacceptable: there, the drawing of the movement IS
# the content, and a silently missing one is an exercise shipped blank.
drawables = {n[:-4] for n in os.listdir("app/src/main/res/drawable") if n.endswith(".xml")}
# An icon is an app drawable OR a name of the shared catalog (libs:ui-icons); Health's page icons are catalog names.
_cat = "../ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"
if os.path.isfile(_cat):
    drawables |= set(re.findall(r'"([a-z0-9_]+)"\s+to\s', open(_cat, encoding="utf-8").read()))
for icon in sorted({o["icon"] for o in list(walk(secs)) + blocks
                    if isinstance(o.get("icon"), str) and o["icon"]}):
    if icon not in drawables:
        bad.append(f"icon '{icon}' — no app/src/main/res/drawable/{icon}.xml")

# A `metric` page is ONE entry of the health taxonomy drawn on a page of its
# own, optionally narrowed to some of that metric's record types. Both names
# are matched at RUNTIME against a list baked from build.json — a typo in
# either resolves to nothing and the page draws its "no such metric" state,
# which is honest but is not what anyone meant to ship.
health_metrics = {m["id"]: m for s in secs if s["id"] == "health" for m in s.get("metrics", [])}
for o in blocks:
    if o.get("kind") != "fragment" or o.get("id") != "health" or o.get("page") != "metric":
        continue
    m = health_metrics.get(o.get("metric"))
    if m is None:
        bad.append(f"metric page names '{o.get('metric')}' — no such ui.sections[health].metrics id")
        continue
    for r in o.get("records", []):
        if r not in m["records"]:
            bad.append(f"metric page '{o['metric']}' narrows to '{r}', which that metric does not declare")

# A `files` block browses a real asset tree; an empty root is a blank screen.
roots = [o["root"] for o in blocks if o.get("kind") == "fragment" and o.get("id") == "files"]
for r in roots:
    if not os.path.isdir(f"data/files/{r}"):
        bad.append(f"files block root '{r}' — no data/files/{r}/")
    elif not any(n.endswith(".json") for _, _, ns in os.walk(f"data/files/{r}") for n in ns):
        bad.append(f"files block root '{r}' — data/files/{r}/ holds no records")

# data/ui and data/files merge into one asset root, so a section folder must
# not collide with a top-level folder in the wallet tree.
if os.path.isdir("data/files"):
    for name in os.listdir("data/files"):
        if name in pages and pages[name]:
            bad.append(f"section '{name}' collides with data/files/{name}/ in the asset root")

# #868 the bar is ui.bottom_nav: at most eight ids (the island fits them all, nav-shape N1), each a section, and the
# landing section (ui.default_section) is one of them. No section carries the
# old bottom_nav/order/default flags any more.
bar_ids = ui.get("bottom_nav", [])
by_id = {s["id"]: s for s in secs}
for i in bar_ids:
    if i not in by_id:
        bad.append(f"ui.bottom_nav names '{i}' — no such section")
if len(bar_ids) > 8:
    bad.append(f"{len(bar_ids)} ui.bottom_nav ids — the island holds eight")
if ui.get("default_section") not in bar_ids:
    bad.append(f"ui.default_section '{ui.get('default_section')}' is not in ui.bottom_nav")
for s in secs:
    for k in ("bottom_nav", "order", "default"):
        if k in s:
            bad.append(f"section '{s['id']}' still carries '{k}' — the bar is ui.bottom_nav")
bar = [by_id[i] for i in bar_ids if i in by_id]

# Mirror NavDecl.bottomSections() — the order is the declared order. Diego's
# Owner order (#1367): Health, Experiences, Socials, Lnktree, Inventory, Buro, LLC, left to right.
bar_order = [s.get("label") for s in bar]
EXPECTED_BAR_ORDER = ["Pers", "Prof", "Proj", "Lnktree", "Inventory", "Buro", "LLC"]  # #1901
if bar_order != EXPECTED_BAR_ORDER:
    bad.append(f"bottom bar order is {bar_order}, expected {EXPECTED_BAR_ORDER}")

for b in sorted(bad): print("FAIL:", b)
print(f"checked {len(files)} pages, {len(bar)} bar sections, {len(roots)} file trees")
sys.exit(1 if bad else 0)
PY

# Every `web` block must point at a page that is actually bundled.
python3 - <<'PYWEB'
import json, os, sys
bad = []
for root, _, names in os.walk("data/ui"):
    for n in names:
        if not n.endswith(".json"):
            continue
        for b in json.load(open(os.path.join(root, n))):
            if isinstance(b, dict) and b.get("id") == "web":
                if not b.get("url", "").startswith("https://") and not os.path.exists(os.path.join("data/web", b.get("url", ""))):
                    bad.append(f"{root}/{n}: web block has no data/web/{b.get('url')}")
for b in bad:
    print("FAIL:", b)
sys.exit(1 if bad else 0)
PYWEB

# Every `extapp:cloud-me#<target>` in the SuperApp must land on a page that
# exists HERE. Those strings are the only way into a specific Cloud Me page
# from outside the app, they live in another app's build.json, and nothing on
# either side notices when a page they name is renamed or removed — the
# SuperApp cannot see this file and the tile stays silent, opening Cloud Me at
# its front door as if the deep link had never been written.
#
# The resolution below mirrors the runtime exactly, so this passes only for the
# reasons the phone would: ShellActivity.launchExternalApp splits the payload at
# '#' (StackAnchors.FRAGMENT) and forwards the half after it as the
# `shortcut_action` extra; MainActivity.handleShortcutIntent reads that extra
# and hands it to onTarget, which splits `page:<section>/<page>` at the first
# '/'; Sections.kt::Section.page() then looks the page id up across the tab
# strip and every sub-strip, and leaf() walks a container tab down to its first
# child.
python3 - <<'PYLINK'
import json, os, sys

SUPERAPP = "../aa_cloud-superapp/build.json"
if not os.path.exists(SUPERAPP):
    print("aa_cloud-superapp not checked out — deep-link routes skipped")
    sys.exit(0)

_ui = json.load(open("build.json"))["ui"]
secs = {s["id"]: s for s in _ui["sections"]}
_ren = _ui.get("renamed", {})

def renamed(section_id, page_id):
    """LegacyRoutes.renamed: ui.renamed.pages["old section/old page"], else ui.renamed.sections[old section] with the page id kept (#1901)."""
    to = _ren.get("pages", {}).get(f"{section_id}/{page_id}" if page_id else section_id)
    if to:
        a, _, b = to.partition("/")
        return a, b
    return _ren.get("sections", {}).get(section_id, section_id), page_id

def resolve(target):
    """The page id Cloud Me would actually show, or a reason it would not."""
    if not target.startswith("page:"):
        return None, f"target {target} — not a page: route"
    section_id, _, page_id = target[len("page:"):].partition("/")
    section_id, page_id = renamed(section_id, page_id)   # old ids (the SuperApp's Me row) go through ui.renamed, as MainActivity.open does
    section = secs.get(section_id)
    if section is None:
        return None, f"target {target} — no section '{section_id}'"
    def flatten(pages):
        for p in pages:
            yield p
            yield from flatten(p.get("pages", []))
    hit = next((p for p in flatten(section.get("pages", [])) if p["id"] == page_id), None)
    if hit is None:
        return None, f"target {target} — section '{section_id}' declares no page '{page_id}'"
    # leaf(): a container tab shows its first child, never itself.
    while hit.get("pages"):
        hit = hit["pages"][0]
    return hit["id"], None

def strings(o):
    if isinstance(o, str):   yield o
    elif isinstance(o, dict):
        for v in o.values(): yield from strings(v)
    elif isinstance(o, list):
        for v in o:          yield from strings(v)

# Every route the SuperApp actually ships, plus the Tasks subpages and the Inventory rows. The literal is here on purpose: it is the contract this app
# publishes, and it has to hold on the day the SuperApp side is written as much
# as on the day after.
routes = {s.split("#", 1)[1] for s in strings(json.load(open(SUPERAPP)))
          if s.startswith("extapp:cloud-me#") and "#" in s}
routes.add("page:tasks/backlogs")
routes.add("page:experiences/geo")     # #1901 the pre-reorganisation ids stay routes: ui.renamed resolves them
routes.add("page:experiences/work")
routes.add("page:health/tracked")
routes.add("page:lnktree/leafy")
routes.add("page:pers/geo")
routes.add("page:pers/tracked")
routes.add("page:prof/work")
routes.add("page:proj/lnktree-site")
routes.add("page:tasks/roadmaps")
routes.add("page:socials/socials-professional")  # #1771 the Cloud SA Socials tile
routes.add("page:socials/socials-personal")
routes.add("page:inventory/housing")
routes.add("page:inventory/utilities")
routes.add("page:inventory/leisure")

bad = [why for _, why in map(resolve, sorted(routes)) if why]
for b in bad:
    print("FAIL:", b)
print(f"checked {len(routes)} cloud-me deep-link routes")
sys.exit(1 if bad else 0)
PYLINK

# A literal-dollar escape in Kotlin is almost always a generated-code accident:
# "${'$'}x" is the string $x, not the value of x. One of those turned every
# page asset path into a filename that could not exist, and the fail-soft
# loader rendered empty pages instead of saying so.
if grep -rn "\${'\$'}" app/src --include='*.kt' 2>/dev/null; then
    echo "FAIL: literal-dollar escape in Kotlin (see above) — did a generator write that?"
    exit 1
fi

# Cloud Wallet's bundled wallet.json is this tree flattened, not a second copy.
./data/regen-wallet-json.py --check

# Study and LLC history are generated from the bundled LinkedIn profile data.
./data/regen-study.py --check

# Profile ships the real mySocials pages. Skipped when the sibling front repo
# is not checked out — CI has the committed bundle and does not need the source.
./data/regen-web.py --check 2>/dev/null || echo "mySocials source not present — skipped"
