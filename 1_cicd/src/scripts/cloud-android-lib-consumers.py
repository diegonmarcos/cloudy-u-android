#!/usr/bin/env python3
"""cloud-android-lib-consumers -- which app calls which shared lib, for the Store's Libs tab.

The Libs tab has two sections and both read aa_cloud-superapp/data/lib-consumers.json,
which data/regen.sh writes on every build from THIS script (libs:appstore bakes it into
BuildConfig.LIB_CONSUMERS_B64), so no list of callers is typed anywhere:

  group       the fleet group that carries the two sections: the constellation group the
              build.json declares default_for_kind "lib"

  runtime     {fleet row id: [app dir]} for every lib row (an installable engine APK): the apps
              that BIND it. An app binds an engine when
                a) its build.json::engines names the row (`fleet`);
                b) it compiles a client module whose <module>/engine-client.json::engines
                   names the row (analytics, ops, decisions-link, ml-l-image, ml-l-sound); or
                c) its manifest, or a compiled lib's, queries the row's package or holds
                   <package>.BIND_ENGINE (core's <queries> for the fleetconfig engine, the
                   keyboard apps for Cloud-Keyboard-Libs); or
                d) it compiles a shared lib whose main source names the engine's package
                   (libs:net's AidlBackend.ENGINE_PKG for net-wg: a client that binds by
                   class name).
  build_time  one entry per shared lib that is NOT an engine (not installable): id, a short
              description (lib_apks.static_libs in lib-apks/build.json), the apps that compile
              it in (the build.json::modules maps plus the gradle closure, the same answer the
              ship workflows watch: cloud_android_lib_closure.inputs), the engine APKs that
              compile it in, and a primary consumer (1_cicd/src/data/lib-primary-consumers.json; informational only, every consumer ships on a lib edit).

              Each entry also carries its `category` (lib-categories.json, the one declared map), its
              `class` (lib-classes.json) and `ships`: "apk" when only engine APKs compile it in, else "static".
  categories  the ordered [{id,label}] the page groups by.

  gen ROOT      print the JSON (reads the fleet file for the lib rows)
  write ROOT    write aa_cloud-superapp/data/lib-consumers.json, for regen.sh
  check ROOT    exit 1 when the committed file differs from gen, or a non-engine lib has
                no description / a described lib is gone
"""
import importlib.util
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
OUT = "aa_cloud-superapp/data/lib-consumers.json"
SUPERAPP = "aa_cloud-superapp/build.json"
LIB_APKS = "ab_cloud-libs-shared/lib-apks/build.json"
PRIMARY = "1_cicd/src/data/lib-primary-consumers.json"
CATEGORIES = "1_cicd/src/data/lib-categories.json"   # #1511 the one category map of every shared lib
CLASSES = "1_cicd/src/data/lib-classes.json"
LIBS = "ab_cloud-libs-shared/libs"
# a companion APK that compiles shared libs like an app does, though it is not under a[acd]_*
EXTRA_APPS = ["ab_cloud-libs-shared/keyboard-engines"]
A = "{http://schemas.android.com/apk/res/android}"
# an ML lib states its application in its own id: ml-{t|l}-{domain}-{name} (the rule libs:appstore reads too)
ML = __import__("re").compile(r"^ml-[tl]-[a-z0-9]+(-|$)")


def _load(name, fname):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


closure = _load("cloud_android_lib_closure", "cloud_android_lib_closure.py")
engines_mod = _load("lib_apks_engines", "cloud-android-lib-apks-engines.py")


def jload(path, default=None):
    try:
        with open(path, encoding="utf-8") as h:
            return json.load(h)
    except (OSError, ValueError):
        return default


def apps_of(root):
    found = sorted(os.path.basename(os.path.dirname(b)) for b in glob.glob(os.path.join(root, "a[acd]_*", "build.json")))
    return found + [a for a in EXTRA_APPS if os.path.isfile(os.path.join(root, a, "build.json"))]


