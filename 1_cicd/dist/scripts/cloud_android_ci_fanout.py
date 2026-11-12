# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud_android_ci_fanout.py ───
#!/usr/bin/env python3
"""
cloud_android_ci_fanout — #763: how many workflow runs does a change start?

Evaluates every .github/workflows/*.yml `on: push` trigger against a change set
exactly as GitHub does for a push to main, and prints the workflows that would
run. The measure #763 is judged by: before it, one commit that touched
libs/devtools started 53 runs, 23 of them guards that run on every push
whatever it touches.

GitHub's semantics, implemented here and nowhere else:
  - no `paths` and no `paths-ignore`      → runs on every push
  - `paths`: patterns in order, the LAST one that matches a file decides
    (a `!` pattern excludes); the workflow runs when any file is included
  - `paths-ignore`: runs when any file matches none of the patterns
  - `*` never crosses `/`, `**` does, `?` is one non-`/` character
  - a `branches` list that does not name main means no run

#796: a run that starts is not a rebuild. Every ship job opens with the
publish gate, which hashes the app's build inputs (cloud-android-source-identity
.sh) and skips the whole job when nothing in that set moved. `--builds` answers
the question the gate answers on the runner, from the same engines, for a change
set that has not been pushed: which app APKs, which lib APKs (ship-cloud-libs
gates each module on its own closure: build.sh module-paths) and which rootfs
companions (the fork engine: build.sh companion-paths, or the same derivation
when the vendored engine predates it) would actually be rebuilt.

USAGE
  cloud_android_ci_fanout.py [--wf DIR] --files F [F ...]
  cloud_android_ci_fanout.py [--wf DIR] --commit SHA      (SHA^..SHA)
  cloud_android_ci_fanout.py [--wf DIR] --range A..B
  cloud_android_ci_fanout.py [--wf DIR] --scenarios FILE  (one table, every scenario)
  add --names to list the workflows, --json for machine output, --builds to
  count the APKs the gates would rebuild (slower: one identity per fired ship)
  and (#836) the apps fleet-refresh.yml would ship later instead
EXIT 0 always (it measures; the guards assert)
"""
import functools, glob, json, os, re, subprocess, sys

import yaml


def glob_re(pattern):
    out, i = "", 0
    while i < len(pattern):
        c = pattern[i]
        if pattern.startswith("**", i):
            # `**/` between separators (or leading) also matches NO directory on GitHub: `docs/**/*.md` fires for docs/hello.md.
            if pattern.startswith("**/", i) and (i == 0 or pattern[i - 1] == "/"):
                out += "(?:.*/)?"
                i += 3
                continue
            out += ".*"
            i += 2
            continue
        if c == "[":
            # GitHub's filter patterns: [] matches one character listed in the brackets or in a range (a-z, A-Z, 0-9).
            # `a[acd]_*` was escaped as a literal before, so a path declared with a set never matched anything here.
            j = pattern.find("]", i + 1)
            if j > i + 1:
                out += "[" + "".join(ch if ch == "-" else re.escape(ch) for ch in pattern[i + 1:j]) + "]"
                i = j + 1
                continue
        out += {"*": "[^/]*", "?": "[^/]"}.get(c, re.escape(c))
        i += 1
    return re.compile(out + r"\Z")


def triggers(path):
    """(kind, patterns) for a push to main: kind in all/paths/ignore/none."""
    y = yaml.safe_load(open(path)) or {}
    on = y.get(True, y.get("on"))
    if isinstance(on, str):
        on = {on: None}
    elif isinstance(on, list):
        on = {k: None for k in on}
    if not isinstance(on, dict) or "push" not in on:
        return "none", []
    push = on["push"] or {}
    branches = push.get("branches")
    if branches is not None and not any(glob_re(b).match("main") for b in branches):
        return "none", []
    if branches is None and push.get("tags") is not None:
        return "none", []  # a tags-only push trigger never fires for a branch push
    if "paths" in push:
        return "paths", list(push["paths"])
    if "paths-ignore" in push:
        return "ignore", list(push["paths-ignore"])
    return "all", []


def fires(kind, patterns, files):
    if kind == "none" or not files:
        return False
    if kind == "all":
        return True
    if kind == "ignore":
        rx = [glob_re(p) for p in patterns]
        return any(not any(r.match(f) for r in rx) for f in files)
    rx = [(p.startswith("!"), glob_re(p.lstrip("!"))) for p in patterns]
    for f in files:
        verdict = False
        for neg, r in rx:
            if r.match(f):
                verdict = not neg
        if verdict:
            return True
    return False


