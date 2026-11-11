#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1652 FIELD PARITY: cloud-writer's own app is unchanged by the extraction into libs
# ╚══════════════════════════════════════════════════════════════════╝
#
# The app's UI moved into libs:writer-ui (WriterSurface + the tool pages) and its pure logic into
# libs:writer-core, so cloud-keyboard's Writer tab can host the same code. This certifies, statically,
# that the app lost nothing and declares nothing twice. It is held to fixtures/parity-baseline.json,
# taken from the tree BEFORE the move (origin/main 5476fcdd8).
#
#   F1 the app's manifest declares exactly what it declared: every permission, <queries>, the application
#      attributes, MainActivity + its three intent filters, the five tool activities, ListenService, the OCR
#      FileProvider and the debug-API provider - each ONCE (nothing double-declared).
#   F2 libs:writer-ui declares NO manifest component (the host owns the manifest) and no uses-permission.
#   F3 nothing lost: every Kotlin file the app had is in the app or the lib (MainActivity.kt became the thin shell
#      + WriterSurface.kt), every core main/test file is in libs:writer-core, every string (en, es) is in the lib,
#      both AIDL files are in the lib and not in the app, and every function of the old MainActivity is a
#      function of WriterSurface (or the shell).
#   F4 the five bottom-nav pages, the five Tools pages, Dictation and the editor are individually addressable:
#      WriterSurface.Page(id) draws each, and the ids are build.json::ui's own.
#   F5 the app's MainActivity is a THIN SHELL (no screen of its own) hosting WriterSurface.Content().
#   F6 libs:writer-ui bakes every BuildConfig field the app baked, and the app no longer bakes the moved ones.
#   F7 no class is defined twice (app vs lib) and no source remains in ac_cloud-writer/core.
# Each check is then run against planted regressions; a plant that leaves every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import copy, json, os, re, sys
import xml.etree.ElementTree as ET

APP = os.environ["APP_DIR"]
LIB = os.path.normpath(os.path.join(APP, "..", "ab_cloud-libs-shared", "libs"))
UI = os.path.join(LIB, "writer-ui")
CORE = os.path.join(LIB, "writer-core")
NS = "{http://schemas.android.com/apk/res/android}"
BASE = json.load(open(os.path.join(APP, "test", "fixtures", "parity-baseline.json")))


def walk(top):
    out = []
    for d, _dirs, files in os.walk(top):
        if "/build" in d.replace(top, ""):
            continue
        for n in files:
            out.append(os.path.relpath(os.path.join(d, n), top))
    return sorted(out)


