#!/usr/bin/env python3
"""Generate data/store.json (the Store's catalogue) from data/fwa.md (the directory of FWA award-winning web projects).

The markdown table is the ONE source; store.json is its generated, committed copy (DO NOT EDIT it by hand). The app's
network security config (app/src/main/res/xml/network_security_config.xml) is generated from the same rows: cleartext stays
forbidden for everything, and is allowed ONLY for the hosts of the Store entries whose link is http (an http-only world
would otherwise fail in Galaxy with net::ERR_CLEARTEXT_NOT_PERMITTED). There is no global cleartext allowance.
  python3 data/gen-store.py           write data/store.json and the network security config
  python3 data/gen-store.py --check   exit 1 when either file is not exactly what fwa.md generates (drift)
Each entry also carries its link's `status` (online | offline | unverified) and `checked` date from data/link-status.json
(written by data/probe-links.py) and its `wayback` link (the Wayback Machine copy, so no address is spelled in Kotlin); a link the probe has not recorded is `unverified` with no date, never guessed online.
Every row must carry a link, a known Type, 2D/3D and VR value; anything else fails loudly rather than being guessed.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC, OUT = os.path.join(HERE, "fwa.md"), os.path.join(HERE, "store.json")
LINKS = os.path.join(HERE, "link-status.json")
RULES = os.path.join(HERE, "world-rules.json")   # per-world launch rules (query string Galaxy appends), keyed by entry id
MODELS = os.path.join(HERE, "models.json")   # #1701 the 3D models of the Store (declared data), merged in after the web worlds
STATUSES = {"online", "offline", "unverified"}
WAYBACK = "https://web.archive.org/web/2020/"   # an entry's archived copy is WAYBACK + its url (Galaxy's offline card links it)
NSC = os.path.join(HERE, "..", "app", "src", "main", "res", "xml", "network_security_config.xml")
COLUMNS = ["Name", "Type", "2D/3D", "Framework", "Stack", "VR Compatibility", "Description"]
TYPES = {"Website", "Game", "Others"}
DIMENSIONS = {"2D", "3D", "2D/3D", "3D/Video"}
VR = {"Yes": True, "No": False}


def slug(name):
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", name.lower())).strip("-")


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def load_links(path=LINKS):
    if not os.path.exists(path): return {}
    links = json.load(open(path, encoding="utf-8")).get("links", {})
    for url, v in links.items():
        if v.get("status") not in STATUSES: raise SystemExit("link-status.json: %s status %r not in %s" % (url, v.get("status"), sorted(STATUSES)))
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(v.get("checked", ""))): raise SystemExit("link-status.json: %s checked %r is not a date" % (url, v.get("checked")))
    return links


def load_rules(path=RULES):
    if not os.path.exists(path): return {}
    rules = json.load(open(path, encoding="utf-8")).get("rules", {})
    for sid, r in rules.items():
        q = r.get("query", "")
        if not re.fullmatch(r"[A-Za-z0-9_.=&-]+", q): raise SystemExit("world-rules.json: %s query %r is not a plain query string" % (sid, q))
        if not isinstance(r.get("off", False), bool): raise SystemExit("world-rules.json: %s off %r is not true/false" % (sid, r.get("off")))
    return rules


def load_models(path=MODELS):
    """The Store's 3D models (data/models.json): each becomes an entry of Type Model carrying its `model` (GLB url + SHA-256 + size, photo, original)."""
    if not os.path.exists(path): return []
    doc = json.load(open(path, encoding="utf-8"))
    out, seen = [], set()
    for m in doc.get("models", []):
        mid = m.get("id", "")
        if not re.fullmatch(r"[a-z0-9-]+", mid) or mid in seen: raise SystemExit("models.json: id %r is empty, not a slug or duplicated" % mid)
        seen.add(mid)
        glb, photo = m.get("glb", {}), m.get("photo", {})
        if not str(glb.get("url", "")).startswith("https://"): raise SystemExit("models.json: %s glb.url must be https" % mid)
        if not re.fullmatch(r"[0-9a-f]{64}", str(glb.get("sha256", ""))): raise SystemExit("models.json: %s glb.sha256 is not a SHA-256" % mid)
        if not isinstance(glb.get("size"), int) or glb["size"] <= 0: raise SystemExit("models.json: %s glb.size is not a byte count" % mid)
        if not str(photo.get("url", "")).startswith("https://"): raise SystemExit("models.json: %s photo.url must be https" % mid)
        for k in ("name", "framework", "description", "format"):
            if not m.get(k): raise SystemExit("models.json: %s has no %s" % (mid, k))
        out.append({
            "id": mid, "name": m["name"], "url": doc["repo_url"], "type": "Model", "dimension": "3D", "framework": m["framework"],
            "stack": list(m.get("stack", [])), "vr": False, "description": m["description"], "status": "online", "checked": doc["checked"],
            "wayback": "",
            "model": {"glb": glb["url"], "sha256": glb["sha256"], "size": glb["size"], "photo": photo["url"], "format": m["format"],
                      "software": m.get("software", ""), "units": m.get("units", "m"), "parts": m.get("parts", 0), "triangles": m.get("triangles", 0),
                      "original": m.get("original", {})},
        })
    return out


