# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-perms-guard.py ───
#!/usr/bin/env python3
"""#1360 fleet permissions guard: every permission an app's code needs is declared, granted from one page, asked at
first run, and checked on the helper app that does the work.

Usage: cloud-android-perms-guard.py [ROOT] [--matrix] [--write]
  ROOT      repo root (default .)
  --matrix  print the audit matrix (app x permission) and exit 0
  --write   (re)generate the app-perms dataset data/fleet/app-perms.json (one entry per package) from every app's build.json::perms
Policy: 1_cicd/src/data/perms-guard.json (code signatures, cross-app engines, waivers).
Exit 1 on any finding. Rules:
  P1 code -> build.json   a permission-gated API in the app's own code needs its catalogue id in perms.needs
  P2 build.json -> manifest  a declared id that has a manifest permission (or service) is in the merged manifest
  P3 manifest -> build.json  every catalogue-known permission the merged manifest asks is in perms.needs
  P4 engines              code that runs work in another app (cld.termux, its shared-store provider) declares that
                          helper in perms.helpers with what it needs, the helper's own perms.needs holds it, and the
                          manifest can see the helper package (<queries>)
  P5 the page             a Gradle app links libs:perms and the one dataset data/fleet/app-perms.json (keyed by package,
                          served at runtime by Cloud Store, read through libs:fleet-data-client) holds its build.json::perms
                          (what the Perms page, first run and the failure prompt read); a baked assets/app-perms.json copy is red (#1547); an app with its own
                          declared Perms section (ui.general.perms) lists every manifest permission there too
  P6 first run            perms.first_run is "libs:perms" (PermsFirstRun) or "own:<File.kt>" naming a file that exists
  P7 one place (#1515)    no app code outside libs:perms and the app's declared Perms page (policy one_place.allow) calls
                          requestPermissions / RequestPermission(s), opens a special-access or App-info settings intent, or runs
                          `pm grant` / `appops set`. one_place.exceptions are declared in data (bgpolicy acts on OTHER apps);
                          one_place.baseline lists the sites not moved yet (path or dir/ prefix + reason), may only shrink
                          (baseline_max), and an entry that no longer violates must be dropped
"""
import glob
import json
import os
import re
import sys

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = ARGS[0] if ARGS else "."
MATRIX = "--matrix" in sys.argv
WRITE = "--write" in sys.argv
POLICY_REL = "1_cicd/src/data/perms-guard.json"
CAT_REL = "ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json"
ASSET = "app-perms.json"   # the OLD per-app baked copy: now forbidden
DATASET_REL = "data/fleet/app-perms.json"
# #1546 the fleet index the Perms page reads for "used by fleet apps" and the per-app filter (libs:perms FleetPerms)
FLEET_REL = "ab_cloud-libs-shared/libs/perms/src/main/assets/fleet-perms.json"
SKIP_DIRS = {"build", "test", "androidTest", "testDebug", "node_modules", ".gradle", "debug", "z_archive", ".git", "intermediates"}


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def strip(text):
    """Comments out, newlines kept (so a finding's line number is the file's)."""
    return re.sub(r"(?<![:\\\"])//[^\n]*|/\*.*?\*/|<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def manifest_of(app_dir, bj):
    rel = (bj.get("perms") or {}).get("manifest")
    if rel:
        return os.path.join(app_dir, rel)
    best = None
    # sorted: glob order is filesystem-dependent, and a tie (or a library that also has an <application>) must not decide
    # which module is the app. A module dir named "app" wins, then the shallowest, then the path.
    def rank(m):
        mod = os.path.relpath(m, app_dir).split(os.sep)[:-3]
        return (0 if mod and mod[-1] == "app" else 1, m.count(os.sep), m)
    for m in sorted(glob.glob(os.path.join(app_dir, "**/src/main/AndroidManifest.xml"), recursive=True)):
        parts = set(os.path.relpath(m, app_dir).split(os.sep))
        if parts & SKIP_DIRS:
            continue
        if "<application" not in (read(m) or ""):
            continue
        if best is None or rank(m) < rank(best):
            best = m
    return best


def module_dirs(app_dir, bj):
    out = []
    mods = dict(bj.get("modules") or {})
    mods.update((bj.get("build") or {}).get("modules") or {})
    for name, v in mods.items():
        if name.startswith("_"):
            continue
        d = v.get("dir") if isinstance(v, dict) else None
        if d:
            out.append(os.path.normpath(os.path.join(app_dir, d)))
        elif name.startswith("libs:"):
            out.append(os.path.join(ROOT, "ab_cloud-libs-shared/libs", name[5:]))
    return out


def settings_files(app_dir, man):
    """The Gradle settings of the root that builds [man] (the nearest ancestor holding one)."""
    d = os.path.dirname(man)
    while d.startswith(app_dir):
        found = [p for p in (os.path.join(d, "settings.gradle"), os.path.join(d, "settings.gradle.kts")) if os.path.exists(p)]
        if found:
            return found
        if d == app_dir:
            break
        d = os.path.dirname(d)
    return []


def package_of(bj, man):
    """build.json::android.application_id, else the applicationId of the Gradle module that owns [man]."""
    pkg = (bj.get("android") or {}).get("application_id")
    if pkg:
        return pkg
    module = os.path.dirname(os.path.dirname(os.path.dirname(man)))
    for f in ("build.gradle", "build.gradle.kts"):
        m = re.search(r"""applicationId\s*=?\s*['"]([\w.]+)['"]""", strip(read(os.path.join(module, f)) or ""))
        if m:
            return m.group(1)
    return None


def mods_of(bj):
    return set(bj.get("modules") or {}) | set((bj.get("build") or {}).get("modules") or {})


def includes(app_dir, man, bj, lib):
    """True when the app's Gradle root builds libs:<lib>: build.json modules (the loop settings read them) or an
    explicit include / a listOf(...) loop in settings."""
    if "libs:" + lib in mods_of(bj):
        return True
    for s in settings_files(app_dir, man):
        t = strip(read(s) or "")
        if re.search(r"""include\s*\(?\s*['"]:libs:%s['"]""" % re.escape(lib), t):
            return True
        for m in re.finditer(r"(?:listOf\(|\[)([^\])]*)[\])]\s*\.?\s*(?:forEach|each)?", t):
            if re.search(r"""['"]%s['"]""" % re.escape(lib), m.group(1)) and "libs" in t[m.end():m.end() + 400]:
                return True
    return False


def links_perms(app_dir, bj, man=None):
    return includes(app_dir, man or os.path.join(app_dir, "x"), bj, "perms")


def manifest_perms(text):
    t = strip(text or "")
    perms = set(re.findall(r'<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"', t))
    services = set(re.findall(r'android:permission="(android\.permission\.BIND_[A-Z_]+)"', t))
    queries = set(re.findall(r'<package\s+android:name="([^"]+)"', t))
    return perms, services, queries


SERVICE_IDS = {
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE": "notification_listener",
    "android.permission.BIND_ACCESSIBILITY_SERVICE": "accessibility",
    "android.permission.BIND_DEVICE_ADMIN": "device_admin",
    "android.permission.BIND_VPN_SERVICE": "vpn",
}


def ids_of_manifest(cat, perms, services):
    """The catalogue ids a manifest asks for (normal-install permissions with no grant step are not catalogue ids)."""
    by_perm = {}
    for e in cat["entries"]:
        p = e.get("permission")
        if p and e["kind"] != "adb_only":
            by_perm.setdefault(p, e["id"])
    ids = {by_perm[p] for p in perms if p in by_perm}
    ids |= {SERVICE_IDS[s] for s in services if s in SERVICE_IDS}
    if "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in perms:
        ids.add("battery_opt")
    if "android.permission.USE_EXACT_ALARM" in perms:
        ids.add("exact_alarm")
    if any(p.startswith("android.permission.health.") for p in perms):
        ids.add("health_connect")
    return ids


def satisfied_by_manifest(cat, need, perms, services):
    e = next((x for x in cat["entries"] if x["id"] == need), None)
    if e is None:
        return False
    if need == "health_connect":
        return any(p.startswith("android.permission.health.") for p in perms)
    if need == "battery_opt":
        return "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in perms
    if need == "exact_alarm":
        return bool({"android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM"} & perms)
    svc = [s for s, i in SERVICE_IDS.items() if i == need]
    if svc:
        return bool(set(svc) & services)
    p = e.get("permission")
    if not p or e["kind"] == "adb_only" or e.get("check") in ("manual", "standby_bucket"):
        return True
    return p in perms


def code_files(app_dir, extra_skip):
    for dirpath, dirnames, filenames in os.walk(app_dir):
        rel = os.path.relpath(dirpath, app_dir).replace(os.sep, "/")
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")
                       and not any((rel + "/" + d).lstrip("./").startswith(x) for x in extra_skip)]
        for f in filenames:
            if f.endswith((".kt", ".java")):
                yield os.path.join(dirpath, f)