# ── #796: would the bytes move? ───────────────────────────────────────────
IDENTITY = "1_cicd/src/scripts/cloud-android-source-identity.sh"
LIB_APKS = "ab_cloud-libs-shared/lib-apks/build.sh"


def under(files, paths):
    """True when a changed file is one of `paths` or inside one of them."""
    return any(f == p or f.startswith(p + "/") for f in files for p in paths)


def work_dir(wf):
    m = re.search(r"^  WORK_DIR: (\S+)$", open(wf).read(), re.M)
    return m.group(1) if m else None


def _run(root, *cmd):
    r = subprocess.run(list(cmd), capture_output=True, text=True, cwd=root)
    return [l for l in r.stdout.splitlines() if l.strip()] if r.returncode == 0 else None


@functools.lru_cache(maxsize=None)
def identity_paths(root, app):
    """The paths the publish gate hashes for `app` (test sets and the tester dir
    already taken out), or None when the engine refuses (then: rebuilt)."""
    out = _run(root, "sh", os.path.join(root, IDENTITY), "explain", app)
    if out is None:
        return None
    return tuple(l.split("  ", 1)[1] for l in out if "  " in l and not l.endswith("missing")) or None


@functools.lru_cache(maxsize=None)
def lib_apks(root):
    return tuple(l.split()[0] for l in _run(root, "bash", os.path.join(root, LIB_APKS), "list") or ())


@functools.lru_cache(maxsize=None)
def lib_apk_paths(root, module):
    out = _run(root, "bash", os.path.join(root, LIB_APKS), "module-paths", module)
    return tuple(out) if out else None


@functools.lru_cache(maxsize=None)
def companions(root, app):
    """((id, paths), ...) — each release.companions[] entry with the input set its
    gate reads: the fork engine's own answer when the vendored build.sh has
    `companion-paths`, else the same derivation (fleet-lib.json → artifact
    identity_files, plus module_dir; a legacy paths_from is taken as is)."""
    try:
        cfg = json.load(open(os.path.join(root, app, "build.json")))
    except (OSError, ValueError):
        return ()
    out = []
    for c in (cfg.get("release") or {}).get("companions") or []:
        cid = c.get("id")
        paths = _run(root, "bash", os.path.join(root, app, "build.sh"), "companion-paths", cid) \
            if "companion-paths" in open(os.path.join(root, app, "build.sh"), errors="replace").read() else None
        if paths is None:
            paths = [p.rstrip("/") for p in c.get("paths_from") or []]
        if paths is None or not paths:
            paths = set()
            try:
                decl = json.load(open(os.path.join(root, app, "fleet-lib.json")))
                blob = json.load(open(os.path.join(root, app, decl.get("declared_in") or "build.json")))
                for part in (decl.get("at") or "").split("."):
                    blob = blob.get(part) if isinstance(blob, dict) else None
                for rel in (blob or {}).get("identity_files") or []:
                    paths.add(os.path.relpath(os.path.normpath(os.path.join(app, rel)), "."))
                if c.get("module_dir"):
                    paths.add(os.path.join(app, c["module_dir"]))
            except (OSError, ValueError):
                pass
        out.append((cid, tuple(sorted(paths))))
    return tuple(out)


def builds(root, wf_dir, hit, files):
    """{apps: [...], libs: [...], rootfs: [...]} — what the gates would rebuild."""
    out = {"apps": [], "libs": [], "rootfs": []}
    for h in hit:
        if not h.startswith("ship"):
            continue
        app = work_dir(os.path.join(wf_dir, h))
        if app is None:
            out["apps"].append(h)  # no WORK_DIR: nothing to gate on, it builds
            continue
        if app.endswith("/lib-apks"):
            for m in lib_apks(root):
                p = lib_apk_paths(root, m)
                if p is None or under(files, p):
                    out["libs"].append(m)
            continue
        p = identity_paths(root, app)
        if p is None or under(files, p):
            out["apps"].append(app)
        for cid, paths in companions(root, app):
            if not paths or under(files, paths):
                out["rootfs"].append(f"{app}:{cid}")
    return out


