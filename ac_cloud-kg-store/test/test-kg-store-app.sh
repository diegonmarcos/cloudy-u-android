#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-kg-store-app — Cloud KG Store (#1207), statically            ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The assistant (page, streaming, sessions, Markdown) is libs:ai's and is proven there. The Web sources and parsers and the code-index
# protocol are Cloud Search's search-core and are proven in its suite. This proves, from the sources alone, that this app only ASSEMBLES
# them, on the fleet's bars, with the data it shows coming from declarations and from the phone:
#   B1  every section id Decl.kt dispatches on is declared by build.json::ui and every declared page is data (data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json::pages) or a
#       settings page Decl.kt names, both ways; the bottom nav is Cloud, Phone, Web; Cloud is horizontal_two_rows with the groups
#       Application (Binary, Web/Debug, MCP, API Data, API Debug) and Repo (Code, CGC, MCP, API Semantic, API GraphRAG); Web is News, Media,
#       Shopping, Real Estate, Jobs, Business; every page has an icon
#   B2  the shell draws the shared bars: first-row scaffold (Profile -> Cloud Account launcher), second row, bottom nav host,
#       theme-background; the nav libs are in build.json modules AND in the app's build script
#   B3  the assistant is libs:ai's: AiChatPage + the sessions drawer panel in the top bar's drawer + Markdown on; the shell draws no list or input of its own
#   B4  the backend is the fleet gateway: the gateway script applied, BuildConfig.AI_GATEWAY_URL baked and read, and no token handling
#       (no revealAiKey, no EncryptedSharedPreferences, no key-shaped literal, nothing logged, the bearer never written to prefs): decisions-use guard D5
#   B5  Profile opens Cloud Account, the manifest can see its package and the installed apps, the launcher shows the app and Share is declared
#   B6  no mock data and no spelled host: no URL in the Kotlin, no stand-in list of apps or services; a page with nothing declared says so (`missing`)
#   B7  the sources are Cloud Search's: search-core is linked by reference, its build.json::search is baked by reference, this app defines no
#       parser, no engine and no config reader of its own; the fleet services are baked from ac_cloud-account's declaration
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudkgstore")
decl, shell, ai, main = (code(os.path.join(kt, f)) for f in ("Decl.kt", "ui/KgShell.kt", "KgAi.kt", "MainActivity.kt"))
script = code(os.path.join(app, "app/build." + "gradle"))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
kg = json.loads(read(os.environ.get("KG_JSON") or os.path.join(app, "..", "data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json")))
sections = {s["id"]: s for s in bj["ui"]["sections"]}

# B1
named_sections = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl))
named_pages = set(re.findall(r'PAGE_\w+\s*=\s*"([^"]+)"', decl))
for x in sorted(named_sections - set(sections)): bad.append("B1 Decl.kt names section %r that build.json::ui does not declare" % x)
for x in sorted(set(sections) - named_sections): bad.append("B1 section %r is declared but Decl.kt has no id for it (the shell would draw nothing)" % x)
data_sections = ("cloud", "phone", "api")
data_pages = [p["id"] for s in data_sections for p in sections[s]["pages"]] if all(s in sections for s in data_sections) else []
if set(data_pages) != set(kg["pages"]): bad.append("B1 kg.json::pages and build.json::ui (cloud, phone, api) differ: %s" % sorted(set(data_pages) ^ set(kg["pages"])))
settings_pages = {p["id"] for p in sections.get("settings", {"pages": []})["pages"]}
if settings_pages != named_pages: bad.append("B1 Decl.kt PAGE_ ids %s are not the Settings pages %s" % (sorted(named_pages), sorted(settings_pages)))
if bj["ui"]["bottom_nav"] != ["api", "scrapped", "home", "me", "cloud"]: bad.append("B1 the bottom nav must be API, Scrapped, Home, Me, Cloud, in that order (#1640)")
for sid, lab in (("api", "API"), ("scrapped", "Scrapped"), ("home", "Home"), ("me", "Me"), ("cloud", "Cloud")):
    if sections.get(sid, {}).get("label") != lab: bad.append("B1 section %r is not labelled %r" % (sid, lab))
