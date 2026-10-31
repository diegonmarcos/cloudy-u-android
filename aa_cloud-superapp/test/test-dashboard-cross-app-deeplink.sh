#!/usr/bin/env bash
# Tester: Cloud ▸ Me's MyHealth icon is a cross-app deep link into
# Cloud-Me, and nothing of the two deleted SuperApp pages is left behind.
#
# MyFin and MyHealth were SuperApp pages (sections `myfin` and `health`). Both
# surfaces now live in Cloud-Me — Buro ▸ Fin and Projects ▸ Health. MyHealth's
# icon stayed put, retargeted to `extapp:cloud-me#<target>`; MyFin's icon was
# dropped from the row by the owner, so Buro ▸ Fin is reached from inside
# Cloud-Me and nothing here links to it. launchExternalApp
# launches Cloud-Me and hands everything after the `#` to it as the
# `shortcut_action` extra, which Cloud-Me's MainActivity.onTarget resolves with
# the same `page:<section>/<page>` grammar.
#
# Three ways that can rot, all of them silent on a phone:
#   • the fragment names a page Cloud-Me does not have → the icon opens Cloud-Me
#     at its front door and looks like it half-worked,
#   • cloud-me loses its external_apps install_apk_url → the icon does nothing
#     at all when Cloud-Me is not installed,
#   • sections[health].metrics is "cleaned up" along with the pages → Configs ▸
#     Permissions silently reports 0 / 0 Health Connect grants.
#
# Static and data-driven: build.json on both sides is the only source of truth.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
ME_BJ="$APP/../ac_cloud-me/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== T1: the two SuperApp pages are gone =="
python3 - "$BJ" <<'PY' && ok "no myfin section, no health pages, no page:myfin/ or page:health/ target" || bad "a deleted page still exists or is still targeted"
import json, sys
d = json.load(open(sys.argv[1]))
secs = d["ui"]["sections"]
by_id = {s["id"]: s for s in secs}
assert "myfin" not in by_id, "sections[myfin] still declared"
assert not by_id["health"].get("pages"), "sections[health] still declares pages"

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

dead = [o["target"] for o in walk(d["ui"])
        if isinstance(o.get("target"), str)
        and o["target"].startswith(("page:myfin/", "page:health/"))]
assert not dead, f"targets still point at the deleted pages: {dead}"
PY

echo "== T2: the surviving migrated icon is an extapp deep link with an install path =="
python3 - "$BJ" <<'PY' && ok "MyHealth tile → extapp:cloud-me#page:..., MyFin gone, cloud-me installable" || bad "a project-row icon is missing, mistargeted, or has no install path"
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
group = next(g for g in cloud["tile_groups"] if g["title"] == "Me")
tiles = {t.get("id"): t for t in group["tiles"]}
want = {"myhealth": "extapp:cloud-me#page:health/home"}
for tid, target in want.items():
    assert tid in tiles, f"Me tile {tid} is gone — the icon must remain"
    assert tiles[tid]["target"] == target, (tid, tiles[tid]["target"], "!=", target)
    assert tiles[tid].get("icon"), f"{tid} tile has no icon"
# MyFin was DROPPED by the owner. Asserting its absence here, not just leaving it
# out of the table above, is what stops a well-meant restore from putting it back
# beside MyBuro: Buro ▸ Fin is still reachable from inside Cloud-Me.
assert "myfin" not in tiles, "the MyFin tile is back — the owner dropped it"
# A missing target app must not be a dead icon: launchExternalApp falls back to
# downloading install_apk_url for install_package.
me = next(e for e in d["ui"]["external_apps"] if e.get("id") == "cloud-me")
assert me["hub_package"] == "com.diegonmarcos.cloudme", me
assert me["install_package"] and me["install_apk_url"].endswith(".apk"), me
PY

echo "== T3: every extapp deep-link fragment names a page Cloud-Me really has =="
if [ ! -f "$ME_BJ" ]; then
  echo "  SKIP: ac_cloud-me not checked out beside this repo"
else
python3 - "$BJ" "$ME_BJ" <<'PY' && ok "each #page:<section>/<page> resolves in ac_cloud-me/build.json" || bad "a deep link points at a Cloud-Me page that does not exist"
import json, sys
sa = json.load(open(sys.argv[1]))
me = json.load(open(sys.argv[2]))

