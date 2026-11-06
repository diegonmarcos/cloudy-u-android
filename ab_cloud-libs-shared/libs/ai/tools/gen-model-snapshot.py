#!/usr/bin/env python3
"""Generates libs:ai's baked model snapshot (assets/ai/models-snapshot.json) from OpenRouter's public model metadata.

Rules live in assets/ai/model-tabs.json (tab -> modality / capability rules, the declared providers); this script only applies them.
  gen-model-snapshot.py --fetch                  read the live API (no key is needed to list models) and write the snapshot
  gen-model-snapshot.py --from-dir DIR           same, from saved API answers (models.json, images.json, videos.json, embeddings.json,
                                                 image-endpoints/<id with / as __>.json): reproducible, offline
  gen-model-snapshot.py --check                  the guard: the committed snapshot must follow the rules' shape (no tab empty, no
                                                 placeholder text, every entry complete); exits 1 otherwise
Run it at refresh time; never edit the snapshot by hand."""
import json, os, re, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
AI = os.path.join(HERE, "../src/main/assets/ai")
TABS = os.path.join(AI, "model-tabs.json")
API = "https://openrouter.ai/api/v1"
PLACEHOLDER = re.compile(r"(?i)\b(tbd|tbc|todo|fixme|placeholder|lorem|sample|example|dummy|coming soon|n/a|xxx)\b")
FIELDS = ("id", "name", "provider", "modalities", "context", "price", "link", "api")
APIS = ("chat", "images", "videos", "embeddings")


def load(p): return json.load(open(p, encoding="utf-8"))


def fetch(path):
    req = urllib.request.Request(API + path, headers={"User-Agent": "cloud-superintel-model-snapshot"})
    with urllib.request.urlopen(req, timeout=60) as r: return json.load(r)


class Source:
    def __init__(self, models, images, videos, embeddings, endpoints):
        self.models, self.images, self.videos, self.embeddings, self.endpoints = models, images, videos, embeddings, endpoints

    @staticmethod
    def live():
        cache = {}
        def endpoints(mid):
            if mid not in cache:
                try: cache[mid] = fetch("/images/models/%s/endpoints" % mid)
                except Exception: cache[mid] = None
            return cache[mid]
        return Source(fetch("/models")["data"], fetch("/images/models")["data"], fetch("/videos/models")["data"], fetch("/embeddings/models")["data"], endpoints)

    @staticmethod
    def saved(d):
        def endpoints(mid):
            p = os.path.join(d, "image-endpoints", mid.replace("/", "__") + ".json")
            return load(p) if os.path.exists(p) else None
        return Source(load(os.path.join(d, "models.json"))["data"], load(os.path.join(d, "images.json"))["data"], load(os.path.join(d, "videos.json"))["data"],
                      load(os.path.join(d, "embeddings.json"))["data"], endpoints)


def per_million(v):
    try: x = float(v)
    except (TypeError, ValueError): return None
    return None if x < 0 else round(x * 1e6, 6)


def provider_of(m, labels):
    pid = m["id"].split("/")[0]
    if pid in labels: return labels[pid]
    name = m.get("name", "")
    return name.split(":")[0].strip() if ":" in name else pid


def short_name(m):
    n = m.get("name") or m["id"]
    return n.split(":", 1)[1].strip() if ":" in n else n


def link(mid): return "https://openrouter.ai/" + mid


def chat_entry(m, labels, api="chat"):
    a = m.get("architecture", {})
    p = m.get("pricing", {})
    pin, pout = per_million(p.get("prompt")), per_million(p.get("completion"))
    return {"id": m["id"], "name": short_name(m), "provider": provider_of(m, labels),
            "modalities": {"in": a.get("input_modalities", []), "out": a.get("output_modalities", [])},
            "context": int(m.get("context_length") or 0), "price": {"unit": "tokens", "in": pin, "out": pout}, "link": link(m["id"]), "api": api}


def image_price(src, mid):
    j = src.endpoints(mid)
    if not j: return None
    best = None
    for ep in j.get("endpoints", []):
        bills = ep.get("pricing", [])
        out = [b for b in bills if b.get("billable") == "output_image"]
        if not out: continue
        unit = {"image": "image", "megapixel": "megapixel", "token": "tokens"}.get(out[0].get("unit"))
        if unit is None: continue
        c = min(b["cost_usd"] for b in out if b.get("unit") == out[0]["unit"])
        i = [b["cost_usd"] for b in bills if b.get("billable") == "input_image" and b.get("unit") == out[0]["unit"]]
        mult = 1e6 if unit == "tokens" else 1
        cand = {"unit": unit, "in": round(min(i) * mult, 6) if i else None, "out": round(c * mult, 6)}
        if best is None or cand["out"] < best["out"]: best = cand
    return best