cloud = sections.get("cloud", {})
if cloud.get("nav_mode") != "horizontal_two_rows": bad.append("B1 the Cloud section is not horizontal_two_rows")
labels = {p["id"]: p["label"] for p in cloud.get("pages", [])}
want = [("Application", ["Binary", "Web/Debug", "MCP", "API Data", "API Debug"]), ("Repo", ["Code", "CGC", "MCP", "API Semantic", "API GraphRAG"])]
got = [(g["label"], [labels.get(i) for i in g["pages"]]) for g in cloud.get("groups", [])]
if got != want: bad.append("B1 the Cloud groups are not Application and Repo with their five pages each: %s" % got)
if [p["label"] for p in sections.get("api", {"pages": []})["pages"]] != ["News", "Media", "Shopping", "Real Estate", "Jobs", "Business"]: bad.append("B1 the API pages are not News, Media, Shopping, Real Estate, Jobs, Business")
if [p["label"] for p in sections.get("phone", {"pages": []})["pages"]] != ["Apps"]: bad.append("B1 the Phone section is not the Apps index")
for s in bj["ui"]["sections"]:
    for p in s.get("pages", []):
        if not p.get("icon"): bad.append("B1 page %r has no icon (the second row draws one per tab)" % p["id"])

# B8 (#1640) Scrapped reads the shared headless declaration by reference; Me clones at runtime and serves; nothing baked, nothing spelled.
# #1547 both declarations reach the app through its runtime dataset (kg.json::scrapped.headless, kg.json::me), never through BuildConfig:
# the dataset must hold exactly what the reference and build.json::ui.me declare.
ui = bj["ui"]
if ui.get("scrapped", {}).get("source") != "../ac_cloud-browser/build.json" or ui["scrapped"].get("path") != ["ui", "domains", "headless"]: bad.append("B8 ui.scrapped does not reference ac_cloud-browser/build.json::ui.domains.headless")
if "HEADLESS_B64" in script or "ME_REPO_B64" in script: bad.append("B8 the build script bakes the headless declaration or the Me repo declaration (#1547: they are read from the kg dataset)")
try:
    shared = json.loads(read(os.environ.get("BROWSER_JSON") or os.path.join(app, ui["scrapped"]["source"])))
    for k in ui["scrapped"]["path"]: shared = shared[k]
except Exception as e:
    shared = None
    bad.append("B8 the referenced headless declaration cannot be read: %s" % e)
