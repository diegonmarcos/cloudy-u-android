#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #798 — Identify: live on-device detection and sound ID      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (this app has no JVM suite; CI builds it; the engines'
# own suites and lib-apks/test/test-ml-goldens.sh run the models):
#   I1 Identify is reachable: IdentifyActivity in the manifest, not exported, opened by the
#      settings dialog's Identify button, every label a string resource.
#   I2 live frames go through the ONE door (ImageContentScanner.detectLive, stream mode), no
#      closer together than recognition.json's detect.interval_ms, the analysis output rotated
#      upright and the frame recycled.
#   I3 the modes are the DECLARED ones (RecognitionConfig.detectModes) plus Sound.
#   I4 boxes land where the preview shows them: FILL_CENTER on the preview, the overlay maps
#      through BoxMapping.toView, whose scale COVERS the view (max of the two ratios).
#   I5 a snapshot is identified on the USER's route (recognize(jpeg, null)) and saved with
#      its EXIF description and JSON comment into DCIM/Camera, with fresh ambient sound tags.
#   I6 Sound mode listens through SoundIdentifier, asks for RECORD_AUDIO, stops on a failure.
#   I7 /api/<image_group>/detect and /api/<sound_group>/classify (ms | path | test), the sound
#      group baked from build.json, the microphone refused without RECORD_AUDIO.
#   I8 the sound CONTRACT is compiled by reference; the engine never is.
# Each check is then run against planted mutations of the real sources; a mutation that
# leaves every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET

APP = os.environ["APP_DIR"]
J = os.path.join(APP, "app/src/main/java/cld/camera")
FILES = {
    "activity": os.path.join(J, "identify/IdentifyActivity.kt"),
    "overlay": os.path.join(J, "identify/DetectionOverlay.kt"),
    "mapping": os.path.join(J, "identify/BoxMapping.kt"),
    "snapshot": os.path.join(J, "identify/IdentifySnapshot.kt"),
    "scanner": os.path.join(J, "analyzer/ImageContentScanner.kt"),
    "sound": os.path.join(J, "analyzer/SoundIdentifier.kt"),
    "provider": os.path.join(J, "debugapi/ImageDebugApiProvider.kt"),
    "dialog": os.path.join(J, "ui/SettingsDialog.kt"),
    "dialog_layout": os.path.join(APP, "app/src/main/res/layout/settings.xml"),
    "strings": os.path.join(APP, "app/src/main/res/values/strings.xml"),
    "manifest": os.path.join(APP, "app/src/main/AndroidManifest.xml"),
    "gradle": os.path.join(APP, "app/build.gradle.kts"),
    "settings_gradle": os.path.join(APP, "settings.gradle.kts"),
    "build": os.path.join(APP, "build.json"),
}
for k, p in FILES.items():
    if not os.path.isfile(p):
        print("ERROR missing source %s — this tester is unrun, not passing" % p); sys.exit(1)

def code(text):
    """Comment lines out, so prose naming a call never satisfies a check."""
    return "\n".join(l for l in text.split("\n") if not re.match(r"\s*(\*|//|/\*|#)", l))

src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}
ANDROID = "{http://schemas.android.com/apk/res/android}"

