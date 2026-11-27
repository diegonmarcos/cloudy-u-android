#!/usr/bin/env bash
# Tester (#1976): Pers is two rows (Profiles = Media | Health | Astro | DD over Achieves = Geo | Endurance | Personal Growth | Community | By 30 | By 40 | By 50), the old
# container ids resolve through ui.renamed, Media holds the three social pages, Geo keeps every field except the owner-named removals (field parity against
# test/fixtures/geo-before-1976.json), Long Stay and Short Stay are split, the Places table has the columns Continent/Region | Cities | NM | Countries | Days with every value
# derived from MyTrips data (never invented), the visited-countries map is per continent with no global view, MyTrips is bundled whole, and the generated pages match
# their inputs. Every check is proven red by a mutation of a copy.
# Usage: test-geo-1976.sh            real tree green, every mutation red
#        test-geo-1976.sh --check DIR  check DIR only, exit 1 when it is red (CI mutation job)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 -I - "$1" <<'PY'
import json, os, re, subprocess, sys
from datetime import date
app = sys.argv[1]; J = lambda p: json.load(open(os.path.join(app, p), encoding="utf-8")); T = lambda p: open(os.path.join(app, p), encoding="utf-8").read()
def E(c, m): print("%s\t%s" % (c, m))
EM = " "; DASH = "—"
ui = J("build.json")["ui"]; secs = {s["id"]: s for s in ui["sections"]}; pers = secs["pers"]
byid = {p["id"]: p for p in pers["pages"]}
def flat(ps):
    for p in ps or []:
        yield p; yield from flat(p.get("pages"))
# N: nav order, two rows, rename
PROFILES = ["pers-profiles", "health", "astro", "dd"]; ACH = ["geo", "endurance", "growth", "community", "by30", "by40", "by50"]
if [p["id"] for p in pers["pages"]] != PROFILES + ACH: E("N1", "Pers pages are %r" % [p["id"] for p in pers["pages"]])
if pers.get("nav_mode") != "horizontal_two_rows": E("N2", "Pers is not a two-row nav (nav_mode %r)" % pers.get("nav_mode"))
if [(g["label"], g["pages"]) for g in pers.get("groups", [])] != [("Profiles", PROFILES), ("Achieves", ACH)]: E("N3", "rows are %r" % [(g["label"], g["pages"]) for g in pers.get("groups", [])])
if [byid[i]["label"] for i in PROFILES + ACH if i in byid] != ["Media", "Health", "Astro", "DD", "Geo", "Endurance", "Personal Growth", "Community", "By 30", "By 40", "By 50"]: E("N4", "tab labels differ")
if any("Personal Archives" in json.dumps(g) for g in pers.get("groups", [])) or "Personal Archives" in json.dumps(pers["pages"]): E("N5", "Personal Archives is not renamed to Achieves")
ren = ui["renamed"]["pages"]
for old, to in (("pers/pers-instagram", "pers/ig0"), ("pers/pers-social", "pers/face0"), ("pers/achieves", "pers/geo"), ("experiences/achieves", "pers/geo")):
    if ren.get(old) != to: E("R1", "ui.renamed.pages[%s] is %r, want %s" % (old, ren.get(old), to))
live = {s["id"]: {p["id"] for p in flat(s.get("pages"))} for s in ui["sections"]}
for old, to in ren.items():
    s, _, p = to.partition("/")
    if s not in live or (p and p not in live[s]): E("R2", "ui.renamed.pages[%s] names %s, which is not a live page" % (old, to))
# M: Media holds the three social pages, every old profile page kept with its file
media = byid.get("pers-profiles", {}); mp = [p["id"] for p in media.get("pages", [])]
if mp != ["personal", "ig0", "ig1", "ig2", "face0", "pinterest", "youtube", "tidal", "linkedin", "orkut"]: E("M1", "Media tabs are %r, want ONE flat strip Personal | ig0 | ig1 | ig2 | face0 | Pinterest | YouTube | Tidal | LinkedIn | Orkut" % mp)
if any(p.get("pages") for p in media.get("pages", [])): E("M2", "a Media page is nested again (Instagram / Social grouping)")
for pid in ("personal", "ig0", "ig1", "ig2", "face0", "pinterest", "youtube", "tidal", "linkedin", "orkut"):
    f = "data/ui/pers/pers-profiles/%s.json" % pid
    try: b = J(f)
    except Exception: E("M4", "page %s has no file %s" % (pid, f)); continue
    if not b or b[0].get("id") != "web" or not str(b[0].get("url", "")).startswith(("https://", "mysocials/")): E("M5", "%s does not embed its page" % pid)
