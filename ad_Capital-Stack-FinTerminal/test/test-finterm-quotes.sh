#!/usr/bin/env bash
# test-finterm-quotes: #1539 the price boards (HG futures board, EQ Exchange rates, EQ Commodity prices), statically (no build, no network, no device).
#   Q1  HG: the 8 asset classes with every instrument the owner named; FX futures declare the hedge currency; the nav pages exist (8 boards + Hedge Calculator + the old EX pages), no Commodities page in HG
#   Q2  EQ: Exchange rates (the 19 currencies, one USD leg each) and Commodity prices (the 16 commodities, each reading a front symbol of an HG root); the old CM / EX-Commodities links redirect to Commodity Prices
#   Q3  every root declares multiplier, tick, months, listed expiries and an expiry rule that exists; a root with no free source has no symbol (n/a), never a guessed one
#   Q4  data/quotes-snapshot.json: a number and the source's own timestamp on every quote, only declared symbols, no placeholder, no quote for a root declared with no source
#   Q5  Kotlin names no instrument, has no sp literal and no address, the generator parses its fixture (--selftest)
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
q, bj = load("data/quotes.json"), load("build.json")["ui"]
cls = {c["id"]: c for c in q["futures"]["classes"]}
# Q1
if list(cls) != ["rates", "equity", "fx", "energy", "metals", "agriculture", "crypto", "volatility"]: bad.append("Q1 the asset classes are %s" % list(cls))
NEED = {
 "rates": ["SOFR", "Fed Funds", "Euribor", "EUR STR", "SONIA", "TONA", "Treasury 2Y", "Treasury 5Y", "Treasury 10Y", "Treasury Bond 30Y", "Schatz", "Bobl", "Bund", "Buxl", "BTP", "OAT", "Gilt", "JGB"],
 "equity": ["S&P 500", "Nasdaq", "Dow", "Russell", "Euro Stoxx 50", "DAX", "FTSE", "Nikkei", "Hang Seng", "KOSPI", "Nifty", "Ibovespa", "ASX"],
 "fx": ["Euro FX", "Japanese Yen", "British Pound", "Australian Dollar", "Canadian Dollar", "Swiss Franc", "Mexican Peso", "Brazilian Real", "Indian Rupee", "Offshore Yuan", "Dollar Index"],
 "energy": ["WTI", "Brent", "Henry Hub", "TTF", "RBOB", "Heating Oil", "Gasoil", "Coal"],
 "metals": ["Gold", "Silver", "Copper", "Platinum", "Palladium", "Aluminium", "Nickel", "Zinc", "Iron Ore"],
 "agriculture": ["Corn", "Wheat", "Soybeans", "Soybean Meal", "Soybean Oil", "Coffee", "Sugar", "Cocoa", "Cotton", "Cattle", "Hogs"],
 "crypto": ["Bitcoin", "Ether"],
 "volatility": ["VIX", "VSTOXX"],
}
for cid, names in NEED.items():
    have = " | ".join(r["name"] for r in cls.get(cid, {"roots": []})["roots"])
    for n in names:
        if n.lower() not in have.lower(): bad.append("Q1 %s lacks %s" % (cid, n))
for r in cls["fx"]["roots"]:
    if r["root"] != "DX" and not r.get("hedge"): bad.append("Q1 FX future %s declares no hedge currency" % r["root"])
want = ["hg-rates", "hg-equity", "hg-fx", "hg-energy", "hg-metals", "hg-agriculture", "hg-crypto", "hg-volatility", "hedge-calculator"]
hg = next(s for s in bj["sections"] if s["id"] == "hg")
ids = [p["id"] for p in hg["pages"]]
if ids[1:1 + len(want)] != want: bad.append("Q1 the HG pages are %s" % ids)
if "commodities" in ids: bad.append("Q1 HG still has the Commodities page")
for e in bj["stacks"]["hg"]:
    if e.get("kind") == "futures_board" and e.get("view") not in cls: bad.append("Q1 board %s names the unknown class %s" % (e.get("page"), e.get("view")))
if [e.get("view") for e in bj["stacks"]["hg"] if e.get("kind") == "futures_board"] != list(cls): bad.append("Q1 the HG boards do not follow the classes in order")
# Q2
CC = ["USD", "EUR", "JPY", "GBP", "CHF", "CAD", "AUD", "NZD", "CNY", "INR", "BRL", "MXN", "KRW", "IDR", "ZAR", "RUB", "TRY", "SEK", "NOK"]
cur = q["fx"]["currencies"]
if [c["id"] for c in cur] != CC: bad.append("Q2 the currencies are %s" % [c["id"] for c in cur])
for c in cur:
    if c["id"] != "USD" and (not c.get("symbol") or c.get("quote") not in ("usd_per", "per_usd")): bad.append("Q2 %s has no USD leg" % c["id"])
