#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ task #1805 — Objects as a scene: objects + Text + live OCR, selectable ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the JVM suites run the maths: SceneOverlayMappingTest, TextSelectionTest,
# SceneFramesTest here, SceneTest in libs:ml-l-image-mlkit; the emulator run is test/emulator-camera-scene.sh):
#   S1 the scene goes through the fleet's ML image engine: contract 4 (`scene`, `scene_bench`) declared by the engine's
#      manifest, answered by its service, asked for by engine-client.json and called by the camera through
#      ImageContentScanner; an older engine keeps the #1533 detect path.
#   S2 the libraries: MediaPipe Tasks Object Detector (EfficientDet-Lite0) and the LiteRT Interpreter (YOLO11n) in the
#      engine, ML Kit Text Recognition v2 with the Latin, Chinese, Devanagari, Japanese and Korean recognizers (through
#      libs:ml-l-image-ocr); both detectors pinned in data/models.json (https, sha256, bytes, licence) and selectable
#      (recognition.json scene.detectors, the Detector button).
#   S3 model delivery follows the runtime-data guard: the detectors are staged by the one fetchModels into the one
#      asset site the baseline already counts; the engine's gradle gains no asset site and the camera bakes no JSON.
#   S4 Text is a class: text regions come back as objects of class "text", labelled scene.text_label ("Text"), and are
#      framed by the overlay.
#   S5 live OCR: on its own executor, only while text is in view, at the declared cadence (SceneThrottle.startOcr),
#      the layout cached per frame (OcrCache) and reused by a freeze; an opened image is read once.
#   S6 the stream: one ImageAnalysis, STRATEGY_KEEP_ONLY_LATEST, a background executor, frames sized to live_side.
#   S7 the overlay is Compose (AbstractComposeView) and maps both ways through FrameMapping (boxToView for drawing,
#      viewToImage for taps), mirrored for the front camera, FIT for an opened image.
#   S8 selection: a tap selects and copies (ClipboardManager), the handles extend (TextSelection.drag), Copy all and
#      Share exist; the snapshot JSON keeps the detections and adds the text.
#   S9 on device: no network anywhere in the scene code.
#   S10 nothing removed (rule 16): the #1533 controls, overlay captions and snapshot are all still there.
# Each check is then run against planted mutations of the real sources; a mutation that leaves every check green
# fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
J = "app/src/main/java/cld/camera"
L = "../ab_cloud-libs-shared/libs"
ENG = L + "/ml-l-image-mlkit"
EJ = ENG + "/src/main/java/com/diegonmarcos/superapp/image"
VJ = L + "/ml-l-vision-mlkit/src/main/java/com/diegonmarcos/superapp/vision"
FILES = {
    "ctl": J + "/objects/ObjectsController.kt", "frames": J + "/objects/SceneFrames.kt", "snap": J + "/objects/ObjectsSnapshot.kt",
    "scanner": J + "/analyzer/ImageContentScanner.kt", "main": J + "/ui/activities/MainActivity.kt",
    "layout": "app/src/main/res/layout/activity_main.xml",
    "overlay": VJ + "/SceneOverlayView.kt", "text": VJ + "/TextLayout.kt", "mapping": VJ + "/FrameMapping.kt",
    "eman": ENG + "/src/main/AndroidManifest.xml", "esvc": EJ + "/ImageScanBackendService.kt", "egradle": ENG + "/build.gradle",
    "escene": EJ + "/Scene.kt", "eon": EJ + "/SceneOnDevice.kt", "emath": EJ + "/SceneMath.kt", "models": ENG + "/data/models.json",
    "ocr": L + "/ml-l-image-ocr/src/main/java/com/diegonmarcos/superapp/mlocr/MlKitOcr.kt", "ocrgradle": L + "/ml-l-image-ocr/build.gradle",
    "client": L + "/ml-l-image/engine-client.json", "recog": L + "/ml-l-image/recognition.json",
    "cscene": L + "/ml-l-image/src/main/java/com/diegonmarcos/superapp/image/Scene.kt",
    "engine": L + "/ml-l-image/src/main/java/com/diegonmarcos/superapp/image/ImageScanEngine.kt",
    "baseline": "../1_cicd/src/data/runtime-data-baseline.json",
}


