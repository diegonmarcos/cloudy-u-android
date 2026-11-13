#!/usr/bin/env python3
"""gen-perf-portfolios.py: the RUNTIME dataset of EQ > Performance (factor portfolios) and FI (bond groups) (#1722).

    python3 scripts/gen-perf-portfolios.py            fetch the publishers' files and write the dataset
    python3 scripts/gen-perf-portfolios.py --check    validate the dataset that is on disk against the declaration (no network)

Reads the DECLARATION data/portfolios.json (which portfolios, which fund or weighting scheme, which benchmarks) and writes
data/fleet/app/com.diegonmarcos.capitalfinterm/perf-portfolios.json (repo root): the fleet data framework serves it to the app at runtime (Cloud Store,
libs:fleet-data-client); it is never baked into the APK. Only what the publishers print is written: constituents and weights in %. No price and no
performance is stored: the app asks its quote source (data/quotes.json `source`) for each constituent's `symbol` when the page opens.

Kinds of portfolio (declaration `kind`):
  etf           a fund's published holdings, largest first (`top`: only the N largest, rescaled to 100 %); sector = the Select Sector SPDR fund that holds the stock
  countries_dm  the declared countries, one weight per declared scheme: gdp (IMF nominal GDP), usd_index (the declared DXY basket, the euro split by GDP),
                liquidity (market value by country in the declared international treasury fund)
  countries_em  the countries of the declared EM government bond funds, at each fund's country weights (a product page's geography breakdown, or the
                holdings summed by issuing country)
  funds         the declared funds, equal weight
  union         the declared member portfolios, each at the same weight, its members at their default-scheme weights inside it
"""
import datetime, html, io, json, os, re, sys, urllib.request, zipfile
from xml.etree import ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
ROOT = os.path.dirname(APP)
DECL = os.path.join(APP, "data/portfolios.json")
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}
KEEP = {"PLC", "ADR", "REIT", "NV", "SA", "SE", "AG", "USA", "UK", "US", "IBM", "AT&T", "CVS", "HCA", "KLA", "AMD", "GE", "HP", "RTX", "UPS", "CME", "EOG", "PNC", "CSX", "MSCI", "NVR", "NRG",
        "PPL", "DTE", "CMS", "AES", "EQT", "LKQ", "APA", "KKR", "3M", "S&P", "TJX", "ADP", "BNY", "CBRE", "CDW", "DR", "EPAM", "FMC", "HD", "II", "III", "ITT", "JPM", "L3HARRIS", "MGM", "NXP", "PG&E",
        "RPM", "SBA", "TE", "UDR", "VF", "WEC", "XPO", "AFLAC", "AON", "CL", "A", "B", "C"}


def out_path(decl):
    return os.path.join(ROOT, "data/fleet/app/com.diegonmarcos.capitalfinterm/%s.json" % decl["dataset"])


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def title(s):
    """A publisher's upper-case name in reading case; short all-capital tokens are kept."""
    s = re.sub(r"\s+", " ", (s or "").strip())
    if s != s.upper():
        return s
    out = []
    for w in s.split(" "):
        core = w.strip(".,()")
        if core in KEEP or (len(core) <= 2 and core.isalpha() and core not in {"OF", "DE", "LA", "CO", "TO", "ON", "IN", "CL"}):
            out.append(w)
        elif "&" in w or "." in core:
            out.append(w)
        else:
            out.append("-".join(p.capitalize() for p in w.split("-")))
    return " ".join(out).replace(" CL A", " Class A").replace(" CL B", " Class B").replace(" CL C", " Class C")


def xlsx_rows(blob):
    """The first sheet of a workbook as rows of strings (no dependency: the zip's own XML)."""
    z = zipfile.ZipFile(io.BytesIO(blob))
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", ns):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % ns["m"])))
    rows = []
    for row in ET.fromstring(z.read("xl/worksheets/sheet1.xml")).iter("{%s}row" % ns["m"]):
        cells = {}
        for c in row.findall("m:c", ns):
            col = re.match(r"[A-Z]+", c.get("r")).group(0)
            idx = 0
            for ch in col:
                idx = idx * 26 + ord(ch) - 64
            v = c.find("m:v", ns)
            if c.get("t") == "s" and v is not None:
                val = shared[int(v.text)]
            elif c.get("t") == "inlineStr":
                val = "".join(t.text or "" for t in c.iter("{%s}t" % ns["m"]))
            else:
                val = v.text if v is not None else ""
            cells[idx - 1] = val
        rows.append([cells.get(i, "") for i in range(max(cells) + 1)] if cells else [])
    return rows


