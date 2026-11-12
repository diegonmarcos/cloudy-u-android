#!/usr/bin/env python3
"""
cloud-android-configs-pages-guard — every app except Cloud SuperApp has Configs ▸ General and Configs ▸ About,
drawn once by libs:ui-nav-bars (package fleetconfigs) from the app's build.json::configs_pages.

DECLARATION  1_cicd/src/data/configs-pages.json (the required sections and About fields, the lib's must-hold
             needles, the exemption (Cloud SuperApp) and the pending ledger: apps not reaching the pages yet, each
             with its reason)

  G0  the lib still carries both pages, the anchor grid, the Update section, the extended About, the scaffold's two
      entries, the Views/WebView activity, the build-time resolver, the Cloud Store / Cloud SuperApp texts and
      libs:perms' asked-store version and Grant all walk; FleetConfigs.REQUIRED_SECTIONS == general_required
  G1  every non-exempt app declares configs_pages.general and configs_pages.about
  G2  general.sections: every required id exactly once, nothing outside required + optional + default
  G3  about meets about_min (#1352 v2, About must be extensive): tagline, a long description, features, screens
      {name, what}, guide steps, privacy, related fleet apps {app, why}, backends {id, why} on a fleet_endpoints
      service with a mesh DNS name, credits for a fork (licences kind in fork_kinds); every grant of build.json::perms
      has a why (about.perms_why or perm_why); the v1 field `what` is gone; every derived field resolves at build
      time (name, repo, licence from licenses/curated.json, crash sink port, debug API port); version and package
      fall back to the installed package at runtime, so they are not required in build.json
  G4  the app reaches the pages (its sources call the shared scaffold, FleetConfigsActivity.intent, or one of the
      lib's menu helpers FleetConfigsMenu / FleetConfigsEntries), or it is
      named in `pending` with a reason; a pending app that reaches them is stale
      An app with no native menu (React Native, Tauri, Cordova, the office host) is named in shortcut_reach with a
      reason and must link libs:ui-nav-bars: the lib publishes the two pages as launcher shortcuts for it.
  G5  every exempt / pending / shortcut_reach entry names a real app and carries a reason

USAGE  cloud-android-configs-pages-guard.py [ROOT]      EXIT 0 ok · 1 at least one violation
"""
import json
import os
import re
import sys

DATA = "1_cicd/src/data/configs-pages.json"
SKIP_DIRS = {"build", ".git", "node_modules", ".gradle", "test", "androidTest", "target", "dist"}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def apps_of(root):
    out = []
    for d in sorted(os.listdir(root)):
        if re.match(r"^a[acd]_", d) and os.path.isfile(os.path.join(root, d, "build.json")):
            out.append(d)
    return out


def app_sources(root, app):
    tops = [os.path.join(root, app)]
    # #1652 android.ui_module_dirs: shared lib modules that hold the app's UI (Cloud Writer's whole UI is libs:writer-ui, its
    # MainActivity a thin shell). The same declaration the nav-shape and configs-tabs guards read, so G4 judges the code that draws the app.
    try:
        for extra in (load(os.path.join(root, app, "build.json")).get("android") or {}).get("ui_module_dirs") or []:
            tops.append(os.path.normpath(os.path.join(root, app, extra)))
    except (OSError, ValueError):
        pass
    for top in tops:
        for base, dirs, files in os.walk(top):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
            for f in files:
                if f.endswith((".kt", ".java")):
                    yield os.path.join(base, f)


def reaches(root, app, needles):
    for p in app_sources(root, app):
        s = read(p)
        if any(n in s for n in needles):
            return True
    return False


def links_ui_nav_bars(root, app, build):
    """The app compiles libs:ui-nav-bars: its build.json::modules lists it, or a settings / build / patch file of the app names it."""
    if "libs:ui-nav-bars" in (build.get("modules") or {}):
        return True
    for base, dirs, files in os.walk(os.path.join(root, app)):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        for f in files:
            if re.search(r"(settings|build)(-extras)?\.gradle(\.kts)?$|\.patch$", f) and ":libs:ui-nav-bars" in read(os.path.join(base, f)):
                return True
    return False


def derived_about(root, spec, app, build):
    """What fleet-configs.gradle derives; empty string when it would not resolve."""
    android = build.get("android") or {}
    appid = android.get("application_id") or ""
    lic = ""
    lp = os.path.join(root, spec["licences"])
    if os.path.isfile(lp):
        lic = ((load(lp).get("directories") or {}).get(app) or {}).get("licence") or ""
    ws = os.path.join(root, spec["webserver"])
    port = ((load(ws).get("runtime") or {}).get("port")) if os.path.isfile(ws) else None
    dp = os.path.join(root, app, "debug-api.json")
    ports = (load(dp).get("ports") or {}) if os.path.isfile(dp) else {}
    dport = ports.get(appid) or (list(ports.values())[0] if len(ports) == 1 else None)
    return {
        "name": build.get("display_name") or build.get("name") or "",
        "version": android.get("version_name") or "",
        "application_id": appid,
        "repo": spec["repo_prefix"] + app,
        "license": lic,
        "crash": "http://127.0.0.1:%s/__app__/api/crashes" % port if port else "",
        "debug": "http://127.0.0.1:%s/" % dport if dport else "",
    }


