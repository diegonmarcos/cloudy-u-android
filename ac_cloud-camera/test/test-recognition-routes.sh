#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #799 Recognition route switch — Model (Jev) by DEFAULT, on-device ML as   ║
# ║ the offline fallback, per type (Image, Sound), in Cloud Camera AND Calc   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   W1  ONE declaration: recognition.json and sound.json both default to openrouter, label the
#       two routes identically ("Model (Jev)", "On-device ML (offline)"), allow the fallback, and
#       the default model is typesafe/jev-1.13.
#   W2  the rule lives once, in the contract (RecognitionRoutes.routed): the image path of both
#       apps goes through RecognitionRoutes.image, the sound path of both through routed
#       (Camera via SoundRouting, Calc via identifyRouted with its own token check).
#   W3  one switch per type in each app: Camera's More settings has an Image AND a Sound route
#       item; Calc's Sound screen saves SoundPrefs and its Camera screen RecognitionPrefs.
#   W4  /api/<image_group>/route and /api/<sound_group>/route in both apps report the active route
#       and the last one used; classify/recognize carry the same report.
# Source checks (the behaviour itself is RecognitionRoutesTest/SoundConfigTest on the JVM).
# Each check is then run against planted mutations.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT

python3 - <<'PY'
import json, os, re, sys

R = os.environ["ROOT"]
LIBS = os.path.join(R, "ab_cloud-libs-shared/libs")
CAM = os.path.join(R, "ac_cloud-camera/app/src/main")
CALC = os.path.join(R, "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc")
FILES = {
    "image_decl": os.path.join(LIBS, "ml-l-image/recognition.json"),
    "sound_decl": os.path.join(LIBS, "ml-l-sound/sound.json"),
    "routes": os.path.join(LIBS, "ml-l-image/src/main/java/com/diegonmarcos/superapp/image/RecognitionRoutes.kt"),
    "sound_engine": os.path.join(LIBS, "ml-l-sound/src/main/java/com/diegonmarcos/superapp/sound/SoundEngine.kt"),
    "cam_scanner": os.path.join(CAM, "java/cld/camera/analyzer/ImageContentScanner.kt"),
    "cam_sound": os.path.join(CAM, "java/cld/camera/analyzer/SoundIdentifier.kt"),
    "cam_settings": os.path.join(CAM, "java/cld/camera/ui/activities/MoreSettings.kt"),
    "cam_layout": os.path.join(CAM, "res/layout/more_settings.xml"),
    "cam_api": os.path.join(CAM, "java/cld/camera/debugapi/ImageDebugApiProvider.kt"),
    "calc_flow": os.path.join(CALC, "audio/SoundFlow.kt"),
    "calc_vision": os.path.join(CALC, "camera/Vision.kt"),
    "calc_screen": os.path.join(CALC, "ui/SoundScreens.kt"),
    "calc_sound_api": os.path.join(CALC, "debugapi/SoundDebugApi.kt"),
    "calc_camera_api": os.path.join(CALC, "debugapi/CameraDebugApi.kt"),
}
for p in FILES.values():
    if not os.path.isfile(p):
        print("ERROR missing source %s — this tester is unrun, not passing" % p); sys.exit(1)

def code(t):
    return "\n".join(l for l in t.split("\n") if not re.match(r"\s*(\*|//|/\*)", l))

src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}

