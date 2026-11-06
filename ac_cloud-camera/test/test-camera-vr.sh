#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #1533 — VR: AR-style face / object filters on the live camera ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the JVM suite in app/src/test runs the anchor maths, the catalogue and the LUT):
#   R1 VR is a mode of the camera, declared in build.json::ui (id vr, the fifth bar item), entered and left by the
#      mode switch; the shutter saves the composited photo, Record the composited video.
#   R2 the analysis is ONE ImageAnalysis, STRATEGY_KEEP_ONLY_LATEST, installed only through AnalyzerLifecycle; hiding
#      the page or pausing the activity stops it, stops a recording and RELEASES the models.
#   R3 the ML is LOCAL and bundled: libs:ml-vision declares ML Kit Face Detection and Selfie Segmentation (the bundled
#      artifacts, not the Play Services ones), the VR code opens no network and asks for no permission (the camera and
#      microphone grants are Configs > Perms rows; the recorder only CHECKS the microphone).
#   R4 the filters are DATA: assets/vr-filters.json declares hats, glasses, masks, backgrounds, colour looks and stickers
#      with their anchors (landmark, offset, scale) and vector art; no filter id or landmark literal sits in the VR code.
#   R5 the compositor does the work through the tested maths: backgrounds through the person mask (DST_IN), the LUT, the
#      overlays through FilterAnchors.place / placeOnBox.
#   R6 the photo is composited at the still's own size; the video is recorded from the composited frames (MediaRecorder
#      surface), mirrored as the screen shows it.
#   R7 every model and library is in the licence inventory (maven:com.google.mlkit:face-detection and
#      segmentation-selfie) and named in LICENSING.md.
# Each check is then run against planted mutations of the real sources; a mutation that leaves every check green
# fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
J = "app/src/main/java/cld/camera"
LIB = "../ab_cloud-libs-shared/libs/ml-vision"
V = LIB + "/src/main/java/com/diegonmarcos/superapp/vision"
FILES = {
    "ctl": J + "/vr/VrController.kt", "rec": J + "/vr/VrRecorder.kt", "snap": J + "/vr/VrSnapshot.kt",
    "cam": J + "/CamConfig.kt", "main": J + "/ui/activities/MainActivity.kt", "layout": "app/src/main/res/layout/activity_main.xml",
    "build": "build.json", "filters": "app/src/main/assets/vr-filters.json", "libgradle": LIB + "/build.gradle",
    "comp": V + "/VrCompositor.kt", "anchors": V + "/FilterAnchors.kt", "catalog": V + "/FilterCatalog.kt", "lut": V + "/ColorLut.kt",
    "face": V + "/MlKitFaceDetector.kt", "seg": V + "/MlKitPersonSegmenter.kt", "view": V + "/CompositeFrameView.kt",
    "inv": "../licenses/inventory.json", "licensing": "../LICENSING.md",
}


def load():
    return {k: open(os.path.join(APP, v), encoding="utf-8").read() for k, v in FILES.items()}


