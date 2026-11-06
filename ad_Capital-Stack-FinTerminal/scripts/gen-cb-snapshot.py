#!/usr/bin/env python3
"""gen-cb-snapshot.py: the baked CB snapshot (data/cb-snapshot.json), refreshed at build or refresh time.

    python3 scripts/gen-cb-snapshot.py [--out data/cb-snapshot.json]     fetch every declared source and write the snapshot
    python3 scripts/gen-cb-snapshot.py --selftest                        parse the response fixtures of each API and assert the result

Reads data/cb-countries.json and data/cb-indicators.json (the declarations; this script names no country, indicator, series or address of its own
besides the endpoint templates the indicator file declares under `sources`). A series is written only when its source answered for the country;
a missing value is absent, never typed: the app shows 'n/a (no free source)' for it. Every series carries its source, release and vintage date.
The app's in-app refresh (CbRefresh.kt) follows the same rules against the same APIs.
"""
import csv, datetime, io, json, os, sys, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
UA = "Capital-Stack-FinTerminal"   # the IMF edge refuses browser user agents: its declared user_agent (cb-indicators.json sources.imf) is sent instead
NOW = datetime.date.today()
FIX = os.path.join(APP, "app/src/test/resources/cb")

# ------------------------------------------------------------------ parsers (pure; the selftest and CbSources.kt cover the same shapes)
def parse_imf(text, series):
    """DataMapper: {"values": {SERIES: {ISO3: {"2024": 1.2}}}} -> {ISO3: {year: value}}"""
    d = json.loads(text)["values"].get(series, {})
    return {c: {int(y): float(v) for y, v in ys.items() if v is not None} for c, ys in d.items()}

def parse_oecd(text):
    """SDMX csvfilewithlabels -> ({(area, measure): {year: value}}, release)"""
    out, rel = {}, ""
    for r in csv.DictReader(io.StringIO(text)):
        if not r.get("OBS_VALUE"): continue
        rel = r.get("STRUCTURE_NAME") or rel
        out.setdefault((r["REF_AREA"], r["MEASURE"]), {})[int(r["TIME_PERIOD"][:4])] = float(r["OBS_VALUE"])
    return out, rel

def parse_wb(text):
    """World Bank v2 JSON -> ({ISO3: {year: value}}, lastupdated)"""
    d = json.loads(text)
    if not isinstance(d, list) or len(d) < 2 or d[1] is None: return {}, ""
    out = {}
    for o in d[1]:
        if o.get("value") is not None: out.setdefault(o["countryiso3code"], {})[int(o["date"])] = float(o["value"])
    return out, d[0].get("lastupdated", "")

def _period(p):
    """'2025-03' / '2025-Q4' / '2025-03-31' / '2025' -> (year, month)"""
    y = int(p[:4]); rest = p[5:]
    if not rest: return y, 12
    if rest.startswith("Q"): return y, int(rest[1]) * 3
    return y, int(rest[:2])

def parse_bis(text):
    """BIS SDMX csv -> {REF_AREA: {(year, month): value}} (the TIME_PERIOD of WS_CBPOL is monthly, WS_TC quarterly)"""
    out = {}
    for r in csv.DictReader(io.StringIO(text)):
        if r.get("OBS_VALUE") not in (None, ""): out.setdefault(r["REF_AREA"], {})[_period(r["TIME_PERIOD"])] = float(r["OBS_VALUE"])
    return out

def parse_fred(text):
    """fredgraph.csv -> {(year, month): value}; '.' is a missing day"""
    out = {}
    rows = csv.reader(io.StringIO(text))
    head = next(rows, None)
    if not head or head[0] not in ("observation_date", "DATE"): return out
    for r in rows:
        if len(r) < 2 or r[1] in ("", "."): continue
        out.setdefault(_period(r[0][:10]), []).append(float(r[1]))
    return out   # month -> observations of that month (a daily series has many)

def parse_ecb(text):
    """ECB data-api csvdata -> ({year: value}, title)"""
    out, title = {}, ""
    for r in csv.DictReader(io.StringIO(text)):
        if r.get("OBS_VALUE") in (None, ""): continue
        out[int(r["TIME_PERIOD"][:4])] = float(r["OBS_VALUE"]); title = r.get("TITLE") or title
    return out, title

# ------------------------------------------------------------------ transport
def get(url, tries=3, ua=None):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": ua or UA}), timeout=120) as r: return r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            if e.code in (400, 404): return None
            time.sleep(2 * (i + 1))
        except Exception:
            time.sleep(2 * (i + 1))
    return None