# F: field parity of Geo minus the owner-named removals
old = J("test/fixtures/geo-before-1976.json"); new = J("data/ui/pers/geo.json")
removed = {0, 2, 3, 4, 6}   # the Places ledger, @brief, the Brief note, the quote, Languages
if old[0].get("kind") != "ledger" or old[2].get("title") != "@brief" or old[6].get("title") != "Languages": E("F0", "the frozen Geo fixture is not the pre-#1976 page")
if any(b.get("kind") == "ledger" for b in new): E("F1", "the Places ledger view is still in Geo")
if any(b.get("title") in ("@brief", "Languages") or b.get("kind") == "quote" for b in new): E("F1", "a removed Brief or Languages block is still in Geo")
def same(a, b): return all(k in b and b[k] == v for k, v in a.items())
for i, o in enumerate(old):
    if i in removed: continue
    if o.get("kind") == "table":
        n = next((b for b in new if b.get("kind") == "table" and b.get("title") == o["title"]), None)
        if n is None: E("F2", "the table %r is gone" % o["title"]); continue
        if n.get("link") != o.get("link"): E("F2", "the table link changed")
        want = [r for r in n["rows"] if r["cells"][0] != "TOTAL"]
        if len(want) != len(o["rows"]): E("F2", "the Places table has %d rows, the page before had %d" % (len(want), len(o["rows"]))); continue
        for r0, r1 in zip(o["rows"], want):
            m = re.match(r"^\s*(\d+)\s*(?:\((\d+)\))?\s*$", r0["cells"][1])
            lab = r1["cells"][0].lstrip(EM)
            if lab != r0["cells"][0]: E("F3", "region %r became %r" % (r0["cells"][0], lab))
            if r1["cells"][1] != m.group(1): E("F3", "%s: cities %r became %r" % (lab, m.group(1), r1["cells"][1]))
            if r1["cells"][2] != (m.group(2) if m.group(2) is not None else DASH): E("F3", "%s: NM %r is not the table's %r" % (lab, r1["cells"][2], m.group(2)))
            if r1["cells"][3] != r0["cells"][2]: E("F3", "%s: countries changed" % lab)
            if bool(r1.get("group")) != bool(r0.get("group")): E("F3", "%s: group flag changed" % lab)
    elif not any(same(o, b) for b in new): E("F2", "block %d (%s %r) of the page before is not in Geo with every field" % (i, o.get("kind"), o.get("title")))
mig = pers.get("migration", {})
if mig.get("store") != "experiences_user" or mig.get("by_value") != {"\U0001f310 Travel": "geo"} or mig.get("default") != "endurance" or mig.get("rename") != {"geo": {"place": "country"}} or mig.get("legacy") != {"done": "_done", "todo": "_todo"}:
    E("F4", "the experiences_user migration (records are kept) was changed")
# S: Long Stay / Short Stay split
idx = lambda pred: next((i for i, b in enumerate(new) if pred(b)), -1)
iL = idx(lambda b: b.get("kind") == "section_title" and b.get("title") == "Long Stay"); iLb = idx(lambda b: b.get("title") == "Long Stays"); iLt = idx(lambda b: b.get("id") == "geo-long-stay-cities")
iS = idx(lambda b: b.get("kind") == "section_title" and b.get("title") == "Short Stay"); iSb = idx(lambda b: b.get("title") == "Short Stays"); iSt = idx(lambda b: b.get("id") == "geo-places")
if min(iL, iLb, iLt, iS, iSb, iSt) < 0 or not (iL < iLb < iLt < iS < iSb < iSt): E("S1", "Geo is not Long Stay (brief, cities with time) then Short Stay (brief, Places table): %r" % [iL, iLb, iLt, iS, iSb, iSt])
# derived data
td = J("data/geo/travel-data.json")["trips"]; cfg = J("data/geo/geo-config.json")
import unicodedata
nm = lambda s: "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower().strip()
dd = lambda t: (date.fromisoformat(t["dateOut"]) - date.fromisoformat(t["dateIn"])).days
city = {}
for t in td: city[(nm(t["city"]), nm(t["country"]))] = city.get((nm(t["city"]), nm(t["country"])), 0) + dd(t)
cont = {}
for t in td: cont[t["continent"]] = cont.get(t["continent"], 0) + dd(t)
if iLt >= 0:
    L = new[iLt]
    if L.get("columns") != ["City", "Country", "Time"]: E("S2", "Long Stay columns are %r" % L.get("columns"))
    rows = {r["cells"][0]: r["cells"] for r in L["rows"]}
    for c in cfg["long_stay_cities"]:
        r = rows.get(c["city"])
        if r is None: E("S3", "the long-stay city %s is not listed" % c["city"]); continue
        d = city.get((nm(c.get("match", c["city"])), nm(c["country"])))
        if d is None and r[2] != DASH: E("S4", "%s has no MyTrips trip, its time must be a dash, not %r" % (c["city"], r[2]))
        if d is not None and not r[2].startswith(format(d, ",") + " days"): E("S4", "%s: time %r is not the %d days of MyTrips" % (c["city"], r[2], d))
