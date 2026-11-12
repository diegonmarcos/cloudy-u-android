#!/usr/bin/env bash
# test-models-renderable (#1542): every model declared in app/src/main/assets/cad/models.json has a renderable derivative.
# Static, no build, no network, no device: tools/check_models.py loads each declared model's bundled GLB and requires that it exists,
# is not an LFS pointer, has its declared size and sha256, parses as glTF 2.0 binary with no extension the viewer cannot decode, and
# holds a non-zero triangle count that equals the declared stats. A planted fault per rule must turn it RED (a tester that cannot
# fail is not one). The Node job of .github/workflows/hardtech-cad-models.yml parses the same files with @gltf-transform/core.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/tools/check_models.py" ] || { echo "ERROR: $APP/tools/check_models.py missing - zero checks ran"; exit 1; }
FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[$(basename "$APP")] models render"
out="$(python3 -I "$APP/tools/check_models.py" "$APP" 2>&1)" && ok "every declared model has a renderable GLB that parses with triangles" || { bad "the real tree is red"; echo "$out"; }
n="$(grep -c '^ok ' <<<"$out")"
[ "$n" -ge 2 ] && ok "at least a boat and a SketchUp model are checked ($n models)" || bad "only $n models were checked"
grep -q '^ok campidoglio' <<<"$out" && ok "the SketchUp model has a rendered GLB" || bad "the SketchUp model (campidoglio) is not rendered"
grep -q '^ok one-lilo' <<<"$out" && ok "the boat model has a rendered GLB" || bad "the boat model (one-lilo) is not rendered"

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
ASSETS="app/src/main/assets/cad"
mutant() {   # mutant <name> <edit run in the copy> <needle expected in the output>
  rm -rf "${W:?}/$FOLDER"; mkdir -p "$W/$FOLDER"; cp -r "$APP/app" "$APP/tools" "$W/$FOLDER/"; [ -d "$APP/data" ] && cp -r "$APP/data" "$W/$FOLDER/"
  ( cd "$W/$FOLDER" && eval "$2" )
  if o="$(python3 -I "$W/$FOLDER/tools/check_models.py" "$W/$FOLDER" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
jedit() { python3 -c "import json;p='$ASSETS/models.json';d=json.load(open(p));$1;json.dump(d,open(p,'w'),indent=2)"; }
mutant "a view file vanishes"            "rm $ASSETS/models/campidoglio.view.glb"                                            "FAIL G3 campidoglio"
mutant "a GLB is cut short"              "head -c 3000 $ASSETS/models/one-lilo.view.glb > t && mv t $ASSETS/models/one-lilo.view.glb" "FAIL G3 one-lilo"
mutant "a GLB is an LFS pointer"         "printf 'version https://git-lfs.github.com/spec/v1\noid sha256:00\nsize 1\n' > $ASSETS/models/one-lilo.view.glb" "FAIL G3 one-lilo"
mutant "a model loses its view"          "jedit \"d['models'][1].pop('view')\""                                              "FAIL G2 campidoglio has no renderable derivative"
mutant "a view is not a GLB"             "jedit \"d['models'][0]['view']['format']='step'\""                                 "FAIL G2 one-lilo has no renderable derivative"
mutant "the declared hash is wrong"      "jedit \"d['models'][1]['view']['sha256']='0'*64\""                                 "FAIL G3 campidoglio"
mutant "the declared triangles are wrong" "jedit \"d['models'][1]['stats']['triangles']=1\""                                 "FAIL G4 campidoglio: declared stats"
mutant "a category is undeclared"        "jedit \"d['models'][0]['category']='ships'\""                                      "FAIL G1 one-lilo: category"
mutant "a model drops its software"      "jedit \"d['models'][1].pop('software')\""                                          "FAIL G1 campidoglio declares no software"
mutant "no model is declared"            "jedit \"d['models']=[]\""                                                          "FAIL G1 models.json declares no model"
mutant "a preview image vanishes"        "rm $ASSETS/models/drone.thumb.jpg"                                                 "FAIL G6 drone: preview image"
mutant "a model declares no preview"     "jedit \"d['models'][0]['view'].pop('thumb')\""                                    "FAIL G6 one-lilo declares no preview image"
mutant "a preview is not a JPEG"         "printf 'not an image' > $ASSETS/models/campidoglio.thumb.jpg"                     "FAIL G6 campidoglio: preview image"
mutant "an IFC derivative vanishes"      "rm -f data/derived/campidoglio.view.ifc"                                           "FAIL G5 campidoglio"
# a GLB with zero triangles (valid file, nothing to draw) and one needing Draco: rewritten in place with its hash and stats kept consistent
mutant "a GLB holds no triangles"        "python3 - <<'PY'
import json, struct, hashlib
p = '$ASSETS/models/campidoglio.view.glb'
d = open(p, 'rb').read(); n = struct.unpack('<I', d[12:16])[0]; j = json.loads(d[20:20 + n])
for m in j['meshes']:
    m['primitives'] = []
b = json.dumps(j, separators=(',', ':')).encode(); b += b' ' * ((4 - len(b) % 4) % 4); rest = d[20 + n:]
o = b'glTF' + struct.pack('<II', 2, 20 + len(b) + len(rest)) + struct.pack('<II', len(b), 0x4E4F534A) + b + rest
open(p, 'wb').write(o)
m = json.load(open('$ASSETS/models.json')); v = m['models'][1]['view']; v['size'] = len(o); v['sha256'] = hashlib.sha256(o).hexdigest()
json.dump(m, open('$ASSETS/models.json', 'w'), indent=2)
PY"                                                                                                                                  "holds 0 triangles"
mutant "a GLB needs Draco"               "python3 - <<'PY'
import json, struct, hashlib
p = '$ASSETS/models/campidoglio.view.glb'
d = open(p, 'rb').read(); n = struct.unpack('<I', d[12:16])[0]; j = json.loads(d[20:20 + n])
j['extensionsRequired'] = ['KHR_draco_mesh_compression']
b = json.dumps(j, separators=(',', ':')).encode(); b += b' ' * ((4 - len(b) % 4) % 4); rest = d[20 + n:]
o = b'glTF' + struct.pack('<II', 2, 20 + len(b) + len(rest)) + struct.pack('<II', len(b), 0x4E4F534A) + b + rest
open(p, 'wb').write(o)
m = json.load(open('$ASSETS/models.json')); v = m['models'][1]['view']; v['size'] = len(o); v['sha256'] = hashlib.sha256(o).hexdigest()
json.dump(m, open('$ASSETS/models.json', 'w'), indent=2)
PY"                                                                                                                                  "KHR_draco_mesh_compression"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