# ------------------------------------------------------------------ annual series from monthly observations
def annualise(months, agg, transform, cur):
    """months {(y, m): [obs]} -> (annual {year: value} for complete years, ytd {year, value, through} or None)"""
    if not months: return {}, None
    last = max(months)
    lastyear = last[0]
    byyear = {}
    for (y, m), obs in months.items(): byyear.setdefault(y, {})[m] = obs
    def level(y, ms):
        vals = [v for m in ms for v in byyear.get(y, {}).get(m, [])]
        if not vals: return None
        if agg == "last": return byyear[y][max(m for m in ms if m in byyear[y])][-1]
        return sum(vals) / len(vals)
    annual = {}
    for y in sorted(byyear):
        if y >= lastyear: continue
        v = level(y, range(1, 13))
        if v is None: continue
        annual[y] = v
    if transform == "yoy":
        annual = {y: (annual[y] / annual[y - 1] - 1) * 100 for y in annual if y - 1 in annual and annual[y - 1] not in (0, None)}
    ytd = None
    ms = sorted(byyear[lastyear])
    v = level(lastyear, ms)
    if v is not None:
        if transform == "yoy":
            p = level(lastyear - 1, ms)
            v = (v / p - 1) * 100 if p not in (None, 0) else None
        if v is not None: ytd = {"year": lastyear, "value": v, "through": "%04d-%02d" % last}
    return annual, ytd

def apply(vals, rule):
    s = rule.get("scale", 1)
    return {y: v * s for y, v in vals.items()}

def tf_yoy(a):
    return {y: (a[y] / a[y - 1] - 1) * 100 for y in a if y - 1 in a and a[y - 1] not in (0, None)}