def scan(app_dir, policy, extra_skip):
    """{catalogue id or engine name: first file:line that needs it}"""
    sigs = [(i, re.compile(rx)) for i, rxs in policy["signatures"].items() for rx in rxs]
    engs = [(n, re.compile(rx)) for n, e in policy["engines"].items() for rx in e["code"]]
    found = {}
    for f in code_files(app_dir, extra_skip):
        if "/src/main/" not in f.replace(os.sep, "/"):
            continue
        text = strip(read(f) or "")
        for i, rx in sigs + engs:
            if i in found:
                continue
            m = rx.search(text)
            if m:
                found[i] = "%s:%d" % (os.path.relpath(f, ROOT), text[:m.start()].count("\n") + 1)
    return found


def one_place(policy, apps):
    """P7: {file: [tokens]} of asker code outside the allowed Perms places, as findings."""
    op = policy.get("one_place")
    if not op:
        return []
    bad = []
    tokens, allow = op["tokens"], op["allow"]
    exc = [(e["path"], set(e["tokens"]), e.get("why", "")) for e in op.get("exceptions", [])]
    base = {k: v for k, v in op.get("baseline", {}).items() if not k.startswith("_")}
    hit_base = set()
    for e in op.get("exceptions", []):
        if len(e.get("why", "")) < 20:
            bad.append("policy: one_place exception %s needs a why" % e["path"])
    seen = 0
    for name, (app_dir, bj, man) in sorted(apps.items()):
        for f in code_files(app_dir, []):
            rel = os.path.relpath(f, ROOT).replace(os.sep, "/")
            if "/src/" not in rel or any(a in rel for a in allow):
                continue
            seen += 1
            text = strip(read(f) or "")
            toks = {t for t in tokens if t in text}
            for path, etoks, _ in exc:
                if rel.startswith(path):
                    toks -= etoks
            if not toks:
                continue
            key = next((k for k in base if rel == k or (k.endswith("/") and rel.startswith(k))), None)
            if key:
                hit_base.add(key)
                continue
            bad.append("P7 %s asks or grants a permission outside the Perms page (%s): move it into a Perms row, or the app's Perms page is not in one_place.allow" % (rel, ", ".join(sorted(toks))))
    if seen < op.get("min_files", 200):
        bad.append("P7 scanned only %d files: the guard lost the fleet" % seen)
    for k, v in base.items():
        if not isinstance(v, str) or len(v) < 12:
            bad.append("policy: one_place.baseline[%s] needs a reason" % k)
        if k not in hit_base:
            bad.append("policy: one_place.baseline[%s] no longer violates: drop it (the list only shrinks)" % k)
    cap = op.get("baseline_max")
    if cap is not None and len(base) > cap:
        bad.append("policy: one_place.baseline has %d entries, more than baseline_max %d (the list may only shrink)" % (len(base), cap))
    return bad


