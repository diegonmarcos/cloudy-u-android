#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #798 — the models the ML engines ship answer what their goldens say,     ║
# ║ through the REAL TFLite runtime, and are what their pins declare         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The TFLite interpreter has no desktop JVM build on Maven, so the engines' JVM suites
# (ClassifierTest, DetectorTest) cannot run a model: they replay its recorded answers and
# read its label list. THIS tester is where the model itself runs — LiteRT's own runtime
# (ai-edge-litert, the same kernels the Android interpreter carries) on the very files the
# engines ship, fetched from each module's data/models.json and refused on a sha256 or size
# mismatch, exactly as ../../libs/ml-models.gradle refuses them.
#
# Modules are DISCOVERED, never listed: every module under ab_cloud-libs-shared/libs with a
# data/models.json is checked, with the goldens in its src/test/resources/golden/goldens.json.
#
#   G1  each pinned model downloads to its declared sha256 and byte count, and declares a
#       licence and its source.
#   G2  the model's real input and output tensors are the shape and dtype the pin declares
#       (the engine's BuildConfig is baked from those numbers), and its metadata zip holds
#       `labels_entry` with exactly `classes` names.
#   G3  every golden image's top-N classes include one of the declared ones.
#   G4  every golden clip's top class is one of the declared ones (16-bit quantised, as the
#       clip reaches the engine).
#   G5  the replay golden the JVM suite stands on: the clip regenerated, cut into windows by
#       the engine's own rule (libs:sound-tags Signal.windows), and every window's scores
#       equal to the recorded ones within the tolerance — so the JVM golden cannot drift
#       from the model it stands for.
#   G6  #1805 a `kind: detector` pin (the scene method's object detectors): its input and EVERY output tensor are
#       the declared shapes and dtypes (order-free), its labels (`labels_entry` in the metadata zip, or the
#       pin's own `labels` list) number `classes`, and — for a `litert-yolo` model, whose decode this tester
#       replays (letterbox, quantise, best class per anchor) — every golden image's best detection is one of the
#       declared labels. A `mediapipe` model's detections are MediaPipe's own decode and are proven on the
#       emulator (ac_cloud-camera/test/emulator-camera-scene.sh); here its file, tensors and labels are.
#   MUT each property broken on purpose (audio fed unnormalised, labels off by one, a wrong
#       declared shape, a recorded score drifted, a wrong pin) turns the check red.
#
# NEEDS THE NETWORK (pip and the model URLs) and python3. Without either it FAILS: a
# golden check that could not run is unrun, not passing.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# Pinned so a runtime upgrade is a reviewed edit, not a silent change of the golden's judge.
RUNTIME="ai-edge-litert==2.2.0 numpy==2.5.3 pillow==12.3.0"
python3 -m venv "$WORK/v" >/dev/null 2>&1 && "$WORK/v/bin/pip" install -q $RUNTIME >/dev/null 2>&1 \
    || { echo "ERROR could not install the TFLite runtime ($RUNTIME) — this tester is unrun, not passing"; exit 1; }
PY="$WORK/v/bin/python"

