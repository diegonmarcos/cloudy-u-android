# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud_android_lib_closure.py ───
"""
cloud_android_lib_closure — #763: which shared library directories are really
inputs of an application. ONE answer, imported by everything that asks:

  cloud-android-ship-repo-workflow-engine.sh   the ship workflow's trigger paths
  cloud-android-mesh-source-guard.py           G1 (closure reaches core+devtools)
                                               and G2 (the ship watches exactly it)
  cloud-android-lib-classes.py                 which non-GUI libs an app compiles

Before #763 the trigger paths were the build.json module map UNIONED with every
hand-written entry ever added, and a hand entry was never dropped while its
directory existed. Measured on 2026-10-02: cloud-vault watched analytics,
browser and updater and compiled none of them, cloud-mail watched analytics,
the SuperApp watched fin (it left with the MyFin dashboard). Each such entry
rebuilt the app for a change that cannot move its bytes, and — because the
publish gate hashes the same list — republished it as an update with nothing in
it.

An application's shared-lib INPUTS are the union of:
  closure   `project(':libs:X')` dependencies in the app's own gradle scripts
            and patches (patches: added lines only), expanded over the shared
            libs' own build.gradle edges — but only into modules the app
            INCLUDES (build.json::modules, settings `include`), because a lib
            edge like updater's `if (findProject(':libs:shizuku-adb-debug-tools'))`
            exists only in builds that include its target
  modules   every dir build.json::modules maps under the shared libs root (an
            included project is configured, and its manifest may merge)
  sources   a shared lib dir named by literal path in a non-comment line of
            the app's gradle/kts files, or as the path value of a non-_doc
            JSON key — the by-reference compiles (libs/sysdns'
            SystemDnsBridge in the terminals) and data reads no project edge shows

CLI  cloud_android_lib_closure.py ROOT [APP_DIR]   prints each app's inputs
"""
import glob, json, os, re, sys

SHARED_LIBS = "ab_cloud-libs-shared/libs"
SKIP = {".git", "build", ".gradle", "node_modules", ".cxx", "test", "tests"}
# implementation / api / debugImplementation / "${flavor}Implementation" … — a
# DEPENDENCY, never settings' `project(':libs:x').projectDir = …`
DEP = re.compile(r"""(?:\bapi|[iI]mplementation)\b["')\s]*,?\s*\(?\s*project\(\s*['"]:libs:([\w-]+)['"]""")
INCLUDE = re.compile(r"""include\b[^\n]*""")
# `for (n in listOf("a", "b")) { include(":libs:$n") }` -- a settings loop includes each listed lib (Gallery's nav bars)
INCLUDE_LOOP = re.compile(r"""for\s*\(\s*(\w+)\s+in\s+listOf\(([^)]*)\)\s*\)\s*\{?\s*include\(\s*["']:libs:\$\{?\1\}?["']""")
INCLUDED_LIB = re.compile(r"""['"]:libs:([\w-]+)['"]""")
PATH_REF = re.compile(re.escape(SHARED_LIBS) + r"/([\w-]+)")
# In JSON only a value that IS a path (nothing else in the string) counts ("source": "../ab_cloud-libs-shared/
# libs/sysdns/data/sysdns.json", or repo-root relative without the ../); prose
# and repo URLs that name a lib (".../tree/main/ab_cloud-libs-shared/libs/gh")
# are not build inputs.
JSON_REF = re.compile(r'"(?!_doc)[^"]*"\s*:\s*"(?:\.\./)*' + re.escape(SHARED_LIBS) + r'/([\w-]+)(?:/[^"\s]*)?"')


def read(p):
    with open(p, encoding="utf-8", errors="replace") as h:
        return h.read()


def _code(line):
    return not line.lstrip().startswith(("//", "*", "/*", "#"))


def deps(text, patch=False):
    out = set()
    for line in text.splitlines():
        if patch:
            if not line.startswith("+") or line.startswith("+++"):
                continue
            line = line[1:]
        if _code(line):
            out.update(DEP.findall(line))
    return out


def walk(top, exts):
    for d, dirs, files in os.walk(top):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in files:
            if f.endswith(exts):
                yield os.path.join(d, f)


def scripts(top):
    return walk(top, (".gradle", ".gradle.kts", ".patch"))


def closure(seed, edges, allowed=None):
    seen, todo = set(), list(seed)
    while todo:
        m = todo.pop()
        if m in seen or (allowed is not None and m not in allowed):
            continue
        seen.add(m)
        todo.extend(edges.get(m, ()))
    return seen