def checks(s):
    bad = []
    def need(label, ok):
        if not ok: bad.append(label)
    try:
        im, so = json.loads(s["image_decl"]), json.loads(s["sound_decl"])
    except ValueError:
        im, so = {}, {}
    # W1
    need("W1 image defaults to the model", im.get("default_route") == "openrouter")
    need("W1 sound defaults to the model", so.get("default_route") == "openrouter")
    need("W1 both switches read the same", im.get("routes") == so.get("routes") == {"openrouter": "Model (Jev)", "ml": "On-device ML (offline)"})
    need("W1 both fall back on device", im.get("fallback_to_ml") is True and so.get("fallback_to_ml") is True)
    need("W1 the default model is jev-1.13", im.get("openrouter", {}).get("default_model") == "typesafe/jev-1.13")
    rt = code(s["routes"])
    # W2
    need("W2 offline is never sent to the model", "online == false -> Plan(false, OFFLINE)" in rt)
    need("W2 no token is never sent to the model", "hasToken == false -> Plan(false, NO_TOKEN)" in rt)
    need("W2 a skipped model falls back", "else onDevice().let { d -> if (d.ok) fellBack(d, p.reason)" in rt)
    need("W2 a failed model falls back", "if (m.ok || !fallback) m else onDevice().let { d -> if (d.ok) fellBack(d" in rt)
    need("W2 every answer is recorded", "return record(type, chosen, r, clock())" in rt)
    need("W2 the image rule goes through routed", "routed(IMAGE, chosen, online, null" in rt)
    se = code(s["sound_engine"])
    need("W2 the sound rule goes through routed", "RecognitionRoutes.routed(RecognitionRoutes.SOUND, chosen, online, null, SoundConfig.fallback()" in se)
    need("W2 Camera's photos are routed", "RecognitionRoutes.image(ctx, request(ctx, route)) { engine.recognize(file, it) }" in code(s["cam_scanner"])
         and "RecognitionRoutes.image(ctx, identifyRequest(ctx))" in code(s["cam_scanner"]))
    cs = code(s["cam_sound"])
    need("W2 Camera's sounds are routed on the user's sound route", "SoundRouting.identify(route ?: SoundPrefs.route(ctx), RecognitionRoutes.online(ctx)" in cs)
    need("W2 Camera's model half is the image engine", "image.recognizeAsGiven(Bitmap.createBitmap(px, side, side" in cs)
    need("W2 Camera's on-device half is YAMNet", "onDevice = { engine.classify(pcm, rate) }" in cs)
    fl = code(s["calc_flow"])
    need("W2 Calc's sounds are routed", "RecognitionRoutes.routed(RecognitionRoutes.SOUND, chosen, RecognitionRoutes.online(ctx), hasToken, SoundConfig.fallback()" in fl)
    # #1801 the key lives in the AI-key owner engine: Calc asks it whether one is held (presence only) before the model.
    need("W2 Calc checks the token before the model", "val hasToken = if (chosen == SoundConfig.OPENROUTER) JevStore.token(ctx).present else null" in fl)
    need("W2 Calc's photos are routed", "RecognitionRoutes.image(ctx, request(ctx, route, context)) { engine(ctx).recognize(photo, it) }" in code(s["calc_vision"]))
    # W3
    st = code(s["cam_settings"])
    need("W3 Camera has a sound switch", "binding.soundRouteSetting.setOnClickListener { pickSoundRoute() }" in st and "SoundPrefs.set(this, routes[i].key)" in st)
    need("W3 Camera's sound switch lists the declared routes", "val routes = SoundConfig.routes().entries.toList()" in st)
    need("W3 Camera's layout has both items", 'android:id="@+id/sound_route_setting"' in s["cam_layout"] and 'android:id="@+id/image_route_setting"' in s["cam_layout"])
    sc = code(s["calc_screen"])
    need("W3 Calc's sound screen saves the pick and uses it", "SoundPrefs.set(ctx, id)" in sc and "SoundFlow.identifyRouted(ctx, pcm, cfg.sampleRate, r, knobs, route)" in sc)
    need("W3 Calc says which route answered", "RecognitionRoutes.answeredBy(id.result, SoundConfig.routes())" in sc)
    # W4
    ca = code(s["cam_api"])
    need("W4 Camera answers /image/route", '"route" -> imageRoute(app, q)' in ca and "RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx))" in ca)
    need("W4 Camera answers /sound/route", '"route" -> soundRoute(app, q)' in ca and 'put("routes", SoundRouting.status(ctx))' in ca)
    need("W4 Camera refuses an undeclared route", "r !in SoundConfig.routes()" in ca and "r !in RecognitionConfig.routes()" in ca)
    sa, cc = code(s["calc_sound_api"]), code(s["calc_camera_api"])
    need("W4 Calc answers /sound/route", '"route" -> route(app, q)' in sa and 'put("routes", SoundRouting.status(ctx))' in sa)
    need("W4 Calc answers /image/route", '"route" -> route(app, q)' in cc and "RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx))" in cc)
    need("W4 Calc's classify says which route answered", '.put("fell_back", r.fellBack).put("reason", r.reason)' in sa)
    need("W4 the report has the active and the last route", '.put("active_route", active)' in rt and '.put("last", u?.let' in rt)
    return bad

