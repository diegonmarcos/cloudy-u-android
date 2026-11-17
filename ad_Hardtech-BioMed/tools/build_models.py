#!/usr/bin/env python3
"""Hardtech-BioMed model builder (stdlib only).

Builds the three GLBs the app loads at RUNTIME (data/fleet/biomed/): body.glb, brain.glb (derived from BodyParts3D 4.0, DBCLS,
CC BY-SA 2.1 Japan: https://lifesciencedb.jp/bp3d/ ) and dna.glb (generated here, CC0). The BodyParts3D parts are read straight
from the public archive by HTTP range requests (only the needed members are fetched), decimated by vertex clustering and written
as glTF binary, one named node per anatomical group. Coordinates: BodyParts3D is mm, z superior, x patient-left, y posterior; the
GLB is metres, y up, +z anterior (the body faces the viewer).

usage: build_models.py OUTDIR [body|brain|dna ...]     (no network needed for dna)
"""
import collections, json, math, os, re, struct, sys, urllib.request, io, zipfile

BASE = "https://dbarchive.biosciencedbc.jp/data/bodyparts3d/LATEST/"
ZIP = BASE + "isa_BP3D_4.0_obj_99.zip"
META = ["partof_element_parts.txt", "isa_element_parts.txt"]


class Ranged(io.RawIOBase):
    def __init__(s, u):
        s.u, s.p = u, 0
        s.n = int(urllib.request.urlopen(urllib.request.Request(u, method="HEAD"), timeout=60).headers["Content-Length"])
    def seekable(s): return True
    def readable(s): return True
    def tell(s): return s.p
    def seek(s, o, w=0):
        s.p = o if w == 0 else (s.p + o if w == 1 else s.n + o); return s.p
    def read(s, k=-1):
        if k < 0: k = s.n - s.p
        k = min(k, s.n - s.p)
        if k <= 0: return b""
        for attempt in range(4):
            try:
                d = urllib.request.urlopen(urllib.request.Request(s.u, headers={"Range": "bytes=%d-%d" % (s.p, s.p + k - 1)}), timeout=120).read()
                s.p += len(d); return d
            except Exception:
                if attempt == 3: raise
    def readinto(s, b):
        d = s.read(len(b)); b[:len(d)] = d; return len(d)


def meta(cache):
    el, fjn = collections.defaultdict(set), collections.defaultdict(set)
    for fn in META:
        p = os.path.join(cache, fn)
        if not os.path.exists(p):
            open(p, "wb").write(urllib.request.urlopen(BASE + fn, timeout=120).read())
        for l in open(p, encoding="utf-8").read().splitlines()[1:]:
            c, n, f = l.split("\t"); el[n].add(f); fjn[f].add(n)
    return el, fjn


def side_of(n):
    if n.startswith("right ") or " right " in n: return "R"
    if n.startswith("left ") or " left " in n: return "L"
    return ""


def slug(n):
    return re.sub(r"[^a-z0-9]+", "_", re.sub(r"^(right|left) ", "", n)).strip("_")


# ---- geometry -------------------------------------------------------------------------------------------------------
def parse_obj(data):
    vs, fs = [], []
    for line in data.split(b"\n"):
        if line[:2] == b"v ":
            p = line.split(); vs.append((float(p[1]), float(p[2]), float(p[3])))
        elif line[:2] == b"f ":
            p = line.split()[1:]
            ix = [int(x.split(b"/")[0]) - 1 for x in p]
            for k in range(1, len(ix) - 1): fs.append((ix[0], ix[k], ix[k + 1]))
    return vs, fs


