#!/bin/sh
# #1690 ONE location tracker owner in the fleet: Cloud Nav.
#   O1 LocationTrackerService is declared (<service>) in exactly one manifest: ac_cloud-nav/app's. No lib manifest
#      (libs:maps included) declares it, so no other app merges the foreground service in.
#   O2 only Cloud Nav compiles the tracker: no other app's Gradle file links project(':libs:maps') (comments ignored).
#   O3 the owner serves the location: TrackerDebugApiProvider routes tracker/location to NavLocationWire (contract v1).
#   O4 the checks bite: a planted second declaration and a planted link fail them.
set -eu
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH='' cd -- "$HERE/../.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys, tempfile, shutil
root = sys.argv[1]
fails = []
def ok(c, good, bad):
    print(("  PASS: " + good) if c else ("  FAIL: " + bad))
    if not c: fails.append(bad)
SKIP = {"z_archive", ".git", "build", "node_modules", ".gradle"}
def walk(top):
    for d, ds, fs in os.walk(top):
        ds[:] = [x for x in ds if x not in SKIP]
        for f in fs: yield os.path.join(d, f)
def strip_xml(s): return re.sub(r"<!--.*?-->", "", s, flags=re.S)
def strip_gradle(s): return "\n".join(l.split("//")[0] for l in re.sub(r"/\*.*?\*/", "", s, flags=re.S).splitlines())
def declarers(top):
    return sorted(os.path.relpath(p, top) for p in walk(top) if p.endswith("AndroidManifest.xml")
                  and re.search(r'<service[^>]*LocationTrackerService', strip_xml(open(p, errors="ignore").read()), re.S))
def linkers(top):
    out = []
    for p in walk(top):
        if not p.endswith(".gradle"): continue
        rel = os.path.relpath(p, top)
        if rel.startswith("ab_cloud-libs-shared/libs/maps/"): continue
        if re.search(r"project\(\s*['\"]:libs:maps['\"]\s*\)", strip_gradle(open(p, errors="ignore").read())): out.append(rel)
    return sorted(out)
OWNER = "ac_cloud-nav/app/src/main/AndroidManifest.xml"
def check(top, quiet=False):
    d, l = declarers(top), linkers(top)
    return d == [OWNER], all(x.startswith("ac_cloud-nav/") for x in l) and any(x.startswith("ac_cloud-nav/") for x in l), d, l

print("== O1/O2: one declaration, one compiler ==")
o1, o2, d, l = check(root)
ok(o1, "LocationTrackerService declared only in " + OWNER, f"tracker declared in {d} (must be exactly [{OWNER}])")
ok(o2, f"only Cloud Nav links libs:maps ({', '.join(l)})", f"libs:maps linked by {l}: only ac_cloud-nav may compile the tracker")

print("== O3: the owner answers tracker/location ==")
prov = open(os.path.join(root, "ac_cloud-nav/app/src/main/java/com/diegonmarcos/cloudnav/tracker/TrackerDebugApiProvider.kt")).read()
wire = os.path.join(root, "ac_cloud-nav/app/src/main/java/com/diegonmarcos/cloudnav/tracker/NavLocationWire.kt")
ok('"location" -> location(app)' in prov and "NavLocationWire.toJson" in prov and os.path.exists(wire),
   "tracker/location routed to NavLocationWire", "tracker/location is not served by Cloud Nav")

print("== O4: the checks bite ==")
tmp = tempfile.mkdtemp()
try:
    for rel in [OWNER, "ab_cloud-libs-shared/libs/maps/src/main/AndroidManifest.xml", "ac_cloud-nav/app/build.gradle"]:
        os.makedirs(os.path.join(tmp, os.path.dirname(rel)), exist_ok=True); shutil.copy(os.path.join(root, rel), os.path.join(tmp, rel))
    good = check(tmp)
    m = os.path.join(tmp, "ab_cloud-libs-shared/libs/maps/src/main/AndroidManifest.xml")
    s = open(m).read(); open(m, "w").write(s.replace("</manifest>", '<application><service android:name="com.diegonmarcos.cloudnav.maps.LocationTrackerService"/></application></manifest>'))
    bad1 = check(tmp); open(m, "w").write(s)
    g = os.path.join(tmp, "aa_cloud-superapp/app/build.gradle"); os.makedirs(os.path.dirname(g), exist_ok=True)
    open(g, "w").write("dependencies {\n    implementation project(':libs:maps')\n}\n"); bad2 = check(tmp)
    open(g, "w").write("dependencies {\n    // implementation project(':libs:maps')\n}\n"); good2 = check(tmp)
    ok(good[0] and good[1], "the pristine copy passes", "the pristine copy fails")
    ok(not bad1[0], "a second <service> in libs:maps fails O1", "O1 missed a second declaration")
    ok(not bad2[1], "a SuperApp link to libs:maps fails O2", "O2 missed a SuperApp link")
    ok(good2[1], "a commented-out link is ignored", "O2 counts comments")
finally:
    shutil.rmtree(tmp)
print(f"\n{'FAIL' if fails else 'PASS'}: test-tracker-owner ({len(fails)} failing)")
sys.exit(1 if fails else 0)
PY
