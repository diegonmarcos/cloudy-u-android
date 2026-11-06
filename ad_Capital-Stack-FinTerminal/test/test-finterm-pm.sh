#!/usr/bin/env bash
# test-finterm-pm: #1478 / #1539 the PM layout (Home | Portfolios | Performance), statically (no build, no network, no device).
#   M1  the PM sub-pages are exactly Home, Portfolios, Performance, each backed by a `pm_table` stack entry whose table exists in data/pm.json; the Commodities page is gone and its links (and the old Vol / Profiles links) redirect
#   M2  Vol: the owner's columns; one row per PM portfolio dashboard (A2 dropped); allocations add up and match the dashboard; Swap No for EM portfolios, blank for DM only;
#       rows follow the four risk levels in order, level 3 by Vol (B1, D1, C1); the filter tabs are All | DM·Low | DM·Middle | DM+EM·Middle-High | DM+EM·High
#   M3  Portfolios (two tables: DM vs EM, US Cyclicals; columns Portfolio | Vol | Top 3 | See all) and Performance (the windows Today, Last 7d, Last 30d, MTD, YTD, Last 1y, Last 3y CAGR; monthly rebalancing declared; the free history source declared;
#       every portfolio of both tables baked with an as-of date or an n/a reason): all declared in data/pm.json
#   M4  Profiles: the 8 cycle profiles, each with its phases and rows of the table's width
#   M5  Kotlin names none of it: PmPage.kt / PmTables.kt hold no declared name, no sp literal, no type-size override, and draw FinMatrix
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
def read(p):
    with open(os.path.join(app, p), encoding="utf-8") as f: return f.read()
bj, dj, pm = (json.loads(read(p)) for p in ("build.json", "data/dashboards.json", "data/pm.json"))
ui = bj["ui"]; dash = {d["id"]: d for d in dj["dashboards"]}
kt = "app/src/main/java/com/diegonmarcos/capitalfinterm/"
# M1
sec = next(s for s in ui["sections"] if s["id"] == "pm")
if [(p["id"], p["label"]) for p in sec["pages"]] != [("pm-home", "Home"), ("pm-portfolios", "Portfolios"), ("pm-performance", "Performance")]:
    bad.append("M1 the PM sub-pages are %s" % [p["label"] for p in sec["pages"]])
tabs = [e for e in ui["stacks"]["pm"] if e.get("kind") == "pm_table"]
if [(e.get("page"), e.get("table")) for e in tabs] != [("pm-portfolios", "portfolios"), ("pm-performance", "performance")]: bad.append("M1 the PM table entries are %s" % [(e.get("page"), e.get("table")) for e in tabs])
for e in tabs:
    if e.get("table") not in pm: bad.append("M1 data/pm.json has no table %s" % e.get("table"))
for e in ui["stacks"]["pm"]:
    if e.get("kind") == "dashboard" and not e.get("detail"): bad.append("M1 PM dashboard %s is not a detail page (a Vol row opens it)" % e.get("dashboard"))
if "commodities" in pm or any(p.get("id") == "pm-commodities" for p in sec["pages"]): bad.append("M1 the PM Commodities table or page is still declared")
for old, tab, to in (("pm-commodities", "eq", "commodity-prices"), ("pm_commodities", "eq", "commodity-prices"), ("pm-vol", "pm", "pm-portfolios"), ("pm-profiles", "pm", "pm-portfolios")):
    if not any(r.get("from") == old and r.get("tab") == tab and r.get("to") == to for r in ui.get("redirects", [])): bad.append("M1 the legacy %s link does not redirect to %s/%s" % (old, tab, to))