def ssga(decl, etf, cache={}):
    """One SPDR fund's daily holdings: (fund name, as-of date, header index by column name, the holding rows)."""
    if etf in cache:
        return cache[etf]
    url = decl["publishers"]["ssga"]["url"].replace("{etf}", etf.lower())
    rows = xlsx_rows(get(url))
    name = next((r[1] for r in rows if r and r[0].startswith("Fund Name")), etf).replace("®", "").replace("State Street ", "")
    as_of = ""
    for r in rows:
        m = re.search(r"(\d{1,2})-([A-Za-z]{3})-(\d{4})", " ".join(r)) if r and r[0].startswith("Holdings") else None
        if m:
            as_of = "%s-%02d-%02d" % (m.group(3), MONTHS[m.group(2)[:3].title()], int(m.group(1)))
    hi = next(i for i, r in enumerate(rows) if r and r[0] == "Name" and "Weight" in r)
    ci = {c: i for i, c in enumerate(rows[hi])}
    body = []
    for r in rows[hi + 1:]:
        if not r or not r[0].strip():
            break
        body.append(r + [""] * (len(ci) - len(r)))
    cache[etf] = (name, as_of, ci, body, url)
    return cache[etf]


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def equity_rows(decl, etf):
    """A fund's equity holdings as (ticker, name, weight %), largest first; cash, futures and blank tickers are left out."""
    name, as_of, ci, body, url = ssga(decl, etf)
    out = []
    for r in body:
        t = r[ci["Ticker"]].strip()
        w = fnum(r[ci["Weight"]])
        if w is None or w <= 0 or not re.fullmatch(r"[A-Z][A-Z0-9.]{0,6}", t) or t in ("CASH", "USD"):
            continue
        if "CASH" in r[0].upper() or "FUTURE" in r[0].upper() or "MINI" in r[0].upper().split():
            continue
        out.append((t, title(r[0]), w))
    out.sort(key=lambda x: -x[2])
    return name, as_of, url, out


def sector_map(decl):
    m = {}
    for etf, sector in decl["sector_etfs"].items():
        for t, _, _ in equity_rows(decl, etf)[3]:
            m.setdefault(t, sector)
    return m


def build_etf(decl, g, sectors):
    name, as_of, url, rows = equity_rows(decl, g["etf"])
    total = len(rows)
    top = g.get("top")
    shown = rows[: top or decl["max_holdings"]]
    scale = 100.0 / sum(w for _, _, w in shown) if top else 1.0
    cons = [{"ticker": t, "symbol": t.replace(".", "-"), "name": n, "bucket": sectors.get(t, "Other"), "weights": {"etf": round(w * scale, 4)}} for t, n, w in shown]
    return {
        "source": "%s (%s) daily holdings, %s" % (name, g["etf"], "State Street Global Advisors"), "source_url": url, "as_of": as_of,
        "count": total, "shown": len(cons), "constituents": cons,
    }


def country_of(decl, isin, ccy, universe=None):
    cs = decl["countries"]
    cc = (isin or "")[:2].upper()
    if cc in cs and (universe is None or cc in universe):
        return cc
    hits = [k for k, v in cs.items() if v["ccy"] == ccy and (universe is None or k in universe)]
    return hits[0] if len(hits) == 1 else None


def bond_country_weights(decl, etf, universe=None):
    """A bond fund's weight by issuing country (the ISIN's country, else the single country of its currency): {cc: weight %}, the as-of date, the url."""
    name, as_of, ci, body, url = ssga(decl, etf)
    out = {}
    for r in body:
        w = fnum(r[ci["Weight"]])
        if w is None or w <= 0:
            continue
        cc = country_of(decl, r[ci["ISIN"]] if "ISIN" in ci else r[ci["Identifier"]], r[ci["Local Currency"]].strip(), universe)
        if cc:
            out[cc] = out.get(cc, 0.0) + w
    return name, as_of, url, out