def entry_of(perms):
    return {
        "needs": perms.get("needs", []),
        "first_run": perms.get("first_run", "libs:perms"),
        "helpers": [{k: h[k] for k in ("package", "label", "needs", "why") if k in h} for h in perms.get("helpers", [])],
    }


def dataset_body(apps_by_pkg):
    out = {
        "schemaVersion": 1,
        "_doc": "GENERATED from every app's build.json::perms by 1_cicd/src/scripts/cloud-android-perms-guard.py --write. DO NOT EDIT. ONE runtime dataset keyed by package (it replaces the 40 baked assets/app-perms.json copies, #1547): Cloud Store serves it as shared/app-perms, libs:perms (AppPerms) reads it through libs:fleet-data-client: the Perms page rows, Grant all, first run and PermsGate.",
        "apps": {k: apps_by_pkg[k] for k in sorted(apps_by_pkg)},
    }
    return json.dumps(out, indent=1, sort_keys=False) + "\n"


def fleet_body(rows):
    out = {
        "_doc": "GENERATED from every fleet app's build.json::perms by 1_cicd/src/scripts/cloud-android-perms-guard.py --write. DO NOT EDIT. Read by libs:perms (FleetPerms): which fleet app needs which catalogue id (#1546).",
        "apps": rows,
    }
    return json.dumps(out, indent=1, sort_keys=False) + "\n"


