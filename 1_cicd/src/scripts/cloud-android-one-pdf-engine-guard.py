#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-one-pdf-engine-guard — the fleet keeps ONE PDF     ║
# ║ engine, reached by manifest, never copied in                     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Ticket #463 was filed as "pdf.js is copied per-app in
# cloud-drive AND cloud-mail -- extract a shared lib" and measured on
# origin/main THAT WAS FALSE: cloud-mail ships zero pdf assets and zero
# viewer, and its only PDF code hands the attachment OUT via an ACTION_VIEW
# chooser, which #458 already wired to the PDF reader declared in ac_cloud-
# drive's manifest. So the fleet ALREADY has exactly one PDF engine reached
# by manifest -- and nothing enforces that. The invariant holds by luck.
#
# This guard makes a second engine impossible to arrive silently. It fails
# when an in-process PDF renderer appears in any app tree other than
# ac_cloud-drive, and its failure MESSAGE TEACHES THE RULE (a guard that only
# says "unexpected file" gets deleted by the next person who hits it).
# Extracting pdf.js into ab_cloud-libs-shared is deliberately NOT built: an
# abstraction with one consumer is cost with no payer, and this fleet has had
# to unwind speculative structure before.
#
# Everything it knows comes from 1_cicd/src/data/one-pdf-engine-guard.json.
# No app, no path and no renderer name appears below. Adding a renderer
# filename, a code marker, or a legitimate exclusion is a DATA edit.
#
# No ripgrep: it is not on the CI runner, and testers here have previously
# passed on its absence rather than on their assertions.

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "one-pdf-engine-guard.json")
RULE = ("one PDF engine in the fleet, reached by manifest; the way to add "
        "a consumer is the manifest, not a copy.")


def _norm(p):
    return os.path.normpath(p).replace(os.sep, "/")


def scan_roots(repo, cfg):
    """Top-level app/solution trees to scan, minus the engine owner.

    Returns [] when there is nothing to look at -- which is a structural
    error for the caller, never a clean verdict. A guard that "found nothing"
    because there were no apps is a guard that reports green on an empty
    checkout, which is the exact false-green this fleet keeps hitting.
    """
    prefixes = [p for p in cfg.get("app_prefixes", [])]
    owner = cfg.get("allowed_owner")
    roots = []
    try:
        entries = sorted(os.listdir(repo))
    except OSError:
        return None
    for e in entries:
        if owner and e == owner:
            continue
        if any(e.startswith(p) for p in prefixes):
            if os.path.isdir(os.path.join(repo, e)):
                roots.append(e)
    return roots


def excluded_paths(cfg):
    """The named exclusions plus the engine owner itself: an owner nested below a top-level tree
    (#1949: ab_cloud-libs-shared/libs/pdf) is carved out of the scan of that tree, not skipped with it."""
    paths = [(_norm(x["path"])) for x in cfg.get("exclusions", [])]
    owner = cfg.get("allowed_owner")
    if owner:
        paths.append(_norm(owner))
    return paths


def is_excluded(rel, excls):
    """True if rel path lies under one of the named exclusions."""
    rel = _norm(rel)
    return any(rel == e or rel.startswith(e + "/") for e in excls)


def scan_tree(repo, tree, cfg):
    """Every (relpath, kind, marker) in one app tree that is a renderer.

    kind is 'filename', 'dirname' or 'marker'. dirname hits are recorded
    against the FIRST ancestor directory that matches (the walk stops
    descending into a matched dir, so one binding = one hit, not N).
    """
    r = cfg["renderer"]
    filenames = set(r["filenames"])
    dirnames = set(d.lower() for d in r["dirnames"])
    markers = r["code_markers"]
    exts = tuple(r["scan_extensions"])
    skip = set(cfg.get("prune_dirs", []))
    excls = excluded_paths(cfg)
    hits = []

    root = os.path.join(repo, tree)
    for dirpath, dirnames_os, filenames_os in os.walk(root):
        dirnames_os[:] = [d for d in dirnames_os if d not in skip]
        rel_dir = _norm(os.path.relpath(dirpath, repo))
        # A directory whose own name is a renderer binding is a hit on its own.
        if dirnames_os:
            pass
        for d in list(dirnames_os):
            if d.lower() in dirnames:
                rel = _norm(os.path.join(rel_dir, d))
                if not is_excluded(rel, excls):
                    hits.append((rel, "dirname", d))
                # binding dir: everything under it is the same engine; prune.
                dirnames_os.remove(d)
        for fn in filenames_os:
            p = os.path.join(dirpath, fn)
            rel = _norm(os.path.relpath(p, repo))
            if is_excluded(rel, excls):
                continue
            if fn in filenames:
                hits.append((rel, "filename", fn))
                continue
            if not fn.endswith(exts):
                continue
            # content scan only for code-ish extensions
            try:
                with open(p, encoding="utf-8", errors="replace") as fh:
                    lines = fh.readlines()
            except OSError:
                continue
            for i, line in enumerate(lines, 1):
                for m in markers:
                    if m in line:
                        hits.append((rel, "marker", m))
                        break
                else:
                    continue
                break  # one hit per file: the engine, not each mention
    return hits


