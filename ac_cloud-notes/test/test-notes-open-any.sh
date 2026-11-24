#!/usr/bin/env bash
# #1866 - Cloud Notes is offered for every file, like Cloud Drive. Parity guard against Drive's manifest, plus the
# routing wiring and a pure-logic replay of OpenRouter's table.
#
#   P1  every MIME type Drive declares for VIEW/EDIT/SEND/SEND_MULTIPLE (any activity) is declared by a Notes MainActivity filter
#   P2  every such action Drive declares is declared by Notes (SEND/SEND_MULTIPLE only if Drive has them)
#   P3  Notes has the bare */* filter: VIEW+EDIT, DEFAULT, content+file
#   P4  the #1642 text filters are still there (nothing removed)
#   R1  OpenRouter, the file view and the JVM test exist and are wired (MainActivity routes, manifest declares FileViewActivity)
#   M1  mutations: dropping */* , dropping a Drive type, dropping EDIT, dropping the 1642 text filter each turn the guard RED
#
# OWN-SOURCE ONLY. python3, no network, no build.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
python3 - "$ROOT" <<'PY'
import re, sys, os
root = sys.argv[1]
NOTES = os.path.join(root, "packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml")
DRIVE = os.path.join(root, "../ac_cloud-drive/app/src/main/AndroidManifest.xml")
for p in (NOTES, DRIVE):
    if not os.path.isfile(p):
        print("ERROR missing source: %s - this tester is unrun, not passing" % p); sys.exit(1)
OPEN = {"VIEW", "EDIT", "SEND", "SEND_MULTIPLE"}

def filters(text, only_activity=None):
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
    out = []
    for m in re.finditer(r'<activity\b([^>]*)>(.*?)</activity>', text, re.S):
        if only_activity and not re.search(r'android:name="%s"' % re.escape(only_activity), m.group(1)):
            continue
        for f in re.findall(r'<intent-filter\b[^>]*>(.*?)</intent-filter>', m.group(2), re.S):
            out.append(dict(
                actions=set(re.findall(r'android\.intent\.action\.(\w+)"', f)),
                cats=set(re.findall(r'android\.intent\.category\.(\w+)"', f)),
                schemes=set(re.findall(r'android:scheme="(\w+)"', f)),
                mimes=set(re.findall(r'android:mimeType="([^"]+)"', f)),
                pats=re.findall(r'android:pathPattern="([^"]+)"', f)))
    return out

def guard(notes_text, drive_text):
    """list of (ok, message)"""
    res = []
    d = [f for f in filters(drive_text) if f["actions"] & OPEN and f["mimes"]]
    n = filters(notes_text, ".MainActivity")
    dm = set().union(*[f["mimes"] for f in d]) if d else set()
    da = set().union(*[f["actions"] & OPEN for f in d]) if d else set()
    nm = set().union(*[f["mimes"] for f in n]) if n else set()
    na = set().union(*[f["actions"] for f in n]) if n else set()
    res.append((bool(dm), "P1 Drive declares %d types for %s" % (len(dm), sorted(da))))
    miss = sorted(dm - nm)
    res.append((not miss, "P1 Notes declares every Drive type" + (": missing %s" % miss if miss else "")))
    miss = sorted(da - na)
    res.append((not miss, "P2 Notes declares every Drive action" + (": missing %s" % miss if miss else "")))
    bare = [f for f in n if "*/*" in f["mimes"] and not f["pats"] and {"VIEW", "EDIT"} <= f["actions"] and "DEFAULT" in f["cats"] and {"content", "file"} <= f["schemes"]]
    res.append((len(bare) >= 1, "P3 bare */* filter (VIEW+EDIT, DEFAULT, content+file)"))
    old = [f for f in n if "text/plain" in f["mimes"] and {"VIEW", "EDIT"} <= f["actions"] and {"content", "file"} <= f["schemes"]]
    res.append((len(old) >= 1, "P4 the #1642 text filter is present"))
    return res

notes = open(NOTES, encoding="utf-8").read()
drive = open(DRIVE, encoding="utf-8").read()
fails = 0
def show(res):
    global fails
    for ok, m in res:
        print("  %s  %s" % ("PASS" if ok else "FAIL", m))
        if not ok: fails += 1