def main():
    bad = []
    policy = json.loads(read(os.environ.get("PERMS_GUARD_POLICY") or os.path.join(ROOT, POLICY_REL)) or "{}")
    cat = json.loads(read(os.environ.get("PERMS_GUARD_CATALOGUE") or os.path.join(ROOT, CAT_REL)) or '{"entries": []}')
    cat_ids = {e["id"] for e in cat["entries"]}
    exempt = policy.get("not_android", {})
    apps = {}
    for bjp in sorted(glob.glob(os.path.join(ROOT, "a[acd]_*/build.json"))):
        app_dir = os.path.dirname(bjp)
        name = os.path.basename(app_dir)
        try:
            bj = json.loads(read(bjp))
        except Exception:
            continue
        man = manifest_of(app_dir, bj)
        if not man:
            if name not in exempt and (bj.get("android") or {}).get("application_id"):
                bad.append("%s: has an application_id but no AndroidManifest.xml with <application> (set perms.manifest or list it in not_android)" % name)
            continue
        apps[name] = (app_dir, bj, man)
    packages = {}
    for name, (d, bj, man) in apps.items():
        pkg = package_of(bj, man)
        if pkg:
            packages[pkg] = name
    matrix = {}
    fleet = []
    dataset = {}
    for name, (app_dir, bj, man) in sorted(apps.items()):
        perms = bj.get("perms")
        perms_ok = isinstance(perms, dict)
        perms = perms if perms_ok else {}
        needs = list(perms.get("needs", []))
        waived = perms.get("waived", {})
        mperms, mservices, mqueries = manifest_perms(read(man))
        merged_p, merged_s = set(mperms), set(mservices)
        others = [os.path.join(md, "src/main/AndroidManifest.xml") for md in module_dirs(app_dir, bj)]
        others += [m for m in glob.glob(os.path.join(app_dir, "**/src/main/AndroidManifest.xml"), recursive=True)
                   if not set(os.path.relpath(m, app_dir).split(os.sep)) & SKIP_DIRS]
        for om in others:
            p2, s2, q2 = manifest_perms(read(om))
            merged_p |= p2
            merged_s |= s2
            mqueries |= q2
        found = scan(app_dir, policy, policy.get("scan_skip", {}).get(name, []))
        code_ids = {k for k in found if k in cat_ids}
        for k, by in policy.get("implied_by", {}).items():
            if k in code_ids and (set(by) & (set(needs) | ids_of_manifest(cat, merged_p, merged_s))):
                code_ids.discard(k)
        man_ids = ids_of_manifest(cat, merged_p, merged_s)
        gradle = bool(settings_files(app_dir, man))
        linked = includes(app_dir, man, bj, "perms")
        row = {"code": found, "manifest": sorted(man_ids), "declared": needs, "helpers": perms.get("helpers", []),
               "links_perms": linked, "first_run": perms.get("first_run"), "gaps": []}
        matrix[name] = row
        if needs or perms.get("helpers"):
            fleet.append({"app": name, "package": package_of(bj, man) or "", "label": bj.get("name") or name,
                          "needs": needs, "helpers": [h.get("package", "") for h in perms.get("helpers", [])]})

        def gap(msg):
            row["gaps"].append(msg)
            bad.append("%s: %s" % (name, msg))

        if not perms_ok:
            gap("build.json has no `perms` block (needs / helpers / first_run)")
        for i in needs:
            if i not in cat_ids:
                gap("perms.needs names %r, which is not a libs:perms catalogue id" % i)
        for i in sorted(code_ids - set(needs) - set(waived)):
            gap("P1 code needs %s (%s) but build.json::perms.needs does not declare it" % (i, found[i]))
        for i in needs:
            if i in cat_ids and not satisfied_by_manifest(cat, i, merged_p, merged_s):
                gap("P2 perms.needs declares %s but the manifest (with its libs) does not ask for it" % i)
        for i in sorted(man_ids - set(needs) - set(waived)):
            gap("P3 the manifest asks for %s but build.json::perms.needs does not declare it (the Perms page would not list it)" % i)
        for w, why in waived.items():
            if not isinstance(why, str) or len(why) < 12:
                gap("perms.waived[%s] needs a reason" % w)
        helpers = {h.get("package"): h for h in perms.get("helpers", [])}
        for eng_name, eng in policy["engines"].items():
            if eng_name not in found or name == packages.get(eng["package"]):
                continue
            h = helpers.get(eng["package"])
            if not h:
                gap("P4 code runs work in %s (%s, %s) but perms.helpers does not declare it" % (eng["package"], eng_name, found[eng_name]))
                continue
            for n in eng["needs"]:
                if n not in h.get("needs", []):
                    gap("P4 helper %s must declare %s (the %s engine needs it there)" % (eng["package"], n, eng_name))
        for pkg, h in helpers.items():
            if not h.get("why") or not h.get("label"):
                gap("P4 helper %s needs a label and a why" % pkg)
            owner = packages.get(pkg)
            if owner is None:
                gap("P4 helper %s is not a fleet app's application_id" % pkg)
            else:
                oneeds = (apps[owner][1].get("perms") or {}).get("needs", [])
                for n in h.get("needs", []):
                    if n not in oneeds:
                        gap("P4 helper %s (%s) does not declare %s in its own perms.needs" % (pkg, owner, n))
            if pkg not in mqueries and gradle:
                gap("P4 the manifest has no <queries><package android:name=\"%s\"/> so the helper's state cannot be read" % pkg)
        pending = policy.get("pending_page", {})
        wants_page = bool(needs or perms.get("helpers"))
        if includes(app_dir, man, bj, "ui-nav-bars") and not linked:
            gap("P5 links libs:ui-nav-bars (Configs > General > Perms is libs:perms' AppPermsSection) but not libs:perms")
        if name in pending:
            if linked or not wants_page:
                gap("P5 pending_page lists %s but it links libs:perms now (or needs nothing): drop it from the policy" % name)
        elif wants_page and not linked:
            if gradle:
                gap("P5 needs %s but does not link libs:perms, so nothing shows, grants or first-run asks them" % ",".join(needs))
            elif not perms.get("page"):
                gap("P5 a non-Gradle app must name the page that grants its permissions (perms.page)")
        if linked:
            baked = os.path.join(os.path.dirname(man), "assets", ASSET)
            if os.path.exists(baked):
                gap("P5 %s is a baked copy: app-perms is the runtime dataset %s (#1547), delete the file" % (os.path.relpath(baked, ROOT), DATASET_REL))
            pkg = policy.get("package_override", {}).get(name) or package_of(bj, man)
            if not pkg:
                gap("P5 no applicationId, so the app has no key in %s" % DATASET_REL)
            elif perms_ok:
                dataset[pkg] = entry_of(perms)
        gen = ((bj.get("ui") or {}).get("general") or {}).get("perms")
        if isinstance(gen, list):
            listed = {p for e in gen for p in e.get("permissions", [])}
            for p in sorted(mperms):
                if p in {e.get("permission") for e in cat["entries"]} and p not in listed:
                    gap("P5 Configs > General > Perms (ui.general.perms) does not list %s from the manifest" % p)
        fr = perms.get("first_run", "libs:perms")
        if fr != "libs:perms":
            m = re.match(r"own:(.+\.kt)$", str(fr))
            if not m or not glob.glob(os.path.join(app_dir, "**", m.group(1)), recursive=True):
                gap("P6 perms.first_run %r is neither libs:perms nor own:<File.kt> naming an existing file" % fr)

    fleet_path = os.path.join(ROOT, FLEET_REL)
    fleet_want = fleet_body(fleet)
    if WRITE:
        with open(fleet_path, "w", encoding="utf-8") as f:
            f.write(fleet_want)
    elif os.path.isdir(os.path.dirname(fleet_path)) and read(fleet_path) != fleet_want:
        bad.append("P8 %s is missing or stale: run cloud-android-perms-guard.py --write" % FLEET_REL)
    want = dataset_body(dataset)
    if WRITE:
        os.makedirs(os.path.dirname(os.path.join(ROOT, DATASET_REL)), exist_ok=True)
        with open(os.path.join(ROOT, DATASET_REL), "w", encoding="utf-8") as f:
            f.write(want)
    elif read(os.path.join(ROOT, DATASET_REL)) != want:
        bad.append("P5 %s is missing or stale: run cloud-android-perms-guard.py --write" % DATASET_REL)
    bad += one_place(policy, apps)
    pend = {k: v for k, v in policy.get("pending_page", {}).items() if not k.startswith("_")}
    for k, v in pend.items():
        if k not in apps:
            bad.append("policy: pending_page names %s, which is not a fleet app" % k)
        if not isinstance(v, str) or len(v) < 20:
            bad.append("policy: pending_page[%s] needs a reason" % k)
    cap = policy.get("pending_page_max")
    if cap is not None and len(pend) > cap:
        bad.append("policy: pending_page has %d apps, more than pending_page_max %d (the list may only shrink)" % (len(pend), cap))
    if MATRIX:
        for name, r in sorted(matrix.items()):
            print("%-34s code=%s manifest=%s declared=%s helpers=%s libs:perms=%s gaps=%d" % (
                name, ",".join(sorted(r["code"])) or "-", ",".join(r["manifest"]) or "-", ",".join(r["declared"]) or "-",
                ",".join(h.get("package", "?") for h in r["helpers"]) or "-", r["links_perms"], len(r["gaps"])))
        print(json.dumps({k: {"code": v["code"], "gaps": v["gaps"]} for k, v in matrix.items()}, indent=1), file=sys.stderr)
        return 0
    if WRITE:
        return 0
    for b in bad:
        print("FAIL " + b)
    print("perms guard: %d apps, %d findings" % (len(matrix), len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