COM = ["WTI", "Brent", "Natural Gas", "Gold", "Silver", "Copper", "Platinum", "Palladium", "Aluminium", "Corn", "Wheat", "Soybeans", "Coffee", "Sugar", "Cocoa", "Cotton"]
com = q["commodities"]
if [c["name"].split(" (")[0].replace(" Crude Oil", "").replace(" C", "").replace(" No. 11", "").replace(" No. 2", "") for c in com] != [n.replace("Natural Gas", "Natural Gas") for n in COM]: bad.append("Q2 the commodities are %s" % [c["name"] for c in com])
roots = {r["id"]: r for c in q["futures"]["classes"] for r in c["roots"]}
for c in com:
    r = roots.get(c["root"])
    if not r or not r.get("front"): bad.append("Q2 %s reads no front symbol of an HG root" % c["id"])
eq = next(s for s in bj["sections"] if s["id"] == "eq")
ep = [p["id"] for p in eq["pages"]]
if "fx-rates" not in ep or "commodity-prices" not in ep: bad.append("Q2 EQ lacks Exchange Rates or Commodity Prices")
for old in ("cm", "ex-commodities"):
    if not any(r.get("from") == old and r.get("tab") == "eq" and r.get("to") == "commodity-prices" for r in bj.get("redirects", [])): bad.append("Q2 the legacy %s link does not redirect to Commodity Prices" % old)
# Q3
codes = set(q["month_codes"])
for cid, c in cls.items():
    for r in c["roots"]:
        for k in ("multiplier", "tick"):
            if not isinstance(r.get(k), (int, float)) or r[k] <= 0: bad.append("Q3 %s has no %s" % (r["id"], k))
        if (r.get("listed", 0) > 0 and not r.get("months")) or not set(r.get("months", "")) <= codes: bad.append("Q3 %s months %r" % (r["id"], r.get("months")))
        if r["expiry"] not in q["expiry_rules"]: bad.append("Q3 %s names the undeclared expiry rule %s" % (r["id"], r["expiry"]))
        if not isinstance(r.get("listed"), int) or r["listed"] < 0: bad.append("Q3 %s listed" % r["id"])
        if r.get("suffix") is None and r.get("listed", 0) > 0 and r.get("front") is not None: bad.append("Q3 %s has a front symbol but no contract suffix" % r["id"])
        if r.get("suffix") is None and r.get("front") is None and re.search(r"symbol|yahoo", json.dumps(r)): bad.append("Q3 %s has no source but names a symbol" % r["id"])
for k, rule in q["expiry_rules"].items():
    if rule["kind"] not in ("nth_weekday", "last_weekday", "last_bday", "bdays_before_last_bday", "bdays_before_first", "bdays_before_day", "nth_bday", "days_before_nth_weekday"): bad.append("Q3 rule %s kind %s" % (k, rule["kind"]))
# Q4
sp = os.path.join(app, "data/quotes-snapshot.json")
if not os.path.exists(sp): bad.append("Q4 no data/quotes-snapshot.json")
else:
    s = load("data/quotes-snapshot.json")
    num = lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and v == v and abs(v) != float("inf")
    for f in ("source", "delay", "generated"):
        if not s.get(f): bad.append("Q4 the snapshot has no %s" % f)
    declared = {c["symbol"] for c in cur if c.get("symbol")} | {r["front"] for r in roots.values() if r.get("front")}
    sufs = {r["id"]: (r.get("yahoo_root", r["root"]), r["suffix"]) for r in roots.values() if r.get("suffix")}
    for k, v in s["quotes"].items():
        if not num(v.get("last")): bad.append("Q4 %s has no numeric last" % k)
        if not isinstance(v.get("asof"), int) or v["asof"] < 1_700_000_000: bad.append("Q4 %s has no source timestamp" % k)
        if k not in declared and not any(k.startswith(p) and k.endswith("." + x) for p, x in sufs.values()): bad.append("Q4 %s is not a declared symbol" % k)
        for f in ("pct", "chg", "prev", "w1", "m1", "ytd"):
            if f in v and not num(v[f]): bad.append("Q4 %s/%s is not a number" % (k, f))
    shared = {x[0] for x in sufs.values()}
    for r in roots.values():
        if r.get("suffix") is None and r.get("front") is None and r["root"] not in shared and any(k.startswith(r["root"]) and "." in k and re.fullmatch(re.escape(r["root"]) + r"[FGHJKMNQUVXZ]\d\d\..*", k) for k in s["quotes"]): bad.append("Q4 a quote exists for %s, which has no free source" % r["id"])
    if re.search(r"(?i)\b(nan|placeholder|lorem|todo|tbd)\b", json.dumps(s)): bad.append("Q4 the snapshot holds a placeholder token")
    for c in cur:
        if c.get("symbol") and c["symbol"] not in s["quotes"]: bad.append("Q4 no FX quote for %s" % c["id"])
# Q5
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/capitalfinterm")
names = [r["name"] for r in roots.values()] + [c["name"] for c in cur] + [c["name"] for c in com]
for fn in ("Quotes.kt", "QuotesStore.kt", "ui/QuotePages.kt"):
    src = open(os.path.join(kt, fn), encoding="utf-8").read()
    for n in names:
        if '"%s"' % n in src: bad.append("Q5 %s names the declared %r" % (fn, n))
    if re.search(r"\b\d+(\.\d+)?\.sp\b", src): bad.append("Q5 %s has an sp literal" % fn)
    if re.search(r"fontSize\s*=|TextStyle\(", src): bad.append("Q5 %s sets a type size" % fn)
    if re.search(r"https?://", src): bad.append("Q5 %s spells an address" % fn)