# Every navigable id Cloud-Me's MainActivity.open can resolve: a section, a tab,
# a sub-tab, and a container tab (which opens its first child).
#
# RECURSIVE, because Cloud-Me's strips are no longer two deep — Projects >
# Health > Workout > Gym is three, and Section.page() still resolves the whole
# chain into one flat list per section. A fixed two-level walk would report a
# page that exists as missing, which fails this test for a tile that works.
def ids_of(pages_json):
    for p in pages_json:
        yield p["id"]
        yield from ids_of(p.get("pages", []))

pages = {s["id"]: list(ids_of(s.get("pages", []))) for s in me["ui"]["sections"]}

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

links = [o["target"] for o in walk(sa["ui"])
         if isinstance(o.get("target"), str)
         and o["target"].startswith("extapp:cloud-me#")]
assert links, "no extapp:cloud-me deep link left to check"
for t in links:
    frag = t.split("#", 1)[1]
    assert frag.startswith("page:"), f"{t}: only page: targets are deep-linkable today"
    sec, _, pg = frag.removeprefix("page:").partition("/")
    assert sec in pages, f"{t}: Cloud-Me has no section '{sec}'"
    assert pg in pages[sec], f"{t}: Cloud-Me section '{sec}' has no page '{pg}'"
PY
fi

echo "== T4: what the SuperApp still needs from Health stayed =="
python3 - "$BJ" <<'PY' && ok "sections[health].metrics intact (34 perms) and libs:health still a module; libs:fin gone" || bad "Configs ▸ Permissions lost its Health Connect taxonomy, or the module graph is inconsistent"
import json, sys
d = json.load(open(sys.argv[1]))
health = next(s for s in d["ui"]["sections"] if s["id"] == "health")
metrics = health.get("metrics") or []
assert metrics, "sections[health].metrics is empty — HealthMetrics.allPermissions would be 0"
perms = {k for m in metrics for k in m["perm_keys"]}
assert len(perms) == 34, f"expected 34 Health Connect perms, found {len(perms)}"
mods = d["modules"]
assert "libs:health" in mods, "libs:health dropped but Configs ▸ Permissions still uses it"
assert "libs:fin" not in mods, "libs:fin still in the module graph after MyFin left"
assert "libs:fin" not in mods["app"]["depends_on"], "app still depends on libs:fin"
assert "myfin_mock" not in d["ui"], "ui.myfin_mock outlived the MyFin page"
PY

echo "== T5: the project rows read left-to-right as declared, and every tile in them goes somewhere =="
python3 - "$BJ" <<'PY' && ok "every project row in declared order, every run one kind, separators inert" || bad "a project row is out of order, mixes kinds inside one run, or carries a tile that leads nowhere"
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
groups = {g["title"]: g for g in cloud["tile_groups"]}

# The product decision each row exists to express, pinned. Nothing about a JSON
# array's order fails on its own when someone appends to it, which is exactly
# why it is written out here. Me is the personal row, Lnktree the
# work half; the split is what tells the next editor which row a new tile joins.
ROWS = {
    # #1092: the row is "Me" (was "Projects Me"), placed right after Data Apps. The Me app leads, one rule,
    # then MySocials, MyBuro, MyHealth, MyStudy, MyTrips as the owner ordered. Agenda left the row (it is
    # reachable inside the Me app); the old rules projects-me-sep-0/1/2 went with the old layout.
    # Owner order (Cloud Me restructure): Me | Me-Health Experiences RoadMap | Me-Duties Me-Buro Me-LLC | Account.
    "Me": ["me", "me-sep-1", "myhealth", "myexperiences", "myroadmap", "me-sep-2", "myduties", "myburo", "myllc", "me-sep-3", "tools-account"],
    # (#1331: the row is now Lnktree; before it held seven named boards (#1212), and task 311 had removed PM Boards, PM C3 and the
    # "|" between them. The rule had marked the board LIST off from the named
    # boards; with the list gone there are no longer two kinds of destination in
    # the row for it to divide, so it went with them rather than floating at the
    # head of a row whose every tile is now the same kind of tile.
    "Lnktree":  ["cloud-superapp", "lnktree-sep-0", "omniverse", "lnktree-sep-1", "finterm", "lnktree-sep-2", "cad", "sim2real"],  # owner 2026-10-30: SuperApp leads (moved from Configs); #1331 owner order: Omniverse | FinTerm | CAD Sim2Real (was Projects W, #1212)
}

