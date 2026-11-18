#!/usr/bin/env bash
# test-canvas-app (#1780): static checks of Cloud Canvas, no build and no network.
#   T1 navigation: build.json::ui bottom nav is Draw | Play | Draw Enhancer | Music Enhancer, every id a section, Decl.kt names the same ids;
#      #1787 the Draw section's pages are Draw | Board (in that order) and Decl.kt names both page ids
#   T2 Draw is drawn on the Compose canvas: no WebView in any source but the Board page (ui/BoardScreen.kt, #1787), which builds its
#      web view only through libs:web-core (CloudWeb.create with WebProfile.APP_PAGE), never a bare WebView(...), and is registered in
#      1_cicd/src/data/webview-guard.json
#   T3 permissions: no microphone and no storage permission in the manifest, build.json perms.needs is empty (loops are recorded as notes, files go through SAF)
#   T4 #1787 the sounds: the only audio assets are the sampled piano (90 files: 30 roots every 3 semitones x 3 layers) and kit (27 files:
#      9 pieces x 3 layers) under assets/samples, each with a licences/curated.json::assets entry (licence, source_url, attribution), each
#      directory with its NOTICE.md, all of them under 15 MB; the one network call is the Draw Enhancer's, so INTERNET is declared and
#      no API key literal is in the sources
#   T5 the unit suites for stroke/undo, layers, PNG, mixer, loop timing, WAV, preset expansion, model selection, key hygiene, the request
#      builder, the sampler (root map, voice, polyphony) and the board store exist
#   T6 #1787 attribution: configs_pages.about.credits names Salamander Grand Piano, Alexander Holm, CC-BY 3.0, MuldjordKit, Lars Muldjord,
#      CC-BY 4.0 and tldraw
#   T7 #1787 the Board copy: tools/vendor-tldraw.py check passes (every pinned file present with its sha256, nothing unpinned), the page
#      loads no analytics, and the manifest keeps hardware acceleration on
# Each rule is then proven red by a planted mutation in a scratch copy.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {  # $1 = app dir, $2 = repo root (licences, webview registry); prints one line per failed rule
python3 -I - "$1" "$2" <<'PY'
import json, os, re, subprocess, sys
app, root = sys.argv[1], sys.argv[2]
bj = json.load(open(os.path.join(app, "build.json")))
ui = bj["ui"]
sec = [s["id"] for s in ui["sections"]]
if ui["bottom_nav"] != ["draw", "play", "enhance", "music"]: print("T1 bottom_nav is %r, want draw, play, enhance, music" % ui["bottom_nav"])
if any(b not in sec for b in ui["bottom_nav"]): print("T1 a bottom_nav id is not a section")
if ui["default_section"] != "draw": print("T1 default_section is not draw")
decl = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcanvas/Decl.kt")).read()
for want in ("SECTION_DRAW = \"draw\"", "SECTION_PLAY = \"play\"", "SECTION_ENHANCE = \"enhance\"", "SECTION_MUSIC = \"music\"",
             "PAGE_DRAW = \"draw\"", "PAGE_BOARD = \"board\""):
    if want not in decl: print("T1 Decl.kt lacks " + want)
drawsec = [x for x in ui["sections"] if x["id"] == "draw"]
if not drawsec or [p["id"] for p in drawsec[0].get("pages", [])] != ["draw", "board"]: print("T1 the Draw section's pages are not draw, board")
elif any(not p.get("label") or not p.get("icon") for p in drawsec[0]["pages"]): print("T1 a Draw page has no label or icon")
src = ""
board_src = ""
for r, _, files in os.walk(os.path.join(app, "app/src/main")):
    for f in files:
        if f.endswith(".kt") or f.endswith(".java"):
            t = open(os.path.join(r, f)).read()
            if f == "BoardScreen.kt": board_src = t
            else: src += t
nc = lambda t: re.sub(r"//.*|/\*.*?\*/", "", t, flags=re.S)
if re.search(r"\bWebView\b", nc(src)): print("T2 a WebView is used outside the Board page")
if not board_src: print("T2 the Board page (ui/BoardScreen.kt) is missing")
else:
    if "CloudWeb.create(c, WebProfile.APP_PAGE)" not in board_src: print("T2 the Board web view is not built by CloudWeb.create(.., WebProfile.APP_PAGE)")
    if re.search(r"(?<![\w.@<])WebView\(\s*(?!\))", nc(board_src)): print("T2 the Board page constructs a bare WebView")
    if "if (isMainFrame)" not in board_src or 'setOf("https://${CloudWeb.ASSET_HOST}")' not in board_src: print("T2 the board bridge is not limited to the asset origin and the main frame")
