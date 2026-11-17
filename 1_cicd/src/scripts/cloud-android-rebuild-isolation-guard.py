#!/usr/bin/env python3
"""cloud-android-rebuild-isolation-guard -- #870 (owner rule): what a change REBUILDS is isolated.

  * app dir change        -> that app only (+ its explicit non-lib inputs)
  * engine lib change     -> that ONE Cloud-Lib APK, 0 apps (apps bind it at runtime)
  * static lib change     -> EVERY app that compiles it (an edit under libs:bottomnav
                             alone must ship Cloud Store), never an unrelated app; no lib APK
  * a lib change never rebuilds another lib; a shared manifest never starts an app

Two checks, both against the SOURCE workflows (1_cicd/src/cicd):
  STATIC   every path an app ship workflow watches is something that app owns:
           its WORK_DIR, its fork/source engine script, its explicit_inputs, or a
           static lib it compiles. An engine lib, a shared
           manifest or another app's dir in a push list is a violation.
  DYNAMIC  for a change set (--commit SHA | --range A..B | --files F... |
           --scenarios FILE), cloud_android_ci_fanout's predicted rebuild set must
           hold: every predicted app owns a changed path; every predicted lib APK
           is an engine whose dir changed (or a harness path changed); a change set
           touching a single engine dir yields exactly one lib APK.
Fleet-refresh (scheduled, never per push) is reported, not judged.

USAGE  cloud-android-rebuild-isolation-guard.py [--root R] [--wf DIR] static
       cloud-android-rebuild-isolation-guard.py [--root R] [--wf DIR] (--commit SHA | --range A..B | --files F... | --scenarios FILE)
EXIT   0 isolated - 1 violation(s) - 2 usage
"""
import fnmatch, glob, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
import cloud_android_ci_fanout as fan  # noqa: E402
from cloud_android_workflow_paths import isolation, lib_apk_modules, is_manifest  # noqa: E402
from cloud_android_lib_closure import inputs as lib_inputs  # noqa: E402

LIBS = "ab_cloud-libs-shared/libs"
HARNESS = ("ab_cloud-libs-shared/lib-apks/", "ab_cloud-libs-shared/build.json", "1_cicd/src/cicd/ship-cloud-libs.yml")
EXEMPT = {"ab_cloud-libs-shared/lib-apks"}


def ship_workflows(wf_dir):
    out = {}
    for w in sorted(glob.glob(os.path.join(wf_dir, "ship-*.yml"))):
        app = fan.work_dir(w)
        if app:
            out[os.path.basename(w)] = (app, w)
    return out


def static_owners(root, wf_dir):
    """{static lib: set(apps allowed to watch it)} -- every app that compiles it."""
    engines = lib_apk_modules(root)
    cons = {}
    for _n, (app, _w) in ship_workflows(wf_dir).items():
        if app in EXEMPT or not os.path.exists(os.path.join(root, app, "build.json")):
            continue
        for lib in lib_inputs(root, app):
            cons.setdefault(lib, set()).add(app)
    own = {}
    for lib, who in cons.items():
        if lib in engines:
            continue
        own[lib] = set(who)
    return own


def owns(root, iso, owners, app, name, f):
    if f == app or f.startswith(app + "/"):
        return True
    if any(f == e or f == e.rstrip("*").rstrip("/") or f.startswith(e.rstrip("*").rstrip("/") + "/")
           for e in iso.get("explicit_inputs", {}).get(app, [])):
        return True
    slug = re.sub(r"^ship-|\.yml$", "", name)
    if f.startswith("1_cicd/src/scripts/") and slug in f and any(fnmatch.fnmatchcase(f, g) for g in iso.get("own_extra_globs", [])):
        return True
    m = re.match(re.escape(LIBS) + r"/([\w-]+)(?:/|$)", f)
    return bool(m and app in owners.get(m.group(1), ()))


def lib_manifests(root, iso):
    """#1783: tracked shared manifests that lie INSIDE the shared libs dir (a static lib an app watches)."""
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", LIBS], capture_output=True, text=True, check=True).stdout.splitlines()
    except (OSError, subprocess.CalledProcessError):
        return []
    return [f for f in out if any(fnmatch.fnmatchcase(f, g) for g in iso.get("manifests", []))]