if not shared or kg.get("scrapped", {}).get("headless") != shared: bad.append("B8 kg.json::scrapped.headless is not the referenced headless declaration (the Scrapped page would list another stack)")
want_me = {"owner": ui.get("me", {}).get("owner"), "repo": ui.get("me", {}).get("repo"), "ref": ui.get("me", {}).get("ref", "main"), "entry": ui.get("me", {}).get("entry", "")}
if {k: v for k, v in kg.get("me", {}).items() if not k.startswith("_")} != want_me: bad.append("B8 kg.json::me is not build.json::ui.me (the Me tab would clone another repo)")
if "svc.kg.headless" not in code(os.path.join(kt, "ui/NativePages.kt")) or "svc.kg.me" not in code(os.path.join(kt, "ui/NativePages.kt")): bad.append("B8 the Scrapped and Me pages do not read the kg dataset")
if not ui.get("me", {}).get("owner") or not ui["me"].get("repo"): bad.append("B8 ui.me owner/repo are not declared")
if any(re.search(r"token|secret|password|bearer", k, re.I) for k in ui.get("me", {})): bad.append("B8 ui.me carries a credential key")
for lib in ("libs:local-serve", "libs:git-ui"):
    if lib not in bj["modules"]: bad.append("B8 build.json modules lack %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("B8 the app's build script does not link %s" % lib)
native = code(os.path.join(kt, "ui/NativePages.kt"))
if "GitConnect(" not in native or "ServedPage(" not in native: bad.append("B8 the Me page must clone through GitConnect and serve through ServedPage")
if "assets" in native or "R.raw" in native: bad.append("B8 the Me page reads baked content")

# B2
for need in ("NavBarTopFirstRowScaffold(", "NavSectionLayout(", "NavBarBottomHost(", "themeBackground(", "rememberAccountLauncher()", "titleHierarchy("):
    if need not in shell: bad.append("B2 KgShell does not call %s" % need)
mods = bj["modules"]
for lib in ("libs:ui-nav-bars", "libs:theme-background", "libs:bottomnav", "libs:ai", "libs:ui-icons"):
    if lib not in mods: bad.append("B2 build.json modules lack %s" % lib)
for lib in ("libs:ui-nav-bars", "libs:theme-background", "libs:bottomnav", "libs:ai"):
    if "project(':%s')" % lib not in script: bad.append("B2 the app's build script does not link %s" % lib)

# B3
for need in ("AiChatPage(", "AiSessionsPanel(", "TopBarDrawer", "markdown = true"):
    if need not in shell + code(os.path.join(kt, "ui/KgShell.kt")): bad.append("B3 KgShell lacks %s" % need)
if re.search(r"\b(LazyColumn|BasicTextField|OutlinedTextField)\b", shell): bad.append("B3 KgShell draws list or input widgets of its own: the pages and the chat are elsewhere")

# B4
if "ai-gateway" not in script or "AI_GATEWAY_URL" not in script: bad.append("B4 the app's build script does not bake the gateway address")
if "BuildConfig.AI_GATEWAY_URL" not in ai: bad.append("B4 KgAi does not read BuildConfig.AI_GATEWAY_URL")
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith((".kt", ".java")):
            t = code(os.path.join(dp, f))
            for pat in (r"revealAiKey", r"EncryptedSharedPreferences", r"sk-or-[A-Za-z0-9]", r"android\.util\.Log", r"\bLog\.[diwev]\(", r"println\(", r"put(String|StringSet)\([^)]*(token|bearer)"):
                if re.search(pat, t, re.I): bad.append("B4 %s matches %s: this app holds and logs no token" % (f, pat))
            if re.search(r"Authorization", t) and f != "KgFleet.kt": bad.append("B4 %s names the Authorization header: only the core's route plan puts the bearer on a public route" % f)

# B5
if 'android:name="com.diegonmarcos.cloudaccount"' not in manifest: bad.append("B5 the manifest cannot see Cloud Account (queries)")
if "android.permission.QUERY_ALL_PACKAGES" not in manifest: bad.append("B5 the manifest lacks QUERY_ALL_PACKAGES: the installed apps index would show only the queried packages")
if "android.intent.category.LAUNCHER" not in manifest: bad.append("B5 no launcher activity")
if "android.intent.action.SEND" not in manifest or "ACTION_SEND" not in main: bad.append("B5 Share > Cloud KG Store is not declared and handled")
if bj["android"]["application_id"] != "com.diegonmarcos.cloudkgstore": bad.append("B5 application_id drifted")

# B6
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main/java")):
    for f in fn:
        if f.endswith(".kt"):
            t = code(os.path.join(dp, f))
            if re.search(r"https?://", t): bad.append("B6 %s spells a URL: every address comes from a declaration" % f)
            if re.search(r'"com\.diegonmarcos\.(?!cloudkgstore)[a-z.]+"', t) and f != "KgServices.kt": bad.append("B6 %s spells a fleet package: the fleet apps come from the generated manifest" % f)
mcp_empty = [p for p in kg["pages"].values() if p["kind"] == "mcp" and not p.get("services")]
if not mcp_empty or any(len(p.get("missing", "")) < 40 for p in mcp_empty): bad.append("B6 an MCP page with no declared service must carry its `missing` sentence")
strings = read(os.path.join(app, "app/src/main/res/values/strings.xml"))
for key in ("status_unavailable", "status_refused", "console_missing", "web_undeclared"):
    if 'name="%s"' % key not in strings: bad.append("B6 strings.xml lacks the honest state %s" % key)

# B7
if mods.get("search-core", {}).get("dir") != "../ac_cloud-search/core": bad.append("B7 search-core is not linked by reference to ../ac_cloud-search/core")
if "search-core" not in mods["app"]["depends_on"] or "project(':search-core')" not in script: bad.append("B7 the app does not link search-core")
if "ac_cloud-search/build.json" not in script or "SEARCH_CONFIG_B64" not in script: bad.append("B7 the build script does not bake Cloud Search's build.json::search")
if "ac_cloud-account/build.json" not in script or "FLEET_SERVICES_B64" not in script: bad.append("B7 the build script does not bake the fleet services from ac_cloud-account")
own = "\n".join(code(os.path.join(dp, f)) for dp, dn, fn in os.walk(os.path.join(app, "app/src/main/java")) for f in fn if f.endswith(".kt"))
for pat in (r"object\s+Parsers\b", r"class\s+SearchEngine\b", r"class\s+SearchConfig\b", r"object\s+Cgc\b", r"class\s+McpClient\b", r"class\s+FleetEndpoint\b", r"fun\s+parse\(\s*parser"):
    if re.search(pat, own): bad.append("B7 this app defines its own %s: the lib has it" % pat)
for need in ("SearchEngine(", "SearchConfig.parse(", "FleetEndpoint("):
    if need not in own: bad.append("B7 this app does not use the lib's %s" % need)

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[kg-store] assembly rules"
out="$(check "$APP")" && ok "B1-B7 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
SCRIPT="app/build.gra""dle"
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/app"; cp -r "$APP" "$W/app"
  cp "$APP/../data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json" "$W/app/kg.json"   # #1547 the declaration is served data, not an asset
  ( cd "$W/app" && eval "$2" )
  if out="$(KG_JSON="$W/app/kg.json" BROWSER_JSON="$APP/../ac_cloud-browser/build.json" check "$W/app" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudkgstore
mutant "section dropped from Decl"      "sed -i '/SECTION_API/d' $KT/Decl.kt"                                 "B1 section 'api' is declared"
mutant "settings page dropped from Decl" "sed -i '/PAGE_ABOUT/d' $KT/Decl.kt"                                  "B1 Decl.kt PAGE_ ids"
mutant "a data page loses its entry"   "python3 -c \"import json;p='kg.json';d=json.load(open(p));d['pages'].pop('web_jobs');json.dump(d,open(p,'w'))\"" "B1 kg.json::pages and build.json::ui"
mutant "bottom nav loses Me"            "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav']=['api','scrapped','home','cloud'];json.dump(d,open(p,'w'))\"" "B1 the bottom nav must be"
mutant "scrapped loses its reference"   "sed -i 's#../ac_cloud-browser/build.json#x/build.json#' build.json"      "B8 ui.scrapped does not reference"
mutant "me serve dropped"               "sed -i 's/ServedPage(/Foo(/' $KT/ui/NativePages.kt"                  "B8 the Me page must clone through GitConnect"
mutant "headless baked again"           "echo 'buildConfigField \"String\", \"HEADLESS_B64\", \"x\"' >> $SCRIPT"   "B8 the build script bakes"
mutant "headless copy drifts"           "python3 -c \"import json;p='kg.json';d=json.load(open(p));d['scrapped']['headless'].pop();json.dump(d,open(p,'w'))\"" "B8 kg.json::scrapped.headless is not"
mutant "me copy drifts"                 "python3 -c \"import json;p='kg.json';d=json.load(open(p));d['me']['repo']='other';json.dump(d,open(p,'w'))\"" "B8 kg.json::me is not"
mutant "pages read a baked copy"        "sed -i 's/svc.kg.headless/emptyList<HeadlessService>()/' $KT/ui/NativePages.kt" "B8 the Scrapped and Me pages do not read"
mutant "Cloud loses its two rows"       "sed -i 's/horizontal_two_rows/horizontal_one_row/' build.json"        "B1 the Cloud section is not horizontal_two_rows"
mutant "a Cloud group is renamed"       "sed -i 's/\"label\": \"Application\"/\"label\": \"Apps\"/' build.json"  "B1 the Cloud groups are not"
mutant "an API page is renamed"          "sed -i 's/\"label\": \"Real Estate\"/\"label\": \"Houses\"/' build.json"  "B1 the API pages are not"
mutant "shell loses the section layout" "sed -i 's/NavSectionLayout(/Foo(/' $KT/ui/KgShell.kt"                 "B2 KgShell does not call NavSectionLayout("
mutant "nav lib unlinked"               "sed -i \"s/project(':libs:theme-background')/project(':x')/\" $SCRIPT"  "B2 the app's build script does not link libs:theme-background"
mutant "drawer loses the sessions"      "sed -i 's/AiSessionsPanel(/Foo(/' $KT/ui/KgShell.kt"                  "B3 KgShell lacks AiSessionsPanel("
mutant "markdown off"                   "sed -i 's/markdown = true/markdown = false/' $KT/ui/KgShell.kt"        "B3 KgShell lacks markdown = true"
mutant "token read creeps in"           "echo 'val x = revealAiKey' >> $KT/KgAi.kt"                             "B4 KgAi.kt matches revealAiKey"
mutant "bearer written to prefs"        "echo 'val y = prefs.putString(\"bearer\", t)' >> $KT/KgServices.kt"    "B4 KgServices.kt matches"
mutant "gateway not baked"              "sed -i 's/AI_GATEWAY_URL/NOPE/g' $SCRIPT"                              "B4 the app's build script does not bake"
mutant "account not visible"            "sed -i 's/com.diegonmarcos.cloudaccount/x.y/' app/src/main/AndroidManifest.xml" "B5 the manifest cannot see Cloud Account"
mutant "installed apps not visible"     "sed -i 's/QUERY_ALL_PACKAGES/X/' app/src/main/AndroidManifest.xml"     "B5 the manifest lacks QUERY_ALL_PACKAGES"
mutant "a host is spelled in Kotlin"    "echo 'val h = \"http://x.example\"' >> $KT/KgFleet.kt"                 "B6 KgFleet.kt spells a URL"
mutant "the missing sentence dropped"   "python3 -c \"import json;p='kg.json';d=json.load(open(p));d['pages']['app_mcp']['missing']='';json.dump(d,open(p,'w'))\"" "B6 an MCP page with no declared service"
mutant "search-core copied"             "sed -i 's#../ac_cloud-search/core#core#' build.json"                  "B7 search-core is not linked by reference"
mutant "search declaration not baked"   "sed -i 's#ac_cloud-search/build.json#x/build.json#' $SCRIPT"          "B7 the build script does not bake Cloud Search"
mutant "own parser defined"             "echo 'object Parsers' >> $KT/KgFleet.kt"                              "B7 this app defines its own"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
