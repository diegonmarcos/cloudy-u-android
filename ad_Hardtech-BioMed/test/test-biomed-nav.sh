#!/usr/bin/env bash
# #1777 Hardtech-BioMed nav shape and wiring. The former simulation app became Hardtech-BioMed with seven tabs on the shared bottom nav:
# Physiology | BioChem | BioMech | Anatomy | Genetic | Neuro | Immuno. This holds:
#   B1 the bottom nav is exactly those seven ids in that order, labelled so, each with an icon of the libs:ui-icons catalogue, the default is the first
#   B2 each tab's page is ONE `scene` entry naming the scene of its own id; the hidden About is declared and off the bar
#   B3 the 3D stack is the fleet's: build.json links libs:web-core, libs:cgc-3d, libs:cad-core, libs:cad-mesh and the build script links them; the viewer
#      page is the cad-core one (cad/viewer.html) and the page calls it uses exist in that viewer (cadHighlight, cadFocus, cadShow, cadPick)
#   B4 runtime data: no model or content file is baked into the APK (no .glb/.gltf/.obj and no content.json under app/src/main), the build script builds
#      MODELS_BASE from ui.model_repo + ui.data_dir, and the served content (data/fleet/biomed/content.json) has a scene for each of the seven tabs
#   B5 the served content's tabs map onto the right models: genetic -> dna, neuro -> brain, the other five -> body
#   B6 the Anatomy tab is the full body: its layers cover every body system the body model carries (skeletal, muscular, cardiovascular, respiratory,
#      digestive, urinary, endocrine, nervous, immune, reproductive); the Neuro tab names every macro region; the Genetic tab is the DNA model only
# then plants a mutation per rule in a scratch copy and requires RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/build.json" ] || { echo "ERROR: build.json missing - zero checks ran"; exit 1; }
DATA="$ROOT/data/fleet/biomed"

