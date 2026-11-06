#!/usr/bin/env bash
# Cloud Tasks: the DECLARED navigation and the chrome around it, held against the Kotlin that draws them.
#
#   build.json::ui is the one declaration (libs:bottomnav's NavDecl reads it). This tester diffs it against the ids Kotlin
#   dispatches on (ui/Nav.kt::Ids) in both directions, and checks the shell is built from the fleet's pieces: the island
#   (BottomNavHost + islandEntries), the page tabs (PageTabs), the page-managed drawer, the top row's three controls, the
#   Profile menu's two entries, CloudKitTheme, libs:ai with persistent sessions, and no second nav widget, no View layout.
#   It reads this application's own files and build.json / the two ratchet data files only.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="$here/.."
repo="$app/.."
export APP="$app" REPO="$repo"
exec python3 - <<'PY'
import json, os, re, sys

APP, REPO = os.environ["APP"], os.environ["REPO"]
SRC = os.path.join(APP, "app/src/main/java/com/diegonmarcos/cloudagenda")
fails = 0

def ok(msg): print("ok   " + msg)
def bad(msg):
    global fails
    fails += 1
    print("FAIL " + msg)
def check(cond, msg): (ok if cond else bad)(msg)

def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as h:
        return h.read()

def strip(t):
    return re.sub(r"//[^\n]*|/\*.*?\*/", "", t, flags=re.S)

build = json.load(open(os.path.join(APP, "build.json")))
ui = build.get("ui", {})
sections = {s["id"]: s for s in ui.get("sections", [])}
bar = ui.get("bottom_nav", [])

# ── the declaration itself ────────────────────────────────────────────────────────────────────────────────
check(2 <= len(bar) <= 8, "ui.bottom_nav holds 2..8 ids (%d)" % len(bar))
check(all(b in sections for b in bar), "every bottom_nav id is a ui.sections id")
check(ui.get("default_section") in bar, "ui.default_section is one of bottom_nav (%s)" % ui.get("default_section"))
check(ui.get("default_section") == "tasks", "the app opens on Tasks")
check("settings" in sections and "settings" not in bar, "Settings is a declared section that is not on the island")
check(all(s.get("background") == "theme" for s in sections.values()), "every section's tab strip follows the app's light/dark mode (background: theme)")

# ── Kotlin ids == declared ids, both directions ───────────────────────────────────────────────────────────
nav_kt = strip(read(SRC, "ui/Nav.kt"))
ids = dict(re.findall(r"const val (\w+) = \"([\w-]+)\"", nav_kt))
sec_ids = {v for k, v in ids.items() if not k.startswith("PAGE_")}
page_ids = {v for k, v in ids.items() if k.startswith("PAGE_")}
declared_pages = {p["id"] for s in sections.values() for p in s.get("pages", [])}
check(sec_ids == set(sections), "Ids sections == build.json sections (%s)" % sorted(sec_ids))
check(page_ids == declared_pages, "Ids pages == build.json pages (%s)" % sorted(page_ids))
check(all(len(s.get("pages", [])) >= 1 for s in sections.values()), "every section declares at least one page")
check(len(sections["tasks"]["pages"]) >= 2 and len(sections["settings"]["pages"]) >= 2, "multi-page sections get a PageTabs strip")
check([p["id"] for p in sections["inboxes"]["pages"]] == ["notes", "synced"], "Inboxes has the sub-pages Notes | Synced, in that order")
check(bar == ["inboxes", "backlogs", "tasks", "library", "projects"], "#1456 the island is Inboxes | Backlogs | Tasks | Library | Projects")
check([p["id"] for p in sections["tasks"]["pages"]] == ["calendar", "list", "gantt", "kanban", "mindmap"], "#1456 Tasks has the sub-pages Calendar, List, Gantt, Kanban, Mindmap")
check([p["id"] for p in sections["settings"]["pages"]][:3] == ["general", "perms", "about"], "Configs starts General | Perms | About (the fleet pattern)")
check("FleetConfigsPageBody(" in strip(read(SRC, "ui/TasksApp.kt")), "General | Perms | About are drawn by libs:ui-nav-bars's FleetConfigsPageBody")
mock = json.load(open(os.path.join(APP, "app/src/main/assets/mock-tasks.json")))
check(mock.get("_fake", "").startswith("MOCK DATA - FAKE") and {i["kind"] for i in mock["items"]} == set(bar), "the mock fixture is declarative JSON, marked fake, with rows for every island tab")