# P: Places columns and values
if iSt >= 0:
    P = new[iSt]
    if P.get("columns") != ["Continent / Region", "Cities", "NM", "Countries", "Days"]: E("P1", "Places columns are %r" % P.get("columns"))
    for r in P["rows"]:
        c = r["cells"]
        if len(c) != 5: E("P2", "row %r has %d cells" % (c[0], len(c))); continue
        if not r.get("group") and not c[0].startswith(EM): E("P3", "region %r is not indented under its continent" % c[0])
        if r.get("group") and c[0] != "TOTAL":
            conts = cfg["places_group_continents"].get(c[0])
            if conts is None or c[4] != str(sum(cont.get(x, 0) for x in conts)): E("P4", "%s: Days %r is not the MyTrips sum for %r" % (c[0], c[4], conts))
        if not r.get("group") and (c[2] != DASH or c[4] != DASH): E("P5", "region %r shows an NM or Days value nothing in the data gives (invented)" % c[0])
        if c[0] == "TOTAL" and c[4] != str(sum(cont.values())): E("P4", "TOTAL days %r is not %d" % (c[4], sum(cont.values())))
# C: the map, per continent, no global view
H = T("data/web/geo-map.html"); m = re.search(r'<script id="d" type="application/json">(.*?)</script>', H, re.S)
try: MD = json.loads(m.group(1).replace("\\u003c", "<"))
except Exception as e: MD = {}; E("C0", "geo-map.html data unreadable (%s)" % e)
if MD:
    CONT = ["Europe", "Asia", "Africa", "North America", "South America", "Oceania"]
    if MD["order"] != CONT: E("C1", "map continents are %r" % MD["order"])
    if any(c.lower() in ("world", "global", "all", "globe", "earth") for c in MD["order"]) or re.search(r'data-continent="(world|global|all)"', H, re.I): E("C2", "the map has a global view")
    if MD["default"] not in MD["order"] or set(MD["frames"]) != set(CONT): E("C3", "default or frames do not cover the continents")
    vis = {}
    for t in td: vis.setdefault(t["countryISO"], t["continent"])
    if {k: v["continent"] for k, v in MD["visited"].items()} != vis: E("C4", "the painted countries differ from the MyTrips visited countries")
    if any(v["continent"] not in CONT for v in MD["visited"].values()): E("C5", "a visited country belongs to no map continent")
    if 'id="sw"' not in H or "data-continent" in H and "dataset.continent" not in H: E("C6", "no continent switcher")
mt = T("data/web/geo-mytrips.html")
for need in ("'Home'", "'Atlas'", "'Collections'", "'Stats'", "'Timeline'", "Cultural Regions", "NomadMania Regions", "Extended Stays", "Hemisphere Distribution", "Geographic Extremes", "Date In"):
    if need not in mt: E("Y1", "MyTrips page lacks %s" % need)
m2 = re.search(r'<script id="d" type="application/json">(.*?)</script>', mt, re.S)
try:
    YD = json.loads(m2.group(1).replace("\\u003c", "<"))
    if len(YD["trips"]) != len(td) or [t[4] for t in YD["trips"]] != [t["city"] for t in td]: E("Y2", "MyTrips trips differ from the data")
    if len(YD["themes"]) < 16: E("Y3", "MyTrips collections missing")
except Exception as e: E("Y2", "geo-mytrips.html data unreadable (%s)" % e)
# W: web_asset blocks and renderer
for b in new:
    if b.get("kind") == "web_asset" and not os.path.exists(os.path.join(app, "data/web", b.get("url", "x"))): E("W1", "web_asset %s is missing" % b.get("url"))
