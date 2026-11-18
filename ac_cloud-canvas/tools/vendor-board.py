#!/usr/bin/env python3
"""
vendor-board — #1787 the Board page's offline whiteboard engines, vendored and pinned.

  vendor-board.py fetch quickdraw [APP_DIR]   re-vendor Quickdraw (shipped: app/src/main/assets/board)
  vendor-board.py fetch tldraw    [APP_DIR]   re-vendor the fleet's tldraw build (kept, NOT shipped: vendor/tldraw)
  vendor-board.py check           [APP_DIR]   offline: both copies match their PIN.json (every pinned file present with its
                                              sha256, nothing unpinned beside the declared own files)

THE SHIPPED ENGINE IS QUICKDRAW (MIT). The brief was the fleet's tldraw build (diegonmarcos/cloudy-h-webapps-desk c_Suite/tldraw,
published at https://diegonmarcos.github.io/tldraw/), with quickdraw as the fallback if bundling tldraw failed. It does fail:
tldraw 5.x has no "watermark without a key" mode any more. Its LicenseProvider classifies a production build with no key as
`unlicensed-production` and, after 5 seconds, replaces the whole editor with `<div data-testid="tl-license-expired">` (the
published Suite page is blank for the same reason). Running it would mean patching tldraw's licence enforcement, which is not
done. Quickdraw (https://github.com/quickdrawjs/quickdraw, npm @quickdrawjs/core) is MIT, needs no key, has zero
dependencies and is plain browser ESM, so it is vendored as published, with no build step.

The tldraw copy is kept pinned in vendor/tldraw (outside the APK) with its patches recorded, so Board can move back to it the
day the Suite build carries a licence key for the app's origin (https://appassets.androidplatform.net).

WHY VENDORED and not fetched by the CI build: a build-time fetch would make the APK depend on what a registry or Pages
serves that minute and break the publish gate's source identity (the same commit must build the same APK). A pinned copy
with its hashes is reproducible and works with no network on the phone; re-pinning is one run of `fetch`.

QUICKDRAW (app/src/main/assets/board):
  quickdraw/src/**            @quickdrawjs/core <version> `src/` from the npm tarball (integrity-checked), byte for byte
  LICENSE                     the MIT licence from the project's repository
  quickdraw/index.html, quickdraw/cloud-board-bridge.js, PIN.json, NOTICE.md   own code (#1787)

TLDRAW (vendor/tldraw), patches recorded in its PIN.json: analytics removed from index.html, the bridge loaded, IndexedDB
persistence off, the editor published as window.tldrawEditor.
"""
import base64, hashlib, io, json, os, re, ssl, sys, tarfile, urllib.request

QD_PKG = "@quickdrawjs/core"
QD_META = "https://registry.npmjs.org/@quickdrawjs%2Fcore"
QD_LICENSE = "https://cdn.jsdelivr.net/gh/quickdrawjs/quickdraw/LICENSE"
QD_OWN = ["quickdraw/index.html", "quickdraw/cloud-board-bridge.js", "PIN.json", "NOTICE.md"]

PAGES = "https://diegonmarcos.github.io/tldraw/"
CDN = "https://cdn.tldraw.com"
NPM_TLDRAW = "https://registry.npmjs.org/tldraw/-/tldraw-{v}.tgz"
TL_OWN = ["tldraw/cloud-board-bridge.js", "PIN.json", "NOTICE.md"]
PATCH_MOUNT = ('o=n?.(r),r.emit("mount")', 'o=n?.(r),window.tldrawEditor=r,r.emit("mount")')
PATCH_PERSIST = ('persistenceKey:"tldraw-diegonmarcos"', 'persistenceKey:void 0')


def sha(b):
    return hashlib.sha256(b).hexdigest()


def get(url):
    ctx = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE") or None)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vendor-board"}), timeout=120, context=ctx) as r:
        return r.read()


def dirs(app):
    return {"quickdraw": os.path.join(app, "app/src/main/assets/board"), "tldraw": os.path.join(app, "vendor/tldraw")}


def writer(out, files):
    def put(rel, data, url, upstream=None, patch=None):
        p = os.path.join(out, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "wb").write(data)
        e = {"sha256": sha(data), "url": url}
        if upstream is not None:
            e["upstream_sha256"] = upstream
            e["patch"] = patch
        files[rel] = e
    return put


def fetch_quickdraw(out):
    files = {}
    put = writer(out, files)
    meta = json.loads(get(QD_META))
    version = meta["dist-tags"]["latest"]
    v = meta["versions"][version]
    assert v.get("license") == "MIT", "the package is no longer MIT: " + str(v.get("license"))
    assert not v.get("dependencies"), "the package gained dependencies; vendoring src/ alone is no longer enough"
    tgz = get(v["dist"]["tarball"])
    assert hashlib.sha512(tgz).digest() == base64.b64decode(v["dist"]["integrity"].split("-", 1)[1]), "tarball integrity"
    with tarfile.open(fileobj=io.BytesIO(tgz)) as t:
        for m in t.getmembers():
            if m.isfile() and m.name.startswith("package/src/"):
                put("quickdraw/" + m.name[len("package/"):], t.extractfile(m).read(), v["dist"]["tarball"] + "#" + m.name)
    put("LICENSE", get(QD_LICENSE), QD_LICENSE)
    pin = {
        "_doc": "#1787 the pinned offline copy of Quickdraw for Cloud Canvas > Draw > Board. Written by ac_cloud-canvas/tools/vendor-board.py fetch quickdraw, checked by its check mode (test-canvas-app.sh T7). Do not edit by hand: re-run fetch.",
        "engine": "quickdraw",
        "source": {"package": QD_PKG, "version": version, "tarball": v["dist"]["tarball"], "integrity": v["dist"]["integrity"], "repo": "https://github.com/quickdrawjs/quickdraw"},
        "licence": "MIT (the package and LICENSE); fonts in quickdraw/src/fonts: Libre Baskerville, SIL OFL 1.1 (quickdraw/src/fonts/OFL.txt)",
        "own": QD_OWN,
        "files": files,
    }
    open(os.path.join(out, "PIN.json"), "w").write(json.dumps(pin, indent=1) + "\n")
    print("vendored quickdraw %s: %d files" % (version, len(files)))