check() {   # check <app dir> <data dir> -> FAIL lines
python3 - "$1" "$2" "$ROOT" <<'PY'
import json, os, re, struct, sys
app, data, root = sys.argv[1:4]
bad = []
bj = json.load(open(os.path.join(app, "build.json")))
ui = bj["ui"]
NAV = [("physiology", "Physiology"), ("biochem", "BioChem"), ("biomech", "BioMech"), ("anatomy", "Anatomy"), ("genetic", "Genetic"), ("neuro", "Neuro"), ("immuno", "Immuno")]
if ui.get("bottom_nav") != [i for i, _ in NAV]: bad.append("B1 bottom_nav is %s, want %s" % (ui.get("bottom_nav"), [i for i, _ in NAV]))
secs = {s["id"]: s for s in ui.get("sections", [])}
icons = set(re.findall(r'^\s*"([a-z0-9_]+)"\s+to', open(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt")).read(), re.M))
for i, l in NAV:
    s = secs.get(i)
    if s is None: bad.append("B1 section %s is not declared" % i); continue
    if s.get("label") != l: bad.append("B1 section %s is labelled %r, want %r" % (i, s.get("label"), l))
    if s.get("icon") not in icons: bad.append("B1 section %s icon %r is not in the catalogue" % (i, s.get("icon")))
    st = ui.get("stacks", {}).get(i, [])
    if len(st) != 1 or st[0].get("kind") != "scene" or st[0].get("scene") != i or st[0].get("title") != l: bad.append("B2 the page of %s is not one scene entry of its own id: %s" % (i, st))
if ui.get("default_section") != "physiology": bad.append("B1 default_section is not physiology")
if "about" not in secs or "about" in ui.get("bottom_nav", []): bad.append("B2 the hidden about section is missing or on the bar")
mods = bj.get("modules", {}); deps = mods.get("app", {}).get("depends_on", [])
script = open(os.path.join(app, "app/build.gradle")).read()
for lib in ("libs:web-core", "libs:cgc-3d", "libs:cad-core", "libs:cad-mesh"):
    if lib not in deps or lib not in mods: bad.append("B3 build.json does not declare %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("B3 app/build.gradle does not link %s" % lib)
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/hardtechbiomed")
viewer = open(os.path.join(kt, "ui/BioViewer.kt")).read()
if 'assetUrl("cad/viewer.html")' not in viewer: bad.append("B3 BioViewer does not load the cad-core viewer page")
vjs = open(os.path.join(root, "ab_cloud-libs-shared/libs/cad-core/src/main/assets/cad/viewer.js")).read()
for fn in ("cadHighlight", "cadFocus", "cadShow", "cadPick", "cadOpen"):
    if "window.%s =" % fn not in vjs: bad.append("B3 the cad-core viewer has no window.%s" % fn)
parse = open(os.path.join(kt, "BioContent.kt")).read() + viewer
for fn in ("cadHighlight", "cadFocus", "cadShow", "cadPick"):
    if fn not in parse: bad.append("B3 the app never calls %s" % fn)
for dp, _, fs in os.walk(os.path.join(app, "app/src/main")):
    for f in fs:
        if f.lower().endswith((".glb", ".gltf", ".obj", ".fbx")) or f == "content.json": bad.append("B4 %s is baked into the APK sources (models and content are served at run time)" % os.path.join(dp, f))
if not ui.get("model_repo") or not ui.get("data_dir"): bad.append("B4 build.json ui.model_repo / ui.data_dir are not declared (the served data location)")
if "MODELS_BASE" not in script or "ui.data_dir" not in script or "ui.model_repo" not in script: bad.append("B4 app/build.gradle does not build MODELS_BASE from ui.model_repo + ui.data_dir")
cp = os.path.join(data, "content.json")
if not os.path.isfile(cp): bad.append("B4 %s is missing" % cp)
else:
    c = json.load(open(cp))
    for i, _ in NAV:
        if i not in c.get("tabs", {}): bad.append("B4 the served content has no tab %s" % i)
    want = {"genetic": "dna", "neuro": "brain"}
    for i, _ in NAV:
        t = c.get("tabs", {}).get(i)
        if t and t.get("model") != want.get(i, "body"): bad.append("B5 tab %s uses model %r, want %r" % (i, t.get("model"), want.get(i, "body")))
    an = c.get("tabs", {}).get("anatomy", {})
    have = {m for l in an.get("layers", []) for m in l.get("match", [])}
    for s in ("skeletal", "muscular", "cardiovascular", "respiratory", "digestive", "urinary", "endocrine", "nervous", "immune", "reproductive"):
        if "system:" + s not in have: bad.append("B6 the Anatomy layers do not cover the %s system" % s)
    ne = json.dumps(c.get("tabs", {}).get("neuro", {}))
    for r in ("frontal_lobe", "parietal_lobe", "temporal_lobe", "occipital_lobe", "limbic_lobe", "cerebellum", "midbrain", "pons", "medulla", "thalamus", "hypothalamus", "hippocampus", "amygdala", "basal_ganglia", "insula"):
        if r not in ne: bad.append("B6 the Neuro tab does not name the region %s" % r)
    ge = c.get("tabs", {}).get("genetic", {})
    if ge.get("model") != "dna": bad.append("B6 the Genetic tab is not the DNA model")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] BioMed nav shape (#1777)"
out="$(check "$APP" "$DATA")" && ok "B1-B6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <python edit of d, build.json> <needle>
  rm -rf "${W:?}/app" "${W:?}/data"; mkdir -p "$W/app/app/src/main/java/com/diegonmarcos" "$W/data"; cp "$APP/build.json" "$W/app/"; cp "$APP/app/build.gradle" "$W/app/app/"
  cp -r "$APP/app/src/main/java/com/diegonmarcos/hardtechbiomed" "$W/app/app/src/main/java/com/diegonmarcos/"; cp "$DATA/content.json" "$W/data/"
  ( cd "$W/app" && python3 -c "import json;p='build.json';d=json.load(open(p));u=d['ui'];$2;json.dump(d,open(p,'w'),indent=2)" ) || { bad "mutant '$1' could not be planted"; return; }
  if o="$(check "$W/app" "$W/data" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
mutant_data() {   # mutant_data <name> <python edit of c, content.json> <needle>
  rm -rf "${W:?}/app" "${W:?}/data"; mkdir -p "$W/app/app/src/main/java/com/diegonmarcos" "$W/data"; cp "$APP/build.json" "$W/app/"; cp "$APP/app/build.gradle" "$W/app/app/"
  cp -r "$APP/app/src/main/java/com/diegonmarcos/hardtechbiomed" "$W/app/app/src/main/java/com/diegonmarcos/"
  python3 -c "import json;c=json.load(open('$DATA/content.json'));$2;json.dump(c,open('$W/data/content.json','w'))" || { bad "mutant '$1' could not be planted"; return; }
  if o="$(check "$W/app" "$W/data" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$o"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $o"; fi
}
mutant "a tab leaves the bar"            "u['bottom_nav'].remove('genetic')"                                                                "B1 bottom_nav"
mutant "two tabs swap"                   "b=u['bottom_nav']; b[0],b[1]=b[1],b[0]"                                                           "B1 bottom_nav"
mutant "a tab is renamed"                "[s.__setitem__('label','X') for s in u['sections'] if s['id']=='neuro']"                          "B1 section neuro"
mutant "an icon leaves the catalogue"    "[s.__setitem__('icon','no_such') for s in u['sections'] if s['id']=='immuno']"                    "B1 section immuno icon"
mutant "a page stops being a scene"      "u['stacks']['anatomy'][0]['kind']='entry'"                                                        "B2 the page of anatomy"
mutant "a scene names another tab"       "u['stacks']['neuro'][0]['scene']='anatomy'"                                                       "B2 the page of neuro"
mutant "the viewer lib is unlinked"      "d['modules']['app']['depends_on'].remove('libs:cad-core')"                                        "B3 build.json does not declare libs:cad-core"
mutant "the data dir is not declared"    "u.pop('data_dir')"                                                                                "B4"
mutant_data "a tab has no content"       "c['tabs'].pop('immuno')"                                                                          "B4 the served content has no tab immuno"
mutant_data "genetic is not DNA"         "c['tabs']['genetic']['model']='body'"                                                             "B5 tab genetic"
mutant_data "an Anatomy layer is gone"   "c['tabs']['anatomy']['layers']=[l for l in c['tabs']['anatomy']['layers'] if l['id']!='digestive']" "B6 the Anatomy layers do not cover the digestive"
mutant_data "a brain region is unnamed"  "c['tabs']['neuro']['groups'][0]['topics']=[t for t in c['tabs']['neuro']['groups'][0]['topics'] if t['id']!='occipital']; c['tabs']['neuro']['layers'][0]['match']=[m for m in c['tabs']['neuro']['layers'][0]['match'] if m!='occipital_lobe']" "B6 the Neuro tab does not name the region occipital_lobe"
# a model baked into the APK
rm -rf "${W:?}/app" "${W:?}/data"; mkdir -p "$W/app/app/src/main/java/com/diegonmarcos" "$W/app/app/src/main/assets" "$W/data"; cp "$APP/build.json" "$W/app/"; cp "$APP/app/build.gradle" "$W/app/app/"
cp -r "$APP/app/src/main/java/com/diegonmarcos/hardtechbiomed" "$W/app/app/src/main/java/com/diegonmarcos/"; cp "$DATA/content.json" "$W/data/"; : > "$W/app/app/src/main/assets/body.glb"
if o="$(check "$W/app" "$W/data" 2>&1)"; then bad "mutant 'a model baked into the APK' stayed green"; elif grep -q "B4 .* is baked into the APK" <<<"$o"; then ok "mutant 'a model baked into the APK' is red (B4)"; else bad "wrong reason: $o"; fi
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
