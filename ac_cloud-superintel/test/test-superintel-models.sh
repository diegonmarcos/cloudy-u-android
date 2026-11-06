#!/usr/bin/env bash
# test-superintel-models - the Model Catalogue, the attach menu and the image flow of Cloud SuperIntel (libs:ai), statically.
#   M1  the tabs are Core's three rows, exactly: Frontier | Text | Search / Audio & Speech | Image | Video / Specialist | Tasks | Medium | Small
#   M2  the baked snapshot follows its rules (tools/gen-model-snapshot.py --check): no tab empty, every entry complete (id, name, provider,
#       modalities, context, price in/out, link), no placeholder / TBD / sample text; Image and Audio are filled
#   M3  data-driven Kotlin: the tab, attach and image-flow code spells no tab id, no sp literal, no model id, no key
#   M4  the key of the image flow is read per send from the private store (OpenRouterKey over PrivateSource), never baked
#   M5  centralized perms: Camera and Image are libs:perms rows (build.json perms.needs + manifest + the design's attach perms), no request in the lib
#   M6  the MCP page is the derivation of the SOT (when the SOT is at hand: CLAUDE_SOT_DIR) and its names digest equals it
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <root holding ac_cloud-superintel and ab_cloud-libs-shared/libs/ai>
python3 -I - "$1" <<'PY'
import json, os, re, subprocess, sys
root = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
app = os.path.join(root, "ac_cloud-superintel")
lib = os.path.join(root, "ab_cloud-libs-shared/libs/ai")
tabs = json.loads(read(os.path.join(lib, "src/main/assets/ai/model-tabs.json")))
LK = os.path.join(lib, "src/main/kotlin/com/diegonmarcos/superapp/ai")
AK = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")

# M1
want = [{"header": "Core", "rows": [["frontier", "text", "search"], ["audio", "image", "video"], ["specialist", "tasks", "medium", "small"]]}]
if tabs["layout"] != want: bad.append("M1 the tab layout is not Core's three rows in the owner's order: %s" % json.dumps(tabs["layout"]))
labels = [tabs["tabs"][t]["label"] for blk in tabs["layout"] for r in blk["rows"] for t in r]
if labels != ["Frontier", "Text", "Search", "Audio & Speech", "Image", "Video", "Specialist", "Tasks", "Medium", "Small"]: bad.append("M1 the tab labels are %s" % labels)
if tabs["search"]["url"] != "https://openrouter.ai/api/v1/models" or tabs["search"]["debounce_ms"] < 200 or tabs["search"]["ttl_minutes"] < 1: bad.append("M1 the live search is not the public models API, debounced and cached")

# M2
r = subprocess.run([sys.executable, "-I", os.path.join(lib, "tools/gen-model-snapshot.py"), "--check"], capture_output=True, text=True)
for line in r.stdout.splitlines(): bad.append("M2 " + line)
snap = json.loads(read(os.path.join(lib, "src/main/assets/ai/models-snapshot.json")))
for t in ("image", "audio"):
    if len(snap["tabs"].get(t, [])) < 5: bad.append("M2 the %s tab has fewer than 5 real models" % t)

# M3
NEW = ["AiModelTabs.kt", "AiAttachUi.kt", "AiAttachments.kt", "AiCataloguePages.kt"]
for f in NEW:
    t = code(os.path.join(LK, f))
    if re.search(r"\b\d+(\.\d+)?\.sp\b|\bTextUnit\(|fontSize\s*=\s*\d", t): bad.append("M3 %s spells an sp literal: the ui-kit / theme type ramp is used" % f)
    for tab in ("frontier", "specialist", "tasks", "medium", "small"):
        if '"%s"' % tab in t: bad.append("M3 %s spells the tab id %s: tabs are data" % (f, tab))
    if re.search(r"\b[a-z0-9-]+/[a-z0-9._-]+-(?:\d|image|audio|pro|flash)", t) and "openrouter.ai" not in t: bad.append("M3 %s spells a model id" % f)
    if re.search(r"sk-or-|sk-ant-|Bearer\s+[A-Za-z0-9._-]{16,}", t): bad.append("M3 %s holds a key" % f)
if re.search(r"sk-or-|sk-ant-", read(os.path.join(lib, "src/main/assets/ai/models-snapshot.json")) + read(os.path.join(lib, "src/main/assets/ai/model-tabs.json"))): bad.append("M3 a key is baked into the catalogue data")

# M4
k = code(os.path.join(AK, "OpenRouterKey.kt"))
if "PrivateSource" not in k or "src.get(" not in k: bad.append("M4 OpenRouterKey does not read the key from the private store")
if re.search(r"\"sk-|BuildConfig|SharedPreferences|putString|writeText", k): bad.append("M4 OpenRouterKey bakes, stores or writes the key")
si = code(os.path.join(AK, "SiAi.kt"))
if "AiTokenSource" not in si or "OpenRouterKey.source(" not in si: bad.append("M4 SiAi does not hand the image flow a per-send token source")

# M5
bj = json.loads(read(os.path.join(app, "build.json")))
design = json.loads(read(os.path.join(lib, "src/main/assets/ai/ai-design.json")))
att = design["composer"]["plus"]["attach"]
if [a["id"] for a in att] != ["files", "images", "camera"]: bad.append("M5 the (+) menu is not Files, Image, Camera")
needs = bj["perms"]["needs"]
for a in att:
    for p in a.get("perms", []):
        if p not in needs: bad.append("M5 attach %s needs perms row %s which build.json::perms.needs lacks" % (a["id"], p))
