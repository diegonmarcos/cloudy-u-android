#!/usr/bin/env bash
# test-canvas-app (#1780): static checks of Cloud Canvas, no build and no network.
#   T1 navigation: build.json::ui bottom nav is Draw | Play | Draw Enhancer | Music Enhancer, every id a section, Decl.kt names the same ids;
#      #1787 the Draw section's pages are Draw | Board (in that order) and Decl.kt names both page ids
#   T2 Draw is drawn on the Compose canvas: no WebView in any source but the Board page (ui/BoardScreen.kt, #1787), which builds its
#      web view only through libs:web-core (CloudWeb.create with WebProfile.APP_PAGE), never a bare WebView(...), and is registered in
#      1_cicd/src/data/webview-guard.json
#   T3 permissions: no storage permission in the manifest (files go through SAF); #1865 the microphone is declared (RECORD_AUDIO, Play's audio
#      tracks), build.json perms.needs is exactly [microphone], and the Play page asks for it at runtime (through PermsLink, the fleet's one asking place)
#   T4 #1787 the sounds: the only audio assets are the sampled piano (90 files: 30 roots every 3 semitones x 3 layers) and kit (27 files:
#      9 pieces x 3 layers) under assets/samples, each with a licences/curated.json::assets entry (licence, source_url, attribution), each
#      directory with its NOTICE.md, all of them under 15 MB; the one network call is the Draw Enhancer's, so INTERNET is declared and
#      no API key literal is in the sources
#   T5 the unit suites for stroke/undo, layers, PNG, mixer, loop timing, WAV, preset expansion, model selection, key hygiene, the request
#      builder, the sampler (root map, voice, polyphony) and the board store exist
#   T6 #1787 attribution: configs_pages.about.credits names Salamander Grand Piano, Alexander Holm, CC-BY 3.0, MuldjordKit, Lars Muldjord,
#      CC-BY 4.0, Quickdraw and MIT
#   T7 #1787 the Board copies: tools/vendor-board.py check passes (Quickdraw in assets/board and the kept tldraw copy in vendor/tldraw:
#      every pinned file present with its sha256, nothing unpinned), the shipped page loads no analytics and nothing from the network,
#      no tldraw file is in the APK's assets (it does not render without a licence key), and hardware acceleration stays on
#   T8 #1809 the SoundFont engine: the bank assets/soundfont/GeneralUser-GS.sf2 matches build.json's sha256 pin and has its LICENSE.txt,
#      NOTICE.md, licences/curated.json directory and asset entries (.sf2 a licence-bearing extension); assets/soundfont/soundfont.json maps
#      every kind (piano, drums, each synth wave) to a preset and every drum pad of Synth.kt to a GM key; app/build.gradle stores the bank
#      uncompressed, verifies the pinned FluidSynth/Oboe downloads by sha256 and builds app/src/main/cpp; the Ship installs the NDK and
#      CMake it pins; the About credits name FluidSynth (LGPL-2.1), Oboe (Apache-2.0) and GeneralUser GS (S. Christian Collins); the
#      Classic engine stays selectable (EngineKind.Classic); the offline-render workflow exists with its planted-fault step
#   #1808 nav: the bar is Draw | Painter | Board | Play | Enhancers (T1): Board has its own entry (its one page board) and Draw keeps
#      Draw | Board (the entry declares no page: ids are unique app-wide, and the shell opens the Board page for it); Enhancers (section id enhance) holds the pages Draw Enhancer then Music Enhancer; Decl.kt names SECTION_BOARD, PAGE_ENHANCE,
#      PAGE_MUSIC; NavShapeTest carries the field-parity tests (T5)
#   #1808 Painter is inserted right after Draw (T1: Draw | Painter | Play | Draw Enhancer | Music Enhancer); its page (ui/PainterScreen.kt)
#      is the second allowed WebView under the same rules as Board's (T2); the Painter page itself is tested by test-painter.sh
#   #1865 the sections live in their own libraries (build.json::modules libs:canvas-*: canvas-core, canvas-draw, canvas-painter,
#      canvas-play, under ab_cloud-libs-shared/libs): every source rule reads app/src/main AND each canvas library's src/main, so no rule
#      lost a file to the move (the split itself and its parity are test-canvas-libs.sh)
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
# #1865 the source roots: the app module and every canvas library build.json::modules declares (by its dir)
mains = [os.path.join(app, "app/src/main")] + [os.path.normpath(os.path.join(app, s["dir"], "src/main")) for n, s in sorted(bj["modules"].items()) if n.startswith("libs:canvas-") and isinstance(s, dict) and s.get("dir")]
def kt(rel):  # the one source file at java/com/diegonmarcos/cloudcanvas/<rel>, wherever the split put it
    for m in mains:
        f = os.path.join(m, "java/com/diegonmarcos/cloudcanvas", rel)
        if os.path.isfile(f): return f
    return os.path.join(mains[0], "java/com/diegonmarcos/cloudcanvas", rel)
