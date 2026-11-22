#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1841 Rule 16 parity: every menu item, setting key and section   ║
# ║ id frozen in feature-freeze.json is still declared; the core's   ║
# ║ widened jobs keep a Kotlin twin and a JNI entry each.            ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
python3 - "$HERE" <<'PY'
import json, sys, os
here = sys.argv[1]; root = os.path.dirname(os.path.dirname(here))
b = json.load(open(os.path.join(here, "..", "build.json"))); fz = json.load(open(os.path.join(here, "feature-freeze.json")))
ui = b["ui"]; br = ui["browser"]
def walk(o, out, path=""):
    if isinstance(o, dict):
        if isinstance(o.get("id"), str): out.add(path.split(".")[0] + ":" + o["id"])
        for k, v in o.items(): walk(v, out, path + "." + k if path else k)
    elif isinstance(o, list):
        for v in o: walk(v, out, path)
now = set(); walk({"menu": br.get("menu"), "settings": br.get("settings"), "sections": ui.get("sections"), "bottom": ui.get("bottom_nav")}, now)
fails = 0
def ok(c, m):
    global fails
    print(("PASS  " if c else "FAIL  ") + m)
    if not c: fails += 1
merged = fz.get("merged", {})
# #1856 the owner's explicit deletions (not a regroup): each needs a reason; test-browser-tools-regroup.sh holds that no OTHER id is lost
removed = fz.get("authorised_removals", {})
missing = [i for i in fz["ids"] if i not in now and merged.get(i) not in now and not (i in removed and str(removed[i]).strip())]
ok(not missing, "all %d frozen menu / settings / section ids are still declared%s" % (len(fz["ids"]), (" (missing: %s)" % missing[:8]) if missing else ""))
core = os.path.join(root, "ab_cloud-libs-shared", "libs", "browser-core-rs")
rs = "".join(open(os.path.join(core, "src", f)).read() for f in ("jni_api.rs", "omnibox.rs", "reader.rs", "filter.rs", "store.rs", "search.rs"))
kt = "".join(open(os.path.join(core, "src", "main", "java", "com", "diegonmarcos", "cloudlib", "browsercore", f)).read() for f in ("BrowserCore.kt", "Fallback.kt", "Twins2.kt", "NativeBridge.kt"))
for job, rust, twin in (("session store", "pub struct SessionStore", "class KtSessionStore"), ("history/bookmark search", "pub fn search(", "object KtSearch"),
                        ("content blocking", "pub struct FilterSet", "class KtFilter"), ("omnibox", "pub fn resolve(", "object KtOmnibox"),
                        ("start decision", "pub fn start_decision(", "fun start("), ("reader extraction", "pub fn extract(", "object KtReader"),
                        ("rows (history, bookmarks, settings, perms, downloads)", "pub fn row_put(", "class KtRows"), ("download machine", "pub fn download_next(", "fun downloadNext("),
                        ("filter list merge", "pub fn merge(", "fun merge(")):
    ok(rust in rs and twin in kt, "core job '%s' has its Rust implementation and its Kotlin twin" % job)
for jni in ("nativeStore", "nativeSearch", "nativeFilterNew", "nativeFilterBlocksUrl", "nativeOmnibox"):
    ok(("NativeBridge_" + jni) in rs and ("external fun " + jni) in kt, "JNI entry %s is exported by Rust and declared by Kotlin" % jni)
readme = open(os.path.join(core, "README.md")).read()
ok("Servo" in readme and "WebView" in readme, "the core's README says rendering stays on Android WebView and why (Servo)")
sys.exit(1 if fails else 0)
PY
