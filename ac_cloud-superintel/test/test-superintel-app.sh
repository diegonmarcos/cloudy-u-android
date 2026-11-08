#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-superintel-app — Cloud SuperIntel (SI), statically          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The chat itself (page, streaming, sessions, Markdown) is libs:ai's and is proven there (AiProtocolTest,
# AiMarkdownTest). This proves, from the sources alone, that this app only ASSEMBLES it, on the fleet's bars:
#   B1  every section and page id Decl.kt dispatches on is declared by build.json::ui, and every declared page is dispatched (both ways);
#       the bottom nav is Notebook, CGCs, SI, AIs, Cost (#1499; the AIs and Cost pages are test-superintel-ais.sh's); the SI page and the CGCs pages are exactly assets/si/personas.json (the router, and the experts with a group), the
#       CGCs section is horizontal_two_rows with exactly the owner's six groups, each group exactly its personas' `group` (both ways)
#   B2  the shell draws the shared bars: first-row scaffold (Profile -> Cloud Account launcher), second row, bottom nav host,
#       theme-background; the nav libs are in build.json modules AND in the app's build script
#   B3  the chat is libs:ai's: AiChatPage + the sessions drawer panel in the top bar's drawer + Markdown on; no chat of this app's own
#   B4  the backend is the fleet gateway: the gateway script applied, BuildConfig.AI_GATEWAY_URL baked and read, and no token handling (the AIs pages read the private store through PrivateSource only, never a stored or logged token)
#       (no revealAiKey, no EncryptedSharedPreferences, no key-shaped literal): decisions-use guard D5
#   B6  the sections are data and honest: the notebook refuses out of scope and cites, no answer is spelled in Kotlin
#   B7  the News tab is gone cleanly: no News code, data, address, strings or provider query is left behind
#   B9  chat layout: top bar (hamburger, session pill, Usage & Cost, Context, Files) then specialists then the sessions rail; (+) on the input row; toolbar rows 1 and 2; plugin and MCP buckets
#   B5  Profile opens Cloud Account, the manifest can see its package, the launcher shows the app and Share > SI is declared
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
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")
decl, shell, ai, main = (code(os.path.join(kt, f)) for f in ("Decl.kt", "ui/SiShell.kt", "SiAi.kt", "MainActivity.kt"))
script = code(os.path.join(app, "app/build." + "gradle"))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))

# B1
declared_sections = {s["id"] for s in bj["ui"]["sections"]}
DATA_SECTIONS = ("si", "cgcs", "ais", "cost")   # their pages are the ids of assets/si/*.json and assets/ai/pages.json (test-superintel-ais.sh), not Kotlin constants
declared_pages = {p["id"] for s in bj["ui"]["sections"] if s["id"] not in DATA_SECTIONS for p in s["pages"]}
named_sections = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl))
named_pages = set(re.findall(r'PAGE_\w+\s*=\s*"([^"]+)"', decl))
for x in sorted(named_sections - declared_sections): bad.append("B1 Decl.kt names section %r that build.json::ui does not declare" % x)
for x in sorted(named_pages - declared_pages): bad.append("B1 Decl.kt names page %r that build.json::ui does not declare" % x)
for x in sorted(declared_pages - named_pages): bad.append("B1 page %r is declared but Decl.kt has no id for it (the shell would draw nothing)" % x)
for x in sorted(set(bj["ui"]["bottom_nav"]) - declared_sections): bad.append("B1 bottom_nav id %r is no section" % x)
if bj["ui"]["bottom_nav"] != ["notebook", "cgcs", "si", "tools", "ais", "cost"]: bad.append("B1 the bottom nav must be Notebook, CGCs, SI, Tools, AIs, Cost, in that order")
def ids_of(section): return [p["id"] for s in bj["ui"]["sections"] if s["id"] == section for p in s["pages"]]
def data(name): return json.loads(read(os.path.join(app, "app/src/main/assets/si", name)))
personas = data("personas.json")["personas"]
if ids_of("si") != [p["id"] for p in personas if not p.get("group")]: bad.append("B1 the SI pages are not exactly the router persona of personas.json")
if ids_of("cgcs") != [p["id"] for p in personas if p.get("group")]: bad.append("B1 the CGCs pages are not exactly the grouped personas of personas.json")
cgcs = [s for s in bj["ui"]["sections"] if s["id"] == "cgcs"][0]
if cgcs.get("nav_mode") != "horizontal_two_rows": bad.append("B1 the CGCs section is not horizontal_two_rows")
if [g["label"] for g in cgcs.get("groups", [])] != ["Deep Science", "Engineers", "Production", "Medicine", "Business", "General"]: bad.append("B1 the CGCs groups are not the owner's six, in order")
for g in cgcs.get("groups", []):
    if g["pages"] != [p["id"] for p in personas if p.get("group") == g["id"]]: bad.append("B1 CGCs group %r is not exactly its personas' group" % g["id"])
