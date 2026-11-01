#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I8 the Search add-on reuses cloud-search; it copies nothing ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# An engine URL or the chat endpoint spelled in the add-on is a second
# declaration that drifts from cloud-search's; a stored token is a token on
# disk. Matched in COMMENT-STRIPPED Kotlin and in build.json.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    bj = build_json(root)
    b = bj["ui"]["browser"]
    search = json.load(open(os.path.join(root, "ac_cloud-search/build.json")))["search"]
    src = kotlin(root, LIB, APP)
    allkt = "".join(src.values())
    # The address bar's own engines (ui.browser.search_engines) are the browser's; the ADD-ON's
    # side (the app's Kotlin and the add-on's block) must not restate cloud-search's.
    appkt = "".join(t for p, t in src.items() if p.startswith(APP))
    raw = json.dumps([a for a in b.get("addons", []) if a["id"] == "search"])
    for e in search["engines"]:
        host = re.sub(r"^https?://", "", e["url"]).split("/")[0]
        ok(host not in appkt and host not in raw, "engine `%s` (%s) is not spelled in the browser" % (e["id"], host))
    ok("openrouter.ai" not in allkt and "openrouter.ai" not in json.dumps(b), "the chat endpoint is not spelled in the browser")
    ok(bj["modules"].get("search-core", {}).get("dir") == "../ac_cloud-search/core", "search-core is linked by reference")
    ok("search-core" in bj["modules"]["app"]["depends_on"], "the app depends on search-core")
    gradle = open(os.path.join(root, "ac_cloud-browser/app/build.gradle")).read()
    ok("ac_cloud-search/build.json" in gradle and "it.engines = searchDecl.engines" in gradle and "SEARCH_CONFIG_B64" in gradle,
       "app/build.gradle reads cloud-search's declaration (engines + the baked search block)")
    ok("implementation project(':search-core')" in gradle, "the app links search-core")
    addon = src.get(os.path.join(APP, "search", "SearchAddon.kt"), "")
    ok(not re.search(r"put(String|StringSet)\([^)]*token", allkt, re.I), "no token is written to prefs")
    ok("Log." not in addon and "println" not in addon, "the search add-on logs nothing")
    ok('"no $provider token in the fleet Account"' in addon, "the missing-token answer is cloud-search's wording")
    ok(re.search(r'put\("[a-z_]+",\s*value\)', addon) is None and "outcomeJson" in addon, "the token never enters an answer")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    for op in ("search/engines", "search/open", "search/chat/sessions", "search/chat/send"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok(api.count('!config.addons.enabled("search"') >= 2, "search/open and chat/send refuse while the add-on is off")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    ok('"search_with" -> { showSearchWith()' in frag and "config.addons.searchEngines()" in frag, "the Search with row is wired to the declared engines")
    # #823 the on-screen page is the fleet's AI page (libs:ai), not a copy, on cloud-search's declared engines.
    page = src.get(os.path.join(APP, "search", "SearchPageScreen.kt"), "")
    ok(bj["modules"].get("libs:ai", {}).get("dir") == "../ab_cloud-libs-shared/libs/ai"
       and "implementation project(':libs:ai')" in gradle, "the app links libs:ai by reference")
    ok("AiChatPage(" in page and "engines = search.cfg.engines.map" in page and "query = { e, q -> search.searchUrl(q, e.id) }" in page,
       "the page is libs:ai's, on cloud-search's declared engines")
    ok("search.host(app.applicationContext)" in page and "direct = backend { accountToken(app, ai.accountProvider) }" in addon,
       "its chat is libs:ai's host on the add-on's backend, the token read per send")
    ok("AI_CONFIG_B64" in gradle and "libs/ai/src/main/assets/ai/ai.json" in gradle and "AiConfig.parse(" in addon,
       "the endpoints and header names are libs:ai's shared contract, baked, never restated")
    panel = src.get(os.path.join(APP, "search", "AgentPanelScreen.kt"), "")
    ok("AiAssistantPanel(" in panel and "BrowserAgentPanelHost.panel =" in panel and "AgentPanelScreen.install()" in api,
       "the assistant side panel is libs:ai's, installed at start-up")
    ok('"search_chat" -> { showSearchPage()' in frag and "openEntryUrl(url)" in frag[frag.find("private fun showSearchPage("):][:1600],
       "the Search & AI row is wired, and a result opens as a tab")
    ok("SearchPageScreen.install(" in api, "the app installs the page at start-up")

BJ = "ac_cloud-browser/build.json"
APPP = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/"
main("search reuse", check, [
    ("an engine URL is copied into the browser", APPP + "search/SearchAddon.kt", 'class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {', 'class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {\n    val ddg = "https://duckduckgo.com/?q={q}"', "is not spelled in the browser"),
    ("the chat endpoint is copied into the browser", APPP + "search/SearchAddon.kt", 'class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {', 'class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {\n    val chat = "https://openrouter.ai/api/v1/chat/completions"', "the chat endpoint is not spelled in the browser"),
    ("the token is stored", APPP + "search/SearchAddon.kt", "val m = model?.takeIf { it.isNotBlank() } ?: ai.defaultModel", "val m = model?.takeIf { it.isNotBlank() } ?: ai.defaultModel\n        prefsX.putString(\"token\", token().first)", "no token is written"),
    ("the gradle stops reading cloud-search", "ac_cloud-browser/app/build.gradle", "it.engines = searchDecl.engines", "it.engines = []", "reads cloud-search's declaration"),
    ("the page restates its own engines", APPP + "search/SearchPageScreen.kt", "engines = search.cfg.engines.map", "engines = emptyList<com.diegonmarcos.cloudsearch.core.SearchConfig.Engine>().map", "on cloud-search's declared engines"),
    ("a result replaces the page instead of a tab", "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt", "page({ url -> close(); openEntryUrl(url) }, close)", "page({ url -> close(); webView?.loadUrl(url) }, close)", "opens as a tab"),
    ("the page reads a token of its own", APPP + "search/SearchAddon.kt", "direct = backend { accountToken(app, ai.accountProvider) }", "direct = backend { \"x\" to \"\" }", "the token read per send"),
    ("the assistant panel is drawn by the browser again", APPP + "search/AgentPanelScreen.kt", "AiAssistantPanel(", "OwnPanel(", "the assistant side panel is libs:ai's"),
    ("the gradle stops baking the shared contract", "ac_cloud-browser/app/build.gradle", 'buildConfigField "String", "AI_CONFIG_B64",', 'buildConfigField "String", "AI_CONFIGX_B64",', "endpoints and header names are libs:ai's shared contract"),
    ("chat/send ignores the switch", APPP + "debugapi/BrowserDebugApi.kt", '"search/chat/send" -> if (!config.addons.enabled("search"', '"search/chat/send" -> if (false && !config.addons.enabled("searchx"', "refuse while the add-on is off"),
])
PYEOF
