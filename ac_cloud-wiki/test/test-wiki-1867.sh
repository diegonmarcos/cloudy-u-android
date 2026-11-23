#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-wiki-1867 — Modern | Arcanum | Projects removes nothing      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Rule 16 (a reorganisation only adds structure). #1867 moved the wiki into the content repo diegonmarcos/Projects-Library-KG (one numbered
# folder per node, wiki.json generated from the tree) and nested the side menu under three parents. test/wiki-1867-before.json is the
# frozen previous menu and dataset; this proves against build.json::ui wiki and the dataset now served (data/fleet/.../wiki.json):
#   P1  every previous side-menu page is still declared once, with the same label and icon, and is reachable from a group (directly or
#       through its hub); every previous group still exists, as a hub page of Modern Science or as a group, with its label and icon
#   P2  every previous topic is still a dataset topic with the same label and intro, and the same tabs (its page ids, in order)
#   P3  every previous page keeps its topic, title, tags (same list) and Markdown byte for byte, so every [[link]] it had is still there
#       and still resolves to a page
#   P4  every previous tag still filters at least as many pages as before
#   P5  Chronos keeps every simulator (ids, labels, icons, hierarchy); its assets moved with the folder Chronos/ -> 3.Projects/3.0.Chronos/
#   P7  every graph edge between previous pages is still an edge (libs:graph-view GraphBuilder's rules: a [[link]], or a shared tag held
#       by at most 12 pages): new pages must not crowd an old tag past the cap and so silently cut the old pages apart
#   P6  THE REPO TREE EQUALS THE APP MENU: build.json::ui wiki groups and pages are exactly the dataset's `menu` (parents -> groups,
#       hubs -> pages holding their topics, Chronos -> its simulators, a project -> `project` = its folder), so a folder added, dropped
#       or renamed in the repo shows here
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <build.json> <before.json> <wiki.json> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
bj, before, wj = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:4])
bad = []
w = next(s for s in bj["ui"]["sections"] if s["id"] == "wiki")
OLD, NEW = "Chronos/", "3.Projects/3.0.Chronos/"
decl, reach = {}, {}
for g in w["groups"]:
    for pid in g["pages"]: reach.setdefault(pid, []).append(g["id"])
for p in w["pages"]:
    decl.setdefault(p["id"], []).append(p)
    if p["id"] != "chronos":
        for c in p.get("pages", []):
            decl.setdefault(c["id"], []).append(c)
            if p["id"] in reach: reach.setdefault(c["id"], []).append(p["id"])
holders = {g["id"]: g for g in w["groups"]}
holders.update({p["id"]: p for p in w["pages"] if p["id"] != "chronos" and p.get("pages")})
# P1
for bp in before["menu"]:
    now = decl.get(bp["id"], [])
    if len(now) != 1: bad.append("P1 page %s is declared %d times (was once)" % (bp["id"], len(now))); continue
    if (now[0].get("label"), now[0].get("icon")) != (bp["label"], bp["icon"]): bad.append("P1 page %s changed its label or icon" % bp["id"])
    if len(reach.get(bp["id"], [])) != 1: bad.append("P1 page %s is not reachable from exactly one group or hub (%s)" % (bp["id"], reach.get(bp["id"])))
for bg in before["groups"]:
    h = holders.get(bg["id"])
    if h is None: bad.append("P1 group %s is gone" % bg["id"])
    elif (h.get("label"), h.get("icon")) != (bg["label"], bg["icon"]): bad.append("P1 group %s changed its label or icon" % bg["id"])
# P2 / P3
topics = {t["id"]: t for t in wj.get("topics", [])}
pages = {p["id"]: (t["id"], p) for t in wj.get("topics", []) for p in t.get("pages", [])}
for bt in before["topics"]:
    t = topics.get(bt["id"])
    if t is None: bad.append("P2 topic %s is gone from the dataset" % bt["id"]); continue
    if (t.get("label"), t.get("intro")) != (bt["label"], bt["intro"]): bad.append("P2 topic %s changed its label or intro" % bt["id"])
    if [p["id"] for p in t.get("pages", [])] != bt["tabs"]: bad.append("P2 topic %s tabs are %s, were %s" % (bt["id"], [p["id"] for p in t.get("pages", [])], bt["tabs"]))
