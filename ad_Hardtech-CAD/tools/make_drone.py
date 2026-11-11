#!/usr/bin/env python3
"""Generates the civilian quadcopter model 'drone' (#1632): app/src/main/assets/cad/models/drone.view.glb. Pure standard library, deterministic.

A small consumer camera quadcopter (X frame, four motors with two-blade propellers, a camera gimbal, landing skids): no weapon, no
payload bay. The model is parametric (this script is its original), so the GLB is the whole model; it is about 100 KB.
Usage: python3 ad_Hardtech-CAD/tools/make_drone.py [--update-manifest]   (writes the GLB; --update-manifest refreshes models.json's drone entry)
Units: metres, Y up, about 0.25 m motor to motor (a 250-class consumer folding quadcopter).
"""
import hashlib, json, math, os, struct, sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
OUT = os.path.join(APP, "app/src/main/assets/cad/models/drone.view.glb")
MANIFEST = os.path.join(APP, "app/src/main/assets/cad/models.json")


class Mesh:
    def __init__(self):
        self.v, self.n, self.i = [], [], []

    def tri(self, a, b, c):
        ux, uy, uz = (b[k] - a[k] for k in range(3))
        vx, vy, vz = (c[k] - a[k] for k in range(3))
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        ln = math.sqrt(nx * nx + ny * ny + nz * nz)
        if ln < 1e-12:
            return
        base = len(self.v)
        for p in (a, b, c):
            self.v.append(p)
            self.n.append((nx / ln, ny / ln, nz / ln))
        self.i += [base, base + 1, base + 2]

    def quad(self, a, b, c, d):
        self.tri(a, b, c)
        self.tri(a, c, d)

    def box(self, cx, cy, cz, sx, sy, sz, yaw=0.0):
        hx, hy, hz = sx / 2, sy / 2, sz / 2
        cs, sn = math.cos(yaw), math.sin(yaw)

        def P(x, y, z):
            return (cx + x * cs + z * sn, cy + y, cz - x * sn + z * cs)
        c = [P(-hx, -hy, -hz), P(hx, -hy, -hz), P(hx, hy, -hz), P(-hx, hy, -hz),
             P(-hx, -hy, hz), P(hx, -hy, hz), P(hx, hy, hz), P(-hx, hy, hz)]
        for f in ((0, 3, 2, 1), (4, 5, 6, 7), (0, 4, 7, 3), (1, 2, 6, 5), (3, 7, 6, 2), (0, 1, 5, 4)):
            self.quad(c[f[0]], c[f[1]], c[f[2]], c[f[3]])

    def cylinder(self, cx, cy, cz, r, h, seg=20):
        y0, y1 = cy - h / 2, cy + h / 2
        for k in range(seg):
            a0, a1 = 2 * math.pi * k / seg, 2 * math.pi * (k + 1) / seg
            p0 = (cx + r * math.cos(a0), cz + r * math.sin(a0))
            p1 = (cx + r * math.cos(a1), cz + r * math.sin(a1))
            self.quad((p0[0], y0, p0[1]), (p1[0], y0, p1[1]), (p1[0], y1, p1[1]), (p0[0], y1, p0[1]))
            self.tri((cx, y1, cz), (p0[0], y1, p0[1]), (p1[0], y1, p1[1]))
            self.tri((cx, y0, cz), (p1[0], y0, p1[1]), (p0[0], y0, p0[1]))

    def sphere(self, cx, cy, cz, r, rings=8, seg=14):
        def P(i, j):
            t, p = math.pi * i / rings, 2 * math.pi * j / seg
            return (cx + r * math.sin(t) * math.cos(p), cy + r * math.cos(t), cz + r * math.sin(t) * math.sin(p))
        for i in range(rings):
            for j in range(seg):
                a, b, c, d = P(i, j), P(i, j + 1), P(i + 1, j + 1), P(i + 1, j)
                self.tri(a, c, b)
                self.tri(a, d, c)

    def blade(self, cx, cy, cz, length, width, ang):
        """A flat two-blade propeller: a thin tapered bar through the motor axis."""
        cs, sn = math.cos(ang), math.sin(ang)
        hl, hw = length / 2, width / 2

        def P(x, z, y):
            return (cx + x * cs + z * sn, y, cz - x * sn + z * cs)
        for y, up in ((cy + 0.0015, True), (cy - 0.0015, False)):
            q = [P(-hl, -hw * 0.5, y), P(0, -hw, y), P(hl, -hw * 0.5, y), P(hl, hw * 0.5, y), P(0, hw, y), P(-hl, hw * 0.5, y)]
            for a, b, c in ((0, 1, 4), (0, 4, 5), (1, 2, 3), (1, 3, 4)):
                if up:
                    self.tri(q[a], q[b], q[c])
                else:
                    self.tri(q[a], q[c], q[b])


