#!/usr/bin/env python3
"""gen-index-holdings.py: the RUNTIME dataset of EQ > World Indices Equity > index holdings breakdown (#1663).

    python3 scripts/gen-index-holdings.py            fetch the three published holdings files and write the dataset
    python3 scripts/gen-index-holdings.py --check    validate the dataset that is on disk (no network)

Writes data/fleet/app/com.diegonmarcos.capitalfinterm/index-holdings.json (repo root): the fleet data framework serves it to the app at runtime
(Cloud Store, libs:fleet-data-client); it is never baked into the APK. Only what the publishers print is written: constituents and their weight in %.
No price and no performance is stored here: the app asks its quote source (data/quotes.json `source`) for each holding's `symbol` when the page opens.

Sources (the holdings of the index, or of the physically replicating fund that publishes its full portfolio daily):
  sp500      SPDR S&P 500 ETF Trust (SPY) daily holdings, State Street Global Advisors
  nikkei225  Nikkei Stock Average constituent weights, Nikkei Inc. (the index provider's own monthly file)
  ftse100    iShares Core FTSE 100 UCITS ETF (ISF) holdings, BlackRock
"""
import csv, datetime, io, json, os, re, sys, urllib.request, zipfile
from xml.etree import ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "data/fleet/app/com.diegonmarcos.capitalfinterm/index-holdings.json")
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

SOURCES = {
    "sp500": {
        "name": "S&P 500", "country": "USA", "index_symbol": "^GSPC", "currency": "USD",
        "source": "SPDR S&P 500 ETF Trust (SPY) daily holdings, State Street Global Advisors",
        "url": "https://www.ssga.com/us/en/intermediary/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx",
    },
    "nikkei225": {
        "name": "Nikkei 225", "country": "Japan", "index_symbol": "^N225", "currency": "JPY",
        "source": "Nikkei Stock Average constituent weights, Nikkei Inc.",
        "url": "https://indexes.nikkei.co.jp/nkave/archives/file/nikkei_stock_average_weight_en.csv",
    },
    "ftse100": {
        "name": "FTSE 100", "country": "UK", "index_symbol": "^FTSE", "currency": "GBP",
        "source": "iShares Core FTSE 100 UCITS ETF (ISF) holdings, BlackRock",
        "url": "https://www.blackrock.com/uk/individual/products/251795/ishares-ftse-100-ucits-etf-inc-fund/1472631233320.ajax?fileType=csv&fileName=ISF_holdings&dataType=fund",
    },
}
MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def title(s):
    """A publisher's upper-case company name in reading case; short all-capital tokens (plc, ADR classes, tickers) are kept."""
    s = re.sub(r"\s+", " ", s.strip())
    if s != s.upper():
        return s
    keep = {"PLC", "ADR", "REIT", "NV", "SA", "SE", "AG", "USA", "UK", "US", "HSBC", "BP", "BT", "BAE", "GSK", "RELX", "WPP", "IMI", "ICG", "LSE", "II", "III",
            "KDDI", "NTT", "TDK", "NEC", "IHI", "ENEOS", "JFE", "SMC", "ANA", "NH", "JR", "MS&AD", "TOTO", "DIC", "NSK", "JTEKT", "SUMCO", "ZOZO", "LY", "HD", "IBM",
            "AT&T", "CVS", "HCA", "KLA", "AMD", "GE", "HP", "RTX", "UPS", "CME", "EOG", "PNC", "CSX", "MSCI", "NVR", "NRG", "PPL", "DTE", "CMS", "AES", "EQT", "ONEOK", "LKQ", "APA", "KKR", "3I", "3M", "S&P", "M&G", "B&M", "L&G"}
    out = []
    for w in s.split(" "):
        core = w.strip(".,()")
        out.append(w if core in keep or (len(core) <= 2 and core.isalpha() and core not in {"OF", "DE", "LA", "CO", "TO", "ON", "IN"}) else w.capitalize() if "&" not in w and "-" not in w else "-".join(p.capitalize() for p in w.split("-")))
    return " ".join(out).replace(" Plc", " plc").replace(" Corp.", " Corp").replace(" Co.,ltd.", " Co").replace(" Inc.", " Inc")


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


def sp500(blob):
    rows = xlsx_rows(blob)
    as_of = ""
    for r in rows[:6]:
        m = re.search(r"As of (\d{1,2})-([A-Za-z]{3})-(\d{4})", " ".join(r))
        if m:
            as_of = "%s-%02d-%02d" % (m.group(3), MONTHS[m.group(2)], int(m.group(1)))
    head = next(i for i, r in enumerate(rows) if "Ticker" in r and "Weight" in r)
    h = rows[head]
    ci = {k: h.index(k) for k in ("Name", "Ticker", "Weight")}
    sec = h.index("Sector") if "Sector" in h else None
    out = []
    for r in rows[head + 1:]:
        if len(r) <= ci["Weight"]:
            continue
        t = r[ci["Ticker"]].strip()
        try:
            w = float(r[ci["Weight"]])
        except ValueError:
            continue
        if not t or t == "-" or not re.match(r"^[A-Z][A-Z.]*$", t) or w <= 0:
            continue
        out.append({"ticker": t, "symbol": t.replace(".", "-"), "name": title(r[ci["Name"]]), "sector": (r[sec].strip().strip("-") if sec is not None and len(r) > sec else ""), "weight": round(w, 4)})
    return as_of, out


