# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fleet-config-guard.py ───
#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-fleet-config-guard — every store that holds a      ║
# ║ fleet app's configuration is declared in fleet-config.json       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY (#783). A new phone must end up configured exactly like the old one
# after Connect + "apply all". That only holds while EVERY place an app keeps
# its configuration is in the one declaration (libs:core
# assets/fleet-config.json): the migration contract exports and imports what
# it declares and nothing else, so an undeclared SharedPreferences file is a
# setting that silently stays behind on the old phone. This guard scans every
# app and lib tree for the stores Android offers — SharedPreferences (plain and
# Encrypted), Preferences DataStore, Room — and fails on:
#
#   C1 a store found in the code that the manifest does not declare;
#   C2 a declared store whose `used_by` disagrees with where the code uses it
#      (a store that moved, or a module that stopped using it);
#   C3 a declared store that no code uses any more (a stale entry reads as
#      policy and pads the coverage numbers);
#   C4 a class outside the vocabulary, or a store with no `doc`;
#   C5 a store name the scanner cannot resolve (built at runtime) that has no
#      `resolve` entry naming what it is;
#   C6 a fleet app (constellation-fleet.json, kind app) with no `apps` entry,
#      or an `apps` entry for something that is not a fleet app;
#   C7 a `config` store with a key-level override whose class is not
#      secret/device/content (an override must narrow, never widen);
#   C8 nothing scanned at all (an empty checkout is not a clean fleet);
#   C9 a declared kind (prefs / encrypted / datastore / room) that is not how the
#      code opens the store (the contract would export ciphertext, or nothing);
#   C10 the human matrix (policy `matrix`) is not what the manifest renders —
#      regenerate it with `--matrix`, never by hand;
#   C11 an app's `libs` is not the set of shared libs its build compiles in
#      (policy `lib_consumers`, generated from the gradle graph): the contract
#      and the vault's settings section attribute a lib's stores to the apps
#      listed here, so a stale list drops (or invents) that app's settings.
#
# Everything it knows lives in data: the scan vocabulary in
# 1_cicd/src/data/fleet-config-guard.json, the declarations in the manifest.
# No app, store or class is named here. No ripgrep: it is not on the runner.
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "fleet-config-guard.json")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def modules(repo, cfg):
    """(module id, root) for every app and lib tree the policy names."""
    for top in sorted(os.listdir(repo)):
        p = os.path.join(repo, top)
        if os.path.isdir(p) and top.startswith(tuple(cfg["app_prefixes"])) and top not in cfg["skip_dirs"]:
            yield top, p
    for libs_dir, prefix in cfg["lib_roots"].items():
        root = os.path.join(repo, libs_dir)
        if not os.path.isdir(root):
            continue
        for lib in sorted(os.listdir(root)):
            if os.path.isdir(os.path.join(root, lib)) and lib not in cfg["skip_dirs"]:
                yield prefix + lib, os.path.join(root, lib)


def scan(repo, cfg):
    """{store: {module: {relpaths}}}, {store: {kinds}}, {module: count of files read}."""
    prune = set(cfg["prune_dirs"])
    exts = tuple(cfg["extensions"])
    const = re.compile(cfg["const_pattern"])
    pats = [(k, re.compile(p)) for k, p in cfg["store_patterns"].items()]
    found = collections.defaultdict(lambda: collections.defaultdict(set))
    kinds = collections.defaultdict(set)
    seen = {}
    for mid, root in modules(repo, cfg):
        texts = {}
        for r, ds, ns in os.walk(root):
            ds[:] = sorted(d for d in ds if d not in prune)
            for n in ns:
                if n.endswith(exts):
                    p = os.path.join(r, n)
                    with open(p, encoding="utf-8", errors="replace") as f:
                        texts[p] = strip_comments(f.read())
        seen[mid] = len(texts)
        consts = collections.defaultdict(set)
        for t in texts.values():
            for k, v in const.findall(t):
                consts[k].add(v)
        for p, t in texts.items():
            # Nearest definition BEFORE the call wins: two companion objects in one file may both
            # declare `FILE`, and the call reads the one in its own class.
            defs = [(m.start(), m.group(1), m.group(2)) for m in const.finditer(t)]
            rel = os.path.relpath(p, repo).replace(os.sep, "/")
            for kind, pat in pats:
                for m in pat.finditer(t):
                    if kind == "prefs_default":
                        found["<default>"][mid].add(rel)
                        kinds["<default>"].add("prefs")
                        continue
                    expr = m.group(1).strip()
                    if kind == "room":
                        found["room:" + expr][mid].add(rel)
                        kinds["room:" + expr].add("room")
                        continue
                    name = None
                    if expr.startswith('"') and expr.endswith('"'):
                        # "${app.packageName}_x" is the same file in every app: {pkg}_x.
                        lit = re.sub(cfg["package_template_pattern"], "{pkg}", expr[1:-1])
                        if "$" not in lit:
                            name = "<default>" if lit == "{pkg}_preferences" else lit
                    elif re.search(cfg["default_name_pattern"], expr):
                        name = "<default>"
                    else:
                        ident = expr.split(".")[-1]
                        mine = [(pos, v) for pos, k, v in defs if k == ident]
                        before = [v for pos, v in mine if pos < m.start()]
                        if mine:
                            name = before[-1] if before else mine[0][1]
                        elif len(consts.get(ident, ())) == 1:
                            name = next(iter(consts[ident]))
                    if name is None:
                        name = "?%s@%s" % (expr, rel)
                    key = ("datastore:" if kind == "datastore" else "") + name
                    found[key][mid].add(rel)
                    kinds[key].add(kind)
    return found, kinds, seen


