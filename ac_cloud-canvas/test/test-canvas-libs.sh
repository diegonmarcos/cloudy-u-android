#!/usr/bin/env bash
# test-canvas-libs (#1865): Cloud Canvas is a thin shell over its section libraries. No build and no network.
#   S1 build.json::modules declares libs:canvas-core, libs:canvas-draw, libs:canvas-painter and libs:canvas-play, each a library whose dir is
#      ../ab_cloud-libs-shared/libs/<name> and exists with its build.gradle and manifest; the app depends_on all four and app/build.gradle
#      links all four (settings.gradle includes whatever build.json::modules declares)
#   S2 each section's sources live in its library and nowhere else: Draw (draw/, board/, ui/DrawScreen, ui/DrawController, ui/BoardScreen)
#      in canvas-draw, Painter (painter/, ui/PainterScreen) in canvas-painter, Play (audio/, ui/PlayScreen, ui/PlayController) in
#      canvas-play, the shared palette, AppTheme, Folders and ExportNames in canvas-core; the app module keeps only the shell (MainActivity,
#      Decl, ui/CanvasShell), the Enhancers (enhance/, ui/Enhance*, ui/AiConfigsPage) and no file of a section; no Kotlin file exists twice
#   S3 parity (rule 16): every top-level declaration, class member, enum entry (tools, instruments, waves, drum pads, engines, tasks),
#      screen label and asset recorded in test/data/parity-baseline-1865.json (snapshotted from the tree BEFORE the split) is still declared
#      somewhere in the app or its canvas libraries (test/canvas_parity.py check)
#   S4 the libraries stay one-way: no section library imports the app (Decl, BuildConfig of the app, CanvasStart, the shell) and no
#      section library imports another section (draw, painter and play reach only canvas-core); the R they draw from is canvas-core's
#   S5 the fleet registries name every canvas library: lib-classes.json (gui), lib-categories.json, lib-apks/build.json (no lib APK, and
#      a description), and the generated lib-consumers.json lists Cloud Canvas as the one consumer of each
# Each rule is then proven red by a planted mutation in a scratch copy.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {  # $1 = repo root holding ac_cloud-canvas and ab_cloud-libs-shared; prints one line per failed rule
python3 -I - "$1" "$HERE/canvas_parity.py" <<'PY'
import json, os, re, subprocess, sys
root, parity = sys.argv[1], sys.argv[2]
app = os.path.join(root, "ac_cloud-canvas")
libs = os.path.join(root, "ab_cloud-libs-shared/libs")
LIBS = ["canvas-core", "canvas-draw", "canvas-painter", "canvas-play"]
bj = json.load(open(os.path.join(app, "build.json")))
mods = bj["modules"]
for l in LIBS:
    s = mods.get("libs:" + l)
    if not isinstance(s, dict) or s.get("type") != "library" or s.get("dir") != "../ab_cloud-libs-shared/libs/" + l:
        print("S1 build.json::modules does not declare libs:%s as a library at ../ab_cloud-libs-shared/libs/%s" % (l, l)); continue
    for f in ("build.gradle", "src/main/AndroidManifest.xml"):
        if not os.path.isfile(os.path.join(libs, l, f)): print("S1 libs/%s has no %s" % (l, f))
    if "libs:" + l not in mods["app"].get("depends_on", []): print("S1 the app does not depend_on libs:" + l)
gr = open(os.path.join(app, "app/build.gradle")).read()
for l in LIBS:
    if "implementation project(':libs:%s')" % l not in gr: print("S1 app/build.gradle does not link libs:" + l)
PKG = "src/main/java/com/diegonmarcos/cloudcanvas/"
home = {"canvas-core": ["ui/AppTheme.kt", "ui/Folders.kt", "draw/ExportNames.kt"],
        "canvas-draw": ["draw/Model.kt", "draw/DocRenderer.kt", "draw/Pixels.kt", "board/BoardStore.kt", "ui/DrawScreen.kt", "ui/DrawController.kt", "ui/BoardScreen.kt"],
        "canvas-painter": ["painter/PainterStore.kt", "ui/PainterScreen.kt"],
        "canvas-play": ["audio/Engine.kt", "audio/Mixer.kt", "audio/Synth.kt", "audio/Samples.kt", "audio/SampleLoader.kt", "audio/SoundFont.kt", "audio/AudioOut.kt", "audio/Wav.kt", "ui/PlayScreen.kt", "ui/PlayController.kt"]}