def nikkei(blob):
    text = blob.decode("utf-8-sig", "replace")
    as_of, out = "", []
    for r in csv.reader(io.StringIO(text)):
        if len(r) < 6 or not re.match(r"^\d{4}/\d{2}/\d{2}$", r[0]):
            continue
        as_of = r[0].replace("/", "-")
        w = float(r[5].rstrip("%"))
        out.append({"ticker": r[1], "symbol": r[1] + ".T", "name": title(r[2]), "sector": r[4].strip(), "weight": round(w, 4)})
    return as_of, out


def ftse(blob):
    text = blob.decode("utf-8-sig", "replace")
    lines = text.splitlines()
    m = re.search(r'"(\d{1,2})/([A-Za-z]{3})/(\d{4})"', lines[0])
    as_of = "%s-%02d-%02d" % (m.group(3), MONTHS[m.group(2)], int(m.group(1))) if m else ""
    head = next(i for i, l in enumerate(lines) if l.startswith("Ticker,"))
    out = []
    for r in csv.DictReader(io.StringIO("\n".join(lines[head:]))):
        if (r.get("Asset Class") or "") != "Equity":
            continue
        t = (r.get("Ticker") or "").strip()
        try:
            w = float((r.get("Weight (%)") or "").replace(",", ""))
        except ValueError:
            continue
        if not t or w <= 0:
            continue
        # the quote source's London symbol: a trailing dot is dropped, an inner dot is a dash (BT.A -> BT-A.L, RR. -> RR.L)
        sym = t.rstrip(".").replace(".", "-") + ".L"
        out.append({"ticker": t.rstrip("."), "symbol": sym, "name": title(r.get("Name") or ""), "sector": (r.get("Sector") or "").strip(), "weight": round(w, 4)})
    return as_of, out


PARSERS = {"sp500": sp500, "nikkei225": nikkei, "ftse100": ftse}
FLOOR = {"sp500": 480, "nikkei225": 220, "ftse100": 95}


def check(doc):
    """What the app relies on: every index has real constituents, weights descending, positive, summing to at most ~100 %, unique symbols."""
    bad = []
    if doc.get("schemaVersion") != 1:
        bad.append("schemaVersion is not 1")
    for key in SOURCES:
        ix = (doc.get("indices") or {}).get(key)
        if not ix:
            bad.append("%s: missing" % key); continue
        hs = ix.get("holdings") or []
        if len(hs) < FLOOR[key]:
            bad.append("%s: %d holdings, fewer than %d" % (key, len(hs), FLOOR[key]))
        ws = [h.get("weight") for h in hs]
        if any(not isinstance(w, (int, float)) or w <= 0 for w in ws):
            bad.append("%s: a weight is not a positive number" % key)
        elif ws != sorted(ws, reverse=True):
            bad.append("%s: weights are not in descending order" % key)
        elif not 90.0 <= sum(ws) <= 100.6:
            bad.append("%s: weights sum to %.2f" % (key, sum(ws)))
        syms = [h.get("symbol") for h in hs]
        if len(set(syms)) != len(syms) or any(not s for s in syms):
            bad.append("%s: symbols are missing or repeated" % key)
        if any(not h.get("ticker") or not h.get("name") for h in hs):
            bad.append("%s: a holding has no ticker or name" % key)
        if not re.match(r"^\d{4}-\d{2}-\d{2}$", ix.get("as_of") or ""):
            bad.append("%s: as_of is not a date" % key)
        for f in ("name", "country", "index_symbol", "source", "source_url"):
            if not ix.get(f):
                bad.append("%s: no %s" % (key, f))
    return bad


def main():
    if "--check" in sys.argv:
        bad = check(json.load(open(OUT)))
        for b in bad:
            print("FAIL " + b)
        print("index-holdings: %d findings" % len(bad))
        return 1 if bad else 0
    doc = {
        "schemaVersion": 1,
        "_doc": "#1663 the constituents and index weights (%) of the indices EQ > World Indices Equity breaks down by holding: S&P 500 (USA), Nikkei 225 (Japan), FTSE 100 (UK). Served at RUNTIME by the fleet data framework (Cloud Store, libs:fleet-data-client), never baked into an APK. Written by ad_Capital-Stack-FinTerminal/scripts/gen-index-holdings.py from the publishers' own files (`source`, `source_url`, `as_of` per index); do not edit by hand. `weight` is the holding's weight in % (the treemap tile's area); `symbol` is the holding's symbol at the app's quote source, which gives the performance (the tile's colour). No price or performance is stored here.",
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
        "indices": {},
    }
    for key, s in SOURCES.items():
        as_of, hs = PARSERS[key](get(s["url"]))
        merged = {}
        for h in hs:
            if h["symbol"] in merged:
                merged[h["symbol"]]["weight"] = round(merged[h["symbol"]]["weight"] + h["weight"], 4)
            else:
                merged[h["symbol"]] = h
        hs = sorted(merged.values(), key=lambda h: -h["weight"])
        doc["indices"][key] = {
            "name": s["name"], "country": s["country"], "index_symbol": s["index_symbol"], "currency": s["currency"],
            "source": s["source"], "source_url": s["url"], "as_of": as_of,
            "count": len(hs), "weight_sum": round(sum(h["weight"] for h in hs), 2), "holdings": hs,
        }
        print("%s: %d holdings, %.2f %%, as of %s, top %s" % (key, len(hs), sum(h["weight"] for h in hs), as_of, ", ".join("%s %.2f" % (h["ticker"], h["weight"]) for h in hs[:5])))
    bad = check(doc)
    for b in bad:
        print("FAIL " + b)
    if bad:
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote " + os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