def video_price(m):
    skus = m.get("pricing_skus") or {}
    sec = []
    for k, v in skus.items():
        if "second" in k:
            try: x = float(v)
            except ValueError: continue
            sec.append(x / 100 if k.startswith("cents_") else x)
    if sec: return {"unit": "second", "in": None, "out": round(min(sec), 6)}
    tok = [float(v) * 1e6 for k, v in skus.items() if k.startswith("video_tokens")]
    return {"unit": "tokens", "in": None, "out": round(min(tok), 6)} if tok else None


def b_params(m):
    s = (m["id"] + " " + (m.get("name") or "")).lower()
    hits = [float(x) for x in re.findall(r"(?<![\w.])(\d+(?:\.\d+)?)b(?![a-z])", s)]
    return max(hits) if hits else None


NEWEST = [0]


def has(have, need): return all(x in have for x in need)


def chat_ok(m, r):
    a = m.get("architecture", {})
    i, o = a.get("input_modalities", []), a.get("output_modalities", [])
    if r.get("out_only") and sorted(o) != sorted(r["out_only"]): return False
    if r.get("out_has") and not has(o, r["out_has"]): return False
    if r.get("in_has") and not has(i, r["in_has"]): return False
    if r.get("param_any") and not any(p in m.get("supported_parameters", []) for p in r["param_any"]): return False
    if r.get("id_exclude") and re.search(r["id_exclude"], m["id"]): return False
    if r.get("min_context") and int(m.get("context_length") or 0) < r["min_context"]: return False
    pr = m.get("pricing", {})
    pout, pin = per_million(pr.get("completion")), per_million(pr.get("prompt"))
    if pout is None or pin is None: return False
    if r.get("min_price_out") is not None and pout < r["min_price_out"]: return False
    if r.get("max_age_days") and NEWEST[0] - int(m.get("created") or 0) > r["max_age_days"] * 86400: return False
    if r.get("params_b"):
        b = b_params(m)
        if b is None or not (r["params_b"][0] <= b <= r["params_b"][1]): return False
    return True


def pick(items, key_provider, r, order):
    """Per provider (in the declared order, then the rest), the newest per_provider items; then the rule's limit."""
    allow = r.get("providers")
    groups = {}
    for it in sorted(items, key=lambda x: -x[0]["created"]):
        p = key_provider(it)
        if allow is not None and p not in allow: continue
        groups.setdefault(p, []).append(it)
    rank = {p: i for i, p in enumerate(order)}
    out = []
    for p in sorted(groups, key=lambda p: (rank.get(p, len(rank)), p)):
        out += groups[p][: r.get("per_provider", 1)]
    return out[: r["limit"]] if r.get("limit") else out


def apply(tab, spec, src, cfg, tasks, tools):
    labels, order = cfg["providers"]["labels"], cfg["providers"]["order"]
    NEWEST[0] = max(int(m.get("created") or 0) for m in src.models)
    by_id = {m["id"]: m for m in src.models}
    out = []
    for r in spec["rules"]:
        s = r["source"]
        if s == "chat" and r.get("domains"):
            for d in spec["domains"]:
                rx = re.compile(d["match"])
                ms = [(m,) for m in src.models if chat_ok(m, dict(r, min_context=d.get("min_context", r.get("min_context")))) and rx.search(m["id"] + " " + m.get("name", ""))]
                ms = sorted(ms, key=lambda x: -x[0]["created"])
                seen, n = set(), 0
                for (m,) in ms:
                    if n >= r.get("per_domain", 3): break
                    fam = re.sub(r"[-_.]?\d{4}\b|-\d{2}-\d{2}", "", m["id"])
                    if fam in seen: continue
                    seen.add(fam); n += 1
                    e = chat_entry(m, labels); e["domain"] = d["label"]; out.append(e)
        elif s == "chat":
            for (m,) in pick([(m,) for m in src.models if chat_ok(m, r)], lambda it: it[0]["id"].split("/")[0], r, order):
                out.append(chat_entry(m, labels))
        elif s == "embeddings":
            for (m,) in pick([(m,) for m in src.embeddings], lambda it: it[0]["id"].split("/")[0], r, order):
                out.append(chat_entry(m, labels, "embeddings"))
        elif s == "images":
            chat_ids = {m["id"] for m in src.models}
            for (m,) in pick([(m,) for m in src.images if m["id"] not in chat_ids], lambda it: it[0]["id"].split("/")[0], r, order):
                price = image_price(src, m["id"])
                if price is None: continue
                a = m.get("architecture", {})
                out.append({"id": m["id"], "name": short_name(m), "provider": provider_of(m, labels), "modalities": {"in": a.get("input_modalities", []), "out": a.get("output_modalities", [])},
                            "context": 0, "price": price, "link": link(m["id"]), "api": "images"})
        elif s == "videos":
            for (m,) in pick([(m,) for m in src.videos], lambda it: it[0]["id"].split("/")[0], r, order):
                price = video_price(m)
                if price is None: continue
                mm = by_id.get(m["id"], {}).get("architecture", {})
                out.append({"id": m["id"], "name": short_name(m), "provider": provider_of(m, labels),
                            "modalities": {"in": mm.get("input_modalities", ["text", "image"]), "out": mm.get("output_modalities", ["video"])},
                            "context": int(by_id.get(m["id"], {}).get("context_length") or 0), "price": price, "link": link(m["id"]), "api": "videos"})
        elif s == "tasks":
            used = {}
            for t in tasks:
                used.setdefault(t["model"], []).append(t["label"])
            for mid, labs in used.items():
                if mid in by_id and chat_ok(by_id[mid], {}):
                    e = chat_entry(by_id[mid], labels); e["note"] = "Tasks: " + ", ".join(labs); out.append(e)
        elif s == "tools_models":
            for row in tools:
                if row["tier"] == r["tier"] and row.get("openrouter_id") in by_id and chat_ok(by_id[row["openrouter_id"]], {}):
                    e = chat_entry(by_id[row["openrouter_id"]], labels); e["note"] = row["params"] + " · " + row["role"]; out.append(e)
        else:
            raise SystemExit("tab %s: unknown source %s" % (tab, s))
    seen, uniq = set(), []
    for e in out:
        if e["id"] in seen: continue
        seen.add(e["id"]); uniq.append(e)
    return uniq