def check(root):
    spec = load(os.path.join(root, DATA))
    bad = []
    apps = apps_of(root)
    exempt = {k: v for k, v in (spec.get("exempt") or {}).items() if not k.startswith("_")}
    pending = {k: v for k, v in (spec.get("pending") or {}).items() if not k.startswith("_")}
    shortcut_reach = {k: v for k, v in (spec.get("shortcut_reach") or {}).items() if not k.startswith("_")}

    # G0
    lib = spec["lib"]
    for h in lib["must_hold"]:
        if h["needle"] not in read(os.path.join(root, h["file"])):
            bad.append("G0 %s no longer carries `%s` (%s)" % (h["file"], h["needle"].replace("\n", "\\n"), h["why"]))
    decl = read(os.path.join(root, lib["decl"]))
    m = re.search(r"REQUIRED_SECTIONS: List<String> = listOf\(([^)]*)\)", decl)
    got = re.findall(r'"([^"]+)"', m.group(1)) if m else None
    if got != spec["general_required"]:
        bad.append("G0 %s REQUIRED_SECTIONS is %s but %s::general_required is %s" % (lib["decl"], got, DATA, spec["general_required"]))

    services = {}
    ep = os.path.join(root, spec["endpoints"])
    if os.path.isfile(ep):
        services = {s.get("id"): s for s in ((load(ep).get("fleet_endpoints") or {}).get("services") or [])}

    allowed = set(spec["general_required"]) | set(spec.get("general_optional") or []) | set(spec.get("general_default") or [])
    mn = spec["about_min"]
    curated = {}
    lp = os.path.join(root, spec["licences"])
    if os.path.isfile(lp):
        curated = load(lp).get("directories") or {}
    perm_why = {k: v for k, v in (spec.get("perm_why") or {}).items() if not k.startswith("_")}
    for app in apps:
        if app in exempt:
            continue
        build = load(os.path.join(root, app, "build.json"))
        cp = build.get("configs_pages")
        # G1
        if not isinstance(cp, dict) or not isinstance(cp.get("general"), dict) or not isinstance(cp.get("about"), dict):
            bad.append("G1 %s: build.json has no configs_pages.general / configs_pages.about — every app but Cloud SuperApp has Configs ▸ General and ▸ About" % app)
            continue
        # G2
        ids = [s.get("id") for s in (cp["general"].get("sections") or []) if isinstance(s, dict)]
        for r in spec["general_required"]:
            if ids.count(r) != 1:
                bad.append("G2 %s: configs_pages.general.sections must list %r exactly once (found %d)" % (app, r, ids.count(r)))
        for i in ids:
            if i not in allowed:
                bad.append("G2 %s: configs_pages.general.sections names %r, not one of %s" % (app, i, sorted(allowed)))
        if len(ids) != len(set(ids)):
            bad.append("G2 %s: configs_pages.general.sections lists an id twice" % app)
        # G3
        about = cp["about"]
        for f in spec["about_declared"]:
            if f not in about:
                bad.append("G3 %s: configs_pages.about.%s is missing" % (app, f))
        if "what" in about:
            bad.append("G3 %s: configs_pages.about.what is the v1 field — it is now description (long) plus tagline (short)" % app)
        tl = len(str(about.get("tagline") or "").strip())
        if not mn["tagline_chars"][0] <= tl <= mn["tagline_chars"][1]:
            bad.append("G3 %s: configs_pages.about.tagline must be %d-%d characters (has %d)" % (app, mn["tagline_chars"][0], mn["tagline_chars"][1], tl))
        if len(str(about.get("description") or "").strip()) < mn["description_chars"]:
            bad.append("G3 %s: configs_pages.about.description must say what the app does and why in at least %d characters" % (app, mn["description_chars"]))
        feats = [x for x in (about.get("features") or []) if isinstance(x, str) and x.strip()]
        if len(feats) < mn["features"] or len(feats) != len(about.get("features") or []):
            bad.append("G3 %s: configs_pages.about.features must be at least %d non-empty strings" % (app, mn["features"]))
        scr = about.get("screens") or []
        if len([x for x in scr if isinstance(x, dict) and str(x.get("name") or "").strip() and str(x.get("what") or "").strip()]) < mn["screens"] \
                or not all(isinstance(x, dict) and str(x.get("name") or "").strip() and str(x.get("what") or "").strip() for x in scr):
            bad.append("G3 %s: configs_pages.about.screens must be at least %d {name, what} entries, each with both" % (app, mn["screens"]))
        guide = [x for x in (about.get("guide") or []) if isinstance(x, str) and x.strip()]
        if len(guide) < mn["guide"]:
            bad.append("G3 %s: configs_pages.about.guide must be at least %d steps" % (app, mn["guide"]))
        if len(str(about.get("privacy") or "").strip()) < mn["privacy_chars"]:
            bad.append("G3 %s: configs_pages.about.privacy must be at least %d characters" % (app, mn["privacy_chars"]))
        rel = about.get("related") if isinstance(about.get("related"), list) else []
        if len(rel) < mn["related"]:
            bad.append("G3 %s: configs_pages.about.related must name at least %d fleet app it talks to" % (app, mn["related"]))
        for r in rel:
            if not isinstance(r, dict) or r.get("app") not in apps or not str(r.get("why") or "").strip():
                bad.append("G3 %s: configs_pages.about.related entry %r must be {app: <a fleet app folder>, why}" % (app, r))
        kind = (curated.get(app) or {}).get("kind") or ""
        if kind in spec["fork_kinds"] and len(str(about.get("credits") or "").strip()) < mn["credits_chars"]:
            bad.append("G3 %s: a %s app credits its upstream in configs_pages.about.credits (at least %d characters)" % (app, kind, mn["credits_chars"]))
        perms = build.get("perms") or {}
        own_why = about.get("perms_why") or {}
        for pid in list(perms.get("needs") or []) + [n for h in (perms.get("helpers") or []) for n in (h.get("needs") or [])]:
            if not str(own_why.get(pid) or perm_why.get(pid) or "").strip():
                bad.append("G3 %s: grant %r has no why (configs_pages.about.perms_why or %s::perm_why)" % (app, pid, DATA))
        if not isinstance(about.get("backends"), list):
            bad.append("G3 %s: configs_pages.about.backends must be a list (empty when the app talks to no fleet service)" % app)
        else:
            for b in about["backends"]:
                bid = b.get("id") if isinstance(b, dict) else None
                if not bid or not str(b.get("why") or "").strip():
                    bad.append("G3 %s: every configs_pages.about.backends entry needs an id and a why" % app)
                elif bid not in services:
                    bad.append("G3 %s: backend %r is not a service of %s::fleet_endpoints" % (app, bid, spec["endpoints"]))
                elif not services[bid].get("mesh_dns"):
                    bad.append("G3 %s: backend %r has no mesh_dns, so About cannot show its private mesh URL" % (app, bid))
        for f, v in derived_about(root, spec, app, build).items():
            if f in spec["about_derived"] and not v:
                bad.append("G3 %s: About field %r does not resolve (derived at build time by libs/ui-nav-bars/fleet-configs.gradle)" % (app, f))
        # G4
        reached = reaches(root, app, (spec["scaffold_call"], spec["views_call"]) + tuple(spec.get("menu_calls") or []))
        if not reached and app in shortcut_reach:
            reached = links_ui_nav_bars(root, app, build)
            if not reached:
                bad.append("G4 %s: named in %s::shortcut_reach but does not link libs:ui-nav-bars, so no launcher shortcut is published" % (app, DATA))
        if app in pending:
            if reached:
                bad.append("G4 %s: reaches Configs ▸ General / ▸ About now — delete its entry in %s::pending" % (app, DATA))
        elif not reached:
            bad.append("G4 %s: no source reaches Configs ▸ General / ▸ About — draw the shared %s…) or start %s…), or name the app in %s::pending with a reason"
                       % (app, spec["scaffold_call"], spec["views_call"], DATA))

    # G5
    for kind, ledger in (("exempt", exempt), ("pending", pending), ("shortcut_reach", shortcut_reach)):
        for app, why in ledger.items():
            if app not in apps:
                bad.append("G5 %s::%s names %s, which is not an app (a[acd]_*/build.json)" % (DATA, kind, app))
            if len(str(why).strip()) < spec["min_reason_chars"]:
                bad.append("G5 %s::%s.%s needs a reason of at least %d characters" % (DATA, kind, app, spec["min_reason_chars"]))
    for app in set(exempt) & set(pending):
        bad.append("G5 %s is both exempt and pending" % app)
    for app in set(shortcut_reach) & (set(pending) | set(exempt)):
        bad.append("G5 %s is in shortcut_reach and also pending or exempt" % app)
    return bad, apps, exempt, pending


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    bad, apps, exempt, pending = check(root)
    held = [a for a in apps if a not in exempt]
    if bad:
        print("configs-pages guard: %d violation(s)" % len(bad))
        for b in bad:
            print("  " + b)
        return 1
    print("configs-pages guard: ok — %d apps declare General + About, %d reach them through the shared scaffold, %d pending (Views/WebView/fork), %d exempt"
          % (len(held), len(held) - len(pending), len(pending), len(exempt)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
