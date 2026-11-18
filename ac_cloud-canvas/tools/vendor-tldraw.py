#!/usr/bin/env python3
"""
vendor-tldraw — #1787 the Board page's offline copy of the fleet's tldraw web app.

  vendor-tldraw.py fetch   [APP_DIR]   re-vendor from the published dist and tldraw's CDN, patch, write PIN.json
  vendor-tldraw.py check   [APP_DIR]   offline: every pinned file exists with its pinned sha256, nothing unpinned is shipped

WHY VENDORED (and not fetched by the CI build): the source is the fleet's own Suite build of tldraw
(diegonmarcos/cloudy-h-webapps-desk, c_Suite/tldraw), published at https://diegonmarcos.github.io/tldraw/. This repository cannot
push to the desk repository and the Pages site is mutable (a redeploy renames the hashed bundles), so a build-time fetch would make
the APK depend on whatever Pages serves that minute and break the publish gate's source identity (the same commit must build the same
APK). A pinned copy with its hashes is reproducible and works with no network on the phone; re-pinning is one run of `fetch`.

WHAT IS FETCHED (APP_DIR/app/src/main/assets/board):
  tldraw/index.html, tldraw/assets/index-*.js|css   the published dist (served at https://appassets.androidplatform.net/tldraw/)
  cdn/<version>/...                                 the files tldraw <version> loads from https://cdn.tldraw.com/<version>/ (fonts,
                                                    icon sprite, translations, embed icons, watermark); BoardScreen answers those
                                                    requests from this copy, so nothing is downloaded at run time
  LICENSE.md                                        tldraw's licence, from the npm package tldraw@<version>

PATCHES (applied by `fetch`, each recorded in PIN.json with the upstream sha256):
  index.html  the two analytics snippets (Matomo, Umami) are removed (the app has no trackers and no network need) and the bridge
              script cloud-board-bridge.js (own code, kept in this directory, not fetched) is loaded after the bundle
  bundle      `persistenceKey:"tldraw-diegonmarcos"` -> `persistenceKey:void 0`: the boards live in app storage through the bridge,
              not in the WebView's IndexedDB; the mount hook also publishes the editor as window.tldrawEditor for the bridge
"""
import hashlib, json, os, re, ssl, sys, urllib.request

PAGES = "https://diegonmarcos.github.io/tldraw/"
CDN = "https://cdn.tldraw.com"
NPM = "https://registry.npmjs.org/tldraw/-/tldraw-{v}.tgz"
OWN = ["tldraw/cloud-board-bridge.js", "PIN.json", "NOTICE.md"]
PATCH_MOUNT = ('o=n?.(r),r.emit("mount")', 'o=n?.(r),window.tldrawEditor=r,r.emit("mount")')
PATCH_PERSIST = ('persistenceKey:"tldraw-diegonmarcos"', 'persistenceKey:void 0')


def sha(b):
    return hashlib.sha256(b).hexdigest()


def get(url):
    ctx = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE") or None)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vendor-tldraw"}), timeout=120, context=ctx) as r:
        return r.read()


def board_dir(app):
    return os.path.join(app, "app/src/main/assets/board")


def fetch(app):
    import io, tarfile
    out = board_dir(app)
    files = {}

    def put(rel, data, url, upstream=None, patch=None):
        p = os.path.join(out, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "wb").write(data)
        e = {"sha256": sha(data), "url": url}
        if upstream is not None:
            e["upstream_sha256"] = upstream
            e["patch"] = patch
        files[rel] = e

    html = get(PAGES).decode()
    up_html = sha(html.encode())
    js = re.search(r'src="/tldraw/(assets/index-[A-Za-z0-9_-]+\.js)"', html).group(1)
    css = re.search(r'href="/tldraw/(assets/index-[A-Za-z0-9_-]+\.css)"', html).group(1)
    clean = re.sub(r"\s*<!-- Matomo -->.*?<!-- End Matomo -->", "", html, flags=re.S)
    clean = re.sub(r"\s*<!-- Umami Analytics -->.*?<!-- End Umami Analytics -->", "", clean, flags=re.S)
    assert "analytics" not in clean, "an analytics snippet survived"
    clean = clean.replace("</body>", '  <script src="/tldraw/cloud-board-bridge.js"></script>\n  </body>')
    put("tldraw/index.html", clean.encode(), PAGES, up_html, "analytics removed; cloud-board-bridge.js loaded")

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

    tgz = get(NPM.format(v=version))
    with tarfile.open(fileobj=io.BytesIO(tgz)) as t:
        lic = t.extractfile("package/LICENSE.md").read()
    put("LICENSE.md", lic, NPM.format(v=version) + "#package/LICENSE.md")

    pin = {
        "_doc": "#1787 the pinned offline copy of the fleet's tldraw web app for Cloud Canvas > Draw > Board. Written by ac_cloud-canvas/tools/vendor-tldraw.py fetch, checked by its check mode (test-canvas-app.sh T7). Do not edit by hand: re-run fetch.",
        "source": {"repo": "diegonmarcos/cloudy-h-webapps-desk", "path": "c_Suite/tldraw", "published": PAGES},
        "tldraw_version": version,
        "cdn": "%s/%s/" % (CDN, version),
        "licence": "LicenseRef-tldraw (tldraw licence: free use shows the 'Made with tldraw' watermark without a licence key); accepted for this personal app",
        "own": OWN,
        "files": files,
    }
    open(os.path.join(out, "PIN.json"), "w").write(json.dumps(pin, indent=1, sort_keys=False) + "\n")
    print("vendored tldraw %s: %d files" % (version, len(files)))
    return 0


def check(app):
    out = board_dir(app)
    pin = json.load(open(os.path.join(out, "PIN.json")))
    bad = []
    for rel, e in pin["files"].items():
        p = os.path.join(out, rel)
        if not os.path.isfile(p):
            bad.append("missing " + rel)
        elif sha(open(p, "rb").read()) != e["sha256"]:
            bad.append("changed " + rel + " (re-run vendor-tldraw.py fetch, never edit the copy)")
    for root, _, fs in os.walk(out):
        for f in fs:
            rel = os.path.relpath(os.path.join(root, f), out)
            if rel not in pin["files"] and rel not in pin["own"]:
                bad.append("unpinned " + rel)
    for b in bad:
        print("PIN " + b)
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("fetch", "check"):
        print(__doc__)
        sys.exit(2)
    app = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    sys.exit(fetch(app) if sys.argv[1] == "fetch" else check(app))