def imf_gdp(decl, ccs, year):
    """The IMF nominal GDP (USD bn) of [ccs] in [year], from the figures the CB page carries (the declared file and path)."""
    pub = decl["publishers"]["imf"]
    node = json.load(open(os.path.join(APP, pub["file"]), encoding="utf-8"))
    for k in pub["path"].split("."):
        node = node[k]
    return pub["file"] + " " + pub["path"], {c: float(node[c][year]) for c in ccs}


def norm(ws):
    s = sum(ws.values())
    return {k: round(v * 100.0 / s, 4) for k, v in ws.items() if v > 0} if s > 0 else {}


def fx_symbol(decl, ccy):
    return "" if ccy == "USD" else decl["fx"]["symbol"].replace("{ccy}", ccy)


def build_dm(decl, g):
    cs = decl["countries"]
    ccs = [c["cc"] for c in g["constituents"]]
    weights, notes = {}, {}
    for s in g["schemes"]:
        if s["id"] == "gdp":
            url, gdp = imf_gdp(decl, ccs, s["year"])
            weights["gdp"] = norm(gdp)
            notes["gdp"] = {"source": decl["publishers"]["imf"]["label"] + ", " + s["year"], "source_url": "", "as_of": s["year"], "file": url}
        elif s["id"] == "usd_index":
            _, gdp = imf_gdp(decl, ccs, next(x["year"] for x in g["schemes"] if x["id"] == "gdp"))
            w = {}
            for ccy, bw in s["basket"].items():
                members = [c for c in ccs if cs[c]["ccy"] == ccy]
                tot = sum(gdp[c] for c in members)
                for c in members:
                    w[c] = bw * gdp[c] / tot
            weights["usd_index"] = norm(w)
            notes["usd_index"] = {"source": "ICE US Dollar Index currency weights (declared basket)", "source_url": "", "as_of": "1999"}
        elif s["id"] == "liquidity":
            # the declared fund is international: the US is not in its index (a stray dollar line is cash, not a treasury)
            name, as_of, url, w = bond_country_weights(decl, s["etf"], set(ccs) - {"US"})
            weights["liquidity"] = norm({c: w.get(c, 0.0) for c in ccs})
            notes["liquidity"] = {"source": "%s (%s) daily holdings, State Street Global Advisors" % (name, s["etf"]), "source_url": url, "as_of": as_of}
    cons = []
    for c in g["constituents"]:
        cc = c["cc"]
        cons.append({"ticker": cc, "symbol": c["symbol"], "name": cs[cc]["name"], "bucket": c["bucket"], "proxy": c["proxy"], "note": c.get("note", ""),
                     "weights": {sid: w[cc] for sid, w in weights.items() if cc in w}})
    d = notes[g["default_scheme"]]
    return {"source": d["source"], "source_url": d["source_url"], "as_of": d["as_of"], "scheme_sources": notes, "count": len(cons), "shown": len(cons), "constituents": cons}


def ishares_geography(decl, s):
    url = decl["publishers"]["ishares_page"]["url"].replace("{id}", s["id_ishares"]).replace("{slug}", s["slug"])
    t = html.unescape(get(url).decode("utf-8", "replace"))
    i = t.index('"geography":{"name":"geography"')
    seg = t[i:i + 20000]
    as_of = re.search(r'"asOf":\{"name":"asOf".*?"value":(\d{8})', seg).group(1)
    vals = json.loads(re.search(r'"fund":\{"name":"fund".*?"value":(\[[^\]]*\])', seg).group(1))
    names = json.loads(re.search(r'"type":\{"name":"type".*?"value":(\[[^\]]*\])', seg).group(1))
    by_name = {}
    for cc, c in decl["countries"].items():
        for n in [c["name"]] + c.get("alias", []):
            by_name[n.lower()] = cc
    out, other = {}, 0.0
    for n, v in zip(names, vals):
        cc = by_name.get(n.lower())
        if cc:
            out[cc] = out.get(cc, 0.0) + float(v)
        else:
            other += float(v)
    return url, "%s-%s-%s" % (as_of[:4], as_of[4:6], as_of[6:]), out, round(other, 2)


