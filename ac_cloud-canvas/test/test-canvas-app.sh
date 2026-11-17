#!/usr/bin/env bash
# test-canvas-app (#1780): static checks of Cloud Canvas, no build and no network.
#   T1 navigation: build.json::ui bottom nav is Draw | Play | Draw Enhancer | Music Enhancer, every id a section, Decl.kt names the same two ids
#   T2 Draw is drawn on the Compose canvas: no WebView anywhere in the sources
#   T3 permissions: no microphone and no storage permission in the manifest, build.json perms.needs is empty (loops are recorded as notes, files go through SAF)
#   T4 sounds are synthesized (no audio asset); the one network call is the Draw Enhancer's, so INTERNET is declared and no API key literal is in the sources
#   T5 the unit suites for stroke/undo, layers, PNG, mixer, loop timing, WAV, preset expansion, model selection, key hygiene and the request builder exist
# Each rule is then proven red by a planted mutation in a scratch copy.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {  # $1 = app dir; prints one line per failed rule
python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
ui = bj["ui"]
sec = [s["id"] for s in ui["sections"]]
if ui["bottom_nav"] != ["draw", "play", "enhance", "music"]: print("T1 bottom_nav is %r, want draw, play, enhance, music" % ui["bottom_nav"])
if any(b not in sec for b in ui["bottom_nav"]): print("T1 a bottom_nav id is not a section")
if ui["default_section"] != "draw": print("T1 default_section is not draw")
decl = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcanvas/Decl.kt")).read()
for want in ("SECTION_DRAW = \"draw\"", "SECTION_PLAY = \"play\"", "SECTION_ENHANCE = \"enhance\"", "SECTION_MUSIC = \"music\""):
    if want not in decl: print("T1 Decl.kt lacks " + want)
src = ""
for root, _, files in os.walk(os.path.join(app, "app/src/main")):
    for f in files:
        if f.endswith(".kt") or f.endswith(".java"): src += open(os.path.join(root, f)).read()
if re.search(r"\bWebView\b", re.sub(r"//.*|/\*.*?\*/", "", src, flags=re.S)): print("T2 a WebView is used")
man = open(os.path.join(app, "app/src/main/AndroidManifest.xml")).read()
man_nc = re.sub(r"<!--.*?-->", "", man, flags=re.S)
for p in ("RECORD_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"):
    if p in man_nc: print("T3 the manifest declares " + p)
if bj["perms"]["needs"] != []: print("T3 perms.needs is not empty: %r" % bj["perms"]["needs"])
if "android.permission.INTERNET" not in man_nc: print("T4 the manifest lacks INTERNET (the Enhancer calls OpenRouter)")
if re.search(r"sk-or-", src): print("T4 an API key literal is in the sources")
assets = os.path.join(app, "app/src/main/assets")
if os.path.isdir(assets) and any(f.lower().endswith((".wav", ".mp3", ".ogg", ".flac", ".mid")) for _, _, fs in os.walk(assets) for f in fs): print("T4 an audio asset is bundled")
tests = ""
for root, _, files in os.walk(os.path.join(app, "app/src/test")):
    for f in files: tests += open(os.path.join(root, f)).read()
for name in ("undoAndRedoWalkTheHistory", "reorderMovesALayerAndClamps", "pngRoundTripsThroughAnImageDecoder", "soloSilencesEveryOtherTrack",
             "windowFindsEventsAndWrapsTheLoopEnd", "wavHeaderAndSamples", "expansionFillsSizeAndQualityFromConfigs",
             "modelSelectionIsPerTaskAndPersists", "noSourceLogsOrPrintsAndNoKeyIsEmbedded", "requestCarriesModelPromptAndImageAsAMultimodalMessage", "bottomNavIsDrawPlayEnhancerMusic"):
    if "fun " + name not in tests: print("T5 missing unit test " + name)
PY
}

echo "== the real tree =="
OUT="$(check "$APP")"
[ -z "$OUT" ] && ok "T1-T5 hold on ac_cloud-canvas" || { bad "rules broken:"; echo "$OUT"; }

S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mut() {  # $1 = label, $2 = rule prefix expected, $3.. = python edit of the scratch copy
  local label="$1" want="$2"; shift 2
  rm -rf "$S/app"; mkdir -p "$S/app"; cp -r "$APP/build.json" "$APP/app" "$S/app/" 2>/dev/null; cp "$APP/build.json" "$S/app/build.json"
  ( cd "$S/app" && "$@" )
  if check "$S/app" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
mut "bottom nav reordered" T1 python3 -I -c 'import json;d=json.load(open("build.json"));d["ui"]["bottom_nav"]=["play","draw"];json.dump(d,open("build.json","w"))'
mut "microphone declared" T3 sed -i 's|<application|<uses-permission android:name="android.permission.RECORD_AUDIO"/><application|' app/src/main/AndroidManifest.xml
mut "webview used" T2 sh -c 'echo "val w: android.webkit.WebView? = null" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt; sed -i "s/^/ /" app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt'
mut "internet missing" T4 sed -i 's|android.permission.INTERNET|android.permission.VIBRATE|' app/src/main/AndroidManifest.xml
mut "key literal" T4 sh -c 'echo "val k = \"sk-or-v1-0000\"" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zk.kt' 
mut "wav test removed" T5 sed -i 's/fun wavHeaderAndSamples/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/PlayTest.kt

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
