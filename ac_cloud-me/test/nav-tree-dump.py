#!/usr/bin/env python3
"""Dump Cloud Me's navigation tree (#1901): bottom nav, sections, every page at any depth, the tiles (the targets and
links inside a page) and every URL, plus the leaf fields of each page's content file.

    nav-tree-dump.py [APP_DIR]  > nav-baseline-1901.json     (APP_DIR defaults to the app this file sits in)

The output is the BASELINE of the Pers / Prof / Proj reorganisation (test/fixtures/nav-baseline-1901.json, dumped from the
tree before it moved) and the shape test-nav-reorg.sh compares the live tree against. Nothing here is Cloud Me specific
logic: it reads build.json::ui and data/ui/<section>/<page path>.json exactly as app/build.gradle and Sections.stack do.

A leaf is "<json path>=<value>" (a value over 120 characters is its sha1). Keys starting with _doc are prose comments of the
declaration, not fields, and are not leaves. A page that only holds other pages has no file and no leaves.
"""
import hashlib
import json
import os
import sys

APP = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

SECTION_EXTRAS_SKIP = {"id", "label", "icon", "pages", "groups", "nav_mode", "toolbar", "target"}


def leaves(o, path=""):
    """Every scalar below o as 'path=value', skipping _doc* keys."""
    if isinstance(o, dict):
        for k in sorted(o):
            if k.startswith("_doc"):
                continue
            yield from leaves(o[k], f"{path}/{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from leaves(v, f"{path}/{i}")
    else:
        s = json.dumps(o, ensure_ascii=False)
        yield f"{path}={s if len(s) <= 120 else 'sha1:' + hashlib.sha1(s.encode()).hexdigest()}"


def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values():
            yield from walk(v)
    elif isinstance(o, list):
        for v in o:
            yield from walk(v)


def node(p):
    n = {"id": p["id"], "label": p.get("label", ""), "icon": p.get("icon", "")}
    for k in ("separator_before", "action"):
        if p.get(k):
            n[k] = p[k]
    if p.get("pages"):
        n["pages"] = [node(c) for c in p["pages"]]
    return n


def collect(section, pages, chain, out):
    for p in pages:
        here = chain + [p["id"]]
        entry = {"section": section["id"], "path": here, "label": p.get("label", ""), "container": bool(p.get("pages"))}
        key = f"{section['id']}/{p['id']}"
        if p.get("pages"):
            entry["file"] = None
            entry["leaves"] = []
            entry["urls"] = []
            entry["targets"] = []
            out[key] = entry
            collect(section, p["pages"], here, out)
            continue
        f = os.path.join("data", "ui", section["id"], *here) + ".json"
        entry["file"] = f
        try:
            content = json.load(open(os.path.join(APP, f), encoding="utf-8"))
        except (OSError, ValueError):
            content = None
        entry["leaves"] = sorted(leaves(content)) if content is not None else []
        blocks = list(walk(content)) if content is not None else []
        entry["urls"] = sorted({b["url"] for b in blocks if isinstance(b.get("url"), str) and b["url"]})
        entry["targets"] = sorted({b["target"] for b in blocks if isinstance(b.get("target"), str) and b["target"]})
        out[key] = entry


def main():
    ui = json.load(open(os.path.join(APP, "build.json"), encoding="utf-8"))["ui"]
    sections, pages = [], {}
    for s in ui["sections"]:
        sec = {"id": s["id"], "label": s.get("label", ""), "icon": s.get("icon", "")}
        if s.get("nav_mode"):
            sec["nav_mode"] = s["nav_mode"]
        if s.get("toolbar"):
            sec["toolbar"] = True
        if s.get("groups"):
            sec["groups"] = [{"id": g["id"], "label": g.get("label", ""), "icon": g.get("icon", ""), "pages": g["pages"]} for g in s["groups"]]
        sec["pages"] = [node(p) for p in s.get("pages", [])]
        # whatever else the section declares (metrics, migration ...) is data the move must carry too
        sec["extras"] = sorted(leaves({k: v for k, v in s.items() if k not in SECTION_EXTRAS_SKIP and not k.startswith("stack_")}))
        sections.append(sec)
        collect(s, s.get("pages", []), [], pages)
    print(json.dumps({
        "_doc": "#1901 BASELINE: Cloud Me's navigation tree before Health became Pers, Experiences Prof and Socials Proj (dumped by test/nav-tree-dump.py from commit "
                + os.environ.get("BASELINE_REV", "?") + "). test-nav-reorg.sh proves every page, field and URL below still exists after the move.",
        "bottom_nav": ui.get("bottom_nav", []),
        "bar": [next(s.get("label", "") for s in ui["sections"] if s["id"] == i) for i in ui.get("bottom_nav", [])],
        "default_section": ui.get("default_section", ""),
        "settings": ui.get("settings", []),
        "sections": sections,
        "pages": pages,
    }, indent=1, ensure_ascii=False))


main()