print("── W1-W4 against the tree ──")
bad = checks(src)
for f in bad:
    print("  FAIL  " + f)
print("  %d failure(s)" % len(bad))

MUTATIONS = [
    ("image-default-ml", "image_decl", '"default_route": "openrouter"', '"default_route": "ml"'),
    ("sound-default-ml", "sound_decl", '"default_route": "openrouter"', '"default_route": "ml"'),
    ("label-drift", "sound_decl", '"openrouter": "Model (Jev)"', '"openrouter": "OpenRouter"'),
    ("no-fallback", "sound_decl", '"fallback_to_ml": true', '"fallback_to_ml": false'),
    ("offline-asked", "routes", "online == false -> Plan(false, OFFLINE)", "online == false -> Plan(true, OFFLINE)"),
    ("tokenless-asked", "routes", "hasToken == false -> Plan(false, NO_TOKEN)", "hasToken == false -> Plan(true, NO_TOKEN)"),
    ("never-falls-back", "routes", "if (m.ok || !fallback) m else onDevice().let { d -> if (d.ok) fellBack(d", "if (true) m else fellBack(onDevice()"),
    ("unrecorded", "routes", "return record(type, chosen, r, clock())", "return r"),
    ("cam-photo-direct", "cam_scanner", "RecognitionRoutes.image(ctx, request(ctx, route)) { engine.recognize(file, it) }", "engine.recognize(file, request(ctx, route))"),
    ("cam-sound-fixed", "cam_sound", "SoundRouting.identify(route ?: SoundPrefs.route(ctx)", "SoundRouting.identify(route ?: SoundConfig.ML"),
    ("calc-no-token-check", "calc_flow", "JevStore.token(ctx).present", "true"),
    ("calc-photo-direct", "calc_vision", "RecognitionRoutes.image(ctx, request(ctx, route, context)) { engine(ctx).recognize(photo, it) }", "engine(ctx).recognize(photo, request(ctx, route, context))"),
    ("cam-no-sound-switch", "cam_settings", "binding.soundRouteSetting.setOnClickListener { pickSoundRoute() }", "Unit"),
    ("cam-layout-gone", "cam_layout", 'android:id="@+id/sound_route_setting"', 'android:id="@+id/sound_route_gone"'),
    ("calc-route-ignored", "calc_screen", "SoundFlow.identifyRouted(ctx, pcm, cfg.sampleRate, r, knobs, route)", "SoundFlow.identifyRouted(ctx, pcm, cfg.sampleRate, r, knobs, SoundConfig.ML)"),
    ("calc-silent-route", "calc_screen", "RecognitionRoutes.answeredBy(id.result, SoundConfig.routes())", '""'),
    ("cam-sound-route-op", "cam_api", '"route" -> soundRoute(app, q)', '"routes" -> soundRoute(app, q)'),
    ("cam-any-route", "cam_api", "r !in SoundConfig.routes()", "false"),
    ("calc-sound-route-op", "calc_sound_api", '"route" -> route(app, q)', '"routed" -> route(app, q)'),
    ("calc-image-route-op", "calc_camera_api", '"route" -> route(app, q)', '"routed" -> route(app, q)'),
    ("no-last", "routes", '.put("last", u?.let', '.put("lost", u?.let'),
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
print("── W1-W4 + %d mutations: %d failure(s) ──" % (len(MUTATIONS), len(bad) + mut_fail))
sys.exit(1 if bad or mut_fail else 0)
PY