def build(src, date):
    cfg = load(TABS)
    tasks = load(os.path.join(AI, "ai-tasks.json"))["tasks"]
    tools = load(os.path.join(AI, "ai-catalogue.json"))["tools_models"]["models"]
    tabs = {t: apply(t, spec, src, cfg, tasks, tools) for t, spec in cfg["tabs"].items()}
    return {"_doc": "Generated by tools/gen-model-snapshot.py from OpenRouter's public model metadata under the rules of model-tabs.json. Never edit by hand.",
            "source": API + "/models", "generated": date, "tabs": tabs}


def check(snap, cfg):
    bad = []
    layout = [t for blk in cfg["layout"] for row in blk["rows"] for t in row]
    if sorted(layout) != sorted(cfg["tabs"]): bad.append("layout tabs %s differ from declared tabs %s" % (layout, sorted(cfg["tabs"])))
    if sorted(snap["tabs"]) != sorted(cfg["tabs"]): bad.append("snapshot tabs differ from the declared tabs")
    for tab, entries in snap["tabs"].items():
        if not entries: bad.append("tab %s is empty" % tab)
        ids = [e.get("id") for e in entries]
        if len(ids) != len(set(ids)): bad.append("tab %s repeats an id" % tab)
        for e in entries:
            where = "%s/%s" % (tab, e.get("id"))
            for f in FIELDS:
                if f not in e: bad.append("%s lacks %s" % (where, f))
            if any(f not in e for f in FIELDS): continue
            if not e["id"] or "/" not in e["id"]: bad.append("%s: id is not provider/model" % where)
            if not e["name"].strip() or not e["provider"].strip(): bad.append("%s: no name or provider" % where)
            if not e["modalities"].get("in") or not e["modalities"].get("out"): bad.append("%s: modalities missing" % where)
            if e["api"] not in APIS: bad.append("%s: unknown api %s" % (where, e["api"]))
            if not isinstance(e["context"], int) or e["context"] < 0 or (e["context"] == 0 and e["api"] in ("chat", "embeddings")): bad.append("%s: context %r" % (where, e["context"]))
            p = e["price"]
            if p.get("unit") not in ("tokens", "image", "megapixel", "second") or not any(isinstance(p.get(k), (int, float)) and p[k] >= 0 for k in ("in", "out")): bad.append("%s: no price" % where)
            if not e["link"].startswith("https://") or e["id"] not in e["link"]: bad.append("%s: link" % where)
            for f in ("id", "name", "provider", "note", "domain"):
                if f in e and PLACEHOLDER.search(str(e[f])): bad.append("%s: placeholder text in %s" % (where, f))
    return bad


def main(argv):
    snap_path = os.path.join(AI, "models-snapshot.json")
    if "--check" in argv:
        bad = check(load(snap_path), load(TABS))
        for b in bad: print("FAIL " + b)
        return 1 if bad else 0
    if "--fetch" in argv:
        src, date = Source.live(), time.strftime("%Y-%m-%d", time.gmtime())
    elif "--from-dir" in argv:
        d = argv[argv.index("--from-dir") + 1]
        src = Source.saved(d)
        date = (argv[argv.index("--date") + 1] if "--date" in argv else time.strftime("%Y-%m-%d", time.gmtime(max(m["created"] for m in src.models))))
    else:
        print(__doc__); return 2
    snap = build(src, date)
    bad = check(snap, load(TABS))
    for b in bad: print("FAIL " + b)
    if bad: return 1
    with open(snap_path, "w", encoding="utf-8") as f: f.write(json.dumps(snap, indent=1, ensure_ascii=False) + "\n")
    print({t: len(e) for t, e in snap["tabs"].items()})
    return 0


sys.exit(main(sys.argv[1:]))