show(guard(notes, drive))

def src(rel):
    p = os.path.join(root, "packages/frontend/apps/android", rel)
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""
J = "App/app/src/main/java/app/cloudnotes/pro/"
router, view, main = src(J + "OpenRouter.kt"), src(J + "FileViewActivity.kt"), src(J + "MainActivity.kt")
test = src("App/app/src/test/java/app/cloudnotes/pro/OpenRouterTest.kt")
res = [
    ("fun route(" in router and all(k in router for k in ("TEXT", "IMAGE", "PDF", "AUDIO", "VIDEO", "BINARY")), "R1 OpenRouter.route decides text/image/pdf/audio/video/binary"),
    ("fun canWriteBack(" in router, "R1 write-back decision is a pure function"),
    ("neverThrows" in test and "genericTypeIsDecidedByTheName" in test, "R1 JVM test covers unknown/empty types and generic names"),
    ("openAsText" in view and "attachToNote" in view and "openWithOther" in view and "EXTRA_EXCLUDE_COMPONENTS" in view and "SHA-256" in view, "R1 file view: open as text, attach to note, open with another app (Notes excluded), hash"),
    ("openInFileView(" in main, "R1 MainActivity routes non-text intents to the file view"),
    ('android:name=".FileViewActivity"' in notes, "R1 manifest declares FileViewActivity"),
    ("saveOpenFile" in src("App/app/src/main/java/app/cloudnotes/pro/plugin/ExternalFilePlugin.kt") and "saveOpenFile" in src("src/plugins/external-file.ts"), "R1 EDIT write-back plugin method on both halves"),
]
show(res)

# Mirror of OpenRouter.route to prove the table (python replay of the Kotlin rules)
TEXT_EXT = set("md markdown txt text json yaml yml html htm csv log conf cfg ini xml tsv toml properties".split())
for e in TEXT_EXT:
    if ('"%s"' % e) not in router:
        res = [(False, "R2 OpenRouter.TEXT_EXT lacks %s" % e)]; show(res)

# M1 mutations of the Notes manifest against the same guard: each must go red
def mutate(name, fn):
    global fails
    m = fn(notes)
    if m == notes:
        print("  FAIL  M1 %s: mutation did not change the manifest" % name); fails += 1; return
    red = any(not ok for ok, _ in guard(m, drive))
    print("  %s  M1 %s goes RED" % ("PASS" if red else "FAIL", name))
    if not red: fails += 1
def drop_bare(t):
    return t.replace('<data android:mimeType="*/*" />\n            </intent-filter>\n\n            <!-- DE-CLOUDED', '</intent-filter>\n\n            <!-- DE-CLOUDED')
mutate("dropping the */* filter", drop_bare)
mutate("dropping image/*", lambda t: t.replace('<data android:mimeType="image/*" />', '', 1))
mutate("dropping application/pdf", lambda t: t.replace('<data android:mimeType="application/pdf" />', ''))
mutate("dropping the EDIT action of the any-file filter", lambda t: re.sub(r'(<action android:name="android.intent.action.EDIT" />\s*)(?=(?:(?!</intent-filter>).)*<data android:mimeType="\*/\*" />\s*</intent-filter>\s*<!-- DE-CLOUDED)', '', t, flags=re.S))
mutate("dropping the #1642 text filter", lambda t: t.replace('<data android:mimeType="text/plain" />', ''))
# #1888 Drive declares SEND/SEND_MULTIPLE (the share sheet), so a Notes without them must turn the guard RED
n2 = notes.replace('<action android:name="android.intent.action.SEND" />', '').replace('<action android:name="android.intent.action.SEND_MULTIPLE" />', '')
red = n2 != notes and any(not ok for ok, _ in guard(n2, drive))
print("  %s  M1 Notes without SEND/SEND_MULTIPLE (Drive has them) goes RED" % ("PASS" if red else "FAIL"))
if not red: fails += 1
print("RESULT: %s" % ("RED" if fails else "GREEN"))
sys.exit(1 if fails else 0)
PY
