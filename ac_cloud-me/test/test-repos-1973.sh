#!/usr/bin/env bash
# Tester (#1973): the Proj nav reads Home | Repos | Unix | CG | Capital | ...; the Repos page is the bundled explorer (List | Table | Canvas);
# field parity against the previous Repos page (the bundled Dolphin tree repos-tree.html, which stays): every repo, every field and every folder group of it
# is still in the explorer's rows; the explorer lists ALL repos with children after their parent (depth = parent depth + 1) and the Table carries every
# registry field. Each check is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]; J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8")); T = lambda p: open(os.path.join(app, p), encoding="utf-8").read()
def E(c, m): print("%s\t%s" % (c, m))
# N: nav order and separators
proj = [s for s in J("build.json")["ui"]["sections"] if s["id"] == "proj"][0]["pages"]
ids = [p["id"] for p in proj]
if ids != ["pj-home", "pj-repos", "pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42"]: E("N1", "Proj nav order is %r, want Home | Repos | Unix | CG | Capital | Hardtech | Wiki | Projects | 42" % ids)
if [bool(p.get("separator_before")) for p in proj[:4]] != [False, True, True, False] or any(p.get("separator_before") for p in proj[3:]): E("N2", "separators are not Home | Repos | Unix | CG ...")
# P: the page embeds the bundled explorer
try:
    b = J("data/ui/proj/pj-repos.json")[0]
    if b.get("url") != "repos-explorer.html": E("P1", "pj-repos does not embed repos-explorer.html (%r)" % b.get("url"))
except Exception as e: E("P1", "no pj-repos page (%s)" % e)
H = T("data/web/repos-explorer.html")
for need in ('data-m="list"', 'data-m="table"', 'data-m="canvas"', 'th[data-c]', 'id="q"', 'aria-label="repos canvas"', 'var J='):
    if need not in H: E("P2", "explorer page lacks %s" % need)
if "repos-tree.html" not in H or len(T("data/web/repos-tree.html")) < 50000: E("P3", "the former Repos tree is not bundled/reachable")
# data: rows embedded in the page equal repos-explorer.json
D = J("data/web/repos-explorer.json"); R = D["rows"]
m = re.search(r"var J=(\{.*?\}),R=J\.rows", H, re.S)
try:
    if not m or json.loads(m.group(1).replace("\\u003c", "<")) != D: E("P4", "the page data differs from repos-explorer.json")
except Exception as e: E("P4", "page data unreadable (%s)" % e)
# F: parity against the previous page
old = T("data/web/repos-tree.html"); i = old.index("var D=") + 6; d = 0; j = i
while True:
    c = old[j]; d += (c == "{") - (c == "}"); 
    if d == 0: break
    j += 1
O = json.loads(old[i:j + 1])["nodes"]; by = {r["name"]: r for r in R}
repos = [n for n in O if not n.get("group")]; groups = [n for n in O if n.get("group")]
if len(repos) < 50: E("F0", "previous page has only %d repos" % len(repos))
for n in repos:
    r = by.get(n["name"])
    if r is None: E("F1", "repo %s of the previous page is missing" % n["name"]); continue
    for k, v in n.items():
        if k == "gh":
            for k2, v2 in (v or {}).items():
                if (r.get("gh") or {}).get(k2) != v2: E("F2", "%s gh.%s lost or changed" % (n["name"], k2))
        elif k not in ("parent",) and k not in r: E("F2", "%s field %s lost" % (n["name"], k))
        elif k not in ("parent",) and r[k] != v: E("F2", "%s field %s changed (%r -> %r)" % (n["name"], k, v, r[k]))
gp = {g["path"] for g in D.get("groups", [])}
for g in groups:
    if g["id"].replace("group:", "") not in gp: E("F3", "folder group %s of the previous page is gone" % g["id"])
# L: all repos, children after their parent and indented one level deeper
seen = {}
for r in R:
    if r["parent"]:
        p = seen.get(r["parent"])
        if p is None: E("L1", "%s is listed before its parent %s" % (r["name"], r["parent"]))
        elif r["depth"] != p["depth"] + 1: E("L1", "%s depth %s is not parent depth + 1" % (r["name"], r["depth"]))
    elif r["depth"] != 0: E("L1", "%s has no parent but depth %s" % (r["name"], r["depth"]))
    if r["name"] in seen: E("L2", "%s listed twice" % r["name"])
    seen[r["name"]] = r