def check(repo, cfg, manifest, roster):
    bad = []
    found, kinds, seen = scan(repo, cfg)
    if sum(seen.values()) == 0:
        return ["C8 no .kt/.java source under any app or lib tree — nothing was checked, which is not a pass"]
    classes = set(manifest["classes"])
    resolve = manifest.get("resolve", {})
    stores = manifest["stores"]
    # C5: names built at runtime resolve through the manifest, by expression@file.
    resolved = collections.defaultdict(lambda: collections.defaultdict(set))
    rkinds = collections.defaultdict(set)
    for name, mods in found.items():
        k = kinds[name]
        if name.startswith(("?", "datastore:?")):
            key = name.split("?", 1)[1]
            target = resolve.get(key)
            if target is None:
                for mid, rels in mods.items():
                    bad.append("C5 %s: a store name built at runtime (%s). Add `resolve[%r]` to fleet-config.json "
                               "naming the store it is (or `-` when it is not a configuration store, with why)."
                               % (sorted(rels)[0], key.split("@")[0], key))
                continue
            if target["store"] == "-":
                continue
            name = target["store"]
        for mid, rels in mods.items():
            resolved[name][mid] |= rels
        rkinds[name] |= k
    for key in sorted(set(resolve) - {n.split("?", 1)[1] for n in found if "?" in n}):
        bad.append("C5 resolve[%r] matches nothing the scan found — drop it" % key)
    for name, mods in sorted(resolved.items()):
        decl = stores.get(name)
        if decl is None:
            bad.append("C1 store %r (%s) is not declared. Every place an app keeps state must be in "
                       "fleet-config.json with its class (config/secret/device/content) and doc, or a new "
                       "phone silently loses it." % (name, ", ".join("%s: %s" % (m, sorted(r)[0]) for m, r in sorted(mods.items()))))
            continue
        if decl.get("kind") not in rkinds[name]:
            bad.append("C9 store %r: kind %r, the code opens it as %s — the migration contract reads an "
                       "encrypted store through its cipher and a plain one directly, so the kind must be true"
                       % (name, decl.get("kind"), sorted(rkinds[name])))
        if sorted(decl.get("used_by", [])) != sorted(mods):
            bad.append("C2 store %r: used_by says %s, the code uses it in %s"
                       % (name, sorted(decl.get("used_by", [])), sorted(mods)))
    for name in sorted(set(stores) - set(resolved)):
        decl = stores[name]
        if decl.get("kind") in ("file", "provider"):
            continue  # files and registered providers are declared by hand; the scanner does not see them
        helper = decl.get("helper")
        if helper:
            # Opened through an app helper that takes the name as an argument: the declaration
            # names the file that passes it, and that file must still pass it.
            hp = os.path.join(repo, helper)
            if os.path.isfile(hp) and ('"%s"' % name) in open(hp, encoding="utf-8", errors="replace").read():
                continue
            bad.append("C3 store %r: its helper %s no longer passes that name" % (name, helper))
            continue
        bad.append("C3 store %r is declared but no code uses it — drop it" % name)
    for name, decl in sorted(stores.items()):
        if decl.get("class") not in classes:
            bad.append("C4 store %r: class %r is not one of %s" % (name, decl.get("class"), sorted(classes)))
        if not decl.get("doc"):
            bad.append("C4 store %r has no doc" % name)
        for k, c in decl.get("keys", {}).items():
            if decl.get("class") == "config" and c not in ("secret", "device", "content"):
                bad.append("C7 store %r key %r: override %r must narrow config to secret/device/content" % (name, k, c))
            if c not in classes:
                bad.append("C4 store %r key %r: class %r is not one of %s" % (name, k, c, sorted(classes)))
    fleet_apps = {a["id"]: a for a in roster["apps"] if a.get("kind") == "app"}
    for aid in sorted(set(fleet_apps) - set(manifest["apps"])):
        bad.append("C6 fleet app %r (%s) has no `apps` entry in fleet-config.json" % (aid, fleet_apps[aid]["package"]))
    for aid in sorted(set(manifest["apps"]) - set(fleet_apps)):
        bad.append("C6 `apps[%r]` is not a fleet app in constellation-fleet.json" % aid)
    for aid, a in sorted(manifest["apps"].items()):
        if aid in fleet_apps and a.get("package") != fleet_apps[aid]["package"]:
            bad.append("C6 apps[%r].package %r, the roster says %r" % (aid, a.get("package"), fleet_apps[aid]["package"]))
    return bad


