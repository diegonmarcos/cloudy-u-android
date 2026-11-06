#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #1533 — Objects: live on-device detection over the preview   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the JVM suite in app/src/test runs the maths and the lifecycle):
#   O1 Objects is a mode of the camera, declared in build.json::ui (id objects), and the mode switch enters and
#      leaves it (CamConfig: isObjectsMode, objects.enter / leave).
#   O2 the analysis is ONE ImageAnalysis with STRATEGY_KEEP_ONLY_LATEST, rotated upright, bound with the preview,
#      its analyzer installed only through AnalyzerLifecycle (never a bare setAnalyzer at bind time).
#   O3 the analyzer stops when the page is hidden: leave() and the activity's onPause both hide the lifecycle,
#      and Pause removes it.
#   O4 detection is LOCAL: the fleet's on-device engine through ImageContentScanner.detectLive; no HTTP client, no
#      URL and no cloud route in the Objects or vision code.
#   O5 boxes + labels + confidence + tracking ids go through libs:ml-vision's mapping (FrameMapping, mirrored for
#      the front camera); a confidence slider filters; the shutter saves the frame with its JSON detections.
#   O6 the camera permission is a Configs > Perms row only: no permission request in the Objects or vision code.
#   O7 libs:ml-vision is a declared, included, compiled module and a licence-inventory subject.
# Each check is then run against planted mutations of the real sources; a mutation that leaves every check
# green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
ROOT = os.path.join(APP, "..")
J = "app/src/main/java/cld/camera"
LIB = "../ab_cloud-libs-shared/libs/ml-vision/src/main/java/com/diegonmarcos/superapp/vision"
FILES = {
    "ctl": J + "/objects/ObjectsController.kt", "det": J + "/objects/EngineObjectDetector.kt",
    "snap": J + "/objects/ObjectsSnapshot.kt", "cam": J + "/CamConfig.kt", "main": J + "/ui/activities/MainActivity.kt",
    "layout": "app/src/main/res/layout/activity_main.xml", "gradle": "app/build.gradle.kts", "settings": "settings.gradle.kts",
    "build": "build.json", "lifecycle": LIB + "/AnalyzerLifecycle.kt", "overlay": LIB + "/DetectionOverlayView.kt",
    "mapping": LIB + "/FrameMapping.kt", "detection": LIB + "/Detection.kt",
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
    c = {k: code(v) for k, v in f.items() if k != "build"}
    try:
        b = json.loads(f["build"])
    except ValueError:
        return ["build.json is unreadable"]
    ids = [s["id"] for s in b["ui"]["sections"]]
    need("O1 build.json::ui declares the objects section", "objects" in ids and "objects" in b["ui"]["bottom_nav"])
    need("O1 CameraMode has OBJECTS", re.search(r"\bOBJECTS\(", c["cam"]) is not None)
    need("O1 the mode switch enters Objects", "objects.enter()" in c["cam"] and "isObjectsMode = mode == CameraMode.OBJECTS" in c["cam"])
    need("O1 the mode switch leaves Objects", "objectsIfStarted?.leave()" in c["cam"])
    need("O2 the analysis is KEEP_ONLY_LATEST", "STRATEGY_KEEP_ONLY_LATEST" in c["ctl"])
    need("O2 the analysis output is rotated upright", "setOutputImageRotationEnabled(true)" in c["ctl"])
    need("O2 the analysis joins the preview's bind", "analysisUseCase(aspectRatio)" in c["cam"] and "useCasesList.add(mActivity.objects.analysisUseCase" in c["cam"])
    need("O2 the analyzer goes through AnalyzerLifecycle", "AnalyzerLifecycle(" in c["ctl"] and "lifecycle.onShown()" in c["ctl"])
    need("O2 the analyzer is installed in the lifecycle host only", len(re.findall(r"\.setAnalyzer\(", c["ctl"])) == 1)
    need("O2 frames are spaced by the declared interval (AnalysisGate)", "AnalysisGate(" in c["ctl"] and "gate.admits()" in c["ctl"] and "interval_ms" in c["ctl"])
    need("O2 every frame is closed", "image.close()" in c["ctl"])
    need("O3 leaving the page stops the analyzer", re.search(r"fun leave\(\)\s*\{\s*lifecycle\.onHidden\(\)", c["ctl"]) is not None)
    need("O3 the activity's onPause stops it", re.search(r"override fun onPause\(\)[^}]*objectsIfStarted\?\.onActivityPaused\(\)", c["main"], re.S) is not None)
    need("O3 onActivityPaused hides the lifecycle", re.search(r"fun onActivityPaused\(\)\s*=\s*lifecycle\.onHidden\(\)", c["ctl"]) is not None)
    need("O3 Pause removes the analyzer", "lifecycle.setPaused(p)" in c["ctl"])
    need("O3 the lifecycle detaches when hidden or paused", "gate.visible && !gate.paused" in c["lifecycle"] and "host.detach()" in c["lifecycle"])
    need("O3 the gate admits nothing while hidden or paused", "if (!visible || paused) return false" in c["lifecycle"])
    need("O4 detection goes through the fleet engine (detectLive)", "scanner.detectLive(" in c["det"])
    for k in ("ctl", "det", "snap", "lifecycle", "overlay", "mapping", "detection"):
        need("O4 %s opens no network" % k, re.search(r"HttpURLConnection|OkHttp|URL\(|https?://|openConnection|Socket\(|\"openrouter\"", c[k]) is None)
    need("O5 the overlay maps through FrameMapping", "FrameMapping.boxToView(" in c["overlay"])
    need("O5 the front camera is mirrored", "LENS_FACING_FRONT" in c["ctl"] and "mirrored" in c["ctl"])
    need("O5 the overlay shows label, confidence and the tracking id", "label" in c["detection"] and "confidence" in c["detection"] and "trackingId" in c["detection"] and "caption()" in c["overlay"])
    need("O5 the slider filters by confidence", "atLeast(minConfidence)" in c["ctl"] and "OnSeekBarChangeListener" in c["ctl"])
    need("O5 the shutter is the snapshot", re.search(r"isObjectsMode\)\s*\{\s*objects\.snapshot\(\)", c["main"]) is not None)
    need("O5 the snapshot saves the frame with its JSON detections", "TAG_USER_COMMENT" in c["snap"] and "toJson(" in c["snap"] and "application/json" in c["snap"])
    need("O5 Pause is a control", "setPaused(!paused)" in c["ctl"])
    for k in ("ctl", "det", "snap", "lifecycle", "overlay", "mapping", "detection"):
        need("O6 %s asks for no permission" % k, re.search(r"requestPermission|Manifest\.permission|checkSelfPermission|RequestPermission", c[k]) is None)
    need("O6 the camera grant is a Configs > Perms row", "camera" in (b.get("perms") or {}).get("needs", []))
    need("O7 the app includes and compiles libs:ml-vision", ":libs:ml-vision" in c["settings"] and ":libs:ml-vision" in c["gradle"])
    need("O7 build.json::modules declares libs:ml-vision", "libs:ml-vision" in (b.get("modules") or {}))
    need("O7 the Objects panel is hidden until the page is picked", re.search(r'objects_panel"[^>]*android:visibility="gone"', f["layout"], re.S) is not None)
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
print("PASS     Objects: mode, analyzer, lifecycle, local detection, overlay, snapshot, perms, lib")

M = [
    ("the analysis keeps every frame", "ctl", lambda t: t.replace("STRATEGY_KEEP_ONLY_LATEST", "STRATEGY_BLOCK_PRODUCER")),
    ("the analysis stays sensor-oriented", "ctl", lambda t: t.replace("setOutputImageRotationEnabled(true)", "setOutputImageRotationEnabled(false)")),
    ("leaving the page leaves the analyzer on", "ctl", lambda t: re.sub(r"fun leave\(\) \{\s*lifecycle\.onHidden\(\)", "fun leave() {", t)),
    ("the activity pause leaves the analyzer on", "main", lambda t: t.replace("objectsIfStarted?.onActivityPaused()", "")),
    ("Pause does not stop the analyzer", "ctl", lambda t: t.replace("lifecycle.setPaused(p)", "")),
    ("the lifecycle never detaches", "lifecycle", lambda t: t.replace("host.detach()", "")),
    ("the gate admits a hidden page", "lifecycle", lambda t: t.replace("if (!visible || paused) return false", "")),
    ("the analyzer is installed bare at bind", "ctl", lambda t: t.replace("lifecycle.onShown()\n        return ia", "ia.setAnalyzer(analysisExecutor) { analyze(it) }\n        return ia")),
    ("frames are never closed", "ctl", lambda t: t.replace("image.close()", "")),
    ("detection phones out", "det", lambda t: t.replace("scanner.detectLive(", "java.net.URL(\"https://x\").openConnection(); scanner.detectLive(")),
    ("the overlay skips the mapping", "overlay", lambda t: t.replace("FrameMapping.boxToView(", "Box(")),
    ("the front camera is not mirrored", "ctl", lambda t: t.replace("LENS_FACING_FRONT", "LENS_FACING_BACK")),
    ("the slider filters nothing", "ctl", lambda t: t.replace("atLeast(minConfidence)", "detections")),
    ("the snapshot drops its JSON", "snap", lambda t: t.replace("TAG_USER_COMMENT", "TAG_SOFTWARE")),
    ("the shutter stops being the snapshot", "main", lambda t: t.replace("objects.snapshot()", "")),
    ("the Objects page asks for the camera itself", "ctl", lambda t: t + "\nfun x() { requestPermission() }\n"),
    ("the mode switch forgets to enter", "cam", lambda t: t.replace("mActivity.objects.enter()", "")),
    ("the analysis misses the bind", "cam", lambda t: t.replace("useCasesList.add(mActivity.objects.analysisUseCase(aspectRatio))", "")),
    ("the lib is not included", "settings", lambda t: t.replace(":libs:ml-vision", ":libs:gone")),
    ("the panel is visible from the start", "layout", lambda t: re.sub(r'(objects_panel"[^>]*?)android:visibility="gone"', r'\1android:visibility="visible"', t, flags=re.S)),
]
survivors = []
for name, key, fn in M:
    try:
        ok = not checks(mutate(real, key, fn))
    except AssertionError as e:
        print("FAIL     mutation is a no-op: " + name); survivors.append(name); continue
    if ok:
        survivors.append(name); print("FAIL     mutation survived: " + name)
    else:
        print("PASS     mutation caught: " + name)
if survivors:
    sys.exit(1)
PY