def checks(s):
    bad = []
    def need(label, ok):
        if not ok: bad.append(label)
    a, ov, mp, sn = code(s["activity"]), code(s["overlay"]), code(s["mapping"]), code(s["snapshot"])
    pr, dg = code(s["provider"]), code(s["dialog"])
    # I1
    try:
        acts = [x for x in ET.fromstring(s["manifest"]).iter("activity") if x.get(ANDROID + "name") == ".identify.IdentifyActivity"]
    except ET.ParseError:
        acts = []
    need("I1 IdentifyActivity is in the manifest", len(acts) == 1)
    need("I1 IdentifyActivity is not exported", bool(acts) and acts[0].get(ANDROID + "exported") == "false")
    need("I1 the settings dialog has an Identify button", 'android:id="@+id/identify_live"' in s["dialog_layout"])
    need("I1 the button opens Identify", re.search(r"identifyButton\.setOnClickListener\s*\{[^}]*IdentifyActivity\.start\(", dg, re.S) is not None)
    used = set(re.findall(r"R\.string\.(identify_\w+)", a + dg + s["dialog_layout"].replace("@string/", "R.string.")))
    declared = set(re.findall(r'<string name="(identify_\w+)"', s["strings"]))
    need("I1 every Identify string is declared (%s)" % sorted(used - declared), bool(used) and used <= declared)
    # I2
    need("I2 frames go to the engine through ImageContentScanner.detectLive", "scanner.detectLive(frame, m)" in a)
    need("I2 no ImageScanEngine outside the scanner", "ImageScanEngine" not in a + ov + mp + sn + pr)
    need("I2 detectLive asks for a live (stream) request", "RecognitionConfig.detectRequest(mode, live = true)" in code(s["scanner"]))
    need("I2 the throttle is the declared interval", 'getJSONObject("detect").optLong("interval_ms"' in a and "now - lastFrameAt < interval" in a)
    need("I2 the analysis output is rotated upright", "setOutputImageRotationEnabled(true)" in a)
    need("I2 the frame is recycled after the call", re.search(r"finally\s*\{\s*frame\.recycle\(\)", a) is not None)
    need("I2 every ImageProxy is closed", re.search(r"finally\s*\{\s*image\.close\(\)", a) is not None)
    # I3
    need("I3 the modes are the declared ones plus Sound", "(RecognitionConfig.detectModes() + SOUND)" in a)
    # I4
    need("I4 the preview fills its view centred (FILL_CENTER)", "PreviewView.ScaleType.FILL_CENTER" in a)
    need("I4 the overlay maps through BoxMapping.toView", "BoxMapping.toView(it.x, it.y, it.w, it.h, frameW, frameH, width, height)" in ov)
    need("I4 the mapping covers the view (max of the ratios)", "val scale = maxOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)" in mp)
    need("I4 the mapping centres the overflow", "val dx = (viewW - frameW * scale) / 2f" in mp and "val dy = (viewH - frameH * scale) / 2f" in mp)
    need("I4 a tap goes to the box under it", "BoxMapping.hit(rects, event.x, event.y)" in ov and "onBox(boxes[i])" in ov)
    # I5
    need("I5 a snapshot is identified on the user's route", "scanner.recognize(jpeg, null)" in sn)
    need("I5 the description and the JSON go into the photo's EXIF",
         "ExifInterface.TAG_IMAGE_DESCRIPTION, summary(meta)" in sn and "ExifInterface.TAG_USER_COMMENT, meta.toString()" in sn and "saveAttributes()" in sn)
    need("I5 the photo is filed in DCIM/Camera", "MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH" in sn)
    need("I5 ambient sound is tagged through the declared rule", "SoundConfig.tags(it)" in sn)
    need("I5 only fresh ambient sound rides along", "SystemClock.elapsedRealtime() - heardAt <= AMBIENT_FRESH_MS" in a)
    need("I5 the shutter saves through IdentifySnapshot", "IdentifySnapshot.save(this@IdentifyActivity, tmp, atShot, ambient, scanner)" in a)
    # I6
    need("I6 Sound mode listens through SoundIdentifier", "sound.listen()" in a)
    need("I6 the microphone is asked for in Configs > Perms (#1515)", 'openPerms("Identify: sound", "microphone")' in a and "RequestPermission" not in a)
    need("I6 a failure stops the loop", "if (!r.ok) break" in a)
    # I7
    need("I7 /api/<image_group>/detect is served", '"detect" -> detect(app, q)' in pr and "scanner.detect(f, mode)" in pr)
    need("I7 an undeclared detect mode is refused", "mode !in RecognitionConfig.detectModes()" in pr)
    need("I7 the sound group is baked from build.json", "BuildConfig.DEBUG_API_SOUND_GROUP" in pr
         and 'buildConfigField("String", "DEBUG_API_SOUND_GROUP", "\\"$debugSoundGroup\\"")' in s["gradle"]
         and re.search(r'"sound_group"\s*:\s*"sound"', s["build"]) is not None)
    need("I7 classify takes ms, path and test", 'q["ms"]' in pr and 'q["path"]' in pr and 'q["test"]' in pr and '"classify" -> classify(app, q)' in pr)
    need("I7 the microphone needs RECORD_AUDIO", "Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED" in pr)
    need("I7 test clips are the contract's", "SoundCapture.testClip(test, SoundConfig.captureMs(ms))" in pr)
    # I8
    sg = code(s["settings_gradle"])
    need("I8 settings includes the sound contract by reference",
         'include(":libs:ml-l-sound")' in sg and 'project(":libs:ml-l-sound").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-sound")' in sg)
    need("I8 the app depends on the sound contract", 'implementation(project(":libs:ml-l-sound"))' in code(s["gradle"]))
    need("I8 the engine is never compiled", "ml-l-sound-yamnet" not in sg + code(s["gradle"]) + s["build"])
    return bad

