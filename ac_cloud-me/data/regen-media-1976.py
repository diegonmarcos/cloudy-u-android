#!/usr/bin/env python3
"""#1976 Pers > Profiles > Media: the strip is DERIVED from the mySocials site's own nav declaration, never typed here.

Source of truth: b_Profiles/mySocials of cloud-me-pub-profiles-prf-prs (src/typescript/main.ts: THEME_SWITCH_ROW_SOCIALS and THEME_SWITCH_ROW_ACCOUNTS, in the site's order,
plus the icq and dms pages that sit outside those rows, and the page files src/*.html). data/media/mysocials-nav.json is the committed snapshot of it (--sync re-reads the clone).
Outputs: the pages of the pers-profiles container in build.json (id, label), one fragment page file per tab (data/ui/pers/pers-profiles/<id>.json embedding
https://diegonmarcos.github.io/mySocials/<page>.html, as Personal embeds the home page) and the matching entries of data/web/me-links.json.
  regen-media-1976.py           rewrite the outputs from the snapshot
  regen-media-1976.py --check   exit 1 when an output differs; with the clone beside this repo (or --sot DIR) also when the snapshot differs from the site
  regen-media-1976.py --sync    re-read the clone into the snapshot, then rewrite the outputs
"""
import json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__)); SNAP = os.path.join(HERE, "media", "mysocials-nav.json")
PAGES = os.path.join(HERE, "ui", "pers", "pers-profiles"); BUILD = os.path.join(HERE, "..", "build.json"); LINKS = os.path.join(HERE, "web", "me-links.json")
ICON = "ic_p_identities"


def rd(p): return json.load(open(p, encoding="utf-8"))


def site_dir(a):
    if "--sot" in a: return os.path.join(a[a.index("--sot") + 1], "b_Profiles", "mySocials")
    root = os.environ.get("ME_SOT_DIR", os.path.abspath(os.path.join(HERE, "..", "..", "..")))
    return os.path.join(root, "cloud-me-pub-profiles-prf-prs", "b_Profiles", "mySocials")


def read_site(sd):
    ts = open(os.path.join(sd, "src", "typescript", "main.ts"), encoding="utf-8").read()
    rows = []
    for name in ("THEME_SWITCH_ROW_SOCIALS", "THEME_SWITCH_ROW_ACCOUNTS"):
        body = re.search(name + r"[^=]*=\s*\[(.*?)\n\];", ts, re.S).group(1)
        rows += [{"theme": m.group(1), "label": m.group(2)} for m in re.finditer(r"\{\s*theme:\s*'([^']+)',\s*label:\s*'([^']+)'\s*\}", body)]
    files = sorted(f for f in os.listdir(os.path.join(sd, "src")) if f.endswith(".html"))
    return rows, files


def outputs(snap):
    base = snap["base"]; tabs = [dict(snap["home"])] + [dict(t) for t in snap["tabs"]]
    pages, files = [], {}
    for t in tabs:
        tid = t["id"]; url = base + t["page"]
        pages.append({"id": tid, "label": t["label"], "icon": ICON})
        files[os.path.join(PAGES, tid + ".json")] = json.dumps([{"kind": "fragment", "id": "web", "_doc": "#1976 Pers > Media > %s: the mySocials page %s, embedded by its https URL from the declared strip (data/media/mysocials-nav.json, data/regen-media-1976.py); the inner mySocials tab bar is not a second navigation, this tab is the page." % (t["label"], t["page"] or "home (index.html)"), "url": url, "link": url}], ensure_ascii=False, indent=2) + "\n"
    return pages, files


def apply(snap, write):
    pages, files = outputs(snap); bad = []
    s = open(BUILD, encoding="utf-8").read(); L = s.split("\n")
    i = [k for k, l in enumerate(L) if l.strip() == '"id": "pers-profiles",'][0]; st = i - 1
    while L[st].strip() != "{": st -= 1
    ind = len(L[st]) - len(L[st].lstrip()); en = i
    while not (L[en].startswith(" " * ind + "}") and len(L[en]) - len(L[en].lstrip()) == ind): en += 1
    cur = json.loads("\n".join(L[st:en + 1]).strip().rstrip(","))
    new = dict(cur); new["pages"] = pages
    blk = [(" " * ind + l) for l in json.dumps(new, ensure_ascii=False, indent=2).split("\n")]; blk[-1] += ","
    if L[st:en + 1] != blk:
        bad.append("build.json pers-profiles"); L[st:en + 1] = blk
    if write: open(BUILD, "w", encoding="utf-8").write("\n".join(L))
    for p, c in files.items():
        have = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None   # an existing page keeps its own text (docs, #1980 edits): only its url must be the declared one
        if have is None or len(have) != 1 or have[0].get("kind") != "fragment" or have[0].get("id") != "web" or have[0].get("url") != json.loads(c)[0]["url"]:
            bad.append(os.path.relpath(p, HERE)); 
            if write: open(p, "w", encoding="utf-8").write(c)
    keep = set(os.path.basename(p) for p in files)
    for f in os.listdir(PAGES):
        if f not in keep:
            bad.append("stale " + f)
            if write: os.remove(os.path.join(PAGES, f))
    d = rd(LINKS); ents = [e for e in d["entries"] if not (e.get("section") == "pers" and e.get("path", [""])[0] == "pers-profiles")]
    mine = []
    for t in [snap["home"]] + snap["tabs"]:
        u = snap["base"] + t["page"]
        mine.append({"id": "pers-" + t["id"], "section": "pers", "kind": "page", "label": "Personal (mySocials home)" if t["id"] == "personal" else t["label"], "source": {"literal": u}, "path": ["pers-profiles", t["id"]], "url": u, "sot_url": u})
    old = [e for e in d["entries"] if e.get("section") == "pers" and e.get("path", [""])[0] == "pers-profiles"]
    if old != mine:
        bad.append("data/web/me-links.json")
        if write:
            first = [k for k, e in enumerate(d["entries"]) if e in old][0] if old else 0
            d["entries"] = ents[:first] + mine + ents[first:]
            open(LINKS, "w", encoding="utf-8").write(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
    return bad


def main():
    a = sys.argv[1:]; sd = site_dir(a)
    if "--sync" in a:
        rows, files = read_site(sd); old = rd(SNAP); lab = old.get("labels", {}); idm = old["ids"]; pg = old["page_files"]
        tabs = [{"id": idm.get(r["theme"], r["theme"]), "label": lab.get(r["label"], r["label"]), "theme": r["theme"], "page": pg.get(r["theme"], r["theme"] + ".html")} for r in rows]
        tabs += [dict(t) for t in old["extra_tabs"]]
        old["tabs"] = tabs; old["site_pages"] = [f for f in files if f not in old["not_media"]]
        open(SNAP, "w", encoding="utf-8").write(json.dumps(old, ensure_ascii=False, indent=2) + "\n")
    snap = rd(SNAP)
    if "--check" in a:
        bad = apply(snap, False)
        if os.path.isdir(sd):
            rows, files = read_site(sd)
            want = [r["theme"] for r in rows] + [t["theme"] for t in snap["extra_tabs"]]
            if [t["theme"] for t in snap["tabs"]] != want: bad.append("snapshot nav differs from the mySocials nav (run --sync)")
            if [f for f in files if f not in snap["not_media"]] != snap["site_pages"]: bad.append("snapshot site_pages differs from the mySocials pages (run --sync)")
        if bad: sys.exit("media strip out of date (run data/regen-media-1976.py): " + ", ".join(bad))
        print("media strip matches the mySocials declaration"); return
    apply(snap, True)


if __name__ == "__main__": main()
