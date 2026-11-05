#!/usr/bin/env bash
# Store ▸ Cloud must work on a phone with NO mesh: WireGuard down, no fleet
# DNS, no git-proxy-api. Every fleet APK is a public asset on the `latest`
# GitHub release, so listing, the update check and the download all have a
# public source, and none of them may depend on a host only the mesh reaches.
#
# MEASURED 2026-09-30 before this guard existed (and still true):
#   list      Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64): baked from
#             aa_cloud-superapp/data/constellation-fleet.json at build time. No network.
#   check     Fleet.status -> releaseStatus: HEAD <release_url> + GET <release_url>.sha256,
#             both github.com/.../releases/download/latest/<asset> (not the
#             rate-limited api.github.com); falls through to ghcr.io anonymously.
#   download  Fleet.download: ReleaseSource (same URL, no Authorization) then GhcrSource.
#   install   the local shell channel ladder or PackageInstaller: on-device.
# Zero calls went to 10.x, *.internal, single-label mesh names or git-proxy-api.
# This guard keeps it that way.
#
# "MESH-ONLY" IS DERIVED, NOT LISTED HERE: every CIDR the superapp's own
# build.json declares for its WireGuard profiles (allowed_ips / address), plus
# any private/loopback/link-local IP literal, plus names no public resolver can
# answer: single-label hosts (git-proxy-api, a docker/mesh name) and the
# .internal / .local / .lan / .home.arpa suffixes.
#
# The one mesh-aware thing on the Store is the #668 feed reader, whose OPTIONAL
# `proxy` may be a fleet host; its `url` is the public fallback and must stay
# public. `proxy` is therefore exempt below.
#
# The download path has the code-side twin of that field: the #831/#837
# MeshMirror leg (libs:updater source/ApkSource.kt), an OPTIONAL mirror tried
# only after both public legs. Its declared origins (MeshMirror.BASE / BASE_WG)
# are exempt exactly while Fleet's source list keeps it LAST — T3 asserts that
# order, so moving the mesh leg ahead of a public one fails here.
#
# Not mesh: a default route (0.0.0.0/0, ::/0 in a full-tunnel profile) names
# every address, not the mesh, so it is not a mesh CIDR; and loopback is the
# phone itself (the fleet's on-device debug APIs), reached with wg0 down.
#
# Overrides (for mutation runs): FLEET_JSON, BUILD_JSON, LIBS.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
python3 - "${FLEET_JSON:-$APP/data/constellation-fleet.json}" "${BUILD_JSON:-$APP/build.json}" \
    "${LIBS:-$ROOT/ab_cloud-libs-shared/libs}" <<'PY'
import ipaddress, json, os, re, sys
from urllib.parse import urlsplit

fleet_path, build_path, libs = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