wg = open(os.path.join(root, "1_cicd/src/data/webview-guard.json")).read()
if "ac_cloud-canvas/app/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt" not in wg: print("T2 BoardScreen.kt is not registered in webview-guard.json")
man = open(os.path.join(app, "app/src/main/AndroidManifest.xml")).read()
man_nc = re.sub(r"<!--.*?-->", "", man, flags=re.S)
for p in ("RECORD_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"):
    if p in man_nc: print("T3 the manifest declares " + p)
if bj["perms"]["needs"] != []: print("T3 perms.needs is not empty: %r" % bj["perms"]["needs"])
if "android.permission.INTERNET" not in man_nc: print("T4 the manifest lacks INTERNET (the Enhancer calls OpenRouter)")
if re.search(r"sk-or-", src): print("T4 an API key literal is in the sources")
assets = os.path.join(app, "app/src/main/assets")
audio = sorted(os.path.relpath(os.path.join(r, f), assets) for r, _, fs in os.walk(assets) for f in fs if f.lower().endswith((".wav", ".mp3", ".ogg", ".flac", ".mid", ".opus", ".m4a")))
want_audio = sorted(["samples/piano/%03d_v%d.ogg" % (n, l) for n in range(21, 109, 3) for l in (1, 2, 3)] +
                    ["samples/drums/%s_v%d.ogg" % (d, l) for d in ("kick", "snare", "hat_closed", "hat_open", "tom_low", "tom_mid", "tom_high", "crash", "ride") for l in (1, 2, 3)])
if audio != want_audio:
    print("T4 the audio assets are not exactly the sampled set: extra %s missing %s" % (sorted(set(audio) - set(want_audio))[:3], sorted(set(want_audio) - set(audio))[:3]))
size = sum(os.path.getsize(os.path.join(assets, a)) for a in audio)
if size > 15 * 1024 * 1024: print("T4 the samples weigh %d bytes, over 15 MB" % size)
cur = json.load(open(os.path.join(root, "licenses/curated.json")))
for a in audio:
    e = cur.get("assets", {}).get("ac_cloud-canvas/app/src/main/assets/" + a)
    if not e or not e.get("licence") or not e.get("source_url", "").startswith("https://") or not e.get("attribution"):
        print("T4 %s has no licences/curated.json::assets entry with licence, source_url and attribution" % a); break
for d in ("samples/piano", "samples/drums", "board"):
    if not os.path.isfile(os.path.join(assets, d, "NOTICE.md")): print("T4 assets/%s has no NOTICE.md" % d)
    if "ac_cloud-canvas/app/src/main/assets/" + d not in cur.get("directories", {}): print("T4 assets/%s is not a licences/curated.json directory" % d)
if ".ogg" not in cur.get("asset_extensions", []): print("T4 .ogg is not a licence-bearing asset extension")
tests = ""
for root, _, files in os.walk(os.path.join(app, "app/src/test")):
    for f in files: tests += open(os.path.join(root, f)).read()
for name in ("undoAndRedoWalkTheHistory", "reorderMovesALayerAndClamps", "pngRoundTripsThroughAnImageDecoder", "soloSilencesEveryOtherTrack",
             "windowFindsEventsAndWrapsTheLoopEnd", "wavHeaderAndSamples", "expansionFillsSizeAndQualityFromConfigs",
             "modelSelectionIsPerTaskAndPersists", "noSourceLogsOrPrintsAndNoKeyIsEmbedded", "requestCarriesModelPromptAndImageAsAMultimodalMessage", "bottomNavIsDrawPlayEnhancerMusic",
             "everyNoteMapsToTheNearestRecordedRoot", "pitchRatioShiftsBySemitones", "sampleVoicePlaysTheSampleAtTheRatio", "polyphonyIsCappedByStealingTheOldest",
             "pianoAndDrumsUseSamplesWhenTheBankHoldsThem", "theAssetSetOnDiskIsComplete", "drawSectionHasDrawAndBoardPages",
             "createRenameDeleteKeepsAtLeastOneBoard", "writeIsAtomicAndRefusesForeignJson", "theIndexSurvivesAReopen"):
    if "fun " + name not in tests: print("T5 missing unit test " + name)
cred = bj.get("configs_pages", {}).get("about", {}).get("credits", "")
for w in ("Salamander Grand Piano", "Alexander Holm", "CC-BY 3.0", "MuldjordKit", "Lars Muldjord", "CC-BY 4.0", "tldraw"):
    if w not in cred: print("T6 configs_pages.about.credits does not name " + w)
