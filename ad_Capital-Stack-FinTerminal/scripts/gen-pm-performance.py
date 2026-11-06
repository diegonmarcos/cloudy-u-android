#!/usr/bin/env python3
"""gen-pm-performance.py: the baked performance of the PM portfolios (data/pm-performance.json): every portfolio of the Portfolios page, from REAL delayed daily closes of its legs.

    python3 scripts/gen-pm-performance.py [--out data/pm-performance.json]    fetch every proxy's history and write the snapshot
    python3 scripts/gen-pm-performance.py --selftest                          the math on the shared fixture (the APK's PmPerformance.kt must give the same numbers)

Reads data/pm.json (portfolios, vol, profiles, performance: the legs, the proxies, the windows, the rebalancing rule, the history source). A portfolio with a leg that has no
free history is written as {"na": reason}; nothing is estimated.
"""
import datetime, json, math, os, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__)); APP = os.path.dirname(HERE)
FIX = os.path.join(APP, "app/src/test/resources/pm")

def parse_history(text):
    """Yahoo chart answer -> ([dates], [closes]) with the exchange-local day of each bar, or None."""
    d = json.loads(text); res = (d.get("chart") or {}).get("result")
    if not res: return None
    r = res[0]; ts = r.get("timestamp"); cl = ((r.get("indicators") or {}).get("quote") or [{}])[0].get("close")
    if not ts or not cl: return None
    off = (r.get("meta") or {}).get("gmtoffset") or 0
    pts = {}
    for t, c in zip(ts, cl):
        if c is not None: pts[(datetime.datetime.fromtimestamp(t + off, datetime.timezone.utc)).date()] = c
    ds = sorted(pts)
    return (ds, [pts[x] for x in ds]) if ds else None

def path(legs, rule):
    """legs = [(weight in %, (dates, closes) or None)] -> [(date, value)] starting at 1.0 on the dates every leg has a close on; None when a leg has no history or the weights are not 100."""
    if not legs or any(s is None for _, s in legs) or abs(sum(w for w, _ in legs) - 100.0) > 1e-6: return None
    maps = [dict(zip(s[0], s[1])) for _, s in legs]
    dates = sorted(set.intersection(*[set(m) for m in maps]))
    if len(dates) < 2 or any(m[d] <= 0 for m in maps for d in dates): return None
    w = [x / 100.0 for x, _ in legs]
    units = [w[i] / maps[i][dates[0]] for i in range(len(w))]
    out = [(dates[0], 1.0)]
    for t in range(1, len(dates)):
        v = sum(units[i] * maps[i][dates[t]] for i in range(len(w)))
        out.append((dates[t], v))
        reb = True if rule == "daily" else (dates[t].month != dates[t - 1].month if rule == "monthly" else False)
        if reb: units = [v * w[i] / maps[i][dates[t]] for i in range(len(w))]
    return out

def _minus_years(d, n):
    try: return d.replace(year=d.year - n)
    except ValueError: return d.replace(year=d.year - n, day=28)

def _minus_months(d, n):
    y, m = d.year, d.month - n
    while m < 1: m += 12; y -= 1
    import calendar
    return d.replace(year=y, month=m, day=min(d.day, calendar.monthrange(y, m)[1]))

def windows(p, ws):
    asof, last = p[-1]
    before = lambda d: next((v for dt, v in reversed(p) if dt < d), None)
    onorbefore = lambda d: None if p[0][0] > d else next((v for dt, v in reversed(p) if dt <= d), None)
    out = {}
    for w in ws:
        if w["id"] == "today": base = p[-2][1] if len(p) >= 2 else None
        elif w["id"] == "mtd": base = before(asof.replace(day=1))
        elif w["id"] == "ytd": base = before(asof.replace(month=1, day=1))
        elif w.get("days"): base = onorbefore(asof - datetime.timedelta(days=w["days"]))
        elif w.get("years"): base = onorbefore(_minus_years(asof, w["years"]))
        else: base = None
        if base is None or base <= 0 or last <= 0: out[w["id"]] = None
        elif w.get("cagr") and w["years"] > 1: out[w["id"]] = (last / base) ** (1.0 / w["years"]) - 1.0
        else: out[w["id"]] = last / base - 1.0
    return out

def vol1y(p):
    v = [x for _, x in p[-253:]]; r = [b / a - 1.0 for a, b in zip(v, v[1:])]
    if len(r) < 200: return None
    m = sum(r) / len(r)
    return math.sqrt(sum((x - m) ** 2 for x in r) / (len(r) - 1)) * math.sqrt(252.0) * 100.0

