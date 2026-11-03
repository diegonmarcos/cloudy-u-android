#!/usr/bin/env python3
"""
cloud-android-configs-tabs-guard — every app's Configs starts General | Perms | About on the top nav (#1439).

DECLARATION  1_cicd/src/data/configs-pages.json::tabs (the required first ids, the lib needles, the pending ledger)

  T0  the shared Configs screen of libs:ui-nav-bars draws the tabs: FleetConfigsPage's entries are exactly `first`, in
      that order, and FleetConfigsScreen draws FleetConfigsTabs over them
  T1  an app that declares Configs tabs of its own (configs_pages.top_nav, ui.modes with tab `configs`, or the pages
      of ui.sections[id=configs]) starts them with `first`; every such declaration of the app agrees
  T2  an app with no tabs of its own conforms through the shared screen only while it reaches it (it is not in
      configs_pages.pending)
  T3  an app breaking T1/T2 is named in tabs.pending with a reason; a pending entry for a conforming app is stale;
      every entry names a real app
  T4  (#1443) a declared tab is a drawn tab: an app with Configs tabs of its own hands General | Perms | About to the shared
      pages (a `tabs.draw` needle in its non-test sources), so the order cannot be declared in build.json yet drawn by nothing
  T5  every a[acd]_* directory is checked: one without a build.json is named in tabs.no_build_json with a reason

USAGE  cloud-android-configs-tabs-guard.py [ROOT]      EXIT 0 ok · 1 at least one violation
"""
import glob
import json
import os
import re
import sys

MIN_REASON = 30


def own_tabs(d):
    """Every declaration of the app's own Configs tabs: [(where, [ids])]."""
    out = []
    cp = d.get("configs_pages") or {}
    if cp.get("top_nav"):
        out.append(("configs_pages.top_nav", [t.get("id") for t in cp["top_nav"]]))
    ui = d.get("ui") or {}
    modes = [m for m in ui.get("modes") or [] if isinstance(m, dict) and m.get("tab") == "configs"]
    if modes:
        ids = [m["kind"][len("fleet_"):] if str(m.get("kind", "")).startswith("fleet_") else m.get("id") for m in modes]
        out.append(("ui.modes[tab=configs]", ids))
    for s in ui.get("sections") or []:
        if isinstance(s, dict) and s.get("id") == "configs":
            out.append(("ui.sections[configs].pages", [p.get("id") if isinstance(p, dict) else p for p in s.get("pages") or []]))
    return out


def app_sources(root, app):
    """The app's non-test Kotlin / Java sources, as text."""
    out = []
    for dp, dn, fn in os.walk(os.path.join(root, app)):
        dn[:] = [d for d in dn if d not in ("test", "androidTest", "build", "node_modules")]
        for f in fn:
            if f.endswith((".kt", ".java")):
                try:
                    out.append(open(os.path.join(dp, f), encoding="utf-8", errors="replace").read())
                except OSError:
                    pass
    return "\n".join(out)


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    rule_all = json.load(open(os.path.join(root, "1_cicd/src/data/configs-pages.json")))
    rule = rule_all["tabs"]
    first = rule["first"]
    exempt = {k for k in rule_all.get("exempt", {}) if not k.startswith("_")}
    unreached = {k for k in rule_all.get("pending", {}) if not k.startswith("_")}
    pending = {k: v for k, v in rule["pending"].items() if not k.startswith("_")}
    bad = []

    # T0 the shared screen
    lib = rule["lib"]
    try:
        src = open(os.path.join(root, lib["file"])).read()
    except OSError:
        src = ""
        bad.append("T0 the shared Configs source is missing: " + lib["file"])
    if src:
        body = src.split(lib["enum"], 1)[1].split("}", 1)[0] if lib["enum"] in src else ""
        ids = re.findall(r'^\s*[A-Z]\w*\("([a-z_]+)"', body, re.M)
        if ids != first:
            bad.append("T0 FleetConfigsPage entries are %s, not %s" % ("|".join(ids) or "missing", "|".join(first)))
        for k in ("screen_needle", "tabs_needle"):
            if lib[k] not in src:
                bad.append("T0 the shared Configs screen no longer draws its tabs (%r missing)" % lib[k])

    apps = {}
    for f in sorted(glob.glob(os.path.join(root, "a[acd]_*/build.json"))):
        app = os.path.basename(os.path.dirname(f))
        try:
            apps[app] = json.load(open(f))
        except ValueError as e:
            bad.append("T1 %s: build.json does not parse (%s)" % (app, e))

    checked, broken = 0, []
    for app, d in apps.items():
        if app in exempt:
            continue
        checked += 1
        why = []
        decls = own_tabs(d)
        for where, ids in decls:
            if ids[:len(first)] != first:
                why.append("%s starts %s" % (where, "|".join(map(str, ids[:len(first)])) or "empty"))
        if len({tuple(ids) for _, ids in decls}) > 1:
            why.append("its Configs tab declarations disagree")
        if not decls and app in unreached:
            why.append("it does not reach the shared Configs screen (configs_pages.pending)")
        if why:
            broken.append(app)
            if app not in pending:
                bad.append("T1 %s: Configs does not start %s: %s" % (app, " | ".join(first), "; ".join(why)))
        elif app in pending:
            bad.append("T3 %s: conforms now; delete its entry in configs-pages.json::tabs.pending" % app)
    # T4 the tabs are drawn: an app with Configs tabs of its own reaches the shared pages from its sources
    needles = rule.get("draw", [])
    for app, d in apps.items():
        if app in exempt or not own_tabs(d):
            continue
        if needles and not any(n in app_sources(root, app) for n in needles):
            bad.append("T4 %s: declares Configs tabs but no source draws the shared pages (%s)" % (app, " | ".join(needles)))
    # T5 no app directory escapes the guard
    skipped = {k: v for k, v in rule.get("no_build_json", {}).items() if not k.startswith("_")}
    for dpath in sorted(glob.glob(os.path.join(root, "a[acd]_*/"))):
        app = os.path.basename(os.path.normpath(dpath))
        if app not in apps and app not in skipped and app not in exempt and not glob.glob(os.path.join(dpath, "build.json")):
            bad.append("T5 %s: an app directory with no build.json is not checked; name it in tabs.no_build_json with a reason" % app)
    for app, reason in skipped.items():
        if len(reason) < MIN_REASON:
            bad.append("T5 tabs.no_build_json %s: the reason is shorter than %d chars" % (app, MIN_REASON))
    for app, reason in pending.items():
        if app not in apps:
            bad.append("T3 tabs.pending names no app: " + app)
        if len(reason) < MIN_REASON:
            bad.append("T3 tabs.pending %s: the reason is shorter than %d chars" % (app, MIN_REASON))

    for b in bad:
        print("FAIL  " + b)
    print("configs-tabs guard: %d app(s) checked, %d pending (%s), %d violation(s)"
          % (checked, len(broken), ", ".join(broken) or "none", len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
