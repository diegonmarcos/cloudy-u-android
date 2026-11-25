#!/usr/bin/env bash
# Tester (#1918): E1 every Proj page label starts with its Linktree emoji (data/web/me-proj-emoji.json); E2 every main category has a Linktree banner URL;
# E3 Repos follows 42 and embeds the bundled repos tree; E4 Prof > Profiles is LinkedIn | CV and the old Profiles pages are still pages of Prof;
# E5 the table and the tree copy equal their sources (when the sibling repos are present). Each is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" <<'PY'
import json, os, sys
app = sys.argv[1]; J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8"))
ui = J("build.json")["ui"]; secs = {s["id"]: s for s in ui["sections"]}; tab = J("data/web/me-proj-emoji.json")
def flat(o):
    for p in o.get("pages", []) or []:
        yield p; yield from flat(p)
for p in flat(secs["proj"]):
    e = tab["emoji"].get(p["id"], {}).get("emoji")
    if not e or not p["label"].startswith(e + " "): print("E1\t%s label %r lacks its emoji %r" % (p["id"], p["label"], e))
for g in ("pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos"):
    u = tab["banners"].get(g, {}).get("url", "")
    if not u.startswith("https://linktree.diegonmarcos.com/public/images/banners/"): print("E2\t%s has no Linktree banner (%r)" % (g, u))
ids = [p["id"] for p in secs["proj"]["pages"]]
if "pj-repos" not in ids or ids.index("pj-repos") != ids.index("pj-lecole42") + 1: print("E3\tRepos does not follow 42")
try:
    if J("data/ui/proj/pj-repos.json")[0].get("url") != "repos-tree.html" or len(open(os.path.join(app, "data/web/repos-tree.html"), encoding="utf-8").read()) < 50000: print("E3\tRepos does not embed the bundled tree")
except Exception as x: print("E3\t%s" % x)
prof = {p["id"]: p for p in flat(secs["prof"])}
if [p["id"] for p in prof.get("prof-web", {}).get("pages", [])] != ["prof-linkedin", "prof-cv"]: print("E4\tProf > Profiles is not LinkedIn | CV")
for old in ("prof-profiles", "professional", "work", "study", "cv", "roadmap", "leafy", "nexus", "stark"):
    if old not in prof: print("E4\told page %s is gone from Prof" % old)
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "E1-E4 emoji, banners, Repos and Profiles hold"; else bad "$out"; fi
if [ -d "$APP/../../cloud-me-pub-profiles-_lnktree" ] || [ -n "${ME_SOT_DIR:-}" ]; then
  python3 "$APP/data/regen-proj-emoji.py" --check >/dev/null && ok "E5 emoji and banners equal the Linktree data" || bad "E5 the table differs from the Linktree data"
fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data/web" "$T/app/data/web"; cp -r "$APP/data/ui" "$T/app/data/ui";
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "a Proj label loses its emoji" 'import sys;p=sys.argv[1]+"/build.json";s=open(p,encoding="utf-8").read().replace("\U0001F427 Unix","Unix");open(p,"w",encoding="utf-8").write(s)'
mut "a banner is dropped" 'import json,sys;p=sys.argv[1]+"/data/web/me-proj-emoji.json";d=json.load(open(p,encoding="utf-8"));del d["banners"]["pj-cg"];json.dump(d,open(p,"w",encoding="utf-8"))'
mut "a banner leaves the Linktree site" 'import json,sys;p=sys.argv[1]+"/data/web/me-proj-emoji.json";d=json.load(open(p,encoding="utf-8"));d["banners"]["pj-cg"]["url"]="https://example.com/x.gif";json.dump(d,open(p,"w",encoding="utf-8"))'
mut "Repos is dropped" 'import sys;p=sys.argv[1]+"/data/ui/proj/pj-repos.json";import os;os.remove(p)'
mut "Repos tree is empty" 'import sys;open(sys.argv[1]+"/data/web/repos-tree.html","w").write("x")'
mut "LinkedIn tab is dropped" 'import re,sys;p=sys.argv[1]+"/build.json";s=open(p,encoding="utf-8").read().replace("\"id\": \"prof-linkedin\"","\"id\": \"prof-x\"");open(p,"w",encoding="utf-8").write(s)'
mut "the old Work page is gone" 'import sys;p=sys.argv[1]+"/build.json";s=open(p,encoding="utf-8").read().replace("\"id\": \"work\"","\"id\": \"wrk\"");open(p,"w",encoding="utf-8").write(s)'
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