def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$|(?<=\s)//[^\n]*$", "", t)


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    c = {k: code(v) for k, v in f.items() if k not in ("build", "filters", "inv", "licensing")}
    try:
        b = json.loads(f["build"]); flt = json.loads(f["filters"]); inv = json.loads(f["inv"])
    except ValueError:
        return ["build.json / vr-filters.json / inventory.json is unreadable"]
    ui = b["ui"]
    need("R1 VR is the fifth bar item", ui["bottom_nav"][-1] == "vr" and "vr" in [s["id"] for s in ui["sections"]])
    need("R1 CameraMode has VR", re.search(r"\bVR\(", c["cam"]) is not None)
    need("R1 the mode switch enters and leaves VR", "mActivity.vr.enter()" in c["cam"] and "vrIfStarted?.leave()" in c["cam"] and "isVrMode = mode == CameraMode.VR" in c["cam"])
    need("R1 the shutter saves the composited photo", re.search(r"isVrMode\)\s*\{\s*vr\.snapshot\(\)", c["main"]) is not None)
    need("R1 Record is a control", "toggleRecord()" in c["ctl"] and "recordButton.setOnClickListener" in c["ctl"])
    need("R2 the analysis is KEEP_ONLY_LATEST", "STRATEGY_KEEP_ONLY_LATEST" in c["ctl"])
    need("R2 the analysis joins the bind", "useCasesList.add(mActivity.vr.analysisUseCase(aspectRatio))" in c["cam"])
    need("R2 the analyzer goes through AnalyzerLifecycle", "AnalyzerLifecycle(" in c["ctl"] and "lifecycle.onShown()" in c["ctl"] and len(re.findall(r"\.setAnalyzer\(", c["ctl"])) == 1)
    need("R2 frames are spaced and closed", "gate.admits()" in c["ctl"] and "image.close()" in c["ctl"])
    need("R2 leaving the page stops analysis, recording and the models", re.search(r"fun leave\(\)\s*\{\s*lifecycle\.onHidden\(\)\s*stopRecording\(\)\s*releaseModels\(\)", c["ctl"]) is not None)
    need("R2 the activity's onPause stops VR", re.search(r"override fun onPause\(\)[^}]*vrIfStarted\?\.onActivityPaused\(\)", c["main"], re.S) is not None)
    need("R2 pausing stops recording and releases the models", re.search(r"fun onActivityPaused\(\)\s*\{\s*lifecycle\.onHidden\(\)\s*stopRecording\(\)\s*releaseModels\(\)", c["ctl"]) is not None)
    need("R2 only the models a filter needs are run", "val needFaces = filters.any" in c["ctl"] and "val needMask = filters.any" in c["ctl"] and "val needObjects = filters.any" in c["ctl"])
    need("R3 ML Kit Face Detection is the bundled artifact", "com.google.mlkit:face-detection:" in f["libgradle"] and "play-services-mlkit-face-detection" not in code(f["libgradle"]))
    need("R3 ML Kit Selfie Segmentation is the bundled artifact", "com.google.mlkit:segmentation-selfie:" in f["libgradle"])
    need("R3 faces come with landmarks and contours", "LANDMARK_MODE_ALL" in c["face"] and "CONTOUR_MODE_ALL" in c["face"])
    need("R3 segmentation runs in stream mode", "STREAM_MODE" in c["seg"])
    for k in ("ctl", "rec", "snap", "comp", "anchors", "catalog", "lut", "face", "seg", "view"):
        need("R3 %s opens no network" % k, re.search(r"HttpURLConnection|OkHttp|URL\(|https?://|openConnection|Socket\(", c[k]) is None)
        need("R3 %s requests no permission" % k, re.search(r"requestPermission|RequestPermission|requestPermissions", c[k]) is None)
    need("R3 the camera grant is a Configs > Perms row", {"camera", "microphone"} <= set((b.get("perms") or {}).get("needs", [])))
    need("R3 the recorder only checks the microphone", "checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)" in c["rec"])
    kinds = {x["kind"] for x in flt["filters"]}
    need("R4 the catalogue declares every kind", kinds >= {"hat", "glasses", "mask", "background", "lut", "sticker"})
    need("R4 hats are on the head, glasses on the eyes", all(x["anchor"]["landmark"] == "head_top" for x in flt["filters"] if x["kind"] == "hat")
         and all(x["anchor"]["landmark"] == "eyes_center" for x in flt["filters"] if x["kind"] == "glasses"))
    need("R4 every overlay declares landmark, scale and art", all("landmark" in x["anchor"] and x["anchor"]["scale"] > 0 and x["art"] for x in flt["filters"] if "anchor" in x))
    need("R4 the controller reads the declared catalogue", "FilterCatalog.load(" in c["ctl"] and "vr-filters.json" in c["ctl"])
    ids = [x["id"] for x in flt["filters"]]
    for k in ("ctl", "comp", "face", "seg", "view", "rec", "snap"):
        need("R4 %s names no filter id" % k, not any(('"%s"' % i) in c[k] for i in ids))
    for k in ("ctl", "comp"):
        need("R4 %s names no landmark" % k, re.search(r'"(head_top|eyes_center|face_center|nose|mouth_center|top_center)"', c[k]) is None)
    need("R5 backgrounds go through the person mask", "PorterDuff.Mode.DST_IN" in c["comp"] and "drawPerson(" in c["comp"])
    need("R5 the LUT is applied to the frame", "lut.apply(px)" in c["comp"] and "ColorLut.from(" in c["comp"])
    need("R5 overlays are placed by FilterAnchors", "FilterAnchors.place(" in c["comp"] and "FilterAnchors.placeOnBox(" in c["comp"])
    need("R5 the art rotates and scales with the placement", "c.rotate(p.rotationDegrees)" in c["comp"] and "c.scale(p.width / art.width" in c["comp"])
    need("R5 the anchor maths follows the head's roll", "rollDegrees(face)" in c["anchors"] and "atan2(" in c["anchors"])
    need("R6 the still is composited at its own size", "VrCompositor(activity.assets).composite(photo" in c["ctl"] and "VrSnapshot.save(" in c["ctl"])
    need("R6 the video is recorded from composited frames", "recorder.add(out)" in c["ctl"] and "VideoSource.SURFACE" in c["rec"] and "lockCanvas" in c["rec"])
    need("R6 the recording mirrors as the screen does", "mirrored" in c["rec"] and "c.scale(-1f, 1f" in c["rec"])
    need("R6 a frame composited for the view is mirrored for the front camera", "mirrored" in c["view"])
    need("R7 the inventory has Face Detection", "maven:com.google.mlkit:face-detection" in inv["entries"])
    need("R7 the inventory has Selfie Segmentation", "maven:com.google.mlkit:segmentation-selfie" in inv["entries"])
    need("R7 LICENSING.md names both", "com.google.mlkit:face-detection" in f["licensing"] and "com.google.mlkit:segmentation-selfie" in f["licensing"])
    need("R7 the panel is hidden until the page is picked", re.search(r'vr_panel"[^>]*android:visibility="gone"', f["layout"], re.S) is not None)
    return bad