if "FinMatrix(" not in open(os.path.join(kt, "ui/QuotePages.kt"), encoding="utf-8").read(): bad.append("Q5 QuotePages does not draw FinMatrix")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] price boards rules (#1539)"
if out="$(python3 -I "$APP/scripts/gen-quotes-snapshot.py" --selftest 2>&1)"; then ok "the generator parses the source's response fixture"; else bad "the quotes generator selftest fails"; echo "$out"; fi
out="$(check "$APP")" && ok "Q1-Q5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
J="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=1)"
KT="app/src/main/java/com/diegonmarcos/capitalfinterm"
APPEND="a=lambda f,t:open(f,'a').write(t)"
mutant "an asset class is dropped"        "$J;d=L('data/quotes.json');d['futures']['classes'].pop();D('data/quotes.json',d)" "Q1 the asset classes"
mutant "an owner instrument is dropped"   "$J;d=L('data/quotes.json');d['futures']['classes'][0]['roots']=[r for r in d['futures']['classes'][0]['roots'] if r['id']!='FGBL'];D('data/quotes.json',d)" "Q1 rates lacks Bund"
mutant "an FX future loses its hedge currency" "$J;d=L('data/quotes.json');[r.pop('hedge') for r in d['futures']['classes'][2]['roots'] if r['id']=='6E'];D('data/quotes.json',d)" "Q1 FX future 6E declares no hedge currency"
mutant "Commodities comes back to HG"     "$J;d=L('build.json');h=[s for s in d['ui']['sections'] if s['id']=='hg'][0];h['pages'].append({'id':'commodities','label':'Commodities','icon':'inventory_2'});D('build.json',d)" "Q1 HG still has the Commodities page"
mutant "a currency is dropped"            "$J;d=L('data/quotes.json');d['fx']['currencies'].pop();D('data/quotes.json',d)" "Q2 the currencies are"
mutant "a currency loses its USD leg"     "$J;d=L('data/quotes.json');d['fx']['currencies'][1].pop('symbol');D('data/quotes.json',d)" "Q2 EUR has no USD leg"
mutant "a commodity reads no front symbol" "$J;d=L('data/quotes.json');d['commodities'][0]['root']='NOPE';D('data/quotes.json',d)" "Q2 wti reads no front symbol"
mutant "the old CM link stops redirecting" "$J;d=L('build.json');d['ui']['redirects']=[r for r in d['ui']['redirects'] if r['from']!='cm'];D('build.json',d)" "Q2 the legacy cm link"
mutant "a root loses its multiplier"      "$J;d=L('data/quotes.json');del d['futures']['classes'][3]['roots'][0]['multiplier'];D('data/quotes.json',d)" "Q3 CL has no multiplier"
mutant "a root names an unknown expiry rule" "$J;d=L('data/quotes.json');d['futures']['classes'][3]['roots'][0]['expiry']='moon';D('data/quotes.json',d)" "Q3 CL names the undeclared expiry rule"
mutant "a quote loses its timestamp"      "$J;d=L('data/quotes-snapshot.json');k=[k for k in d['quotes']][0];del d['quotes'][k]['asof'];D('data/quotes-snapshot.json',d)" "has no source timestamp"
mutant "a quote is a string"              "$J;d=L('data/quotes-snapshot.json');k=[k for k in d['quotes']][0];d['quotes'][k]['last']='n/a';D('data/quotes-snapshot.json',d)" "has no numeric last"
mutant "a quote is invented for an undeclared symbol" "$J;d=L('data/quotes-snapshot.json');d['quotes']['FAKE=F']=dict(list(d['quotes'].values())[0]);D('data/quotes-snapshot.json',d)" "FAKE=F is not a declared symbol"
mutant "a quote is invented for a root with no free source" "$J;d=L('data/quotes-snapshot.json');d['quotes']['FDAXZ26.EUX']=dict(list(d['quotes'].values())[0]);D('data/quotes-snapshot.json',d)" "Q4 a quote exists for FDAX"
mutant "the snapshot loses its source"    "$J;d=L('data/quotes-snapshot.json');d['source']='';D('data/quotes-snapshot.json',d)" "the snapshot has no source"
mutant "Kotlin names an instrument"       "$APPEND;a('$KT/Quotes.kt','\\nval x = \"Gold\"\\n')" "Q5 Quotes.kt names the declared 'Gold'"
mutant "Kotlin types an sp literal"       "$APPEND;a('$KT/ui/QuotePages.kt','\\nval y = 12.sp\\n')" "Q5 ui/QuotePages.kt has an sp literal"
mutant "Kotlin spells an address"         "$APPEND;a('$KT/QuotesStore.kt','\\nval u = \"https://example.test/\"\\n')" "Q5 QuotesStore.kt spells an address"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