def libs_drift(manifest, consumers):
    """C11: apps[].libs against the build_time section of lib-consumers.json."""
    built = collections.defaultdict(set)
    for lib in consumers.get("build_time", []):
        for mod in lib.get("compiled_by", []):
            built[mod].add("lib-" + lib["id"])
    return ["C11 apps[%r].libs is %s, the build compiles in %s — copy the build's list (%s is generated from the gradle graph)"
            % (aid, sorted(a.get("libs", [])), sorted(built[a["module"]]), "lib-consumers.json")
            for aid, a in sorted(manifest["apps"].items()) if sorted(a.get("libs", [])) != sorted(built[a["module"]])]


def coverage(manifest, app):
    """(covered store names, gaps) — the same rule as libs:core FleetConfig.Manifest.coverage."""
    mods = set(app.get("libs", [])) | {app["module"]}
    mig, portable = set(manifest["migrate"]), {"prefs", "encrypted"}
    aid = next(k for k, v in manifest["apps"].items() if v is app)
    mine = sorted((n for n, s in manifest["stores"].items()
                   if set(s.get("used_by", [])) & mods and s.get("class_by_app", {}).get(aid, s["class"]) in mig))
    items = [i for i in app.get("items", []) + [i for l in app.get("libs", []) for i in manifest.get("libs", {}).get(l, {}).get("items", [])]
             if i.get("class") in mig]
    def moved(i):   # an item held in a portable store, reinstalled, or re-fetched on Connect
        st = manifest["stores"].get(i.get("store", ""))
        return i.get("via") in ("install", "connect") or (st is not None and st["kind"] in portable)
    covered = [n for n in mine if manifest["stores"][n]["kind"] in portable] + ["file:" + (i.get("path") or i.get("id")) for i in items if moved(i)]
    gaps = [n for n in mine if manifest["stores"][n]["kind"] not in portable] + ["file:" + (i.get("path") or i.get("id")) for i in items if not moved(i)]
    return covered, gaps