def build():
    parts = {k: Mesh() for k in ("shell", "arm", "motor", "prop", "gimbal", "lens", "skid", "led")}
    s, a, m, pr, g, ln, sk, led = (parts[k] for k in ("shell", "arm", "motor", "prop", "gimbal", "lens", "skid", "led"))
    # fuselage: an upper shell over a battery bay, a nose block
    s.box(0, 0.045, 0, 0.11, 0.032, 0.07)
    s.box(0, 0.025, 0, 0.09, 0.02, 0.06)
    s.box(0, 0.055, 0, 0.07, 0.014, 0.05)
    s.box(0.052, 0.04, 0, 0.026, 0.022, 0.05)
    led.box(0.067, 0.045, 0.016, 0.004, 0.004, 0.006)
    led.box(0.067, 0.045, -0.016, 0.004, 0.004, 0.006)
    mx = 0.0875
    for sx in (-1, 1):
        for sz in (-1, 1):
            x, z = sx * mx, sz * mx
            yaw = math.atan2(sz, sx)
            length = math.hypot(x, z)
            a.box(x / 2, 0.042, z / 2, length, 0.012, 0.016, yaw=-yaw)
            m.cylinder(x, 0.052, z, 0.014, 0.024)
            g.cylinder(x, 0.038, z, 0.007, 0.016, seg=10)
            pr.blade(x, 0.068, z, 0.125, 0.014, yaw + (0.0 if sx * sz > 0 else math.pi / 2))
            led.cylinder(x, 0.063, z, 0.004, 0.006, seg=8)
    # landing skids: two rails on four legs
    for sz in (-1, 1):
        sk.box(0, -0.032, sz * 0.05, 0.16, 0.006, 0.008)
        for sx in (-1, 1):
            sk.box(sx * 0.05, -0.014, sz * 0.05, 0.006, 0.040, 0.006)
    # camera gimbal under the nose: yoke, ball housing and lens
    g.box(0.05, 0.018, 0, 0.012, 0.016, 0.02)
    g.sphere(0.06, 0.0, 0, 0.018)
    ln.cylinder(0.078, 0.0, 0, 0.009, 0.006, seg=14)
    return parts


MATERIALS = {
    "shell": ("Shell white", [0.92, 0.93, 0.95, 1.0], 0.1, 0.55), "arm": ("Arm dark grey", [0.16, 0.17, 0.19, 1.0], 0.2, 0.6),
    "motor": ("Motor silver", [0.62, 0.64, 0.68, 1.0], 0.9, 0.3), "prop": ("Propeller black", [0.05, 0.05, 0.06, 1.0], 0.0, 0.7),
    "gimbal": ("Gimbal graphite", [0.1, 0.11, 0.13, 1.0], 0.3, 0.5), "lens": ("Lens glass", [0.02, 0.05, 0.12, 1.0], 0.6, 0.1),
    "skid": ("Skid grey", [0.3, 0.31, 0.34, 1.0], 0.1, 0.7), "led": ("Status LED green", [0.1, 0.9, 0.3, 1.0], 0.0, 0.4),
}


def pad(b, c=b"\x00"):
    return b + c * ((4 - len(b) % 4) % 4)


def glb(parts):
    binb, views, accs, prims, used = b"", [], [], [], []
    for k, (name, col, met, rough) in MATERIALS.items():
        me = parts[k]
        if not me.i:
            continue
        used.append({"name": name, "pbrMetallicRoughness": {"baseColorFactor": col, "metallicFactor": met, "roughnessFactor": rough}, "doubleSided": True})
        pos = b"".join(struct.pack("<3f", *p) for p in me.v)
        nor = b"".join(struct.pack("<3f", *p) for p in me.n)
        idx = b"".join(struct.pack("<I", i) for i in me.i)
        lo = [min(p[j] for p in me.v) for j in range(3)]
        hi = [max(p[j] for p in me.v) for j in range(3)]
        for data, tgt in ((pos, 34962), (nor, 34962), (idx, 34963)):
            views.append({"buffer": 0, "byteOffset": len(binb), "byteLength": len(data), "target": tgt})
            binb += pad(data)
        b = len(views) - 3
        accs += [{"bufferView": b, "componentType": 5126, "count": len(me.v), "type": "VEC3", "min": lo, "max": hi},
                 {"bufferView": b + 1, "componentType": 5126, "count": len(me.v), "type": "VEC3"},
                 {"bufferView": b + 2, "componentType": 5125, "count": len(me.i), "type": "SCALAR"}]
        prims.append({"attributes": {"POSITION": len(accs) - 3, "NORMAL": len(accs) - 2}, "indices": len(accs) - 1, "material": len(used) - 1, "mode": 4})
    doc = {"asset": {"version": "2.0", "generator": "ad_Hardtech-CAD/tools/make_drone.py"}, "scene": 0, "scenes": [{"nodes": [0]}],
           "nodes": [{"name": "Drone", "mesh": 0}], "meshes": [{"name": "Quadcopter", "primitives": prims}], "materials": used,
           "accessors": accs, "bufferViews": views, "buffers": [{"byteLength": len(binb)}]}
    js = pad(json.dumps(doc, separators=(",", ":")).encode(), b" ")
    total = 12 + 8 + len(js) + 8 + len(binb)
    out = struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<II", len(js), 0x4E4F534A) + js + struct.pack("<II", len(binb), 0x004E4942) + binb
    return out, doc


def main():
    parts = build()
    data, doc = glb(parts)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(data)
    verts = sum(len(p.v) for p in parts.values())
    tris = sum(len(p.i) // 3 for p in parts.values())
    lo = [min(x["min"][j] for x in doc["accessors"] if "min" in x) for j in range(3)]
    hi = [max(x["max"][j] for x in doc["accessors"] if "max" in x) for j in range(3)]
    sha = hashlib.sha256(data).hexdigest()
    print("drone.view.glb: %d bytes, %d vertices, %d triangles, sha256 %s" % (len(data), verts, tris, sha))
    if "--update-manifest" in sys.argv:
        d = json.load(open(MANIFEST))
        m = next(x for x in d["models"] if x["id"] == "drone")
        r = lambda v: round(v, 4)
        m["size"], m["sha256"] = len(data), sha
        m["stats"] = {"vertices": verts, "triangles": tris}
        m["bbox"] = {"min": [r(x) for x in lo], "max": [r(x) for x in hi]}
        m["material_count"] = len(doc["materials"])
        m["materials"] = [x["name"] for x in doc["materials"]]
        v = m["view"]
        v["size"], v["sha256"], v["vertices"], v["triangles"] = len(data), sha, verts, tris
        with open(MANIFEST, "w") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")


if __name__ == "__main__":
    main()