def build_em(decl, g):
    cs = decl["countries"]
    weights, notes = {}, {}
    for s in g["schemes"]:
        if s["publisher"] == "ishares_page":
            url, as_of, w, other = ishares_geography(decl, s)
            weights[s["id"]] = {k: round(v, 4) for k, v in w.items()}
            notes[s["id"]] = {"source": "%s country weights, %s" % (s["fund"], decl["publishers"]["ishares_page"]["label"]), "source_url": url, "as_of": as_of, "unlisted": other}
        else:
            name, as_of, url, w = bond_country_weights(decl, s["etf"])
            weights[s["id"]] = {k: round(v, 4) for k, v in w.items()}
            notes[s["id"]] = {"source": "%s (%s) daily holdings summed by issuing country, State Street Global Advisors" % (name, s["etf"]), "source_url": url, "as_of": as_of,
                              "unlisted": round(100.0 - sum(w.values()), 2)}
    # an EM fund's stray developed-market lines (cash, a supranational's bond) are not EM government bonds
    dm = {c["cc"] for x in decl["groups"] if x["kind"] == "countries_dm" for c in x["constituents"]}
    weights = {sid: {c: v for c, v in w.items() if c not in dm and v >= 0.005} for sid, w in weights.items()}
    ccs = sorted({c for w in weights.values() for c in w}, key=lambda c: -max(w.get(c, 0.0) for w in weights.values()))
    cons = []
    for cc in ccs:
        ccy = cs[cc]["ccy"]
        cons.append({"ticker": cc, "symbol": fx_symbol(decl, ccy), "name": cs[cc]["name"], "bucket": ccy, "basis": "fx" if ccy != "USD" else "usd",
                     "weights": {sid: w[cc] for sid, w in weights.items() if cc in w}})
    d = notes[g["default_scheme"]]
    return {"source": d["source"], "source_url": d["source_url"], "as_of": d["as_of"], "scheme_sources": notes, "count": len(cons), "shown": len(cons), "constituents": cons}


def build_funds(decl, g, today):
    n = len(g["constituents"])
    cons = [dict(c, weights={"equal": round(100.0 / n, 4)}) for c in g["constituents"]]
    return {"source": "Declared fund list (data/portfolios.json), equal weight", "source_url": "", "as_of": today, "count": n, "shown": n, "constituents": cons}


def build_union(decl, g, built, today):
    members = g["members"]
    share = 100.0 / len(members)
    by_key = {x["key"]: x for x in decl["groups"]}
    cons = []
    for k in members:
        m, md = built[k], by_key[k]
        sid = md["default_scheme"]
        tot = sum(c["weights"].get(sid, 0.0) for c in m["constituents"])
        for c in m["constituents"]:
            w = c["weights"].get(sid, 0.0)
            if w <= 0 or tot <= 0:
                continue
            cons.append(dict({kk: vv for kk, vv in c.items() if kk != "weights"}, ticker=c["ticker"], bucket=md["label"], group=k, weights={"equal_groups": round(w * share / tot, 4)}))
    return {"source": "The six bond groups, one sixth each", "source_url": "", "as_of": today, "count": len(cons), "shown": len(cons), "constituents": cons}


