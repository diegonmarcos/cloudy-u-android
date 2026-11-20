#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-chronos — Cloud Wiki's Chronos pages (#1789)                 ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The Chronos pages are diegonmarcos/lib-chronos at a pinned commit (tools/chronos-pin.json), baked into assets/chronos by
# tools/regen-chronos.py and declared as nested pages under build.json::ui wiki > chronos. This proves:
#   C1  THE TREE GUARD: the pages build.json declares are exactly the page tree of the committed assets (one per index.html, the repo's
#       hierarchy, ids from the directory numbers, labels the pages' own <title>, icons from the pin), and every declared asset exists
#       and no baked page is a "Simulator pending upload" placeholder
#   C2  the vendored CDN files hash to their pins, nothing vendored is unpinned, no page or stylesheet loads from the network, and no
#       path has a segment aapt's default ignoreAssetsPattern drops (`<dir>_*`, dot files): it would be committed but missing from the APK
#   C3  the generated tree says what it is (GENERATED.md, DO NOT EDIT, the pinned commit)
#   C4  THE DRIFT CHECK (network): regenerating from lib-chronos at the pin gives the committed assets and declaration byte for byte
#       (regen-chronos.py --check); the repo moving past the pin is a warning, never a silent change
#   C5  the page: the fleet web view (CloudWeb.create, WebProfile.APP_PAGE) through WebViewAssetLoader on the asset origin, every
#       off-origin request answered 403, no JavaScript bridge, the served path and the assets dir are the pin's; Decl.chronos reads the
#       baked ui.sections and WikiPage dispatches a Chronos page before the dataset check
#   C6  the app links libs:web-core (build.json modules, app gradle) and webview-guard.json declares the usage
#   C7  the emulator screenshot script photographs the Chronos section and a deep simulator page
# then plants a mutation per offline rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
SCRIPT="app/build.gra""dle"