# ── the mesh, from its own declaration ──────────────────────────────────────
mesh = set()
def walk(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("allowed_ips", "address", "interface_address") and isinstance(v, str):
                nets = []
                for c in v.split(","):
                    try: n = ipaddress.ip_network(c.strip(), strict=False)
                    except ValueError: continue
                    if n.prefixlen: nets.append(n)   # /0 is a full tunnel, not the mesh
                # A full tunnel written as "everything except <the proxy>" is the
                # complement set (0.0.0.0/3, 32.0.0.0/7, ...): it routes the public
                # internet through wg0, it does not name the mesh. Most of IPv4 = not mesh.
                if sum(n.num_addresses for n in nets if n.version == 4) > 2 ** 31: continue
                mesh.update(nets)
            walk(v)
    elif isinstance(o, list):
        for v in o: walk(v)
walk(json.loads(read(build_path)))
if not mesh: print("FATAL: no WireGuard CIDR declared in", build_path); sys.exit(2)
PRIVATE_SUFFIXES = (".internal", ".local", ".lan", ".home.arpa")

def mesh_only(host):
    """Why [host] is reachable only through the mesh, or None if it is public."""
    h = host.strip("[]").lower()
    try:
        ip = ipaddress.ip_address(h)
        if ip.is_loopback: return None   # on-device; never crosses wg0
        if any(ip in n for n in mesh): return "inside a declared WireGuard CIDR"
        if ip.is_private or ip.is_link_local: return "a private IP"
        return None
    except ValueError: pass
    if "." not in h: return "a single-label name only mesh DNS can answer"
    if h.endswith(PRIVATE_SUFFIXES): return "a private DNS suffix"
    return None

def host_of(url):
    return urlsplit(url).hostname or ""

# ── T1: the Apps list resolves with no network ──────────────────────────────
print("== T1: the Apps list is baked, not fetched ==")
fleet = json.loads(read(fleet_path))
apps = fleet.get("apps") or []
ids = {a["id"] for a in apps}
groups = [g for g in fleet.get("groups", []) if any(m in ids for m in g.get("members", []))]
if apps and groups: ok("%d fleet apps across %d groups, straight from the baked manifest" % (len(apps), len(groups)))
else: bad("the baked fleet manifest has no apps or no group that resolves any (apps=%d)" % len(apps))
gradle = read(os.path.join(libs, "appstore/build.gradle"))
if "constellationFleetFile.text" in gradle and not re.search(r"\.toURL\(|new URL\(|openConnection", gradle):
    ok("libs:appstore bakes the manifest from a file in the tree, never a URL")
else: bad("libs:appstore's fleet bake is not a local file read")
store = read(os.path.join(libs, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"))
if "Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)" in store: ok("Store ▸ Cloud lists from the baked manifest")
else: bad("Store ▸ Cloud does not list from BuildConfig.CONSTELLATION_FLEET_B64")

# ── T2: update check + download URL resolve to public release assets ────────
print("== T2: every published app checks and downloads from a public release asset ==")
published = [a for a in apps if not a.get("blocked") and a.get("release_url")]
if not published: bad("no published app carries a release_url")
wrong = []
for a in published:
    base = a["release_url"]
    asset = a.get("asset", "")
    # Every URL Fleet.App.abiReleaseUrl can produce: the declared one, and the
    # declared one with each per-ABI asset swapped in; plus each .sha256 sidecar.
    urls = [base] + ([base[: -len(asset)] + v for v in (a.get("assets") or {}).values()]
                     if asset and base.endswith("/" + asset) else [])
    for u in urls + [u + ".sha256" for u in urls]:
        s = urlsplit(u)
        why = mesh_only(s.hostname or "")
        if s.scheme != "https": wrong.append("%s: not https (%s)" % (a["id"], u))
        elif why: wrong.append("%s: %s is %s" % (a["id"], s.hostname, why))
        elif s.hostname == "api.github.com": wrong.append("%s: rate-limited API, not a download URL (%s)" % (a["id"], u))
        elif "/releases/download/" not in s.path: wrong.append("%s: not a release-asset URL (%s)" % (a["id"], u))
for a in apps:
    why = mesh_only(a.get("registry", "ghcr.io"))
    if why: wrong.append("%s: registry %s is %s" % (a["id"], a["registry"], why))
if not wrong: ok("%d published apps: check, sidecar and download URLs are all public release assets" % len(published))
else: wrong = sorted(set(wrong)); bad("%d URL(s) need the mesh or the rate-limited API: %s" % (len(wrong), "; ".join(wrong[:5])))

upd = os.path.join(libs, "updater/src/main/java/com/diegonmarcos/superapp/updater")
fk = read(os.path.join(upd, "Fleet.kt")); src = read(os.path.join(upd, "source/ApkSource.kt"))
checks = [
    ("the check HEADs the ABI release asset", "java.net.URL(url)" in fk and "val url = app.abiReleaseUrl" in fk),
    ("the check reads the .sha256 sidecar beside it", 'java.net.URL(app.abiReleaseUrl + ".sha256")' in fk),
    ("the release answers before the registry", "releaseStatus(app, installed)?.let { return it }" in fk),
    ("the public release is the first fallback after the mesh leg (#1093)", re.search(r"sources: List<ApkSource> = listOf\(MeshMirrorSource, ReleaseSource,", fk) is not None),
    ("the release download sends no credential", "val url = app.abiReleaseUrl" in src
        and re.search(r"Download\.toFile\((?:(?!\)\n).)*headers\s*=", src, re.S) is None),
]
for what, good in checks: ok(what) if good else bad(what)

# ── T3: nothing on the Store's code path names a mesh-only host ─────────────
print("== T3: no mesh-only host in libs:updater / libs:appstore ==")
def code(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r'(?<![:"])//.*$', "", l) for l in text.splitlines())
def strings_of(o, key=""):
    if isinstance(o, dict):
        for k, v in o.items():
            if k.startswith("_doc") or k == "proxy": continue
            yield from strings_of(v, k)
    elif isinstance(o, list):
        for v in o: yield from strings_of(v, key)
    elif isinstance(o, str): yield o
# The declared mesh leg: exempt only while it is the FIRST download source and the public legs follow it (#1093: mesh first, public fallback).
upd_src = os.path.join(libs, "updater/src/main/java/com/diegonmarcos/superapp/updater")
srcs = re.search(r"val sources: List<ApkSource> = listOf\(([^)]*)\)", code(read(os.path.join(upd_src, "Fleet.kt"))))
order = [x.strip() for x in srcs.group(1).split(",")] if srcs else []
if order and order[0] == "MeshMirrorSource" and {"ReleaseSource", "GhcrSource"} <= set(order[1:]):
    ok("the mesh leg is tried first, the public legs follow it: %s" % " -> ".join(order))
    mesh_leg_ok = True
else:
    bad("Fleet's download sources do not keep MeshMirrorSource first with the public legs after it: %s" % order)
    mesh_leg_ok = False
MESH_LEG = re.compile(r"^\s*const val BASE(?:_WG)? = \"[^\"]*\"\s*$", re.M)
def exempt(f, text):
    """Drop MeshMirror's declared origins (only those two lines) from ApkSource.kt."""
    if f != "ApkSource.kt" or not mesh_leg_ok: return text
    m = re.search(r"object MeshMirror \{.*?@Volatile var bases", text, re.S)
    if not m: return text
    return text[:m.start()] + MESH_LEG.sub("", m.group(0)) + text[m.end():]
hits, scanned = [], 0
for sub in ("updater/src", "appstore/src"):
    for dp, _, fs in os.walk(os.path.join(libs, sub)):
        if "/test" in dp: continue
        for f in fs:
            p = os.path.join(dp, f)
            if f.endswith(".kt"): texts = re.findall(r'"((?:[^"\\\n]|\\.)*)"', exempt(f, code(read(p))))
            elif f.endswith(".json"): texts = list(strings_of(json.loads(read(p))))
            else: continue
            scanned += 1
            for t in texts:
                for u in re.findall(r"https?://[^\s\"'<>]+", t):
                    h = host_of(u)
                    why = None if h.startswith("$") or "{" in h else mesh_only(h)
                    if why: hits.append("%s: %s (%s)" % (f, u, why))
                for ip in re.findall(r"(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.])", t):
                    why = mesh_only(ip)
                    if why: hits.append("%s: %s (%s)" % (f, ip, why))
if scanned < 20: bad("scanned only %d files under %s — wrong LIBS?" % (scanned, libs))
elif not hits: ok("%d files scanned; no literal points at the mesh" % scanned)
else: bad("mesh-only host on the Store's path: %s" % "; ".join(hits[:5]))

feeds = json.loads(read(os.path.join(libs, "appstore/src/main/assets/appstore-feeds.json")))["feeds"]
priv = ["%s: %s" % (f.get("id", f.get("label")), mesh_only(host_of(f["url"]))) for f in feeds if mesh_only(host_of(f["url"]))]
if feeds and not priv: ok("#668 feeds: every public `url` stays public (only the optional proxy may be fleet)")
else: bad("a feed's public url is mesh-only or no feeds declared: %s" % priv)

print("\n%d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