# Every target, spelled the way the ROUTER resolves it and not the way the tile
# reads on screen. MyStudy and MyTrips are the reason this table exists: their
# pages are `studying` and `trips` in ac_cloud-me, and T3 above is what proves
# those ids resolve against Cloud-Me's flattened page tree rather than against
# a filename that happens to look similar.
TARGETS = {
    # The Me app itself: launches Cloud-Me at its front door (no #page fragment). #1092 dropped the Agenda
    # tile from this row, so its deep link is no longer pinned here.
    "tools-account": "page:config/profile",
    "me":        "extapp:cloud-me",
    # #1335: the Lnktree row's tiles LAUNCH the Lnktree family apps (test-lnktree-row.sh pins the row, ad_*/test/test-lnktree-app.sh the pages).
    # owner 2026-10-30: SuperApp leads Lnktree (moved from Configs); #1153 app:// own-task launch, T7 pins the mechanics.
    "cloud-superapp": "app://com.diegonmarcos.superapp",
    "omniverse": "extapp:cg-omniverse",
    "finterm": "extapp:capital-stack-finterminal",
    "cad": "extapp:hardtech-cad",
    "sim2real": "extapp:hardtech-sim2real",
    "myhealth":    "extapp:cloud-me#page:health/home",
    "myexperiences": "extapp:cloud-me#page:experiences/work",
    "myroadmap":   "extapp:cloud-me#page:tasks/roadmaps",
    "myduties":    "extapp:cloud-me#page:tasks/backlogs",
    "myburo":      "extapp:cloud-me#page:buro/ids",
    "myllc":       "extapp:cloud-me#page:llc/employed",
}

def kind(tile):
    """What TAPPING this tile does — the only distinction the rules mark.
    An `extapp:` tile launches another app on this phone, or offers to install
    it; an http tile opens a web page. Mixing the two inside one run is what
    makes a row unreadable, because two neighbouring icons then behave nothing
    alike."""
    target = tile["target"]
    if target.startswith("extapp:"):
        return "another app on this phone"
    if target.startswith(("http://", "https://")):
        return "a web page"
    return "a screen in this app"

apps = {a["id"] for a in d["ui"].get("external_apps", [])}

for title, want_order in ROWS.items():
    assert title in groups, f"tile_groups has no row titled '{title}'"
    tiles = groups[title]["tiles"]
    assert [t.get("id") for t in tiles] == want_order, (title, [t.get("id") for t in tiles])

    # A rule is a glyph, not a control. A cell with no target that is still
    # clickable is the dead-tap defect; declaring it keeps GroupedTilesFragment
    # and Sections.TileGroup.destinations able to tell decoration from a
    # destination.
    for sep in (t for t in tiles if t.get("separator")):
        assert sep.get("separator") is True, sep
        assert not sep.get("target"), f"{title}: a separator must not carry a target: {sep}"
        assert sep.get("label"), "parseTilesInline reads label with getString — it must exist"

    # Split the row on its rules. A rule at either edge, or two in a row,
    # divides nothing and is pure decoration the reader has to explain away.
    runs, current = [], []
    for t in tiles:
        if t.get("separator"):
            assert current, f"{title}: a separator with nothing before it divides nothing"
            runs.append(current); current = []
        else:
            current.append(t)
    assert current, f"{title}: the row ends on a separator, which divides nothing"
    runs.append(current)

    # THE rule the separators actually keep: each run is of ONE kind. Note this
    # is deliberately weaker than "consecutive runs differ" — the old Projects Me row's
    # second rule divides Buro pages from Projects pages and BOTH are deep
    # links, so the stronger assertion would be false, and a tester that
    # asserts something false is deleted the first time it is inconvenient.
    # The Me row once exempted its `Account Me` run; since #1362 Account sits
    # alone behind its own rule, so every row keeps the rule.
    for run in runs:
        kinds = {kind(t) for t in run}
        assert len(kinds) == 1, (
            f"{title}: one run mixes {sorted(kinds)} — a browser link and a "
            f"cross-app deep link on the same side of a rule: "
            f"{[t.get('id') for t in run]}")

    # A tile that is NOT a separator must go somewhere, in a grammar the
    # launcher actually dispatches. This is the check that would have caught a
    # tile pointing at a page nobody declares — T3 above then proves the
    # cloud-me ones resolve.
    for t in tiles:
        if t.get("separator"):
            continue
        tid, target = t.get("id"), t.get("target", "")
        assert target, f"{title}: tile {tid} has no target — it would be a dead tap"
        assert t.get("icon"), f"{title}: tile {tid} has no icon"
        assert tid in TARGETS, f"{title}: tile {tid} is not in the pinned target table"
        assert target.rstrip("/") != "https://paca.diegonmarcos.com/projects", f"{title}: tile {tid} still targets the paca project index placeholder"
        assert target == TARGETS[tid], (tid, target, "!=", TARGETS[tid])
        if target.startswith("extapp:"):
            app = target.removeprefix("extapp:").split("#", 1)[0]
            assert app in apps, f"{title}: tile {tid} → no such ui.external_apps id '{app}'"
        else:
            assert target.startswith(("page:", "section:", "action:", "http", "app://")), \
                f"{title}: tile {tid} target '{target}' — unknown grammar"