def portfolios(pm):
    """The portfolios of the Portfolios page as {id: [(weight %, symbol or None)]} and the reasons {id: why}: the same reading as PmPortfolios.kt."""
    perf = pm["performance"]; out = {}; why = {}
    for t in pm["portfolios"]["tables"]:
        src = pm[t["table"]]
        if "weighting" in t:
            for tab in src["tabs"]:
                n = len(tab["rows"]); out[tab["id"]] = [(100.0 / n, r[0]) for r in tab["rows"]]
        else:
            for r in src["rows"]:
                legs = [] if r["dashboard"] in t.get("no_legs", []) else [(float(r["cells"][l["cell"]]), perf["proxies"].get(l["name"])) for l in t["legs"] if r["cells"][l["cell"]] not in ("", None) and float(r["cells"][l["cell"]]) > 0]
                out[r["dashboard"]] = legs
                if r["dashboard"] in perf["no_history"]: why[r["dashboard"]] = perf["no_history"][r["dashboard"]]
    return out, why

def compute(legs, hist, perf, why=None):
    if why: return {"na": why}
    if not legs: return {"na": perf["ui"]["na_long"]}
    p = path([(w, hist.get(s) if s else None) for w, s in legs], perf["rebalance"])
    if p is None: return {"na": perf["ui"]["na_long"]}
    return {"asof": p[-1][0].isoformat(), "values": {k: (None if v is None else round(v, 8)) for k, v in windows(p, perf["windows"]).items()}, "vol1y": (None if vol1y(p) is None else round(vol1y(p), 4))}

def get(url, ua, tries=2):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": ua}), timeout=90) as r: return r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            if e.code in (400, 404): return None
            time.sleep(1.5 * (i + 1))
        except Exception:
            time.sleep(1.5 * (i + 1))
    return None

def main(out):
    pm = json.load(open(os.path.join(APP, "data/pm.json"))); perf = pm["performance"]; h = perf["history"]
    pfs, why = portfolios(pm)
    syms = sorted({s for legs in pfs.values() for _, s in legs if s})
    def one(s):
        t = get(h["url"].replace("{symbol}", urllib.parse.quote(s)), h["user_agent"])
        try: return s, (parse_history(t) if t else None)
        except Exception: return s, None
    import urllib.parse
    with ThreadPoolExecutor(8) as ex: hist = dict(ex.map(one, syms))
    res = {pid: compute(legs, hist, perf, why.get(pid)) for pid, legs in pfs.items()}
    snap = {"_doc": "GENERATED by scripts/gen-pm-performance.py from data/pm.json (do not edit). Per portfolio: the as-of date (the last common close), the window returns as fractions (null = n/a: the history does not reach the base), the realised 1y volatility in % and, for a portfolio with a leg that has no free history, the reason (na). Weighted ETF-proxy portfolios rebalanced by the declared rule from real delayed daily closes.",
            "generated": datetime.date.today().isoformat(), "source": h["source"], "rebalance": perf["rebalance"], "portfolios": res}
    json.dump(snap, open(out, "w"), separators=(",", ":"), ensure_ascii=False)
    print("gen-pm-performance: %d portfolios, %d with numbers, %d of %d symbols answered" % (len(res), sum(1 for r in res.values() if "asof" in r), sum(1 for v in hist.values() if v), len(syms)))

def selftest():
    fx = json.load(open(os.path.join(FIX, "legs-fixture.json")))
    ws = json.load(open(os.path.join(APP, "data/pm.json")))["performance"]["windows"]
    legs = [(w, ([datetime.date.fromisoformat(d) for d in s["dates"]], s["closes"])) for w, s in zip(fx["weights"], fx["legs"])]
    bad = []
    for rule, want in fx["expected"].items():
        p = path(legs, rule); got = windows(p, ws)
        for k, v in want["values"].items():
            if (v is None) != (got[k] is None) or (v is not None and abs(v - got[k]) > 1e-9): bad.append("%s %s: got %r, wanted %r" % (rule, k, got[k], v))
        if abs(vol1y(p) - want["vol1y"]) > 1e-9: bad.append("%s vol1y" % rule)
    # a hand-checked case: one leg, three closes
    d = [datetime.date(2026, 11, 3), datetime.date(2026, 11, 4), datetime.date(2026, 11, 5)]
    p = path([(100.0, (d, [100.0, 110.0, 99.0]))], "monthly")
    if abs(windows(p, ws)["today"] - (-0.1)) > 1e-12: bad.append("today of 110 -> 99")
    if windows(p, ws)["y1"] is not None: bad.append("a window the history does not reach must be None")
    if path([(60.0, (d, [1, 2, 3])), (30.0, (d, [1, 2, 3]))], "daily") is not None: bad.append("weights not adding to 100 must give no path")
    if path([(50.0, (d, [1, 2, 3])), (50.0, None)], "daily") is not None: bad.append("a leg with no history must give no path")
    c = parse_history(open(os.path.join(os.path.dirname(FIX), "quotes/yahoo-chart.json"), encoding="utf-8").read())
    if c is None or len(c[0]) != 30 or c[1][-1] <= 0: bad.append("parse_history on the chart fixture")
    for m in bad: print("FAIL " + m)
    print("selftest: %s" % ("FAILED" if bad else "ok"))
    return 1 if bad else 0

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(selftest())
    main(sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(APP, "data/pm-performance.json"))
