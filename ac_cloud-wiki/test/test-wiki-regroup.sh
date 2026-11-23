#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-wiki-regroup — the Projects regroup removes nothing (#1857)  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Rule 16 (a reorg only adds structure): the side menu went from Sciences | Humanities | Arts | Explore to Sciences | Humanities | Arts | Projects |
# Explore, Chronos moved out of Humanities into Projects and Sacred Geometry was added. test/wiki-nav-before.json is the frozen previous
# declaration; this proves against build.json::ui wiki and the dataset:
#   R1  every page of the previous menu is still declared, with the same label and icon, once
#   R2  every one of them is still REACHABLE: in exactly one group, the group it sat in before, except Chronos, which sits in Projects
#   R3  every previous group is still there with its label and icon; the groups are now Sciences, Humanities, Arts, Projects, Explore
#   R4  Chronos is unchanged: the same nested pages (ids, labels, icons, assets, hierarchy) as before
#   R5  Sacred Geometry is new and is in Projects, after Chronos; the page list follows the group order
#   R6  every dataset topic (wiki.json) is a declared page in a group: nothing the dataset serves is orphaned
# #1867 the menu is nested one level more (parents Modern Science | Arcanum Science | Projects as the groups, the former groups Sciences,
# Humanities, Arts, Explore as HUB pages of Modern Science holding their topics): a page's "group" below is the hub or group that holds
# it, the previous groups must still exist with their label and icon as a hub or a group, and Chronos' assets moved with its folder
# (Chronos/ -> 3.Projects/3.0.Chronos/) while its ids, labels, icons and hierarchy are unchanged.
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <build.json> <before.json> <wiki.json> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$2" "$3" <<'PY'
import json, sys
bj, before, wj = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:4])
bad = []
w = next(s for s in bj["ui"]["sections"] if s["id"] == "wiki")
OLD_ASSET, NEW_ASSET = "Chronos/", "3.Projects/3.0.Chronos/"
pages, where, holders = {}, {}, {}
for g in w["groups"]:
    holders[g["id"]] = g
    for pid in g["pages"]:
        where.setdefault(pid, []).append(g["id"])
for p in w["pages"]:
    pages.setdefault(p["id"], []).append(p)
    if p["id"] != "chronos" and p.get("pages"):
        holders[p["id"]] = p
        for c in p["pages"]:
            pages.setdefault(c["id"], []).append(c)
            where.setdefault(c["id"], []).append(p["id"])
old_group = {pid: g["id"] for g in before["groups"] for pid in g["pages"]}
def slim(p, old=False):
    o = {k: p[k] for k in ("id", "label", "icon", "asset") if k in p}
    if old and "asset" in o and o["asset"].startswith(OLD_ASSET): o["asset"] = NEW_ASSET + o["asset"][len(OLD_ASSET):]
    if p.get("pages"): o["pages"] = [slim(x, old) for x in p["pages"]]
    return o
for bp in before["pages"]:
    pid = bp["id"]
    now = pages.get(pid, [])
    if len(now) != 1: bad.append("R1 page %s is declared %d times (was once)" % (pid, len(now))); continue
    if (now[0].get("label"), now[0].get("icon")) != (bp["label"], bp["icon"]):
        bad.append("R1 page %s changed its label or icon: %r/%r was %r/%r" % (pid, now[0].get("label"), now[0].get("icon"), bp["label"], bp["icon"]))
    if where.get(pid) != ["projects" if pid == "chronos" else old_group[pid]]:
        bad.append("R2 page %s is reachable from %s, expected %s" % (pid, where.get(pid), "projects" if pid == "chronos" else old_group[pid]))
    if pid == "chronos" and [slim(x) for x in now[0].get("pages", [])] != [slim(x, True) for x in bp.get("pages", [])]:
        bad.append("R4 Chronos' nested pages differ from before the regroup")
for bg in before["groups"]:
    h = holders.get(bg["id"])
    if h is None: bad.append("R3 group %s was removed" % bg["id"])
    elif (h["label"], h["icon"]) != (bg["label"], bg["icon"]): bad.append("R3 group %s changed its label or icon" % bg["id"])
    else:
        held = h["pages"] if isinstance(h["pages"][0], str) else [c["id"] for c in h["pages"]]
        for pid in bg["pages"]:
            if pid != "chronos" and pid not in held: bad.append("R2 group %s lost page %s" % (bg["id"], pid))
if [g["id"] for g in w["groups"]] != ["modern", "arcanum", "projects"]:
    bad.append("R3 the groups are %s, not Modern Science, Arcanum Science, Projects" % [g["id"] for g in w["groups"]])
