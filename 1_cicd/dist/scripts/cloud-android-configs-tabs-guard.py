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
  T6  (#1470) an app may keep an order its owner chose, but only by name in tabs.app_order with its exact `order` and a reason;
      its declarations must equal that order, so the exception is per app and never loosens another app
  T7  (#1630) the fleet-default tail: the strip is [General + own tabs] | Agents, Perms, Logcat, X-Ray App, About. The lib holds that
      order (FleetConfigsExtensions.TAIL_IDS + stripOrder), and every app that reaches the shared screen links the three
      opt-in libs (libs:fleetconfigs-agents / -logcat / -xray in build.json::modules) or is named in tabs.tail.pending with a
      reason; a pending entry for an app that links all three is stale (delete it in the commit that wires the app)
  T8  (#1630) FLEET CONFORMANCE, the anti-drift guard: EVERY app is checked and listed. An app whose Configs strip is drawn by its own host
      (it declares Configs tabs of its own, or its sources draw the shared page bodies) must pass its declared pages through
      FleetConfigsExtensions.withTail (the one place that appends the five and draws the `|`) -- or, for a WebView host (Cloud Media Center, Cloud C3 Webserver: Tauri, no Kotlin), a web page that draws the five after the `|` with the three native ones opening FleetConfigsActivity through the intent bridge (tail.web_hosts); or, for Cloud Calc whose strip is its
      modes, through ConfigsTail whose KINDS are the five in order and whose build.json declares all five kinds; an app on the shared
      screen gets the strip from the lib (T0 / T7). Missing one of the five (a lib not linked, a kind not declared), the five out of
      order, or no `|` (the lib's separatorBefore) is a violation, named per app; the only way out is tabs.tail.pending with a reason
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
    tops = [os.path.join(root, app)]
    try:  # #1652 android.ui_module_dirs: shared lib modules that hold the app's UI (Cloud Writer: libs:writer-ui)
        for extra in (json.load(open(os.path.join(root, app, "build.json"))).get("android") or {}).get("ui_module_dirs") or []:
            tops.append(os.path.normpath(os.path.join(root, app, extra)))
    except (OSError, ValueError):
        pass
    for top in tops:
        for dp, dn, fn in os.walk(top):
            dn[:] = [d for d in dn if d not in ("test", "androidTest", "build", "node_modules")]
            for f in fn:
                if f.endswith((".kt", ".java")):
                    try:
                        out.append(open(os.path.join(dp, f), encoding="utf-8", errors="replace").read())
                    except OSError:
                        pass
    return "\n".join(out)


def gradle_libs(root, app):
    """Libs an app links in its own gradle/settings text (an app whose build.json carries no module map): {"libs:x"}."""
    out = set()
    for dp, dn, fn in os.walk(os.path.join(root, app)):
        dn[:] = [x for x in dn if x not in ("build", "node_modules", "src", "test", "androidTest")]
        for f in fn:
            if f.startswith(("build", "settings")) and f.endswith((".gradle", ".gradle.kts")):
                try:
                    t = open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                out.update("libs:" + m for m in re.findall(r"libs:(fleetconfigs-[a-z]+)", t))
    return out


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    rule_all = json.load(open(os.path.join(root, "1_cicd/src/data/configs-pages.json")))
    rule = rule_all["tabs"]
    first = rule["first"]
    exempt = {k for k in rule_all.get("exempt", {}) if not k.startswith("_")}
    unreached = {k for k in rule_all.get("pending", {}) if not k.startswith("_")}
    pending = {k: v for k, v in rule["pending"].items() if not k.startswith("_")}
    app_order = {k: v for k, v in rule.get("app_order", {}).items() if not k.startswith("_")}
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
        if os.path.islink(os.path.dirname(f)):   # a symlinked a[acd]_* entry (the web portal) is no app module of this repo
            continue
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
        want = app_order.get(app, {}).get("order")
        for where, ids in decls:
            if want:
                if ids != want:
                    why.append("%s is %s, the owner's declared order is %s" % (where, "|".join(map(str, ids)) or "empty", "|".join(want)))
            elif ids[:len(first)] != first:
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
        src = app_order.get(app, {}).get("source")
        if src:  # #1469 a web Configs page: it must draw exactly the declared order (no Kotlin to hold a needle)
            try:
                text = open(os.path.join(root, src), encoding="utf-8").read()
                drawn = re.findall(r"^\s*\{ id: '([a-z_]+)', label:", text.split("var TABS = [", 1)[1].split("\n  ];", 1)[0], re.M)
            except (OSError, IndexError):
                drawn = []
            if drawn != app_order[app]["order"]:
                bad.append("T4 %s: %s draws %s, not the declared %s" % (app, src, "|".join(drawn) or "no tabs", "|".join(app_order[app]["order"])))
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
    for app, o in app_order.items():
        if app not in apps:
            bad.append("T6 tabs.app_order names no app: " + app)
        elif not own_tabs(apps[app]):
            bad.append("T6 tabs.app_order %s: the app declares no Configs tabs of its own" % app)
        if not isinstance(o, dict) or not isinstance(o.get("order"), list) or not set(first) <= set(o["order"]):
            bad.append("T6 tabs.app_order %s: `order` must be a list holding %s" % (app, "|".join(first)))
        if not isinstance(o, dict) or len(str(o.get("reason", ""))) < MIN_REASON:
            bad.append("T6 tabs.app_order %s: the reason is shorter than %d chars" % (app, MIN_REASON))
    for app, reason in pending.items():
        if app not in apps:
            bad.append("T3 tabs.pending names no app: " + app)
        if len(reason) < MIN_REASON:
            bad.append("T3 tabs.pending %s: the reason is shorter than %d chars" % (app, MIN_REASON))

    # T7 the fleet-default tail (#1630)
    report = []
    tail = rule.get("tail")
    if tail:
        try:
            ext = open(os.path.join(root, tail["file"])).read()
        except OSError:
            ext = ""
            bad.append("T7 the extensions source is missing: " + tail["file"])
        if ext:
            m = re.search(r"TAIL_IDS: List<String> = listOf\(([^)]*)\)", ext)
            ids = re.findall(r'"([a-z_]+)"', m.group(1)) if m else []
            if ids != tail["ids"]:
                bad.append("T7 FleetConfigsExtensions.TAIL_IDS is %s, not %s" % ("|".join(ids) or "missing", "|".join(tail["ids"])))
            for n in tail.get("needles", []):
                if n not in ext:
                    bad.append("T7 the shared strip no longer builds the tail (%r missing)" % n)
        tpend = {k: v for k, v in tail.get("pending", {}).items() if not k.startswith("_")}
        host_needles = tail.get("host_tail", [])
        draw_needles = tail.get("host_draws", [])
        mode_hosts = {k: v for k, v in tail.get("mode_hosts", {}).items() if not k.startswith("_")}
        web_hosts = {k: v for k, v in tail.get("web_hosts", {}).items() if not k.startswith("_")}
        for app, d in sorted(apps.items()):
            if app in exempt:
                report.append((app, "exempt", "-", "EXEMPT  " + rule_all["exempt"][app][:70]))
                continue
            if app in unreached:
                report.append((app, "unreached", "-", "PENDING (does not reach the shared Configs screen: configs_pages.pending)"))
                continue
            mods = set((d.get("modules") or {}).keys()) | gradle_libs(root, app)
            missing = [l for l in tail["libs"] if l not in mods]
            srcs = app_sources(root, app)
            ui = d.get("ui") or {}
            own_strip = bool(own_tabs(d)) or any(isinstance(x, dict) and x.get("id") in ("configs", "settings", "config") and x.get("pages") for x in ui.get("sections") or [])
            host = own_strip or any(n in srcs for n in draw_needles) or app in web_hosts
            lacks = ["not linked: " + ", ".join(m.split("-")[-1] for m in missing)] if missing else []
            if host and app in mode_hosts:
                mh = mode_hosts[app]
                try:
                    ksrc = open(os.path.join(root, mh["file"]), encoding="utf-8").read()
                    mk = re.search(r"KINDS: List<String> = listOf\(([^)]*)\)", ksrc)
                    kinds = re.findall(r'"([a-z_]+)"', mk.group(1)) if mk else []
                except OSError:
                    kinds = []
                if [k[len("fleet_"):] for k in kinds] != tail["ids"]:
                    lacks.append("mode kinds %s are not the tail order %s" % ("|".join(kinds) or "missing", "|".join(tail["ids"])))
                declared = {m.get("kind") for m in (d.get("ui") or {}).get("modes") or [] if isinstance(m, dict) and m.get("tab") == "configs"}
                gone = ["fleet_" + i for i in tail["ids"] if "fleet_" + i not in declared]
                if gone:
                    lacks.append("build.json ui.modes[tab=configs] declares no " + ", ".join(gone))
                if mh["needle"] not in srcs:
                    lacks.append("the strip no longer orders its modes (%r missing)" % mh["needle"])
            elif app in web_hosts:
                wh = web_hosts[app]
                try:
                    wtext = open(os.path.join(root, wh["source"]), encoding="utf-8").read()
                except OSError:
                    wtext = ""
                    lacks.append("its web Configs source %s is missing" % wh["source"])
                lacks += ["its web Configs page does not draw %r" % n for n in wh["needles"] if wtext and n not in wtext]
                lacks += ["%s ends %s, not the tail %s" % (w, "|".join(map(str, ids[-len(tail["ids"]):])), "|".join(tail["ids"])) for w, ids in own_tabs(d) if ids[-len(tail["ids"]):] != tail["ids"]]
            elif host and host_needles and not any(n in srcs for n in host_needles):
                lacks.append("its own Configs strip does not end with the tail (no %s in its sources)" % " / ".join(host_needles))
            how = "own-host strip, tail via " + ("ConfigsTail" if app in mode_hosts else "native bridge (web page)" if app in web_hosts else "withTail") if host else "shared screen (FleetConfigsScreen)"
            if app in tpend and lacks:
                how = "NOT DRAWN (pending): " + how.split(",")[0]
            if lacks and app not in tpend:
                bad.append("T7 %s: Configs lacks the fleet-default tail (%s); wire it or name it in tabs.tail.pending" % (app, "; ".join(lacks)))
            elif not lacks and app in tpend:
                bad.append("T7 %s: has the tail now; delete its entry in configs-pages.json::tabs.tail.pending" % app)
            report.append((app, how, "|".join(tail["ids"]), ("PENDING " + tpend[app][:60]) if app in tpend else ("FAIL " + "; ".join(lacks) if lacks else "OK")))
        for app, reason in tpend.items():
            if app not in apps:
                bad.append("T7 tabs.tail.pending names no app: " + app)
            if len(reason) < MIN_REASON:
                bad.append("T7 tabs.tail.pending %s: the reason is shorter than %d chars" % (app, MIN_REASON))

    for app, how, ids, verdict in report:   # T8 every app checked, listed
        print("  %-30s %-44s [%s]  %s" % (app, how, ids.replace("|", " | ") if ids != "-" else "-", verdict))
    for b in bad:
        print("FAIL  " + b)
    print("configs-tabs guard: %d app(s) checked, %d pending (%s), %d violation(s)"
          % (checked, len(broken), ", ".join(broken) or "none", len(bad)))
    if tail:
        n_ok = sum(1 for r in report if r[3] == "OK")
        print("fleet tail (T7/T8): %d app(s) listed, %d carry %s, %d pending/exempt, %d violation(s)"
              % (len(report), n_ok, " | ".join(tail["ids"]), len(report) - n_ok, sum(1 for b in bad if b.startswith("T7"))))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
