#!/usr/bin/env bash
# Tester (#1861): Inventory item images, size fields and the Storage / Apartment 3D views.
#   P1 parity (rule 16): every field and seeded record the inventory ledgers declared before #1861 (test/fixtures/inventory-baseline.json)
#      is still declared, same order, same values; the new fields only come after them.
#   P2 every inventory ledger declares photo (image) and width_cm, depth_cm, height_cm, weight_kg (number); every seeded size is a valid number;
#      the physical mock items (laptop, suit, bike, sofa ...) carry a size.
#   P3 the pure layout module holds the zone mapping (desk, racks, floor bays, shelves, Others fallback), the four apartment rooms and a door on the bottom wall,
#      imports no Android or Compose, and the JVM test covers them.
#   P4 the screens use it: a thumb on every row, the photo field, the number type, both 3D views under the tables, Compose Canvas only (no 3D dependency).
# Every check is proven red by its own mutation of a copy of the tree (the tail of this file).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

check_tree() { # <app dir>: one "ID<TAB>problem" line per failure
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
out = []
def bad(i, m): out.append(f"{i}\t{m}")
MAIN = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudme")
def rd(p):
    try: return open(p, encoding="utf-8").read()
    except OSError: return ""
def code(t): return re.sub(r"(?m)^\s*//.*$", "", re.sub(r"/\*.*?\*/", "", t, flags=re.S))

base = json.load(open(os.path.join(app, "test/fixtures/inventory-baseline.json"), encoding="utf-8"))
NEW = {"photo": "image", "width_cm": "number", "depth_cm": "number", "height_cm": "number", "weight_kg": "number"}
for coll, b in base.items():
    f = os.path.join(app, f"data/ui/inventory/{b['page']}.json")
    try: blks = [x for x in json.load(open(f, encoding="utf-8")) if x.get("kind") == "ledger"]
    except (OSError, ValueError): bad("parity", f"{b['page']} is unreadable"); continue
    led = next((l for x in blks for l in x.get("ledgers", []) if l.get("collection") == coll), None)
    if led is None: bad("parity", f"ledger {coll} is gone"); continue
    ids = [x["id"] for x in led.get("fields", [])]
    if ids[:len(b["fields"])] != b["fields"]: bad("parity", f"{coll} lost or reordered a field: {ids} vs {b['fields']}")
    seed = led.get("seed", [])
    for r in b["seed"]:
        now = next((s for s in seed if s.get("name") == r["name"]), None)
        if now is None: bad("parity", f"{coll} lost the record {r['name']}"); continue
        for k, v in r.items():
            if now.get(k) != v: bad("parity", f"{coll} {r['name']}.{k} changed from {v!r} to {now.get(k)!r}")
    fl = {x["id"]: x for x in led.get("fields", [])}
    for k, ty in NEW.items():
        if fl.get(k, {}).get("type") != ty: bad("fields", f"{coll} does not declare {k} as {ty}")
    for s in seed:
        for k in ("width_cm", "depth_cm", "height_cm", "weight_kg"):
            if k in s and not re.fullmatch(r"\d+(\.\d+)?", s[k]): bad("fields", f"{coll} {s['name']}.{k}={s[k]!r} is not a number")
        if any(k in s for k in ("width_cm", "depth_cm", "height_cm")) and not all(k in s for k in ("width_cm", "depth_cm", "height_cm")):
            bad("fields", f"{coll} {s['name']} has a partial size")
PHYS = {"inv_electronics": ["Laptop", "Phone", "Monitor (left)", "NAS", "Camera", "Drone"], "inv_clothing": ["Rain shell", "Suit", "Motorcycle helmet"],
        "inv_transport": ["Gravel bike", "Motorcycle", "Campervan"], "inv_household": ["Sofa", "Bed and mattress", "Washing machine"],
        "inv_housing": ["Einbauküche (Ablöse)"], "inv_leisure": ["Stand-up paddle board"]}
for coll, names in PHYS.items():
    page = base[coll]["page"] if coll in base else coll[4:]
    try: led = next(l for x in json.load(open(os.path.join(app, f"data/ui/inventory/{page}.json"), encoding="utf-8")) if x.get("kind") == "ledger" for l in x["ledgers"])
    except Exception: bad("sizes", f"{coll} unreadable"); continue
    for n in names:
        s = next((r for r in led.get("seed", []) if r.get("name") == n), None)
        if not s or not all(s.get(k) for k in ("width_cm", "depth_cm", "height_cm", "weight_kg")): bad("sizes", f"{coll} mock item {n} has no size")

lay = rd(os.path.join(MAIN, "InventoryLayout.kt")); lc = code(lay)
for need, why in [(r'"inv_electronics" -> Zone\.DESK', "Electronics is not on the desk"), (r'"inv_clothing" -> Zone\.RACK', "Clothing is not on the garment racks"),
                  (r'"inv_transport" -> Zone\.FLOOR', "Transport is not in the floor bays"), (r'else -> Zone\.OTHERS', "no Others fallback zone"),
                  (r'in SHELF_LEDGERS -> Zone\.SHELF', "the rest is not on the shelves"), (r'RoomId\.LEFT.*RoomId\.LIVING.*RoomId\.RIGHT.*RoomId\.ENTRY', "the apartment is not left, living, right, entry"),
                  (r'val DOOR = Door\(450, 100, APARTMENT_D\)', "the door is not on the bottom wall (z = apartment depth)"),
                  (r'Room\(RoomId\.LIVING, 300, 0, 400, 500\)', "the living room is not the central room"),
                  (r'turn && i\.wCm > w', "an item wider than its room is not turned"), (r'fun pick\(', "no tap picking"), (r'fun project\(', "no orbit projection")]:
    if not re.search(need, lc, flags=re.S): bad("layout", why)
if re.search(r"import (android|androidx)", lc): bad("pure", "InventoryLayout imports Android or Compose")
tst = rd(os.path.join(app, "app/src/test/java/com/diegonmarcos/cloudme/InventoryLayoutTest.kt"))
for n in ["placed exactly once", "never overlap", "Others is the fallback", "door sits on the bottom wall", "rule 16"]:
    if n not in tst: bad("jvm-test", f"InventoryLayoutTest lost its '{n}' test")

ls, lm = code(rd(os.path.join(MAIN, "LedgerScreen.kt"))), code(rd(os.path.join(MAIN, "LedgerModel.kt")))
iv = code(rd(os.path.join(MAIN, "InventoryViews.kt")))
if "ItemThumb(def.collection, r)" not in ls: bad("ui", "item rows draw no image on the left")
if 'f.type == "image"' not in ls or "PhotoField(" not in ls: bad("ui", "the form has no photo field")
if "InventoryViews(invDefs, data" not in ls: bad("ui", "the 3D views are not drawn below the tables")
if 'f.type == "number"' not in lm: bad("ui", "LedgerModel does not validate number fields")
if "InventoryLayout.icon(collection" not in iv: bad("ui", "no category placeholder icon")
if "detectTransformGestures" not in iv or "detectTapGestures" not in iv or "Orbit.pick(" not in iv: bad("ui", "the 3D canvas is not rotatable, zoomable and tappable")
if "Canvas(" not in iv: bad("ui", "the views do not use Compose Canvas")
gr = rd(os.path.join(app, "app/build.gradle"))
if re.search(r"(?i)filament|sceneform|jmonkey|libgdx|rajawali|min3d|opengl-?es-?compose", gr): bad("deps", "a 3D dependency was added")
for line in out: print(line)
PY
}

