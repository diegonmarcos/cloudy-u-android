#!/usr/bin/env python3
"""#1906 Pers/Prof links: derive data/web/me-links.json (the declared table) and the `link` of each page file from the SoT.

SoT (siblings of the clone, or --sot DIR): cloud-me-pub-profiles-_lnktree (personal-/professional-profiles.json),
cloud-me-pub-profiles-prf-prs (mySocials data). Each entry names its SoT file + JSON pointer (`source`); `sot_url` is what
the SoT said at generation time (the snapshot the Kotlin test and CI compare against, the SoT repos are not in CI).
  regen-me-links.py            rewrite me-links.json (url/sot_url) and the page files' `link`
  regen-me-links.py --check    fail when the table differs from the SoT (needs the SoT repos)
"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "web", "me-links.json")
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
def repo_site(root, s):
    """#1914 a Proj leaf: its published site from _front-html___/repos-pages.json (an `overlay` folder, else pages_url, else a custom domain page),
    else its GitHub repo page (git-repos-master/0_registry/repos.json url; `github_path` = a folder of it)."""
    name = s["repo"]
    reg = {r["name"]: r for r in load(root, "git-repos-master/0_registry/repos.json")["repos"]}
    if name not in reg: sys.exit("repo %s is not in the registry" % name)
    if "github_path" not in s:
        pg = {r["name"]: r for r in load(root, "_front-html___/repos-pages.json")["repos"]}.get(name, {})
        if s.get("overlay"):
            for o in pg.get("overlay_pages", []):
                if o["folder"] == s["overlay"]: return o["url"]
            sys.exit("repo %s has no overlay page %s" % (name, s["overlay"]))
        if pg.get("pages_url"): return pg["pages_url"]
    base = "https://github.com/" + reg[name]["url"].split(":", 1)[1][:-4]
    return base + ("/" + s["github_path"] if "github_path" in s else "")
def sot_value(root, e):
    s = e["source"]
    if "repo" in s: return repo_site(root, s)
    if "literal" in s: return s["literal"]
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