# goldens <libs root> [mutation] : every check over every module with a data/models.json
goldens() {
    MUTATE="${2:-}" TF_CPP_MIN_LOG_LEVEL=3 "$PY" - "$1" "$WORK/cache" <<'PYTHON' 2>&1 | grep -v "^INFO: Created TensorFlow Lite"
import hashlib, io, json, os, sys, urllib.request, zipfile
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

libs, cache = sys.argv[1], sys.argv[2]
mutate = os.environ.get("MUTATE", "")
os.makedirs(cache, exist_ok=True)
bad = 0
def no(msg):
    global bad
    print("    " + msg); bad = 1

def fetch(m):
    path = os.path.join(cache, m["sha256"] + ".tflite")
    if not os.path.isfile(path):
        with urllib.request.urlopen(m["url"], timeout=120) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.replace(path + ".part", path)
    data = open(path, "rb").read()
    want = m["sha256"] if mutate != "sha" else "0" * 64
    if hashlib.sha256(data).hexdigest() != want or len(data) != m["bytes"]:
        return None, path
    return data, path

def labels_of(data, entry, inline=None):
    if inline is not None:
        names = list(inline)
    else:
        z = zipfile.ZipFile(io.BytesIO(data))
        names = [l.strip() for l in z.read(entry).decode("utf-8").splitlines() if l.strip()]
    return names[1:] + names[:1] if mutate == "label_shift" else names

def tensors(details):
    return sorted((list(map(int, d["shape"])), np.dtype(d["dtype"]).name) for d in details)

def yolo_best(interp, img_path, names, pad):
    # The engine's own YOLO path (SceneOnDevice.Yolo + SceneMath.decodeYolo): letterbox into the square on grey `pad`,
    # quantise as the input declares, NCHW or NHWC by its shape, best class per anchor.
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    nchw = inp["shape"][1] == 3
    side = int(inp["shape"][2] if nchw else inp["shape"][1])
    im = Image.open(img_path).convert("RGB"); w, h = im.size; s = min(side / w, side / h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    canvas = Image.new("RGB", (side, side), (pad, pad, pad)); canvas.paste(im.resize((nw, nh), Image.BILINEAR), ((side - nw) // 2, (side - nh) // 2))
    x = np.asarray(canvas, np.float32) / 255.0
    if nchw: x = x.transpose(2, 0, 1)
    scale, zero = inp["quantization"]
    dt = np.dtype(inp["dtype"])
    if dt.kind in "iu":
        info = np.iinfo(dt)
        x = np.clip(np.round(x / scale + zero), info.min, info.max).astype(dt)
    interp.set_tensor(inp["index"], x[None].astype(dt))
    interp.invoke()
    y = interp.get_tensor(out["index"]).astype(np.float64)
    oscale, ozero = out["quantization"]
    if oscale: y = (y - ozero) * oscale
    y = y[0]
    if y.shape[0] != len(names) + 4: y = y.T
    scores = y[4:]
    if scores.min() < 0 or scores.max() > 1: scores = 1 / (1 + np.exp(-scores))
    best = scores.max(axis=0); cls = scores.argmax(axis=0); a = int(np.argmax(best))
    return names[int(cls[a])], float(best[a])

def run(interp, x):
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    y = interp.get_tensor(out["index"]).astype(np.float64)
    scale, zero = out["quantization"]
    return ((y - zero) * scale if scale else y).reshape(-1)

def clip(parts, rate):
    xs = []
    for p in parts:
        if "tone" in p:
            f, a, s = p["tone"]; n = int(round(s * rate)); i = np.arange(n)
            xs.append(a * np.sin(2 * np.pi * f * i / rate))
        elif "silence" in p:
            xs.append(np.zeros(int(round(p["silence"] * rate))))
        elif "noise" in p:
            a, s, seed = p["noise"]
            xs.append(np.clip(a * np.random.default_rng(seed).standard_normal(int(round(s * rate))), -1, 1))
        else:
            raise ValueError("unknown clip part %r" % p)
    x = np.concatenate(xs)
    q = np.round(x * 32767).astype(np.int16)
    # the engine's input is 16-bit PCM scaled into [-1, 1]; the mutation feeds the raw integers
    return q.astype(np.float32) if mutate == "unnormalised" else (q.astype(np.float32) / 32768.0).astype(np.float32)

def windows(x, size, hop):
    # libs:sound-tags Signal.windows: a window is taken while half of it is audio, the rest zero-padded
    out, start = [], 0
    while True:
        w = np.zeros(size, np.float32); seg = x[start:start + size]; w[:len(seg)] = seg
        out.append((start, w)); start += hop
        if not (start + size // 2 < len(x)):
            return out

modules = sorted(d for d in os.listdir(libs) if os.path.isfile(os.path.join(libs, d, "data", "models.json")))
if not modules:
    no("no module declares data/models.json — this tester checked nothing")
checked = 0
for module in modules:
    pin = json.load(open(os.path.join(libs, module, "data", "models.json")))["models"]
    gpath = os.path.join(libs, module, "src", "test", "resources", "golden", "goldens.json")
    gold = json.load(open(gpath)) if os.path.isfile(gpath) else {}
    if not gold:
        no("%s: no src/test/resources/golden/goldens.json — its models answer nothing anyone checked" % module)
    for mid, m in sorted(pin.items()):
        where = "%s/%s" % (module, mid)
        # G1
        if not m.get("licence") or not m.get("licence_source"):
            no("G1 %s: no recorded licence and source" % where)
        data, path = fetch(m)
        if data is None:
            no("G1 %s: %s does not match the pinned sha256 %s / %d bytes" % (where, m["url"], m["sha256"][:12], m["bytes"])); continue
        interp = Interpreter(model_path=path); interp.allocate_tensors()
        if m.get("kind") == "detector":
            # G6
            dshape = list(m["input"]["shape"]) if mutate != "declared_shape" else [s + 1 for s in m["input"]["shape"]]
            if tensors(interp.get_input_details()[:1]) != [(dshape, m["input"]["dtype"])]:
                no("G6 %s: the model reads %s, the pin declares %s %s" % (where, tensors(interp.get_input_details()[:1]), dshape, m["input"]["dtype"]))
            want = sorted((list(o["shape"]), o["dtype"]) for o in m["outputs"])
            if tensors(interp.get_output_details()) != want:
                no("G6 %s: the model answers %s, the pin declares %s" % (where, tensors(interp.get_output_details()), want))
            names = labels_of(data, m.get("labels_entry"), m.get("labels") if not m.get("labels_entry") else None)
            if len(names) != m["classes"]:
                no("G6 %s: %d labels, the pin declares %d classes" % (where, len(names), m["classes"]))
            for g in (x for x in gold.get("detections", []) if x["model"] == mid):
                if m.get("runtime") != "litert-yolo":
                    print("    G6 %s: %s is %s's own decode, proven on the emulator" % (where, g["file"], m.get("runtime"))); continue
                label, p = yolo_best(interp, os.path.join(os.path.dirname(gpath), g["file"]), names, int(m["input"].get("letterbox_pad", 114)))
                if label not in g["includes"] or p < g["min_score"]:
                    no("G6 %s: %s's best detection is %r %.3f, not one of %s at %.2f" % (where, g["file"], label, p, g["includes"], g["min_score"]))
                else:
                    print("    G6 %s: %s -> %s %.3f" % (where, g["file"], label, p)); checked += 1
            continue
        # G2
        inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
        dshape = list(m["input"]["shape"]) if mutate != "declared_shape" else [s + 1 for s in m["input"]["shape"]]
        if list(inp["shape"]) != dshape or np.dtype(inp["dtype"]).name != m["input"]["dtype"]:
            no("G2 %s: the model reads %s %s, the pin declares %s %s" % (where, list(inp["shape"]), np.dtype(inp["dtype"]).name, dshape, m["input"]["dtype"]))
        if list(out["shape"]) != list(m["output"]["shape"]) or np.dtype(out["dtype"]).name != m["output"]["dtype"]:
            no("G2 %s: the model answers %s %s, the pin declares %s %s" % (where, list(out["shape"]), np.dtype(out["dtype"]).name, m["output"]["shape"], m["output"]["dtype"]))
        names = labels_of(data, m["labels_entry"])
        if len(names) != m["classes"]:
            no("G2 %s: %s names %d classes, the pin declares %d" % (where, m["labels_entry"], len(names), m["classes"]))
        # G3
        for g in (x for x in gold.get("images", []) if x["model"] == mid):
            h, w = inp["shape"][1], inp["shape"][2]
            img = Image.open(os.path.join(os.path.dirname(gpath), g["file"])).convert("RGB").resize((w, h))
            y = run(interp, np.asarray(img, dtype=inp["dtype"])[None])
            top = [names[i] for i in np.argsort(y)[::-1][:g["top"]]]
            if not set(top) & set(g["includes"]):
                no("G3 %s: %s's top %d is %s, none of %s" % (where, g["file"], g["top"], top, g["includes"]))
            else:
                print("    G3 %s: %s -> %s" % (where, g["file"], top)); checked += 1
        if "sample_rate" not in m["input"]:
            continue
        rate, size, hop = m["input"]["sample_rate"], m["input"]["shape"][0], m["hop_samples"]
        # G4
        for g in gold.get("clips", []):
            x = clip(g["parts"], rate)
            y = np.mean([run(interp, w) for _, w in windows(x, size, hop)], axis=0)
            best = names[int(np.argmax(y))]
            if best not in g["top1"]:
                no("G4 %s: clip %r is heard as %r (%.3f), not one of %s" % (where, g["name"], best, float(np.max(y)), g["top1"]))
            else:
                print("    G4 %s: %s -> %s %.3f" % (where, g["name"], best, float(np.max(y)))); checked += 1
        # G5
        r = gold.get("replay")
        if r:
            rec = json.load(open(os.path.join(os.path.dirname(gpath), r["file"])))
            wins = windows(clip(r["parts"], rate), size, hop)
            if [s for s, _ in wins] != rec["starts"] or rec["window"] != size or rec["hop"] != hop:
                no("G5 %s: the clip's windows start at %s, the recording at %s" % (where, [s for s, _ in wins], rec["starts"]))
            else:
                drift = 0.0
                for k, (_, w) in enumerate(wins):
                    recorded = np.array(rec["scores"][k]) + (0.01 if mutate == "score_drift" else 0.0)
                    drift = max(drift, float(np.max(np.abs(run(interp, w) - recorded))))
                if drift > r["tolerance"]:
                    no("G5 %s: the recorded scores in %s drift %.5f from the model's (tolerance %g) — re-record them" % (where, r["file"], drift, r["tolerance"]))
                else:
                    print("    G5 %s: %d recorded windows within %.1e of the model" % (where, len(wins), drift)); checked += 1
if checked == 0:
    no("no golden was checked")
sys.exit(1 if bad else 0)
PYTHON
    return "${PIPESTATUS[0]}"
}

FAILURES=0
echo "── G1-G5 the shipped models are the pinned ones and answer their goldens ──"
if goldens "$LIBS"; then echo "  PASS  every pinned model matches its pin and answers its golden images and clips through the real runtime"
else echo "  FAIL  a pinned model does not match its pin or misses a golden"; FAILURES=$((FAILURES + 1)); fi

# ══ MUT ════════════════════════════════════════════════════════════════════
MUTATIONS=0; HOLLOW=0
for m in unnormalised label_shift declared_shape score_drift sha; do
    MUTATIONS=$((MUTATIONS + 1))
    if goldens "$LIBS" "$m" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $m — still passes"; HOLLOW=$((HOLLOW + 1)); else echo "  MUT-RED     $m"; fi
done
echo "── $MUTATIONS mutations, $HOLLOW hollow ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-ml-goldens: all checks passed"; else echo "test-ml-goldens: $FAILURES check(s) FAILED"; exit 1; fi
