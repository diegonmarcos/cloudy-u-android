#!/usr/bin/env bash
# Tester (#1932): Proj > every repo entry has the four-view selector. V1 build.json is walked: every last-child of Proj whose page file embeds a
# github.com/<owner>/<repo>[/tree/<ref>/<dir>] URL declares views gh|md|html|canvas; V2 parity: the GH Repo Page (today's view) is still the entry's own url
# (== link), SectionScreen still hosts the bare WebPage for it, and no Proj page file or url was dropped against the baseline fixture; V3 the views are
# derived (RepoViews builds raw.githubusercontent.com/<owner>/<repo>/main/README.* from the url, no per-repo list), the selected view is remembered per
# repo, a failed fetch is verbose (URL, status, Retry); V4 libs:json-canvas is a module, an app dependency, wired in settings through build.json and in
# the ship workflow's paths; V5 every new string is in values and values-es; V6 README.html is a WebView with JavaScript off and a base URL.
# Each is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" "$2" <<'PY'
import json, os, re, sys, xml.etree.ElementTree as ET
app, repo = sys.argv[1], sys.argv[2]
J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8")); T = lambda p: open(os.path.join(app, p), encoding="utf-8").read()
R = re.compile(r"^https://github\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)(?:/tree/([A-Za-z0-9._-]+)/(.+?))?/?$")
ui = J("build.json")["ui"]; proj = [s for s in ui["sections"] if s["id"] == "proj"][0]
def leaves(o, path):
    ks = o.get("pages") or []
    if not ks: yield path; return
    for k in ks: yield from leaves(k, path + [k["id"]])
n = 0; urls = {}
for p in leaves(proj, []):
    if not p: continue
    f = "data/ui/proj/" + "/".join(p) + ".json"
    if not os.path.exists(os.path.join(app, f)): print("V1\t%s has no page file" % "/".join(p)); continue
    b = J(f)[0]; u = b.get("url", ""); urls[p[-1]] = u
    if b.get("id") == "web" and R.match(u):
        n += 1
        if b.get("views") != ["gh", "md", "html", "canvas"]: print("V1\t%s does not declare the four views (%r)" % (p[-1], b.get("views")))
        if b.get("link") != u: print("V2\t%s GH Repo Page (link) is not its own url" % p[-1])
if n < 20: print("V1\tonly %d Proj repo entries found" % n)
base = J("test/fixtures/proj-repo-urls-1932.json")
for k, u in base.items():
    if urls.get(k) != u: print("V2\t%s url %r differs from the baseline %r (nothing may be dropped)" % (k, urls.get(k), u))
S = T("app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt")
if "WebPage(single, modifier, pageId = page.id)" not in S or "RepoViewerScreen(" not in S: print("V2\tSectionScreen lost the bare WebPage or does not host the viewer")
V = T("app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt")
if "RepoViews.GH -> WebPage(block, body, pageId = pageId)" not in V: print("V2\tthe GH view is not the entry's WebPage")
K = T("app/src/main/java/com/diegonmarcos/cloudme/RepoViews.kt")
for need in ('https://raw.githubusercontent.com/$owner/$repo/$branch/', '"README.md"', '"README.html"', '"README.canvas"', 'View(GH, null)'):
    if need not in K: print("V3\tRepoViews lacks %s" % need)
if re.search(r"diegonmarcos/(?!\$)", K.replace("diegonmarcos/_", "")) and "github.com/diegonmarcos" in K: print("V3\tRepoViews hard-codes a repo")
for need in ("RepoViewMemory.put(ctx, ref, v)", "RepoViewMemory.get(ctx, ref)", "repo_view_detail_url", "repo_view_retry", "repo_view_detail_status", "repo_view_detail_error"):
    if need not in V: print("V3\tRepoViewerScreen lacks %s" % need)
bj = J("build.json"); m = bj["modules"]
if "libs:json-canvas" not in m or not m["libs:json-canvas"].get("dir", "").endswith("libs/json-canvas"): print("V4\tbuild.json lacks the libs:json-canvas module")
if "libs:json-canvas" not in m["app"]["depends_on"]: print("V4\tapp does not depend on libs:json-canvas")
if "project(':libs:json-canvas')" not in T("app/build.gradle"): print("V4\tapp/build.gradle does not link libs:json-canvas")
if not os.path.exists(os.path.join(app, "../ab_cloud-libs-shared/libs/json-canvas/build.gradle")): print("V4\tthe lib has no build.gradle")
for wf in (".github/workflows/ship-cloud-me.yml",):
    if "libs/json-canvas/**" not in open(os.path.join(app, "..", wf), encoding="utf-8").read(): print("V4\t%s does not watch the lib" % wf)
new = ["repo_view_gh", "repo_view_md", "repo_view_html", "repo_view_canvas", "repo_view_loading", "repo_view_failed_title", "repo_view_detail_url", "repo_view_detail_status", "repo_view_detail_error",
       "repo_view_kind_notfound", "repo_view_kind_private", "repo_view_kind_refused", "repo_view_kind_server", "repo_view_kind_network", "repo_view_retry", "repo_view_bad_canvas",
       "canvas_fit", "canvas_open", "canvas_close", "canvas_file", "canvas_link", "canvas_group", "canvas_empty"]
