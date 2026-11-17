#!/usr/bin/env bash
# Guard: every Android app's LAUNCHER LABEL is "Cloud " + Title Case words, with
# spaces, declared once in build.json::display_name and derived from there.
#
# THE FAILURE THIS EXISTS FOR. Launchers showed "cloud-browser" (the slug in
# build.json::name, which resValue app_name used to copy), "Gallery", "WatchTower",
# "Morpheus", "Mattermost Beta", "Phone_debug" and "Cloud Terminal (Nix)" beside
# "Cloud Mail". The slug stays the app's IDENTITY (.name); the label is a second
# declaration, build.json::display_name, and every surface below must take it.
#
#   L1  every app (aa_*/ac_* with a build.json) declares display_name matching
#       ^Cloud( [A-Z][A-Za-z0-9]*)+$ : no hyphen, no "cloud-", no brackets, no
#       lower-case word. (The SuperApp's own brand is "Cloud SuperApp".)
#   L2  display names are unique, and constellation-fleet.json carries them
#   L3  gradle resValue app_name / title is build.json display_name (or the same
#       literal), never .name and never a different literal
#   L4  a launcher string resource (app_name, app_launcher_name) in values/ of
#       main/release equals display_name; release overrides main/beta flavours
#   L5  a literal android:label on <application> or a launcher activity/alias
#       equals display_name; an alias may not use any other string than app_name
#   L6  the shared-uid / forked-launcher declarations (TERMUX_APP_NAME), the
#       Cloud Office configure flag and the Cloud Code build script use it too
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
exec python3 - "$ROOT" <<'PY'
import glob, json, os, re, sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
TITLE = re.compile(r"^Cloud( [A-Z][A-Za-z0-9]*)+$")
# #1335 the launcher label of an app is its FAMILY's pattern (1_cicd/src/data/app-families.json::display_pattern): the Cloud family's
# "Cloud " + Title Case words, the Lnktree family's CG-Omniverse / Capital-Stack-FinTerminal / Hardtech-CAD / Hardtech-BioMed.
FAMILIES = json.load(open(os.path.join(root, "1_cicd/src/data/app-families.json")))["families"]
def label_pattern(d):
    fam = next((f for f in FAMILIES if d.startswith(f["folder_prefix"])), None)
    return re.compile(fam["display_pattern"]) if fam and d.startswith("ad_") else TITLE
A = "{http://schemas.android.com/apk/res/android}"
passed = failed = 0
def check(cond, msg):
    global passed, failed
    if cond: passed += 1
    else:
        failed += 1; print("  FAIL: " + msg)

apps = {}
for path in sorted(glob.glob(os.path.join(root, "aa_*", "build.json")) + glob.glob(os.path.join(root, "ac_*", "build.json")) + glob.glob(os.path.join(root, "ad_*", "build.json"))):
    apps[os.path.basename(os.path.dirname(path))] = json.load(open(path))
check(len(apps) >= 30, "found only %d apps - the detection matched too little" % len(apps))

def skip(path):
    p = path.replace(os.sep, "/")
    # authenticator*/ is the vault tree's separate Authenticator module, not the
    # launcher app (build.json::_doc: module `app` is the Cloud Vault launcher).
    return any(x in p for x in ("/build/", "/libs/", "/test/", "/tests/", "/androidTest/", "/testharness/", "/patches/", "/node_modules/", "/authenticator"))
def files(d, pattern):
    out = []
    for base, dirs, names in os.walk(os.path.join(root, d)):
        dirs[:] = [x for x in dirs if x not in ("build", "node_modules", ".git", "libs", "test", "tests")]
        out += [os.path.join(base, n) for n in names if re.search(pattern, n)]
    return [f for f in out if not skip(f)]

print("== L1: display_name is Cloud + Title Case words ==")
shown = {}
for d, bj in apps.items():
    dn = bj.get("display_name")
    check(isinstance(dn, str) and label_pattern(d).match(dn) is not None and ("-" not in dn or d.startswith("ad_")) and "cloud-" not in dn.lower(),
          "%s/build.json::display_name = %r is not 'Cloud ' + Title Case words with spaces" % (d, dn))
    if isinstance(dn, str): shown[d] = dn

print("== L2: unique, and the fleet file carries it ==")
vals = list(shown.values())
check(len(vals) == len(set(vals)), "two apps share a display name: %s" % sorted({v for v in vals if vals.count(v) > 1}))
fleet = json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))["apps"]
by_slug = {b.get("name"): shown.get(d) for d, b in apps.items()}
for entry in fleet:
    if entry.get("kind") == "app":
        check(entry.get("display_name") == by_slug.get(entry["label"]) and entry.get("display_name") is not None,
              "constellation-fleet.json %s display_name = %r, build.json says %r - run data/regen.sh"
              % (entry["label"], entry.get("display_name"), by_slug.get(entry["label"])))