def matrix(manifest, roster):
    """The human matrix, rendered from the manifest alone."""
    labels = {a["id"]: a.get("label", a["id"]) for a in roster["apps"]}
    out = ["# Fleet configuration matrix (#783)", "",
           "GENERATED by `python3 1_cicd/src/scripts/cloud-android-fleet-config-guard.py . --matrix` from",
           "`fleet-private-data/fleet-config.json` (#855: libs:fleetconfig-model carries it, so it rides in the SuperApp and Cloud-Lib-Fleetconfig; every other app is handed it per call) — edit the manifest, never this file.",
           "fleet-config-guard.yml fails when it is stale.", "",
           "Classes: " + "; ".join("**%s** — %s" % (k, v) for k, v in manifest["classes"].items()), "",
           "Kinds: " + "; ".join("**%s** — %s" % (k, v) for k, v in manifest["kinds"].items()), "",
           "## Coverage per app", "",
           "Covered = migrating (config/secret) stores the libs:core contract moves by itself; gaps = migrating",
           "DataStore / Room stores and declared files it cannot move yet.", "",
           "| app | package | covered | gaps | coverage |", "|---|---|---:|---:|---:|"]
    tc = tt = 0
    for aid, a in manifest["apps"].items():
        c, g = coverage(manifest, a)
        tc += len(c); tt += len(c) + len(g)
        pct = 100 if not (c or g) else len(c) * 100 // (len(c) + len(g))
        out.append("| %s | `%s` | %d | %d | %d%% |" % (labels.get(aid, aid), a["package"], len(c), len(g), pct))
    out += ["| **fleet** | | **%d** | **%d** | **%d%%** |" % (tc, tt - tc, tc * 100 // max(tt, 1)), ""]
    for aid, a in manifest["apps"].items():
        c, g = coverage(manifest, a)
        mods = set(a.get("libs", [])) | {a["module"]}
        out += ["## %s — `%s`" % (labels.get(aid, aid), a["package"]), "",
                "Module `%s`; libs: %s." % (a["module"], ", ".join(a.get("libs", [])) or "none"), "",
                "| store | kind | class | migrates | doc |", "|---|---|---|---|---|"]
        for n, s in sorted(manifest["stores"].items()):
            if not set(s.get("used_by", [])) & mods:
                continue
            cls = s.get("class_by_app", {}).get(aid, s["class"])
            moves = "yes" if cls in manifest["migrate"] and s["kind"] in ("prefs", "encrypted") else ("GAP" if cls in manifest["migrate"] else "no")
            narrowed = ", ".join("%s→%s" % kv for kv in sorted(s.get("keys", {}).items()))
            out.append("| `%s` | %s | %s%s | %s | %s |" % (n, s["kind"], cls, (" (keys: %s)" % narrowed) if narrowed else "", moves,
                                                          s["doc"].replace("|", "\\|").replace("\n", " ")))
        files = a.get("items", []) + [i for l in a.get("libs", []) for i in manifest.get("libs", {}).get(l, {}).get("items", [])]
        if files:
            out += ["", "| file | class | doc |", "|---|---|---|"]
            for i in files:
                out.append("| `%s` | %s | %s |" % (i.get("path") or i.get("id"), i.get("class"), str(i.get("doc", "")).replace("|", "\\|").replace("\n", " ")))
        out += ["", "Coverage: %d covered, %d gaps%s." % (len(c), len(g), (" — " + ", ".join(g)) if g else ""), ""]
    return "\n".join(out) + "\n"


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    repo = os.path.abspath(args[0]) if args else os.getcwd()
    with open(args[1] if len(args) > 1 else DEFAULT_POLICY, encoding="utf-8") as f:
        cfg = json.load(f)
    if "--scan" in argv:
        found, kinds, _ = scan(repo, cfg)
        json.dump({n: {"kinds": sorted(kinds[n]), "used_by": {m: sorted(r) for m, r in sorted(ms.items())}}
                   for n, ms in sorted(found.items())},
                  sys.stdout, indent=1)
        return 0
    with open(os.path.join(repo, cfg["manifest"]), encoding="utf-8") as f:
        manifest = json.load(f)
    with open(os.path.join(repo, cfg["roster"]), encoding="utf-8") as f:
        roster = json.load(f)
    doc = os.path.join(repo, cfg["matrix"])
    if "--matrix" in argv:
        os.makedirs(os.path.dirname(doc), exist_ok=True)
        with open(doc, "w", encoding="utf-8") as f:
            f.write(matrix(manifest, roster))
        print("wrote " + cfg["matrix"])
        return 0
    bad = check(repo, cfg, manifest, roster)
    consumers = os.path.join(repo, cfg["lib_consumers"])
    if os.path.isfile(consumers):
        with open(consumers, encoding="utf-8") as f:
            bad += libs_drift(manifest, json.load(f))
    if os.path.isfile(os.path.join(repo, cfg["roster"])) and manifest.get("kinds"):
        current = open(doc, encoding="utf-8").read() if os.path.isfile(doc) else ""
        if current != matrix(manifest, roster):
            bad.append("C10 %s is not what the manifest renders — run the guard with --matrix and commit it" % cfg["matrix"])
    if bad:
        print("fleet config guard: %d violation(s). Rule: %s" % (len(bad), cfg.get("_rule", "")))
        for b in bad:
            print("  " + b)
        return 1
    print("fleet config guard: OK — %d store(s) declared across %d app(s); every store in the code is classified"
          % (len(manifest["stores"]), len(manifest["apps"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