import glob
for f in sorted(glob.glob(os.path.join(app, "app/src/main/res/values*/strings.xml"))):
    names = {e.get("name") for e in ET.parse(f).getroot()}
    for s in new:
        if s not in names: print("V5\t%s lacks %s" % (os.path.relpath(f, app), s))
if "settings.javaScriptEnabled = false" not in V or "loadDataWithBaseURL(ref.baseUrl()" not in V: print("V6\tREADME.html is not a JS-off WebView loaded with a base URL")
PY
}
out=$(check "$APP" x); if [ -z "$out" ]; then ok "V1-V6 views declared, GH page kept, derived, wired, translated"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/r"; mkdir -p "$T/r/ac_cloud-me/app/src/main" "$T/r/ab_cloud-libs-shared/libs/json-canvas" "$T/r/.github"
  cp -r "$APP/app/src/main/java" "$T/r/ac_cloud-me/app/src/main/java"; cp -r "$APP/app/src/main/res" "$T/r/ac_cloud-me/app/src/main/res"
  cp "$APP/build.json" "$T/r/ac_cloud-me/"; cp "$APP/app/build.gradle" "$T/r/ac_cloud-me/app/build.gradle"
  cp -r "$APP/data" "$T/r/ac_cloud-me/data" ; mkdir -p "$T/r/ac_cloud-me/test"; cp -r "$APP/test/fixtures" "$T/r/ac_cloud-me/test/fixtures"
  cp "$APP/../ab_cloud-libs-shared/libs/json-canvas/build.gradle" "$T/r/ab_cloud-libs-shared/libs/json-canvas/"; cp -r "$APP/../.github/workflows" "$T/r/.github/workflows"
  python3 -c "$2" "$T/r/ac_cloud-me"; r=$(check "$T/r/ac_cloud-me" x); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "an entry loses its views" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-unix/pj-unix-superapp.json";d=json.load(open(p,encoding="utf-8"));del d[0]["views"];json.dump(d,open(p,"w",encoding="utf-8"))'
mut "an entry declares only three views" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-capital/pj-capital-pm.json";d=json.load(open(p,encoding="utf-8"));d[0]["views"]=["gh","md","html"];json.dump(d,open(p,"w",encoding="utf-8"))'
mut "the GH page diverges from the entry url" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-unix/pj-unix-cloudy.json";d=json.load(open(p,encoding="utf-8"));d[0]["link"]="https://github.com/x/y";json.dump(d,open(p,"w",encoding="utf-8"))'
mut "an entry url is dropped" 'import json,sys;p=sys.argv[1]+"/data/ui/proj/pj-hardtech/pj-hardtech-cad.json";d=json.load(open(p,encoding="utf-8"));d[0]["url"]="https://github.com/diegonmarcos/other";d[0]["link"]=d[0]["url"];json.dump(d,open(p,"w",encoding="utf-8"))'
mut "a Proj page file is deleted" 'import os,sys;os.remove(sys.argv[1]+"/data/ui/proj/pj-wiki/pj-wiki-arcanum.json")'
mut "the GH view stops being the WebPage" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt";s=open(p,encoding="utf-8").read().replace("RepoViews.GH -> WebPage(block, body, pageId = pageId)","RepoViews.GH -> Unit");open(p,"w",encoding="utf-8").write(s)'
mut "SectionScreen drops the bare WebPage" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt";s=open(p,encoding="utf-8").read().replace("WebPage(single, modifier, pageId = page.id)","Unit");open(p,"w",encoding="utf-8").write(s)'
mut "the canvas view is hard-coded to a repo" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/RepoViews.kt";s=open(p,encoding="utf-8").read().replace("\"README.canvas\"","\"canvas.json\"");open(p,"w",encoding="utf-8").write(s)'
mut "the choice is not remembered" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt";s=open(p,encoding="utf-8").read().replace("RepoViewMemory.put(ctx, ref, v)","Unit");open(p,"w",encoding="utf-8").write(s)'
mut "the failure view loses Retry" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt";s=open(p,encoding="utf-8").read().replace("repo_view_retry","repo_view_x");open(p,"w",encoding="utf-8").write(s)'
mut "the lib module is unlinked" 'import sys;p=sys.argv[1]+"/build.json";s=open(p,encoding="utf-8").read().replace("\"libs:json-canvas\"","\"libs:other\"");open(p,"w",encoding="utf-8").write(s)'
mut "a Spanish string is missing" 'import sys;p=sys.argv[1]+"/app/src/main/res/values-es/strings.xml";s=open(p,encoding="utf-8").read().replace("name=\"canvas_fit\"","name=\"canvas_fitx\"");open(p,"w",encoding="utf-8").write(s)'
mut "README.html enables JavaScript" 'import sys;p=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/RepoViewerScreen.kt";s=open(p,encoding="utf-8").read().replace("javaScriptEnabled = false","javaScriptEnabled = true");open(p,"w",encoding="utf-8").write(s)'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