# ------------------------------------------------------------------ the run
def main(out_path):
    cty = json.load(open(os.path.join(APP, "data/cb-countries.json")))
    ind = json.load(open(os.path.join(APP, "data/cb-indicators.json")))
    cs = cty["countries"]
    cur = NOW.year
    hist_from = cur - cty["years"]["history"]
    fc_to = cur + cty["years"]["forecast"] - 1
    today = NOW.isoformat()
    items = {i["id"]: (g["id"], i) for g in ind["full"]["groups"] for i in g["items"]}
    srcs = ind["sources"]
    cache = {}
    def cached(k, fn):
        if k not in cache: cache[k] = fn()
        return cache[k]
    # --- the requests each source needs (one per series, or one for all of the OECD)
    rules = [(i["id"], n, r) for i in items.values() for n, r in enumerate(i[1]["sources"])] if False else [(iid, n, r) for iid, (_, it) in items.items() for n, r in enumerate(it["sources"])]
    def imf(series):  return cached(("imf", series), lambda: parse_imf(get(srcs["imf"]["url"].format(series=series), ua=srcs["imf"].get("user_agent")) or '{"values":{}}', series))
    oecd_areas = "+".join(c["oecd"] for c in cs if c["oecd"])
    oecd_measures = sorted({r["series"] for _, _, r in rules if r["src"] == "oecd_eo"})
    def oecd_all():
        t = get(srcs["oecd_eo"]["url"].format(areas=oecd_areas, series="+".join(oecd_measures), **{"from": hist_from - 1, "to": fc_to}))
        return parse_oecd(t) if t else ({}, "")
    def wb(series):
        def f():
            codes = ";".join(sorted({c["wb"] for c in cs}))
            t = get(srcs["wb"]["url"].format(codes=codes, series=series, **{"from": hist_from - 1, "to": cur}))
            return parse_wb(t) if t else ({}, "")
        return cached(("wb", series), f)
    def bis(series, key, areas):
        def f():
            t = get(srcs["bis"]["url"].format(series=series, key=key, **{"from": hist_from - 1}))
            return parse_bis(t) if t else {}
        return cached(("bis", series, key), f)
    def fred(series):
        return cached(("fred", series), lambda: (lambda t: parse_fred(t) if t else None)(get(srcs["fred"]["url"].format(series=series, **{"from": hist_from - 1}))))
    def ecb(series):
        def f():
            for v in ("S%02d" % (cur % 100), "G%02d" % (cur % 100), "W%02d" % (cur % 100), "S%02d" % (cur % 100 - 1)):
                t = get(srcs["ecb_mpd"]["url"].format(series=series, vintage=v))
                if t and t.startswith("KEY"):
                    vals, title = parse_ecb(t)
                    if vals: return vals, title or "ECB staff macroeconomic projections (MPD vintage %s)" % v
            return {}, ""
        return cached(("ecb", series), f)
    bis_areas = "+".join(sorted({c["bis"] for c in cs}))
    by_id = {c["id"]: c for c in cs}

    def fetch(rule, c):
        """-> (annual {year: value}, ytd, release, note) or None: what `rule` gives country `c`"""
        s, src = rule["series"], rule["src"]
        tr = rule.get("transform")
        if src == "imf":
            v = imf(s).get(c["iso3"])
            if not v: return None
            return apply(tf_yoy(v) if tr == "yoy" else v, rule), None, "IMF World Economic Outlook, retrieved " + today
        if src == "oecd_eo":
            if not c["oecd"]: return None
            data, rel = oecd_all()
            v = data.get((c["oecd"], s))
            if not v: return None
            return apply(tf_yoy(v) if tr == "yoy" else v, rule), None, (rel or "OECD Economic Outlook") + ", retrieved " + today
        if src == "wb":
            data, rel = wb(s)
            v = data.get(c["iso3"] if c["id"] != "EA" else "EMU")
            if not v: return None
            return apply(tf_yoy(v) if tr == "yoy" else v, rule), None, "World Bank, last updated %s, retrieved %s" % (rel or "n/a", today)
        if src == "bis":
            tgt = by_id[rule["ref"]] if rule.get("ref") else c
            key = rule.get("key", "M.{bis}").format(bis=bis_areas)
            data = bis(s, key, bis_areas).get(tgt["bis"])
            if not data: return None
            months = {k: [v] for k, v in data.items()}
            a, ytd = annualise(months, rule.get("agg", "avg"), tr, cur)
            return apply(a, rule), ({**ytd, "value": ytd["value"] * rule.get("scale", 1)} if ytd else None), "BIS %s, retrieved %s" % (s, today)
        if src == "fred":
            series = s.replace("{iso2}", c["id"])
            data = fred(series)
            if not data: return None
            a, ytd = annualise(data, rule.get("agg", "avg"), tr, cur)
            sc = rule.get("scale", 1)
            last = "%04d-%02d" % max(data)
            return apply(a, rule), ({**ytd, "value": ytd["value"] * sc} if ytd else None), "FRED %s, last observation %s, retrieved %s" % (series, last, today)
        if src == "ecb_mpd":
            vals, title = ecb(s)
            if not vals: return None
            return vals, None, "%s, retrieved %s" % (title or "ECB staff projections", today)
        return None

    def matches(rule, c):
        cs_ = rule["countries"]
        return cs_ == "*" or c["id"] in cs_

    def rnd(d): return {str(y): round(v, 4) for y, v in sorted(d.items())}

    snap_c = {}
    for c in cs:
        series = {}
        for iid, (gid, it) in items.items():
            main_i, proj = None, []
            for n, r in enumerate(it["sources"]):
                if not matches(r, c): continue
                if r.get("role") == "cb_projection":
                    got = fetch(r, c)
                    if got:
                        a, _, rel = got
                        fc = {y: v for y, v in a.items() if cur <= y <= fc_to}
                        if fc: proj.append({"source": r.get("label") or srcs[r["src"]]["label"], "release": rel, "vintage": today, "label": r.get("label") or srcs[r["src"]]["label"], "rule": n, "fc": rnd(fc)})
                    continue
                if main_i is not None: continue
                got = fetch(r, c)
                if not got: continue
                a, ytd, rel = got
                hist = {y: v for y, v in a.items() if hist_from <= y < cur}
                fc = {y: v for y, v in a.items() if cur <= y <= fc_to}
                if not hist and not fc and not ytd: continue
                main_i = {"source": srcs[r["src"]]["label"], "release": rel, "vintage": today, "rule": n, "unit": r.get("unit") or it["unit"], "hist": rnd(hist), "fc": rnd(fc)}
                if r.get("note"): main_i["note"] = r["note"]
                if ytd and ytd["year"] >= cur - 0 and str(cur) not in main_i["fc"]: main_i["ytd"] = {"year": ytd["year"], "value": round(ytd["value"], 4), "through": ytd["through"]}
            if main_i is None and proj and False: pass
            if main_i is not None:
                if proj: main_i["cb_projection"] = proj
                series[iid] = main_i
        snap_c[c["id"]] = {"series": series}
    # --- weights (IMF GDP; the euro area is the sum of its members)
    w = {"ppp": {}, "fx": {}, "ppp_members": {}, "fx_members": {}}
    for opt in cty["weights"]["options"]:
        data = imf(opt["series"])
        for c in cs:
            if c.get("weight_members"):
                for m in c["weight_members"]:
                    if m in data: w[opt["id"] + "_members"][m] = rnd({y: v for y, v in data[m].items() if hist_from <= y <= fc_to})
                years = sorted({y for m in c["weight_members"] for y in data.get(m, {}) if hist_from <= y <= fc_to})
                w[opt["id"]][c["id"]] = rnd({y: sum(data[m][y] for m in c["weight_members"] if y in data.get(m, {})) for y in years})
            elif c["iso3"] in data:
                w[opt["id"]][c["id"]] = rnd({y: v for y, v in data[c["iso3"]].items() if hist_from <= y <= fc_to})
    snap = {
        "_doc": "GENERATED by scripts/gen-cb-snapshot.py from the sources declared in cb-indicators.json (do not edit). Every series: source, release, vintage (the retrieval date), the rule (index into the indicator's `sources`) that supplied it, hist (actuals before the current year), fc (current year, +1, +2), optional ytd (year to date of the current year for a sub-annual source) and cb_projection (the bank's own published projection). A missing value is absent: the app shows 'n/a (no free source)'.",
        "generated": today, "current_year": cur, "history_from": hist_from, "forecast_to": fc_to,
        "weights": w, "countries": snap_c,
    }
    with open(out_path, "w", encoding="utf-8") as f: json.dump(snap, f, separators=(",", ":"), ensure_ascii=False)
    n = sum(len(c["series"]) for c in snap_c.values())
    print("gen-cb-snapshot: %d series, %d countries, %d indicators, %d bytes" % (n, len(snap_c), len(items), os.path.getsize(out_path)))