print("== L3: gradle app_name / title resValue ==")
res_re = re.compile(r"resValue\s*\(?\s*[\"']string[\"']\s*,\s*[\"'](app_name|title_activity_main|main_activity_title)[\"']\s*,\s*([^\n]+)")
derived = 0
for d, dn in shown.items():
    for g in files(d, r"^build\.gradle(\.kts)?$"):
        for name, expr in res_re.findall(open(g, errors="ignore").read()):
            expr = expr.strip().rstrip(")").strip()
            where = "%s %s" % (os.path.relpath(g, root), name)
            if re.search(r"(buildJson|appMetadataJson)(\.display_name\b|\[\s*\"display_name\"\s*\])", expr):
                derived += 1; check(True, "")
            elif re.fullmatch(r'"[^"$]*"', expr):
                check(expr.strip('"') == dn, "%s = %s, display_name is %r" % (where, expr, dn))
            elif "baseAppName" in expr and d == "ac_cloud-matrix":
                src = open(os.path.join(root, "ac_cloud-matrix/plugins/src/main/kotlin/config/BuildTimeConfig.kt")).read()
                m = re.search(r'APPLICATION_NAME\s*=\s*"([^"]*)"', src)
                check(m is not None and m.group(1) == dn, "%s derives from BuildTimeConfig.APPLICATION_NAME = %r, display_name is %r"
                      % (where, m and m.group(1), dn))
            else:
                check(False, "%s = %s is not build.json::display_name" % (where, expr))
check(derived >= 20, "only %d gradle files derive app_name from display_name" % derived)

print("== L4: launcher string resources ==")
LAUNCH_STR = ("app_name", "app_launcher_name")
for d, dn in shown.items():
    found = {}
    for x in files(d, r"^strings(_non_localized)?\.xml$"):
        p = x.replace(os.sep, "/")
        m = re.search(r"/src/([^/]+)/res/values/", p)
        if not m or m.group(1) not in ("main", "release", "beta", "debug"):
            continue
        for name, text in re.findall(r'<string\s+name="([^"]+)"[^>]*>([^<]*)</string>', open(x, errors="ignore").read()):
            if name in LAUNCH_STR:
                found.setdefault(name, {})[m.group(1)] = text.strip()
    for name, flavours in found.items():
        # release is what ships; main is the base other flavours override
        target = flavours.get("release", flavours.get("main"))
        if target is not None:
            check(target == dn, "%s string %s = %r, display_name is %r" % (d, name, target, dn))
        if "release" not in flavours:
            for fl in ("beta", "debug"):
                if fl in flavours and fl != "debug":
                    check(flavours[fl] == dn or flavours[fl].startswith(dn + " "),
                          "%s %s/%s = %r does not start with display_name" % (d, fl, name, flavours[fl]))
        if "debug" in flavours:
            v = flavours["debug"]
            check(TITLE.match(v) is not None, "%s debug %s = %r is not Cloud + Title Case words" % (d, name, v))

print("== L5: manifest labels ==")
for d, dn in shown.items():
    for mf in files(d, r"^AndroidManifest\.xml$"):
        if "/src/main/" not in mf.replace(os.sep, "/") or "rootfs-lib/" in mf:
            continue
        try:
            tree = ET.parse(mf)
        except ET.ParseError:
            continue
        application = tree.getroot().find("application")
        if application is None: continue
        rel = os.path.relpath(mf, root)
        alabel = application.get(A + "label")
        if alabel and not alabel.startswith(("@", "$")):
            check(alabel == dn, "%s <application> android:label = %r, display_name is %r" % (rel, alabel, dn))
        for el in application:
            if el.tag not in ("activity", "activity-alias"): continue
            launcher = any(c.get("{0}name".format(A)) == "android.intent.category.LAUNCHER" for c in el.iter("category"))
            lab = el.get(A + "label")
            if launcher and lab and not lab.startswith(("@", "$")):
                check(lab == dn, "%s launcher %s label = %r, display_name is %r" % (rel, el.get(A + "name"), lab, dn))
            if el.tag == "activity-alias" and launcher and lab:
                check(lab in ("@string/app_name", "@string/app_launcher_name", "@string/application_name", "${appLabel}")
                      or not lab.startswith("@"), "%s alias %s label %s is not the app's one name string" % (rel, el.get(A + "name"), lab))

print("== L6: placeholders, configure flag, build script ==")
for d, dn in shown.items():
    for g in files(d, r"^build\.gradle(\.kts)?$"):
        for val in re.findall(r'TERMUX_APP_NAME\s*=\s*"([^"]*)"', open(g, errors="ignore").read()):
            check(val == dn, "%s TERMUX_APP_NAME = %r, display_name is %r" % (os.path.relpath(g, root), val, dn))
office = apps.get("ac_cloud-office")
if office:
    check(office["upstream"]["online"]["configure"].get("--with-app-name") == shown.get("ac_cloud-office"),
          "ac_cloud-office --with-app-name is not its display_name")
code = open(os.path.join(root, "ac_cloud-code/build.sh")).read()
check("launcher name ← build.json::display_name" in code and 'python3 - "$DISPLAY_NAME"' in code,
      "ac_cloud-code/build.sh does not set the Cordova <name> from display_name")
gal = open(os.path.join(root, "ac_cloud-gallery/app/src/main/AndroidManifest.xml")).read()
check("app_name_gallery" not in gal, "ac_cloud-gallery has a second launcher-name string (app_name_gallery)")

print()
print("-- test-launcher-labels: %d passed, %d failed --" % (passed, failed))
sys.exit(1 if failed else 0)
PY