def load():
    return {k: open(os.path.join(APP, v), encoding="utf-8").read() for k, v in FILES.items()}


def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$|(?<=\s)//[^\n]*$", "", t)


ASSET_INJECT = re.compile(r"assets\.srcDirs?\b|\bassets\s*\{|\binto\s*\(?[^)\n]*assets|\bassetsDir\b|\bmlAssetsDir\b")


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    c = {k: code(v) for k, v in f.items() if k not in ("models", "client", "recog", "baseline", "layout", "eman")}
    try:
        models = json.loads(f["models"])["models"]; client = json.loads(f["client"]); recog = json.loads(f["recog"]); base = json.loads(f["baseline"])
    except ValueError as e:
        return ["a declaration is unreadable: %s" % e]
    m = re.search(r'engine\.CONTRACT"\s*android:value="(\d+)"', f["eman"])
    contract = int(m.group(1)) if m else 0
    mc = client["engines"]["image"].get("method_contracts", {})
    need("S1 the engine declares contract 4 or above", contract >= 4)
    need("S1 engine-client.json asks scene at contract 4", mc.get("scene") == 4 and mc.get("scene", 99) <= contract)
    need("S1 the engine service answers scene and scene_bench", re.search(r"arrayOf\([^)]*\bSCENE\b[^)]*\bSCENE_BENCH\b", c["esvc"]) is not None and "scene.json(image, request)" in c["esvc"] and "scene.benchJson(image, request)" in c["esvc"])
    need("S1 the client calls scene at the scene contract", 'SCENE = "scene"' in c["engine"] and "IMAGE_SCENE_CONTRACT" in c["engine"])
    need("S1 the camera reaches the scene through ImageContentScanner", "engine.scene(" in c["scanner"] and "scanner.scene(" in c["ctl"] and "scanner.sceneStatus()" in c["ctl"])
    need("S1 an older engine keeps the #1533 detect path", "detector.detect(frame)" in c["ctl"])
    need("S2 MediaPipe Tasks Object Detector in the engine", "com.google.mediapipe:tasks-vision:" in c["egradle"] and "ObjectDetector.createFromOptions(" in c["eon"])
    need("S2 the LiteRT Interpreter runs YOLO11n", "com.google.ai.edge.litert:litert:" in c["egradle"] and "Interpreter(" in c["eon"] and "decodeYolo(" in c["eon"])
    for s in ("Chinese", "Devanagari", "Japanese", "Korean"):
        need("S2 ML Kit v2 %s recognizer" % s, ("text-recognition-%s:" % s.lower()) in c["ocrgradle"] and ("%sTextRecognizerOptions" % s) in c["ocr"])
    need("S2 the engine reads the five scripts through libs:ml-l-image-ocr", "project(':libs:ml-l-image-ocr')" in c["egradle"] and "MlKitOcr.text(" in c["eon"])
    need("S2 the engine merges the scripts per region", "mergeScripts(" in c["escene"] and all(x in c["escene"] for x in ('"latin"', '"chinese"', '"devanagari"', '"japanese"', '"korean"')))
    for mid, rt in (("scene_efficientdet", "mediapipe"), ("scene_yolo11n", "litert-yolo")):
        p = models.get(mid) or {}
        need("S2 %s is pinned (https, sha256, bytes, licence, runtime %s)" % (mid, rt),
             p.get("runtime") == rt and str(p.get("url", "")).startswith("https://") and re.fullmatch(r"[0-9a-f]{64}", str(p.get("sha256", ""))) is not None
             and isinstance(p.get("bytes"), int) and bool(p.get("licence")) and bool(p.get("licence_source")))
    sc = recog.get("scene") or {}
    need("S2 both detectors are selectable, the default one of them", set((sc.get("detectors") or {}).keys()) == {"efficientdet", "yolo11n"} and sc.get("default_detector") in (sc.get("detectors") or {}))
    need("S2 the Detector button cycles the detectors", "nextDetector()" in c["ctl"] and "Scene.detectors()" in c["ctl"])
    allowed = {k: v for k, v in base.get("gradle_assets", {}).items() if not k.startswith("_")}
    need("S3 the engine's gradle injects assets only where the runtime-data baseline counts",
         len(ASSET_INJECT.findall(c["egradle"])) <= allowed.get("ab_cloud-libs-shared/libs/ml-l-image-mlkit/build.gradle", 0))
    need("S3 the detectors come from the one fetchModels (ml-models.gradle)", "apply from: '../ml-models.gradle'" in c["egradle"] and "models.scene_efficientdet" in c["egradle"] and "models.scene_yolo11n" in c["egradle"])
    need("S3 the camera bakes no JSON asset", not any(re.search(r"src/[A-Za-z0-9]+/(assets|res/raw[^/]*)/.*\.json$", os.path.relpath(os.path.join(d, x), APP)) for d, _, fs in os.walk(os.path.join(APP, "app/src")) for x in fs))
    need("S4 text regions are objects of class text", 'obj("text"' in c["escene"] and 'TEXT_LABEL = "Text"' in c["escene"] and sc.get("text_label") == "Text")
    need("S4 the camera frames the text regions", "SceneFrames.textRegions(" in c["ctl"] and "overlay.show(shown, lastText" in c["ctl"] and "s.text.forEach { frame(" in c["overlay"])
    need("S5 the OCR has its own executor", "ocrExecutor.execute" in c["ctl"])
    need("S5 the OCR runs only while text is in view, at its cadence, one at a time",
         "throttle.startOcr(now)" in c["ctl"] and "if (!textPresent || ocrRunning || now - ocrAt < ocrIntervalMs) return false" in c["frames"])
    need("S5 the layout is cached per frame and a freeze reuses it", "cache.put(id, l" in c["ctl"] and "cache.freshSince(" in c["ctl"] and "class OcrCache" in c["text"])
    need("S5 an opened image is read once", "fun openUri(" in c["ctl"] and "objectsImagePicker" in c["main"] and "GetContent()" in c["main"])
    need("S6 one keep-latest analysis on a background executor", "STRATEGY_KEEP_ONLY_LATEST" in c["ctl"] and "Executors.newSingleThreadExecutor()" in c["ctl"] and len(re.findall(r"\.setAnalyzer\(", c["ctl"])) == 1)
    need("S6 live frames are sized to live_side", 'fit(frame, sceneDecl.optInt("live_side"' in c["ctl"])
    need("S7 the overlay is Compose", "AbstractComposeView" in c["overlay"] and "@Composable" in c["overlay"] and "SceneOverlayView" in f["layout"])
    need("S7 drawing maps through FrameMapping.boxToView", "FrameMapping.boxToView(" in c["overlay"])
    need("S7 taps map back through FrameMapping.viewToImage", "FrameMapping.viewToImage(" in c["overlay"] and "fun viewToImage(" in c["mapping"])
    need("S7 the front camera is mirrored and an opened image fits", "LENS_FACING_FRONT" in c["ctl"] and "cover = false" in c["ctl"])
    need("S8 a tap selects (word, line, block)", "TextSelection.tap(" in c["overlay"] and "Level.BLOCK" in c["text"])
    need("S8 a selection is copied", "ClipboardManager" in c["ctl"] and "setPrimaryClip(" in c["ctl"])
    need("S8 the handles extend the selection", "TextSelection.drag(" in c["overlay"] and "detectDragGestures(" in c["overlay"])
    need("S8 Copy all and Share", "fun copyAll()" in c["ctl"] and "Intent.ACTION_SEND" in c["ctl"] and "objects_copy_all" in f["layout"] and "objects_share" in f["layout"])
    need("S8 the snapshot keeps the detections and adds the text", "toJson(minConfidence)" in c["snap"] and "withText(" in c["snap"] and '"text_regions"' in c["snap"])
    for k in ("ctl", "frames", "overlay", "text", "escene", "eon", "emath", "cscene"):
        need("S9 %s opens no network" % k, re.search(r"HttpURLConnection|OkHttp|URL\(|https?://|openConnection|Socket\(|\"openrouter\"", c[k]) is None)
    need("S10 the #1533 controls are all still there", all(x in f["layout"] for x in ("objects_status", "objects_pause", "objects_threshold", "objects_threshold_value", "objects_panel", "objects_overlay")))
    need("S10 the overlay still captions label, confidence, tracking id", "it.caption()" in c["overlay"])
    need("S10 the #1533 snapshot, slider and pause are all still there", "fun snapshot()" in c["ctl"] and "atLeast(minConfidence)" in c["ctl"] and "setPaused(!paused)" in c["ctl"])
    return bad