# M2
vol = pm["vol"]
if vol["columns"] != ["Portfolio", "DM", "DM\nBonds", "DM\nEquity", "EM", "EM\nBonds", "EM\nEquity", "Swap", "Vol"]: bad.append("M2 Vol columns are %s" % vol["columns"])
want = {d for d in dash if dash[d]["tab"] == "pm" and d != "portfolio_risk"}
got = [r["dashboard"] for r in vol["rows"]]
if "pm_a2_low" in dash or "pm_a2_low" in got: bad.append("M2 the duplicate A2 is back (it is identical to 70/30)")
if set(got) != want or len(got) != len(set(got)): bad.append("M2 Vol rows %s are not the PM portfolios %s" % (sorted(got), sorted(want)))
tiers = [t["id"] for t in dj["pm_risk_tabs"]["tabs"]]
if [t["label"] for t in dj["pm_risk_tabs"]["tabs"]] != ["All", "DM · Low", "DM · Middle", "DM+EM · Middle-High", "DM+EM · High"]: bad.append("M2 the filter tabs are %s" % [t["label"] for t in dj["pm_risk_tabs"]["tabs"]])
last = -1; lvl = {}
for r in vol["rows"]:
    d = dash.get(r["dashboard"]); c = r["cells"]
    if d is None: continue
    if len(c) != len(vol["columns"]) - 1: bad.append("M2 %s has %d cells" % (r["dashboard"], len(c))); continue
    t = tiers.index(d["risk"]); lvl.setdefault(t, []).append(c[-1])
    if t < last: bad.append("M2 %s is out of risk-level order" % r["dashboard"])
    last = t
    if d["market"] == "EM" and c[6] not in ("Yes", "No"): bad.append("M2 %s is an EM portfolio with no Swap yes/no" % r["dashboard"])
    if d["market"] == "DM" and c[6] != "": bad.append("M2 %s is DM only and has a Swap" % r["dashboard"])
    if r["dashboard"] == "pm_d0_carry": continue
    n = [int(x) for x in c[:6]]
    if n[0] + n[3] != 100 or n[0] != n[1] + n[2] or n[3] != n[4] + n[5]: bad.append("M2 %s allocation does not add up: %s" % (r["dashboard"], n))
    if d["em_pct"] != n[3] or d["equity_pct"] != n[2] + n[5]: bad.append("M2 %s allocation differs from its dashboard (em %s, equity %s)" % (r["dashboard"], d["em_pct"], d["equity_pct"]))
l3 = [r["dashboard"] for r in vol["rows"] if dash.get(r["dashboard"], {}).get("risk") == "em_mid"]
if l3 != ["pm_b1_mid", "pm_d1_em_bonds", "pm_c1_risky"]: bad.append("M2 level 3 is %s, want B1, D1, C1" % l3)
if "sample" not in vol["note"].lower() and "model" not in vol["note"].lower(): bad.append("M2 the Vol note does not label the values as model values")
# M3
po, pf = pm["portfolios"], pm["performance"]
if po["columns"] != ["Portfolio", "Vol", "Top 3", "See all"]: bad.append("M3 the Portfolios columns are %s" % po["columns"])
if [x["title"] for x in po["tables"]] != ["Portfolios DM vs EM", "Portfolios US Cyclicals"] or [x["table"] for x in po["tables"]] != ["vol", "profiles"]: bad.append("M3 the Portfolios tables are %s" % [(x["title"], x["table"]) for x in po["tables"]])
if po.get("top") != 3: bad.append("M3 Top 3 is declared as top %s" % po.get("top"))
if [w["label"] for w in pf["windows"]] != ["Today", "Last 7d", "Last 30d", "Month (MTD)", "Year (YTD)", "Last 1y", "Last 3y (CAGR, annualised)"]: bad.append("M3 the performance windows are %s" % [w["label"] for w in pf["windows"]])
if [w["id"] for w in pf["windows"] if w.get("cagr")] != ["y3"]: bad.append("M3 only the 3y window is a CAGR")
if pf["rebalance"] != "monthly" or pf["rebalance"] not in pf["rebalance_rules"]: bad.append("M3 the rebalancing rule is %r" % pf["rebalance"])
if not pf["history"].get("url", "").startswith("https://") or not pf["history"].get("source"): bad.append("M3 the price history source is not declared")
legsn = {l["name"] for l in po["tables"][0]["legs"]}
if not legsn <= set(pf["proxies"]): bad.append("M3 legs without a proxy: %s" % sorted(legsn - set(pf["proxies"])))
for k in pf["no_history"]:
    if k not in dash: bad.append("M3 no_history names the unknown portfolio %s" % k)