def manifest_refs(path):
    """(packages a <queries> names, permissions held) of one manifest, or empty."""
    try:
        r = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return set(), set()
    pk = {p.get(A + "name") for q in r.iter("queries") for p in q.iter("package")}
    pm = {p.get(A + "name") for p in r.iter("uses-permission")}
    return pk, pm


def generate(root):
    fleet = jload(os.path.join(root, FLEET), {}) or {}
    rows = [a for a in fleet.get("apps", []) if a.get("kind") == "lib"]
    edges, libs = closure.lib_edges(root), closure.shared_libs(root)
    apps = apps_of(root)
    inputs = {a: closure.inputs(root, a, edges, libs) for a in apps}
    engines = set(engines_mod.engines(root))
    row_of = {"lib-" + e: e for e in engines}

    # client modules that declare, for every app compiling them, the engine they bind
    client_of = {}
    for g in glob.glob(os.path.join(root, LIBS, "*", "engine-client.json")):
        mod = os.path.basename(os.path.dirname(g))
        for spec in (jload(g, {}) or {}).get("engines", {}).values():
            if isinstance(spec, dict) and spec.get("fleet"):
                client_of.setdefault(spec["fleet"], set()).add(mod)

    def mentions(pkg, skip):
        """Shared lib modules (not the engine's own) whose main source names [pkg] in a literal."""
        out = set()
        for m in libs:
            if m == skip:
                continue
            for dp, _, fs in os.walk(os.path.join(root, LIBS, m, "src", "main")):
                if any(f.endswith((".kt", ".java")) and '"%s"' % pkg in open(os.path.join(dp, f), errors="replace").read() for f in fs):
                    out.add(m)
                    break
        return out

    runtime = {}
    for row in rows:
        rid, pkg = row["id"], row.get("package") or ""
        callers = set()
        for app in apps:
            decl = (jload(os.path.join(root, app, "build.json"), {}) or {}).get("engines", {})
            if any(isinstance(s, dict) and s.get("fleet") == rid for s in decl.values()):
                callers.add(app)
            if (client_of.get(rid, set()) | (mentions(pkg, rid[4:]) if pkg else set())) & set(inputs[app]):
                callers.add(app)
            mans = [os.path.join(root, app, "app", "src", "main", "AndroidManifest.xml")]
            mans += [os.path.join(root, LIBS, m, "src", "main", "AndroidManifest.xml") for m in inputs[app]]
            for mf in mans:
                pk, pm = manifest_refs(mf)
                if pkg and (pkg in pk or pkg + ".BIND_ENGINE" in pm):
                    callers.add(app)
        if callers:
            runtime[rid] = sorted(callers)

    static = ((jload(os.path.join(root, LIB_APKS), {}) or {}).get("lib_apks", {}).get("static_libs", {}))
    primary = (jload(os.path.join(root, PRIMARY), {}) or {}).get("primary", {})
    asset = {r["id"]: (r.get("asset") or "").replace(".apk", "") for r in rows}
    in_engine = {}
    for e in sorted(engines):
        for m in closure.closure({e}, edges) - {e}:
            in_engine.setdefault(m, []).append(asset.get("lib-" + e) or "Cloud-Lib-" + e)

    cats = (jload(os.path.join(root, CATEGORIES), {}) or {}).get("categories", [])
    cat_of = {m: c["id"] for c in cats for m in c.get("members", [])}
    classes = (jload(os.path.join(root, CLASSES), {}) or {}).get("libs", {})
    build_time = []
    for m in sorted(libs - engines):
        entry = {"id": m, "ml": bool(ML.match(m)), "description": static.get(m, ""),
                 "compiled_by": sorted(a for a in apps if m in inputs[a]),
                 "engines": sorted(in_engine.get(m, []))}
        if primary.get(m):
            entry["primary"] = primary[m]
        entry["category"] = cat_of.get(m, "")
        entry["class"] = classes.get(m, {}).get("class", "")
        entry["ships"] = "apk" if entry["engines"] and not entry["compiled_by"] else "static"
        build_time.append(entry)
    groups = ((jload(os.path.join(root, SUPERAPP), {}) or {}).get("constellation", {}).get("groups", []))
    group = next((g["id"] for g in groups if g.get("default_for_kind") == "lib"), "")
    return {"group": group, "categories": [{"id": c["id"], "label": c["label"]} for c in cats],
            "runtime": runtime, "build_time": build_time}