for l, fs in home.items():
    for f in fs:
        if not os.path.isfile(os.path.join(libs, l, PKG, f)): print("S2 %s is not in libs/%s" % (f, l))
if not os.path.isfile(os.path.join(libs, "canvas-core/src/main/res/values/colors.xml")): print("S2 the palette (colors.xml) is not in libs/canvas-core")
appjava = os.path.join(app, "app", PKG)
for d in ("draw", "board", "painter", "audio"):
    p = os.path.join(appjava, d)
    if os.path.isdir(p) and any(f.endswith(".kt") for f in os.listdir(p)): print("S2 the app module still holds section sources in %s/" % d)
for f in ("MainActivity.kt", "Decl.kt", "ui/CanvasShell.kt", "ui/EnhanceScreen.kt", "ui/EnhanceController.kt", "ui/AiConfigsPage.kt"):
    if not os.path.isfile(os.path.join(appjava, f)): print("S2 the shell lost " + f)
seen = {}
for top in [os.path.join(app, "app/src/main/java")] + [os.path.join(libs, l, "src/main/java") for l in LIBS]:
    for r, _, fs in os.walk(top):
        for f in fs:
            if f.endswith(".kt"):
                k = os.path.relpath(os.path.join(r, f), top)
                if k in seen: print("S2 %s exists twice (%s and %s)" % (k, seen[k], top))
                seen[k] = top
pr = subprocess.run([sys.executable, "-I", parity, "check", app, os.path.join(app, "test/data/parity-baseline-1865.json")], capture_output=True, text=True)
if pr.returncode != 0: print("S3 parity broken: " + pr.stdout.strip().replace("\n", "; ")[:400])
for l in LIBS:
    for r, _, fs in os.walk(os.path.join(libs, l, "src/main/java")):
        for f in fs:
            if not f.endswith(".kt"): continue
            t = open(os.path.join(r, f)).read()
            imps = re.findall(r"^import ([\w.]+)", t, re.M)
            if any(i in ("com.diegonmarcos.cloudcanvas.Decl", "com.diegonmarcos.cloudcanvas.CanvasStart", "com.diegonmarcos.cloudcanvas.BuildConfig", "com.diegonmarcos.cloudcanvas.R") for i in imps) \
               or "com.diegonmarcos.cloudcanvas.BuildConfig" in t:
                print("S4 libs/%s %s reaches into the app (Decl, CanvasStart, the app's BuildConfig or R)" % (l, f))
            if l != "canvas-core":
                other = [o for o in LIBS if o not in (l, "canvas-core")]
                for i in imps:
                    for o in other:
                        for f2 in home[o]:
                            sym = "com.diegonmarcos.cloudcanvas." + f2[:-3].replace("/", ".")
                            if i == sym or i.startswith(sym + "."):
                                print("S4 libs/%s %s imports %s of libs/%s" % (l, f, i, o))
            for rm in re.findall(r"^import ([\w.]+\.R)$", t, re.M):
                if rm != "com.diegonmarcos.cloudlib.canvas.core.R": print("S4 libs/%s %s draws from %s, not canvas-core's R" % (l, f, rm))
# the cross-section types the move must not drag across (each section's model type names, imported unqualified)
sect_types = {"canvas-draw": ["DrawController", "DocRenderer", "DrawModel", "BoardStore", "BoardController"], "canvas-painter": ["PainterStore", "PainterController"],
              "canvas-play": ["PlayController", "MixEngine", "MixerState", "AudioOut"]}
for l, names in sect_types.items():
    for o in [x for x in sect_types if x != l]:
        for r, _, fs in os.walk(os.path.join(libs, o, "src/main/java")):
            for f in fs:
                if f.endswith(".kt") and re.search(r"\b(%s)\b" % "|".join(names), open(os.path.join(r, f)).read()):
                    print("S4 libs/%s %s names a type of libs/%s" % (o, f, l))