def cluster(vs, fs, res):
    """Vertex clustering to about res cells along the longest bbox side; returns (verts, tris)."""
    if not vs: return [], []
    mn = [min(v[i] for v in vs) for i in range(3)]; mx = [max(v[i] for v in vs) for i in range(3)]
    ext = max(mx[i] - mn[i] for i in range(3)) or 1.0
    cell = max(ext / res, 0.4)
    key = {}; acc = []; remap = [0] * len(vs)
    for i, v in enumerate(vs):
        k = (int((v[0] - mn[0]) / cell), int((v[1] - mn[1]) / cell), int((v[2] - mn[2]) / cell))
        j = key.get(k)
        if j is None:
            j = key[k] = len(acc); acc.append([0.0, 0.0, 0.0, 0])
        a = acc[j]; a[0] += v[0]; a[1] += v[1]; a[2] += v[2]; a[3] += 1; remap[i] = j
    out = [(a[0] / a[3], a[1] / a[3], a[2] / a[3]) for a in acc]
    seen = set(); tris = []
    for a, b, c in fs:
        a, b, c = remap[a], remap[b], remap[c]
        if a == b or b == c or a == c: continue
        t = (a, b, c); s = tuple(sorted(t))
        if s in seen: continue
        seen.add(s); tris.append(t)
    return out, tris


def to_gl(v):  # BodyParts3D mm -> glTF metres, y up, +z anterior
    return (v[0] / 1000.0, v[2] / 1000.0, -v[1] / 1000.0)


def normals(vs, tris):
    n = [[0.0, 0.0, 0.0] for _ in vs]
    for a, b, c in tris:
        pa, pb, pc = vs[a], vs[b], vs[c]
        u = (pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]); w = (pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2])
        f = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        for i in (a, b, c):
            n[i][0] += f[0]; n[i][1] += f[1]; n[i][2] += f[2]
    for x in n:
        l = math.sqrt(x[0] ** 2 + x[1] ** 2 + x[2] ** 2) or 1.0
        x[0] /= l; x[1] /= l; x[2] /= l
    return n


# ---- glTF writer ----------------------------------------------------------------------------------------------------
class Glb:
    def __init__(s, generator):
        s.nodes, s.meshes, s.mats, s.acc, s.views, s.bin = [], [], [], [], [], bytearray()
        s.gen = generator
    def _pad(s):
        while len(s.bin) % 4: s.bin.append(0)
    def _view(s, data, target):
        s._pad(); off = len(s.bin); s.bin += data
        s.views.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target}); return len(s.views) - 1
    def add(s, name, verts, tris, color, alpha=1.0, extras=None):
        if not tris: return False
        nrm = normals(verts, tris)
        xs = [v[0] for v in verts]; ys = [v[1] for v in verts]; zs = [v[2] for v in verts]
        pv = s._view(b"".join(struct.pack("<3f", *v) for v in verts), 34962)
        nv = s._view(b"".join(struct.pack("<3f", *v) for v in nrm), 34962)
        big = len(verts) > 65535
        iv = s._view(b"".join(struct.pack("<3I" if big else "<3H", *t) for t in tris), 34963)
        s.acc.append({"bufferView": pv, "componentType": 5126, "count": len(verts), "type": "VEC3", "min": [min(xs), min(ys), min(zs)], "max": [max(xs), max(ys), max(zs)]})
        s.acc.append({"bufferView": nv, "componentType": 5126, "count": len(verts), "type": "VEC3"})
        s.acc.append({"bufferView": iv, "componentType": 5125 if big else 5123, "count": len(tris) * 3, "type": "SCALAR"})
        a = len(s.acc) - 3
        mat = {"pbrMetallicRoughness": {"baseColorFactor": [color[0], color[1], color[2], alpha], "metallicFactor": 0.0, "roughnessFactor": 0.75}, "doubleSided": True}
        if alpha < 1.0: mat["alphaMode"] = "BLEND"
        s.mats.append(mat)
        s.meshes.append({"primitives": [{"attributes": {"POSITION": a, "NORMAL": a + 1}, "indices": a + 2, "material": len(s.mats) - 1}]})
        node = {"name": name, "mesh": len(s.meshes) - 1}
        if extras: node["extras"] = extras
        s.nodes.append(node); return True
    def write(s, path):
        s._pad()
        j = {"asset": {"version": "2.0", "generator": s.gen}, "scene": 0, "scenes": [{"nodes": list(range(len(s.nodes)))}], "nodes": s.nodes,
             "meshes": s.meshes, "materials": s.mats, "accessors": s.acc, "bufferViews": s.views, "buffers": [{"byteLength": len(s.bin)}]}
        js = json.dumps(j, separators=(",", ":")).encode()
        while len(js) % 4: js += b" "
        total = 12 + 8 + len(js) + 8 + len(s.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total)); f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
            f.write(struct.pack("<II", len(s.bin), 0x004E4942)); f.write(s.bin)
        return total