pin = subprocess.run([sys.executable, "-I", os.path.join(app, "tools/vendor-tldraw.py"), "check", app], capture_output=True, text=True)
if pin.returncode != 0: print("T7 the pinned tldraw copy does not match PIN.json: " + pin.stdout.strip().replace("\n", "; ")[:300])
idx = os.path.join(assets, "board/tldraw/index.html")
if not os.path.isfile(idx) or re.search(r"matomo|umami|analytics", open(idx).read(), re.I): print("T7 the board page loads analytics (or is missing)")
if 'android:hardwareAccelerated="false"' in man_nc or man_nc.count('android:hardwareAccelerated="true"') < 2: print("T7 hardware acceleration is not declared on for the application and the activity")
PY
}

echo "== the real tree =="
ROOT="$(cd "$APP/.." && pwd)"
OUT="$(check "$APP" "$ROOT")"
[ -z "$OUT" ] && ok "T1-T7 hold on ac_cloud-canvas" || { bad "rules broken:"; echo "$OUT"; }

S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mut() {  # $1 = label, $2 = rule prefix expected, $3.. = python edit of the scratch copy
  local label="$1" want="$2"; shift 2
  rm -rf "$S/app" "$S/root"; mkdir -p "$S/app" "$S/root/licenses" "$S/root/1_cicd/src/data"; cp -r "$APP/build.json" "$APP/app" "$APP/tools" "$S/app/" 2>/dev/null; cp "$APP/build.json" "$S/app/build.json"
  cp "$ROOT/licenses/curated.json" "$S/root/licenses/"; cp "$ROOT/1_cicd/src/data/webview-guard.json" "$S/root/1_cicd/src/data/"
  ( cd "$S/app" && "$@" )
  if check "$S/app" "$S/root" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
mut "bottom nav reordered" T1 python3 -I -c 'import json;d=json.load(open("build.json"));d["ui"]["bottom_nav"]=["play","draw"];json.dump(d,open("build.json","w"))'
mut "microphone declared" T3 sed -i 's|<application|<uses-permission android:name="android.permission.RECORD_AUDIO"/><application|' app/src/main/AndroidManifest.xml
mut "webview used" T2 sh -c 'echo "val w: android.webkit.WebView? = null" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt; sed -i "s/^/ /" app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt'
mut "internet missing" T4 sed -i 's|android.permission.INTERNET|android.permission.VIBRATE|' app/src/main/AndroidManifest.xml
mut "key literal" T4 sh -c 'echo "val k = \"sk-or-v1-0000\"" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zk.kt' 
mut "wav test removed" T5 sed -i 's/fun wavHeaderAndSamples/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/PlayTest.kt
# #1787
mut "board page dropped" T1 python3 -I -c 'import json;d=json.load(open("build.json"));d["ui"]["sections"][0]["pages"]=d["ui"]["sections"][0]["pages"][:1];json.dump(d,open("build.json","w"))'
mut "board built as a bare WebView" T2 sed -i 's/CloudWeb.create(c, WebProfile.APP_PAGE)/WebView(c)/' app/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt
mut "bridge open to every frame" T2 sed -i 's/if (isMainFrame) //' app/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt
mut "board not registered" T2 sed -i 's#ac_cloud-canvas/app/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt#ac_cloud-canvas/x.kt#' ../root/1_cicd/src/data/webview-guard.json
mut "unlicensed sound" T4 sh -c 'printf x > app/src/main/assets/samples/drums/clap_v1.ogg'
mut "sample missing" T4 rm app/src/main/assets/samples/piano/060_v2.ogg
mut "licence entry dropped" T4 python3 -I -c 'import json;p="../root/licenses/curated.json";d=json.load(open(p));del d["assets"]["ac_cloud-canvas/app/src/main/assets/samples/piano/021_v1.ogg"];json.dump(d,open(p,"w"))'
mut "notice dropped" T4 rm app/src/main/assets/samples/drums/NOTICE.md
mut "sampler test removed" T5 sed -i 's/fun polyphonyIsCappedByStealingTheOldest/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/SampleTest.kt
mut "board test removed" T5 sed -i 's/fun writeIsAtomicAndRefusesForeignJson/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/BoardTest.kt
mut "attribution dropped" T6 python3 -I -c 'import json;d=json.load(open("build.json"));d["configs_pages"]["about"]["credits"]="";json.dump(d,open("build.json","w"))'
mut "vendored bundle edited" T7 sh -c 'for f in app/src/main/assets/board/tldraw/assets/*.js; do echo "//x" >> "$f"; done'
mut "analytics back" T7 sed -i 's#</head>#<script src="https://analytics.example/umami/script.js"></script></head>#' app/src/main/assets/board/tldraw/index.html
mut "acceleration off" T7 sed -i '0,/android:hardwareAccelerated="true"/s//android:hardwareAccelerated="false"/' app/src/main/AndroidManifest.xml

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