def refreshed(wf_dir, files, root="."):
    """#836: the apps fleet-refresh.yml would ship for this change set — those
    whose DEFERRED shared-lib inputs (hashed, not push-watched; comment lines
    inside the managed fence) contain a changed file. The refresh diffs from
    each app's last published commit; with that commit as the change set's
    parent this is exactly its answer."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.dont_write_bytecode = True
    from cloud_android_workflow_paths import deferred_inputs, refresh_input
    out = []
    for wf in sorted(glob.glob(os.path.join(wf_dir, "ship-*.yml"))):
        text = open(wf).read()
        libs = [d.rstrip("*").rstrip("/") for d in deferred_inputs(text)
                if refresh_input(root, d)]  # #870: cross-app source dirs only
        app = re.search(r"^  WORK_DIR: (\S+)$", text, re.M)
        if libs and "fleet-refresh-" in text and under(files, libs):
            out.append(app.group(1) if app else os.path.basename(wf))
    return out


def scenarios_table(root, wf_dir, path):
    spec = json.load(open(path))
    rows = []
    for sc in spec["scenarios"]:
        files = sc["files"]
        hit = sorted(os.path.basename(w) for w in glob.glob(os.path.join(wf_dir, "*.yml"))
                     if fires(*triggers(w), files))
        b = builds(root, wf_dir, hit, files)
        b["refresh"] = refreshed(wf_dir, files, root)
        rows.append({"id": sc["id"], "runs": len(hit), "ship": sum(h.startswith("ship") for h in hit),
                     "apps": len(b["apps"]), "libs": len(b["libs"]), "rootfs": len(b["rootfs"]),
                     "refresh": len(b["refresh"]), "built": b, "expect": sc.get("expect")})
    return rows


def changed(spec, root):
    out = subprocess.run(["git", "-C", root, "diff", "--name-only", spec],
                         capture_output=True, text=True, check=True).stdout
    return [l for l in out.splitlines() if l]


def main(argv):
    wf_dir, files, names, as_json, root = ".github/workflows", [], False, False, "."
    want_builds, scenarios = False, None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--wf":
            wf_dir = argv[i + 1]; i += 2; continue
        if a == "--scenarios":
            scenarios = argv[i + 1]; i += 2; continue
        if a == "--names":
            names = True
        elif a == "--builds":
            want_builds = True
        elif a == "--json":
            as_json = True
        elif a == "--commit":
            files = changed(f"{argv[i + 1]}^..{argv[i + 1]}", root); i += 2; continue
        elif a == "--range":
            files = changed(argv[i + 1], root); i += 2; continue
        elif a == "--files":
            files = argv[i + 1:]; break
        i += 1
    if scenarios:
        rows = scenarios_table(root, wf_dir, scenarios)
        if as_json:
            print(json.dumps(rows))
        else:
            print(f"{'scenario':46} {'runs':>4} {'ship':>4} {'apps':>4} {'libs':>4} {'rootfs':>6} {'refresh':>7}")
            for r in rows:
                print(f"{r['id']:46} {r['runs']:4} {r['ship']:4} {r['apps']:4} {r['libs']:4} {r['rootfs']:6} {r['refresh']:7}")
        return 0
    hit = sorted(os.path.basename(w) for w in glob.glob(os.path.join(wf_dir, "*.yml"))
                 if fires(*triggers(w), files))
    ship = [h for h in hit if h.startswith("ship")]
    b = builds(root, wf_dir, hit, files) if want_builds else None
    if b is not None:
        b["refresh"] = refreshed(wf_dir, files, root)
    if as_json:
        print(json.dumps({"files": len(files), "runs": len(hit), "ship": len(ship), "workflows": hit,
                          **({"built": b} if b else {})}))
    else:
        if names:
            print("\n".join(hit))
        print(f"{len(hit)} runs ({len(ship)} ship, {len(hit) - len(ship)} other) for {len(files)} changed file(s)")
        if b:
            print(f"rebuilt: {len(b['apps'])} app APK(s), {len(b['libs'])} lib APK(s), {len(b['rootfs'])} rootfs lib(s)")
            print(f"deferred to fleet-refresh.yml: {len(b['refresh'])} app(s)")
            for k in ("apps", "libs", "rootfs", "refresh"):
                if b[k]:
                    print(f"  {k}: " + " ".join(b[k]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
