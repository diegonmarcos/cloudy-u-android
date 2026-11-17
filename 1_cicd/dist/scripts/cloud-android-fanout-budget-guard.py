# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fanout-budget-guard.py ───
#!/usr/bin/env python3
"""cloud-android-fanout-budget-guard -- #1783 P2: a push cannot start more than N app ships by accident.

The rebuild-scenarios ratchet locks a fan-out in; it never budgeted one (the 42 for fleet-config.json
was raised silently). This guard counts the per-app ship workflows (ship-*.yml, the lib-APK builder
excluded) the change set of a push starts, after taking out the paths 1_cicd/src/data/fanout-budget.json
allows (each with a reason and a ticket), and fails above the budget.

USAGE  cloud-android-fanout-budget-guard.py [--root R] [--wf DIR] [--budget N] (--commit SHA | --range A..B | --files F...)
EXIT   0 within budget - 1 over budget or a bad allowlist - 2 usage
"""
import glob, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
import json  # noqa: E402
import cloud_android_ci_fanout as fan  # noqa: E402

DATA = "1_cicd/src/data/fanout-budget.json"


def load(root, data=None):
    return json.load(open(data or os.path.join(root, DATA)))


def check_config(cfg):
    bad = []
    if not isinstance(cfg.get("budget"), int) or cfg["budget"] < 0:
        bad.append("budget is not a non-negative integer")
    for i, a in enumerate(cfg.get("allow", [])):
        if not a.get("glob"):
            bad.append(f"allow[{i}] has no glob")
        if not str(a.get("reason", "")).strip():
            bad.append(f"allow[{i}] ({a.get('glob')}) has no reason")
        if not str(a.get("ticket", "")).strip():
            bad.append(f"allow[{i}] ({a.get('glob')}) has no ticket")
    return bad


def shipped(wf_dir, files):
    out = []
    for w in sorted(glob.glob(os.path.join(wf_dir, "ship-*.yml"))):
        if fan.work_dir(w) and fan.work_dir(w).endswith("/lib-apks"):
            continue
        if fan.fires(*fan.triggers(w), files):
            out.append(os.path.basename(w))
    return out


def evaluate(wf_dir, files, cfg):
    rx = [fan.glob_re(a["glob"]) for a in cfg.get("allow", []) if a.get("glob")]
    counted = [f for f in files if not any(r.match(f) for r in rx)]
    return shipped(wf_dir, counted), [f for f in files if f not in counted]


def main(argv):
    root, wf, budget, a = ".", None, None, list(argv)
    while a and a[0] in ("--root", "--wf", "--budget", "--data"):
        if a[0] == "--root":
            root = a[1]
        elif a[0] == "--wf":
            wf = a[1]
        elif a[0] == "--data":
            os.environ["FANOUT_BUDGET_DATA"] = a[1]
        else:
            budget = int(a[1])
        a = a[2:]
    wf = wf or os.path.join(root, "1_cicd/src/cicd")
    if not a:
        print(__doc__)
        return 2
    if a[0] == "--commit":
        files = fan.changed(f"{a[1]}^..{a[1]}", root)
    elif a[0] == "--range":
        files = fan.changed(a[1], root)
    elif a[0] == "--files":
        files = a[1:]
    else:
        print(__doc__)
        return 2
    cfg = load(root, os.environ.get("FANOUT_BUDGET_DATA"))
    bad = check_config(cfg)
    limit = cfg["budget"] if budget is None else budget
    ships, allowed = evaluate(wf, files, cfg)
    print(f"{len(files)} changed file(s), {len(allowed)} allowlisted; starts {len(ships)} app ship(s) (budget {limit})")
    if len(ships) > limit:
        bad.append(f"this change starts {len(ships)} app ships, budget is {limit}: " + " ".join(s[5:-4] for s in ships[:12])
                   + (" ..." if len(ships) > 12 else "")
                   + " -- narrow the change (a per-app declaration belongs in the app's own dir) or, for a genuine shared-lib edit,"
                   + f" add its path to {DATA} with a reason and a ticket")
    for m in bad:
        print("VIOLATION " + m)
    print("fan-out budget: %s" % ("HELD" if not bad else f"{len(bad)} violation(s)"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