cls = json.load(open(os.path.join(root, "1_cicd/src/data/lib-classes.json")))["libs"]
cats = [m for c in json.load(open(os.path.join(root, "1_cicd/src/data/lib-categories.json")))["categories"] for m in c["members"]]
apks = open(os.path.join(root, "ab_cloud-libs-shared/lib-apks/build.json")).read()
cons = {}
for line in open(os.path.join(root, "aa_cloud-superapp/data/lib-consumers.json")):
    line = line.strip().rstrip(",")
    if line.startswith("{\"") and "\"id\"" in line:
        try: e = json.loads(line); cons[e["id"]] = e
        except ValueError: pass
for l in LIBS:
    if cls.get(l, {}).get("class") != "gui": print("S5 lib-classes.json does not classify %s gui" % l)
    if cats.count(l) != 1: print("S5 lib-categories.json does not list %s exactly once" % l)
    if apks.count('"%s":' % l) < 2: print("S5 lib-apks/build.json lacks the no-APK reason or the description of " + l)
    if cons.get(l, {}).get("compiled_by") != ["ac_cloud-canvas"]: print("S5 lib-consumers.json does not name ac_cloud-canvas as the one consumer of " + l)
PY
}

echo "== the real tree =="
OUT="$(check "$ROOT")"
[ -z "$OUT" ] && ok "S1-S5 hold on ac_cloud-canvas and its libraries" || { bad "rules broken:"; echo "$OUT"; }

