#!/usr/bin/env bash
# Tester (#1961): Proj tabs and README coverage. T1 RepoViews declares the tab order README.html, GH Repo Page, README.canvas, README.md (+ the
# entry's site last) and README.html is the default; T2 every Proj page file of a registry repo (data/web/me-links.json, source.repo) declares
# `repo` (its GitHub page) and `views` in that order, CG's site entries included (they add "site" and keep their url); T3 the README trio
# fixture (data/regen-proj-readme-trio.py, GitHub REST) covers every such entry, CG included, with README.html, README.canvas and README.md all
# present; T4 the ship workflow reads the ProjTabs1961Test verdict from its JUnit XML and passes dispatch inputs through env and an allow-list.
# Each check is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
T = lambda p: open(os.path.join(app, p), encoding="utf-8").read(); J = lambda p: json.loads(T(p))
ORDER = ["html", "gh", "canvas", "md"]
K = T("app/src/main/java/com/diegonmarcos/cloudme/RepoViews.kt")
m = re.search(r"val VIEWS = listOf\((.*)\)", K)
ids = re.findall(r"View\((\w+),", m.group(1)) if m else []
if ids != ["HTML", "GH", "CANVAS", "MD", "SITE"]: print("T1\tRepoViews.VIEWS order is %r" % ids)
if "else IDS.first()" not in K: print("T1\tthe default tab is not the first (README.html)")
V = T("app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt")
if 'block.optString("repo").ifEmpty { block.optString("url") }' not in V: print("T2\tthe viewer does not read the entry's repo")
if "RepoViews.SITE -> WebPage(block, body, pageId = pageId)" not in V: print("T2\tthe site tab is not the entry's own page")
if "?: views.first()" not in V: print("T1\tthe viewer does not open the first declared tab")
if ".horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag(RepoViewerTags.SELECTOR)" not in V: print("T1\tthe tab row does not scroll (README.md and Site fall off a phone screen)")
links = J("data/web/me-links.json")["entries"]; n = 0; cg = 0
for e in links:
    if e.get("section") != "proj" or "repo" not in e["source"]: continue
    f = "data/ui/proj/" + "/".join(e["path"]) + ".json"
    if not os.path.exists(os.path.join(app, f)): print("T2\t%s has no page file" % f); continue
    b = J(f)[0]; n += 1; site = not b.get("url", "").startswith("https://github.com/")
    want = ORDER + (["site"] if site else [])
    if b.get("views") != want: print("T2\t%s views %r != %r" % (e["id"], b.get("views"), want))
    r = b.get("repo", "")
    if not r.startswith("https://github.com/diegonmarcos/" + e["source"]["repo"]): print("T2\t%s repo %r is not %s" % (e["id"], r, e["source"]["repo"]))
    if b.get("url") != e["url"]: print("T2\t%s url %r is not the declared %r" % (e["id"], b.get("url"), e["url"]))
    if e["id"].startswith("proj-cg-"): cg += 1
if n < 24: print("T2\tonly %d Proj repo entries" % n)
if cg < 2: print("T2\tCG declares %d repo entries (want 2)" % cg)
fx = J("test/fixtures/proj-readme-trio-1961.json")
for e in links:
    if e.get("section") != "proj" or "repo" not in e["source"]: continue
    k = e["path"][-1]; v = fx.get(k)
    if not v: print("T3\t%s is not in the README trio fixture" % k); continue
    if v["repo"] != "diegonmarcos/" + e["source"]["repo"]: print("T3\t%s fixture repo %s" % (k, v["repo"]))
    gp = J("data/ui/proj/" + "/".join(e["path"]) + ".json")[0].get("repo", "").split("/")
    if gp[5:7] == ["tree", "main"] and v.get("dir") != "/".join(gp[7:]): print("T3\t%s is a folder entry (%s) but the fixture's trio dir is %r (the README tabs read <folder>/README.*)" % (k, "/".join(gp[7:]), v.get("dir")))
    for f in ("README.html", "README.canvas", "README.md"):
        if not v["files"].get(f): print("T3\t%s (%s) lacks %s on main" % (k, v["repo"], f))