def check(decl, doc):
    """The dataset against its declaration; a list of failures (empty = valid)."""
    bad = []
    if not isinstance(doc.get("schemaVersion"), int):
        bad.append("schemaVersion is not an integer")
    ps = doc.get("portfolios", {})
    for g in decl["groups"]:
        p = ps.get(g["key"])
        if p is None:
            bad.append("portfolio %s is not in the dataset" % g["key"]); continue
        cons = p.get("constituents") or []
        if not cons:
            bad.append("portfolio %s has no constituent" % g["key"]); continue
        for f in ("source", "as_of"):
            if not p.get(f):
                bad.append("portfolio %s lacks %s" % (g["key"], f))
        for s in g["schemes"]:
            ws = [c["weights"].get(s["id"], 0.0) for c in cons]
            tot = sum(ws)
            if any(w < 0 for w in ws):
                bad.append("portfolio %s scheme %s has a negative weight" % (g["key"], s["id"]))
            lo = 20.0 if g["kind"] == "etf" and not g.get("top") else 80.0 if g["kind"] == "countries_em" else 99.5
            if not (lo <= tot <= 100.5):
                bad.append("portfolio %s scheme %s weights sum to %.2f %% (expected %.1f to 100.5)" % (g["key"], s["id"], tot, lo))
        for c in cons:
            if not c.get("ticker") or not c.get("name"):
                bad.append("portfolio %s has a constituent without ticker or name" % g["key"])
            if set(c["weights"]) - {s["id"] for s in g["schemes"]}:
                bad.append("portfolio %s constituent %s names an undeclared scheme" % (g["key"], c.get("ticker")))
        if g["kind"] == "etf" and g.get("top") and len(cons) != g["top"]:
            bad.append("portfolio %s shows %d holdings, its declared top is %d" % (g["key"], len(cons), g["top"]))
        if len({c["ticker"] + "|" + c.get("group", "") for c in cons}) != len(cons):
            bad.append("portfolio %s repeats a constituent" % g["key"])
    for k in ps:
        if k not in {g["key"] for g in decl["groups"]}:
            bad.append("dataset portfolio %s is not declared" % k)
    if re.search(r'"(price|last|perf|return|pct|ytd)"\s*:', json.dumps(doc)):
        bad.append("the dataset stores a price or a performance")
    return bad


def main():
    decl = json.load(open(DECL, encoding="utf-8"))
    out = out_path(decl)
    if "--check" in sys.argv:
        bad = check(decl, json.load(open(out, encoding="utf-8")))
        for b in bad:
            print("FAIL " + b)
        print("perf-portfolios: %s" % ("%d failure(s)" % len(bad) if bad else "valid"))
        sys.exit(1 if bad else 0)
    today = datetime.datetime.utcnow().strftime("%Y-%m-%d")
    sectors = sector_map(decl)
    built = {}
    for g in decl["groups"]:
        k = g["kind"]
        if k == "etf":
            built[g["key"]] = build_etf(decl, g, sectors)
        elif k == "countries_dm":
            built[g["key"]] = build_dm(decl, g)
        elif k == "countries_em":
            built[g["key"]] = build_em(decl, g)
        elif k == "funds":
            built[g["key"]] = build_funds(decl, g, today)
    for g in decl["groups"]:
        if g["kind"] == "union":
            built[g["key"]] = build_union(decl, g, built, today)
    doc = {
        "schemaVersion": 1,
        "_doc": "#1722 the constituents and weights (%) of the performance portfolios of Capital-Stack-FinTerminal: EQ > Performance (ten factor portfolios, from the funds' published holdings) and FI (the bond groups: government bonds by country under each declared weighting scheme, the rating groups as listed funds). Served at RUNTIME by the fleet data framework (Cloud Store, libs:fleet-data-client), never baked into an APK. Written by ad_Capital-Stack-FinTerminal/scripts/gen-perf-portfolios.py from the declaration data/portfolios.json and the publishers' own files (`source`, `source_url`, `as_of` per portfolio and per scheme); do not edit by hand. `weights` is the constituent's weight in % under each scheme; `symbol` is its symbol at the app's quote source (empty = no listed instrument). No price or performance is stored here.",
        "generated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%MZ"),
        "portfolios": {g["key"]: dict({"name": g["label"], "kind": g["kind"]}, **built[g["key"]]) for g in decl["groups"]},
    }
    bad = check(decl, doc)
    if bad:
        for b in bad:
            print("FAIL " + b)
        sys.exit(1)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    # one constituent per line: a reviewable diff without a 10 000-line file
    lines = {}
    for k, p in doc["portfolios"].items():
        for i, c in enumerate(p["constituents"]):
            token = "@@%s@@%d@@" % (k, i)
            lines[json.dumps(token)] = json.dumps(c, ensure_ascii=False)
            p["constituents"][i] = token
    text = json.dumps(doc, ensure_ascii=False, indent=1)
    for token, line in lines.items():
        text = text.replace(token, line)
    doc = json.loads(text)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    for k, p in doc["portfolios"].items():
        print("%-14s %4d shown of %4d  as of %s  %s" % (k, p["shown"], p["count"], p["as_of"], p["source"][:70]))


if __name__ == "__main__":
    main()
