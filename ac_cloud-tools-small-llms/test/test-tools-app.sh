#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-tools-app — Cloud Tools Small LLMs, statically              ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The JVM suite (core/ ToolsCoreTest, run by `gradle test`) proves the logic against recorded
# OpenRouter answers. This proves what a JVM test cannot see, from the sources alone:
#   A1  every icon build.json names (ui.sections, tools.tasks) is a branch of ui/IconCatalog.kt
#   A2  the token is stored ONLY through EncryptedSharedPreferences under a Keystore AES-256-GCM
#       master key, in the store fleet-config.json declares as `encrypted` / `secret`
#   A3  the token is never logged: no android.util.Log, println or printStackTrace anywhere in the
#       app or core sources, and the vault's toString is its mask
#   A4  decisions-use guard compliance: nothing here calls the fleet Account's key reveal
#       (revealAiKey) or the engine's DecisionsLink; the Account is asked only aiRoutingSnapshot
#   A5  the JVM suite uses recorded fixtures, never the network: every fixture it names exists,
#       and no test source opens a URL of its own
#   A6  no key-shaped literal in the module (the leak scan's sk-or- shape)
#   A7  every section id a Kotlin source names is one build.json::ui.sections declares
#   A8  the model data (data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json): one file, both tiers plus retrieval, every field typed, macro in the
#       declared set, chat=false exactly for retrieval, the ~20% overhead stated, every role/macro/app icon in the shared
#       catalog, every app step names declared macros and models; Models declares Small, Medium and Retrieval pages
#       and locations (device, ollama) and the 4/8/16-bit tags are declared
#       and every app declares its own UI: a template of the declared set (all twelve used) plus params
#       and the chat's task->model mapping (every task names declared models, a gate and a judge, escalation score)
#   A9  the shell draws the shared bars (first row with Profile -> Cloud Account, second row, bottom nav, theme
#       background), the bottom nav is exactly Models (AI Models), MLs, Apps, Store, Sensors and there is no Costs
#       section, page or ledger, the AI chat is libs:ai's over the gateway with its picker fed ONLY by the chat rows of
#       the data file, the four views and the apps page exist, MLs has Lite MLs and Tiny MLs bound to the Cloud Store's
#       groups and derived from its fleet file (not hand-copied), and the Apps page has the All | Hybrids | Models Only |
#       ML Only filter with every app carrying exactly one class (Hybrids 12, Models Only 9, ML Only 4)
# then plants mutations in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$ROOT" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
def sources(*dirs):
    for d in dirs:
        for dp, dn, fn in os.walk(os.path.join(app, d)):
            dn[:] = [x for x in dn if x != "build"]
            for f in fn:
                if f.endswith((".kt", ".java")): yield os.path.join(dp, f)
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudtoolssmallllms")
main = list(sources("app/src/main", "core/src/main"))