if len(R) != D.get("count") or len(R) < 70: E("L2", "%d rows, count %s" % (len(R), D.get("count")))
# T: the table carries every registry field
REG = ["code", "name", "node", "farm", "link", "project", "field", "family", "subcategory", "visibility", "developer", "url", "kind", "html_repo", "lfs_repo", "gh", "vis_icon", "role", "parent",
       "depth", "children_count", "registry_label", "registry_kind", "description", "doc", "private", "ssh_url", "area", "area_code", "project_code", "family_code", "subfamily", "subfamily_code",
       "registry_group", "school", "hub_of", "up", "path", "renamed_from", "lfs_registry", "apps", "parts", "tree_source", "tree_head", "tree_entries", "tree_files"]
for k in REG:
    if not all(k in r for r in R): E("T1", "the Table lacks the registry field %s on some repo" % k)
sot = os.environ.get("ME_SOT_DIR") or os.path.abspath(os.path.join(app, "..", "..", ".."))
pj = os.path.join(sot, "git-repos-master", "A0_repos-tree", "dist", "repos-explorer.json")
if os.path.exists(pj) and open(pj, "rb").read() != open(os.path.join(app, "data/web/repos-explorer.json"), "rb").read() and not os.environ.get("MUTANT"): E("T2", "repos-explorer.json differs from _git-repos-master (run data/regen-repos-explorer.py)")
PY
}
if [ "${1:-}" = --check ]; then o=$(MUTANT=1 check "${2:-$APP}"); [ -z "$o" ] && exit 0; echo "$o"; exit 1; fi   # CI mutation job: exit 1 = the tree is red
out=$(check "$APP"); if [ -z "$out" ]; then ok "N/P/F/L/T: nav order, bundled explorer, field parity with the previous page, hierarchy, full table fields"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data/web" "$T/app/data/web"; cp -r "$APP/data/ui" "$T/app/data/ui";
  MUTANT=1 python3 -c "$2" "$T/app"; r=$(MUTANT=1 check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
BJ='import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p,encoding="utf-8"));S=[s for s in d["ui"]["sections"] if s["id"]=="proj"][0]["pages"];'
WB='json.dump(d,open(p,"w",encoding="utf-8"),ensure_ascii=False)'
RJ='import json,sys;p=sys.argv[1]+"/data/web/repos-explorer.json";d=json.load(open(p,encoding="utf-8"));'
mut "Repos back at the end of the Proj nav" "${BJ}S.append(S.pop(1));$WB"
mut "the separator after Repos is gone" "${BJ}S[2].pop('separator_before');$WB"
mut "Repos page points at the old tree" 'import sys;p=sys.argv[1]+"/data/ui/proj/pj-repos.json";s=open(p,encoding="utf-8").read().replace("repos-explorer.html","repos-tree.html");open(p,"w",encoding="utf-8").write(s)'
mut "the Canvas view is dropped" 'import sys;p=sys.argv[1]+"/data/web/repos-explorer.html";s=open(p,encoding="utf-8").read().replace("data-m=\"canvas\"","data-m=\"x\"");open(p,"w",encoding="utf-8").write(s)'
mut "a repo field of the previous page is lost (stars)" "${RJ}[r['gh'].pop('stars') for r in d['rows']];json.dump(d,open(p,'w',encoding='utf-8'))"
mut "a registry field is lost from the Table (school)" "${RJ}[r.pop('school') for r in d['rows']];json.dump(d,open(p,'w',encoding='utf-8'))"
mut "a repo is missing from the list" "${RJ}d['rows']=[r for r in d['rows'] if r['name']!='cloudy-u-android'];d['count']-=1;json.dump(d,open(p,'w',encoding='utf-8'))"
mut "a child is listed before its parent" "${RJ}r=d['rows'];i=[k for k,x in enumerate(r) if x['name']=='cloudy-u-android'][0];r.insert(0,r.pop(i));json.dump(d,open(p,'w',encoding='utf-8'))"
mut "a folder group is lost" "${RJ}d['groups']=d['groups'][1:];json.dump(d,open(p,'w',encoding='utf-8'))"
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