for bp in before["pages"]:
    if bp["id"] not in pages: bad.append("P3 page %s is gone" % bp["id"]); continue
    tid, p = pages[bp["id"]]
    if tid != bp["topic"]: bad.append("P3 page %s moved from topic %s to %s" % (bp["id"], bp["topic"], tid))
    if p.get("title") != bp["title"]: bad.append("P3 page %s changed its title" % bp["id"])
    if p.get("tags") != bp["tags"]: bad.append("P3 page %s tags are %s, were %s" % (bp["id"], p.get("tags"), bp["tags"]))
    if p.get("md") != bp["md"]: bad.append("P3 page %s Markdown changed" % bp["id"])
    now_links = list(dict.fromkeys(re.findall(r"\[\[([^\]\s]+)\]\]", p.get("md", ""))))
    for l in bp["links"]:
        if l not in now_links: bad.append("P3 page %s lost its link to %s" % (bp["id"], l))
        elif l not in pages: bad.append("P3 page %s links to %s, which no longer resolves" % (bp["id"], l))
# P4
def cloud(ps):
    c = {}
    for x in ps:
        for tg in set(x["tags"]): c[tg] = c.get(tg, 0) + 1
    return c
was, now = cloud(before["pages"]), cloud([p for _, p in pages.values()])
for tg, n in sorted(was.items()):
    if now.get(tg, 0) < n: bad.append("P4 tag %s filters %d pages, was %d" % (tg, now.get(tg, 0), n))
# P7 the graph (GraphBuilder.build, maxFanout 12)
def edges(ps):
    ids = {x["id"] for x in ps}
    e = set()
    for x in ps:
        for l in re.findall(r"\[\[([^\]\s]+)\]\]", x["md"]):
            if l in ids and l != x["id"]: e.add(tuple(sorted((x["id"], l))))
    by = {}
    for x in ps:
        for tg in set(x["tags"]): by.setdefault(tg, []).append(x["id"])
    for m in by.values():
        if len(m) <= 12: e |= {tuple(sorted((a, b))) for i, a in enumerate(m) for b in m[i + 1:]}
    return e
old_ids = {x["id"] for x in before["pages"]}
lost = sorted(edges(before["pages"]) - {k for k in edges([p for _, p in pages.values()]) if k[0] in old_ids and k[1] in old_ids})
if lost: bad.append("P7 %d graph edges between previous pages are gone, e.g. %s" % (len(lost), lost[:3]))
# P5
def slim(p, move=False):
    o = {k: p[k] for k in ("id", "label", "icon", "asset") if k in p}
    if move and o.get("asset", "").startswith(OLD): o["asset"] = NEW + o["asset"][len(OLD):]
    if p.get("pages"): o["pages"] = [slim(x, move) for x in p["pages"]]
    return o
bc = next((p for p in before["menu"] if p["id"] == "chronos"), {})
nc = (decl.get("chronos") or [{}])[0]
if [slim(x) for x in nc.get("pages", [])] != [slim(x, True) for x in bc.get("pages", [])]: bad.append("P5 Chronos' simulators differ from before (ids, labels, icons, hierarchy or moved assets)")
# P6
want_groups, want_pages = [], []
for parent in wj.get("menu", []):
    want_groups.append({"id": parent["id"], "label": parent["label"], "icon": parent["icon"], "pages": [c["id"] for c in parent.get("children", [])]})
    for c in parent.get("children", []):
        o = {"id": c["id"], "label": c["label"], "icon": c["icon"]}
        if c["kind"] == "hub": o["pages"] = [{"id": t["id"], "label": t["label"], "icon": t["icon"]} for t in c.get("children", [])]
        elif c["kind"] == "project": o["project"] = c["path"]
        want_pages.append(o)
if w["groups"] != want_groups: bad.append("P6 build.json::ui wiki groups are not the repo's menu parents")
strip = [({k: v for k, v in p.items() if k != "pages"} if p["id"] == "chronos" else p) for p in w["pages"]]
if strip != [({k: v for k, v in p.items() if k != "pages"} if p["id"] == "chronos" else p) for p in want_pages]:
    bad.append("P6 build.json::ui wiki pages are not the repo's menu (hubs and their topics, Chronos, projects)")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
PKG="com.diegonmarcos.cloudwiki"
BEFORE="$HERE/wiki-1867-before.json"; DATA="$APP/../data/fleet/app/$PKG/wiki.json"