if [b.get("url") for b in new if b.get("kind") == "web_asset"] != ["geo-map.html", "geo-mytrips.html"] or iSt < 0 or idx(lambda b: b.get("url") == "geo-map.html") < iSt: E("W2", "Geo does not end with the map then MyTrips")
if '"web_asset" -> WebAssetBlock' not in T("app/src/main/java/com/diegonmarcos/cloudme/StackScreen.kt"): E("W3", "StackScreen does not render web_asset")
# G: the generated outputs match their inputs
r = subprocess.run([sys.executable, os.path.join(app, "data/regen-geo-1976.py"), "--check"], capture_output=True, text=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
if r.returncode != 0: E("G1", (r.stderr or r.stdout).strip()[:200])
sot = os.environ.get("MYTRIPS_DIR") or os.path.abspath(os.path.join(app, "..", "..", "..", "cloud-me-pub-shared", "b_MyData", "mymaps-mytrips"))
src = os.path.join(sot, "public", "travel-data.json")
if os.path.exists(src) and open(src, "rb").read() != open(os.path.join(app, "data/geo/travel-data.json"), "rb").read() and not os.environ.get("MUTANT"): E("G2", "data/geo/travel-data.json differs from MyTrips (run data/regen-geo-1976.py --sync)")
PY
}
if [ "${1:-}" = --check ]; then o=$(MUTANT=1 check "${2:-$APP}"); [ -z "$o" ] && exit 0; echo "$o"; exit 1; fi   # CI mutation job: exit 1 = the tree is red
out=$(check "$APP"); if [ -z "$out" ]; then ok "N/R/M/F/S/P/C/Y/W/G: nav rows, rename, Media, Geo parity, Long/Short split, Places columns, per-continent map, MyTrips, generated pages"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/app"; mkdir -p "$T/app/data" "$T/app/test" "$T/app/app/src/main/java/com/diegonmarcos/cloudme"; cp "$APP/build.json" "$T/app/"; cp -r "$APP/data/web" "$APP/data/ui" "$APP/data/geo" "$T/app/data/"; cp "$APP/data/regen-geo-1976.py" "$T/app/data/"
  cp -r "$APP/test/fixtures" "$T/app/test/"; cp "$APP/app/src/main/java/com/diegonmarcos/cloudme/StackScreen.kt" "$T/app/app/src/main/java/com/diegonmarcos/cloudme/"
  MUTANT=1 python3 -c "$2" "$T/app"; r=$(MUTANT=1 check "$T/app"); if [ -n "$r" ]; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
