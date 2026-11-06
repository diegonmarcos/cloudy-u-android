#!/usr/bin/env python3
"""Make the app's render views from the declared models (run from the repository root; idempotent).

Usage: python3 ad_Hardtech-CAD/tools/derive_models.py <originals-checkout>   (a checkout of the originals repo, LFS files pulled)

For every model of app/src/main/assets/cad/models.json:
  * format skp: read the original with OpenSKP (MIT, pure Python, no SketchUp SDK), write a GLB view (metres, Y-up, colours, no
    textures) into app/src/main/assets/cad/models/<id>.view.glb and an IFC4 file into data/derived/<id>.view.ifc;
  * a view that already exists in the originals repo (the boats' B-rep views): mirror it into the same assets folder, hash-checked;
and record the provenance (tool, version, input hash) in the model's `view`, `derived` and `conversion_log`. The measured facts of
every GLB view (vertices, triangles, bounding box) are written from the file itself. The original stays the source of truth.
"""
import datetime, hashlib, json, os, shutil, struct, sys

APP = "ad_Hardtech-CAD"
MANIFEST = APP + "/app/src/main/assets/cad/models.json"
ASSET_DIR = APP + "/app/src/main/assets/"
VIEW_DIR = "cad/models/"
DERIVED_DIR = APP + "/data/derived/"
TOOL = "ad_Hardtech-CAD/tools/derive_models.py with openskp %s (MIT, https://github.com/iamahsanmehmood/openskp)"
MIRROR_TOOL = "mirrored from the originals repo, hash-checked by ad_Hardtech-CAD/tools/derive_models.py"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def glb_json(path):
    d = open(path, "rb").read()
    if d[:4] != b"glTF":
        raise SystemExit("%s is not a GLB (an LFS pointer? run git lfs pull)" % path)
    n, t = struct.unpack("<II", d[12:20])
    if t != 0x4E4F534A:
        raise SystemExit("%s: first chunk is not JSON" % path)
    return d, n, json.loads(d[20:20 + n])


def to_metres(path):
    """openskp writes millimetres; glTF is metres. Wrap the scene's root nodes in one node scaled 0.001 (JSON chunk only)."""
    d, n, j = glb_json(path)
    scene = j["scenes"][j.get("scene", 0)]
    j["nodes"].append({"name": "units_mm_to_m", "scale": [0.001, 0.001, 0.001], "children": list(scene["nodes"])})
    scene["nodes"] = [len(j["nodes"]) - 1]
    body = json.dumps(j, separators=(",", ":")).encode()
    body += b" " * ((4 - len(body) % 4) % 4)
    rest = d[20 + n:]
    open(path, "wb").write(b"glTF" + struct.pack("<II", 2, 12 + 8 + len(body) + len(rest)) + struct.pack("<II", len(body), 0x4E4F534A) + body + rest)


def measure(path):
    """Vertices, triangles (file counts, instances not multiplied) and the world bounding box of a GLB."""
    import trimesh
    _, _, j = glb_json(path)
    verts = tris = 0
    for m in j.get("meshes", []):
        for p in m["primitives"]:
            if p.get("mode", 4) != 4:
                continue
            pos = j["accessors"][p["attributes"]["POSITION"]]["count"]
            verts += pos
            tris += (j["accessors"][p["indices"]]["count"] if "indices" in p else pos) // 3
    b = trimesh.load(path, force="scene").bounds
    return verts, tris, [round(float(x), 4) for x in b[0]], [round(float(x), 4) for x in b[1]]


def asset_ref(m, ext):
    return ASSET_DIR + VIEW_DIR + "%s.view.%s" % (m["id"], ext)


def up_to_date_skp(m, version):
    """The view exists, matches its recorded hash, was made by this tool version from this very input."""
    v = m.get("view")
    return bool(v and os.path.isfile(ASSET_DIR + v.get("asset", "")) and sha256(ASSET_DIR + v["asset"]) == v["sha256"]
                and v.get("tool") == TOOL % version and m.get("conversion_log") and m["sha256"] in m["conversion_log"][0]
                and m.get("derived") and os.path.isfile(m["derived"][0]["path"]))