# MyProjects was REMOVED from the row by the owner. Asserting its absence keeps
# a well-meant restore from quietly reappearing beside MyBuro, where it used to
# sit — the Projects Summary page it opened is still reachable from Cloud-Me.
placed = {t.get("id") for g in ROWS for t in groups[g]["tiles"]}
for gone in ("myprojects", "myfin"):
    assert gone not in placed, f"{gone} is back in a project row — the owner removed it"
PY

echo "== T6 (#1092): Cloud ▸ Apps row order, Vault and Account in Tools Primary =="
python3 - "$BJ" "$APP/data/constellation-fleet.json" <<'PY' && ok "rows follow the derived registry (constellation-fleet.json app_categories); Vault in Tools Primary; Account closes Me, after a '|' (#1362); Account kept in Configs" || bad "Cloud ▸ Apps row order or the Vault/Account placement drifted"
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
titles = [g["title"] for g in cloud["tile_groups"]]
# The expected order is the derived registry (data/app-categories.py via regen.sh writes one
# app_categories entry per tile group, same title, same order, then "Other"), not a literal list here.
want = [c["label"] for c in json.load(open(sys.argv[2]))["app_categories"] if c["id"] != "other"]
assert titles == want, (titles, "!=", want, "regen: data/regen.sh --constellation-only")
g = {x["title"]: [t.get("label") for t in x["tiles"]] for x in cloud["tile_groups"]}
assert g["Me"] == ["Me", "|", "Me-Health", "Experiences", "RoadMap", "|", "Me-Duties", "Me-Buro", "Me-LLC", "|", "Account"], g["Me"]
assert g["Tools Primary"][-2:] == ["Wallet", "Vault"] and "Account" not in g["Tools Primary"], g["Tools Primary"]  # #1155 order (Light left for Cloud Calc > Toolkit); Account moved to Me (last since #1362)
assert "Vault" not in g["Data Apps"] and "Me" not in g["Tools Primary"], g
assert "Account" in g["Configs"], "Account must stay in Configs (intentional duplicate)"
ids = [t["id"] for x in cloud["tile_groups"] for t in x["tiles"] if t.get("id")]
assert len(ids) == len(set(ids)), "tile ids must stay unique"
assert "Me" in [t["label"] for t in cloud["tiles_shared"]], "the Home index label follows the row name"
PY

echo "== T7 (#1153, supersedes #1095): the Lnktree-row SuperApp tile launches the app as its own task (moved from Configs 2026-10-30) =="
python3 - "$BJ" "$APP" <<'PY' && ok "Cloud ▸ Apps ▸ Lnktree ▸ SuperApp → app://<package>, launched NEW_TASK | LAUNCH_ADJACENT onto a resizeable MainActivity" || bad "the Lnktree-row SuperApp tile does not launch the app as its own task"
import json, os, re, sys
d = json.load(open(sys.argv[1]))
app = sys.argv[2]
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
row = next(g for g in cloud["tile_groups"] if g["title"] == "Lnktree")
assert row["tiles"][0].get("id") == "cloud-superapp", "SuperApp must LEAD the Lnktree row"
tile = next(t for t in row["tiles"] if t.get("id") == "cloud-superapp")
assert tile["label"] == "SuperApp", tile
own = "app://" + d["android"]["application_id"]
assert tile["target"] == own, f"SuperApp tile target is {tile['target']!r}; it must launch the app as its own task ({own})"
shell = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"), encoding="utf-8").read()
assert "FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT" in shell, "ShellActivity no longer launches app: targets NEW_TASK | LAUNCH_ADJACENT"
man = open(os.path.join(app, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
main = man.split('android:name=".MainActivity"', 1)[1].split(">", 1)[0]
assert 'android:resizeableActivity="true"' in main, "MainActivity is not resizeable"
PY

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
