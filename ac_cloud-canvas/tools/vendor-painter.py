#!/usr/bin/env python3
"""
vendor-painter — #1808 the Painter page's offline libraries, vendored and pinned.

  vendor-painter.py fetch [APP_DIR]   re-vendor Three.js r128 (+ OrbitControls) and the Inter font into app/src/main/assets/painter/vendor
  vendor-painter.py check [APP_DIR]   offline: the copy matches PIN.json (every pinned file present with its sha256, nothing unpinned
                                      in vendor/ beside PIN.json)

Painter (Cloud Canvas > Painter) is a Three.js r128 page. The library is not fetched from a CDN at run time: the page must work with
no network, and the build must not depend on what a registry serves that minute (the publish gate's source identity). The copy is
taken from the npm tarballs (integrity-checked), byte for byte, with every file's sha256 recorded in PIN.json.

  vendor/three/three.min.js          three@0.128.0 build/three.min.js (MIT)
  vendor/three/OrbitControls.js      three@0.128.0 examples/js/controls/OrbitControls.js (MIT; the classic script that sets THREE.OrbitControls)
  vendor/three/LICENSE               three@0.128.0 LICENSE
  vendor/inter/inter-latin-*.woff2   @fontsource/inter 5.3.0, latin subset, weights 400/500/600/700 (SIL OFL 1.1)
  vendor/inter/LICENSE               @fontsource/inter 5.3.0 LICENSE (OFL 1.1)
"""
import base64, hashlib, io, json, os, ssl, sys, tarfile, urllib.request

THREE = ("three", "0.128.0")
INTER = ("@fontsource/inter", "5.3.0")
WEIGHTS = (400, 500, 600, 700)


def sha(b):
    return hashlib.sha256(b).hexdigest()


def get(url):
    ctx = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE") or None)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vendor-painter"}), timeout=120, context=ctx) as r:
        return r.read()


def tarball(pkg, version, want_licence):
    meta = json.loads(get("https://registry.npmjs.org/%s/%s" % (pkg.replace("/", "%2F"), version)))
    assert meta.get("license") == want_licence, "%s %s is no longer %s: %s" % (pkg, version, want_licence, meta.get("license"))
    tgz = get(meta["dist"]["tarball"])
    assert hashlib.sha512(tgz).digest() == base64.b64decode(meta["dist"]["integrity"].split("-", 1)[1]), "tarball integrity " + pkg
    return meta["dist"], tarfile.open(fileobj=io.BytesIO(tgz))


def out_dir(app):
    return os.path.join(app, "app/src/main/assets/painter/vendor")


def fetch(out):
    files = {}

    def put(rel, data, url):
        p = os.path.join(out, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "wb").write(data)
        files[rel] = {"sha256": sha(data), "url": url}

    dist, t = tarball(THREE[0], THREE[1], "MIT")
    with t:
        for src, rel in (("package/build/three.min.js", "three/three.min.js"),
                         ("package/examples/js/controls/OrbitControls.js", "three/OrbitControls.js"),
                         ("package/LICENSE", "three/LICENSE")):
            put(rel, t.extractfile(src).read(), dist["tarball"] + "#" + src)
    three_dist = dist
    dist, t = tarball(INTER[0], INTER[1], "OFL-1.1")
    with t:
        for w in WEIGHTS:
            src = "package/files/inter-latin-%d-normal.woff2" % w
            put("inter/inter-latin-%d-normal.woff2" % w, t.extractfile(src).read(), dist["tarball"] + "#" + src)
        put("inter/LICENSE", t.extractfile("package/LICENSE").read(), dist["tarball"] + "#package/LICENSE")
    pin = {
        "_doc": "#1808 the pinned offline libraries of Cloud Canvas > Painter. Written by ac_cloud-canvas/tools/vendor-painter.py fetch, checked by its check mode (test-painter.sh). Do not edit by hand: re-run fetch.",
        "three": {"package": THREE[0], "version": THREE[1], "revision": "r128", "tarball": three_dist["tarball"], "integrity": three_dist["integrity"], "licence": "MIT"},
        "inter": {"package": INTER[0], "version": INTER[1], "tarball": dist["tarball"], "integrity": dist["integrity"], "licence": "OFL-1.1", "weights": list(WEIGHTS), "subset": "latin"},
        "files": files,
    }
    open(os.path.join(out, "PIN.json"), "w").write(json.dumps(pin, indent=1) + "\n")
    print("vendored three %s and inter %s: %d files" % (THREE[1], INTER[1], len(files)))


def check(out):
    pf = os.path.join(out, "PIN.json")
    if not os.path.isfile(pf):
        return ["missing painter/vendor/PIN.json"]
    pin = json.load(open(pf))
    bad = []
    for rel, e in pin["files"].items():
        p = os.path.join(out, rel)
        if not os.path.isfile(p):
            bad.append("missing painter/vendor/" + rel)
        elif sha(open(p, "rb").read()) != e["sha256"]:
            bad.append("changed painter/vendor/%s (re-run vendor-painter.py fetch, never edit the copy)" % rel)
    for root, _, fs in os.walk(out):
        for f in fs:
            rel = os.path.relpath(os.path.join(root, f), out)
            if rel != "PIN.json" and rel not in pin["files"]:
                bad.append("unpinned painter/vendor/" + rel)
    return bad


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] not in ("fetch", "check"):
        print(__doc__)
        sys.exit(2)
    app = os.path.abspath(a[1] if len(a) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    if a[0] == "fetch":
        fetch(out_dir(app))
        sys.exit(0)
    bad = check(out_dir(app))
    for b in bad:
        print("PIN " + b)
    sys.exit(1 if bad else 0)
