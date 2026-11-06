#!/usr/bin/env bash
# test-finterm-cb: #1539 the Central Bank (CB) page declarations and data, statically (no build, no network, no device).
#   C1  data/cb-indicators.json: SIMPLE = 5 groups with the owner's items; FULL = 7 groups of 25, 16, 16, 22, 15, 11 and 9 items; every full item has id, name, unit,
#       frequency and a `sources` list; every simple ref resolves; every rule names a declared source; the DSGE items are model-only
#   C2  data/cb-countries.json: the DM and BRICS EM lists, South Korea / Australia / Canada / Indonesia once each, euro-area members never aggregated twice, both weight options
#   C3  data/cb-snapshot.json: every series carries a source, a release and a vintage date; every value is a finite number; every series is declared by a rule of its indicator;
#       no value is typed for a country with no declared rule (the n/a guard); the DSGE latent variables appear only from a declared source
#   C4  the weights: the snapshot holds PPP and market-FX GDP for every aggregated country and the euro area weight is the sum of its members
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def load(p):
    with open(os.path.join(app, p), encoding="utf-8") as f: return json.load(f)
ind, cty = load("data/cb-indicators.json"), load("data/cb-countries.json")
# C1
SIMPLE = [
 ("Real Economy", ["GDP & output gap", "Private Consumption", "Private Investment", "Residential Investment", "Government Spending"]),
 ("Labor", ["Unemployment", "Employment/Hours", "Nominal Wages"]),
 ("Prices", ["Headline", "Core", "Import/Export prices", "Commodity/Oil"]),
 ("Financial & Monetary", ["Policy rate", "10Y yield", "Lending rates", "Asset prices & wealth", "Credit volumes"]),
 ("International", ["FX", "Net exports", "Foreign demand", "Foreign rates"]),
]
sg = ind["simple"]["groups"]
if [(g["label"], [i["name"] for i in g["items"]]) for g in sg] != SIMPLE: bad.append("C1 the simple list is %s" % [(g["label"], [i["name"] for i in g["items"]]) for g in sg])
FULL = [("Real Output and Expenditure", 25), ("Labor Market", 16), ("Prices and Inflation", 16), ("Financial Markets and Interest Rates", 22), ("Income, Wealth, and Fiscal Policy", 15), ("International and Open Economy", 11), ("Latent Variables and Structural Shocks (DSGE)", 9)]
fg = ind["full"]["groups"]
if [(g["label"], len(g["items"])) for g in fg] != FULL: bad.append("C1 the full list group counts are %s" % [(g["label"], len(g["items"])) for g in fg])
DSGE = ["Total Factor Productivity (TFP)", "Investment-Specific Technology", "Preference (Discount-Factor) Shock", "Wage Markup Shock", "Price Markup Shock", "Capital Utilization", "Marginal Cost", "Tobin's Q", "External Finance Premium"]
if [i["name"] for i in fg[-1]["items"]] != DSGE: bad.append("C1 the DSGE items are %s" % [i["name"] for i in fg[-1]["items"]])
for need in ("Real GDP", "Nominal GDP", "Potential GDP", "Output Gap", "Capacity Utilization"):
    if not any(i["name"] == need for i in fg[0]["items"]): bad.append("C1 the real output group lacks %s" % need)
ids, srcs = {}, set(ind["sources"])
for g in fg:
    for i in g["items"]:
        for k in ("id", "name", "unit", "frequency", "sources"):
            if k not in i: bad.append("C1 %s/%s has no %s" % (g["id"], i.get("id"), k))
        if i["id"] in ids: bad.append("C1 duplicate indicator id %s" % i["id"])
        ids[i["id"]] = (g["id"], i)
        if g["id"] == "dsge" and not i.get("model_only"): bad.append("C1 DSGE %s is not marked model_only" % i["id"])
        for r in i.get("sources", []):
            if r.get("src") not in srcs: bad.append("C1 %s names the undeclared source %s" % (i["id"], r.get("src")))
            if not r.get("series"): bad.append("C1 %s has a rule with no series" % i["id"])
            if r.get("countries") != "*" and not isinstance(r.get("countries"), list): bad.append("C1 %s: countries is %r" % (i["id"], r.get("countries")))
for g in sg:
    for it in g["items"]:
        if not it.get("refs"): bad.append("C1 simple item %s has no refs" % it["id"])
        for r in it.get("refs", []):
            if r not in ids: bad.append("C1 simple item %s refs the unknown indicator %s" % (it["id"], r))
