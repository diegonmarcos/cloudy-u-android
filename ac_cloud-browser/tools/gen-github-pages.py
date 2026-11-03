#!/usr/bin/env python3
"""Derive ac_cloud-browser/data/github_pages.json: every GitHub Pages site the owner publishes.

Nothing is typed. The sites are the front project's own list: diegonmarcos.github.io (the front repo's
published site) builds every project into diegonmarcos.github.io/<deploy_name>/, and its derive job
commits that list as front-topology.json (1_front-configs/dist/front-topology.json, `projects[]`: name, slug, deploy_name, has_dist). A probe of
the owner's other public repos (2026-10) found no other Pages site, so this one list is all of them.

  gen-github-pages.py                    regenerate from the published front-topology.json
  gen-github-pages.py --topology FILE|URL  read another copy (a checkout, a mirror)
  gen-github-pages.py --probe            also GET every URL and leave out (and name) any that is not 200: a project
                                         the front builds but Pages does not serve is not a link worth seeding
  gen-github-pages.py --check            write nothing; exit 1 when the committed file differs (without --probe: when it
                                         holds a site the topology no longer has, or out of order)

The output rows are {name, url, repo}; app/build.gradle expands them into the Fav seed under the group
"GitHub Pages" (build.json::ui.browser.favourites.sources). The guard test-browser-github-pages.sh holds
the file to: parses, no duplicates, https on the Pages host.
"""
import json, os, sys, urllib.request

HOST = "https://diegonmarcos.github.io"
TOPOLOGY = "https://raw.githubusercontent.com/diegonmarcos/diegonmarcos.github.io/main/1_front-configs/dist/front-topology.json"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "github_pages.json")


def derive(topology):
    """front-topology.json -> [{name, url}], the root site first, then the projects in topology order.
    Raises ValueError on a project with no deploy_name or a duplicate URL/name: a list with either is not published."""
    # #1422 `repo`: the front project's source folder (topology `category`), the Fav sub-folder of GH Pages
    rows = [{"name": "Diego Marcos", "url": HOST + "/", "repo": HOST.split("://")[1]}]
    for p in topology.get("projects", []):
        if not p.get("has_dist"):
            continue   # nothing is published for it
        dn = str(p.get("deploy_name") or "").strip().strip("/")
        if not dn:
            raise ValueError("project without deploy_name: %r" % p.get("slug"))
        rows.append({"name": str(p.get("name") or dn).strip(), "url": "%s/%s/" % (HOST, dn),
                     "repo": str(p.get("category") or dn).strip()})
    for key in ("url", "name"):
        seen = set()
        for r in rows:
            k = r[key].lower()
            if k in seen:
                raise ValueError("duplicate %s: %s" % (key, r[key]))
            seen.add(k)
    return rows


def read(src):
    if src.startswith("https://"):
        with urllib.request.urlopen(src, timeout=30) as r:
            return json.loads(r.read().decode("utf-8"))
    with open(src, encoding="utf-8") as f:
        return json.load(f)


def probe(rows):
    bad = []
    for r in rows:
        try:
            code = urllib.request.urlopen(urllib.request.Request(r["url"], method="GET"), timeout=20).status
        except Exception as e:   # urllib raises on 4xx/5xx
            code = getattr(e, "code", str(e))
        if code != 200:
            bad.append((r["url"], code))
    return bad


def render(rows):
    return json.dumps(rows, indent=2, ensure_ascii=False) + "\n"


def main(argv):
    src = TOPOLOGY
    if "--topology" in argv:
        src = argv[argv.index("--topology") + 1]
    rows = derive(read(src))
    if "--probe" in argv:
        bad = probe(rows)
        for u, c in bad:
            print("left out, not served (%s): %s" % (c, u), file=sys.stderr)
        rows = [r for r in rows if r["url"] not in {u for u, _ in bad}]
    text = render(rows)
    if "--check" in argv:
        have = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if "--probe" not in argv:   # offline: the committed rows must be an in-order subset of the derived ones
            it = iter(json.dumps(r, sort_keys=True) for r in rows)
            try:
                mine = [json.dumps(r, sort_keys=True) for r in json.loads(have)]
            except ValueError:
                mine = None
            ok = mine is not None and all(any(m == d for d in it) for m in mine)
            have, text = ("", "x") if not ok else ("", "")
        if have != text:
            print("data/github_pages.json is stale: run tools/gen-github-pages.py", file=sys.stderr)
            return 1
        print("up to date (%d derived)" % len(rows))
        return 0
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote %d sites to %s" % (len(rows), os.path.normpath(OUT)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