def code(text):
    """Kotlin/Gradle with comments blanked (string literals kept, so "text/*" is not a comment)."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j; continue
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < n and text[j] not in c + "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)


def attrs(e):
    return {k.replace(NS, "android:"): v for k, v in e.attrib.items()}


def manifest_model(path):
    man = ET.parse(path).getroot()
    app = man.find("application")
    comps = []
    for e in (list(app) if app is not None else []):
        c = {"tag": e.tag, "attrs": attrs(e), "filters": []}
        for f in e.findall("intent-filter"):
            c["filters"].append({"actions": sorted(a.get(NS + "name") for a in f.findall("action")),
                                 "categories": sorted(a.get(NS + "name") for a in f.findall("category")),
                                 "mime": sorted(a.get(NS + "mimeType") for a in f.findall("data"))})
        for m in e.findall("meta-data"):
            c.setdefault("meta", []).append(attrs(m))
        comps.append(c)
    q = man.find("queries")
    return {"uses_permissions": sorted(u.get(NS + "name") for u in man.findall("uses-permission")),
            "queries": [p.get(NS + "name") for p in q] if q is not None else [],
            "application": attrs(app) if app is not None else {}, "components": comps}


def strings(path):
    return sorted(x.get("name") for x in ET.parse(path).getroot() if x.tag in ("string", "plurals", "string-array"))


def load():
    f = {}
    f["app_manifest"] = manifest_model(os.path.join(APP, "app/src/main/AndroidManifest.xml"))
    f["lib_manifest"] = manifest_model(os.path.join(UI, "src/main/AndroidManifest.xml"))
    f["app_files"] = walk(os.path.join(APP, "app/src/main/java"))
    f["lib_files"] = walk(os.path.join(UI, "src/main/java"))
    f["core_main"] = walk(os.path.join(CORE, "src/main/kotlin"))
    f["core_test"] = walk(os.path.join(CORE, "src/test/kotlin"))
    f["old_core_left"] = walk(os.path.join(APP, "core")) if os.path.isdir(os.path.join(APP, "core")) else []
    f["strings"] = strings(os.path.join(UI, "src/main/res/values/strings.xml"))
    f["strings_es"] = strings(os.path.join(UI, "src/main/res/values-es/strings.xml"))
    f["app_strings_left"] = os.path.exists(os.path.join(APP, "app/src/main/res/values/strings.xml"))
    f["lib_aidl"] = walk(os.path.join(UI, "src/main/aidl"))
    f["app_aidl"] = walk(os.path.join(APP, "app/src/main/aidl")) if os.path.isdir(os.path.join(APP, "app/src/main/aidl")) else []
    rd = lambda p: open(p, encoding="utf-8").read()
    j = "src/main/java/com/diegonmarcos/cloudwriter/"
    f["surface"] = code(rd(os.path.join(UI, j + "WriterSurface.kt")))
    f["shell"] = code(rd(os.path.join(APP, "app/src/main/java/com/diegonmarcos/cloudwriter/MainActivity.kt")))
    f["lib_gradle"] = code(rd(os.path.join(UI, "build.gradle")))
    f["app_gradle"] = code(rd(os.path.join(APP, "app/build.gradle")))
    f["build_json"] = json.loads(rd(os.path.join(APP, "build.json")))
    return f


def find_comp(model, name):
    return [c for c in model["components"] if c["attrs"].get("android:name") == name]


def checks(f):
    bad = []
    need = lambda label, ok: bad.append(label) if not ok else None
    bm = BASE["manifest"]
    am = f["app_manifest"]
    # F1
    need("F1 the app's uses-permissions are exactly the baseline's", am["uses_permissions"] == bm["uses_permissions"])
    need("F1 the app's <queries> are the baseline's", am["queries"] == bm["queries"])
    need("F1 the application attributes are the baseline's", am["application"] == bm["application"])
    for c in bm["components"]:
        name = c["attrs"].get("android:name")
        got = find_comp(am, name)
        need("F1 %s is declared exactly once in the app manifest (found %d)" % (name, len(got)), len(got) == 1)
        if len(got) == 1:
            need("F1 %s keeps its attributes, intent filters and meta-data" % name, got[0] == c)
    need("F1 the app manifest declares nothing the baseline did not (%d components)" % len(bm["components"]),
         len(am["components"]) == len(bm["components"]))
    # F2
    lm = f["lib_manifest"]
    need("F2 libs:writer-ui's manifest declares no component (the host owns the manifest)", lm["components"] == [])
    need("F2 libs:writer-ui's manifest declares no uses-permission and no <queries>", lm["uses_permissions"] == [] and lm["queries"] == [])
    # F3
    have = set(f["app_files"]) | set(f["lib_files"])
    for p in BASE["app_kotlin"]:
        want = p.replace("MainActivity.kt", "WriterSurface.kt")
        need("F3 %s is still in the app or the lib" % p, want in have)
    need("F3 the app still has a MainActivity.kt (the thin shell)", any(p.endswith("/MainActivity.kt") for p in f["app_files"]))
    need("F3 every core main file is in libs:writer-core", all(p in f["core_main"] for p in BASE["core_main"]))
    need("F3 every core test file is in libs:writer-core", all(p in f["core_test"] for p in BASE["core_test"]))
    need("F3 every string (en) is in libs:writer-ui", all(s in f["strings"] for s in BASE["strings"]))
    need("F3 every string (es) is in libs:writer-ui", all(s in f["strings_es"] for s in BASE["strings_es"]))
    need("F3 the AIDL files moved into libs:writer-ui", sorted(f["lib_aidl"]) == sorted(BASE["aidl"]))
    need("F3 no AIDL copy is left in the app (a second copy is a duplicate class)", f["app_aidl"] == [])
    fns = set(re.findall(r"\bfun\s+(?:[A-Za-z<>?., ]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", f["surface"] + f["shell"]))
    # the lifecycle overrides became observer methods of the same names; everything else keeps its name
    lost = [n for n in BASE["main_activity_functions"] if n not in fns]
    need("F3 every function of the old MainActivity is a function of WriterSurface (lost: %s)" % ", ".join(lost), not lost)
    # F4
    s = f["surface"]
    nav = BASE["nav"]
    need("F4 build.json::ui.bottom_nav is still %s" % nav["bottom_nav"], f["build_json"]["ui"]["bottom_nav"] == nav["bottom_nav"])
    need("F4 the Tools pages are still %s" % nav["tools_pages"],
         [p["id"] for sec in f["build_json"]["ui"]["sections"] if sec["id"] == "tools" for p in sec["pages"]] == nav["tools_pages"])
    need("F4 WriterSurface exposes Content() and Page(id)", "fun Content(" in s and re.search(r"fun Page\(id: String", s) is not None)
    need("F4 the bottom-nav pages are WriterTabs.ORDER", "BOTTOM_NAV_PAGES: List<String> = WriterTabs.ORDER" in s)
    for tid in nav["tools_pages"]:
        need("F4 the Tools page %s has a page id" % tid, re.search(r'PAGE_TOOL_%s\s*=\s*"tools\.%s"' % (tid.upper(), tid), s) is not None)
    for const, screen in (("PAGE_ENHANCER", "EnhancerScreen()"), ("PAGE_DRAFTER", "DrafterScreen()"), ("PAGE_TRANSLATOR", "TranslatorScreen()"),
                          ("PAGE_GRAMMAR", "GrammarScreen()"), ("PAGE_TOOLS", "ToolsScreen()"), ("PAGE_CONFIGS", "ConfigsScreen()"),
                          ("PAGE_DICTATION", "DictationPage()")):
        need("F4 Page() draws %s with %s" % (const, screen), re.search(r"%s\s*->[^\n]*%s|%s\s*->\s*\{[^}]*%s" % (const, re.escape(screen), const, re.escape(screen)), s) is not None)
    need("F4 Page() draws Home and the editor in place", re.search(r"PAGE_HOME, PAGE_EDITOR\s*->", s) is not None and "EditorScreen()" in s and "DocsScreen()" in s)
    for page in ("TextEnhancePage", "TranslationPage", "GrammarCheckPage", "AiRoutingPage", "RoutesPage"):
        need("F4 Page() builds the tool page %s" % page, "%s(activity)" % page in s)
    need("F4 the host can add pages (the keyboard's Dictionary)", "hostPages" in s and "PAGE_DICTIONARY" in s)
    # F5
    sh = f["shell"]
    need("F5 MainActivity hosts WriterSurface.Content()", "WriterSurface(this)" in sh and "surface.Content(" in sh)
    need("F5 MainActivity is thin (no @Composable of its own, under 60 lines of code)", "@Composable" not in sh and len([l for l in sh.splitlines() if l.strip()]) < 60)
    # F6
    for fld in BASE["buildconfig_fields"]:
        need("F6 libs:writer-ui bakes %s" % fld, fld in f["lib_gradle"])
    for fld in BASE["buildconfig_fields"]:
        if fld != "GIT_SHORT_SHA":
            need("F6 the app no longer bakes %s (one place only)" % fld, '"%s"' % fld not in f["app_gradle"])
    # F7
    names = lambda files: {os.path.basename(p) for p in files}
    twice = (names(f["app_files"]) & names(f["lib_files"])) - {"MainActivity.kt"}
    need("F7 no source file is defined in both the app and the lib (%s)" % ", ".join(sorted(twice)), not twice)
    need("F7 nothing is left in ac_cloud-writer/core", f["old_core_left"] == [])
    need("F7 the app declares no strings.xml (strings live once, in the lib)", not f["app_strings_left"])
    return bad


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     parity holds: %d manifest components once each, %d strings, %d core files, %d page ids" %
      (len(BASE["manifest"]["components"]), len(real["strings"]), len(real["core_main"]) + len(real["core_test"]), len(re.findall(r"const val PAGE_", real["surface"]))))


def mutate(label, fn):
    g = copy.deepcopy(real)
    fn(g)
    if not checks(g):
        print("FAIL     plant survived: " + label); return 1
    print("PASS     plant caught: " + label); return 0


def dup_listen(g):
    g["app_manifest"]["components"].append(copy.deepcopy(find_comp(g["app_manifest"], "com.diegonmarcos.cloudwriter.ListenService")[0]))

def lib_declares(g):
    g["lib_manifest"]["components"].append(copy.deepcopy(find_comp(g["app_manifest"], "com.diegonmarcos.cloudwriter.ListenService")[0]))

def lose_activity(g):
    g["app_manifest"]["components"] = [c for c in g["app_manifest"]["components"] if not c["attrs"].get("android:name", "").endswith("RoutesActivity")]

def lose_string(g):
    g["strings"] = g["strings"][1:]

def lose_core_file(g):
    g["core_main"] = g["core_main"][1:]

def lose_function(g):
    g["surface"] = g["surface"].replace("fun requestListen", "fun requestListenX")

def lose_page(g):
    g["surface"] = g["surface"].replace("PAGE_GRAMMAR ->", "PAGE_GRAMMARX ->")

def fat_shell(g):
    g["shell"] += "\n@Composable fun Screen() {}\n"

def dup_aidl(g):
    g["app_aidl"] = ["com/diegonmarcos/cloudkeyboardlibs/IVoiceEngine.aidl"]

def lose_field(g):
    g["lib_gradle"] = g["lib_gradle"].replace("WRITER_ROUTES_B64", "WRITER_ROUTES_X")

def dup_class(g):
    g["app_files"] = g["app_files"] + ["com/diegonmarcos/cloudwriter/WriterPrefs.kt"]

survivors = sum(mutate(l, fn) for l, fn in (
    ("ListenService declared twice", dup_listen), ("the lib declares a component", lib_declares), ("an activity is dropped from the manifest", lose_activity),
    ("a string is lost", lose_string), ("a core file is lost", lose_core_file), ("a MainActivity function is lost", lose_function),
    ("a page is no longer addressable", lose_page), ("the shell grows a screen", fat_shell), ("the AIDL is duplicated in the app", dup_aidl),
    ("a BuildConfig field is no longer baked", lose_field), ("a class is defined twice", dup_class)))
sys.exit(1 if survivors else 0)
PY
