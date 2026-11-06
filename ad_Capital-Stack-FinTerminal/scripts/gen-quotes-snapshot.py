#!/usr/bin/env python3
"""gen-quotes-snapshot.py: the baked quote snapshot (data/quotes-snapshot.json) of the HG futures board, EQ Exchange rates and EQ Commodity prices.

    python3 scripts/gen-quotes-snapshot.py [--out data/quotes-snapshot.json]     fetch every declared symbol and write the snapshot
    python3 scripts/gen-quotes-snapshot.py --selftest                            parse the response fixture and assert the derived numbers

Reads data/quotes.json (symbols, contract calendar, FX legs); names no instrument of its own. A symbol the source does not answer is absent (the app shows
'n/a (no free source)'); nothing is typed or estimated. Every quote carries the source's own timestamp (`asof`, epoch seconds); the snapshot names the source and its delay.
"""
import datetime, json, os, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
FIX = os.path.join(APP, "app/src/test/resources/quotes")

def parse_chart(text):
    """Yahoo chart answer -> the quote record, or None. last = the source's market price; prev = the previous daily close; w1 / m1 / ytd = % change of the daily close over 5 / 21 trading days and since the last close of the year before."""
    d = json.loads(text)
    res = (d.get("chart") or {}).get("result")
    if not res: return None
    r = res[0]; m = r["meta"]
    ts = r.get("timestamp") or []
    q = (r.get("indicators", {}).get("quote") or [{}])[0]
    cl = [(t, c) for t, c in zip(ts, q.get("close") or []) if c is not None]
    last = m.get("regularMarketPrice")
    if last is None and cl: last = cl[-1][1]
    if last is None: return None
    closes = [c for _, c in cl]
    asof = m.get("regularMarketTime") or (cl[-1][0] if cl else None)
    if asof is None: return None
    # the last bar is the session in progress when its date is the quote's date: the previous close is the bar before it
    same = bool(cl) and datetime.datetime.fromtimestamp(cl[-1][0], datetime.timezone.utc).date() == datetime.datetime.fromtimestamp(asof, datetime.timezone.utc).date()
    base = closes[:-1] if same else closes
    prev = base[-1] if base else None
    def pct(ago):
        return None if len(base) < ago or base[-ago] in (None, 0) else (last / base[-ago] - 1) * 100
    year = datetime.datetime.fromtimestamp(asof, datetime.timezone.utc).year
    ytdbase = [c for t, c in (cl[:-1] if same else cl) if datetime.datetime.fromtimestamp(t, datetime.timezone.utc).year < year]
    o = {"last": round(last, 6), "asof": int(asof), "exchange": m.get("fullExchangeName") or m.get("exchangeName") or "", "currency": m.get("currency") or "", "name": m.get("longName") or m.get("shortName") or ""}
    if prev not in (None, 0):
        o["prev"] = round(prev, 6); o["chg"] = round(last - prev, 6); o["pct"] = round((last / prev - 1) * 100, 4)
    for k, v in (("w1", pct(5)), ("m1", pct(21)), ("ytd", (last / ytdbase[-1] - 1) * 100 if ytdbase and ytdbase[-1] else None)):
        if v is not None: o[k] = round(v, 4)
    if m.get("regularMarketVolume"): o["vol"] = int(m["regularMarketVolume"])
    return o

def get(url, ua, tries=2):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": ua}), timeout=60) as r: return r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            if e.code in (400, 404): return None
            time.sleep(1.5 * (i + 1))
        except Exception:
            time.sleep(1.5 * (i + 1))
    return None

def contract_candidates(root, months, codes, today, count):
    """The next `count` (month code, year) pairs of the root's listed months from the current month on."""
    out = []; y, mth = today.year, today.month
    inv = {v: k for k, v in codes.items()}
    while len(out) < count and y < today.year + 6:
        c = inv[mth]
        if c in months: out.append((c, y))
        mth += 1
        if mth == 13: mth, y = 1, y + 1
    return out

def symbols(q, today):
    codes = q["month_codes"]; s = []
    for c in q["fx"]["currencies"]:
        if c.get("symbol"): s.append(c["symbol"])
    for cl in q["futures"]["classes"]:
        for r in cl["roots"]:
            if r.get("front"): s.append(r["front"])
            if not r.get("suffix"): continue
            for code, y in contract_candidates(r["root"], r["months"], codes, today, r["listed"] + 1):
                s.append("%s%s%02d.%s" % (r.get("yahoo_root", r["root"]), code, y % 100, r["suffix"]))
    return list(dict.fromkeys(s))

def main(out):
    q = json.load(open(os.path.join(APP, "data/quotes.json")))
    today = datetime.date.today()
    syms = symbols(q, today)
    src = q["source"]
    def one(sym):
        t = get(src["url"].format(symbol=sym), src["user_agent"])
        try: return sym, (parse_chart(t) if t else None)
        except Exception: return sym, None
    with ThreadPoolExecutor(8) as ex: got = dict(ex.map(one, syms))
    quotes = {k: v for k, v in got.items() if v}
    snap = {"_doc": "GENERATED by scripts/gen-quotes-snapshot.py from data/quotes.json (do not edit). One record per symbol the source answered: last, previous daily close, change, %, 1W / 1M / YTD % of the daily close, volume, the source's timestamp (asof, epoch seconds), exchange, currency, name. A symbol the source did not answer is absent: the app shows 'n/a (no free source)'. Bid / ask, open interest and settlement are not carried by the source.",
            "generated": today.isoformat(), "source": src["label"], "delay": src["delay"], "quotes": quotes}
    json.dump(snap, open(out, "w"), separators=(",", ":"), ensure_ascii=False)
    print("gen-quotes-snapshot: %d of %d symbols answered, %d bytes" % (len(quotes), len(syms), os.path.getsize(out)))

def selftest():
    o = parse_chart(open(os.path.join(FIX, "yahoo-chart.json"), encoding="utf-8").read())
    bad = []
    def eq(n, a, b):
        if a != b: bad.append("%s: got %r, wanted %r" % (n, a, b))
    eq("last", o["last"], 91.09); eq("prev is the bar before the session in progress", round(o["prev"], 2), 90.52); eq("exchange", o["exchange"], "NY Mercantile"); eq("name", o["name"], "Crude Oil Dec 26"); eq("currency", o["currency"], "USD")
    eq("chg = last - prev", round(o["chg"], 6), round(o["last"] - o["prev"], 6)); eq("pct", round(o["pct"], 4), round((o["last"] / o["prev"] - 1) * 100, 4))
    eq("asof", o["asof"], 1793973020); eq("vol", o["vol"], 105147)
    eq("none on an error answer", parse_chart('{"chart":{"result":null,"error":{"code":"Not Found"}}}'), None)
    for m in bad: print("FAIL " + m)
    print("selftest: %s" % ("FAILED" if bad else "ok"))
    return 1 if bad else 0

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(selftest())
    main(sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(APP, "data/quotes-snapshot.json"))
