#!/usr/bin/env bash
# Tester (#1937 point 5): OWNER-ORDERED REMOVAL of Prof > More (prof-profiles) and its subpages Professional, Work, Study (CV, Roadmap).
#   P1 gone     none of the six page ids is declared in Prof, in a group, nor has a data/ui/prof/prof-profiles folder; the generator no longer writes them
#   P2 aliases  every id of the removed pages (this layout's and the experiences/socials ones of #1901) resolves, through ui.renamed, to prof/prof-web (Prof > Profiles)
#   P3 kept     every other Prof page is still declared with its page file: Profiles (Professional, LinkedIn, Portfolio, Developer, CV), Leaf.y, Nexus.z, Stark.x, in their two groups
#   P4 lnktree  the Lnktree section's own pages (lt-professional ...) are untouched
# Each check is proven red by a mutation of a copy.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" <<'PY'
import json, os, sys
app = sys.argv[1]; ui = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]
secs = {s["id"]: s for s in ui["sections"]}
def flat(o):
    for p in o.get("pages", []) or []:
        yield p; yield from flat(p)
prof = secs["prof"]; ids = [p["id"] for p in flat(prof)]
REMOVED = ["prof-profiles", "professional", "work", "study", "cv", "roadmap"]
for r in REMOVED:
    if r in ids: print("P1\tthe removed page %s is declared in Prof" % r)
    if any(r in g.get("pages", []) for g in prof.get("groups", [])): print("P1\tthe removed page %s is in a Prof group" % r)
if os.path.isdir(os.path.join(app, "data/ui/prof/prof-profiles")): print("P1\tdata/ui/prof/prof-profiles is back")
gen = open(os.path.join(app, "data/regen-study.py"), encoding="utf-8").read()
if '"prof/prof-profiles' in gen: print("P1\tdata/regen-study.py writes the removed pages again")
ren = ui["renamed"]["pages"]
for old in ["prof/" + r for r in REMOVED] + ["experiences/work", "experiences/study", "experiences/cv", "experiences/roadmap", "socials/socials-professional"]:
    if ren.get(old) != "prof/prof-web": print("P2\t%s does not resolve to prof/prof-web (%r)" % (old, ren.get(old)))
if [p["id"] for p in prof.get("pages", [])] != ["prof-web", "leafy", "nexus", "stark"]: print("P3\tProf pages are not prof-web, leafy, nexus, stark")
if [p["id"] for p in next((p for p in prof["pages"] if p["id"] == "prof-web"), {}).get("pages", [])] != ["prof-professional", "prof-linkedin", "prof-portfolio", "prof-developer", "prof-cv"]: print("P3\tProf > Profiles is not Professional | LinkedIn | Portfolio | Developer | CV")
if {g["id"]: g["pages"] for g in prof.get("groups", [])} != {"profiles": ["prof-web"], "companies": ["leafy", "nexus", "stark"]}: print("P3\tProf groups changed")
for f in ("leafy", "nexus", "stark", "prof-web/prof-professional", "prof-web/prof-linkedin", "prof-web/prof-portfolio", "prof-web/prof-developer", "prof-web/prof-cv"):
    if not os.path.isfile(os.path.join(app, "data/ui/prof", f + ".json")): print("P3\tdata/ui/prof/%s.json is missing" % f)
lt = [p["id"] for p in secs["lnktree"]["pages"]]
if "lt-professional" not in lt or not os.path.isfile(os.path.join(app, "data/ui/lnktree/lt-professional.json")): print("P4\tthe Lnktree page lt-professional is gone")
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "P1-P4 More and its subpages are gone, their ids resolve to Profiles, every other Prof page stays"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data" "$T/app/data"; rm -rf "$T/app/data/__pycache__"
  python3 -c "$2" "$T/app"; r=$(check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
BJ='import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p));U=d["ui"];S={s["id"]:s for s in U["sections"]};'
WR='json.dump(d,open(p,"w"))'
mut "More is declared again" "${BJ}S['prof']['pages'].insert(1,{'id':'prof-profiles','label':'More','icon':'ic_nav_me'});$WR"
mut "Work is declared again inside Profiles" "${BJ}S['prof']['pages'][0]['pages'].append({'id':'work','label':'Work','icon':'ic_nav_work'});$WR"
mut "More is back in its group" "${BJ}S['prof']['groups'][0]['pages'].append('prof-profiles');$WR"
mut "the More folder is back" 'import os,sys;os.makedirs(sys.argv[1]+"/data/ui/prof/prof-profiles");open(sys.argv[1]+"/data/ui/prof/prof-profiles/work.json","w").write("[]")'
mut "an old More id stops resolving" "${BJ}del U['renamed']['pages']['prof/prof-profiles'];$WR"
mut "an old Study id resolves elsewhere" "${BJ}U['renamed']['pages']['experiences/study']='pers/geo';$WR"
mut "Leaf.y is dropped" "${BJ}S['prof']['pages']=[p for p in S['prof']['pages'] if p['id']!='leafy'];$WR"
mut "CV is dropped from Profiles" "${BJ}S['prof']['pages'][0]['pages'].pop();$WR"
mut "a Prof page file is lost" 'import os,sys;os.remove(sys.argv[1]+"/data/ui/prof/stark.json")'
mut "the Lnktree Professional page is dropped" "${BJ}S['lnktree']['pages']=[p for p in S['lnktree']['pages'] if p['id']!='lt-professional'];$WR"
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
