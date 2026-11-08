#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #900 Configs is a section of top tabs, one page per category      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   C1 build.json::ui.browser.settings_sections declares the pages (= the top tabs), in order; every setting
#      names one of them and every page holds a setting, a global row or a native block;
#   C2 ui.sections[configs].pages is the SAME list (ids and order): the tabs libs:ui-nav-bars draws;
#   C3 each page is drawn on its own (BrowserConfigsPageScreen): its settings, then its rows, then its native
#      blocks, from libs:ui-kit's rows and a chip row per enum; the host swaps the page when a tab is picked;
#   C4 the page is Compose (no View screen added: the compose ratchet counts it).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, os
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

# #1402 the Configs pages, in order; every former page id redirects to the page that holds its content now
PAGES = ["sites", "permissions", "autocomplete", "browsing", "headless", "gpu"]   # #1443 the host's pages; the tabs start with the fleet's General | Perms | About
FLEET = ["general", "perms", "about"]
OLD = ["general", "search", "privacy", "site", "autocomplete", "passwords", "payment", "downloads",
       "appearance", "tabs", "sync", "advanced"]

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    sheets = src.get(LIB + "/BrowserSheets.kt", "")
    screen = src.get(LIB + "/BrowserConfigsScreen.kt", "")
    cfg = src.get(LIB + "/BrowserConfig.kt", "")
    main_act = src.get(APP + "/MainActivity.kt", "")
    bj = build_json(root)
    b = bj["ui"]["browser"]
    topics = [s["id"] for s in b.get("settings_sections", [])]
    ok(topics == PAGES, "C1 the pages are declared, in order", str(topics))
    for s in b["settings"]:
        ok(s["section"] in topics, "C1 setting `%s` sits on a declared page" % s["key"], s["section"])
    held = {s["section"] for s in b["settings"]} | {r["page"] for r in b["configs"]["rows"]} | set(b["configs"]["native"])
    held |= {m["page"] for a in b["addons"] for m in a.get("menu", []) if m.get("scope") == "global"}
    for t in topics:
        ok(t in held, "C1 page `%s` holds something" % t)
    nav = [s for s in bj["ui"]["sections"] if s["id"] == "configs"][0]
    ok([p["id"] for p in nav.get("pages", [])] == FLEET + topics, "C2 the configs section's tabs are the fleet pages (General | Perms | About, #1443) then the settings pages, in order")
    ok("NavBarTopSecondRow(" in main_act and "openConfigsPage(" in main_act and "host.openConfigsPage(id)" in main_act, "C2 the shell draws the tabs with NavBarTopSecondRow and swaps the page")
    ok("fun openConfigsPage(" in frag and "val configsPageIds" in frag, "C2 the host opens a page by id")
    ok("catalogue.settings.filter { it.section == pageId }" in screen and "BrowserSettingRow(s, v)" in screen, "C3 a page draws the settings of its own id")
    ok("rs.forEach" in screen and "native()" in screen, "C3 then its rows and its native blocks")
    # #1402 grouped pages, redirected anchors, the fleet About, Sites and Permissions
    c = b["configs"]
    groups = c.get("groups", {})
    ok([g["id"] for g in groups.get("browsing", [])] == ["general", "search", "privacy", "site", "downloads", "appearance", "tabs", "advanced"], "C5 Browser groups every other setting, in sections")
    ok([g["id"] for g in groups.get("autocomplete", [])] == ["autocomplete", "passwords", "payment", "sync"], "C5 Autocomplete groups passwords, payment and the vault")
    for s in b["settings"]:
        gl = [g["id"] for g in groups.get(s["section"], [])]
        ok(not gl or s.get("group") in gl, "C5 setting `%s` sits in a declared group" % s["key"], s.get("group"))
    red = c.get("redirects", {})
    for o in OLD:
        ok(o in topics or red.get(o) in topics, "C6 the old anchor `%s` opens a page" % o, red.get(o))
    ok("val page = pages.pageFor(pageId) ?: pages.first()" in frag and "configsPage = host.configsPageFor(id)" in main_act, "C6 old anchors redirect (host and shell)")
    ok("FleetConfigsPageBody(" in main_act and "isFleetConfigsPage(configsPage)" in main_act, "C7 General | Perms | About are the fleet pages")
    ok(c["native"].get("sites") == ["site_list"] and '"site_list" -> SiteListBlock()' in frag and "private fun showSite(host: String)" in frag, "C8 Sites lists the sites; one site holds its Data, Perms and more")
    site = frag[frag.find("private fun showSite(host: String)"):]
    ok('"browser:site:data"' in site and "showSiteSettings(host)" in site and '"browser:site:zoom"' in site, "C8 a site's Data, Perms and other settings")
    ok(c["native"].get("permissions") == ["perms_defaults"] and "BrowserPermsList(null, config.sitePerms" in frag, "C9 Permissions is the defaults and the overrides, the Perms component")
    ok("groups.forEach { g ->" in screen and "groups = pages.groupsFor(page)" in frag, "C5 a page draws its groups under their titles")
    ok("KitSectionHeader(" in screen and "KitSettingsRow(" in screen, "C3 with the fleet kit's header and rows")
    ok("KitSwitchRow(" in sheets and "FilterChip(selected = v == opt" in sheets and "FlowRow(" in sheets, "C3 a bool is a switch, an enum a dense chip row")
    ok("pages.resolve(page, allFacts(), config.menu.whys)" in frag and "native = { ConfigsNative(pages.nativeFor(page)) }" in frag, "C3 the host hands the page its rows and blocks")
    ok('optJSONArray("settings_sections")' in cfg and "BrowserConfigPages.parse(" in cfg, "C3 the config parses them")
    ok("@Composable" in screen and "BrowserConfigsPageScreen" in screen, "C4 the page is Compose")