def repo_rel(f): return os.path.relpath(f, os.path.dirname(os.path.abspath(app)))  # repo-relative: the app and its libs share one parent
sec = [s["id"] for s in ui["sections"]]
if ui["bottom_nav"] != ["draw", "painter", "board", "play", "enhance"]: print("T1 bottom_nav is %r, want draw, painter, board, play, enhance" % ui["bottom_nav"])
labels = {s["id"]: s.get("label") for s in ui["sections"]}
if [labels.get(b) for b in ui["bottom_nav"]] != ["Draw", "Painter", "Board", "Play", "Enhancers"]: print("T1 the bar labels are not Draw | Painter | Board | Play | Enhancers")
enhsec = [x for x in ui["sections"] if x["id"] == "enhance"]
if not enhsec or [(p["id"], p.get("label")) for p in enhsec[0].get("pages", [])] != [("enhance", "Draw Enhancer"), ("music", "Music Enhancer")]: print("T1 the Enhancers section's pages are not Draw Enhancer, Music Enhancer")
boardsec = [x for x in ui["sections"] if x["id"] == "board"]
if not boardsec or boardsec[0].get("pages", []) != [] or not boardsec[0].get("icon"): print("T1 the Board entry is missing, has no icon, or declares a page of its own (the id board is Draw's page; ids are unique app-wide)")
if "Decl.SECTION_BOARD -> BoardScreen(board" not in open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcanvas/ui/CanvasShell.kt")).read(): print("T1 the Board entry does not open the Board page")
if any(b not in sec for b in ui["bottom_nav"]): print("T1 a bottom_nav id is not a section")
if ui["default_section"] != "draw": print("T1 default_section is not draw")
decl = open(os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcanvas/Decl.kt")).read()
for want in ("SECTION_DRAW = \"draw\"", "SECTION_PLAY = \"play\"", "SECTION_ENHANCE = \"enhance\"", "PAGE_MUSIC = \"music\"", "SECTION_BOARD = \"board\"", "PAGE_ENHANCE = \"enhance\"",
             "PAGE_DRAW = \"draw\"", "PAGE_BOARD = \"board\"", "SECTION_PAINTER = \"painter\"", "PAGE_PAINTER = \"painter\""):
    if want not in decl: print("T1 Decl.kt lacks " + want)
drawsec = [x for x in ui["sections"] if x["id"] == "draw"]
if not drawsec or [p["id"] for p in drawsec[0].get("pages", [])] != ["draw", "board"]: print("T1 the Draw section's pages are not draw, board")
elif any(not p.get("label") or not p.get("icon") for p in drawsec[0]["pages"]): print("T1 a Draw page has no label or icon")
src = ""
board_src = ""
painter_src = ""
for r, _, files in (w for m in mains for w in os.walk(m)):
    for f in files:
        if f.endswith(".kt") or f.endswith(".java"):
            t = open(os.path.join(r, f)).read()
            if f == "BoardScreen.kt": board_src = t
            elif f == "PainterScreen.kt": painter_src = t
            else: src += t
nc = lambda t: re.sub(r"//.*|/\*.*?\*/", "", t, flags=re.S)
if re.search(r"\bWebView\b", nc(src)): print("T2 a WebView is used outside the Board and Painter pages")
if not board_src: print("T2 the Board page (ui/BoardScreen.kt) is missing")
else:
    if "CloudWeb.create(c, WebProfile.APP_PAGE)" not in board_src: print("T2 the Board web view is not built by CloudWeb.create(.., WebProfile.APP_PAGE)")
    if re.search(r"(?<![\w.@<])WebView\(\s*(?!\))", nc(board_src)): print("T2 the Board page constructs a bare WebView")
    if "if (isMainFrame)" not in board_src or 'setOf("https://${CloudWeb.ASSET_HOST}")' not in board_src: print("T2 the board bridge is not limited to the asset origin and the main frame")
wg = open(os.path.join(root, "1_cicd/src/data/webview-guard.json")).read()
if repo_rel(kt("ui/BoardScreen.kt")) not in wg: print("T2 BoardScreen.kt is not registered in webview-guard.json")
if not painter_src: print("T2 the Painter page (ui/PainterScreen.kt) is missing")
else:
    if "CloudWeb.create(c, WebProfile.APP_PAGE)" not in painter_src: print("T2 the Painter web view is not built by CloudWeb.create(.., WebProfile.APP_PAGE)")
    if re.search(r"(?<![\w.@<])WebView\(\s*(?!\))", nc(painter_src)): print("T2 the Painter page constructs a bare WebView")
    if "if (isMainFrame)" not in painter_src or 'setOf("https://${CloudWeb.ASSET_HOST}")' not in painter_src: print("T2 the painter bridge is not limited to the asset origin and the main frame")
if repo_rel(kt("ui/PainterScreen.kt")) not in wg: print("T2 PainterScreen.kt is not registered in webview-guard.json")
man = open(os.path.join(app, "app/src/main/AndroidManifest.xml")).read()
man_nc = re.sub(r"<!--.*?-->", "", man, flags=re.S)
for p in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"):
    if p in man_nc: print("T3 the manifest declares " + p)
if "android.permission.RECORD_AUDIO" not in man_nc: print("T3 the manifest does not declare RECORD_AUDIO (Play's audio tracks, #1865)")
if bj["perms"]["needs"] != ["microphone"]: print("T3 perms.needs is not [microphone]: %r" % bj["perms"]["needs"])
if "PermsLink.open(" not in src or "Manifest.permission.RECORD_AUDIO" not in src: print("T3 the microphone is not requested at runtime")
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
if "ac_cloud-canvas/vendor/tldraw" not in cur.get("directories", {}): print("T4 vendor/tldraw is not a licences/curated.json directory")
if ".ogg" not in cur.get("asset_extensions", []): print("T4 .ogg is not a licence-bearing asset extension")
tests = ""
for root, _, files in os.walk(os.path.join(app, "app/src/test")):
    for f in files: tests += open(os.path.join(root, f)).read()
for name in ("undoAndRedoWalkTheHistory", "reorderMovesALayerAndClamps", "pngRoundTripsThroughAnImageDecoder", "soloSilencesEveryOtherTrack",
             "windowFindsEventsAndWrapsTheLoopEnd", "wavHeaderAndSamples", "expansionFillsSizeAndQualityFromConfigs",
             "modelSelectionIsPerTaskAndPersists", "noSourceLogsOrPrintsAndNoKeyIsEmbedded", "requestCarriesModelPromptAndImageAsAMultimodalMessage", "bottomNavIsDrawPlayEnhancerMusic",
             "everyNoteMapsToTheNearestRecordedRoot", "pitchRatioShiftsBySemitones", "sampleVoicePlaysTheSampleAtTheRatio", "polyphonyIsCappedByStealingTheOldest",
             "pianoAndDrumsUseSamplesWhenTheBankHoldsThem", "theAssetSetOnDiskIsComplete", "drawSectionHasDrawAndBoardPages",
             "createRenameDeleteKeepsAtLeastOneBoard", "everyInstrumentResolvesToAPresetOfTheBank", "theBankAndTheDeclarationMatchTheirPins",
             "eachTrackHasItsOwnOutputGroupAndEachKindItsOwnChannel", "liveNotesGoToTheirTracksChannelAndAScheduledNoteStartsAtItsExactFrame",
             "aMutedOrUnsoloedSoundFontTrackIsSilentAndSendsNoReverb", "theMasterLimiterHoldsTheCeilingAndLeavesQuietSoundAlone", "aStolenVoiceFadesOutInsteadOfBeingCut", "bottomNavIsDrawPainterBoardPlayEnhancers", "everyEarlierPageIsStillDeclaredWithItsFields", "everyEarlierDeepLinkStillReachesItsPage", "writeIsAtomicAndRefusesForeignJson", "theIndexSurvivesAReopen"):
    if "fun " + name not in tests: print("T5 missing unit test " + name)
cred = bj.get("configs_pages", {}).get("about", {}).get("credits", "")
for w in ("Salamander Grand Piano", "Alexander Holm", "CC-BY 3.0", "MuldjordKit", "Lars Muldjord", "CC-BY 4.0", "Quickdraw", "MIT"):
    if w not in cred: print("T6 configs_pages.about.credits does not name " + w)
pin = subprocess.run([sys.executable, "-I", os.path.join(app, "tools/vendor-board.py"), "check", app], capture_output=True, text=True)
if pin.returncode != 0: print("T7 a pinned board copy does not match its PIN.json: " + pin.stdout.strip().replace("\n", "; ")[:300])
idx = os.path.join(assets, "board/quickdraw/index.html")
page = open(idx).read() if os.path.isfile(idx) else ""
if not page or re.search(r"matomo|umami|analytics|https?://", page, re.I): print("T7 the board page is missing, loads analytics or names a network address")
if any("tldraw" in f.lower() for _, _, fs in os.walk(assets) for f in fs) or os.path.isdir(os.path.join(assets, "board/tldraw")): print("T7 a tldraw file is in the APK's assets")
if "/board/quickdraw/index.html" not in board_src: print("T7 BoardScreen does not load the vendored Quickdraw page")
# T8 #1809 the SoundFont engine
import hashlib
sfb = bj.get("audio", {}).get("soundfont", {})
bank = os.path.join(assets, sfb.get("bank", {}).get("asset", "soundfont/GeneralUser-GS.sf2"))
if not os.path.isfile(bank): print("T8 the SoundFont bank is missing")
else:
    h = hashlib.sha256()
    with open(bank, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""): h.update(chunk)
    if h.hexdigest() != sfb.get("bank", {}).get("sha256"): print("T8 the bank's sha256 is not build.json's pin")
for f in ("LICENSE.txt", "NOTICE.md"):
    if not os.path.isfile(os.path.join(assets, "soundfont", f)): print("T8 assets/soundfont has no " + f)
if "ac_cloud-canvas/app/src/main/assets/soundfont" not in cur.get("directories", {}) or "ac_cloud-canvas/app/src/main/assets/soundfont/GeneralUser-GS.sf2" not in cur.get("assets", {}) or ".sf2" not in cur.get("asset_extensions", []):
    print("T8 the bank has no licences/curated.json directory/asset entry or .sf2 is not licence-bearing")
for d in ("fluidsynth", "oboe"):
    if "ac_cloud-canvas/app/src/main/cpp/third_party/" + d not in cur.get("directories", {}): print("T8 cpp/third_party/%s is not a licences/curated.json directory" % d)
cfgp = os.path.join(assets, sfb.get("config_asset", "soundfont/soundfont.json"))
cfg = json.load(open(cfgp)) if os.path.isfile(cfgp) else {}
kinds = ["piano", "drums", "synth_sine", "synth_saw", "synth_square", "synth_triangle"]
if cfg.get("kinds") != kinds or any(k not in cfg.get("presets", {}) or not cfg["presets"][k].get("name") for k in kinds): print("T8 soundfont.json does not map every kind to a named preset")
synth_kt = open(kt("audio/Synth.kt")).read()
pads = re.findall(r"(\w+)\(\"[^\"]+\"\)", synth_kt[synth_kt.index("enum class Drum"):synth_kt.index("}", synth_kt.index("enum class Drum"))])
if not pads or any(not (35 <= cfg.get("drum_notes", {}).get(pd, 0) <= 81) for pd in pads): print("T8 a drum pad has no General MIDI key in soundfont.json")
gr = open(os.path.join(app, "app/build.gradle")).read()
if not re.search(r"noCompress[^\n]*'sf2'", gr): print("T8 the bank is not stored uncompressed (noCompress 'sf2')")
if "sha256Of(" not in gr or "sfDecl.android_zip.sha256" not in gr or "sfDecl.oboe_headers_aar.sha256" not in gr: print("T8 the FluidSynth / Oboe downloads are not verified against their sha256 pins")
if "src/main/cpp/CMakeLists.txt" not in gr: print("T8 app/build.gradle does not build app/src/main/cpp")
repo = sys.argv[2]   # (the T5 loop above reuses the name root)
ship = os.path.join(repo, "1_cicd/src/cicd/ship-cloud-canvas.yml")
if not os.path.isfile(ship) or ("ndk;29.0.14033849" not in open(ship).read() or "cmake;3.31.6" not in open(ship).read()): print("T8 the Ship does not install the pinned NDK and CMake")
for w in ("FluidSynth", "LGPL-2.1", "Oboe", "Apache-2.0", "GeneralUser GS", "S. Christian Collins"):
    if w not in cred: print("T8 configs_pages.about.credits does not name " + w)
if "Classic(\"classic\"" not in src: print("T8 the Classic engine is not selectable (EngineKind.Classic)")
rw = os.path.join(repo, "1_cicd/src/cicd/canvas-soundfont-render.yml")
if not os.path.isfile(rw) or "--plant" not in open(rw).read(): print("T8 the SoundFont offline-render workflow or its planted-fault step is missing")
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
  rm -rf "$S/app" "$S/root"; mkdir -p "$S/app" "$S/root/licenses" "$S/root/1_cicd/src/data"; cp -r "$APP/build.json" "$APP/app" "$APP/tools" "$APP/vendor" "$S/app/" 2>/dev/null; cp "$APP/build.json" "$S/app/build.json"
  rm -rf "$S/ab_cloud-libs-shared"; mkdir -p "$S/ab_cloud-libs-shared/libs"; cp -r "$ROOT"/ab_cloud-libs-shared/libs/canvas-* "$S/ab_cloud-libs-shared/libs/"
  cp "$ROOT/licenses/curated.json" "$S/root/licenses/"; cp "$ROOT/1_cicd/src/data/webview-guard.json" "$S/root/1_cicd/src/data/"
  mkdir -p "$S/root/1_cicd/src/cicd"; cp "$ROOT/1_cicd/src/cicd/ship-cloud-canvas.yml" "$ROOT/1_cicd/src/cicd/canvas-soundfont-render.yml" "$S/root/1_cicd/src/cicd/"
  ( cd "$S/app" && "$@" )
  if check "$S/app" "$S/root" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
mut "bottom nav reordered" T1 python3 -I -c 'import json;d=json.load(open("build.json"));d["ui"]["bottom_nav"]=["play","draw"];json.dump(d,open("build.json","w"))'
mut "storage declared" T3 sed -i 's|<application|<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/><application|' app/src/main/AndroidManifest.xml
mut "microphone undeclared" T3 sed -i 's|android.permission.RECORD_AUDIO|android.permission.VIBRATE|' app/src/main/AndroidManifest.xml
mut "microphone not in perms" T3 python3 -I -c 'import json;d=json.load(open("build.json"));d["perms"]["needs"]=[];json.dump(d,open("build.json","w"))'
mut "microphone never asked" T3 sed -i 's/PermsLink.open(/PermsLinkGone.open(/' ../ab_cloud-libs-shared/libs/canvas-play/src/main/java/com/diegonmarcos/cloudcanvas/ui/PlayScreen.kt
mut "webview used" T2 sh -c 'echo "val w: android.webkit.WebView? = null" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt; sed -i "s/^/ /" app/src/main/java/com/diegonmarcos/cloudcanvas/Zz.kt'
mut "internet missing" T4 sed -i 's|android.permission.INTERNET|android.permission.VIBRATE|' app/src/main/AndroidManifest.xml
mut "key literal" T4 sh -c 'echo "val k = \"sk-or-v1-0000\"" > app/src/main/java/com/diegonmarcos/cloudcanvas/Zk.kt' 
mut "wav test removed" T5 sed -i 's/fun wavHeaderAndSamples/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/PlayTest.kt
# #1787
mut "board page dropped" T1 python3 -I -c 'import json;d=json.load(open("build.json"));d["ui"]["sections"][0]["pages"]=d["ui"]["sections"][0]["pages"][:1];json.dump(d,open("build.json","w"))'
mut "board built as a bare WebView" T2 sed -i 's/CloudWeb.create(c, WebProfile.APP_PAGE)/WebView(c)/' ../ab_cloud-libs-shared/libs/canvas-draw/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt
mut "bridge open to every frame" T2 sed -i 's/if (isMainFrame) //' ../ab_cloud-libs-shared/libs/canvas-draw/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt
# #1808
mut "painter moved after play" T1 python3 -I -c 'import json;d=json.load(open("build.json"));b=d["ui"]["bottom_nav"];b.remove("painter");b.insert(2,"painter");json.dump(d,open("build.json","w"))'
mut "painter built as a bare WebView" T2 sed -i 's/CloudWeb.create(c, WebProfile.APP_PAGE)/WebView(c)/' ../ab_cloud-libs-shared/libs/canvas-painter/src/main/java/com/diegonmarcos/cloudcanvas/ui/PainterScreen.kt
mut "painter bridge open to every frame" T2 sed -i 's/if (isMainFrame) //' ../ab_cloud-libs-shared/libs/canvas-painter/src/main/java/com/diegonmarcos/cloudcanvas/ui/PainterScreen.kt
mut "painter not registered" T2 sed -i 's#ab_cloud-libs-shared/libs/canvas-painter/src/main/java/com/diegonmarcos/cloudcanvas/ui/PainterScreen.kt#ac_cloud-canvas/y.kt#' ../root/1_cicd/src/data/webview-guard.json
# #1808 nav
mut "board entry dropped" T1 python3 -I -c 'import json;d=json.load(open("build.json"));b=d["ui"]["bottom_nav"];b.remove("board");json.dump(d,open("build.json","w"))'
mut "board entry shows draw" T1 sed -i 's/Decl.SECTION_BOARD -> BoardScreen(board/Decl.SECTION_BOARD -> DrawScreen(draw/' app/src/main/java/com/diegonmarcos/cloudcanvas/ui/CanvasShell.kt
mut "music enhancer page dropped" T1 python3 -I -c 'import json;d=json.load(open("build.json"));s=[x for x in d["ui"]["sections"] if x["id"]=="enhance"][0];s["pages"]=s["pages"][:1];json.dump(d,open("build.json","w"))'
mut "enhancers relabelled" T1 python3 -I -c 'import json;d=json.load(open("build.json"));s=[x for x in d["ui"]["sections"] if x["id"]=="enhance"][0];s["label"]="Draw Enhancer";json.dump(d,open("build.json","w"))'
mut "parity test removed" T5 sed -i 's/fun everyEarlierPageIsStillDeclaredWithItsFields/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/NavShapeTest.kt
# #1809
mut "bank pin changed" T8 python3 -I -c 'import json;d=json.load(open("build.json"));d["audio"]["soundfont"]["bank"]["sha256"]="0"*64;json.dump(d,open("build.json","w"))'
mut "a kind lost its preset" T8 python3 -I -c 'import json;p="app/src/main/assets/soundfont/soundfont.json";d=json.load(open(p));del d["presets"]["synth_saw"];json.dump(d,open(p,"w"))'
mut "a pad lost its GM key" T8 python3 -I -c 'import json;p="app/src/main/assets/soundfont/soundfont.json";d=json.load(open(p));del d["drum_notes"]["Ride"];json.dump(d,open(p,"w"))'
mut "bank compressed" T8 sed -i "s/noCompress 'ogg', 'sf2'/noCompress 'ogg'/" app/build.gradle
mut "download unverified" T8 sed -i 's/sfDecl.android_zip.sha256/sfDecl.android_zip.url/g' app/build.gradle
mut "engine credit dropped" T8 python3 -I -c 'import json;d=json.load(open("build.json"));c=d["configs_pages"]["about"];c["credits"]=c["credits"].replace("FluidSynth","Synth");json.dump(d,open("build.json","w"))'
mut "classic engine removed" T8 sed -i 's/Classic("classic", "Classic")/Other("other", "Other")/' ../ab_cloud-libs-shared/libs/canvas-play/src/main/java/com/diegonmarcos/cloudcanvas/audio/SoundFont.kt
mut "ship without the ndk" T8 sed -i 's/ ndk;29.0.14033849//' ../root/1_cicd/src/cicd/ship-cloud-canvas.yml
mut "soundfont test removed" T5 sed -i 's/fun everyInstrumentResolvesToAPresetOfTheBank/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/SoundFontTest.kt
mut "board not registered" T2 sed -i 's#ab_cloud-libs-shared/libs/canvas-draw/src/main/java/com/diegonmarcos/cloudcanvas/ui/BoardScreen.kt#ac_cloud-canvas/x.kt#' ../root/1_cicd/src/data/webview-guard.json
mut "unlicensed sound" T4 sh -c 'printf x > app/src/main/assets/samples/drums/clap_v1.ogg'
mut "sample missing" T4 rm app/src/main/assets/samples/piano/060_v2.ogg
mut "licence entry dropped" T4 python3 -I -c 'import json;p="../root/licenses/curated.json";d=json.load(open(p));del d["assets"]["ac_cloud-canvas/app/src/main/assets/samples/piano/021_v1.ogg"];json.dump(d,open(p,"w"))'
mut "notice dropped" T4 rm app/src/main/assets/samples/drums/NOTICE.md
mut "sampler test removed" T5 sed -i 's/fun polyphonyIsCappedByStealingTheOldest/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/SampleTest.kt
mut "board test removed" T5 sed -i 's/fun writeIsAtomicAndRefusesForeignJson/fun x/' app/src/test/java/com/diegonmarcos/cloudcanvas/BoardTest.kt
mut "attribution dropped" T6 python3 -I -c 'import json;d=json.load(open("build.json"));d["configs_pages"]["about"]["credits"]="";json.dump(d,open("build.json","w"))'
mut "vendored engine edited" T7 sh -c 'echo "//x" >> app/src/main/assets/board/quickdraw/src/editor.js'
mut "kept tldraw copy edited" T7 sh -c 'for f in vendor/tldraw/tldraw/assets/*.js; do echo "//x" >> "$f"; done'
mut "tldraw shipped again" T7 sh -c 'mkdir -p app/src/main/assets/board/tldraw && cp vendor/tldraw/tldraw/index.html app/src/main/assets/board/tldraw/'
mut "page reaches the network" T7 sed -i 's#</head>#<script src="https://cdn.example/x.js"></script></head>#' app/src/main/assets/board/quickdraw/index.html
mut "acceleration off" T7 sed -i '0,/android:hardwareAccelerated="true"/s//android:hardwareAccelerated="false"/' app/src/main/AndroidManifest.xml

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
