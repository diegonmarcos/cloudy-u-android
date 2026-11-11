#!/usr/bin/env bash
# #1642 - "Open with" for text files: the app's opening Activity declares VIEW + EDIT intent-filters for text
# types on content:// and file://, plus the extension recipe for senders that report a generic type, and the code that
# opens the file is wired. Field-parity / presence guard: removing a declared type, extension, scheme or the wiring goes red.
#
#   T1  text/plain, text/*, markdown, csv, xml, html, application/json, application/xml are declared with scheme content AND file
#   T2  every declared action (VIEW EDIT) and category DEFAULT is on the MIME filter
#   T3  the generic-type recipe (host *, */*) lists pathPatterns for .txt .md .log .json .csv .conf .yml .xml
#   T4  no filter takes */* (or octet-stream) without a pathPattern: the app never becomes the default handler for binary types
#   T5  the opening code is wired
#
# OWN-SOURCE ONLY. python3, no network, no build.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
python3 - "$ROOT" <<'PY'
import re, sys, os
root = sys.argv[1]
manifest = os.path.join(root, "packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml")
ACTIONS = "VIEW EDIT".split()
if not os.path.isfile(manifest):
    print("ERROR missing source: packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml - this tester is unrun, not passing"); sys.exit(1)
s = open(manifest, encoding="utf-8").read()
act = re.search(r'<activity\b[^>]*android:name="\.MainActivity"[^>]*>(.*?)</activity>', s, re.S)
fails = 0
def check(c, m):
    global fails
    print("  %s  %s" % ("PASS" if c else "FAIL", m))
    if not c: fails += 1
if not act:
    print("  FAIL  MainActivity is not declared"); sys.exit(1)
filters = re.findall(r'<intent-filter>(.*?)</intent-filter>', re.sub(r'<!--.*?-->', '', act.group(1), flags=re.S), re.S)
def parse(f):
    return dict(
        actions=set(re.findall(r'<action android:name="android\.intent\.action\.(\w+)"', f)),
        cats=set(re.findall(r'<category android:name="android\.intent\.category\.(\w+)"', f)),
        schemes=set(re.findall(r'android:scheme="(\w+)"', f)),
        mimes=set(re.findall(r'android:mimeType="([^"]+)"', f)),
        hosts=set(re.findall(r'android:host="([^"]+)"', f)),
        pats=re.findall(r'android:pathPattern="([^"]+)"', f),
    )
fs = [parse(f) for f in filters]
text = [f for f in fs if "text/*" in f["mimes"]]
check(len(text) == 1, "one MIME filter carries text/*")
t = text[0] if text else dict(actions=set(), cats=set(), schemes=set(), mimes=set(), pats=[], hosts=set())
need = {"text/plain", "text/*", "text/markdown", "text/csv", "text/xml", "text/html", "application/json", "application/xml"}
check(need <= t["mimes"], "T1 MIME types declared" + ("" if need <= t["mimes"] else ": missing %s" % sorted(need - t["mimes"])))
check({"content", "file"} <= t["schemes"], "T1 schemes content and file")
check(set(ACTIONS) <= t["actions"], "T2 actions %s" % ACTIONS)
check("DEFAULT" in t["cats"], "T2 category DEFAULT")
gen = [f for f in fs if "*/*" in f["mimes"] and any(re.search(r"txt", p) for p in f["pats"])]
check(len(gen) == 1, "T3 generic-type recipe filter present")
g = gen[0] if gen else dict(actions=set(), cats=set(), schemes=set(), mimes=set(), pats=[], hosts=set())
for e in "txt md log json csv conf yml xml".split():
    check(any(p.endswith("\\\\." + e) for p in g["pats"]), "T3 pathPattern for .%s" % e)
check("*" in g["hosts"] and {"content", "file"} <= g["schemes"] and set(ACTIONS) <= g["actions"] and "DEFAULT" in g["cats"], "T3 recipe has host *, both schemes, actions, DEFAULT")
bare = [f for f in fs if f["mimes"] & {"*/*", "application/octet-stream"} and not f["pats"]]
check(not bare, "T4 no filter takes */* or octet-stream without a pathPattern")
def src(rel):
    p = os.path.join(root, rel)
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else None

D = "packages/frontend/apps/android/"
pl = src(D + "App/app/src/main/java/app/cloudnotes/pro/plugin/ExternalFilePlugin.kt")
ma = src(D + "App/app/src/main/java/app/cloudnotes/pro/MainActivity.kt")
ts = src(D + "src/plugins/external-file.ts")
ap = src(D + "src/app.tsx")
check(pl is not None and "fun capture(" in pl and "fun takePendingOpen(" in pl, "T5 native capture + takePendingOpen")
check(ma is not None and ma.count("captureViewIntent(") >= 3 and "override fun onNewIntent" in ma, "T5 MainActivity captures the intent on create and onNewIntent")
check(ts is not None and "takePendingOpen" in ts, "T5 plugin type declares takePendingOpen")
check(ap is not None and "takePendingOpen" in ap and "cloud-notes:view-intent" in ap, "T5 the editor takes the pending file on the view-intent event")
print("RESULT: %s" % ("RED" if fails else "GREEN"))
sys.exit(1 if fails else 0)
PY
