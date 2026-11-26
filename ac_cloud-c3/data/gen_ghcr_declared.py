#!/usr/bin/env python3
"""#1942 Derive data/ghcr_declared.json, the GHCR packages the Orch > GHCR page lists, from what the app already declares:
every `ghcr.io/<owner>/<image>` in data/containers_declared.json (tag or digest dropped, one row per package) plus one
`cgc-db-<repo>` package per repo of data/repos_declared.json. NOTHING is typed here and NO token is involved: the app reads
the tags of each package anonymously at run time (a private package shows as private). `--check` exits 1 when stale."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "ghcr_declared.json")


def build():
    c = json.load(open(os.path.join(HERE, "containers_declared.json"), encoding="utf-8"))
    r = json.load(open(os.path.join(HERE, "repos_declared.json"), encoding="utf-8"))
    pk, seen = [], set()
    for x in c["containers"]:
        im = x.get("image", "")
        if not im.startswith("ghcr.io/"):
            continue
        ref = im[len("ghcr.io/"):].split("@")[0]
        ref = ref.rsplit(":", 1)[0] if ":" in ref.rsplit("/", 1)[-1] else ref
        owner, _, name = ref.partition("/")
        if not name or (owner, name) in seen:
            continue
        seen.add((owner, name))
        pk.append({"owner": owner, "package": name, "repo": "", "source": "containers_declared"})
    for p in r["repos"]:
        o, n = "diegonmarcos", "cgc-db-" + p["name"]
        if (o, n) not in seen:
            seen.add((o, n))
            pk.append({"owner": o, "package": n, "repo": p["name"], "source": "cgc-db"})
    return {"_meta": {"generated_by": "data/gen_ghcr_declared.py", "source": "data/containers_declared.json image fields + cgc-db-<repo> per data/repos_declared.json repo"}, "packages": pk}


if __name__ == "__main__":
    text = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        if open(OUT, encoding="utf-8").read() != text:
            sys.exit("stale data/ghcr_declared.json - run data/gen_ghcr_declared.py")
    else:
        open(OUT, "w", encoding="utf-8").write(text)