# ── the shell dispatches every declared id ────────────────────────────────────────────────────────────────
app_kt = strip(read(SRC, "ui/TasksApp.kt"))
for k, v in sorted(ids.items()):
    if k.startswith("PAGE_"):
        check(("Ids." + k) in app_kt, "the shell dispatches page %s (Ids.%s)" % (v, k))
    else:
        check(re.search(r"Ids\.%s\s*->" % k, app_kt) is not None, "the shell has a branch for section %s" % v)

# ── built from the fleet's pieces ─────────────────────────────────────────────────────────────────────────
check("NavBarBottomHost(" in app_kt and "islandEntries" in app_kt, "the island is libs:ui-nav-bars's NavBarBottomHost over the declared entries")
check("NavBarTopSecondRow(" in app_kt and "stripSurface(" in app_kt, "tab strips are libs:ui-nav-bars's NavBarTopSecondRow, their surface from the declaration")
check("NavBarTopFirstRowScaffold(" in app_kt and "rememberAccountLauncher(" in app_kt and "onConfigs =" in app_kt, "the first row is libs:ui-nav-bars's, Profile via the fleet launcher, Configs opening Settings")
check("UI_TOP_BAR_B64" in read(APP, "app/build.gradle") and set(ui.get("top_bar", {}).get("settings", [])) == {"account", "configs"} and ui["top_bar"].get("drawer") and ui["top_bar"].get("ai"), "build.json::ui.top_bar declares drawer, ai and the Account / Configs settings, baked into BuildConfig")
check("TasksTheme(" in app_kt, "the app is wrapped in the theme")
theme = strip(read(SRC, "ui/Theme.kt"))
check("CloudKitTheme(" in theme and "ThemeMode" in theme and "isDark(" in theme, "TasksTheme applies light / dark / system through CloudKitTheme")
main = strip(read(SRC, "MainActivity.kt"))
check("FleetChrome.apply(" in main and "ComponentActivity" in main and "setContent" in main, "MainActivity is a Compose activity wearing the fleet window")
check("AppCompatActivity" not in main and "setContentView" not in main, "no View content in MainActivity")

allsrc = ""
for d, _, fs in os.walk(SRC):
    for f in fs:
        if f.endswith(".kt"):
            allsrc += strip(read(d, f)) + "\n"
check(not re.search(r"\b(BottomNavigationView|NavigationBar\s*[({]|NavigationRail|BottomAppBar|TabLayout|TabRow\s*\(|ScrollableTabRow)", allsrc), "no hand-rolled second nav widget")
check(not os.path.isdir(os.path.join(APP, "app/src/main/res/layout")), "no View layouts (res/layout is gone)")

# ── the top row ───────────────────────────────────────────────────────────────────────────────────────────
check(not os.path.exists(os.path.join(SRC, "ui/Chrome.kt")) and not re.search(r"Icons\.\w+\.Menu\b|ModalNavigationDrawer|TopAppBar\s*\(", allsrc), "the app draws no hamburger, drawer or top app bar of its own")
tasks_screen = strip(read(SRC, "ui/TasksScreen.kt"))
check("TopBarDrawer {" in tasks_screen and "ProjectsDrawerContent(" in tasks_screen, "the Tasks page fills the bar's drawer with the projects")
check("expanded" in strip(read(SRC, "ui/ProjectsDrawer.kt")) and "emptySet<String>()" in tasks_screen, "projects start collapsed")

# ── the AI panel ──────────────────────────────────────────────────────────────────────────────────────────
ai = strip(read(SRC, "ui/AiDropdown.kt"))
check("AiChatPage(" in ai and "AiFileSessions(" in ai and "sessionsList = true" in ai and "aiPanel =" in app_kt, "the AI panel is libs:ai's page over a persisted sessions file")
check("AiStandardHost(" in ai and "AiGatewayBackend(" in ai, "it talks to the fleet gateway through libs:ai")
check(not re.search(r"\b(role|\"assistant\"|chat/completions|openrouter\.ai)", ai), "the app draws no chat of its own and names no endpoint")