def problems(root, gen):
    static = ((jload(os.path.join(root, LIB_APKS), {}) or {}).get("lib_apks", {}).get("static_libs", {}))
    ids = {e["id"] for e in gen["build_time"]}
    out = ["lib_apks.static_libs has no description for %s" % m for m in sorted(ids - set(static))]
    out += ["lib_apks.static_libs describes %s, which is not a shared non-engine lib" % m for m in sorted(set(static) - ids)]
    # #1511 every lib is in exactly one category, and a category names only libs
    cats = (jload(os.path.join(root, CATEGORIES), {}) or {}).get("categories", [])
    seen = {}
    for c in cats:
        for m in c.get("members", []):
            seen.setdefault(m, []).append(c["id"])
    out += ["lib-categories.json: %s is in no category (add it to one)" % m for m in sorted(ids - set(seen))]
    out += ["lib-categories.json: %s is in %d categories" % (m, len(v)) for m, v in sorted(seen.items()) if len(v) > 1]
    out += ["lib-categories.json lists %s, which is not a shared non-engine lib" % m for m in sorted(set(seen) - ids)]
    # ML libs never share a list with the others: flagged by the id rule, and none may be
    # unflagged or flagged without the rule (the page splits on this flag)
    out += ["%s: ml flag %s disagrees with the ml- id rule" % (e["id"], e["ml"]) for e in gen["build_time"] if e["ml"] != bool(ML.match(e["id"]))]
    out += ["every build-time lib is %s: nothing to split" % ("ML" if all(e["ml"] for e in gen["build_time"]) else "non-ML")
            for _ in [0] if len({e["ml"] for e in gen["build_time"]}) < 2]
    return out


def dump(gen):
    """Compact, one lib per line: a diff reads per entry."""
    j = lambda o: json.dumps(o, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    rt = ",\n".join(j(k) + ":" + j(v) for k, v in sorted(gen["runtime"].items()))
    bt = ",\n".join(j(e) for e in gen["build_time"])
    return '{"group":%s,\n"categories":%s,\n"runtime":{\n%s},\n"build_time":[\n%s\n]}\n' % (j(gen["group"]), j(gen["categories"]), rt, bt)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, root = argv[0], os.path.abspath(argv[1])
    gen = generate(root)
    if cmd == "gen":
        print(json.dumps(gen, indent=1, ensure_ascii=False))
        return 0
    if cmd == "write":
        with open(os.path.join(root, OUT), "w", encoding="utf-8") as h:
            h.write(dump(gen))
        return 0
    if cmd == "check":
        bad = problems(root, gen)
        have = jload(os.path.join(root, OUT))
        if have != gen:
            bad.append("%s does not match the build.json files / manifests -- run aa_cloud-superapp/data/regen.sh --constellation-only" % OUT)
        if not gen["group"]:
            bad.append("%s declares no constellation group with default_for_kind \"lib\"" % SUPERAPP)
        if not gen["runtime"] or not gen["build_time"]:
            bad.append("vacuous: %d runtime rows, %d build-time libs" % (len(gen["runtime"]), len(gen["build_time"])))
        for b in bad:
            print("FAIL: " + b, file=sys.stderr)
        if not bad:
            print("lib consumers ok: %d runtime engines with callers, %d build-time libs" % (len(gen["runtime"]), len(gen["build_time"])))
        return 1 if bad else 0
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