def lib_edges(root):
    edges = {}
    for g in glob.glob(os.path.join(root, SHARED_LIBS, "*", "build.gradle*")):
        edges.setdefault(os.path.basename(os.path.dirname(g)), set()).update(deps(read(g)))
    return edges


def shared_libs(root):
    return {os.path.basename(os.path.dirname(g)) for g in glob.glob(os.path.join(root, SHARED_LIBS, "*", "build.gradle*"))}


def module_map(root, app):
    """Shared lib names build.json::modules maps (top-level or under build)."""
    try:
        cfg = json.load(open(os.path.join(root, app, "build.json")))
    except (OSError, ValueError):
        return set()
    out = set()
    for mods in (cfg.get("modules"), (cfg.get("build") or {}).get("modules") if isinstance(cfg.get("build"), dict) else None):
        if not isinstance(mods, dict):
            continue
        for m in mods.values():
            if isinstance(m, dict) and m.get("dir"):
                d = os.path.normpath(os.path.join(app, m["dir"]))
                if d.startswith(SHARED_LIBS + os.sep):
                    out.add(d.split(os.sep)[len(SHARED_LIBS.split("/"))])
    # #1682 the ADB owner's libs (build.json::adb_owner_modules, by name) are part of the owner app's build while
    # 1_cicd/src/data/adb-owner.json names it, so they are watched and hashed like module dirs.
    try:
        owner = json.load(open(os.path.join(root, "1_cicd/src/data/adb-owner.json"))).get("adb_owner")
        if owner == os.path.basename(app):
            for name in (cfg.get("adb_owner_modules") or {}):
                if name.startswith("libs:"):
                    out.add(name[len("libs:"):])
    except (OSError, ValueError):
        pass
    return out


def included(root, app):
    """Shared libs the app's settings include; None when no settings declare any
    and no module map exists (then expansion is unrestricted, as before #763)."""
    found = set(module_map(root, app))
    for s in walk(os.path.join(root, app), ("settings.gradle", "settings.gradle.kts", ".patch")):
        patch = s.endswith(".patch")
        for line in read(s).splitlines():
            if patch:
                if not line.startswith("+") or line.startswith("+++"):
                    continue
                line = line[1:]
            if _code(line):
                for inc in INCLUDE.findall(line):
                    found.update(INCLUDED_LIB.findall(inc))
        for _var, items in INCLUDE_LOOP.findall(read(s)):
            found.update(re.findall(r"""["']([\w-]+)["']""", items))
    return found or None


def cargo_libs(root):
    """Shared libs that are Cargo crates, not Gradle modules (fleet-webserver): never a project
    edge, only ever reached by a path an app's JSON or gradle names (by_reference)."""
    return {os.path.basename(os.path.dirname(g)) for g in glob.glob(os.path.join(root, SHARED_LIBS, "*", "Cargo.toml"))}


def by_reference(root, app, libs):
    libs = set(libs) | cargo_libs(root)
    out = set()
    for p in walk(os.path.join(root, app), (".gradle", ".gradle.kts", ".json")):
        rx = JSON_REF if p.endswith(".json") else PATH_REF
        for line in read(p).splitlines():
            if _code(line):
                out.update(m for m in rx.findall(line) if m in libs)
    return out


def analyse(root, app, edges=None, libs=None):
    """(seed, closure, {lib: why}) — the direct deps, the dependency closure, and
    every shared lib directory that is an input of [app] with the reason."""
    edges = lib_edges(root) if edges is None else edges
    libs = shared_libs(root) if libs is None else libs
    seed = set()
    for p in scripts(os.path.join(root, app)):
        seed |= deps(read(p), patch=p.endswith(".patch"))
    cl = closure(seed, edges, included(root, app))
    why = {}
    for m in by_reference(root, app, libs):
        why[m] = "sources"
    for m in module_map(root, app):
        why[m] = "modules"
    for m in cl:
        why[m] = "closure"
    return seed, cl, {m: w for m, w in why.items() if m in libs or m in cargo_libs(root)}


def inputs(root, app, edges=None, libs=None):
    """{lib: why} for every shared lib directory that is an input of [app]."""
    return analyse(root, app, edges, libs)[2]


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    apps = argv[1:] or sorted(os.path.basename(os.path.dirname(b)) for b in glob.glob(os.path.join(root, "a[acd]_*", "build.json")))
    edges, libs = lib_edges(root), shared_libs(root)
    for app in apps:
        got = inputs(root, app, edges, libs)
        print(f"{app:28} " + " ".join(f"{m}({w[0]})" for m, w in sorted(got.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