# A1
cat = code(os.path.join(kt, "ui/IconCatalog.kt"))
branches = set(re.findall(r'"([a-z_]+)"\s*->', cat))
for i in sorted({t["icon"] for t in bj["tools"]["tasks"]} - branches): bad.append("A1 icon %r is not a branch of IconCatalog: it would draw the fallback" % i)
uiicons = read(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"))
def navicons(sections):
    for s in sections:
        yield s["icon"]
        for i in navicons(s["pages"]): yield i
for i in sorted(set(navicons(bj["ui"]["sections"]))):
    if '"%s"' % i not in uiicons: bad.append("A1 nav icon %r is not in libs:ui-icons" % i)

# A2
sp = code(os.path.join(kt, "data/SecretPrefs.kt"))
# #1106 SecretPrefs opens the store through libs:crash SafeEncryptedPrefs, which holds the encrypted-prefs schemes.
safe = code(os.path.join(root, "ab_cloud-libs-shared/libs/crash/src/main/java/com/diegonmarcos/superapp/crash/SafeEncryptedPrefs.kt"))
if "SafeEncryptedPrefs.prefs(" not in sp: bad.append("A2 SecretPrefs does not use SafeEncryptedPrefs.prefs(")
for need in ("EncryptedSharedPreferences.create(", "MasterKey.KeyScheme.AES256_GCM", "PrefValueEncryptionScheme.AES256_GCM"):
    if need not in safe: bad.append("A2 SafeEncryptedPrefs does not use %s" % need)
store = re.search(r'PREFS\s*=\s*"([^"]+)"', sp)
fc = json.loads(read(os.path.join(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")))
decl = fc["stores"].get(store.group(1)) if store else None
if not decl or decl.get("kind") != "encrypted" or decl.get("class") != "secret":
    bad.append("A2 the token store %r is not declared encrypted/secret in fleet-config.json" % (store.group(1) if store else None))
for f in main:
    c = code(f)
    if "getSharedPreferences(" in c and "openrouter_token" in c:
        bad.append("A2 %s writes the token to plain SharedPreferences" % os.path.relpath(f, app))
    if "SecretPrefs(" in c and not f.endswith(("SecretPrefs.kt", "ToolsModel.kt")):
        bad.append("A2 %s holds the secret store; only the model's vault may" % os.path.relpath(f, app))

# A3
for f in main:
    c = code(f)
    for w in ("android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "println(", "printStackTrace("):
        if w in c: bad.append("A3 %s calls %s: this app logs nothing" % (os.path.relpath(f, app), w))
vault = code(os.path.join(app, "core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt"))
if not re.search(r'override fun toString\(\): String = "TokenVault\(\$\{masked\(\)\}\)"', vault):
    bad.append("A3 TokenVault.toString is not its mask")

# A4
for f in main:
    c = code(f)
    if "revealAiKey(" in c: bad.append("A4 %s reads the fleet Account's key (decisions-use D5)" % os.path.relpath(f, app))
    if "DecisionsLink" in c: bad.append("A4 %s calls the decisions engine; this app pays with its own token" % os.path.relpath(f, app))
acc = code(os.path.join(kt, "data/AccountStatus.kt"))
if "aiRoutingSnapshot()" not in acc: bad.append("A4 AccountStatus no longer asks the key-free aiRoutingSnapshot")

# A5
tests = list(sources("core/src/test"))
fx = os.path.join(app, "core/src/test/resources/fixtures")
for f in tests:
    t = read(f)
    for name in re.findall(r'fixture\("([^"]+)"\)', t):
        if not os.path.isfile(os.path.join(fx, name)): bad.append("A5 fixture %s is missing" % name)
    if re.search(r"UrlHttp|openConnection|HttpURLConnection|java\.net\.URL\(", t):
        bad.append("A5 %s reaches the network" % os.path.relpath(f, app))
if not tests: bad.append("A5 no JVM test sources")

# A6
key = re.compile(r"sk-or-[A-Za-z0-9-]{8,}")
for dp, dn, fn in os.walk(app):
    dn[:] = [x for x in dn if x not in ("build", ".gradle")]
    for f in fn:
        p = os.path.join(dp, f)
        try: t = read(p)
        except (UnicodeDecodeError, OSError): continue
        if key.search(t): bad.append("A6 %s holds a key-shaped literal" % os.path.relpath(p, app))

# A7
sections = {s["id"] for s in bj["ui"]["sections"]}
for f in sources("app/src/main"):
    for lit in re.findall(r'section\s*=\s*"([a-z_-]+)"', code(f)) + re.findall(r'^\s*"([a-z_-]+)"\s*->\s*\w+Page\(', code(f), re.M):
        if lit not in sections: bad.append("A7 %s names section %r, which build.json does not declare" % (os.path.relpath(f, app), lit))

# A8
md = json.loads(read(os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json")))
macros = [m["id"] for m in md["macros"]]
if macros != ["retrieval", "gatekeeping", "routing", "understanding", "acting", "thinking", "producing", "seeing", "judging"]: bad.append("A8 macros are %r" % macros)
for m in md["macros"]:
    if '"%s"' % m["icon"] not in uiicons: bad.append("A8 macro %r icon %r is not in libs:ui-icons" % (m["id"], m["icon"]))
fields = {"role": str, "model": str, "params": str, "disk_gb_fp16": float, "ram_gb_fp16": float, "ram_gb_q8": float, "ram_gb_q4": float, "tier": str, "macro": str, "chat": bool}
for r in md["models"]:
    for k, t in fields.items():
        if k not in r or not isinstance(r[k], (t, int) if t is float else t): bad.append("A8 model %r lacks %s of type %s" % (r.get("model"), k, t.__name__))
    if r.get("tier") not in ("small", "medium"): bad.append("A8 model %r has tier %r" % (r.get("model"), r.get("tier")))
    if r.get("role") not in md["roles"]: bad.append("A8 role %r has no icon and purpose" % r.get("role"))
    if r.get("macro") not in macros: bad.append("A8 model %r has macro %r" % (r.get("model"), r.get("macro")))
    if r.get("chat") != (r.get("macro") != "retrieval"): bad.append("A8 model %r: chat must be false exactly for retrieval models" % r.get("model"))
    if r.get("macro") == "retrieval" and not r.get("task"): bad.append("A8 retrieval model %r has no task" % r.get("model"))
for t in ("small", "medium"):
    if not [r for r in md["models"] if r["tier"] == t and r["macro"] != "retrieval"]: bad.append("A8 tier %s has no models" % t)
    if not [r for r in md["models"] if r["tier"] == t and r["macro"] == "retrieval"]: bad.append("A8 tier %s has no retrieval models" % t)
    if t not in md["quant_guidance"]: bad.append("A8 no quantization guidance for %s" % t)
if len({r["model"] for r in md["models"]}) != len(md["models"]): bad.append("A8 duplicate model names")
if md.get("overhead_pct") != 20 or "20%" not in md.get("overhead_note", ""): bad.append("A8 the 20% overhead assumption is not stated in the data")
for n, ro in md["roles"].items():
    if '"%s"' % ro["icon"] not in uiicons: bad.append("A8 role %r icon %r is not in libs:ui-icons" % (n, ro["icon"]))
names = {r["model"] for r in md["models"]}
cats = [c["id"] for c in md["app_categories"]]
if cats != ["search", "documents", "communication", "agents", "reasoning", "safety", "personal"]: bad.append("A8 app categories are %r" % cats)
for c in md["app_categories"]:
    if '"%s"' % c["icon"] not in uiicons: bad.append("A8 category %r icon %r is not in libs:ui-icons" % (c["id"], c["icon"]))
for a in md["apps"]:
    if a.get("category") not in cats: bad.append("A8 app %r has category %r" % (a["id"], a.get("category")))
if [cats.index(a["category"]) for a in md["apps"] if a.get("category") in cats] != sorted(cats.index(a["category"]) for a in md["apps"] if a.get("category") in cats): bad.append("A8 apps are not ordered by category")
tpls = [t["id"] for t in md["app_ui_templates"]]
if sorted(tpls) != sorted(["rag_search", "doc_extract", "triage_inbox", "chat_guarded", "tool_runner", "tutor", "writer", "vision_qa", "compare_judge", "cluster_view", "summarizer", "quick_actions"]): bad.append("A8 UI templates are %r" % tpls)
for a in md["apps"]:
    if a.get("ui", {}).get("template") not in tpls: bad.append("A8 app %r has no valid ui.template" % a["id"])
    if not a.get("ui", {}).get("params", {}).get("input_hint"): bad.append("A8 app %r ui has no input_hint" % a["id"])
if {a["ui"]["template"] for a in md["apps"] if "ui" in a} != set(tpls): bad.append("A8 some UI template is used by no app")
names0 = {r["model"] for r in md["models"]}
for t in md["chat_tasks"]:
    for k in ("small", "medium"):
        if t[k] not in names0: bad.append("A8 chat task %r names unknown %s model %r" % (t["id"], k, t[k]))
    if t["macro"] not in macros: bad.append("A8 chat task %r has macro %r" % (t["id"], t["macro"]))
    if '"%s"' % t["icon"] not in uiicons: bad.append("A8 chat task %r icon %r is not in libs:ui-icons" % (t["id"], t["icon"]))
if md["chat_default_task"] not in [t["id"] for t in md["chat_tasks"]]: bad.append("A8 chat_default_task is not a declared task")
if md["chat_gate"]["model"] not in names0 or md["chat_escalation"]["small_judge"] not in names0: bad.append("A8 the chat gate or judge model is not declared")
if not 1 <= md["chat_escalation"]["min_score"] <= 10: bad.append("A8 chat escalation min_score out of range")
for m in macros:
    if not md["step_prompts"].get(m): bad.append("A8 no step prompt for macro %r" % m)
if len(md["apps"]) < 25: bad.append("A8 only %d apps declared (25 expected)" % len(md["apps"]))
for a in md["apps"]:
    if '"%s"' % a["icon"] not in uiicons: bad.append("A8 app %r icon %r is not in libs:ui-icons" % (a["id"], a["icon"]))
    if not a["pipeline"]: bad.append("A8 app %r has no pipeline" % a["id"])
    for st in a["pipeline"]:
        if st["macro"] not in macros: bad.append("A8 app %r step macro %r" % (a["id"], st["macro"]))
        if not st["models"] and not st.get("note"): bad.append("A8 app %r has a step with neither models nor a note" % a["id"])
        for m in st["models"]:
            if m not in names: bad.append("A8 app %r names unknown model %r" % (a["id"], m))
if sorted(l["kind"] for l in md["locations"]) != ["device", "ollama"]: bad.append("A8 locations are not one device and one ollama runtime")
if sorted(md["quant_tags"]) != ["16", "4", "8"]: bad.append("A8 quant_tags do not cover 4, 8 and 16 bit")
for a in md["apps"]:
    for st in a["pipeline"]:
        if st.get("backend", "local") not in ("local", "openrouter"): bad.append("A8 app %r step backend %r" % (a["id"], st.get("backend")))
secs = {s["id"]: s for s in bj["ui"]["sections"]}
if [p["id"] for p in secs["apps"]["pages"]] != ["apps_grid", "apps_list"]: bad.append("A8 Apps pages are not grid, list")
if [p["id"] for p in secs["store"]["pages"]] != ["installed", "loaded", "all"]: bad.append("A8 Store pages are not installed, loaded, all")
if [p["id"] for p in secs["models"]["pages"]] != ["small", "medium", "retrieval"]: bad.append("A8 Models pages are not small, medium, retrieval")
decl = code(os.path.join(kt, "Decl.kt"))
named = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl)); namedp = set(re.findall(r'PAGE_\w+\s*=\s*"([^"]+)"', decl))
declp = {p["id"] for s in bj["ui"]["sections"] for p in s["pages"]}
for x in sorted(named ^ set(secs)): bad.append("A8 Decl.kt and build.json::ui disagree on section %r" % x)
for x in sorted(namedp ^ declp): bad.append("A8 Decl.kt and build.json::ui disagree on page %r" % x)

# A9
rt = code(os.path.join(kt, "data/ModelRuntime.kt"))
iface = re.search(r"interface ModelRuntime \{.*?\n\}", rt, re.S)
for need in ("fun state(): RuntimeState", "fun install(", "fun uninstall(", "fun load(", "fun unload("):
    if not iface or need not in iface.group(0): bad.append("A9 the engine contract (interface ModelRuntime) lacks %s" % need)
for need in ("class OllamaRuntime(", "class PhoneRuntime("):
    if need not in rt: bad.append("A9 ModelRuntime.kt lacks %s" % need)
store = code(os.path.join(kt, "ui/StorePage.kt"))
for need in ("it.install(", "it.uninstall(", "it.load(", "it.unload(", "store_fits", "store_not_installed", "store_loaded", "store_installed", "listOf(4, 8, 16)"):
    if need not in store: bad.append("A9 StorePage lacks %s" % need)
if re.search(r"getSharedPreferences|mutableStateOf\(\s*listOf", store): bad.append("A9 StorePage keeps model state of its own: it must come from the runtimes")
if bj["ui"]["bottom_nav"] != ["models", "mls", "apps", "store", "sensors"]: bad.append("A9 bottom_nav is %r" % bj["ui"]["bottom_nav"])
if "costs" in {s["id"] for s in bj["ui"]["sections"]}: bad.append("A9 a Costs section is still declared")
for f in sources("app/src/main", "core/src/main"):
    if re.search(r"CostsPage|SECTION_COSTS|Ledger\b|saveLedger", code(f)): bad.append("A9 %s still names the Costs page or its ledger" % os.path.relpath(f, app))
if os.path.exists(os.path.join(kt, "ui/CostsPage.kt")): bad.append("A9 ui/CostsPage.kt still exists")
if "libs:ui-kit" not in bj["modules"] or "project(':libs:ui-kit')" not in code(os.path.join(app, "app/build." + "gradle")): bad.append("A9 libs:ui-kit (KitStoreRow) is not linked")
shell = code(os.path.join(kt, "ui/ToolsShell.kt"))
for need in ("NavBarTopFirstRowScaffold(", "NavSectionLayout(", "NavBarBottomHost(", "themeBackground(", "rememberAccountLauncher()", "titleHierarchy(", "AiChatPage(", "AppsPage(", "ModelsPage(", "MlsPage(", "SensorsPage("):
    if need not in shell: bad.append("A9 ToolsShell does not call %s" % need)
apg = code(os.path.join(kt, "ui/AppsPage.kt"))
for need in ("appRamQ4(", "appDiskFp16(", "appModels(", "phoneCapable(", "AppsList(", "AppsGrid(", "app_totals", "app_phone_icon", "appCategories", "s.escalation", "s.alternatives"):
    if need not in apg: bad.append("A9 AppsPage lacks %s (list view, computed totals, badges)" % need)
for need in ("OutlinedTextField(", "model.runApp(", "stepStatus(", "app_info_show", "APP_RESULT"):
    if need not in apg: bad.append("A9 the app page lacks %s (own UI: input, run, per-step status, result, collapsible header)" % need)
if len(re.findall(r"app_totals, gb\(data\.appRamQ4\(a\)\), gb\(data\.appDiskFp16\(a\)\)", apg)) < 1 or len(re.findall(r"totalsCompact\(data, a\)", apg)) < 2: bad.append("A9 the grid, the list and the detail do not all show the computed totals")
pages = code(os.path.join(kt, "ui/ModelsPages.kt"))
for v in ("TABLE", "GRID", "DETAIL", "MACRO"):
    if not re.search(r"ModelsView\.%s\s*->\s*Models%s\(" % (v, v.title()), pages): bad.append("A9 the %s view is not drawn" % v)
if "AiChatPage(" not in shell or re.search(r"\b(BasicTextField|OutlinedTextField)\b", shell): bad.append("A9 the AI page is not libs:ai's alone")
script = code(os.path.join(app, "app/build." + "gradle"))
for lib in ("libs:ui-nav-bars", "libs:ui-nav-bars", "libs:ui-nav-bars", "libs:theme-background", "libs:ui-icons", "libs:ai", "libs:ui-kit"):
    if lib not in bj["modules"]: bad.append("A9 build.json modules lack %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("A9 the app's build script does not link %s" % lib)
if "AI_GATEWAY_URL" not in script or "ai-gateway.gradle" not in script: bad.append("A9 the gateway address is not baked")
tm = code(os.path.join(kt, "ui/ToolsModel.kt"))
if "AiGatewayBackend(" not in tm or "BuildConfig.AI_GATEWAY_URL" not in tm: bad.append("A9 the chat is not routed through the fleet gateway")
if "modelSource = { emptyList() }" not in tm or "fun answerTask(" not in tm: bad.append("A9 the chat has a model picker: the user picks a task and answerTask picks the model")
for need in ("task.small", "task.medium", "models.judgeMinScore", "flagged(question, \"gatekeeping\")", "flagged(text, \"gatekeeping\")", 'text + "\\n\\n— " + label', "private fun placeFor(", "helper(models.judgeModel", "label"):
    if need not in tm: bad.append("A9 ToolsModel lacks %s (task routing, guard in and out, escalation, model label)" % need)
if "STRONGER" not in shell or "showModel" in shell: bad.append("A9 the AI page does not keep Stronger, or still toggles a model picker of its own")
if "chat.tasks = models.chatTasks" not in tm or "chat.onTask" not in tm: bad.append("A9 the composer's Task control is not fed by chat_tasks (the task, not the user, picks the model)")
cd = json.loads(read(os.path.join(app, "app/src/main/assets/ai/composer.json")))
if not cd.get("task_required") or not {"task", "agent", "model"} <= set(cd["controls"]) or {"mode", "mcp", "effort"} & set(cd["controls"]): bad.append("A9 composer.json must enable Task (required), Agent and Model, and not Mode, MCP or Effort (the router calls the model)")
shared = os.path.join(app, "../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai-tasks.json")
if os.path.exists(shared):
    st = {t["id"]: t["model"] for t in json.loads(read(shared))["tasks"]}
    bt = {t["id"]: t["worker"] for t in bj["tools"]["tasks"]}
    if st != bt: bad.append("A9 libs:ai's ai-tasks.json and build.json::tools.tasks differ (ids or workers): %s" % sorted(set(st.items()) ^ set(bt.items()))[:4])
shared_cat = os.path.join(app, "../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai-catalogue.json")
if os.path.exists(shared_cat):
    sc = {(m["id"], m["tier"], m["params"], m.get("openrouter_id", "")) for m in json.loads(read(shared_cat))["tools_models"]["models"]}
    tm_rows = {(m["model"], m["tier"], m["params"], m.get("openrouter_id", "")) for m in json.loads(read(os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json")))["models"] if m.get("chat")}
    if sc != tm_rows: bad.append("A9 libs:ai's ai-catalogue.json tools_models and tools-models.json chat rows differ: %s" % sorted(sc ^ tm_rows)[:4])
if "Tags.pin(" not in code(os.path.join(kt, "ui/SettingsPage.kt")): bad.append("A9 Settings has no advanced pin-model option")
if "row?.wireId ?: req.model" not in tm: bad.append("A9 the gateway is not sent the data's openrouter_id")
for need in ("fun runApp(", "private fun place(", "status = \"running\"", "call(placed.second", "models.stepPrompt("):
    if need not in tm: bad.append("A9 ToolsModel lacks %s (the app runner)" % need)
for need in ("fun chooseBackend(", "OPENROUTER -> if (cloud) OPENROUTER else null", "if (loaded) LOCAL", "LocalChatBackend("):
    if need not in tm: bad.append("A9 ToolsModel lacks %s" % need)
if "BACKEND_LOCAL" not in shell or "BACKEND_OPENROUTER" not in shell or "header = {" not in shell: bad.append("A9 the AI page has no OpenRouter | Local toggle in its header")
mls = code(os.path.join(kt, "ui/MlsPage.kt"))
for need in ("KitStoreRowLine", "KitStoreName(", "KitStoreCaption(", "KitStoreDot(", "group.libs", "group.blurb"):
    if need not in mls: bad.append("A9 MlsPage lacks %s (the Cloud Store's dense row over the derived groups)" % need)
if re.search(r"\d\s*\.\s*sp\b", mls + apg): bad.append("A9 MlsPage / AppsPage hold an sp literal: the type ramp is KitStoreRow's")
if "ml_pages" not in script or "ml-fleet" in script or "constellation-fleet.json" in script or "copy {" in script: bad.append("A9 the build bakes or copies MLs data into the APK: it is the runtime dataset constellation-fleet (#1547)")
if "FleetData.observe" not in code(os.path.join(kt, "data/ToolsData.kt")) or "assets.open" in tm: bad.append("A9 the model does not read its data at runtime through libs:fleet-data-client")
if "MlCatalogue.parse(" not in tm: bad.append("A9 the model does not read the MLs groups through MlCatalogue")
mlp = bj["ui"].get("ml_pages", {})
fleet = json.loads(read(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))
fg = {g["id"]: g for g in fleet["groups"]}
if [p["id"] for p in secs["mls"]["pages"]] != ["ml_lite", "ml_tiny"] or [p["label"] for p in secs["mls"]["pages"]] != ["Lite MLs", "Tiny MLs"]: bad.append("A9 MLs pages are not Lite MLs, Tiny MLs")
if mlp.get("ml_lite") != "libs-l-ml" or mlp.get("ml_tiny") != "libs-t-ml" or any(g not in fg for g in (mlp.get("ml_lite"), mlp.get("ml_tiny"))): bad.append("A9 ml_pages do not name the Cloud Store's Lite-ML and Tiny-ML groups")
fl = [f["id"] for f in md["app_filters"]]
if fl != ["all", "hybrid", "models_only", "ml_only"] or [f["label"] for f in md["app_filters"]] != ["All", "Hybrids", "Models Only", "ML Only"]: bad.append("A9 the Apps filter is %r" % fl)
for a in md["apps"]:
    if a.get("class") not in fl[1:]: bad.append("A9 app %r is not classified (class %r)" % (a["id"], a.get("class")))
if "AppFilters(model, data)" not in apg or "appsIn(" not in apg: bad.append("A9 the Apps page has no top filter")

# A10 (#1538) the Sensors section: Inbuilt | Connected, every phone sensor listed and read live only while visible, the other hardware,
# and the 20 declared external sensors scanned on tap; names are data (data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors.json), rows are KitStoreRow, grants are Perms rows
sd = json.loads(read(os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors.json")))
if [p["id"] for p in secs["sensors"]["pages"]] != ["sensors_inbuilt", "sensors_connected"] or [p["label"] for p in secs["sensors"]["pages"]] != ["Inbuilt", "Connected"]: bad.append("A10 Sensors pages are not Inbuilt, Connected")
if len(sd["connected"]["profiles"]) < 40: bad.append("A10 %d connected sensors declared (the 20 originals plus 20 more at least)" % len(sd["connected"]["profiles"]))
cats = {c["id"] for c in sd["connected"]["categories"]}
for pr in sd["connected"]["profiles"]:
    if not any(k in pr for k in ("uuid", "usb_class", "feature", "package", "input")): bad.append("A10 connected sensor %r declares no GATT UUID, USB class, platform feature, package or input device" % pr.get("id"))
    if pr.get("category") not in cats or not pr.get("transport"): bad.append("A10 connected sensor %r has no declared category or no transport" % pr.get("id"))
cg = sd.get("catalogue", {}).get("categories", [])
if [c["id"] for c in cg] != ["motion", "position", "environment", "location", "imaging", "connectivity", "audio", "biometric", "hardware"] or sum(len(c["entries"]) for c in cg) < 99:
    bad.append("A10 the Inbuilt sensor catalogue is not the nine declared categories with every member")
sp = code(os.path.join(kt, "ui/SensorsPage.kt")); hwk = code(os.path.join(kt, "data/SensorHw.kt")); cio = code(os.path.join(kt, "data/ConnectedIo.kt"))
for need in ("KitStoreRowLine", "KitStoreName(", "KitStoreCaption(", "KitStoreQuick(", "WhileStarted(", "LiveReading(", "BleScan(", "UsbList.read(", "GattSession(", "AndroidSensorSource(", "HardwareProbe("):
    if need not in sp: bad.append("A10 SensorsPage lacks %s" % need)
if re.search(r"\d\s*\.\s*sp\b", sp + hwk + cio): bad.append("A10 the Sensors sources hold an sp literal: the type ramp is KitStoreRow's")
if "SENSOR_DELAY_UI" not in hwk or re.search(r"SENSOR_DELAY_(FASTEST|GAME|NORMAL)", hwk): bad.append("A10 the live listener is not SENSOR_DELAY_UI")
if "Sensor.TYPE_ALL" not in hwk or "getDynamicSensorList" not in hwk: bad.append("A10 the list is not getSensorList(TYPE_ALL) plus the dynamic sensors")
if "setServiceUuid(" not in cio or "postDelayed(stopper" not in cio or "deviceList" not in cio: bad.append("A10 the scan is not a service-UUID ScanFilter with a timeout plus the UsbManager list")
if re.search(r"requestPermissions|RequestPermission", sp + hwk + cio): bad.append("A10 the Sensors sources ask for a permission: grants are Configs > Perms rows only")
for need in ("body_sensors", "bt_scan", "bt_connect", "usb_host", "fine_location", "activity_recognition"):
    if need not in bj["perms"]["needs"]: bad.append("A10 perms.needs lacks %s" % need)
if "android.permission.BODY_SENSORS" not in read(os.path.join(app, "app/src/main/AndroidManifest.xml")): bad.append("A10 the manifest lacks BODY_SENSORS")
allkt = "\n".join(code(f) for f in main)
for t in sd["types"]:
    if '"%s"' % t["const"] in allkt: bad.append("A10 a sensor type name (%s) is spelled in Kotlin: it is data" % t["const"])
for h in sd["hardware"]:
    if '"%s"' % h["label"] in allkt: bad.append("A10 a hardware name (%s) is spelled in Kotlin" % h["label"])
for pr in sd["connected"]["profiles"]:
    if '"%s"' % pr["label"] in allkt: bad.append("A10 a connected sensor name (%s) is spelled in Kotlin" % pr["label"])

for b in bad: print("  FAIL: " + b)
sys.exit(1 if bad else 0)
PY
}

PASS=0; FAILS=0
echo "[ac_cloud-tools-small-llms] static checks"
if check "$APP"; then PASS=$((PASS + 1)); echo "  PASS: A1-A7 hold on the tree"; else FAILS=$((FAILS + 1)); fi

# Mutations: each must turn the check RED, or the check cannot fail.
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mutate() {   # mutate <name> <python statement on M (scratch module dir)>
  rm -rf "$S/m"; mkdir -p "$S/m"
  (cd "$APP" && tar --exclude=build --exclude=.gradle -cf - build.json app core) | (cd "$S/m" && tar -xf -)
  rm -rf "$S/data"; mkdir -p "$S/data/fleet"; cp -r "$APP/../data/fleet/app" "$S/data/fleet/"
  python3 - "$S/m" <<PY
import re, sys
M = sys.argv[1]
K = M + "/app/src/main/java/com/diegonmarcos/cloudtoolssmallllms"
def sub(p, a, b):
    s = open(p).read(); assert a in s, (p, a); open(p, "w").write(s.replace(a, b, 1))
$2
PY
  if check "$S/m" >/dev/null 2>&1; then FAILS=$((FAILS + 1)); echo "  FAIL: mutation '$1' stayed green"; else PASS=$((PASS + 1)); echo "  PASS: mutation '$1' is caught"; fi
}
mutate "unknown task icon" 'sub(M + "/build.json", "\"icon\": \"translate\"", "\"icon\": \"tranzlate\"")'
mutate "plain prefs token" 'sub(K + "/data/SecretPrefs.kt", "SafeEncryptedPrefs.prefs(", "PlainPrefs.create(")'
mutate "a log line" 'sub(K + "/data/Catalogue.kt", "    companion object {", "    fun dbg(t: String) = android.util.Log.d(\"tools\", t)\n\n    companion object {")'
mutate "Account key read" 'sub(K + "/data/AccountStatus.kt", "client.aiRoutingSnapshot() }", "client.revealAiKey(PROVIDER).text }")'
mutate "vault prints the token" 'D = chr(36); sub(M + "/core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt", "TokenVault(" + D + "{masked()})", "TokenVault(" + D + "{token()})")'
mutate "test goes online" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "import java.io.IOException", "import java.io.IOException\nimport com.diegonmarcos.superapp.decisions.UrlHttp")'
mutate "missing fixture" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "fixture(\"jev-route.json\")", "fixture(\"jev-gone.json\")")'
mutate "undeclared section" 'sub(K + "/ui/ToolsShell.kt", "Decl.SECTION_STORE -> StorePage(model, pageId ?: Decl.PAGE_INSTALLED)", "\"storez\" -> StorePage(model, \"\")")'
mutate "section dropped from Decl" 'sub(K + "/Decl.kt", "    const val SECTION_STORE = \"store\"\n", "")'
mutate "model data loses its guidance" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"medium\": \"Q4 is", "\"mediumz\": \"Q4 is")'
mutate "overhead note dropped" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"overhead_pct\": 20", "\"overhead_pct\": 0")'
mutate "retrieval model becomes a chat model" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"chat\": false", "\"chat\": true")'
mutate "app names an unknown model" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"bge-m3\"\n", "\"bge-m4\"\n")'
mutate "shell loses the second row" 'sub(K + "/ui/ToolsShell.kt", "NavSectionLayout(", "Foo(")'
mutate "apps lose the computed totals" 'sub(K + "/ui/AppsPage.kt", "app_totals, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a))", "app_totals, \"0\", \"0\"")'
mutate "app has no ui template" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"template\": \"rag_search\"", "\"template\": \"nope\"")'
mutate "runner does not call the model" 'sub(K + "/ui/ToolsModel.kt", "call(placed.second", "callz(placed.second")'
mutate "runner loses per-step status" 'sub(K + "/ui/ToolsModel.kt", "status = \"running\"", "status = \"busy\"")'
mutate "app page loses its input" 'sub(K + "/ui/AppsPage.kt", "OutlinedTextField(", "Foo(")'
mutate "app loses its category" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"category\": \"search\"", "\"category\": \"nowhere\"")'
mutate "apps list page dropped" 'sub(M + "/build.json", "\"id\": \"apps_list\"", "\"id\": \"apps_listz\"")'
mutate "shell loses the apps page" 'sub(K + "/ui/ToolsShell.kt", "AppsPage(model, pageId ?: Decl.PAGE_APPS_GRID)", "Unit")'
mutate "macro view undrawn" 'sub(K + "/ui/ModelsPages.kt", "ModelsView.MACRO -> ModelsMacro", "ModelsView.MACRO -> ModelsGrid")'
mutate "openrouter id not sent" 'sub(K + "/ui/ToolsModel.kt", "row?.wireId ?: req.model", "req.model")'
mutate "engine loses unload" 'sub(K + "/data/ModelRuntime.kt", "    fun unload(loaded: LoadedModel): String?\n", "")'
mutate "store keeps its own state" 'sub(K + "/ui/StorePage.kt", "val data = model.models", "val data = model.models; val own = context.getSharedPreferences(\"x\", 0)")'
mutate "store pages reordered" 'sub(M + "/build.json", "\"id\": \"installed\"", "\"id\": \"installedz\"")'
mutate "chat gets a picker back" 'sub(K + "/ui/ToolsModel.kt", "modelSource = { emptyList() }", "modelSource = { models.rows.map { AiModel(it.model, it.model) } }")'
mutate "guard on the answer dropped" 'sub(K + "/ui/ToolsModel.kt", "flagged(text, \"gatekeeping\")", "null")'
mutate "escalation ignores the score" 'sub(K + "/ui/ToolsModel.kt", "models.judgeMinScore", "0")'
mutate "reply loses its model label" 'sub(K + "/ui/ToolsModel.kt", "text + \"\\n\\n— \" + label", "text")'
mutate "task row dropped" 'sub(K + "/ui/ToolsModel.kt", "chat.tasks = models.chatTasks", "val unused = models.chatTasks")'
mutate "chat task names an unknown model" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"small\": \"ministral-3b-2512\"", "\"small\": \"ministral-9z\"")'
mutate "bottom nav reordered" 'sub(M + "/build.json", "\"models\",\n      \"mls\"", "\"mls\",\n      \"models\"")'
mutate "Costs back on the nav" 'sub(M + "/build.json", "\"store\",\n      \"sensors\"\n", "\"store\",\n      \"sensors\",\n      \"costs\"\n")'
mutate "Costs ledger back" 'sub(K + "/Decl.kt", "SECTION_MLS", "SECTION_COSTS")'
mutate "MLs page dropped from the shell" 'sub(K + "/ui/ToolsShell.kt", "MlsPage(model.mls, pageId ?: Decl.PAGE_ML_LITE, model.mlsNote)", "Unit")'
mutate "MLs page renamed" 'sub(M + "/build.json", "\"Tiny MLs\"", "\"Tiny\"")'
mutate "MLs group not the Store's" 'sub(M + "/build.json", "\"libs-t-ml\"", "\"libs-x-ml\"")'
mutate "MLs data baked again" 'sub(M + "/app/build.gradle", "def uiMlPagesB64", "copy { from(\"x\") }\ndef uiMlPagesB64")'
mutate "filter loses a class" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"label\": \"ML Only\"", "\"label\": \"ML\"")'
mutate "an app is unclassified" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools-models.json", "\"class\": \"hybrid\",\n      \"ui\"", "\"class\": \"none\",\n      \"ui\"")'
mutate "apps page loses the filter" 'sub(K + "/ui/AppsPage.kt", "AppFilters(model, data)\n", "")'

mutate "Sensors page dropped from the shell" 'sub(K + "/ui/ToolsShell.kt", "SensorsPage(pageId ?: Decl.PAGE_SENSORS_INBUILT, model.sensorsJson)", "Unit")'
mutate "live listener at the fast rate" 'sub(K + "/data/SensorHw.kt", "SensorManager.SENSOR_DELAY_UI", "SensorManager.SENSOR_DELAY_FASTEST")'
mutate "sensor list without the dynamic ones" 'sub(K + "/data/SensorHw.kt", "getDynamicSensorList", "getSensorList")'
mutate "scan without a UUID filter" 'sub(K + "/data/ConnectedIo.kt", "setServiceUuid(", "setDeviceName(")'
mutate "scan never times out" 'sub(K + "/data/ConnectedIo.kt", "postDelayed(stopper", "post(stopper")'
mutate "a sensor name in Kotlin" 'sub(K + "/data/SensorHw.kt", "class AndroidRegistrar", "val x = \"TYPE_ACCELEROMETER\"\nclass AndroidRegistrar")'
mutate "an sp literal on the Sensors page" 'sub(K + "/ui/SensorsPage.kt", "fontSize = KitStoreRow.meta", "fontSize = 11.sp")'
mutate "the page asks for a permission" 'sub(K + "/ui/SensorsPage.kt", "/** Runs [start] while", "val ask = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()\n/** Runs [start] while")'
mutate "body sensors dropped from the perms" 'sub(M + "/build.json", "\"body_sensors\"\n", "\"body_sensorz\"\n")'
mutate "a connected sensor loses its UUID and class" 'sub(M + "/../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors.json", "\"uuid\": \"FEED\"", "\"note\": \"FEED\"")'
mutate "Sensors page renamed" 'sub(M + "/build.json", "\"Connected\"", "\"Paired\"")'
echo "-- test-tools-app: $PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