if holders.get("modern", {}).get("pages") != ["sciences", "humanities", "arts", "explore"]:
    bad.append("R3 Modern Science does not hold Sciences, Humanities, Arts, Explore in that order")
if holders.get("projects", {}).get("pages") != ["chronos", "sacred"]: bad.append("R5 Projects does not hold Chronos then Sacred Geometry")
sg = pages.get("sacred", [{}])[0]
if sg.get("label") != "Sacred Geometry" or sg.get("project") != "3.Projects/3.1.Sacred-geo": bad.append("R5 Sacred Geometry is not declared as the Sacred-geo project")
if [p["id"] for p in w["pages"]] != [pid for g in w["groups"] for pid in g["pages"]]: bad.append("R5 the page list does not follow the group order")
for t in wj.get("topics", []):
    if len(where.get(t["id"], [])) != 1: bad.append("R6 dataset topic %s is not reachable from exactly one group" % t["id"])
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
PKG="com.diegonmarcos.cloudwiki"
BEFORE="$HERE/wiki-nav-before.json"; DATA="$APP/../data/fleet/app/$PKG/wiki.json"

echo "[regroup] parity with the menu before #1857 (#1867 nested one level more)"
out="$(check "$APP/build.json" "$BEFORE" "$DATA")" && ok "R1-R6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <python edit of d (build.json) / fx (before) / wk (wiki.json)> <needle expected in the output>
  python3 - "$APP/build.json" "$BEFORE" "$DATA" "$W" "$2" <<'PY'
import json, sys
d, fx, wk = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:4])
w = next(s for s in d["ui"]["sections"] if s["id"] == "wiki")
grp = {g["id"]: g for g in w["groups"]}
page = {p["id"]: p for p in w["pages"]}
for p in list(w["pages"]):
    for c in p.get("pages", []): page.setdefault(c["id"], c)
def kids(h): return page[h]["pages"]
def drop(h, pid): page[h]["pages"] = [c for c in page[h]["pages"] if c["id"] != pid]
exec(sys.argv[5])
for n, o in (("build", d), ("before", fx), ("wiki", wk)): json.dump(o, open(sys.argv[4] + "/" + n + ".json", "w"))
PY
  if out="$(check "$W/build.json" "$W/before.json" "$W/wiki.json" 2>&1)"; then bad "mutant '$1' stayed green"
  elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
mutant "a topic leaves its group"     "drop('arts', 'music')"                                                         "R2 .* music"
mutant "a topic is undeclared"        "drop('humanities', 'anthropology')"                                            "R1 page anthropology is declared 0 times"
mutant "a topic is relabelled"        "page['cs']['label']='CS'"                                                      "R1 page cs changed its label"
mutant "a topic changes its icon"     "page['math']['icon']='star'"                                                   "R1 page math changed its label or icon"
mutant "a topic moves group"          "e=page['eng'];drop('sciences','eng');kids('arts').append(e)"                  "R2 page eng is reachable from"
mutant "Chronos stays in Humanities"  "c=page['chronos'];w['pages'].remove(c);grp['projects']['pages'].remove('chronos');kids('humanities').append(c)" "R2 page chronos is reachable from"
mutant "Chronos is in both groups"    "grp['modern']['pages'].append('chronos')"                                      "R2 page chronos is reachable from"
mutant "Chronos loses a nested page"  "page['chronos']['pages'].pop()"                                                "R4 Chronos' nested pages differ"
mutant "Chronos is relabelled inside" "page['chronos']['pages'][1]['label']='X'"                                      "R4 Chronos' nested pages differ"
mutant "a Chronos asset is lost"      "page['chronos']['pages'][1]['asset']='Chronos/0.Humansphere-Circadian/index.html'" "R4 Chronos' nested pages differ"
mutant "a group is removed"           "w['pages']=[p for p in w['pages'] if p['id']!='humanities'];grp['modern']['pages'].remove('humanities')" "R3 group humanities was removed"
mutant "a group is renamed"           "page['arts']['label']='Craft'"                                                 "R3 group arts changed its label"
mutant "Projects comes before Arcanum" "g=w['groups'];g[1],g[2]=g[2],g[1]"                                            "R3 the groups are"
mutant "a hub moves out of Modern"    "grp['modern']['pages'].remove('explore');grp['arcanum']['pages'].append('explore')" "R3 Modern Science does not hold"
mutant "Sacred Geometry is missing"   "grp['projects']['pages'].remove('sacred')"                                     "R5 Projects does not hold"
mutant "the page list is reordered"   "w['pages'].reverse()"                                                          "R5 the page list does not follow"
mutant "a dataset topic is orphaned"  "wk['topics'].append({'id':'orphan','pages':[]})"                               "R6 dataset topic orphan"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