BJ = "ac_cloud-browser/build.json"
SC = LIB + "/BrowserConfigsScreen.kt"
main("configs layout", check, [
    ("a page is dropped from the declaration", BJ, '"id": "permissions",\n          "label": "Permissions"\n', '"id": "permissions_gone",\n          "label": "Permissions"\n', "declared, in order"),
    ("a setting lands on no page", BJ, '"label": "Tab strip",\n          "section": "browsing"', '"label": "Tab strip",\n          "section": "nowhere"', "sits on a declared page"),
    ("an old anchor loses its redirect", BJ, '"privacy": "browsing",', '', "old anchor `privacy`"),
    ("About stops being the fleet page", APP + "/MainActivity.kt", "com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(", "BrowserAboutGone(", "are the fleet pages"),
    ("groups are not drawn", SC, "groups.forEach { g ->", "emptyList<BrowserConfigGroup>().forEach { g ->", "draws its groups"),
    ("a site loses its Perms", LIB + "/BrowserHostFragment.kt", 'KitSettingsRow("Perms", "every permission this site can request", Modifier.testTag("browser:site:perms_open"), onClick = { showSiteSettings(host) })', '', "Data, Perms and other"),
    ("the nav tabs lose a page", BJ, '"id": "gpu",\n            "label": "GPU",\n            "icon": "language"\n          }\n        ]', '"id": "gpu",\n            "label": "GPU",\n            "icon": "language"\n          }\n        , {"id": "extra", "label": "Extra"}]', "tabs are the fleet pages"),
    ("the page ignores its id", SC, "catalogue.settings.filter { it.section == pageId }", "catalogue.settings.filter { true }", "settings of its own id"),
    ("native blocks are not drawn", SC, "        native()\n", "", "native blocks"),
    ("enums become a list again", LIB + "/BrowserSheets.kt", "FilterChip(selected", "androidx.compose.material3.Text(selected", "chip row"),
    ("the host stops passing the rows", LIB + "/BrowserHostFragment.kt", "rows = pages.resolve(page, allFacts(), config.menu.whys),", "rows = emptyList(),", "hands the page"),
    ("the shell stops swapping pages", APP + "/MainActivity.kt", "host.openConfigsPage(id)", "host.openSettings()", "swaps the page"),
])
PYEOF