def mutate(f, key, fn):
    g = dict(f); before = g[key]; g[key] = fn(before)
    assert g[key] != before, "no-op mutation on " + key
    return g


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     Scene: engine contract 4, libraries, model delivery, Text class, live OCR + cache, stream, Compose overlay, selection, on device, nothing removed")

M = [
    ("the engine stays at contract 3", "eman", lambda t: t.replace('android:value="4"', 'android:value="3"')),
    ("the service forgets scene_bench", "esvc", lambda t: t.replace(", SCENE, SCENE_BENCH)", ", SCENE)")),
    ("the camera skips the scene", "ctl", lambda t: t.replace("scanner.scene(", "scanner.detectLive(")),
    ("MediaPipe is dropped", "egradle", lambda t: t.replace("com.google.mediapipe:tasks-vision:", "com.example:none:")),
    ("Korean is dropped", "ocrgradle", lambda t: t.replace("text-recognition-korean:", "text-recognition-x:")),
    ("YOLO loses its pin", "models", lambda t: t.replace('"runtime": "litert-yolo"', '"runtime": "unknown"')),
    ("a second asset site in the engine", "egradle", lambda t: t.replace("dependencies {", "android { sourceSets { main { assets.srcDirs += 'extra' } } }\ndependencies {", 1)),
    ("text regions lose their class", "escene", lambda t: t.replace('obj("text"', 'obj("object"')),
    ("the OCR runs on every frame", "frames", lambda t: t.replace("if (!textPresent || ocrRunning || now - ocrAt < ocrIntervalMs) return false", "if (ocrRunning) return false")),
    ("the OCR runs on the analysis thread", "ctl", lambda t: t.replace("ocrExecutor.execute", "analysisExecutor.execute")),
    ("nothing is cached", "ctl", lambda t: t.replace("cache.put(id, l", "cache.toString(); listOf(id, l")),
    ("the analysis blocks the producer", "ctl", lambda t: t.replace("STRATEGY_KEEP_ONLY_LATEST", "STRATEGY_BLOCK_PRODUCER")),
    ("taps are not mapped back", "overlay", lambda t: t.replace("FrameMapping.viewToImage(", "Pair(")),
    ("the overlay is a plain View", "overlay", lambda t: t.replace("AbstractComposeView", "android.view.View")),
    ("a selection is not copied", "ctl", lambda t: t.replace("setPrimaryClip(", "toString(")),
    ("the handles do nothing", "overlay", lambda t: t.replace("TextSelection.drag(", "TextSelection.dragNot(")),
    ("the OCR phones out", "eon", lambda t: t + "\nval u = java.net.URL(\"https://x\")\n"),
    ("the pause control is removed", "ctl", lambda t: t.replace("setPaused(!paused)", "")),
    ("Share is removed", "layout", lambda t: t.replace("objects_share", "objects_gone")),
]
survivors = []
for name, key, fn in M:
    try:
        ok = not checks(mutate(real, key, fn))
    except AssertionError:
        print("FAIL     mutation is a no-op: " + name); survivors.append(name); continue
    if ok:
        survivors.append(name); print("FAIL     mutation survived: " + name)
    else:
        print("PASS     mutation caught: " + name)
if survivors:
    sys.exit(1)
PY