def exclusions_engaged(repo, cfg):
    """Which named exclusions actually hold a renderer on disk.

    A green run reports these. If an exclusion stops being load-bearing (the
    vendored renderer that justified it is gone, or the path is mis-typed and
    matches nothing), that is visible on every green instead of a silent skip.
    """
    r = cfg["renderer"]
    filenames = set(r["filenames"])
    dirnames = set(d.lower() for d in r["dirnames"])
    markers = r["code_markers"]
    exts = tuple(r["scan_extensions"])
    skip = set(cfg.get("prune_dirs", []))
    engaged = []
    for x in cfg.get("exclusions", []):
        epath = _norm(x["path"])
        eroot = os.path.join(repo, epath)
        if not os.path.isdir(eroot):
            engaged.append((epath, "MISSING", None))
            continue
        found = None
        for dirpath, dirnames_os, filenames_os in os.walk(eroot):
            dirnames_os[:] = [d for d in dirnames_os if d not in skip]
            for d in dirnames_os:
                if d.lower() in dirnames:
                    found = found or d
            for fn in filenames_os:
                if fn in filenames:
                    found = found or fn
                if found:
                    break
                if not fn.endswith(exts):
                    continue
                try:
                    with open(os.path.join(dirpath, fn), encoding="utf-8",
                              errors="replace") as fh:
                        text = fh.read()
                except OSError:
                    continue
                if any(m in text for m in markers):
                    found = found or "code marker"
            if found:
                break
        engaged.append((epath, "engines_present" if found else "NO_RENDERER_FOUND", found))
    return engaged


def main(argv):
    args = [a for a in argv[1:] if a != "--repo"]
    if not args:
        print("usage: cloud-android-one-pdf-engine-guard.py <repo-root> "
              "[policy.json]", file=sys.stderr)
        return 2
    target = args[0]
    policy_path = args[1] if len(args) > 1 else DEFAULT_POLICY

    with open(policy_path, encoding="utf-8") as fh:
        cfg = json.load(fh)

    if not os.path.isdir(target):
        print(f"ERROR: '{target}' is not a directory", file=sys.stderr)
        return 2

    roots = scan_roots(target, cfg)
    if roots is None:
        print(f"ERROR: cannot read '{target}'", file=sys.stderr)
        return 2
    if not roots:
        print(f"ERROR: no app tree found under {target} (against prefixes "
              f"{cfg['app_prefixes']}); a guard that scans nothing must not "
              f"report clean.", file=sys.stderr)
        return 2

    all_hits = []
    for tree in roots:
        all_hits.extend((h[0], tree, h[1], h[2])
                        for h in scan_tree(target, tree, cfg))

    excls_engaged = exclusions_engaged(target, cfg)
    if all_hits:
        print(f"FAIL   one-pdf-engine: a second in-process PDF renderer "
              f"outside {cfg['allowed_owner']}", file=sys.stderr)
        print(f"RULE   {RULE}", file=sys.stderr)
        print(f"       The fleet's engine is {cfg['engine']}, "
              f"reached by apps through the Android manifest "
              f"(ACTION_VIEW + the declared PDF-reader chooser). A new "
              f"consumer declares that engine in the manifest; it is "
              f"NEVER copied in.", file=sys.stderr)
        for rel, tree, kind, marker in sorted(all_hits):
            print(f"  [{kind}] {rel}   ({marker})", file=sys.stderr)
        print(f"       {len(all_hits)} in-process renderer file(s) outside "
              f"{cfg['allowed_owner']}. Remove the copy and reach the "
              f"engine through the manifest instead.", file=sys.stderr)
        return 1

    print(f"ok     one-pdf-engine: no in-process PDF renderer outside "
          f"{cfg['allowed_owner']} (scanned {len(roots)} app tree(s)); "
          f"fleet stays at one engine, reached by manifest.")
    for epath, status, found in excls_engaged:
        if status == "MISSING":
            print(f"  !     excluded path NOT on disk: {epath} -- the "
                  f"exclusion no longer hides anything; prune the entry",
                  file=sys.stderr)
        elif status == "NO_RENDERER_FOUND":
            print(f"  !     excluded path holds no renderer: {epath} -- "
                  f"exclusion is not load-bearing; verify or remove it",
                  file=sys.stderr)
        else:
            print(f"  ok    excluded (vendored, read-only): {epath} "
                  f"({found})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))