#!/usr/bin/env python3
"""#1547 runtime-data guard: APKs are ENGINES, data is read at RUNTIME (libs:fleet-data-client), never baked in at build time.

Usage: cloud-android-runtime-data-guard.py [ROOT] [--inventory]
  ROOT         repo root (default .)
  --inventory  print the current inventory (counts per rule) and exit 0
Policy: 1_cicd/src/data/runtime-data-baseline.json. Exit 1 on any finding. Rules:
  D1 json    a JSON file under src/<set>/assets or src/<set>/res/raw* that is neither in `seed_allowlist` (tier-4 static reference seed, with a
             reason) nor in the shrink-only `json` baseline. A baselined file that is gone must be dropped from the baseline.
  D2 gradle  a gradle site that injects files into an APK's assets (assets.srcDir(s), a Copy/copy into an assets dir) beyond the per-file count in
             `gradle_assets`; a count below the baseline must be lowered with it.
  D3 b64     a BuildConfig field that carries build.json data as base64 (buildConfigField named *B64*, or whose value is a base64 expression) beyond
             the per-file count in `buildconfig_b64`; same shrink rule.
  D4 mock    a mock-data file (`mock_files`) under a main source set: mock data never ships in a release build.
  D5 fleet   data/fleet/manifest.json: every dataset it lists exists, carries an integer schemaVersion, is tier 3-4 (tiers 1-2 are never in the
             public repo), and no personal/ or fleet/ dataset file sits under data/fleet.
Every list only shrinks (json_max / gradle_max / b64_max are the ceilings).
"""
import hashlib
import json
import os
import re
import sys

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = ARGS[0] if ARGS else "."
INVENTORY = "--inventory" in sys.argv
POLICY = os.path.join(ROOT, "1_cicd/src/data/runtime-data-baseline.json")
SKIP = {"build", "node_modules", ".gradle", ".git", "z_archive", "intermediates", "dist-newstyle"}
JSON_RE = re.compile(r"(^|/)src/[A-Za-z0-9]+/(assets|res/raw[^/]*)/.*\.json$")
ASSET_INJECT = re.compile(r"assets\.srcDirs?\b|\bassets\s*\{|\binto\s*\(?[^)\n]*assets|\bassetsDir\b|\bmlAssetsDir\b")
B64_FIELD = re.compile(r"""buildConfigField\s*\(?\s*['"]String['"]\s*,\s*['"]([A-Za-z0-9_]*)['"]\s*,\s*(.*)""")
B64_VALUE = re.compile(r"(?i)b64|base64")


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in files:
            yield os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")


