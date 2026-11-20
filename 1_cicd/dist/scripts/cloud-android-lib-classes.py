# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-lib-classes.py ───
#!/usr/bin/env python3
"""
cloud-android-lib-classes — #763: every shared lib is classified, the class
matches what the source measurably is, and the non-GUI logic still compiled
into apps can only shrink.

DECLARATION  1_cicd/src/data/lib-classes.json (see its _doc for the classes)
USES         cloud_android_lib_closure.inputs — which apps compile which lib

  L1  every dir under ab_cloud-libs-shared/libs with a build.gradle is
      classified exactly once, and every classified name is such a dir
  L2  the class matches the source:
        engine          no application compiles it in
        contract/engine renders nothing (no View/Fragment/Compose/Material import, no layout)
        gui             renders something
        pinned          says why
  L3  mesh and contract libs stay under their budget_lines (src/main .kt/.java)
  L4  the backlog — (app, lib) edges where an app compiles in a `logic` lib —
      equals backlog_baseline: above it a non-GUI lib was newly compiled into an
      app; below it a migration landed and the baseline must be lowered with it

USAGE  cloud-android-lib-classes.py [ROOT]
EXIT   0 ok · 1 at least one violation
"""
import glob, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from cloud_android_lib_closure import SHARED_LIBS, inputs, lib_edges, shared_libs  # noqa: E402

DATA = "1_cicd/src/data/lib-classes.json"
CLASSES = {"gui", "contract", "engine", "mesh", "pinned", "logic"}
UI = re.compile(r"^import (android\.view\.|android\.widget\.|androidx\.fragment\.|androidx\.compose\.|"
                r"androidx\.recyclerview\.|androidx\.appcompat\.|com\.google\.android\.material\.)", re.M)


def sources(root, lib):
    top = os.path.join(root, SHARED_LIBS, lib, "src", "main")
    return [p for p in glob.glob(os.path.join(top, "**", "*.kt"), recursive=True)
            + glob.glob(os.path.join(top, "**", "*.java"), recursive=True)]


def renders(root, lib):
    if glob.glob(os.path.join(root, SHARED_LIBS, lib, "src", "main", "res", "layout*", "*.xml")):
        return True
    return any(UI.search(open(p, errors="replace").read()) for p in sources(root, lib))


def lines(root, lib):
    return sum(sum(1 for _ in open(p, errors="replace")) for p in sources(root, lib))


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    d = json.load(open(os.path.join(root, DATA)))
    decl = d["libs"]
    libs, edges = shared_libs(root), lib_edges(root)
    bad = []

    for m in sorted(libs - set(decl)):
        bad.append(f"L1 {m}: a shared lib with no class in {DATA}")
    for m in sorted(set(decl) - libs):
        bad.append(f"L1 {m}: classified in {DATA} but there is no {SHARED_LIBS}/{m}/build.gradle")
    for m, spec in sorted(decl.items()):
        if spec.get("class") not in CLASSES:
            bad.append(f"L1 {m}: class {spec.get('class')!r} is not one of {', '.join(sorted(CLASSES))}")

    users = {}
    for bj in sorted(glob.glob(os.path.join(root, "a[acd]_*", "build.json"))):
        app = os.path.basename(os.path.dirname(bj))
        for m in inputs(root, app, edges, libs):
            users.setdefault(m, []).append(app)

    for m, spec in sorted(decl.items()):
        if m not in libs:
            continue
        cls = spec.get("class")
        if cls == "engine" and users.get(m):
            bad.append(f"L2 {m}: an engine compiled into {', '.join(users[m])} — apps bind engines over IPC")
        # #1801 an engine may draw ITS OWN screen (e.g. the AI-key owner's key entry, which cannot live in an app because the keys
        # never leave the engine's process) only when the declaration says why; a contract never draws.
        own_ui = cls == "engine" and len(spec.get("own_ui", "")) >= 20
        if cls in ("contract", "engine") and renders(root, m) and not own_ui:
            bad.append(f"L2 {m}: classified {cls} but renders UI — a {cls} draws nothing")
        if cls == "gui" and not renders(root, m):
            bad.append(f"L2 {m}: classified gui but renders nothing (no View/Fragment/Compose import, no layout) — it is logic")
        if cls == "pinned" and not spec.get("why"):
            bad.append(f"L2 {m}: pinned without the OS boundary that pins it")
        if cls in ("mesh", "contract"):
            budget, n = spec.get("budget_lines"), lines(root, m)
            if not budget:
                bad.append(f"L3 {m}: a {cls} lib needs a budget_lines")
            elif n > budget:
                bad.append(f"L3 {m}: {n} lines, over its budget of {budget} — a {cls} lib compiled into "
                           f"{len(users.get(m, []))} app(s) must stay minimal; move the logic to an engine "
                           f"or raise the budget in {DATA} with the reason")

    backlog = sorted((app, m) for m, apps in users.items() if decl.get(m, {}).get("class") == "logic" for app in apps)
    print("BACKLOG  non-GUI logic still compiled into apps (lib: apps):")
    for m in sorted({m for _, m in backlog}, key=lambda m: (-len(users[m]), m)):
        print(f"  {m:24} {len(users[m]):2}  {', '.join(a.split('_', 1)[1] for a in users[m])}")
    pinned = sorted(m for m, s in decl.items() if s.get("class") == "pinned" and users.get(m))
    print(f"PINNED   {', '.join(f'{m}({len(users[m])})' for m in pinned) or 'none'}")
    base = d.get("backlog_baseline")
    if len(backlog) > base:
        bad.append(f"L4 backlog {len(backlog)} > baseline {base}: a non-GUI lib was newly compiled into an app — "
                   f"make it an engine reached over IPC, or classify it honestly")
    elif len(backlog) < base:
        bad.append(f"L4 backlog {len(backlog)} < baseline {base}: a migration landed — lower backlog_baseline "
                   f"to {len(backlog)} in {DATA} so it cannot creep back")

    for b in bad:
        print("FAIL     " + b)
    counts = {c: sum(1 for s in decl.values() if s.get("class") == c) for c in sorted(CLASSES)}
    print(f"── {len(libs)} lib(s): " + ", ".join(f"{n} {c}" for c, n in counts.items())
          + f"; backlog {len(backlog)} edge(s); {len(bad)} violation(s) ──")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
