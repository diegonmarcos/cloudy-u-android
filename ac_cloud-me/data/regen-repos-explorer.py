#!/usr/bin/env python3
"""#1973 Proj > Repos: data/web/repos-explorer.html (List | Table | Canvas, app UI) is a copy of _git-repos-master A0_repos-tree/dist/repos-explorer.html
(self-contained, generated there from the registry projects-repos.json, the source of truth for repo info); repos-explorer.json is the same rows.
The former page data/web/repos-tree.html stays bundled (the page's Tree chip opens it).
  regen-repos-explorer.py          refresh the copies
  regen-repos-explorer.py --check  exit 1 if they differ (needs the git-repos-master clone beside this one, or --sot DIR)
"""
import os, sys, shutil
HERE = os.path.dirname(os.path.abspath(__file__)); W = os.path.join(HERE, "web")
root = sys.argv[sys.argv.index("--sot") + 1] if "--sot" in sys.argv else os.environ.get("ME_SOT_DIR", os.path.abspath(os.path.join(HERE, "..", "..", "..")))
for f in ("repos-explorer.html", "repos-explorer.json"):
    src = os.path.join(root, "git-repos-master", "A0_repos-tree", "dist", f); dst = os.path.join(W, f)
    if "--check" in sys.argv:
        if open(src, "rb").read() != open(dst, "rb").read(): sys.exit("web/%s differs from %s" % (f, src))
    else: shutil.copyfile(src, dst)
if "--check" in sys.argv: print("repos-explorer matches")