# ---- group specs ----------------------------------------------------------------------------------------------------
# (id, label, system, colour, res, name-regex or exact-name list, restrict-to-concept (files must belong to it), sided)
def _lin(c): return ((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92


def C(h):
    """A #rrggbb colour as the LINEAR factor glTF's baseColorFactor wants (the hex is sRGB)."""
    return tuple(round(_lin(int(h[i:i + 2], 16) / 255), 5) for i in (1, 3, 5))

BONE = C("#e6dcc3"); MUS = C("#b5483a"); ART = C("#d63b3b"); VEN = C("#3b6fd6")

BODY = []  # filled below; order = claim priority
def g(id, label, system, color, res, pat, within=None, sided=False, alpha=1.0, split_names=False):
    BODY.append(dict(id=id, label=label, system=system, color=color, res=res, pat=pat, within=within, sided=sided, alpha=alpha, split=split_names))

SK = "skeleton (in vivo)"
g("eyes", "Eyes", "sensory", C("#f2f2f2"), 8, r"^(right |left )eyeball$", None, sided=True)
g("skull", "Skull", "skeletal", BONE, 16, r"^(right |left )?(frontal bone|parietal bone|occipital bone|temporal bone|sphenoid bone|ethmoid bone|nasal bone|zygomatic bone|lacrimal bone|palatine bone|maxilla|vomer|mandible|inferior nasal concha|hyoid bone)$", SK)
g("vertebrae", "Vertebral column", "skeletal", BONE, 12, r"vertebra$|^sacrum$|^coccyx$|^sacral vertebra", SK)
g("disks", "Intervertebral disks", "skeletal", C("#9fb8c8"), 24, r"^intervertebral disk", SK)
g("ribs", "Ribs", "skeletal", BONE, 16, r"^(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth) rib$", SK, sided=True)
g("sternum", "Sternum", "skeletal", BONE, 22, r"sternum|manubrium|xiphoid", SK)
g("costal_cartilage", "Costal cartilage", "skeletal", C("#a9c4cf"), 20, r"cartilage", SK, sided=True)
g("clavicle", "Clavicle", "skeletal", BONE, 30, r"^(right |left )clavicle$", None, sided=True)
g("scapula", "Scapula", "skeletal", BONE, 22, r"^(right |left )scapula$", None, sided=True)
g("humerus", "Humerus", "skeletal", BONE, 24, r"^(right |left )humerus$", None, sided=True)
g("radius", "Radius", "skeletal", BONE, 22, r"^(right |left )radius$", None, sided=True)
g("ulna", "Ulna", "skeletal", BONE, 22, r"^(right |left )ulna$", None, sided=True)
g("hand_bones", "Hand bones", "skeletal", BONE, 14, r"^(right |left )(scaphoid|lunate|triquetral|pisiform|trapezium|trapezoid|capitate|hamate|(first|second|third|fourth|fifth) metacarpal bone|.*phalanx.*|.*sesamoid.*)$", None, sided=True)
g("hip_bone", "Hip bone", "skeletal", BONE, 26, r"^(right |left )hip bone$", None, sided=True)
g("femur", "Femur", "skeletal", BONE, 24, r"^(right |left )femur$", None, sided=True)
g("patella", "Patella", "skeletal", BONE, 18, r"^(right |left )patella$", None, sided=True)
g("tibia", "Tibia", "skeletal", BONE, 24, r"^(right |left )tibia$", None, sided=True)
g("fibula", "Fibula", "skeletal", BONE, 22, r"^(right |left )fibula$", None, sided=True)
g("foot_bones", "Foot bones", "skeletal", BONE, 16, r"^(right |left )(talus|calcaneus|navicular bone|cuboid bone|.*cuneiform bone|(first|second|third|fourth|fifth) metatarsal bone|.*phalanx.*|.*sesamoid.*)$", None, sided=True)
g("other_bones", "Other bones", "skeletal", BONE, 30, r"^skeleton \(in vivo\)$", SK, sided=False)

MUSCLES = """anconeus|brachialis|brachioradialis|coracobrachialis|digastric|extensor carpi radialis brevis|extensor carpi radialis longus|extensor carpi ulnaris|extensor digiti minimi|extensor digitorum|extensor digitorum longus|extensor hallucis brevis|extensor hallucis longus|extensor indicis|extensor pollicis brevis|extensor pollicis longus|external oblique|fibularis brevis|fibularis longus|fibularis tertius|flexor accessorius|flexor carpi radialis|flexor digitorum brevis|flexor digitorum longus|flexor digitorum profundus|flexor digitorum superficialis|flexor hallucis longus|flexor pollicis brevis|flexor pollicis longus|gemellus inferior|gemellus superior|gluteus maximus|gluteus medius|gluteus minimus|gracilis|iliacus|iliocostalis lumborum|iliocostalis thoracis|infraspinatus muscle|levator scapulae|longissimus thoracis|obturator externus|obturator internus|opponens pollicis|palmaris longus|pectineus|pectoralis major|pectoralis minor|piriformis|plantaris|platysma|popliteus|pronator quadratus|psoas major|quadratus femoris|rectus femoris|rhomboid major|rhomboid minor|sartorius|scalenus anterior|scalenus medius|scalenus posterior|semimembranosus|semitendinosus|serratus anterior|soleus|spinalis thoracis|splenius capitis|sternocleidomastoid|subclavius|subscapularis|supinator|supraspinatus|tensor fasciae latae|teres major|teres minor|tibialis anterior|tibialis posterior|transversus thoracis|vastus intermedius|vastus lateralis|vastus medialis|masseter|temporalis|trapezius|deltoid|biceps brachii|triceps brachii|latissimus dorsi|rectus abdominis|internal oblique|transversus abdominis|biceps femoris|gastrocnemius|adductor magnus|adductor longus|adductor brevis|erector spinae""".split("|")
MUS_RE = r"^(right |left )?(" + "|".join(re.escape(m) for m in MUSCLES) + r")$"
# individual muscles are generated one node per (muscle, side): handled specially (split_names)
g("m", "Muscle", "muscular", MUS, 14, MUS_RE, None, sided=True, split_names=True)

g("skin", "Skin", "integumentary", C("#e0b9a0"), 48, r"^skin$", None, alpha=0.22)
g("brain", "Brain", "nervous", C("#d9a3b5"), 10, r"^brain$", None)
g("spinal_cord", "Spinal cord", "nervous", C("#e9dd9a"), 24, r"^spinal cord$", None)
g("heart", "Heart", "cardiovascular", C("#c0392b"), 18, r"^heart$", None)
g("liver", "Liver", "digestive", C("#8b4a2b"), 18, r"^liver$", None)
g("lungs", "Lungs", "respiratory", C("#e8a3a0"), 14, r"^(right |left )lung$", None, sided=True)
g("aorta", "Aorta", "cardiovascular", ART, 30, r"^aorta$", None)
g("vena_cava", "Venae cavae", "cardiovascular", VEN, 28, r"^(inferior|superior) vena cava$", None)
g("arteries", "Systemic arteries", "cardiovascular", ART, 10, r"^systemic arterial tree$", None)
g("veins", "Systemic veins", "cardiovascular", VEN, 16, r"^systemic venous system$", None)
g("pulm_vessels", "Pulmonary vessels", "cardiovascular", C("#8e44ad"), 14, r"^pulmonary vascular system$", None)
g("portal", "Portal venous system", "cardiovascular", C("#5b5bd6"), 12, r"^portal venous system$", None)
g("trachea", "Trachea and bronchi", "respiratory", C("#b9d4e3"), 26, r"^(trachea|respiratory tract)$", None)
g("diaphragm", "Diaphragm", "respiratory", C("#b5483a"), 24, r"^diaphragm$", None)
g("esophagus", "Esophagus", "digestive", C("#d99a7a"), 24, r"^esophagus$", None)
g("stomach", "Stomach", "digestive", C("#e0a48a"), 28, r"^stomach$", None)
g("gallbladder", "Gallbladder", "digestive", C("#6b8e23"), 20, r"^gallbladder$", None)
g("pancreas", "Pancreas", "digestive", C("#e6c27a"), 28, r"^pancreas$", None)
g("small_intestine", "Small intestine", "digestive", C("#e8a89a"), 22, r"^small intestine$", None)
g("large_intestine", "Large intestine", "digestive", C("#c98f78"), 20, r"^large intestine$", None)
g("kidneys", "Kidneys", "urinary", C("#9b3d3d"), 30, r"^(right |left )kidney$", None, sided=True)
g("ureters", "Ureters", "urinary", C("#d6b84a"), 14, r"^(right |left )ureter$", None, sided=True)
g("urinary_bladder", "Urinary bladder", "urinary", C("#d6a44a"), 26, r"^urinary bladder$", None)
g("adrenals", "Adrenal glands", "endocrine", C("#d4a017"), 18, r"^(right |left )adrenal gland$", None, sided=True)
g("pituitary", "Pituitary gland", "endocrine", C("#d4a017"), 12, r"^pituitary gland$", None)
g("thymus", "Thymus", "immune", C("#59c3a7"), 24, r"^thymus$", None)
g("spleen", "Spleen", "immune", C("#7a3b8e"), 30, r"^spleen$", None)
g("prostate", "Prostate", "reproductive", C("#c97d9a"), 18, r"^prostate$", None)
g("testes", "Testes", "reproductive", C("#c97d9a"), 18, r"^(right |left )testis$", None, sided=True)
g("seminal", "Seminal vesicles and epididymis", "reproductive", C("#b86a88"), 18, r"^(right |left )(seminal vesicle|epididymis)$", None, sided=True)

BRAIN = []
def b(id, label, color, res, pat, sided=True):
    BRAIN.append(dict(id=id, label=label, system="brain", color=color, res=res, pat=pat, within=("brain" if id == "cerebrum_other" else None), sided=sided, alpha=1.0, split=False))
b("hippocampus", "Hippocampus", C("#f5a623"), 30, r"^(right |left )hippocampus$|^(right |left )hippocampal formation$")
b("amygdala", "Amygdala", C("#d0021b"), 24, r"^(right |left )amygdala$")
b("thalamus", "Thalamus", C("#7ed321"), 30, r"^(right |left )thalamus$")
b("hypothalamus", "Hypothalamus", C("#f8e71c"), 24, r"^(right |left )?hypothalamus$")
b("basal_ganglia", "Basal ganglia", C("#bd10e0"), 28, r"^(right |left )(caudate nucleus|putamen|globus pallidus)$")
b("corpus_callosum", "Corpus callosum", C("#f0f0f0"), 30, r"^corpus callosum$", sided=False)
b("insula", "Insula", C("#50e3c2"), 30, r"^(right |left )insula$")
b("ventricles", "Ventricles", C("#4a90e2"), 24, r"^(right |left )?(lateral|third|fourth) ventricle$|^ventricular system of brain$|^cerebral aqueduct$", sided=False)
b("frontal_lobe", "Frontal lobe", C("#4a90e2"), 50, r"^(right |left )frontal lobe$")
b("parietal_lobe", "Parietal lobe", C("#f5a623"), 50, r"^(right |left )parietal lobe$")
b("temporal_lobe", "Temporal lobe", C("#7ed321"), 50, r"^(right |left )temporal lobe$")
b("occipital_lobe", "Occipital lobe", C("#d0021b"), 50, r"^(right |left )occipital lobe$")
b("limbic_lobe", "Limbic lobe", C("#e0709a"), 40, r"^(right |left )limbic lobe$")
b("cerebellum", "Cerebellum", C("#9013fe"), 50, r"^cerebellum$", sided=False)
b("midbrain", "Midbrain", C("#f8a1a1"), 28, r"^midbrain$", sided=False)
b("pons", "Pons", C("#a1d8f8"), 28, r"^pons$", sided=False)
b("medulla", "Medulla oblongata", C("#a1f8c8"), 28, r"^medulla oblongata$", sided=False)
b("cerebrum_other", "Other cerebrum", C("#c9a0b0"), 40, r"^(right |left )cerebral hemisphere$|^brain$")


def build(specs, el, fjn, zf, out, gen, only_within=None):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    files = {i.filename.split("/")[-1][:-4]: i for i in zf.infolist() if i.filename.endswith(".obj")}
    claimed = set(); plan = []
    for sp in specs:  # phase 1: who owns which element file (no IO)
        rx = re.compile(sp["pat"]); names = [n for n in el if rx.search(n)]
        nodes = collections.OrderedDict()
        for n in sorted(names):
            fl = set(el[n])
            if sp["within"]: fl &= set(el[sp["within"]])
            fl = {f for f in fl if f in files and f not in claimed}
            if not fl: continue
            key = (slug(n), side_of(n)) if sp["split"] else ((sp["id"], side_of(n)) if sp["sided"] else (sp["id"], ""))
            nodes.setdefault(key, set()).update(fl)
        done = set()
        for (base, side), fl in nodes.items():
            fl = {f for f in fl if f not in done}
            if not fl: continue
            done |= fl; claimed |= fl
            plan.append((sp, base, side, sorted(fl)))
    print("planned", len(plan), "nodes,", len(claimed), "element files", flush=True)
    local = threading.local()
    def zf_here():
        if not hasattr(local, "z"): local.z = zipfile.ZipFile(io.BufferedReader(Ranged(ZIP), buffer_size=1 << 20))
        return local.z
    def work(item):
        sp, base, side, fl = item
        z = zf_here(); vs_all, tris_all = [], []
        for f in fl:
            vs, fs = parse_obj(z.read(files[f]))
            nv, nt = cluster(vs, fs, sp["res"])
            off = len(vs_all); vs_all += [to_gl(v) for v in nv]; tris_all += [(a + off, b_ + off, c + off) for a, b_, c in nt]
        return vs_all, tris_all
    glb = Glb(gen); stats = collections.Counter(); report = []
    with ThreadPoolExecutor(8) as ex:
        for item, (vs_all, tris_all) in zip(plan, ex.map(work, plan)):
            sp, base, side, fl = item
            nid = (sp["id"] + "." + base if sp["split"] else base) + ("." + side if side else "")
            label = sp["label"] if not sp["split"] else base.replace("_", " ").title()
            if glb.add(nid, vs_all, tris_all, sp["color"], sp["alpha"], {"label": label, "system": sp["system"], "side": side}):
                stats["nodes"] += 1; stats["tris"] += len(tris_all); report.append((nid, len(fl), len(tris_all)))
                print("node", nid, len(fl), "files", len(tris_all), "tris", flush=True)
    size = glb.write(out)
    print(out, size, "bytes", stats["nodes"], "nodes", stats["tris"], "triangles", flush=True)
    return report


# ---- DNA (generated, CC0) -------------------------------------------------------------------------------------------
def tube(p0, p1, r, seg=8):
    d = [p1[i] - p0[i] for i in range(3)]; L = math.sqrt(sum(x * x for x in d)) or 1e-9; d = [x / L for x in d]
    a = (1, 0, 0) if abs(d[0]) < 0.9 else (0, 1, 0)
    u = [d[1] * a[2] - d[2] * a[1], d[2] * a[0] - d[0] * a[2], d[0] * a[1] - d[1] * a[0]]; ul = math.sqrt(sum(x * x for x in u)); u = [x / ul for x in u]
    w = [d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0]]
    vs, ts = [], []
    for e in (p0, p1):
        for k in range(seg):
            t = 2 * math.pi * k / seg
            vs.append(tuple(e[i] + r * (math.cos(t) * u[i] + math.sin(t) * w[i]) for i in range(3)))
    for k in range(seg):
        k2 = (k + 1) % seg
        ts += [(k, k2, seg + k), (k2, seg + k2, seg + k)]
    return vs, ts


def sphere(c, r, la=6, lo=10):
    vs, ts = [], []
    for i in range(la + 1):
        th = math.pi * i / la
        for j in range(lo):
            ph = 2 * math.pi * j / lo
            vs.append((c[0] + r * math.sin(th) * math.cos(ph), c[1] + r * math.cos(th), c[2] + r * math.sin(th) * math.sin(ph)))
    for i in range(la):
        for j in range(lo):
            a = i * lo + j; b_ = i * lo + (j + 1) % lo
            ts += [(a, b_, a + lo), (b_, b_ + lo, a + lo)]
    return vs, ts


def merge(parts):
    vs, ts = [], []
    for pv, pt in parts:
        o = len(vs); vs += pv; ts += [(a + o, b_ + o, c + o) for a, b_, c in pt]
    return vs, ts


def dna(out):
    """A B-DNA double helix, 30 base pairs, 3 turns (about 10 pairs per turn), schematic proportions (1 unit = 1 nm / 10)."""
    n, R, rise, per_turn = 30, 0.10, 0.034, 10.0
    glb = Glb("hardtech-biomed dna generator (CC0)")
    strands = ([], []); sugar = ([], []); pairs = {"A": [], "T": [], "G": [], "C": []}
    seq = "ATGCGTACGTTAGCCGATAGCTAGGCTAAT"[:n]; comp = {"A": "T", "T": "A", "G": "C", "C": "G"}
    pts = [[], []]
    for i in range(n):
        ang = 2 * math.pi * i / per_turn; y = (i - n / 2) * rise
        p1 = (R * math.cos(ang), y, R * math.sin(ang)); p2 = (R * math.cos(ang + math.pi * 0.85), y, R * math.sin(ang + math.pi * 0.85))
        pts[0].append(p1); pts[1].append(p2)
        sugar[0].append(sphere(p1, 0.017)); sugar[1].append(sphere(p2, 0.017))
        mid = tuple((p1[k] + p2[k]) / 2 for k in range(3)); b1 = seq[i]; b2 = comp[b1]
        pairs[b1].append(tube(p1, mid, 0.008)); pairs[b2].append(tube(mid, p2, 0.008))
    for s in (0, 1):
        for i in range(n - 1): strands[s].append(tube(pts[s][i], pts[s][i + 1], 0.007))
    for nm, lab, col, parts in (("backbone_1", "Backbone strand 1", C("#4a90e2"), strands[0] + sugar[0]), ("backbone_2", "Backbone strand 2", C("#9013fe"), strands[1] + sugar[1]),
                                ("base_A", "Adenine (A)", C("#2ecc71"), pairs["A"]), ("base_T", "Thymine (T)", C("#e74c3c"), pairs["T"]),
                                ("base_G", "Guanine (G)", C("#f1c40f"), pairs["G"]), ("base_C", "Cytosine (C)", C("#e67e22"), pairs["C"])):
        vs, ts = merge(parts)
        glb.add(nm, vs, ts, col, 1.0, {"label": lab, "system": "genetic", "side": ""})
    size = glb.write(out); print(out, size, "bytes")


if __name__ == "__main__":
    out = sys.argv[1]; what = sys.argv[2:] or ["dna", "brain", "body"]
    os.makedirs(out, exist_ok=True)
    if "dna" in what: dna(os.path.join(out, "dna.glb"))
    if "brain" in what or "body" in what:
        el, fjn = meta(out)
        zf = zipfile.ZipFile(io.BufferedReader(Ranged(ZIP), buffer_size=1 << 20))
        if "brain" in what: build(BRAIN, el, fjn, zf, os.path.join(out, "brain.glb"), "hardtech-biomed from BodyParts3D 4.0 (CC BY-SA 2.1 JP), DBCLS")
        if "body" in what: build(BODY, el, fjn, zf, os.path.join(out, "body.glb"), "hardtech-biomed from BodyParts3D 4.0 (CC BY-SA 2.1 JP), DBCLS")