# ------------------------------------------------------------------ the selftest: each API's response fixture -> the expected parse
def selftest():
    rd = lambda n: open(os.path.join(FIX, n), encoding="utf-8").read()
    bad = []
    def eq(name, got, want):
        if got != want: bad.append("%s: got %r, wanted %r" % (name, got, want))
    imf = parse_imf(rd("imf-datamapper.json"), "NGDP_RPCH")
    eq("imf USA 2026", imf["USA"][2026], 2.3); eq("imf years", sorted(imf["USA"]), [2024, 2025, 2026, 2027]); eq("imf areas", sorted(imf), ["EURO", "JPN", "USA"])
    o, rel = parse_oecd(rd("oecd-eo.csv"))
    eq("oecd release", rel, "Economic Outlook 119"); eq("oecd gap", round(o[("USA", "GAP")][2024], 3), 0.143)
    w, lu = parse_wb(rd("worldbank.json"))
    eq("wb lastupdated", lu, "2026-10-08"); eq("wb ARE 2025", round(w["ARE"][2025], 3), 1.251); eq("wb countries", sorted(w), ["ARE", "EMU"])
    b = parse_bis(rd("bis-cbpol.csv"))
    eq("bis US 2026-10", b["US"][(2026, 10)], 3.875); eq("bis areas", sorted(b), ["GB", "US"])
    f = parse_fred(rd("fred.csv"))
    eq("fred months", sorted(f), [(2026, 1)]); eq("fred january", f[(2026, 1)], [4.19, 4.17, 4.18, 4.15, 4.19, 4.18])
    eq("fred missing day skipped", parse_fred("observation_date,X\n2026-01-02,.\n2026-01-05,1.5\n"), {(2026, 1): [1.5]})
    eq("fred non-csv refused", parse_fred("<html>not found</html>"), {})
    e, _ = parse_ecb(rd("ecb-mpd.csv"))
    eq("ecb 2027", e[2027], 1.4); eq("ecb years", sorted(e), [2026, 2027, 2028])
    a, ytd = annualise({(2024, 1): [2.0], (2024, 2): [4.0], (2025, 1): [3.0], (2025, 2): [3.0], (2026, 1): [6.0]}, "avg", None, 2026)
    eq("annualise avg", a, {2024: 3.0, 2025: 3.0}); eq("annualise ytd", ytd, {"year": 2026, "value": 6.0, "through": "2026-01"})
    a, ytd = annualise({(2024, 1): [100.0], (2025, 1): [110.0], (2026, 1): [121.0]}, "avg", "yoy", 2026)
    eq("annualise yoy", {y: round(v, 6) for y, v in a.items()}, {2025: 10.0}); eq("annualise yoy ytd", round(ytd["value"], 6), 10.0)
    a, _ = annualise({(2023, 12): [100.0], (2024, 12): [110.0], (2025, 12): [121.0], (2026, 3): [1.0]}, "last", "yoy", 2026)
    eq("annualise last yoy", {y: round(v, 6) for y, v in a.items()}, {2024: 10.0, 2025: 10.0})
    for m in bad: print("FAIL " + m)
    print("selftest: %s" % ("FAILED" if bad else "ok"))
    return 1 if bad else 0

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(selftest())
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(APP, "data/cb-snapshot.json")
    main(out)
