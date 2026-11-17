#!/usr/bin/env bash
# #1777 every asset Hardtech-BioMed serves or ships is licensed. The 3D models and the texts live in data/fleet/biomed (served at run time, never baked
# into the APK); data/fleet/biomed/assets.json is their licence register. This holds:
#   L1 every file of data/fleet/biomed is in the register (and every register entry is a file), except the register itself and LICENSE.md
#   L2 each entry names an open licence from the allowed set (CC-BY-SA-2.1-JP, CC0-1.0, CC-BY-4.0, CC-BY-SA-4.0), its source, its author and the sha256 of the
#      file, and the sha256 is the file's real one
#   L3 a ShareAlike model records how it was derived (the tool) and its credit line is in the served content.json (the in-app attribution), so the licence
#      travels with the model
#   L4 LICENSE.md names every licence used and each model's attribution; the directory is a vendored directory of licenses/curated.json (so the repository's
#      licence inventory knows it) and the inventory lists it
#   L5 no model or content file is anywhere else in the app: a .glb/.gltf/.obj/.fbx under ad_Hardtech-BioMed (outside tools/) must be in the register too
# then plants a mutation per rule in a scratch copy and requires RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
DATA="$ROOT/data/fleet/biomed"

check() {   # check <data dir> <app dir> <curated.json> <inventory.json> -> FAIL lines
python3 - "$1" "$2" "$3" "$4" <<'PY'
import hashlib, json, os, sys
data, app, cur_p, inv_p = sys.argv[1:5]
bad = []
ALLOWED = {"CC-BY-SA-2.1-JP", "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0"}
SHAREALIKE = {"CC-BY-SA-2.1-JP", "CC-BY-SA-4.0"}
try: reg = json.load(open(os.path.join(data, "assets.json")))["files"]
except Exception as e: print("FAIL L1 assets.json is missing or not JSON: %s" % e); sys.exit(1)
on_disk = {f for f in os.listdir(data) if os.path.isfile(os.path.join(data, f))} - {"assets.json", "LICENSE.md"}
for f in sorted(on_disk - set(reg)): bad.append("L1 %s is served but not in the licence register" % f)
for f in sorted(set(reg) - on_disk): bad.append("L1 the register lists %s, which is not a file" % f)
content = {}
try: content = json.load(open(os.path.join(data, "content.json")))
except Exception: pass
credits = " ".join(m.get("credit", "") + " " + m.get("licence", "") for m in content.get("models", {}).values())
for f, e in sorted(reg.items()):
    for k in ("licence", "source", "author", "sha256"):
        if not str(e.get(k, "")).strip(): bad.append("L2 %s has no %s in the register" % (f, k))
    if e.get("licence") not in ALLOWED: bad.append("L2 %s: licence %r is not in the allowed open set %s" % (f, e.get("licence"), sorted(ALLOWED)))
    p = os.path.join(data, f)
    if os.path.isfile(p) and f != "content.json" and hashlib.sha256(open(p, "rb").read()).hexdigest() != e.get("sha256"): bad.append("L2 %s: the register's sha256 is not the file's" % f)
    if os.path.isfile(p) and f == "content.json" and hashlib.sha256(open(p, "rb").read()).hexdigest() != e.get("sha256"): bad.append("L2 content.json: the register's sha256 is not the file's")
    if e.get("licence") in SHAREALIKE:
        if not str(e.get("derived", "")).strip(): bad.append("L3 %s is ShareAlike and records no derivation" % f)
        mid = f.rsplit(".", 1)[0]
        m = content.get("models", {}).get(mid)
        if not m or "CC BY-SA" not in m.get("licence", "") or "BodyParts3D" not in m.get("credit", ""): bad.append("L3 %s: the served content.json does not carry its licence and credit" % f)
lic = open(os.path.join(data, "LICENSE.md")).read() if os.path.isfile(os.path.join(data, "LICENSE.md")) else ""
if not lic: bad.append("L4 data/fleet/biomed/LICENSE.md is missing")
for need in ("CC BY-SA 2.1", "BodyParts3D", "CC0"):
    if need not in lic: bad.append("L4 LICENSE.md does not mention %s" % need)
try:
    cur = json.load(open(cur_p))["directories"]
    if "data/fleet/biomed" not in cur or not cur["data/fleet/biomed"].get("licence"): bad.append("L4 licenses/curated.json has no directory entry for data/fleet/biomed")
    inv = json.load(open(inv_p)).get("entries", {})
    if not any(k.endswith("data/fleet/biomed") for k in inv): bad.append("L4 licenses/inventory.json does not list data/fleet/biomed")
except Exception as e: bad.append("L4 the licence files could not be read: %s" % e)
for d, _, fs in os.walk(app):
    if os.path.relpath(d, app).startswith(("tools", "build")): continue
    for f in fs:
        if f.lower().endswith((".glb", ".gltf", ".obj", ".fbx")): bad.append("L5 %s is a model inside the app: models are served from data/fleet/biomed and registered there" % os.path.relpath(os.path.join(d, f), app))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
CUR="$ROOT/licenses/curated.json"; INV="$ROOT/licenses/inventory.json"
echo "[$(basename "$APP")] served assets are licensed (#1777)"
[ -d "$DATA" ] || { bad "data/fleet/biomed is missing"; exit 1; }
out="$(check "$DATA" "$APP" "$CUR" "$INV")" && ok "L1-L5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <shell edit in the scratch data dir (cwd)> <needle>
  rm -rf "${W:?}/d" "${W:?}/a"; mkdir -p "$W/a"; cp -r "$DATA" "$W/d"
  # the big models are not needed to prove a rule: truncate nothing, hash is checked, so copy as is
  ( cd "$W/d" && eval "$2" )
  if o="$(check "$W/d" "$W/a" "$CUR" "$INV" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
j() { echo "python3 -c \"import json;p='assets.json';d=json.load(open(p));$1;json.dump(d,open(p,'w'))\""; }
mutant "an unregistered model is served"     "cp dna.glb extra.glb"                                                                          "L1 extra.glb is served"
mutant "a register entry has no file"        "$(j "d['files']['ghost.glb']=dict(d['files']['dna.glb'])")"                                    "L1 the register lists ghost.glb"
mutant "a licence is not open"               "$(j "d['files']['dna.glb']['licence']='All-Rights-Reserved'")"                                 "L2 dna.glb: licence"
mutant "a source is missing"                 "$(j "d['files']['body.glb']['source']=''")"                                                    "L2 body.glb has no source"
mutant "the author is missing"               "$(j "d['files']['brain.glb']['author']=''")"                                                   "L2 brain.glb has no author"
mutant "a file changes under its hash"       "printf x >> dna.glb"                                                                           "L2 dna.glb: the register's sha256"
mutant "a ShareAlike model has no derivation" "$(j "d['files']['body.glb']['derived']=''")"                                                  "L3 body.glb is ShareAlike and records no derivation"
mutant "the in-app credit is dropped"        "python3 -c \"import json;c=json.load(open('content.json'));c['models']['body']['credit']='x';json.dump(c,open('content.json','w'))\"" "L3 body.glb"
mutant "the licence text is gone"            "rm LICENSE.md"                                                                                 "L4 data/fleet/biomed/LICENSE.md is missing"
# a model inside the app
rm -rf "${W:?}/d" "${W:?}/a"; mkdir -p "$W/a/app/src/main/assets"; cp -r "$DATA" "$W/d"; : > "$W/a/app/src/main/assets/x.glb"
if o="$(check "$W/d" "$W/a" "$CUR" "$INV" 2>&1)"; then bad "mutant 'a model inside the app' stayed green"; elif grep -q "L5 " <<<"$o"; then ok "mutant 'a model inside the app' is red (L5)"; else bad "wrong reason: $o"; fi
# the directory is unknown to the repository's licence inventory
rm -rf "${W:?}/d" "${W:?}/a"; mkdir -p "$W/a"; cp -r "$DATA" "$W/d"; python3 -c "import json;c=json.load(open('$CUR'));c['directories'].pop('data/fleet/biomed',None);json.dump(c,open('$W/cur.json','w'))"
if o="$(check "$W/d" "$W/a" "$W/cur.json" "$INV" 2>&1)"; then bad "mutant 'the directory leaves licenses/curated.json' stayed green"; elif grep -q "L4 licenses/curated.json" <<<"$o"; then ok "mutant 'the directory leaves licenses/curated.json' is red (L4)"; else bad "wrong reason: $o"; fi
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
