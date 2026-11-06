#!/usr/bin/env python3
"""The render guard of the model data (pure Python, no network, no build): every declared model has a renderable derivative.

Usage: python3 check_models.py <app dir>      (the Hardtech-CAD folder; reads app/src/main/assets/cad/models.json)
Prints one FAIL line per violation (exit 1) or one ok line per model:
  G1  the declaration is schema 2 with categories, and each model declares id, name, category (a declared one), software, format,
      path, source, licence, size, sha256, updated;
  G2  every model has a RENDERABLE derivative: a GLB view bundled in the APK assets (view.asset);
  G3  that file exists, is not an LFS pointer, and has the declared size and sha256;
  G4  it PARSES as glTF 2.0 binary (header, JSON chunk, BIN chunk, every buffer view inside it) and needs no extension the app's
      viewer cannot decode (Draco, meshopt, ... any extensionsRequired), and holds a NON-ZERO triangle count with finite, non-degenerate
      bounds; the declared stats (triangles, vertices) equal what the file holds;
  G5  every declared derivative (IFC) exists with its hash.
The Node job of the models workflow (verify-models.mjs, @gltf-transform/core) parses the same files with a second, independent parser.
"""
import hashlib, json, os, struct, sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def parse_glb(path):
    """-> (vertices, triangles, lo, hi) or raises ValueError with the reason."""
    d = open(path, "rb").read()
    if d[:24].startswith(b"version https://git-lfs"):
        raise ValueError("an LFS pointer, not the file")
    if len(d) < 28 or d[:4] != b"glTF":
        raise ValueError("not a GLB (bad magic)")
    ver, total = struct.unpack("<II", d[4:12])
    if ver != 2:
        raise ValueError("glTF version %d, not 2" % ver)
    if total != len(d):
        raise ValueError("header length %d but the file has %d bytes (truncated?)" % (total, len(d)))
    n, t = struct.unpack("<II", d[12:20])
    if t != 0x4E4F534A:
        raise ValueError("first chunk is not JSON")
    try:
        j = json.loads(d[20:20 + n])
    except Exception as e:
        raise ValueError("JSON chunk does not parse: %s" % e)
    off = 20 + n
    binlen = 0
    if off + 8 <= len(d):
        bl, bt = struct.unpack("<II", d[off:off + 8])
        if bt == 0x004E4942:
            binlen = bl
    req = j.get("extensionsRequired") or []
    if req:
        raise ValueError("requires %s, which the viewer cannot decode" % ", ".join(req))
    for k in ("KHR_draco_mesh_compression", "EXT_meshopt_compression"):
        if k in (j.get("extensionsUsed") or []):
            raise ValueError("uses %s, which the viewer does not decode" % k)
    views = j.get("bufferViews", [])
    for i, v in enumerate(views):
        if v.get("byteOffset", 0) + v["byteLength"] > binlen:
            raise ValueError("bufferView %d runs past the BIN chunk" % i)
    acc = j.get("accessors", [])
    verts = tris = 0
    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3
    for m in j.get("meshes", []):
        for p in m.get("primitives", []):
            if p.get("mode", 4) != 4:
                continue
            pa = acc[p["attributes"]["POSITION"]]
            verts += pa["count"]
            tris += (acc[p["indices"]]["count"] if "indices" in p else pa["count"]) // 3
            for k in range(3):
                a, b = pa.get("min", [None] * 3)[k], pa.get("max", [None] * 3)[k]
                if a is None or b is None or a != a or b != b or abs(a) == float("inf") or abs(b) == float("inf"):
                    raise ValueError("a POSITION accessor has no finite min/max")
                lo[k], hi[k] = min(lo[k], a), max(hi[k], b)
    if tris <= 0:
        raise ValueError("parses but holds %d triangles" % tris)
    if not any(hi[k] > lo[k] for k in range(3)):
        raise ValueError("every vertex is at one point")
    return verts, tris, lo, hi


def check(app):
    bad, ok = [], []
    p = os.path.join(app, "app/src/main/assets/cad/models.json")
    try:
        doc = json.load(open(p))
    except Exception as e:
        return ["FAIL G1 %s does not parse: %s" % (p, e)], ok
    assets = os.path.join(app, "app/src/main/assets")
    root = os.path.dirname(os.path.abspath(app))
    if doc.get("schema") != 2:
        bad.append("FAIL G1 models.json schema is %r, not 2" % doc.get("schema"))
    cats = {c.get("id") for c in doc.get("categories", [])}
    if not cats:
        bad.append("FAIL G1 models.json declares no categories")
    models = doc.get("models", [])
    if not models:
        bad.append("FAIL G1 models.json declares no model")
    for m in models:
        mid = m.get("id", "?")
        for k in ("id", "name", "category", "software", "format", "path", "source", "licence", "size", "sha256", "updated"):
            if m.get(k) in (None, ""):
                bad.append("FAIL G1 %s declares no %s" % (mid, k))
        if m.get("category") not in cats:
            bad.append("FAIL G1 %s: category %r is not a declared category" % (mid, m.get("category")))
        v = m.get("view")
        if not v or v.get("format") != "glb" or not v.get("asset"):
            bad.append("FAIL G2 %s has no renderable derivative: a GLB view bundled in the assets (view.asset); format %s, view %s" % (mid, m.get("format"), v))
            continue
        f = os.path.join(assets, v["asset"])
        if not os.path.isfile(f):
            bad.append("FAIL G3 %s: %s is missing" % (mid, f))
            continue
        if os.path.getsize(f) != v.get("size"):
            bad.append("FAIL G3 %s: %s is %d bytes, declared %s" % (mid, v["asset"], os.path.getsize(f), v.get("size")))
        if sha256(f) != v.get("sha256"):
            bad.append("FAIL G3 %s: %s sha256 is not the declared one" % (mid, v["asset"]))
        try:
            verts, tris, lo, hi = parse_glb(f)
        except ValueError as e:
            bad.append("FAIL G4 %s: %s %s" % (mid, v["asset"], e))
            continue
        st = m.get("stats") or {}
        if st.get("triangles") != tris or st.get("vertices") != verts:
            bad.append("FAIL G4 %s: declared stats %s, the file holds %d vertices and %d triangles" % (mid, st, verts, tris))
        for d in m.get("derived", []):
            q = os.path.join(root, d["path"]) if not os.path.isabs(d["path"]) else d["path"]
            if not os.path.isfile(q) or sha256(q) != d.get("sha256"):
                bad.append("FAIL G5 %s: derived %s is missing or not the declared file" % (mid, d["path"]))
        ok.append("ok %s: %s parses, %d triangles, %d vertices" % (mid, v["asset"], tris, verts))
    return bad, ok


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    bad, ok = check(sys.argv[1])
    print("\n".join(ok))
    if bad:
        print("\n".join(bad))
        sys.exit(1)
    print("every declared model has a renderable GLB that parses with a non-zero triangle count")