check() {   # check <app dir> <repo root> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$2" "$SCRIPT" <<'PY'
import json, os, re, subprocess, sys
app, root, gradle = sys.argv[1], sys.argv[2], sys.argv[3]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

# C1-C3: the tool's own offline guard over this copy
r = subprocess.run([sys.executable, os.path.join(app, "tools/regen-chronos.py"), "--guard"], capture_output=True, text=True)
bad += [l[5:] for l in r.stdout.splitlines() if l.startswith("FAIL ")]
if r.returncode != 0 and not any(l.startswith("FAIL ") for l in r.stdout.splitlines()):
    bad.append("C1 regen-chronos.py --guard failed: " + (r.stderr.strip() or r.stdout.strip())[:300])

# C1b: no baked Chronos page is a placeholder
for dp, _, fs in os.walk(os.path.join(app, "app/src/main/assets/chronos/Chronos")):
    for f in fs:
        # #1830 the ONE page allowed to wait: Humansphere, until the owner uploads its simulator (the generated one was removed)
        if "0.Humansphere-Circadian" in dp: continue
        if f.endswith(".html") and "Simulator pending upload" in read(os.path.join(dp, f)):
            bad.append("C1 %s is a placeholder (Simulator pending upload)" % os.path.relpath(os.path.join(dp, f), app))

pin = json.loads(read(os.path.join(app, "tools/chronos-pin.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudwiki")
page, model, decl, pages = (code(os.path.join(kt, f)) for f in ("ui/ChronosPage.kt", "Chronos.kt", "Decl.kt", "ui/WikiPages.kt"))

# C5
if "CloudWeb.create(" not in page or "WebProfile.APP_PAGE" not in page: bad.append("C5 the Chronos page is not the fleet web view (CloudWeb.create, WebProfile.APP_PAGE)")
if "WebViewAssetLoader" not in page or "addPathHandler(Chronos.SERVE_PATH" not in page: bad.append("C5 the Chronos page is not served by WebViewAssetLoader on Chronos.SERVE_PATH")
if not re.search(r"if \(!onOrigin\(u\)\) return blocked\(\)", page): bad.append("C5 an off-origin request is not answered 403: JavaScript is not scoped to the asset origin")
if not re.search(r'u\.scheme == "https" && u\.host == CloudWeb\.ASSET_HOST', page): bad.append("C5 the origin test is not https on CloudWeb.ASSET_HOST")
for dp, _, fs in os.walk(os.path.join(app, "app/src/main")):
    for f in fs:
        if f.endswith(".kt") and re.search(r"addJavascriptInterface|addWebMessageListener", code(os.path.join(dp, f))):
            bad.append("C5 %s gives the Chronos pages a JavaScript bridge into the app" % f)
m = re.search(r'SERVE_PATH\s*=\s*"([^"]+)"', model)
if not m or m.group(1) != pin["serve_path"]: bad.append("C5 Chronos.SERVE_PATH is not tools/chronos-pin.json::serve_path %r" % pin["serve_path"])
m = re.search(r'ASSETS_DIR\s*=\s*"([^"]+)"', model)
if not m or "app/src/main/assets/" + m.group(1).rstrip("/") != pin["assets_dir"]: bad.append("C5 Chronos.ASSETS_DIR is not tools/chronos-pin.json::assets_dir")
for k, v in (("PARENT", pin["nav"]["parent"]), ("SECTION", pin["nav"]["section"])):
    m = re.search(r'\b%s\s*=\s*"([^"]+)"' % k, model)
    if not m or m.group(1) != v: bad.append("C5 Chronos.%s is not the pin's nav.%s %r" % (k, k.lower(), v))
if "Chronos.parse(" not in decl or "UI_SECTIONS_B64" not in decl: bad.append("C5 Decl.chronos does not read the baked ui.sections")
i, j = pages.find("Decl.chronos.page("), pages.find("data == null")
if i < 0 or j < 0 or i > j: bad.append("C5 WikiPage does not show a Chronos page before (independently of) the dataset check")

# C6
bj = json.loads(read(os.path.join(app, "build.json")))
if "libs:web-core" not in bj["modules"] or "libs:web-core" not in bj["modules"]["app"]["depends_on"]: bad.append("C6 build.json modules do not link libs:web-core")
if "project(':libs:web-core')" not in read(os.path.join(app, gradle)): bad.append("C6 the app's build script does not link libs:web-core")
wg = json.loads(read(os.path.join(root, "1_cicd/src/data/webview-guard.json")))
if not any(u["file"] == "ac_cloud-wiki/app/src/main/java/com/diegonmarcos/cloudwiki/ui/ChronosPage.kt" and u["profile"] == "APP_PAGE" for u in wg["usages"]):
    bad.append("C6 webview-guard.json declares no APP_PAGE usage for ChronosPage.kt")

# C7
em = read(os.path.join(app, "test/emulator-wiki-screens.sh"))
for shot in ("wiki-chronos", "wiki-chronos-deep"):
    if not re.search(r"\bshot %s\b" % shot, em): bad.append("C7 emulator-wiki-screens.sh does not capture %s" % shot)

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[chronos] offline rules"
out="$(check "$APP" "$ROOT")" && ok "C1-C3, C5-C7 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

echo "[chronos] drift against lib-chronos at the pin (network)"
if out="$(python3 "$APP/tools/regen-chronos.py" --check 2>&1)"; then ok "C4 $(tail -1 <<<"$out")"; grep '^::warning::' <<<"$out" || true
else bad "C4 the baked Chronos pages drifted from the pin (or the pin could not be reached: a check that cannot reach its subject fails)"; echo "$out"; fi

# mutations on a scratch copy: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <shell edit run in the copy (cwd = copy of the app)> <needle expected in the output>
  rm -rf "$W/root"; mkdir -p "$W/root/1_cicd/src/data"; cp -r "$APP" "$W/root/app"; cp "$ROOT/1_cicd/src/data/webview-guard.json" "$W/root/1_cicd/src/data/"
  ( cd "$W/root/app" && eval "$2" )
  if out="$(check "$W/root/app" "$W/root" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudwiki
A=app/src/main/assets/chronos
DROP="import json;p='build.json';d=json.load(open(p));c=[x for x in [s for s in d['ui']['sections'] if s['id']=='wiki'][0]['pages'] if x['id']=='chronos'][0]"
mutant "a page is not declared"     "python3 -c \"$DROP;c['pages'].pop();json.dump(d,open(p,'w'),indent=2)\""                   "C1 build.json::ui chronos pages"
mutant "a label is retyped"         "python3 -c \"$DROP;c['pages'][0]['label']='Chronos';json.dump(d,open(p,'w'),indent=2)\""                "C1 build.json::ui chronos pages carry other labels"
mutant "the hierarchy is flattened" "python3 -c \"$DROP;q=[x for x in c['pages'] if x.get('pages')][0];c['pages']+=q.pop('pages');json.dump(d,open(p,'w'),indent=2)\"" "C1 build.json::ui chronos pages"
mutant "a page is a placeholder"    "printf '<title>x</title><p>Simulator pending upload.</p>' > $A/Chronos/1.Earthsphere-Cycles/index.html"  "is a placeholder"
mutant "a page appears by hand"     "mkdir -p $A/Chronos/5.Extra && printf '<title>Extra</title>' > $A/Chronos/5.Extra/index.html"           "C1 build.json::ui chronos pages"
mutant "an asset goes missing"      "rm $A/Chronos/2.Milkwaysphere-Orbits/index.html"                                                       "C1"
mutant "a vendored file is edited"  "echo '//x' >> $A/vendor/tailwindcss/3.4.17/tailwind.js"                                              "C2 vendored file vendor/tailwindcss/3.4.17/tailwind.js does not hash"
mutant "an unpinned vendor file"    "echo x > $A/vendor/extra.js"                                                                          "C2 vendored file vendor/extra.js has no sha256"
mutant "a page loads a CDN again"   "sed -i 's|</head>|<script src=\"https://cdn.example/x.js\"></script></head>|' $A/Chronos/index.html"   "C2 Chronos/index.html loads from the network"
mutant "a packager-ignored dir"     "mkdir -p $A/_hidden && mv $A/vendor/tailwindcss $A/_hidden/"                                       "C2 _hidden/tailwindcss/3.4.17/tailwind.js has a path segment the Android asset packager ignores"
mutant "the header is dropped"      "echo '# hand-made' > $A/GENERATED.md"                                                                  "C3 GENERATED.md is missing"
mutant "the web view is hand-built" "sed -i 's/CloudWeb.create(/Foo(/' $KT/ui/ChronosPage.kt"                                               "C5 the Chronos page is not the fleet web view"
mutant "off-origin requests pass"   "sed -i 's/if (!onOrigin(u)) return blocked()/if (false) return blocked()/' $KT/ui/ChronosPage.kt"     "C5 an off-origin request is not answered 403"
mutant "a JavaScript bridge"        "echo 'fun b(w: android.webkit.WebView) = w.addJavascriptInterface(Any(), \"X\")' >> $KT/ui/ChronosPage.kt" "C5 ChronosPage.kt gives the Chronos pages a JavaScript bridge"
mutant "the served path moves"      "sed -i 's|SERVE_PATH = \"/chronos/\"|SERVE_PATH = \"/sims/\"|' $KT/Chronos.kt"                        "C5 Chronos.SERVE_PATH is not"
mutant "chronos waits for the data" "sed -i 's/Decl.chronos.page(/Decl.chronosX(/' $KT/ui/WikiPages.kt"                                     "C5 WikiPage does not show a Chronos page"
mutant "web-core is unlinked"       "sed -i \"s/implementation project(':libs:web-core')//\" $SCRIPT"                                       "C6 the app's build script does not link libs:web-core"
mutant "the usage is undeclared"    "python3 -c \"import json;p='../1_cicd/src/data/webview-guard.json';d=json.load(open(p));d['usages']=[u for u in d['usages'] if 'cloudwiki' not in u['file']];json.dump(d,open(p,'w'))\"" "C6 webview-guard.json declares no APP_PAGE usage"
mutant "no deep screenshot"         "sed -i 's/shot wiki-chronos-deep/shot wiki-other/' test/emulator-wiki-screens.sh"                      "C7 emulator-wiki-screens.sh does not capture wiki-chronos-deep"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