def generate(text, links=None, rules=None, models=None):
    links = load_links() if links is None else links
    rules = load_rules() if rules is None else rules
    models = load_models() if models is None else models
    rows = [l for l in text.splitlines() if l.strip().startswith("|")]
    if not rows or cells(rows[0]) != COLUMNS:
        raise SystemExit("fwa.md: the header is not %s" % " | ".join(COLUMNS))
    entries, seen = [], set()
    for n, line in enumerate(rows[2:], start=1):
        c = cells(line)
        if len(c) != len(COLUMNS):
            raise SystemExit("fwa.md row %d: %d cells, want %d" % (n, len(c), len(COLUMNS)))
        m = re.fullmatch(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", c[0])
        if not m:
            raise SystemExit("fwa.md row %d: Name %r is not [name](url)" % (n, c[0]))
        name, url = m.group(1), m.group(2)
        if c[1] not in TYPES: raise SystemExit("fwa.md row %d: Type %r not in %s" % (n, c[1], sorted(TYPES)))
        if c[2] not in DIMENSIONS: raise SystemExit("fwa.md row %d: 2D/3D %r not in %s" % (n, c[2], sorted(DIMENSIONS)))
        if c[5] not in VR: raise SystemExit("fwa.md row %d: VR %r not Yes/No" % (n, c[5]))
        sid = slug(name)
        if sid in seen: raise SystemExit("fwa.md row %d: duplicate id %s" % (n, sid))
        seen.add(sid)
        entries.append({
            "id": sid, "name": name, "url": url, "type": c[1], "dimension": c[2], "framework": c[3],
            "stack": [s.strip() for s in c[4].split(",") if s.strip()], "vr": VR[c[5]], "description": c[6],
            "status": links.get(url, {}).get("status", "unverified"), "checked": links.get(url, {}).get("checked", ""),
            "wayback": WAYBACK + url,
        })
    if not entries: raise SystemExit("fwa.md: no rows")
    for e in entries:
        r = rules.get(e["id"])
        if r is None or r.get("off"): continue   # an "off" rule stays declared (with its why) but the world loads its plain address
        e["launch_query"] = r["query"]
        if "cpu_query" in r: e["launch_query_cpu"] = r["cpu_query"]
    for m in models:
        if m["id"] in seen: raise SystemExit("models.json: id %s is also a web world of fwa.md" % m["id"])
        seen.add(m["id"])
    entries += models
    unknown = sorted(set(rules) - seen)
    if unknown: raise SystemExit("world-rules.json: unknown world id(s) %s" % unknown)
    return {
        "_doc": "GENERATED by data/gen-store.py from data/fwa.md - DO NOT EDIT. The Store's catalogue and the worlds Galaxy can load.",
        "source": "data/fwa.md",
        "entries": entries,
    }


def render(text):
    return json.dumps(generate(text), indent=2, ensure_ascii=False) + "\n"


def cleartext_hosts(store):
    """The hosts of the http-only Store entries, sorted and unique: the only hosts allowed cleartext."""
    return sorted({re.match(r"http://([^/:]+)", e["url"]).group(1).lower() for e in store["entries"] if e["url"].startswith("http://")})


def render_nsc(text):
    hosts = cleartext_hosts(generate(text))
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- GENERATED by data/gen-store.py from data/fwa.md - DO NOT EDIT. Cleartext is forbidden; only the hosts of the",
             "     http-only Store entries (and their subdomains, where a world redirects to www) may load over http in Galaxy. -->",
             "<network-security-config>",
             '    <base-config cleartextTrafficPermitted="false" />']
    if hosts:
        lines.append('    <domain-config cleartextTrafficPermitted="true">')
        lines += ['        <domain includeSubdomains="true">%s</domain>' % h for h in hosts]
        lines.append("    </domain-config>")
    lines.append("</network-security-config>")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    text = open(SRC, encoding="utf-8").read()
    outputs = [(OUT, render(text), "data/store.json"), (NSC, render_nsc(text), "app/src/main/res/xml/network_security_config.xml")]
    if "--check" in sys.argv[1:]:
        for path, want, name in outputs:
            have = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
            if have != want:
                print("DRIFT: %s is not what data/fwa.md generates; run python3 data/gen-store.py" % name); sys.exit(1)
        print("store.json and the network security config match fwa.md (%d entries)" % len(generate(text)["entries"])); sys.exit(0)
    for path, want, name in outputs:
        open(path, "w", encoding="utf-8").write(want)
        print("wrote " + name)