# C2
cs = cty["countries"]
cid = [c["id"] for c in cs]
if len(set(cid)) != len(cid): bad.append("C2 a country is listed twice: %s" % sorted({x for x in cid if cid.count(x) > 1}))
DM = ["US", "EA", "DE", "FR", "IT", "ES", "GB", "JP", "CH", "CA", "AU", "SE", "NO", "NZ", "KR"]
EM = ["BR", "RU", "IN", "CN", "ZA", "EG", "ET", "IR", "AE", "ID"]
if [c["id"] for c in cs if c["group"] == "DM"] != DM: bad.append("C2 the DM list is %s" % [c["id"] for c in cs if c["group"] == "DM"])
if [c["id"] for c in cs if c["group"] == "EM"] != EM: bad.append("C2 the EM list is %s" % [c["id"] for c in cs if c["group"] == "EM"])
for x in ("KR", "AU", "CA", "ID"):
    if not next((c for c in cs if c["id"] == x), {}).get("explicit"): bad.append("C2 %s is not marked explicit" % x)
for c in cs:
    if c["id"] in ("DE", "FR", "IT", "ES") and c["aggregate"]: bad.append("C2 %s is a euro-area member but is aggregated again" % c["id"])
    if c["id"] not in ("DE", "FR", "IT", "ES") and not c["aggregate"]: bad.append("C2 %s is not aggregated" % c["id"])
    for k in ("iso3", "wb", "bis", "name"):
        if not c.get(k): bad.append("C2 %s has no %s" % (c["id"], k))
