#!/usr/bin/env bash
# Tester (#1948): page parity for the Pers > Profiles > Personal / Prof > Profiles move (rule 16: nothing removed).
#   R1 parity   every https URL a data/ui page file embedded or linked BEFORE the move (test/fixtures/profiles-urls-before-1948.json) is still carried by some page file
#   R2 tabs     Prof > Profiles is Professional | LinkedIn | Portfolio | Developer | CV in build.json and each tab has its page file; Personal embeds the mySocials home
#   R3 urls     Professional=diegonmarcos.com, LinkedIn=mySocials/linkedin.html, Portfolio=cv_web, CV=cv_pdf, and me-links.json declares the same
#   R4 cached   the page cache covers section prof and pers and excludes none of the four tabs
# Usage: test-profiles-1948.sh            real tree + mutation proofs (each break must turn a check red)
#        test-profiles-1948.sh --check DIR  check DIR only, exit 1 on any finding (used by the me-profiles-1948-parity workflow)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
check() { python3 -I - "$1" "$APP/test/fixtures/profiles-urls-before-1948.json" <<'PY'
import json, os, sys
app, fx = sys.argv[1], sys.argv[2]
ui = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]
secs = {s["id"]: s for s in ui["sections"]}
now = set()
for r, _, fs in os.walk(os.path.join(app, "data/ui")):
    for f in fs:
        if not f.endswith(".json"): continue
        def walk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in ("url", "link") and isinstance(v, str): now.add(v)
                    else: walk(v)
            elif isinstance(o, list):
                for v in o: walk(v)
        walk(json.load(open(os.path.join(r, f), encoding="utf-8")))
for u, was in sorted(json.load(open(fx, encoding="utf-8"))["urls"].items()):
    if u not in now: print("R1\t%s (was on %s) is not reachable from any page file any more" % (u, ", ".join(was)))
def flat(o):
    for p in o.get("pages", []) or []:
        yield p; yield from flat(p)
web = next((p for p in flat(secs.get("prof", {})) if p["id"] == "prof-web"), {})
tabs = [p["id"] for p in web.get("pages", [])]
if tabs != ["prof-professional", "prof-linkedin", "prof-portfolio", "prof-developer", "prof-cv"]: print("R2\tProf > Profiles tabs are %r" % tabs)
want = {"prof/prof-web/prof-professional": "https://diegonmarcos.com/", "prof/prof-web/prof-linkedin": "https://diegonmarcos.github.io/mySocials/linkedin.html",
        "prof/prof-web/prof-portfolio": "https://diegonmarcos.github.io/cv_web/", "prof/prof-web/prof-developer": "https://github.com/diegonmarcos", "prof/prof-web/prof-cv": "https://diegonmarcos.github.io/cv_pdf/",
        "pers/pers-profiles/personal": "https://diegonmarcos.github.io/mySocials/"}
table = {}
for e in json.load(open(os.path.join(app, "data/web/me-links.json"), encoding="utf-8"))["entries"]:
    if "path" in e: table["%s/%s" % (e["section"], "/".join(e["path"]))] = e["url"]
for p, u in want.items():
    try: got = json.load(open(os.path.join(app, "data/ui", p + ".json"), encoding="utf-8"))[0].get("url")
    except (OSError, ValueError, IndexError): got = None
    if got != u: print("R3\t%s embeds %r, wanted %s" % (p, got, u))
    if table.get(p) != u: print("R3\tme-links.json declares %r for %s, wanted %s" % (table.get(p), p, u))
c = ui.get("lnktree_cache", {})
if not {"pers", "prof"} <= set(c.get("sections", [])): print("R4\tthe page cache does not cover pers and prof")
for t in tabs + ["personal"]:
    if t in c.get("exclude_pages", []): print("R4\t%s is excluded from the page cache" % t)
PY
}
if [ "${1:-}" = "--check" ]; then out=$(check "$2"); [ -z "$out" ] && { echo "green"; exit 0; }; echo "$out"; exit 1; fi
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
out=$(check "$APP"); if [ -z "$out" ]; then ok "R1-R4 the real tree holds"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data/web" "$T/app/data/web"; cp -r "$APP/data/ui" "$T/app/data/ui"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
mut "the Portfolio tab (cv_web) is dropped" 'import sys,os;os.remove(sys.argv[1]+"/data/ui/prof/prof-web/prof-portfolio.json")'
mut "the Developer tab (GitHub profile) is dropped (#1962)" 'import sys,os;os.remove(sys.argv[1]+"/data/ui/prof/prof-web/prof-developer.json")'
mut "the Developer URL is replaced (#1962)" 'import sys;p=sys.argv[1]+"/data/ui/prof/prof-web/prof-developer.json";s=open(p).read().replace("https://github.com/diegonmarcos","https://example.com/");open(p,"w").write(s)'
mut "the Professional URL is replaced" 'import sys;p=sys.argv[1]+"/data/ui/prof/prof-web/prof-professional.json";s=open(p).read().replace("https://diegonmarcos.com/","https://example.com/");open(p,"w").write(s)'
mut "the CV tab shows cv_web again and cv_pdf is lost" 'import sys;p=sys.argv[1]+"/data/ui/prof/prof-web/prof-cv.json";s=open(p).read().replace("cv_pdf","cv_web");open(p,"w").write(s)'
mut "the old diegonmarcos.com is back on Personal and gone from Prof" 'import sys,os;os.remove(sys.argv[1]+"/data/ui/prof/prof-web/prof-professional.json")'
mut "a tab is renamed away in build.json" 'import sys;p=sys.argv[1]+"/build.json";s=open(p,encoding="utf-8").read().replace("\"id\": \"prof-portfolio\"","\"id\": \"prof-x\"");open(p,"w",encoding="utf-8").write(s)'
mut "the page cache drops section prof" 'import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p,encoding="utf-8"));d["ui"]["lnktree_cache"]["sections"]=["proj"];json.dump(d,open(p,"w",encoding="utf-8"))'
echo "pass=$PASS fail=$FAIL"; [ "$FAIL" -eq 0 ]