print("── I1-I8 against the tree ──")
bad = checks(src)
for f in bad:
    print("  FAIL  " + f)
print("  %d failure(s)" % len(bad))

MUTATIONS = [
    ("exported", "manifest", 'android:name=".identify.IdentifyActivity"\n            android:theme="@style/Theme.App"\n            android:screenOrientation="portrait"\n            android:exported="false"',
     'android:name=".identify.IdentifyActivity"\n            android:theme="@style/Theme.App"\n            android:screenOrientation="portrait"\n            android:exported="true"'),
    ("button-dead", "dialog", "IdentifyActivity.start(mActivity)", "Unit"),
    ("string-missing", "strings", '<string name="identify_snapshot">', '<string name="identify_snap">'),
    ("engine-direct", "activity", "scanner.detectLive(frame, m)", "ImageScanEngine(this).detect(frame, RecognitionConfig.detectRequest(m, true))"),
    ("not-live", "scanner", "RecognitionConfig.detectRequest(mode, live = true)", "RecognitionConfig.detectRequest(mode, live = false)"),
    ("no-throttle", "activity", "now - lastFrameAt < interval", "false"),
    ("sideways", "activity", "setOutputImageRotationEnabled(true)", "setOutputImageRotationEnabled(false)"),
    ("frame-leak", "activity", "finally { frame.recycle() }", "finally { }"),
    ("modes-literal", "activity", "(RecognitionConfig.detectModes() + SOUND)", '(listOf("objects") + SOUND)'),
    ("fit-not-fill", "activity", "PreviewView.ScaleType.FILL_CENTER", "PreviewView.ScaleType.FIT_CENTER"),
    ("contain-not-cover", "mapping", "val scale = maxOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)", "val scale = minOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)"),
    ("not-centred", "mapping", "val dy = (viewH - frameH * scale) / 2f", "val dy = 0f"),
    ("route-ignored", "snapshot", "scanner.recognize(jpeg, null)", 'scanner.recognize(jpeg, "ml")'),
    ("no-exif", "snapshot", "ExifInterface.TAG_USER_COMMENT, meta.toString()", "ExifInterface.TAG_SOFTWARE, meta.toString()"),
    ("stale-sound", "activity", "SystemClock.elapsedRealtime() - heardAt <= AMBIENT_FRESH_MS", "true"),
    ("spin", "activity", "if (!r.ok) break", "if (!r.ok) continue"),
    ("no-detect-op", "provider", '"detect" -> detect(app, q)', '"detected" -> detect(app, q)'),
    ("mic-unguarded", "provider", "Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED", "Manifest.permission.RECORD_AUDIO) == -42"),
    ("sound-group-undeclared", "build", '"sound_group": "sound"', '"sound_grp": "sound"'),
    ("engine-compiled", "gradle", 'implementation(project(":libs:ml-l-sound"))', 'implementation(project(":libs:ml-l-sound"))\n    implementation(project(":libs:ml-l-sound-yamnet"))'),
]
print("── planted mutations ──")
mut_fail = 0
for name, key, old, new in MUTATIONS:
    if src[key].count(old) != 1:
        print("  STALE MUT %s — its anchor is not in %s exactly once" % (name, key)); mut_fail += 1; continue
    m = dict(src); m[key] = src[key].replace(old, new)
    if checks(m):
        print("  PASS  MUT " + name)
    else:
        print("  FAIL  MUT %s — survived every check" % name); mut_fail += 1
print("── I1-I8 + %d mutations: %d failure(s) ──" % (len(MUTATIONS), len(bad) + mut_fail))
sys.exit(1 if bad or mut_fail else 0)
PY