def mutate(f, key, fn):
    g = dict(f); before = g[key]; g[key] = fn(before)
    assert g[key] != before, "no-op mutation on " + key
    return g


def edit_json(key, fn):
    def go(t):
        d = json.loads(t); fn(d); return json.dumps(d)
    return go


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     VR: mode, analyzer lifecycle, bundled local ML, declared filters, compositor, capture, licences")

M = [
    ("VR leaves the bar", "build", edit_json("build", lambda d: d["ui"]["bottom_nav"].remove("vr"))),
    ("the mode switch forgets VR", "cam", lambda t: t.replace("mActivity.vr.enter()", "")),
    ("the shutter stops saving the composited photo", "main", lambda t: t.replace("vr.snapshot()", "")),
    ("the analysis keeps every frame", "ctl", lambda t: t.replace("STRATEGY_KEEP_ONLY_LATEST", "STRATEGY_BLOCK_PRODUCER")),
    ("leaving the page leaves the models loaded", "ctl", lambda t: t.replace("stopRecording()\n        releaseModels()\n        view.show(null, false)", "stopRecording()\n        view.show(null, false)")),
    ("the activity pause leaves VR running", "main", lambda t: t.replace("vrIfStarted?.onActivityPaused()", "")),
    ("the analyzer is installed bare", "ctl", lambda t: t.replace("lifecycle.onShown()\n        return ia", "ia.setAnalyzer(analysisExecutor) { analyze(it) }\n        return ia")),
    ("every model runs for every filter", "ctl", lambda t: t.replace("val needMask =", "val needMaskX =")),
    ("face detection goes unbundled", "libgradle", lambda t: t.replace("com.google.mlkit:face-detection:", "com.google.android.gms:play-services-mlkit-face-detection:")),
    ("segmentation is dropped", "libgradle", lambda t: t.replace("com.google.mlkit:segmentation-selfie:", "com.google.mlkit:segmentation-gone:")),
    ("contours are off", "face", lambda t: t.replace("CONTOUR_MODE_ALL", "CONTOUR_MODE_NONE")),
    ("the VR code phones out", "ctl", lambda t: t + "\nfun x() { java.net.URL(\"https://x\").openConnection() }\n"),
    ("the VR code asks for the camera", "ctl", lambda t: t + "\nfun y() { requestPermission() }\n"),
    ("the recorder asks for the microphone", "rec", lambda t: t.replace("ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)", "requestPermissions()")),
    ("the catalogue loses its stickers", "filters", edit_json("filters", lambda d: d.__setitem__("filters", [x for x in d["filters"] if x["kind"] != "sticker"]))),
    ("a hat moves off the head", "filters", edit_json("filters", lambda d: [x["anchor"].__setitem__("landmark", "chin") for x in d["filters"] if x["kind"] == "hat"])),
    ("a filter id is typed into the controller", "ctl", lambda t: t + "\nval fav = \"hat_party\"\n"),
    ("the controller stops reading the catalogue", "ctl", lambda t: t.replace("FilterCatalog.load(", "FilterCatalogX.load(")),
    ("the background ignores the mask", "comp", lambda t: t.replace("PorterDuff.Mode.DST_IN", "PorterDuff.Mode.SRC_OVER")),
    ("the LUT is never applied", "comp", lambda t: t.replace("lut.apply(px)", "")),
    ("overlays skip the anchor maths", "comp", lambda t: t.replace("FilterAnchors.place(anchor", "FilterAnchors.placeX(anchor")),
    ("the art stops rotating", "comp", lambda t: t.replace("c.rotate(p.rotationDegrees)", "")),
    ("the anchor ignores the head's roll", "anchors", lambda t: t.replace("rollDegrees(face)", "0f")),
    ("the still is composited at preview size", "ctl", lambda t: t.replace("VrCompositor(activity.assets).composite(photo", "liveCompositor.composite(photo")),
    ("the video is not fed the composited frames", "ctl", lambda t: t.replace("recorder.add(out)", "")),
    ("the recording is not mirrored", "rec", lambda t: t.replace("c.scale(-1f, 1f", "c.scale(1f, 1f")),
    ("the inventory forgets Face Detection", "inv", edit_json("inv", lambda d: d["entries"].pop("maven:com.google.mlkit:face-detection"))),
    ("the panel is visible from the start", "layout", lambda t: re.sub(r'(vr_panel"[^>]*?)android:visibility="gone"', r'\1android:visibility="visible"', t, flags=re.S)),
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