def static_check(root, wf_dir):
    iso, owners, bad = isolation(root), static_owners(root, wf_dir), []
    mans = lib_manifests(root, iso)
    for name, (app, w) in ship_workflows(wf_dir).items():
        if app in EXEMPT:
            continue
        kind, pats = fan.triggers(w)
        # THE RULE ITSELF (#870 "a regenerated shared manifest never starts an app"): a manifest inside a
        # watched lib dir is matched by that dir's glob, which the per-path loop below cannot see.
        for man in mans:
            if app not in iso.get("manifest_watchers", {}).get(man, []) and fan.fires(kind, pats, [man]):
                bad.append(f"{name}: a push to the shared manifest {man} starts this app -- add `!{man}` to its push filter "
                           f"(rebuild-isolation.json::manifest_watchers lists the only apps that may watch it)")
        for p in pats if kind == "paths" else []:
            if p.startswith("!"):
                continue
            probe = p[:-3] if p.endswith("/**") else p
            if p in (f"{app}/**",) or owns(root, iso, owners, app, name, probe + "/x") or owns(root, iso, owners, app, name, probe):
                continue
            why = ("an ENGINE lib (ships its own Cloud-Lib APK)" if probe.startswith(LIBS + "/") and probe.rsplit("/", 1)[-1] in lib_apk_modules(root)
                   else "a static lib this app does not compile" if probe.startswith(LIBS + "/")
                   else "a shared manifest/data file" if is_manifest(root, p)
                   else "a path another owner holds")
            bad.append(f"{name}: watches {p} -- {why}; an app ships on its own dir only (defer it: hashed, not watched)")
    return bad


def dynamic_check(root, wf_dir, files, label=""):
    iso, owners, bad = isolation(root), static_owners(root, wf_dir), []
    engines = lib_apk_modules(root)
    hit = sorted(os.path.basename(w) for w in glob.glob(os.path.join(wf_dir, "*.yml")) if fan.fires(*fan.triggers(w), files))
    b = fan.builds(root, wf_dir, hit, files)
    wfs = {app: n for n, (app, _w) in ship_workflows(wf_dir).items()}
    for app in b["apps"]:
        name = wfs.get(app)
        if name is None or app in EXEMPT:
            continue
        if not any(owns(root, iso, owners, app, name, f) for f in files):
            bad.append(f"{label}predicted rebuild of {app} but it owns no changed path")
    harness = any(f.startswith(HARNESS) for f in files)
    dirs = {m.group(1) for f in files for m in [re.match(re.escape(LIBS) + r"/([\w-]+)/", f)] if m}
    for lib in b["libs"]:
        if lib not in engines:
            bad.append(f"{label}predicted lib APK {lib} is a static lib -- only runtime engines ship a Cloud-Lib APK")
        elif not harness and lib not in dirs:
            bad.append(f"{label}predicted lib APK {lib} but its dir did not change (another lib's change rebuilds it)")
    only = {d for d in dirs}
    other = [f for f in files if not f.startswith(LIBS + "/")]
    if len(only) == 1 and not harness and len(b["libs"]) > 1:
        bad.append(f"{label}a single-lib change ({next(iter(only))}) predicts {len(b['libs'])} lib APKs: {' '.join(b['libs'])}")
    return bad, b


def main(argv):
    root, wf = ".", None
    a = list(argv)
    while a and a[0] in ("--root", "--wf"):
        if a[0] == "--root":
            root = a[1]
        else:
            wf = a[1]
        a = a[2:]
    wf = wf or os.path.join(root, "1_cicd/src/cicd")
    if not a:
        print(__doc__)
        return 2
    bad = []
    if a[0] == "static":
        bad = static_check(root, wf)
    elif a[0] == "--scenarios":
        for sc in json.load(open(a[1]))["scenarios"]:
            bad += dynamic_check(root, wf, sc["files"], sc["id"] + ": ")[0]
        bad += static_check(root, wf)
    else:
        if a[0] == "--commit":
            files = fan.changed(f"{a[1]}^..{a[1]}", root)
        elif a[0] == "--range":
            files = fan.changed(a[1], root)
        elif a[0] == "--files":
            files = a[1:]
        else:
            print(__doc__)
            return 2
        bad, b = dynamic_check(root, wf, files)
        print(f"predicted: {len(b['apps'])} app(s) {' '.join(b['apps'])} | {len(b['libs'])} lib APK(s) {' '.join(b['libs'])}")
    for m in bad:
        print("VIOLATION " + m)
    print("rebuild isolation: %s" % ("HELD" if not bad else f"{len(bad)} violation(s)"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
