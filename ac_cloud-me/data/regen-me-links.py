#!/usr/bin/env python3
"""#1906 Pers/Prof links: derive data/links/me-links.json (the declared table) and the `link` of each page file from the SoT.

SoT (siblings of the clone, or --sot DIR): cloud-me-pub-profiles-_lnktree (personal-/professional-profiles.json),
cloud-me-pub-profiles-prf-prs (mySocials data). Each entry names its SoT file + JSON pointer (`source`); `sot_url` is what
the SoT said at generation time (the snapshot the Kotlin test and CI compare against, the SoT repos are not in CI).
  regen-me-links.py            rewrite me-links.json (url/sot_url) and the page files' `link`
  regen-me-links.py --check    fail when the table differs from the SoT (needs the SoT repos)
"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "links", "me-links.json")
def sot_root():
    a = sys.argv
    if "--sot" in a: return a[a.index("--sot") + 1]
    return os.environ.get("ME_SOT_DIR", os.path.abspath(os.path.join(HERE, "..", "..", "..")))
def load(root, rel):
    for base in (root, os.path.join(root, "cloud-me-pub-profiles-___")):
        p = os.path.join(base, rel)
        if os.path.isfile(p): return json.load(open(p, encoding="utf-8"))
    sys.exit("SoT file not found: %s under %s" % (rel, root))
def point(doc, ptr):
    for k in ptr.strip("/").split("/"): doc = doc[int(k)] if isinstance(doc, list) else doc[k]
    return doc
def sot_value(root, e):
    s = e["source"]
    return s.get("prefix", "") + point(load(root, s["file"]), s["pointer"]) + s.get("suffix", "")
def page_file(e): return os.path.join(HERE, "ui", e["section"], *e["path"]) + ".json"
def main():
    root = sot_root(); t = json.load(open(TABLE, encoding="utf-8")); bad = []
    for e in t["entries"]:
        v = sot_value(root, e)
        if "--check" in sys.argv:
            if v != e["url"] or v != e["sot_url"]: bad.append("%s: table %s / snapshot %s / SoT %s" % (e["id"], e["url"], e["sot_url"], v))
            continue
        e["url"] = e["sot_url"] = v
        if "path" in e:
            f = page_file(e); a = json.load(open(f, encoding="utf-8")); a[0]["link"] = v
            open(f, "w", encoding="utf-8").write(json.dumps(a, indent=2, ensure_ascii=False) + "\n")
    if "--check" in sys.argv:
        if bad: sys.exit("DIFFERS FROM SoT:\n" + "\n".join(bad))
        print("me-links: %d entries match the SoT" % len(t["entries"])); return
    open(TABLE, "w", encoding="utf-8").write(json.dumps(t, indent=2, ensure_ascii=False) + "\n")
main()