S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mut() {  # $1 = label, $2 = rule prefix expected, $3.. = edit of the scratch copy (run in the scratch repo root)
  local label="$1" want="$2"; shift 2
  rm -rf "$S/r"; mkdir -p "$S/r/ac_cloud-canvas/app/src" "$S/r/ab_cloud-libs-shared/libs" "$S/r/ab_cloud-libs-shared/lib-apks" "$S/r/1_cicd/src/data" "$S/r/aa_cloud-superapp/data"
  cp -r "$APP/build.json" "$APP/test" "$S/r/ac_cloud-canvas/"; cp "$APP/app/build.gradle" "$S/r/ac_cloud-canvas/app/"; cp -r "$APP/app/src/main" "$S/r/ac_cloud-canvas/app/src/" 2>/dev/null
  rm -rf "$S/r/ac_cloud-canvas/app/src/main/assets"; cp -r "$ROOT"/ab_cloud-libs-shared/libs/canvas-* "$S/r/ab_cloud-libs-shared/libs/"
  # the asset inventory without copying the bank and the samples: empty files under the same names
  ( cd "$APP/app/src/main" && find assets -type f ) | while IFS= read -r f; do mkdir -p "$S/r/ac_cloud-canvas/app/src/main/$(dirname "$f")"; : > "$S/r/ac_cloud-canvas/app/src/main/$f"; done
  cp "$ROOT/ab_cloud-libs-shared/lib-apks/build.json" "$S/r/ab_cloud-libs-shared/lib-apks/"; cp "$ROOT/aa_cloud-superapp/data/lib-consumers.json" "$S/r/aa_cloud-superapp/data/"
  cp "$ROOT/1_cicd/src/data/lib-classes.json" "$ROOT/1_cicd/src/data/lib-categories.json" "$S/r/1_cicd/src/data/"
  ( cd "$S/r" && "$@" )
  if check "$S/r" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
C=ab_cloud-libs-shared/libs
J=src/main/java/com/diegonmarcos/cloudcanvas
mut "a lib undeclared"            S1 python3 -I -c 'import json;p="ac_cloud-canvas/build.json";d=json.load(open(p));del d["modules"]["libs:canvas-play"];json.dump(d,open(p,"w"))'
mut "a lib not depended on"       S1 python3 -I -c 'import json;p="ac_cloud-canvas/build.json";d=json.load(open(p));d["modules"]["app"]["depends_on"].remove("libs:canvas-draw");json.dump(d,open(p,"w"))'
mut "a lib unlinked"              S1 sed -i "/implementation project(':libs:canvas-painter')/d" ac_cloud-canvas/app/build.gradle
mut "a lib without a manifest"    S1 rm $C/canvas-core/src/main/AndroidManifest.xml
mut "a section file back in app"  S2 sh -c "mkdir -p ac_cloud-canvas/app/$J/audio && mv $C/canvas-play/$J/audio/Wav.kt ac_cloud-canvas/app/$J/audio/"
mut "a file copied, not moved"    S2 sh -c "mkdir -p ac_cloud-canvas/app/$J/draw && cp $C/canvas-draw/$J/draw/Model.kt ac_cloud-canvas/app/$J/draw/"
mut "the palette left core"       S2 rm $C/canvas-core/src/main/res/values/colors.xml
mut "the shell lost Decl"         S2 rm ac_cloud-canvas/app/$J/Decl.kt
mut "a tool dropped"              S3 sed -i 's/, Eyedropper("Eyedropper")//' $C/canvas-draw/$J/draw/Model.kt
mut "a drum pad dropped"          S3 sed -i 's/, Ride("Ride")//' $C/canvas-play/$J/audio/Synth.kt
mut "a controller feature dropped" S3 sed -i 's/fun exportWav(/fun exportX(/' $C/canvas-play/$J/ui/PlayController.kt
mut "a screen label dropped"      S3 sed -i 's/Text("Export PNG"/Text("Save"/' $C/canvas-draw/$J/ui/DrawScreen.kt
mut "a declaration dropped"       S3 sed -i 's/^object ExportNames {/private object ExportNames {/' $C/canvas-core/$J/draw/ExportNames.kt
mut "an asset dropped"            S3 rm ac_cloud-canvas/app/src/main/assets/painter/grid.js
mut "a whole lib dropped"         S3 python3 -I -c 'import json;p="ac_cloud-canvas/build.json";d=json.load(open(p));del d["modules"]["libs:canvas-painter"];json.dump(d,open(p,"w"))'
mut "a lib reads the app Decl"    S4 sed -i 's/^package com.diegonmarcos.cloudcanvas.ui$/package com.diegonmarcos.cloudcanvas.ui\nimport com.diegonmarcos.cloudcanvas.Decl/' $C/canvas-painter/$J/ui/PainterScreen.kt
mut "a lib reads the app BuildConfig" S4 sed -i 's/?: defaultEngine)/?: com.diegonmarcos.cloudcanvas.BuildConfig.AUDIO_DEFAULT_ENGINE)/' $C/canvas-play/$J/ui/PlayController.kt
mut "play imports draw"           S4 sed -i 's/^package com.diegonmarcos.cloudcanvas.ui$/package com.diegonmarcos.cloudcanvas.ui\nimport com.diegonmarcos.cloudcanvas.draw.Model/' $C/canvas-play/$J/ui/PlayScreen.kt
mut "painter names a draw type"   S4 sh -c "echo 'val x: DrawController? = null' >> $C/canvas-painter/$J/painter/PainterStore.kt"
mut "a lib draws from the app R"  S4 sed -i 's/^import com.diegonmarcos.cloudlib.canvas.core.R$/import com.diegonmarcos.cloudcanvas.R/' $C/canvas-draw/$J/ui/BoardScreen.kt
mut "a lib unclassified"          S5 python3 -I -c 'import json;p="1_cicd/src/data/lib-classes.json";d=json.load(open(p));del d["libs"]["canvas-play"];json.dump(d,open(p,"w"))'
mut "a lib uncategorised"         S5 python3 -I -c 'import json;p="1_cicd/src/data/lib-categories.json";d=json.load(open(p));[c["members"].remove("canvas-draw") for c in d["categories"] if "canvas-draw" in c["members"]];json.dump(d,open(p,"w"))'
mut "a lib's apk reason dropped"  S5 sed -i '/"canvas-core": "#870/d' ab_cloud-libs-shared/lib-apks/build.json
mut "a consumer missing"          S5 sed -i 's/"compiled_by":\["ac_cloud-canvas"\],"description":"Cloud Canvas Painter/"compiled_by":[],"description":"Cloud Canvas Painter/' aa_cloud-superapp/data/lib-consumers.json

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