echo "[1867] parity with the wiki before Modern | Arcanum | Projects, and the repo tree = the app menu"
out="$(check "$APP/build.json" "$BEFORE" "$DATA")" && ok "P1-P6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <python edit of d (build.json) / wk (wiki.json)> <needle expected in the output>
  python3 - "$APP/build.json" "$DATA" "$W" "$2" <<'PY'
import json, sys
d, wk = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:3])
w = next(s for s in d["ui"]["sections"] if s["id"] == "wiki")
grp = {g["id"]: g for g in w["groups"]}
page = {p["id"]: p for p in w["pages"]}
for p in list(w["pages"]):
    for c in p.get("pages", []): page.setdefault(c["id"], c)
top = {t["id"]: t for t in wk["topics"]}
pg = {p["id"]: p for t in wk["topics"] for p in t["pages"]}
exec(sys.argv[4])
for n, o in (("build", d), ("wiki", wk)): json.dump(o, open(sys.argv[3] + "/" + n + ".json", "w"))
PY
  if out="$(check "$W/build.json" "$BEFORE" "$W/wiki.json" 2>&1)"; then bad "mutant '$1' stayed green"
  elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
mutant "a topic leaves the menu"       "page['arts']['pages']=[c for c in page['arts']['pages'] if c['id']!='music']"          "P1 page music is declared 0 times"
mutant "a topic is relabelled"         "page['cs']['label']='CS'"                                                              "P1 page cs changed its label"
mutant "a hub is unreachable"          "grp['modern']['pages'].remove('humanities')"                                          "P1 page anthropology is not reachable"
mutant "an old group is renamed"       "page['sciences']['label']='Science'"                                                  "P1 group sciences changed its label"
mutant "a topic leaves the dataset"    "wk['topics']=[t for t in wk['topics'] if t['id']!='theater']"                         "P2 topic theater is gone"
mutant "a topic's intro is rewritten"  "top['math']['intro']='Numbers.'"                                                      "P2 topic math changed its label or intro"
mutant "a tab is dropped"              "top['physics']['pages'].pop()"                                                        "P2 topic physics tabs are"
mutant "tabs are reordered"            "top['eng']['pages'].reverse()"                                                        "P2 topic eng tabs are"
mutant "a page changes topic"          "p=top['math']['pages'].pop(0);top['cs']['pages'].append(p)"                          "P2 topic math tabs are"
mutant "a page is retitled"            "pg['math.algebra']['title']='Algebra I'"                                              "P3 page math.algebra changed its title"
mutant "a tag is lost"                 "pg['math.algebra']['tags']=pg['math.algebra']['tags'][:-1]"                           "P3 page math.algebra tags are"
mutant "a link is lost"                "pg['math.algebra']['md']=pg['math.algebra']['md'].replace('[[cs.cryptography]]','cryptography')" "P3 page math.algebra lost its link to cs.cryptography"
mutant "a link target is gone"         "top['cs']['pages']=[p for p in top['cs']['pages'] if p['id']!='cs.cryptography']"   "no longer resolves"
mutant "a tag filters fewer pages"     "[p.update(tags=[x for x in p['tags'] if x!='structure']) for p in pg.values()]"       "P4 tag structure filters"
mutant "a new page crowds a tag"      "[t['pages'].append({'id':t['id']+'.x%d'%i,'title':'X','tags':['ritual'],'md':'x'*90}) for t in wk['topics'] if t['id']=='divination' for i in range(12)]" "P7 .* graph edges between previous pages are gone"
mutant "a simulator is lost"           "page['chronos']['pages'].pop()"                                                       "P5 Chronos' simulators differ"
mutant "a simulator keeps its old path" "page['chronos']['pages'][0]['asset']='Chronos/index.html'"                           "P5 Chronos' simulators differ"
mutant "a repo folder has no menu row" "wk['menu'][1]['children'][0]['children'].append({'id':'x','label':'X','icon':'star','kind':'topic','path':'p'})" "P6 build.json::ui wiki pages are not the repo's menu"
mutant "a menu row has no repo folder" "page['arcanum-c']['pages'].append({'id':'x','label':'X','icon':'star'})"              "P6 build.json::ui wiki pages are not the repo's menu"
mutant "a parent is renamed in the app" "grp['arcanum']['label']='Occult'"                                                    "P6 build.json::ui wiki groups are not the repo's menu"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
