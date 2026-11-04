#!/usr/bin/env python3
"""Derive data/repos_declared.json (every owner git REPO as a Containers-page entity: kind Repos) from the fleet registry.
NOTHING here is typed: repos, areas, projects and families are READ from git-repos-master 0_registry/projects-repos.json,
Pages URLs from front-html pages.json, the project of a repo from the registry code: data/container_projects.json declares
each project's registry `code` (1.1, 1.2, 2, ...); a repo belongs to the project whose code is the longest prefix of the repo's
registry code, else to the declared default (Others). The Archived area is skipped.

  python3 data/gen_repos_slice.py [--registry F] [--pages F] [--check]   (default: git-repos-master / front-html main, raw)

Output: {_meta, projects: {<id>: {families: [registry top-level family names]}}, repos: [{name, project, group, category, code,
visibility pub|pvt, default_branch, pages_url, github_url}]}. group = the repo's top-level registry family, except a project that
declares `repo_group` in container_projects.json (Cloudy keeps its own macro groups) where every repo sits in that group and the
family is its category. default_branch is read from the Pages source when the repo has one; the registry carries no other branch.
"""
import argparse, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REG = "https://raw.githubusercontent.com/diegonmarcos/_git-repos-master/main/0_registry/projects-repos.json"
PAGES = "https://raw.githubusercontent.com/diegonmarcos/front-html/main/pages.json"
OUT = os.path.join(HERE, "repos_declared.json")
OWNER = "diegonmarcos"
SKIP_AREAS = {"Archived"}


def load(src, url):
    return json.load(open(src)) if src else json.load(urllib.request.urlopen(url))


def project_of(code, projects, default):
    best = max((p for p in projects if p.get("code") and (code == p["code"] or code.startswith(p["code"] + "."))),
               key=lambda p: len(p["code"]), default=None)
    return best["id"] if best else default


def derive(reg, pages, cp):
    projects, default = cp["projects"], cp["default"]
    pg = {p["repo"]: p for p in pages.get("pages", [])}
    fam = {p["id"]: [] for p in projects}
    repos = []

    def walk(n, area, trail):
        k = n.get("kind")
        if k == "family" and not any(t.get("kind") == "family" for t in trail):
            pid = project_of(n["code"], projects, default)
            if n["name"] not in fam[pid]:
                fam[pid].append(n["name"])
        if k == "repo":
            fams = [t for t in trail if t.get("kind") == "family"]
            sub = [t for t in trail if t.get("kind") == "subcategory"]
            pid = project_of(n["code"], projects, default)
            decl = next(p for p in projects if p["id"] == pid)
            top = fams[0]["name"] if fams else ""
            cat = (sub[0]["name"] if sub else (fams[-1]["name"] if len(fams) > 1 else top))
            group = decl.get("repo_group") or top
            if decl.get("repo_group"):
                cat = top
            p = pg.get(n["name"]) or {}
            repos.append({
                "name": n["name"], "project": pid, "group": group, "category": cat, "code": n["code"],
                "visibility": "pvt" if n.get("visibility") == "private" else "pub",
                "default_branch": (p.get("source") or {}).get("branch", ""),
                "pages_url": p.get("url", ""),
                "github_url": "https://github.com/%s/%s" % (OWNER, n["name"]),
            })
        for c in n.get("children", []):
            walk(c, area, trail + [n])

    for a in reg["tree"]:
        if a["name"] in SKIP_AREAS or a.get("kind") != "area":
            continue
        walk(a, a["name"], [])
    return {"_meta": {"generated_by": "data/gen_repos_slice.py", "source": "git-repos-master 0_registry/projects-repos.json + front-html pages.json"},
            "projects": {k: {"families": v} for k, v in fam.items()}, "repos": repos}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry"); ap.add_argument("--pages"); ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    cp = json.load(open(os.path.join(HERE, "container_projects.json")))
    out = derive(load(a.registry, REG), load(a.pages, PAGES), cp)
    text = json.dumps(out, indent=1, ensure_ascii=False) + "\n"
    if a.check:
        if open(OUT).read() != text:
            print("DRIFT: data/repos_declared.json differs from the registry; run data/gen_repos_slice.py"); sys.exit(1)
        print("ok: repos_declared.json matches the registry"); return
    open(os.path.join(HERE, "repos_declared.json"), "w").write(text)  # noqa
    print("wrote %d repos" % len(out["repos"]))


if __name__ == "__main__":
    main()
