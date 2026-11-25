#!/usr/bin/env python3
"""#1918 Proj > Repos: data/web/repos-tree.html is a copy of _git-repos-master A0_repos-tree/dist/repos-tree.html (self-contained, generated there from the registry).
  regen-repos-tree.py          refresh the copy
  regen-repos-tree.py --check  exit 1 if it differs (needs the git-repos-master clone beside this one, or --sot DIR)
"""
import os, sys, shutil
HERE = os.path.dirname(os.path.abspath(__file__)); DST = os.path.join(HERE, "web", "repos-tree.html")
root = sys.argv[sys.argv.index("--sot") + 1] if "--sot" in sys.argv else os.environ.get("ME_SOT_DIR", os.path.abspath(os.path.join(HERE, "..", "..", "..")))
src = os.path.join(root, "git-repos-master", "A0_repos-tree", "dist", "repos-tree.html")
if "--check" in sys.argv:
    if open(src, "rb").read() != open(DST, "rb").read(): sys.exit("web/repos-tree.html differs from " + src)
    print("repos-tree.html matches"); sys.exit(0)
shutil.copyfile(src, DST)
