#!/bin/sh
# #1547 / #1562 the Explored map's capitals and subregion members are served datasets (data/fleet/geo-capitals.json, geo-region-members.json), not maps assets.
#   G1 both files exist with schemaVersion 1 and the payload key, and the manifest lists them as shared datasets
#   G2 the maps lib bakes neither (assets/geo holds no capitals.json / region-members.json) and reads both through MapsGeoData (FleetData)
#   G3 the generator writes them to data/fleet, not to the assets
#   G4 the checks bite: baking a file back, dropping the FleetData read and pointing the generator back at the assets each fail
set -eu
HERE="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH='' cd -- "$HERE/.." && pwd)/.."
python3 - "$(CDPATH='' cd -- "$ROOT" && pwd)" <<'PY'
import json, os, shutil, sys, tempfile
root = sys.argv[1]
FILES = ["data/fleet/geo-capitals.json", "data/fleet/geo-region-members.json", "data/fleet/manifest.json",
         "ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsGeoData.kt",
         "ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsGeoLayers.kt",
         "ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps/MapsExploredFragment.kt",
         "ac_cloud-nav/tools/geo/gen.mjs"]
ASSETS = "ab_cloud-libs-shared/libs/maps/src/main/assets/geo"
fails = []
def ok(c, good, bad):
    print(("  PASS: " + good) if c else ("  FAIL: " + bad))
    if not c: fails.append(bad)
def rd(top, f): return open(os.path.join(top, f), errors="ignore").read()
def check(top):
    r = {}
    try:
        cap = json.loads(rd(top, FILES[0])); mem = json.loads(rd(top, FILES[1])); man = json.loads(rd(top, FILES[2]))
        r["g1"] = (cap.get("schemaVersion") == 1 and len(cap.get("capitals", {})) > 30 and mem.get("schemaVersion") == 1 and len(mem.get("members", [])) > 40
                   and all(k in man["shared"] and man["shared"][k]["consumers"] == ["*"] for k in ("geo-capitals", "geo-region-members")))
    except Exception:
        r["g1"] = False
    baked = [f for f in ("capitals.json", "region-members.json") if os.path.exists(os.path.join(top, ASSETS, f))]
    data, layers, frag = rd(top, FILES[3]), rd(top, FILES[4]), rd(top, FILES[5])
    r["g2"] = (not baked and "FleetData.get(" in data and "FleetData.observe(" in data and "MapsGeoData.members(ctx)" in layers
               and "observeCapitals" in frag and 'assets.open("geo/capitals.json")' not in frag and "region-members.json" not in layers)
    gen = rd(top, FILES[6])
    r["g3"] = "geo-capitals.json" in gen and "geo-region-members.json" in gen and "join(OUT, 'capitals.json')" not in gen and "join(OUT, 'region-members.json')" not in gen
    return r
r = check(root)
ok(r["g1"], "both datasets exist (schemaVersion 1) and the manifest lists them as shared", "a geo dataset or its manifest entry is missing")
ok(r["g2"], "the maps lib bakes neither file and reads both through FleetData", "a geo file is baked or not read through FleetData")
ok(r["g3"], "the generator writes data/fleet, not the assets", "the generator still writes the assets")
tmp = tempfile.mkdtemp()
try:
    for f in FILES:
        os.makedirs(os.path.dirname(os.path.join(tmp, f)), exist_ok=True); shutil.copy(os.path.join(root, f), os.path.join(tmp, f))
    os.makedirs(os.path.join(tmp, ASSETS), exist_ok=True)
    def edit(f, a, b):
        p = os.path.join(tmp, f); s = open(p).read(); assert a in s, (f, a); open(p, "w").write(s.replace(a, b)); return s, p
    p = os.path.join(tmp, ASSETS, "capitals.json"); open(p, "w").write("{}")
    ok(not check(tmp)["g2"], "mutant: a baked capitals.json is caught", "G4 missed: a baked capitals.json")
    os.remove(p)
    s, p = edit(FILES[3], "FleetData.get(", "FleetGet.get("); ok(not check(tmp)["g2"], "mutant: members read without FleetData is caught", "G4 missed: members not served"); open(p, "w").write(s)
    s, p = edit(FILES[6], "geo-capitals.json", "capitals.json"); ok(not check(tmp)["g3"], "mutant: the generator back on the assets is caught", "G4 missed: generator"); open(p, "w").write(s)
    s, p = edit(FILES[0], '"schemaVersion":1', '"schemaVersion":0'); ok(not check(tmp)["g1"], "mutant: a missing schemaVersion is caught", "G4 missed: schemaVersion"); open(p, "w").write(s)
    ok(all(check(tmp).values()), "the pristine copy passes again", "the pristine copy fails")
finally:
    shutil.rmtree(tmp)
print(f"\n{'FAIL' if fails else 'PASS'}: test-geo-data ({len(fails)} failing)")
sys.exit(1 if fails else 0)
PY