ea = next(c for c in cs if c["id"] == "EA")
if not all(m in ea.get("weight_members", []) for m in ("DEU", "FRA", "ITA", "ESP")): bad.append("C2 the euro area weight members lack DE, FR, IT or ES")
w = cty["weights"]
if [o["id"] for o in w["options"]] != ["ppp", "fx"] or w["default"] not in ("ppp", "fx"): bad.append("C2 the weight options are %s" % w)
# C3 / C4 (the snapshot, when present)
sp = os.path.join(app, "data/cb-snapshot.json")
if os.path.exists(sp):
    snap = load("data/cb-snapshot.json")
    num = lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and v == v and abs(v) != float("inf")
    for c, cd in snap["countries"].items():
        if c not in cid: bad.append("C3 the snapshot has the undeclared country %s" % c)
        for k, s in cd["series"].items():
            if k not in ids: bad.append("C3 %s/%s is not a declared indicator" % (c, k)); continue
            for f in ("source", "release", "vintage", "rule"):
                if not s.get(f): bad.append("C3 %s/%s has no %s" % (c, k, f))
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", s.get("vintage", "")): bad.append("C3 %s/%s vintage %r is not a date" % (c, k, s.get("vintage")))
            rules = ids[k][1]["sources"]
            if not (0 <= s.get("rule", -1) < len(rules)): bad.append("C3 %s/%s names the rule %s, which the indicator does not declare" % (c, k, s.get("rule"))); continue
            r = rules[s["rule"]]
            if r["countries"] != "*" and c not in r["countries"]: bad.append("C3 %s/%s: rule %d is not declared for this country" % (c, k, s["rule"]))
            if s["source"] != ind["sources"][r["src"]]["label"]: bad.append("C3 %s/%s source %r is not its rule's source" % (c, k, s["source"]))
            for part in ("hist", "fc"):
                for y, v in s.get(part, {}).items():
                    if not num(v) or not re.fullmatch(r"\d{4}", y): bad.append("C3 %s/%s/%s %s=%r is not a number" % (c, k, part, y, v))
            if not s.get("hist") and not s.get("fc"): bad.append("C3 %s/%s holds no value (a missing value is absent, never typed)" % (c, k))
            for cp in s.get("cb_projection", []) if isinstance(s.get("cb_projection"), list) else [s["cb_projection"]] if s.get("cb_projection") else []:
                for f in ("source", "release", "vintage", "label", "fc"):
                    if not cp.get(f): bad.append("C3 %s/%s cb_projection has no %s" % (c, k, f))
                if not all(num(v) for v in cp.get("fc", {}).values()): bad.append("C3 %s/%s cb_projection holds a non-number" % (c, k))
    txt = json.dumps(snap)
    if re.search(r"(?i)\b(nan|infinity|placeholder|lorem|todo|tbd)\b", txt): bad.append("C3 the snapshot holds a placeholder or non-number token")
    for c in cid:
        if c not in snap["countries"]: bad.append("C3 the snapshot has no country %s" % c)
    for c in ("US", "EA", "GB", "JP"):
        if "real_gdp" not in snap["countries"].get(c, {}).get("series", {}): bad.append("C3 %s has no real GDP" % c)
    # C4
    for opt in ("ppp", "fx"):
        for c in cs:
            if c["aggregate"]:
                ww = snap["weights"][opt].get(c["id"], {})
                if not ww or not all(num(v) and v > 0 for v in ww.values()): bad.append("C4 %s has no %s weight" % (c["id"], opt))
        if not snap["weights"][opt]["EA"]: bad.append("C4 the euro area has no %s weight" % opt)
    for opt in ("ppp", "fx"):
        e = snap["weights"][opt].get("EA", {})
        for y, v in e.items():
            tot = sum(snap["weights"][opt + "_members"].get(m, {}).get(y, 0) for m in ea["weight_members"])
            if abs(tot - v) > 1e-6 * max(1, v): bad.append("C4 the euro area %s weight %s is %s, the members sum to %s" % (opt, y, v, tot))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] CB page rules (#1539)"
out="$(check "$APP")" && ok "C1-C4 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <python statements on the scratch copy, cwd = the copy> <needle>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
J="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=1)"
mutant "a simple group is dropped"        "$J;d=L('data/cb-indicators.json');d['simple']['groups'].pop();D('data/cb-indicators.json',d)" "C1 the simple list"
mutant "a simple item is renamed"         "$J;d=L('data/cb-indicators.json');d['simple']['groups'][0]['items'][0]['name']='GDP';D('data/cb-indicators.json',d)" "C1 the simple list"
mutant "a full item is dropped"           "$J;d=L('data/cb-indicators.json');d['full']['groups'][3]['items'].pop();D('data/cb-indicators.json',d)" "C1 the full list group counts"
mutant "a DSGE item is renamed"           "$J;d=L('data/cb-indicators.json');d['full']['groups'][6]['items'][7]['name']='Q';D('data/cb-indicators.json',d)" "C1 the DSGE items"
mutant "an item loses its unit"           "$J;d=L('data/cb-indicators.json');del d['full']['groups'][0]['items'][0]['unit'];D('data/cb-indicators.json',d)" "has no unit"
mutant "a rule names an unknown source"   "$J;d=L('data/cb-indicators.json');d['full']['groups'][0]['items'][0]['sources'][0]['src']='bloomberg';D('data/cb-indicators.json',d)" "undeclared source"
mutant "a simple ref is dangling"         "$J;d=L('data/cb-indicators.json');d['simple']['groups'][0]['items'][0]['refs'][0]='nope';D('data/cb-indicators.json',d)" "unknown indicator"
mutant "a DSGE item is not model-only"    "$J;d=L('data/cb-indicators.json');del d['full']['groups'][6]['items'][0]['model_only'];D('data/cb-indicators.json',d)" "not marked model_only"
mutant "a country is listed twice"        "$J;d=L('data/cb-countries.json');d['countries'].append(dict(d['countries'][0]));D('data/cb-countries.json',d)" "listed twice"
mutant "Germany is aggregated again"      "$J;d=L('data/cb-countries.json');d['countries'][2]['aggregate']=True;D('data/cb-countries.json',d)" "aggregated again"
mutant "Indonesia loses its explicit mark" "$J;d=L('data/cb-countries.json');[c.pop('explicit') for c in d['countries'] if c['id']=='ID'];D('data/cb-countries.json',d)" "ID is not marked explicit"
mutant "a weight option is dropped"       "$J;d=L('data/cb-countries.json');d['weights']['options'].pop();D('data/cb-countries.json',d)" "weight options"
if [ -f "$APP/data/cb-snapshot.json" ]; then
mutant "a value loses its vintage"        "$J;d=L('data/cb-snapshot.json');s=d['countries']['US']['series']['real_gdp'];del s['vintage'];D('data/cb-snapshot.json',d)" "US/real_gdp has no vintage"
mutant "a value loses its source"         "$J;d=L('data/cb-snapshot.json');s=d['countries']['US']['series']['real_gdp'];s['source']='';D('data/cb-snapshot.json',d)" "US/real_gdp has no source"
mutant "a value is a string"              "$J;d=L('data/cb-snapshot.json');s=d['countries']['US']['series']['real_gdp'];s['hist']['2020']='n/a';D('data/cb-snapshot.json',d)" "is not a number"
mutant "a value is typed for a country with no rule" "$J;d=L('data/cb-snapshot.json');d['countries']['ID']['series']['federal_spending']=dict(d['countries']['US']['series']['real_gdp']);D('data/cb-snapshot.json',d)" "ID/federal_spending"
mutant "a DSGE value appears with no source" "$J;d=L('data/cb-snapshot.json');d['countries']['US']['series']['tobins_q']=dict(d['countries']['US']['series']['real_gdp']);D('data/cb-snapshot.json',d)" "US/tobins_q"
mutant "a placeholder token appears"      "$J;d=L('data/cb-snapshot.json');d['countries']['US']['series']['real_gdp']['release']='TBD';D('data/cb-snapshot.json',d)" "placeholder"
mutant "the euro area weight is wrong"    "$J;d=L('data/cb-snapshot.json');d['weights']['ppp']['EA']['2026']*=2;D('data/cb-snapshot.json',d)" "the euro area ppp weight"
mutant "a weight is missing"              "$J;d=L('data/cb-snapshot.json');d['weights']['fx'].pop('JP');D('data/cb-snapshot.json',d)" "C4 JP has no fx weight"
fi
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