def read(p):
    try:
        with open(os.path.join(ROOT, p), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def strip_comments(t):
    return re.sub(r"(?m)^\s*//[^\n]*$", "", re.sub(r"/\*.*?\*/", "", t, flags=re.S))


def scan():
    files = sorted(walk(ROOT))
    jsons = [f for f in files if JSON_RE.search(f)]
    gradle, b64 = {}, {}
    for f in files:
        if not f.endswith((".gradle", ".gradle.kts")):
            continue
        t = strip_comments(read(f))
        n = len(ASSET_INJECT.findall(t))
        if n:
            gradle[f] = n
        m = 0
        for line in t.splitlines():
            h = B64_FIELD.search(line)
            if h and (B64_VALUE.search(h.group(1)) or B64_VALUE.search(h.group(2))):
                m += 1
        if m:
            b64[f] = m
    return files, jsons, gradle, b64


def main():
    files, jsons, gradle, b64 = scan()
    if INVENTORY:
        print("json=%d gradle_asset_sites=%d (%d files) buildconfig_b64=%d (%d files)" % (len(jsons), sum(gradle.values()), len(gradle), sum(b64.values()), len(b64)))
        return 0
    try:
        pol = json.loads(read("1_cicd/src/data/runtime-data-baseline.json"))
    except ValueError:
        print("FAIL policy %s is missing or not JSON" % POLICY)
        return 1
    bad = []
    seed = {k: v for k, v in pol.get("seed_allowlist", {}).items() if not k.startswith("_")}
    base = {k: v for k, v in pol.get("json", {}).items() if not k.startswith("_")}
    for k, v in seed.items():
        if not isinstance(v, str) or len(v) < 20:
            bad.append("policy: seed_allowlist[%s] needs a reason (tier-4 static reference data only)" % k)
    cur = set(jsons)
    for f in sorted(cur):
        if f in base or any(f.startswith(p) for p in seed if p.endswith("/")) or f in seed:
            continue
        bad.append("D1 %s is baked JSON: an APK is an engine, serve it as a runtime dataset (libs:fleet-data-client), or list a tier-4 seed in seed_allowlist with a reason" % f)
    for f in sorted(set(base) - cur):
        bad.append("D1 baseline lists %s, which is gone: drop it (the list only shrinks)" % f)
    for k, v in base.items():
        if not isinstance(v, dict) or v.get("tier") not in (1, 2, 3, 4, "mock") or not v.get("target"):
            bad.append("policy: json[%s] needs a tier (1-4 or mock) and a target provider" % k)
    if len(base) > pol.get("json_max", len(base)):
        bad.append("policy: json baseline has %d entries, more than json_max %d (the list may only shrink)" % (len(base), pol["json_max"]))
    for name, found, key, label in (("D2", gradle, "gradle_assets", "gradle injects files into assets"), ("D3", b64, "buildconfig_b64", "BuildConfig carries base64 data")):
        allowed = {k: v for k, v in pol.get(key, {}).items() if not k.startswith("_")}
        for f, n in sorted(found.items()):
            if n > allowed.get(f, 0):
                bad.append("%s %s: %s (%d sites, baseline %d): read the data at runtime instead" % (name, f, label, n, allowed.get(f, 0)))
        for f, n in sorted(allowed.items()):
            if found.get(f, 0) < n:
                bad.append("%s baseline says %s has %d, it has %d now: lower the baseline (the list only shrinks)" % (name, f, n, found.get(f, 0)))
    cap = pol.get("gradle_max")
    if cap is not None and sum(v for k, v in pol.get("gradle_assets", {}).items() if not k.startswith("_")) > cap:
        bad.append("policy: gradle_assets sum is above gradle_max %d" % cap)
    cap = pol.get("b64_max")
    if cap is not None and sum(v for k, v in pol.get("buildconfig_b64", {}).items() if not k.startswith("_")) > cap:
        bad.append("policy: buildconfig_b64 sum is above b64_max %d" % cap)
    for f in files:
        if os.path.basename(f) in pol.get("mock_files", []) and "/src/main/" in f:
            bad.append("D4 %s is mock data in a main source set: it would ship in the release APK" % f)
    bad += fleet(files)
    for b in bad:
        print("FAIL " + b)
    print("runtime-data guard: %d JSON, %d gradle asset sites, %d base64 fields, %d findings" % (len(jsons), sum(gradle.values()), sum(b64.values()), len(bad)))
    return 1 if bad else 0


def fleet(files):
    bad = []
    mp = "data/fleet/manifest.json"
    if mp not in files:
        return ["D5 %s is missing" % mp]
    try:
        m = json.loads(read(mp))
    except ValueError:
        return ["D5 %s is not JSON" % mp]
    if not isinstance(m.get("schemaVersion"), int):
        bad.append("D5 manifest has no integer schemaVersion")
    served = set()

    def check(path, label, tier):
        served.add(path)
        if path not in files:
            bad.append("D5 %s: %s does not exist" % (label, path))
            return
        try:
            d = json.loads(read(path))
        except ValueError:
            bad.append("D5 %s: %s is not JSON" % (label, path))
            return
        if not isinstance(d, dict) or not isinstance(d.get("schemaVersion"), int):
            bad.append("D5 %s: %s has no integer schemaVersion" % (label, path))
        if tier not in (3, 4):
            bad.append("D5 %s is tier %s: tiers 1-2 are never served from the public repo" % (label, tier))
        if isinstance(d, dict) and "sha256" in d:  # a downloadable pack descriptor: the blob it points at must match it byte for byte
            blob = "data/fleet/packs/" + str(d.get("url", "")).rsplit("/", 1)[-1]
            try:
                with open(os.path.join(ROOT, blob), "rb") as bf:
                    raw = bf.read()
            except OSError:
                bad.append("D5 %s: pack blob %s does not exist" % (label, blob))
                return
            if hashlib.sha256(raw).hexdigest() != d["sha256"] or len(raw) != d.get("bytes"):
                bad.append("D5 %s: pack blob %s does not match its descriptor sha256/bytes" % (label, blob))

    for name, e in (m.get("shared") or {}).items():
        if name.startswith(("personal/", "fleet/", "infra/")):
            bad.append("D5 shared dataset %s has a personal/fleet prefix: tiers 1-2 are never in the public repo" % name)
        check(e.get("source") or "data/fleet/%s.json" % name, "shared/" + name, e.get("tier", 3))
    for pkg, names in (m.get("apps") or {}).items():
        for n in names:
            check("data/fleet/app/%s/%s.json" % (pkg, n), "app/%s/%s" % (pkg, n), 3)
    for f in files:
        if f.startswith("data/fleet/") and f.endswith(".json") and f != mp and f not in served:
            bad.append("D5 %s is not listed in the manifest (an unserved dataset is dead data)" % f)
        if re.match(r"data/fleet/(personal|fleet|infra)/", f):
            bad.append("D5 %s: personal and fleet data never go in the public repo" % f)
    return bad


if __name__ == "__main__":
    sys.exit(main())