if not any(a["id"] == "camera" and a.get("requires") and "camera" in a.get("perms", []) for a in att): bad.append("M5 Camera is not gated on its Perms row")
man = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
for perm in ("android.permission.CAMERA", "android.permission.READ_MEDIA_IMAGES"):
    if perm not in man: bad.append("M5 the manifest lacks %s" % perm)
# #1547 the grants live in the ONE runtime dataset keyed by package (data/fleet/app-perms.json), no baked per-app copy
fleet = json.loads(read(os.path.join(root, "data/fleet/app-perms.json")))["apps"].get(bj["android"]["application_id"], {})
if fleet.get("needs") != needs: bad.append("M5 data/fleet/app-perms.json differs from build.json::perms (rerun cloud-android-perms-guard.py --write)")
for f in ("AiAttachUi.kt", "AiAttachments.kt", "AiComposerBar.kt"):
    if re.search(r"requestPermissions|RequestPermission|ACTION_APPLICATION_DETAILS|pm grant", code(os.path.join(LK, f))): bad.append("M5 %s asks for a permission itself" % f)
if "PermsFixChip" not in code(os.path.join(AK, "ui/SiShell.kt")): bad.append("M5 the app does not show the Fix in Perms chip")

# M6
sot = os.environ.get("CLAUDE_SOT_DIR", "")
if sot and os.path.isdir(sot):
    if subprocess.call([sys.executable, "-I", os.path.join(app, "tools/derive-ai-data.py"), sot, "--check"]) != 0: bad.append("M6 the MCP page or its digest differs from the SOT: rerun tools/derive-ai-data.py")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[superintel-models] catalogue, attach menu, image flow"
out="$(check "$ROOT")" && ok "M1-M6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
LIBREL=ab_cloud-libs-shared/libs/ai
mutant() {   # mutant <name> <shell edit run in the copy's root> <needle expected in the output>
  rm -rf "$W/m"; mkdir -p "$W/m/$LIBREL"
  cp -r "$APP" "$W/m/ac_cloud-superintel"; cp -r "$ROOT/$LIBREL/src" "$ROOT/$LIBREL/tools" "$W/m/$LIBREL/"
  mkdir -p "$W/m/data/fleet"; cp "$ROOT/data/fleet/app-perms.json" "$W/m/data/fleet/"
  ( cd "$W/m" && eval "$2" )
  if out="$(check "$W/m" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
L=$LIBREL/src/main/assets/ai
K=$LIBREL/src/main/kotlin/com/diegonmarcos/superapp/ai
PYS="import json;p='$L/models-snapshot.json';d=json.load(open(p));"
PYT="import json;p='$L/model-tabs.json';d=json.load(open(p));"
mutant "a tab is emptied"         "python3 -c \"${PYS}d['tabs']['image']=[];json.dump(d,open(p,'w'))\""                                "tab image is empty"
mutant "an entry says TBD"        "python3 -c \"${PYS}d['tabs']['audio'][0]['name']='TBD model';json.dump(d,open(p,'w'))\""            "placeholder text in name"
mutant "an entry loses its price" "python3 -c \"${PYS}d['tabs']['text'][0]['price']={'unit':'tokens','in':None,'out':None};json.dump(d,open(p,'w'))\"" "no price"
mutant "an entry loses its link"  "python3 -c \"${PYS}del d['tabs']['video'][0]['link'];json.dump(d,open(p,'w'))\""                    "lacks link"
mutant "the tab order changes"    "python3 -c \"${PYT}d['layout'][0]['rows'][0].reverse();json.dump(d,open(p,'w'))\""                  "M1 the tab layout is not"
mutant "a tab is renamed"         "sed -i 's/\"Audio & Speech\"/\"Audio\"/' $L/model-tabs.json"                                        "M1 the tab labels are"
mutant "the search loses its API" "sed -i 's#https://openrouter.ai/api/v1/models\"#https://example.invalid/models\"#' $L/model-tabs.json" "M1 the live search is not"
mutant "an sp literal creeps in"  "echo 'val x = 12.sp' >> $K/AiAttachUi.kt"                                                            "M3 AiAttachUi.kt spells an sp literal"
mutant "a tab id is spelled"      "echo 'val t = \"frontier\"' >> $K/AiCataloguePages.kt"                                               "M3 AiCataloguePages.kt spells the tab id"
mutant "a key is baked"           "echo 'val k = \"sk-or-v1-abcdefabcdefabcdef\"' >> ac_cloud-superintel/app/src/main/java/com/diegonmarcos/cloudsuperintel/OpenRouterKey.kt" "M4 OpenRouterKey bakes"
mutant "the key leaves the store" "sed -i 's/src[.]get(/cfg.get(/g' ac_cloud-superintel/app/src/main/java/com/diegonmarcos/cloudsuperintel/OpenRouterKey.kt" "M4 OpenRouterKey does not read"
mutant "a perms row is dropped"   "python3 -c \"import json;p='ac_cloud-superintel/build.json';d=json.load(open(p));d['perms']['needs'].remove('camera');json.dump(d,open(p,'w'))\"" "M5 attach camera needs perms row camera"
mutant "the lib asks itself"      "echo 'val r = \"requestPermissions\"' >> $K/AiAttachUi.kt"                                           "M5 AiAttachUi.kt asks for a permission itself"
mutant "camera loses its gate"    "sed -i 's/\"requires\": true/\"requires\": false/' $L/ai-design.json"                                "M5 Camera is not gated"
mutant "the perms dataset drifts"  "python3 -c \"import json;p='data/fleet/app-perms.json';d=json.load(open(p));d['apps']['com.diegonmarcos.cloudsuperintel']['needs']=['camera'];json.dump(d,open(p,'w'))\"" "M5 data/fleet/app-perms.json differs"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