def fetch_tldraw(out):
    files = {}
    put = writer(out, files)
    html = get(PAGES).decode()
    js = re.search(r'src="/tldraw/(assets/index-[A-Za-z0-9_-]+\.js)"', html).group(1)
    css = re.search(r'href="/tldraw/(assets/index-[A-Za-z0-9_-]+\.css)"', html).group(1)
    clean = re.sub(r"\s*<!-- Matomo -->.*?<!-- End Matomo -->", "", html, flags=re.S)
    clean = re.sub(r"\s*<!-- Umami Analytics -->.*?<!-- End Umami Analytics -->", "", clean, flags=re.S)
    assert "analytics" not in clean, "an analytics snippet survived"
    clean = clean.replace("</body>", '  <script src="/tldraw/cloud-board-bridge.js"></script>\n  </body>')
    put("tldraw/index.html", clean.encode(), PAGES, sha(html.encode()), "analytics removed; cloud-board-bridge.js loaded")
    b = get(PAGES + js)
    src = b.decode()
    for old, new in (PATCH_MOUNT, PATCH_PERSIST):
        assert src.count(old) == 1, "patch anchor not found exactly once: " + old
        src = src.replace(old, new)
    put("tldraw/" + js, src.encode(), PAGES + js, sha(b), "persistence off (app storage through the bridge); editor published as window.tldrawEditor")
    put("tldraw/" + css, get(PAGES + css), PAGES + css)
    version = re.search(r'lu\("tldraw","([0-9.]+)","esm"\)', src).group(1)
    cdn = sorted(set(re.findall(r"nr\(\)\}/((?:fonts|icons|watermarks)/[A-Za-z0-9_./-]+?\.(?:woff2|svg))", src)))
    cdn += ["translations/en.json", "translations/languages.json"]
    cdn += ["embed-icons/%s.png" % t for t in sorted(set(re.findall(r'type:"([a-z_]+)",title:"[^"]+",hostnames', src)))]
    for rel in cdn:
        u = "%s/%s/%s" % (CDN, version, rel)
        put("cdn/%s/%s" % (version, rel), get(u), u)
    with tarfile.open(fileobj=io.BytesIO(get(NPM_TLDRAW.format(v=version)))) as t:
        put("LICENSE.md", t.extractfile("package/LICENSE.md").read(), NPM_TLDRAW.format(v=version) + "#package/LICENSE.md")
    pin = {
        "_doc": "#1787 the pinned copy of the fleet's tldraw web app, KEPT OUT OF THE APK: tldraw 5.x refuses to render an unlicensed production build (LicenseProvider, 'unlicensed-production', the editor is replaced after 5 s), so Board ships Quickdraw. Written by ac_cloud-canvas/tools/vendor-board.py fetch tldraw, checked by its check mode. Do not edit by hand.",
        "engine": "tldraw",
        "source": {"repo": "diegonmarcos/cloudy-h-webapps-desk", "path": "c_Suite/tldraw", "published": PAGES},
        "tldraw_version": version,
        "cdn": "%s/%s/" % (CDN, version),
        "licence": "LicenseRef-tldraw (tldraw licence: a production build needs a licence key; without one the editor does not render)",
        "own": TL_OWN,
        "files": files,
    }
    open(os.path.join(out, "PIN.json"), "w").write(json.dumps(pin, indent=1) + "\n")
    print("vendored tldraw %s: %d files" % (version, len(files)))


def check_one(out):
    pin = json.load(open(os.path.join(out, "PIN.json")))
    bad = []
    for rel, e in pin["files"].items():
        p = os.path.join(out, rel)
        if not os.path.isfile(p):
            bad.append("missing %s/%s" % (pin["engine"], rel))
        elif sha(open(p, "rb").read()) != e["sha256"]:
            bad.append("changed %s/%s (re-run vendor-board.py fetch, never edit the copy)" % (pin["engine"], rel))
    for root, _, fs in os.walk(out):
        for f in fs:
            rel = os.path.relpath(os.path.join(root, f), out)
            if rel not in pin["files"] and rel not in pin["own"]:
                bad.append("unpinned %s/%s" % (pin["engine"], rel))
    return bad


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] not in ("fetch", "check") or (a[0] == "fetch" and (len(a) < 2 or a[1] not in ("quickdraw", "tldraw"))):
        print(__doc__)
        sys.exit(2)
    rest = a[2:] if a[0] == "fetch" else a[1:]
    app = os.path.abspath(rest[0] if rest else os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    d = dirs(app)
    if a[0] == "fetch":
        (fetch_quickdraw if a[1] == "quickdraw" else fetch_tldraw)(d[a[1]])
        sys.exit(0)
    bad = check_one(d["quickdraw"]) + check_one(d["tldraw"])
    for b in bad:
        print("PIN " + b)
    sys.exit(1 if bad else 0)