for s in bj["ui"]["sections"]:
    for p in s["pages"]:
        if not p.get("icon"): bad.append("B1 page %r has no icon (the second row draws one per tab)" % p["id"])

# B2
for need in ("NavBarTopFirstRowScaffold(", "NavSectionLayout(", "NavBarBottomHost(", "themeBackground(", "rememberAccountLauncher()", "titleHierarchy("):
    if need not in shell: bad.append("B2 SiShell does not call %s" % need)
mods = bj["modules"]
for lib in ("libs:ui-nav-bars", "libs:ui-nav-bars", "libs:ui-nav-bars", "libs:theme-background", "libs:bottomnav", "libs:ai"):
    if lib not in mods: bad.append("B2 build.json modules lack %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("B2 the app's build script does not link %s" % lib)

# B3
for need in ("AiChatPage(", "AiSessionsPanel(", "TopBarDrawer", "markdown = true"):
    if need not in shell: bad.append("B3 SiShell lacks %s" % need)
if re.search(r"\b(LazyColumn|BasicTextField|OutlinedTextField)\b", shell): bad.append("B3 SiShell draws list or input widgets of its own: the chat is libs:ai's")

# B8 the AI tooling #1563 had dropped must stay (rule 16: never remove): the Tools section on the bottom nav, the sessions rail and the
# composer's tooling on the SI tab. A regression here is red, so the chat can never again replace the AI tools with a bare composer.
if "tools" not in bj["ui"]["bottom_nav"]: bad.append("B8 the AI Tools section left the bottom nav")
if [p["id"] for s in bj["ui"]["sections"] if s["id"] == "tools" for p in s["pages"]] != ["tools_models", "tools_pickers", "tools_tasks", "tools_mcp"]: bad.append("B8 Tools must have Models, Agent & Model, Tasks, MCP")
for need in ("AiModelCatalogue(", "AiTaskCatalogue(", "AiSettingsPickers(", "AiAttachHost(", "ForumSessions(", "ForumTools(", "sessions = ForumSessions", "tools = ForumTools"):
    if need not in shell: bad.append("B8 SiShell lacks %s (the SI tab lost its sessions rail or AI tooling)" % need)
comp = json.loads(read(os.path.join(app, "app/src/main/assets/ai/composer.json")))["controls"]
# Chat UI restructure: every prior control (plus, task, agent, model, effort, mode, mcp, more) keeps a home; mcp is now the four MCP buckets.
want = ["plus", "task", "specialist", "agent", "model", "effort", "mode", "repos", "skills", "mcp_kg", "mcp_infra", "mcp_user", "mcp_ext", "perf", "more"]
for need in want:
    if need not in comp: bad.append("B8 composer.json lost the %s control" % need)
if "cost" in comp or "usage" in comp: bad.append("B8 Usage & Cost belongs in the top bar, not the toolbar")
libai = os.path.join(app, "..", "ab_cloud-libs-shared", "libs", "ai")
dj = json.loads(read(os.path.join(libai, "src/main/assets/ai/ai-design.json")))
rows = {c["id"]: c.get("row", 1) for c in dj["composer"]["layout"]["controls"]}
if [c for c in want if rows.get(c) == 0] != ["plus"]: bad.append("B9 (+) must be the input row's control (row 0)")
if [c for c in want if rows.get(c) == 1] != ["task", "specialist", "agent", "model", "effort", "mode"]: bad.append("B9 toolbar row 1 must be Task, Specialist, Agent, Model, Effort, Mode")
if [c for c in want if rows.get(c) == 2] != ["repos", "skills", "mcp_kg", "mcp_infra", "mcp_user", "mcp_ext", "perf", "more"]: bad.append("B9 toolbar row 2 must be Repos, Skills, 4 MCP buckets, Performance, More")
items = {i["id"]: i for g in dj["more"]["groups"] for i in g["items"]}
if items.get("principles", {}).get("label") != "Cloudy Principals" or items["principles"].get("class") != "skill": bad.append("B9 Principles must be 'Cloudy Principals' under Plugins > Skills")
if items.get("headroom", {}).get("class") != "performance": bad.append("B9 Headroom must be a performance plugin")
forum = read(os.path.join(libai, "src/main/kotlin/com/diegonmarcos/superapp/ai/AiForumChat.kt"))
if not (0 <= forum.find("SessionTopBar(topic, sessions") < forum.find("SessionsRail(sessions, theme")): bad.append("B9 order must be top bar, then the sessions rail")
if "TopicStrip" in forum: bad.append("B9 the standalone specialists chip row is gone (specialists are the Specialist tool)")
bar = read(os.path.join(libai, "src/main/kotlin/com/diegonmarcos/superapp/ai/AiComposerBar.kt"))
if "AiTags.rowToggle(n)" not in bar or '".row$n"' not in bar: bad.append("B9 each toolbar row needs its own remembered collapse toggle")
if not any(c.get("divider_before") and c["id"] == "agent" for c in dj["composer"]["layout"]["controls"]): bad.append("B9 the divider must stand before Agent (after Specialist)")
if "groups = groups" not in shell or "AiSessionGroups" not in shell: bad.append("B9 SiShell does not give the all-chats drawer its session groups")

# B4
if "ai-gateway" not in script or "AI_GATEWAY_URL" not in script: bad.append("B4 the app's build script does not bake the gateway address")
if "BuildConfig.AI_GATEWAY_URL" not in ai: bad.append("B4 SiAi does not read BuildConfig.AI_GATEWAY_URL")
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith((".kt", ".java")):
            t = code(os.path.join(dp, f))
            for pat in (r"revealAiKey", r"EncryptedSharedPreferences", r"sk-or-[A-Za-z0-9]", r"android\.util\.Log", r"Authorization"):
                if re.search(pat, t): bad.append("B4 %s matches %s: this app holds and logs no token" % (f, pat))

# B5
if 'android:name="com.diegonmarcos.cloudaccount"' not in manifest: bad.append("B5 the manifest cannot see Cloud Account (queries)")
if "android.intent.category.LAUNCHER" not in manifest: bad.append("B5 no launcher activity")
if "android.intent.action.SEND" not in manifest or "ACTION_SEND" not in main: bad.append("B5 Share > SI is not declared and handled")
if bj["android"]["application_id"] != "com.diegonmarcos.cloudsuperintel": bad.append("B5 application_id drifted")

# B6
nb = data("notebook.json")
if not nb["refusal"].strip() or "{{refusal}}" not in nb["grounding"]["system"]: bad.append("B6 the notebook declares no refusal line in its grounding prompt")
notebook_kt = code(os.path.join(kt, "Notebook.kt"))
if "fun check(" not in notebook_kt or "cfg.refusal" not in notebook_kt: bad.append("B6 Notebook.kt does not hold answers to their citations")
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith(".kt"):
            t = code(os.path.join(dp, f))
            if re.search(r'Article\(\s*"https?://', t) or re.search(r'Story\(\s*"', t): bad.append("B6 %s spells a news article in Kotlin: no mock data" % f)

# B7
for gone in ("News.kt", "SiNews.kt", "FleetNews.kt", "ui/NewsScreens.kt"):
    if os.path.exists(os.path.join(kt, gone)): bad.append("B7 %s is still here: the News tab is removed" % gone)
if os.path.exists(os.path.join(app, "app/src/main/assets/si/news.json")): bad.append("B7 assets/si/news.json is still here")
if re.search(r"NEWS_(MESH|PUBLIC)_URL", script): bad.append("B7 the build script still bakes a news address")
if "com.diegonmarcos.superapp\"" in manifest: bad.append("B7 the manifest still queries the SuperApp package (only FleetNews read its bearer)")
if re.search(r'name="(news_|backend_news)', read(os.path.join(app, "app/src/main/res/values/strings.xml"))): bad.append("B7 news strings are left in strings.xml")
if "news" in [s["id"] for s in bj["ui"]["sections"]]: bad.append("B7 build.json::ui still declares a News section")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[superintel] assembly rules"
out="$(check "$APP")" && ok "B1-B5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
SCRIPT="app/build.gra""dle"
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/app" "$W/ab_cloud-libs-shared"; cp -r "$APP" "$W/app"
  mkdir -p "$W/ab_cloud-libs-shared/libs"; cp -r "$APP/../ab_cloud-libs-shared/libs/ai" "$W/ab_cloud-libs-shared/libs/ai"   # B9 reads the shared chat's sources next to the app
  ( cd "$W/app" && eval "$2" )
  if out="$(check "$W/app" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudsuperintel
mutant "page dropped from Decl"        "sed -i '/PAGE_MODELS/d' $KT/Decl.kt"                                  "B1 page 'models' is declared"
mutant "shell loses the section layout" "sed -i 's/NavSectionLayout(/Foo(/' $KT/ui/SiShell.kt"                 "B2 SiShell does not call NavSectionLayout("
mutant "drawer loses the sessions"     "sed -i 's/AiSessionsPanel(/Foo(/' $KT/ui/SiShell.kt"                  "B3 SiShell lacks AiSessionsPanel("
mutant "markdown off"                  "sed -i 's/markdown = true/markdown = false/' $KT/ui/SiShell.kt"        "B3 SiShell lacks markdown = true"
mutant "bottom nav loses CGCs"          "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav']=['notebook','si','cgcs'];json.dump(d,open(p,'w'))\"" "B1 the bottom nav must be"
mutant "CGCs loses its two rows"       "sed -i 's/horizontal_two_rows/horizontal_one_row/' build.json"        "B1 the CGCs section is not horizontal_two_rows"
mutant "a CGC group is renamed"        "sed -i 's/\"label\": \"Engineers\"/\"label\": \"Builders\"/' build.json"  "B1 the CGCs groups are not the owner's six"
mutant "a persona leaves its group"    "sed -i 's/\"group\": \"business\"/\"group\": \"general\"/' app/src/main/assets/si/personas.json"  "B1 CGCs group"
mutant "persona page not in data"      "sed -i 's/\"id\": \"cto\"/\"id\": \"cto2\"/' app/src/main/assets/si/personas.json"  "B1 the CGCs pages are not exactly"
mutant "news address creeps back"      "echo 'buildConfigField \"String\", \"NEWS_MESH_URL\", \"x\"' >> $SCRIPT"                    "B7 the build script still bakes a news address"
mutant "news code creeps back"         "echo 'object SiNews' > $KT/SiNews.kt"                                    "B7 SiNews.kt is still here"
mutant "news data creeps back"         "echo '{}' > app/src/main/assets/si/news.json"                            "B7 assets/si/news.json is still here"
mutant "news section creeps back"      "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['sections'].append({'id':'news','label':'News','icon':'rss_feed','pages':[]});json.dump(d,open(p,'w'))\"" "B7 build.json::ui still declares a News section"
mutant "refusal line dropped"          "sed -i 's/exactly: {{refusal}}/exactly: nothing/' app/src/main/assets/si/notebook.json"  "B6 the notebook declares no refusal"
mutant "article spelled in Kotlin"     "echo 'val x = Article(\"https://a\", \"t\", \"\", \"\", \"d\")' >> $KT/SiAi.kt"  "B6 SiAi.kt spells a news article"
mutant "token read creeps in"          "echo 'val x = revealAiKey' >> $KT/SiAi.kt"                             "B4 SiAi.kt matches revealAiKey"
mutant "gateway not baked"             "sed -i 's/AI_GATEWAY_URL/NOPE/g' $SCRIPT"                              "B4 the app's build script does not bake"
mutant "account not visible"           "sed -i 's/com.diegonmarcos.cloudaccount/x.y/' app/src/main/AndroidManifest.xml" "B5 the manifest cannot see Cloud Account"
mutant "Tools leaves the bottom nav"    "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav'].remove('tools');json.dump(d,open(p,'w'))\"" "B8 the AI Tools section left the bottom nav"
mutant "the sessions rail is dropped"  "sed -i 's/sessions = ForumSessions/sessions = null \/\/ ForumSessions/' $KT/ui/SiShell.kt" "B8 SiShell lacks sessions = ForumSessions"
mutant "the composer tooling is dropped" "sed -i 's/tools = ForumTools/tools = null \/\/ ForumTools/' $KT/ui/SiShell.kt" "B8 SiShell lacks tools = ForumTools"
mutant "the composer loses MCP"        "sed -i 's/\"mcp_kg\",//' app/src/main/assets/ai/composer.json" "B8 composer.json lost the mcp_kg control"
mutant "Principles is not renamed"     "sed -i 's/Cloudy Principals/Principles/' ../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai-design.json" "B9 Principles must be"
mutant "the rail climbs to the top"    "sed -i 's/SessionTopBar(topic, sessions/SessionTopBarX(topic, sessions/' ../ab_cloud-libs-shared/libs/ai/src/main/kotlin/com/diegonmarcos/superapp/ai/AiForumChat.kt" "B9 order must be top bar"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