snap_p = os.path.join(app, "data/pm-performance.json")
want_ids = [r["dashboard"] for r in vol["rows"]] + [t["id"] for t in pm["profiles"]["tabs"]]
if not os.path.exists(snap_p): bad.append("M3 no data/pm-performance.json")
else:
    sn = json.loads(read("data/pm-performance.json"))
    if sorted(sn["portfolios"]) != sorted(want_ids): bad.append("M3 the performance snapshot does not hold every portfolio of both tables: %s" % sorted(set(want_ids) ^ set(sn["portfolios"])))
    for k, v in sn["portfolios"].items():
        if "na" in v:
            if not v["na"]: bad.append("M3 %s is n/a with no reason" % k)
            continue
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", v.get("asof", "")): bad.append("M3 %s has no as-of date" % k)
        if set(v["values"]) != {w["id"] for w in pf["windows"]}: bad.append("M3 %s windows are %s" % (k, sorted(v["values"])))
        for w, x in v["values"].items():
            if x is not None and not (isinstance(x, (int, float)) and abs(x) < 20): bad.append("M3 %s/%s is not a plausible return: %r" % (k, w, x))
    for k in pf["no_history"]:
        if "na" not in sn["portfolios"].get(k, {}): bad.append("M3 %s has no free history but carries numbers" % k)
    if re.search(r"(?i)\b(nan|placeholder|lorem|todo|tbd)\b", json.dumps(sn)): bad.append("M3 the performance snapshot holds a placeholder token")
# M4
pr = pm["profiles"]
if [t["label"] for t in pr["tabs"]] != ["Growth", "Value", "Quality", "Cyclical", "Defensive", "Dividend", "Momentum", "Small-cap"]: bad.append("M4 profiles are %s" % [t["label"] for t in pr["tabs"]])
for t in pr["tabs"]:
    if not t.get("leads") or not t["rows"] or any(len(r) != len(pr["columns"]) for r in t["rows"]): bad.append("M4 profile %s has no phases, no rows or a ragged row" % t["id"])
# M5
src = {f: re.sub(r"/\*.*?\*/", "", read(kt + f), flags=re.S) for f in ("ui/PmPage.kt", "PmTables.kt", "ui/PmPanels.kt", "PmPortfolios.kt", "PmPerformance.kt")}
words = {t["label"] for t in pr["tabs"]} | {"Commodity", "Swap", "Gold", "AGG", "URTH", "EEM", "EMB"} | {r["name"] for r in []}
for f, text in src.items():
    code = "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in text.splitlines())
    for w in sorted(words):
        if re.search(r'"[^"]*\b%s\b[^"]*"' % re.escape(w), code): bad.append("M5 %s names %r (it is declared in data/pm.json)" % (f, w))
    if re.search(r"\d\s*\.\s*sp\b|\bfontSize\s*=", code): bad.append("M5 %s sets a type size (use the FinType ramp)" % f)