# ── data ──────────────────────────────────────────────────────────────────────────────────────────────────
repo_kt = strip(read(SRC, "data/TasksRepository.kt"))
check('"Project Zero"' in repo_kt and 'Step $step' in repo_kt and "1..3" in repo_kt and "1..2" in repo_kt, "the seed: Project Zero, Step 0 / Step 1, 3 tasks per list, 2 subtasks per task")
check("projects > 0 || marked" in repo_kt, "seeding is idempotent (empty database and no marker)")
db_kt = strip(read(SRC, "data/TasksDb.kt"))
check("fallbackToDestructive" not in db_kt and "DROP TABLE" not in db_kt.upper().replace("DROP  ", "DROP "), "no destructive migration")
check("onDowngrade" in db_kt, "a downgrade is handled without wiping")

# ── the mail agent ────────────────────────────────────────────────────────────────────────────────────────
runner = strip(read(SRC, "agent/AgentRunner.kt"))
check(not re.search(r"\b(saveTask|addTask)\b", runner), "the agent never creates a task, only proposals")
check("BACKEND_NOT_DEPLOYED" in runner and runner.index("backend.status(") < runner.index("mail.recent("), "the backend is checked before any mail is read")
settings = strip(read(SRC, "ui/SettingsPages.kt"))
check("Agent backend not deployed" in settings, "the Agent page says 'Agent backend not deployed' when that is the last status")
for token in ("Enable the mail agent", "Mail account", "Mailboxes", "Frequency", "Agent and model", "What to extract", "Target project and list"):
    check(token in settings, "the Agent page has: " + token)
check("decideProposal" in repo_kt and "ProposalStatus.PENDING" in repo_kt, "approval is the only way a proposal becomes a task")
check(os.path.exists(os.path.join(SRC, "agent/MailAgentWorker.kt")) and 'UNIQUE = "tasks-mail-agent"' in strip(read(SRC, "agent/MailAgentWorker.kt")), "the worker's unique name is the one build.json::fleet_agents declares")
agent_decl = [a for a in build.get("fleet_agents", []) if a["id"] == "mail-tasks"]
check(len(agent_decl) == 1 and agent_decl[0]["status"].get("unique") == "tasks-mail-agent", "the mail agent is declared in fleet_agents with that unique name")

# ── build wiring ──────────────────────────────────────────────────────────────────────────────────────────
gradle = read(APP, "app/build.gradle")
for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64", "UI_DEFAULT_SECTION", "AI_GATEWAY_URL"):
    check(field in gradle, "build.gradle bakes " + field)
for lib in ("libs:bottomnav", "libs:ui-kit", "libs:ai"):
    check("project(':%s')" % lib in gradle, "the app compiles " + lib)
    check(lib in build["modules"], "build.json::modules lists " + lib)
manifest = read(APP, "app/src/main/AndroidManifest.xml")
for ph in ("mailPackage", "drivePackage", "accountPackage"):
    check("${%s}" % ph in manifest, "the manifest queries %s" % ph)

# ── the ratchet and the nav guard ─────────────────────────────────────────────────────────────────────────
mig = json.load(open(os.path.join(REPO, "1_cicd/src/data/compose-migration.json")))
unit = next((u for u in mig["units"] if u["id"] == "ac_cloud-tasks"), None)
check(unit is not None and unit.get("scope") == "in" and all(v == 0 for v in unit["baseline"].values()), "the compose ratchet holds Cloud Tasks at zero View UI")
shape = json.load(open(os.path.join(REPO, "1_cicd/src/data/nav-shape.json")))
check("ac_cloud-tasks" not in shape.get("exempt", {}), "Cloud Tasks is held by the nav-shape guard (no longer exempt)")

print("all passed" if fails == 0 else "%d failed" % fails)
sys.exit(1 if fails else 0)
PY