W = open(os.path.join(app, "..", ".github/workflows/ship-cloud-me.yml"), encoding="utf-8").read()
if "TEST-*.ProjTabs1961Test.xml" not in W or 'c.find("skipped")' not in W.split("ProjTabs1961Test.xml", 1)[-1][:900]: print("T4\tthe workflow does not read the ProjTabs1961Test verdict from the XML")
if re.search(r"run:[^\n]*\n(?:[^\n]*\n){0,8}?[^\n]*\$\{\{\s*inputs\.", W): print("T4\ta dispatch input is expanded inside a run script (use env)")
if "INPUT_VARIANT" not in W or not re.search(r"debug\|release\)", W): print("T4\tthe variant input is not allow-listed through env")
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "T1-T4 tab order, CG repo tabs, README trio coverage, CI XML verdict"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/r"; mkdir -p "$T/r/ac_cloud-me/app/src/main/java/com/diegonmarcos" "$T/r/ac_cloud-me/test" "$T/r/.github/workflows"
  cp -r "$APP/app/src/main/java/com/diegonmarcos/cloudme" "$T/r/ac_cloud-me/app/src/main/java/com/diegonmarcos/"; cp -r "$APP/data" "$T/r/ac_cloud-me/data"
  cp -r "$APP/test/fixtures" "$T/r/ac_cloud-me/test/fixtures"; cp "$APP/../.github/workflows/ship-cloud-me.yml" "$T/r/.github/workflows/"
  python3 -I -c "$2" "$T/r/ac_cloud-me"; r=$(check "$T/r/ac_cloud-me"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
K='app/src/main/java/com/diegonmarcos/cloudme/RepoViews.kt'
mut "GH Repo Page is first again" "import sys;p=sys.argv[1]+'/$K';s=open(p).read().replace('listOf(View(HTML, \"README.html\"), View(GH, null)','listOf(View(GH, null), View(HTML, \"README.html\")');open(p,'w').write(s)"
mut "the default tab is GH" "import sys;p=sys.argv[1]+'/$K';s=open(p).read().replace('else IDS.first()','else GH');open(p,'w').write(s)"
mut "a CG entry loses its README tabs" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-cg/pj-cg-omniverse.json";d=json.load(open(p));del d[0]["views"];json.dump(d,open(p,"w"))'
mut "a CG entry loses its repo" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-cg/pj-cg-zirkusverse.json";d=json.load(open(p));del d[0]["repo"];json.dump(d,open(p,"w"))'
mut "a CG entry drops its site tab" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-cg/pj-cg-zirkusverse.json";d=json.load(open(p));d[0]["views"]=d[0]["views"][:4];json.dump(d,open(p,"w"))'
mut "an entry declares README.md before README.canvas" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-unix/pj-unix-superapp.json";d=json.load(open(p));d[0]["views"]=["html","gh","md","canvas"];json.dump(d,open(p,"w"))'
mut "CG-galaxy lacks README.canvas" 'import json,sys;p=sys.argv[1]+"/test/fixtures/proj-readme-trio-1961.json";d=json.load(open(p));d["pj-cg-omniverse"]["files"]["README.canvas"]=0;json.dump(d,open(p,"w"))'
mut "a Wiki folder entry's trio is read at the repo root" 'import json,sys;p=sys.argv[1]+"/test/fixtures/proj-readme-trio-1961.json";d=json.load(open(p));del d["pj-wiki-modernsc"]["dir"];json.dump(d,open(p,"w"))'
mut "a Wiki folder has no README.md on main" 'import json,sys;p=sys.argv[1]+"/test/fixtures/proj-readme-trio-1961.json";d=json.load(open(p));d["pj-wiki-arcanum"]["files"]["README.md"]=0;json.dump(d,open(p,"w"))'
mut "a project is missing from the trio fixture" 'import json,sys;p=sys.argv[1]+"/test/fixtures/proj-readme-trio-1961.json";d=json.load(open(p));del d["pj-cg-zirkusverse"];json.dump(d,open(p,"w"))'
mut "the tab row stops scrolling" "import sys;p=sys.argv[1]+'/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt';s=open(p).read().replace('.horizontalScroll(rememberScrollState())','');open(p,'w').write(s)"
mut "the viewer ignores repo" "import sys;p=sys.argv[1]+'/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt';s=open(p).read().replace('block.optString(\"repo\").ifEmpty { block.optString(\"url\") }','block.optString(\"url\")');open(p,'w').write(s)"
mut "the XML verdict step is removed" 'import sys;p=sys.argv[1]+"/../.github/workflows/ship-cloud-me.yml";s=open(p).read().replace("ProjTabs1961Test.xml","Other.xml");open(p,"w").write(s)'
mut "a dispatch input is expanded in run" 'import sys;p=sys.argv[1]+"/../.github/workflows/ship-cloud-me.yml";s=open(p).read().replace("echo \"variant=$INPUT_VARIANT\"","echo \"variant=${{ inputs.variant }}\"");open(p,"w").write(s)'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
