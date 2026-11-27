#!/usr/bin/env python3
"""#1961 the README trio of every Proj repo entry, as GitHub serves it on main: test/fixtures/proj-readme-trio-1961.json.

Each Proj page file that declares `repo` (data/regen-me-links.py) names a registry repo; the README engine
(_git-repos-master A2_repo-readmes + _dotfiles/readme) commits README.html, README.canvas and README.md at that repo's root.
This snapshots their sizes by GitHub REST (gh api, read-only) so test/test-proj-tabs-1961.sh can prove offline in CI that every
declared project, CG included, has all three; `--check` re-reads GitHub and fails when one is missing or the snapshot is stale.
  regen-proj-readme-trio.py           rewrite the fixture
  regen-proj-readme-trio.py --check   fail when GitHub lacks a file or differs from the fixture (needs gh)
"""
import glob, json, os, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "test", "fixtures", "proj-readme-trio-1961.json")
FILES = ["README.html", "README.canvas", "README.md"]
def size(repo, f):
    r = subprocess.run(["gh", "api", "repos/%s/contents/%s?ref=main" % (repo, f), "--jq", ".size"], capture_output=True, text=True)
    return int(r.stdout.strip()) if r.returncode == 0 and r.stdout.strip().isdigit() else 0
def main():
    t = {}
    for p in sorted(glob.glob(os.path.join(HERE, "ui", "proj", "*", "*.json"))):
        b = json.load(open(p, encoding="utf-8"))[0]
        if "repo" not in b: continue
        parts = b["repo"].split("/"); repo = "/".join(parts[3:5])   # the repo root: the engine writes the trio there
        d = "/".join(parts[7:]) if parts[5:7] == ["tree", "main"] else ""   # #1961 a Wiki folder: the engine's folder targets write the trio into it
        t[os.path.basename(p)[:-5]] = {"repo": repo, "files": {f: size(repo, (d + "/" if d else "") + f) for f in FILES}}
        if d: t[os.path.basename(p)[:-5]]["dir"] = d
    if "--check" in sys.argv:
        old = json.load(open(OUT, encoding="utf-8"))
        bad = ["%s: %s" % (k, v) for k, v in t.items() if not all(v["files"].values()) or k not in old or old[k]["repo"] != v["repo"] or old[k].get("dir") != v.get("dir")]
        if bad: sys.exit("README trio missing or fixture stale:\n" + "\n".join(bad))
        print("proj-readme-trio: %d entries, all three files on main" % len(t)); return
    open(OUT, "w", encoding="utf-8").write(json.dumps(t, indent=1, sort_keys=True) + "\n")
    print("wrote %s (%d entries)" % (os.path.relpath(OUT), len(t)))
main()