BJ='import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p,encoding="utf-8"));U=d["ui"];P=[s for s in U["sections"] if s["id"]=="pers"][0];'
WB='json.dump(d,open(p,"w",encoding="utf-8"),ensure_ascii=False)'
GJ='import json,sys;p=sys.argv[1]+"/data/ui/pers/geo.json";g=json.load(open(p,encoding="utf-8"));'
WG='json.dump(g,open(p,"w",encoding="utf-8"),ensure_ascii=False)'
SUB() { echo "import sys;p=sys.argv[1]+\"/$1\";s=open(p,encoding='utf-8').read();assert '''$2''' in s;open(p,'w',encoding='utf-8').write(s.replace('''$2''','''$3''',1))"; }
mut "Media is not first in row 1" "${BJ}P['groups'][0]['pages'].append(P['groups'][0]['pages'].pop(0));$WB"
mut "the Achieves row is called Personal Archives again" "${BJ}P['groups'][1]['label']='Personal Archives';$WB"
mut "Pers is one row again" "${BJ}P['nav_mode']='horizontal_one_row';$WB"
mut "By 50 left the Achieves row" "${BJ}P['groups'][1]['pages'].pop();$WB"
mut "the old Achieves id no longer resolves" "${BJ}U['renamed']['pages'].pop('pers/achieves');$WB"
mut "the old Profiles id resolves to a page that does not exist" "${BJ}U['renamed']['pages']['pers/pers-profiles']='pers/pers-gone';$WB"
mut "Media loses its last page (Orkut)" "${BJ}P['pages'][0]['pages'].pop();$WB"
mut "Media is re-nested: ig1 and ig2 sit under ig0" "${BJ}m=P['pages'][0]['pages'];m[1].setdefault('pages',[]).extend([m.pop(2),m.pop(2)]);$WB"
mut "Media order changed: Personal is not first" "${BJ}m=P['pages'][0]['pages'];m.append(m.pop(0));$WB"
mut "the SuperApp target pers-profiles is no longer a declared page" "${BJ}P['pages'][0]['id']='pers-media';P['groups'][0]['pages'][0]='pers-media';$WB"
mut "the Instagram alias is dropped" "${BJ}U['renamed']['pages'].pop('pers/pers-instagram');$WB"
mut "a Media page file is gone (Pinterest)" 'import os,sys;os.remove(sys.argv[1]+"/data/ui/pers/pers-profiles/pinterest.json")'
mut "the Places ledger view is back" "${GJ}g.insert(0,{'kind':'ledger','title':'Places'});$WG"
mut "the Languages block is back" "${GJ}g.append({'kind':'bullets','title':'Languages','items':[]});$WG"
mut "a kept block (Short Stays) is altered" "$(SUB data/ui/pers/geo.json 'On short stays traveled over 180+ cities' 'On short stays traveled over 18 cities')"
mut "the NomadMania link is dropped" "${GJ}[b.pop('link') for b in g if b.get('id')=='geo-places'];$WG"
mut "a Places city count is altered" "${GJ}[r['cells'].__setitem__(1,'101') for b in g if b.get('id')=='geo-places' for r in b['rows'] if r['cells'][0]=='EU'];$WG"
mut "a Places NM value of the table is altered" "${GJ}[r['cells'].__setitem__(2,'71') for b in g if b.get('id')=='geo-places' for r in b['rows'] if r['cells'][0]=='EU'];$WG"
mut "the migration of the records changed" "${BJ}P['migration']['default']='trips';$WB"
mut "Short Stay comes before Long Stay" "${GJ}a=[i for i,b in enumerate(g) if b.get('title')=='Long Stay'][0];b=[i for i,b in enumerate(g) if b.get('title')=='Short Stay'][0];g[a],g[b]=g[b],g[a];$WG"
mut "the Long Stay cities table is gone" "${GJ}g=[b for b in g if b.get('id')!='geo-long-stay-cities'];$WG"
mut "a Long Stay time is altered (Berlin)" "${GJ}[r['cells'].__setitem__(2,'1 days') for b in g if b.get('id')=='geo-long-stay-cities' for r in b['rows'] if r['cells'][0]=='Berlin'];$WG"
mut "a city without a MyTrips trip gets an invented time (Casablanca)" "${GJ}[r['cells'].__setitem__(2,'90 days') for b in g if b.get('id')=='geo-long-stay-cities' for r in b['rows'] if r['cells'][0]=='Casablanca'];$WG"
mut "the NM column is dropped from Places" "${GJ}[b.__setitem__('columns',['Continent / Region','Cities','Countries','Days']) for b in g if b.get('id')=='geo-places'];$WG"
mut "the Days column is dropped from Places" "${GJ}[b.__setitem__('columns',['Continent / Region','Cities','NM','Countries']) for b in g if b.get('id')=='geo-places'];$WG"
mut "a region is not indented" "${GJ}[r['cells'].__setitem__(0,r['cells'][0].lstrip(' ')) for b in g if b.get('id')=='geo-places' for r in b['rows'] if not r.get('group')];$WG"
mut "a continent Days value is invented" "${GJ}[r['cells'].__setitem__(4,'999') for b in g if b.get('id')=='geo-places' for r in b['rows'] if r['cells'][0]=='ASIA'];$WG"
mut "a region gets an invented NM value" "${GJ}[r['cells'].__setitem__(2,'5') for b in g if b.get('id')=='geo-places' for r in b['rows'] if not r.get('group')][:1];$WG"
mut "the map gets a global view" "$(SUB data/web/geo-map.html '"order":["Europe"' '"order":["World","Europe"')"
mut "the map loses a continent" "$(SUB data/web/geo-map.html '"order":["Europe","Asia","Africa","North America","South America","Oceania"]' '"order":["Europe","Asia","Africa","North America","South America"]')"
mut "the map paints a country MyTrips never visited" "$(SUB data/web/geo-map.html '"visited":{' '"visited":{"JP":{"name":"Japan","flag":"x","continent":"Asia","days":1,"lat":1,"lng":1},')"
mut "MyTrips loses its Timeline view" "$(SUB data/web/geo-mytrips.html "['timeline','Timeline']" "['timeline','Time']")"
mut "MyTrips loses its Stats extremes" "$(SUB data/web/geo-mytrips.html 'Geographic Extremes' 'Extremes')"
mut "the map page is out of sync with the data" "$(SUB data/web/geo-map.html 'No visit recorded in' 'Nothing in')"
mut "the MyTrips data copy drifts from the page" "$(SUB data/geo/travel-data.json '"city": "Sao Caetano do Sul"' '"city": "Sao Caetano"')"
mut "StackScreen cannot draw web_asset" "$(SUB app/src/main/java/com/diegonmarcos/cloudme/StackScreen.kt '"web_asset" -> WebAssetBlock' '"web_assetx" -> WebAssetBlock')"
mut "Geo does not end with the map and MyTrips" "${GJ}g=[b for b in g if b.get('id')!='geo-mytrips'];$WG"
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