def derive_skp(m, src, version):
    import openskp
    from openskp.export import glb, ifc
    orig = os.path.join(src, m["path"])
    if not os.path.isfile(orig) or os.path.getsize(orig) < 1000 or sha256(orig) != m["sha256"]:
        raise SystemExit("%s: missing, an LFS pointer or not the declared file (declared sha256 %s)" % (orig, m["sha256"]))
    view_path = asset_ref(m, "glb")
    ifc_path = DERIVED_DIR + "%s.view.ifc" % m["id"]
    os.makedirs(os.path.dirname(view_path), exist_ok=True)
    os.makedirs(os.path.dirname(ifc_path), exist_ok=True)
    skp = openskp.SkpFile.open(orig)
    model = skp.parse()
    glb.export(skp, view_path)
    to_metres(view_path)
    for extra in ("_metadata.json", "_thumbnail.png"):   # side files openskp writes next to the GLB: not artifacts of ours
        side = view_path[:-4] + extra
        if os.path.exists(side):
            os.remove(side)
    ifc.export(skp.build_scene(), ifc_path)
    tool = TOOL % version
    m["view"] = {"asset": VIEW_DIR + "%s.view.glb" % m["id"], "format": "glb", "size": os.path.getsize(view_path), "sha256": sha256(view_path), "tool": tool}
    m["derived"] = [{"path": ifc_path, "format": "ifc", "size": os.path.getsize(ifc_path), "sha256": sha256(ifc_path), "tool": tool}]
    m.pop("conversion", None)
    m["units"] = "m"
    m["material_count"] = len(model.materials)
    m["materials"] = [str(getattr(x, "name", x)) for x in model.materials]
    m["layer_count"] = len(model.layers)
    m["layers"] = [str(getattr(x, "name", x)) for x in model.layers]
    m["component_count"] = len(model.definitions)
    m["conversion_log"] = [
        "input %s sha256 %s (%d bytes), SketchUp 2021 file" % (m["path"], m["sha256"], m["size"]),
        "openskp %s parsed it: %d definitions, %d layers, %d materials" % (version, len(model.definitions), len(model.layers), len(model.materials)),
        "glb.export(skp, view) wrote the GLB; wrapped in a 0.001 scale node (openskp writes millimetres, glTF is metres); colours, no textures",
        "ifc.export(skp.build_scene(), ifc) wrote the IFC4 file (meshes, no BIM semantics)",
    ]
    m["updated"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")


def mirror(m, src):
    """A view that exists in the originals repo: copy it next to the app's assets, refusing a file that is not the declared one."""
    v = m["view"]
    dst = asset_ref(m, "glb")
    if os.path.isfile(dst) and sha256(dst) == v["sha256"]:
        return
    srcf = os.path.join(src, v["path"])
    if not os.path.isfile(srcf) or sha256(srcf) != v["sha256"]:
        raise SystemExit("%s: missing, an LFS pointer or not the declared view (declared sha256 %s)" % (srcf, v["sha256"]))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(srcf, dst)
    v["tool"] = (v.get("tool", "") + "; " + MIRROR_TOOL).strip("; ")


def main():
    from importlib.metadata import version as pkg_version
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = sys.argv[1]
    version = pkg_version("openskp")
    doc = json.load(open(MANIFEST))
    for m in doc["models"]:
        v = m.get("view")
        if m["format"].lower() == "skp":
            if not up_to_date_skp(m, version):
                derive_skp(m, src, version)
        elif v and v["format"] == "glb" and v.get("path"):
            mirror(m, src)
        else:
            continue
        v = m["view"]
        v["asset"] = VIEW_DIR + "%s.view.glb" % m["id"]
        verts, tris, lo, hi = measure(ASSET_DIR + v["asset"])
        m["stats"] = {"vertices": verts, "triangles": tris}
        v["vertices"], v["triangles"] = verts, tris
        v["size"] = os.path.getsize(ASSET_DIR + v["asset"])
        m["bbox"] = {"min": lo, "max": hi}
    with open(MANIFEST, "w") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("models.json refreshed:", ", ".join(m["id"] for m in doc["models"]))


if __name__ == "__main__":
    sys.exit(main())
