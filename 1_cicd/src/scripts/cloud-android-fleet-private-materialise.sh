#!/bin/sh
# cloud-android-fleet-private-materialise (#1566): copy the fleet-private
# datasets checked out at .fleet-private/ into the (gitignored) paths that the
# Gradle build and the testers read. Fails loud when a declared dataset is
# missing, so a red build names the dataset instead of a tester diffing air.
set -eu
ROOT="${1:-$(pwd)}"
MAP="$ROOT/1_cicd/src/data/fleet-private-paths.json"
SRC="$ROOT/.fleet-private/fleet-data/2/fleet"
[ -f "$MAP" ] || { echo "fleet-private-materialise: no $MAP"; exit 1; }
[ -d "$SRC" ] || { echo "fleet-private-materialise: private checkout missing at $SRC (token cannot read cloud-me-pvt-configs?)"; exit 1; }
python3 - "$ROOT" "$SRC" "$MAP" <<'PY'
import json, os, shutil, sys
root, src, mp = sys.argv[1:4]
n = 0
for ds, rel in json.load(open(mp))["datasets"].items():
    s = os.path.join(src, ds + ".json")
    if not os.path.isfile(s):
        sys.exit("fleet-private-materialise: dataset %s not in cloud-me-pvt-configs" % ds)
    d = os.path.join(root, rel)
    os.makedirs(os.path.dirname(d), exist_ok=True)
    shutil.copyfile(s, d)
    n += 1
print("fleet-private-materialise: %d dataset(s) materialised" % n)
PY