if "FinMatrix(" not in src["ui/PmPage.kt"] or "FinMatrix(" not in src["ui/PmPanels.kt"]: bad.append("M5 PmPage / PmPanels do not draw the FinMatrix")
if "Pages.KIND_PM_TABLE" not in read(kt + "ui/SectionPage.kt"): bad.append("M5 SectionPage does not draw the pm_table kind")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] PM layout rules (#1478)"
out="$(check "$APP")" && ok "M1-M5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <python statements on the scratch copy, cwd = the copy> <needle>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
JE="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=2,ensure_ascii=False)"
APPEND="A=lambda f,t:open(f,'a').write(t)"
KT="app/src/main/java/com/diegonmarcos/capitalfinterm"
mutant "a PM page is dropped"            "$JE;d=L('build.json');next(s for s in d['ui']['sections'] if s['id']=='pm')['pages'].pop();D('build.json',d)" "M1 the PM sub-pages"
mutant "a Vol row is dropped"            "$JE;d=L('data/pm.json');d['vol']['rows'].pop(0);D('data/pm.json',d)" "M2 Vol rows"
mutant "the duplicate A2 comes back"     "$JE;d=L('data/dashboards.json');d['dashboards'].append(dict(d['dashboards'][-1],id='pm_a2_low'));D('data/dashboards.json',d)" "M2 the duplicate A2"
mutant "an allocation does not add up"   "$JE;d=L('data/pm.json');d['vol']['rows'][1]['cells'][0]='90';D('data/pm.json',d)" "M2 mp_70_30 allocation does not add up"
mutant "an EM portfolio loses its Swap"  "$JE;d=L('data/pm.json');d['vol']['rows'][5]['cells'][6]='';D('data/pm.json',d)" "M2 pm_b1_mid is an EM portfolio with no Swap"
mutant "level 3 loses its Vol order"     "$JE;d=L('data/pm.json');r=d['vol']['rows'];r[5],r[6]=r[6],r[5];D('data/pm.json',d)" "M2 level 3 is"
mutant "a filter tab is renamed"         "$JE;d=L('data/dashboards.json');d['pm_risk_tabs']['tabs'][4]['label']='EM · High';D('data/dashboards.json',d)" "M2 the filter tabs"
mutant "the Commodities table comes back" "$JE;d=L('data/pm.json');d['commodities']={'columns':[],'rows':[]};D('data/pm.json',d)" "M1 the PM Commodities table or page is still declared"
mutant "a legacy PM link stops redirecting" "$JE;d=L('build.json');d['ui']['redirects']=[r for r in d['ui']['redirects'] if r['from']!='pm-commodities'];D('build.json',d)" "M1 the legacy pm-commodities link"
mutant "a Portfolios column is renamed"  "$JE;d=L('data/pm.json');d['portfolios']['columns'][2]='Top 5';D('data/pm.json',d)" "M3 the Portfolios columns"
mutant "a performance window is dropped" "$JE;d=L('data/pm.json');d['performance']['windows'].pop(0);D('data/pm.json',d)" "M3 the performance windows"
mutant "the rebalancing rule is undeclared" "$JE;d=L('data/pm.json');d['performance']['rebalance']='weekly';D('data/pm.json',d)" "M3 the rebalancing rule"
mutant "a leg loses its proxy"           "$JE;d=L('data/pm.json');d['performance']['proxies'].pop('EM Bonds');D('data/pm.json',d)" "M3 legs without a proxy"
mutant "a portfolio is missing from the snapshot" "$JE;d=L('data/pm-performance.json');d['portfolios'].pop('growth');D('data/pm-performance.json',d)" "M3 the performance snapshot does not hold every portfolio"
mutant "a portfolio loses its as-of date" "$JE;d=L('data/pm-performance.json');del d['portfolios']['mp_60_40']['asof'];D('data/pm-performance.json',d)" "M3 mp_60_40 has no as-of date"
mutant "a no-history portfolio carries numbers" "$JE;d=L('data/pm-performance.json');d['portfolios']['pm_a1_cash']=dict(d['portfolios']['mp_60_40']);D('data/pm-performance.json',d)" "M3 pm_a1_cash has no free history but carries numbers"
mutant "an n/a has no reason"            "$JE;d=L('data/pm-performance.json');d['portfolios']['pm_d0_carry']['na']='';D('data/pm-performance.json',d)" "M3 pm_d0_carry is n/a with no reason"
mutant "a return is implausible"         "$JE;d=L('data/pm-performance.json');d['portfolios']['mp_60_40']['values']['ytd']=55.0;D('data/pm-performance.json',d)" "M3 mp_60_40/ytd is not a plausible return"
mutant "Kotlin names a proxy"            "$APPEND;A('$KT/PmPerformance.kt','\nval x = \"URTH\"\n')" "M5 PmPerformance.kt names 'URTH'"
mutant "a profile is dropped"            "$JE;d=L('data/pm.json');d['profiles']['tabs'].pop();D('data/pm.json',d)" "M4 profiles are"
mutant "Kotlin names a commodity"        "$APPEND;A('$KT/ui/PmPage.kt','\nval x = \"Gold\"\n')" "M5 ui/PmPage.kt names 'Gold'"
mutant "Kotlin types an sp literal"      "$APPEND;A('$KT/PmTables.kt','\nval y = 12.sp\n')" "M5 PmTables.kt sets a type size"
mutant "the panels stop using FinMatrix" "$JE;p='$KT/ui/PmPanels.kt';t=open(p).read();open(p,'w').write(t.replace('FinMatrix(','FinMatrixx('))" "M5 PmPage / PmPanels do not draw the FinMatrix"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
