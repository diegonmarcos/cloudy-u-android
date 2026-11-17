#!/usr/bin/env python3
"""cloud-android-fleet-slice -- #1783 P1/P3: the publish identity of the fleet manifest.

fleet-config.json (libs/fleetconfig-model assets) is baked into every app, but one app's
declaration in it is that app's input only. The publish gate therefore hashes, instead of
the file's git blob:
  * planners (rebuild-isolation.json::manifest_watchers: SuperApp, Cloud Account, the
    engine lib builder): the WHOLE manifest, canonical JSON, doc keys removed
  * every other app: its SLICE -- the shared schema, its own `apps` entry, the `libs`
    entries it compiles and the `stores` whose used_by names it or one of those libs
Doc keys (identity-exclusions.json) never count, so a doc-only edit moves no identity.

USAGE  cloud-android-fleet-slice.py [--root R] [--rev REV] whole
       cloud-android-fleet-slice.py [--root R] [--rev REV] slice <app-dir>
       cloud-android-fleet-slice.py [--root R] [--rev REV] identity <app-dir> [--scoped]
              prints the identity line `<sha256>  <label>` the gate adds, or nothing when
              the app does not compile the manifest
       cloud-android-fleet-slice.py [--root R] check      union of slices covers the manifest
"""
import hashlib, json, os, subprocess, sys

SCHEMA = ("schema_version", "contract", "classes", "migrate", "kinds")
OWNER_ONLY = ("bundle",)   # Cloud Account's vault routes: planners only


def conf(root):
    return json.load(open(os.path.join(root, "1_cicd/src/data/identity-exclusions.json")))


def isolation(root):
    return json.load(open(os.path.join(root, "1_cicd/src/data/rebuild-isolation.json")))


def strip(x, c):
    if isinstance(x, dict):
        return {k: strip(v, c) for k, v in x.items()
                if k not in c["doc_keys"] and not k.startswith(tuple(c["doc_key_prefixes"]))}
    if isinstance(x, list):
        return [strip(i, c) for i in x]
    return x


def load(root, rev, manifest):
    if rev:
        raw = subprocess.run(["git", "-C", root, "show", f"{rev}:{manifest}"], capture_output=True, text=True, check=True).stdout
    else:
        raw = open(os.path.join(root, manifest)).read()
    return json.loads(raw)


def digest(x):
    return hashlib.sha256(json.dumps(x, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def slice_of(d, app):
    entry = next((v for v in d.get("apps", {}).values() if v.get("module") == app), None)
    libs = set(entry.get("libs", [])) if entry else set()
    names = libs | {app}
    out = {k: d[k] for k in SCHEMA if k in d}
    out["app"] = entry
    out["libs"] = {k: v for k, v in d.get("libs", {}).items() if k in libs}
    out["stores"] = {k: v for k, v in d.get("stores", {}).items() if names & set(v.get("used_by", []))}
    out["resolve"] = {k: v for k, v in d.get("resolve", {}).items() if ("@" + app + "/") in k}
    return out


def whole_of(d):
    return {k: v for k, v in d.items()}


def main(argv):
    a = list(argv)
    root, rev = ".", None
    while a and a[0] in ("--root", "--rev"):
        if a[0] == "--root":
            root = a[1]
        else:
            rev = a[1]
        a = a[2:]
    if not a:
        print(__doc__)
        return 2
    c = conf(root)
    manifest = c["normalized_json"][0]
    planners = isolation(root).get("manifest_watchers", {}).get(manifest, [])
    cmd = a[0]
    if cmd == "check":
        d = strip(load(root, rev, manifest), c)
        apps = [v["module"] for v in d["apps"].values()]
        covered = {s for app in apps for s in slice_of(d, app)["stores"]}
        orphan = sorted(set(d["stores"]) - covered)
        # a store used only by libs no app compiles / by nobody is reported, not judged: planners hash it whole
        print("slices: %d apps, %d/%d stores covered by an app slice, %d planner-only: %s" % (
            len(apps), len(covered), len(d["stores"]), len(orphan), " ".join(orphan[:8])))
        return 0
    d = strip(load(root, rev, manifest), c)
    if cmd == "whole":
        print(digest(whole_of(d)))
        return 0
    if cmd == "slice":
        print(json.dumps(slice_of(d, a[1]), sort_keys=True, indent=1))
        return 0
    if cmd == "identity":
        app, scoped = a[1], "--scoped" in a[2:]
        if app in planners or scoped:
            print("%s  %s" % (digest(whole_of(d)), manifest))
        else:
            print("%s  fleet-slice:%s" % (digest(slice_of(d, app)), app))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.exit(main(sys.argv[1:]))