expect_green() { local r; r="$(check_tree "$APP")"; if [ -z "$r" ]; then ok "the tree is green"; else bad "the tree is not green"; echo "$r" | sed 's/^/        /'; fi; }
only() { check_tree "$APP" | cut -f1 | grep -qx "$1" && return 1 || return 0; }
echo "== checks on the real tree =="
expect_green
for t in "parity|every pre-existing inventory field and seeded record is still declared, in order (rule 16)" \
         "fields|every inventory ledger declares photo and the four size fields, seeded sizes are numbers" \
         "sizes|the physical mock items carry sizes" \
         "layout|zones, apartment rooms, bottom-wall door, turning and orbit picking are declared" \
         "pure|the layout module is free of Android and Compose" \
         "jvm-test|the JVM test covers placement, bounds, zone mapping, doors and parity" \
         "ui|rows show an image, the form picks a photo, both 3D views sit below the tables on Canvas" \
         "deps|no 3D dependency was added"; do
  id="${t%%|*}"; name="${t#*|}"
  if only "$id"; then ok "$name"; else bad "$name"; check_tree "$APP" | grep "^$id" | sed 's/^/        /'; fi
done

echo "== mutations: each check must go red on its own break =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/base"; cp -r "$APP/app" "$APP/data" "$APP/test" "$W/base/" 2>/dev/null; rm -rf "$W/base/app/build"
mutant() { # id description command-run-in-copy
  local id="$1" desc="$2" cmd="$3" d="$W/m"
  rm -rf "$d"; cp -r "$W/base" "$d"
  ( cd "$d" && eval "$cmd" ) >/dev/null 2>&1
  if check_tree "$d" | cut -f1 | grep -qx "$id"; then ok "red: $desc"; else bad "NOT red: $desc"; fi
}
J='python3 -c'
mutant parity "a field is dropped from Electronics" "$J \"import json;f='data/ui/inventory/electronics.json';d=json.load(open(f));l=d[1]['ledgers'][0];l['fields']=[x for x in l['fields'] if x['id']!='brand'];json.dump(d,open(f,'w'))\""
mutant parity "a seeded record is dropped from Clothing" "$J \"import json;f='data/ui/inventory/clothing.json';d=json.load(open(f));l=d[1]['ledgers'][0];l['seed'].pop(0);json.dump(d,open(f,'w'))\""
mutant parity "a seeded value is changed" "sed -i 's/\"2799.00\"/\"1.00\"/' data/ui/inventory/electronics.json"
mutant parity "the fields are reordered" "$J \"import json;f='data/ui/inventory/housing.json';d=json.load(open(f));l=d[1]['ledgers'][0];l['fields'].reverse();json.dump(d,open(f,'w'))\""
mutant parity "a whole ledger is gone" "$J \"import json;f='data/ui/inventory/leisure.json';d=json.load(open(f));d[1]['ledgers']=[];json.dump(d,open(f,'w'))\""
mutant fields "the photo field is missing from Transport" "$J \"import json;f='data/ui/inventory/transport.json';d=json.load(open(f));l=d[1]['ledgers'][0];l['fields']=[x for x in l['fields'] if x['id']!='photo'];json.dump(d,open(f,'w'))\""
mutant fields "width_cm is renamed in Food" "sed -i 's/\"id\": \"width_cm\"/\"id\": \"width\"/' data/ui/inventory/food.json"
mutant fields "a seeded size is not a number" "sed -i 's/\"width_cm\": \"31\"/\"width_cm\": \"wide\"/' data/ui/inventory/electronics.json"
mutant sizes "the laptop loses its size" "sed -i '/\"width_cm\": \"31\",/d' data/ui/inventory/electronics.json"
mutant fields "the laptop has a partial size" "sed -i '/\"depth_cm\": \"22\",/d' data/ui/inventory/electronics.json"
L=app/src/main/java/com/diegonmarcos/cloudme
mutant layout "Electronics leaves the desk" "sed -i 's/\"inv_electronics\" -> Zone.DESK/\"inv_electronics\" -> Zone.SHELF/' $L/InventoryLayout.kt"
mutant layout "Clothing leaves the racks" "sed -i 's/\"inv_clothing\" -> Zone.RACK/\"inv_clothing\" -> Zone.FLOOR/' $L/InventoryLayout.kt"
mutant layout "the Others fallback is gone" "sed -i 's/else -> Zone.OTHERS/else -> Zone.SHELF/' $L/InventoryLayout.kt"
mutant layout "the door leaves the bottom wall" "sed -i 's/Door(450, 100, APARTMENT_D)/Door(450, 100, 0)/' $L/InventoryLayout.kt"
mutant layout "the living room moves off-centre" "sed -i 's/Room(RoomId.LIVING, 300, 0, 400, 500)/Room(RoomId.LIVING, 0, 0, 400, 500)/' $L/InventoryLayout.kt"
mutant layout "wide items are no longer turned" "sed -i 's/turn \&\& i.wCm > w/false \&\& i.wCm > w/' $L/InventoryLayout.kt"
mutant layout "tap picking is removed" "sed -i 's/fun pick(/fun pickX(/' $L/InventoryLayout.kt"
mutant pure "the layout imports Compose" "sed -i '2i import androidx.compose.ui.graphics.Color' $L/InventoryLayout.kt"
mutant jvm-test "the placed-once test is dropped" "sed -i 's/placed exactly once/placed/' app/src/test/java/com/diegonmarcos/cloudme/InventoryLayoutTest.kt"
mutant jvm-test "the parity test is dropped" "sed -i 's/rule 16/rule/' app/src/test/java/com/diegonmarcos/cloudme/InventoryLayoutTest.kt"
mutant ui "rows lose their image" "sed -i 's/ItemThumb(def.collection, r)/Unit/' $L/LedgerScreen.kt"
mutant ui "the form loses its photo field" "sed -i 's/PhotoField(/PhotoFieldX(/' $L/LedgerScreen.kt"
mutant ui "the 3D views are not drawn" "sed -i 's/InventoryViews(invDefs, data/InventoryViewsX(invDefs, data/' $L/LedgerScreen.kt"
mutant ui "number fields are not validated" "sed -i 's/f.type == \"number\"/f.type == \"numberx\"/' $L/LedgerModel.kt"
mutant ui "the canvas cannot be zoomed" "sed -i 's/detectTransformGestures/detectDragGestures/' $L/InventoryViews.kt"
mutant ui "no placeholder icon" "sed -i 's/InventoryLayout.icon(collection/InventoryLayout.iconX(collection/' $L/InventoryViews.kt"
mutant deps "a 3D engine is added" "echo \"implementation 'com.google.android.filament:filament-android:1.0'\" >> app/build.gradle"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
