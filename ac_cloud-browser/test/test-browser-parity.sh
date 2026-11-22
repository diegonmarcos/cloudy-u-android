#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1842 Rule 16 parity: the browser nav, Weby, the Cloudy pages    ║
# ║ and the shared top bar keep everything they offered before       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# parity-baseline-1842.json is the FROZEN list of what each of them offered before the change (adding is free, removing is not):
#   N  the island holds the same five items, now in the order Domains, Tabs, Browser, Cloudy, Weby; the default is still Browser
#   W  Weby keeps every tag, text and engine object it had and gains the Quickmarks | Bookmarks (all) views; Bookmarks (all) holds every Quickmark
#   C  the four old Cloudy pages are WebApps (GH Pages + Local Webserver, in Weby's page) and C3 Urls (Private + Public merged: one grid
#      by c3-infra category, a chooser, tap opens, long-press copies); every function and word they used is still there
#   T  the shared top bar: ONE control (amendment 7): the Classic row's path dropdown carries Url-Uri (A Pages, B Tools) and the tree of parents and keeps
#      every entry of the old path popup (T14); the Clean row keeps the Url-Uri button (Pages and Tools, the frozen ids, the app slots read from
#      build.json::ui.url_uri), the right icons at the Android status-icon size, every tag and the hamburger kept, and every app that drew
#      the shared first row still draws it
# Each check is then re-run against planted mutations of the real sources; a mutation that leaves every check green fails this tester.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
import browser_tester
from browser_tester import LIB, APP, kotlin, build_json, main

NAVLIB = "ab_cloud-libs-shared/libs/bottomnav/src"
BARS = "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1"
SITES = APP + "/sites"
REAL = os.environ["ROOT"]
BASE = json.load(open("parity-baseline-1842.json", encoding="utf-8"))
browser_tester.COPY += ["ab_cloud-libs-shared/libs/bottomnav", "ab_cloud-libs-shared/libs/ui-nav-bars", "ab_cloud-libs-shared/libs/ui-icons"]

def app_file(root, app):
    """The file of [app] that calls the shared first row (the real tree when a mutated copy does not carry the app)."""
    base = root if os.path.isdir(os.path.join(root, app)) else REAL
    for d, _, fs in os.walk(os.path.join(base, app)):
        if "/build" in d or "/test" in d: continue
        for f in fs:
            if f.endswith(".kt") and "NavBarTopFirstRowScaffold(" in open(os.path.join(d, f), encoding="utf-8").read():
                return os.path.join(d, f)
    return None

def check(root, ok):
    bj = build_json(root); ui = bj["ui"]
    src = kotlin(root, APP, NAVLIB.replace("/src", "") + "/src", "ab_cloud-libs-shared/libs/ui-nav-bars/src/main")
    sites = "".join(v for k, v in src.items() if k.startswith(SITES))
    ui_sites = src.get(SITES + "/SitesUi.kt", "")
    # ── N the island
    n = BASE["nav"]
    ok(ui["bottom_nav"] == n["order_after"], "N1 the island is Domains | Tabs | Browser | Cloudy | Weby", str(ui["bottom_nav"]))
    ok(sorted(ui["bottom_nav"]) == sorted(n["items_before"]), "N2 no island item was dropped or added")
    ok(ui["default_section"] == n["default_section"], "N3 the app still opens on Browser")
    secs = {s["id"]: s for s in ui["sections"]}
    ok(all(i in secs for i in ui["bottom_nav"]), "N4 every island item is a declared section")
    # ── C Cloudy
    c = BASE["cloudy"]
    ok([p["id"] for p in secs["cloudy"]["pages"]] == c["pages_after"], "C1 Cloudy's pages are WebApps | GH Pages | Local | C3 (#1856)")
    ok(all(new in c["pages_after"] for new in c["pages_before_to_after"].values()), "C2 every old Cloudy page has a home")
    cloudy_kt = src.get(SITES + "/Cloudy.kt", "")
    for fn in c["api_before"]:
        ok(re.search(r"fun %s\(" % fn, cloudy_kt) is not None, "C3 Cloudy.%s is still there" % fn)
    for t in c["tags_before"]:
        ok('"%s"' % t in ui_sites, "C4 the tag %s is still drawn" % t)
    for k in c["texts_before"]:
        ok(k in ui["cloudy"]["texts"], "C5 the word %s is still declared" % k)
    ok("Cloudy.merge(seed, cache.refresh(" in ui_sites and "Cloudy.merge(seed, cache.pagesJson())" in ui_sites and "Cloudy.webserverExplorers(" in ui_sites and "Cloudy.webserverPages(" in ui_sites and "Cloudy.storeEntries(" in ui_sites and "Cloudy.seedPages(" in ui_sites,
       "C6 GH Pages lists the declared + live pages and Local the Webserver (declared + Repos store), each on its own subpage (#1856)")
    weby_kt = src.get(SITES + "/Weby.kt", "")
    ok("CloudyWebApps.webApps(" in ui_sites and "CloudyWebApps.ghPages(" in ui_sites and "CloudyWebApps.local(" in ui_sites and "WebyPage(decl, fixed = folders" in ui_sites, "C7 WebApps, GH Pages and Local are Weby's own page over fixed sections (#1856)")
    for sec in c["sections_after"]:
        ok('"%s"' % sec in weby_kt or sec.replace(" ", "_").upper() in weby_kt, "C8 the section %s exists" % sec)
    ok("const val WEBAPPS = \"WebApps\"" in weby_kt and "const val GH_PAGES = \"GitHub Pages\"" in weby_kt and "const val LOCAL = \"Local Webserver\"" in weby_kt, "C8 the three section titles")
    ok("Cloudy.c3Groups(" in ui_sites and 'optString("private_url")' in cloudy_kt and 'optString("public_url")' in cloudy_kt, "C9 C3 Urls merges the private and public routes by category")
    ok("combinedClickable(" in ui_sites and "onLongClick = { copyUrl(" in ui_sites and "onOpen(url!!)" in ui_sites and "chosen = ct" in ui_sites, "C10 a tap opens the chooser, a tap on a row opens the URL, a long-press copies it")
    for t in c["c3_tags"]:
        ok(t in ui_sites, "C11 the C3 tag %s" % t)
    bg = open(os.path.join(root, "ac_cloud-browser/app/build.gradle"), encoding="utf-8").read()
    ok("app_categories" in bg and "tile_groups" in bg and "pathData" in bg and "categories: webappCategories" in bg,
       "C12 WebApps' categories (Cloud Store) and icons (SuperApp drawables) are DERIVED at build time, never a list here")
    ok("links_url" in ui["cloudy"].get("webapps", {}) and "cloudy-h-webapps-mob" in ui["cloudy"]["webapps"]["links_url"], "C13 the web apps' addresses come from the cloudy-h-webapps-mob repo's declared app-links.json")
    # ── W Weby
    w = BASE["weby"]
    for t in w["tags_before"]:
        ok('"%s"' % t in ui_sites, "W1 the tag %s is still drawn" % t)
    for k in w["texts_before"]:
        ok(k in ui["weby"]["texts"], "W2 the word %s is still declared" % k)
    for o in w["api_before"]:
        ok(re.search(r"(object|class) %s\b" % o, weby_kt) is not None, "W3 %s is still there" % o)
    ok('WEBY_QUICK = "quickmarks"' in ui_sites and 'WEBY_ALL = "bookmarks"' in ui_sites and "view_quickmarks" in ui_sites and "view_bookmarks" in ui_sites
       and ui["weby"]["texts"].get("view_quickmarks") == "Quickmarks" and ui["weby"]["texts"].get("view_bookmarks") == "Bookmarks (all)", "W4 Weby splits Quickmarks | Bookmarks (all)")
    ok("WebyBookmarks.all(folders, browserMarks, catalogue)" in ui_sites and "WebyFolder(QUICK, emptyList(), quick)" in weby_kt and "BrowserBookmarks(ctx).all()" in ui_sites,
       "W5 Bookmarks (all) holds the browser's bookmarks, every Quickmark and the Phone-apps catalogue")
    ok("else -> folders" in ui_sites and "WebyOps.addBookmark(folders" in ui_sites, "W6 the Quickmarks view is still the editable groups")
    # ── T the shared top bar
    t = BASE["top_bar"]
    uu = src.get(NAVLIB + "/main/kotlin/com/diegonmarcos/superapp/bottomnav/UrlUri.kt", "")
    consts = dict(re.findall(r'const val (\w+): String = "(\w+)"', uu))
    fp = re.search(r"FIXED_PAGES: List<String> = listOf\(([^)]*)\)", uu); ft = re.search(r"FIXED_TOOLS: List<String> = listOf\(([^)]*)\)", uu)
    ok(fp is not None and [consts.get(x.strip()) for x in fp.group(1).split(",")] == t["pages_after"], "T1 the Pages entries after the bottom pages are AI, Profile, Configs")
    ok(ft is not None and [consts.get(x.strip()) for x in ft.group(1).split(",")] == t["tools_after"], "T2 the Tools are back, up, home, background, force close, restart")
    ok("pages = decl.pages" in uu or "decl.pages.forEach" in uu, "T3 the app's pages slot is read from the declaration")
    ok("decl.tools.forEach" in uu, "T3 the app's tools slot is read from the declaration")
    ok(set(i["id"] for i in ui["url_uri"]["pages"]) <= set(secs) and ui["url_uri"]["tools"], "T4 build.json::ui.url_uri fills the slots (its pages name declared sections)")
    ok("url_uri: buildJson.ui.url_uri" in bg and "UrlUriDecl.parse(sites.urlUri.toString())" in src.get(APP + "/MainActivity.kt", ""), "T5 the browser carries its slots (ui.url_uri) in its declaration and hands them to the bar")
    ok("UrlUriBuildConfig" in src.get(BARS.replace("/src/main/kotlin", "/src/main/kotlin") + "/NavBarTopFirstRow.kt", ""), "T6 an app that passes nothing still gets the dropdown (read from its BuildConfig)")
    row = src.get(BARS + "/NavBarTopFirstRow.kt", ""); clean = src.get(BARS + "/NavBarTopCleanRow.kt", "")
    ok(clean.count("leading?.invoke()") == 1 and row.count("leading?.invoke()") == 1 and row.count("leading = urlUriControl") == 1 and row.count("NavBarTopCleanRow(") == 1, "T7 the Clean row keeps the Url-Uri button (it has no path popup)")
    ok("urlUri = UrlUriMenu(" in row and "UrlUriPagesGroup(uu, colors)" in row and "UrlUriToolsGroup(uu, colors)" in row,
       "T7 the Classic row has ONE control: its path dropdown carries Url-Uri Pages and Tools, no chain-link button (amendment 7)")
    # amendment 7 field parity (source): every tag of the previous path popup and of the previous Url-Uri popup is still drawn by the merged dropdown
    dd = src.get(BARS + "/UrlUriDropdown.kt", "")
    for tag in ("TopFirstRowTags.crumb(i))", "TopFirstRowTags.SEPARATOR_PAGES)", "TopFirstRowTags.SEPARATOR_ACTIONS)", "TopFirstRowTags.ACTIONS)", "TopFirstRowTags.HEADER_TREE", "TopFirstRowTags.HEADER_SIBLINGS", "TopFirstRowTags.HEADER_ACTIONS", "TopFirstRowTags.CONFIGS_GENERAL)", "TopFirstRowTags.CONFIGS_ABOUT)", "TopFirstRowTags.entry(e.id))", "TopFirstRowTags.DROPDOWN)"):
        ok(tag in row, "T14 the path popup's %s is kept in the merged dropdown" % tag)
    for tag in ("UrlUriTags.HEADER_PAGES", "UrlUriTags.HEADER_TOOLS", "UrlUriTags.entry(e.id)"):
        ok(tag in dd, "T14 the Url-Uri %s is drawn by the shared groups" % tag)
    ok("UrlUriTags.SEPARATOR" in row and "UrlUriTags.DROPDOWN" in row and "UrlUriTags.SEPARATOR" in dd, "T14 the Url-Uri separator and container tags stay")
    pop = row.find("Header(\"Parents Tree\"")
    pos = [row.find(k, pop) if pop >= 0 else -1 for k in ("Header(\"Parents Tree\"", "TopFirstRowTags.crumb(i)", "HEADER_SIBLINGS", "nav.related.forEach", "UrlUriPagesGroup(uu", "SEPARATOR_ACTIONS", "UrlUriToolsGroup(uu", "CONFIGS_GENERAL")]
    ok(all(x >= 0 for x in pos) and pos == sorted(pos), "T14 the merged order is Parents Tree, Siblings, Parent Pages, actions, Tools, Configs (#1850)")
    # #1850 the owner-approved removal of the page drawer list and the sections list: the parity check proves every destination is still reachable instead
    ok("nav.sections.forEach" not in row and "SEPARATOR_SECTIONS" not in row, "T15 the sections list is removed from the path popup (#1850, owner-approved)")
    ok("c.actionsContent" in row and "c.drawerContent" not in row, "T15 the page drawer slot is no longer listed; only the page's own actions are (#1850)")
    ok("mergedParentPages(m.sections, sections)" in row, "T15 every declared section the removed list carried is a Parent Page (no destination lost)")
    ok('Header("Parents Tree"' in row and 'Header("Siblings"' in row and 'Header("Parent Pages"' in dd and "SEPARATOR_SETTINGS" in dd, "T15 the headers and the Settings line are drawn")
    ok("Pages\", UrlUriTags.HEADER_PAGES" not in dd.replace('"Parent Pages"', ""), "T15 the old Pages header text is gone")
    app_src = src.get(APP + "/MainActivity.kt", "")
    ok("TopBarDrawerWithActions" in app_src and "TopBarDrawer {" not in app_src, "T15 the browser keeps its real actions (back, forward, reload, new tab) under the Actions header")
    ok(re.search(r"ICON_RIGHT: Dp = %d\.dp" % t["icon_right_dp"], row) is not None and "modifier = Modifier.size(TopFirstRowSpec.ICON_RIGHT))\n    }\n}" in row,
       "T8 the right-side icons are drawn at the status-bar icon size (%ddp)" % t["icon_right_dp"])
    ok("CleanDrawerTags.MENU" in clean and "controller.openDrawer()" in clean, "T9 the Clean hamburger is kept")
    allrow = row + clean + src.get(BARS + "/UrlUriDropdown.kt", "")
    for tag in ("navtop1_more", "navtop1_dropdown", "navtop1_actions"):
        ok('"%s"' % tag in allrow, "T10 the tag %s is kept" % tag)
    for tg in ("PlainIcon(Icons.Filled.AutoAwesome, labels.ai", "PlainIcon(Icons.Filled.AccountCircle, labels.account", "PlainIcon(Icons.Filled.MoreVert, labels.configs"):
        ok(tg in row, "T11 the right icon %s is kept" % tg.split("(")[1].split(",")[0])
    # every app's declared slots (build.json::ui.url_uri) are well formed: id, label, icon; a pages entry names a page; ids unique
    for d in sorted(os.listdir(REAL)):
        bjp = os.path.join(root if d == "ac_cloud-browser" else REAL, d, "build.json")
        if not os.path.exists(bjp) or d.startswith((".", "0_")): continue
        try: uu_decl = (json.load(open(bjp, encoding="utf-8")).get("ui") or {}).get("url_uri")
        except Exception: continue
        if uu_decl is None: continue
        for slot in ("pages", "tools"):
            items = uu_decl.get(slot, [])
            ok(all(i.get("id") and i.get("label") and i.get("icon") for i in items) and len({i.get("id") for i in items}) == len(items), "T13 %s ui.url_uri.%s entries have id, label, icon and unique ids" % (d, slot))
        ok(all(i.get("page") for i in uu_decl.get("pages", [])), "T13 %s ui.url_uri.pages entries name the page they open" % d)
    for app in t["apps_with_shared_first_row"]:
        f = app_file(root, app)
        ok(f is not None, "T12 %s still draws the shared first row (NavBarTopFirstRowScaffold)" % app)

def mut(label, rel, old, new, expect=None):
    return (label, rel, old, new, expect) if expect else (label, rel, old, new)

calc = app_file(REAL, "ac_cloud-calc")
calc_rel = os.path.relpath(calc, REAL)
bn = "ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/UrlUri.kt"
browser_tester.COPY.append(os.path.dirname(calc_rel).split("/src/")[0])
main("test-browser-parity", check, [
    mut("the island order reverts", "ac_cloud-browser/build.json", '"bottom_nav": [\n      "domains",\n      "tabs",', '"bottom_nav": [\n      "tabs",\n      "domains",', "N1"),
    mut("an island item is dropped", "ac_cloud-browser/build.json", '"cloudy",\n      "weby"\n    ],', '"cloudy"\n    ],', "N2"),
    mut("Cloudy loses C3 Urls", "ac_cloud-browser/build.json", '{"id": "c3_urls", "label": "C3", "icon": "link"}', '{"id": "c3_x", "label": "C3", "icon": "link"}', "C1"),
    mut("Cloudy stops listing the GH pages", SITES + "/SitesUi.kt", "Cloudy.merge(seed, cache.refresh(", "Cloudy.mergeX(seed, cache.refresh(", "C6"),
    mut("a Cloudy function is removed", SITES + "/Cloudy.kt", "fun storeEntries(", "fun storeEntriesX(", "C3"),
    mut("the Webserver open row goes", SITES + "/SitesUi.kt", '"cloudy:ws:open"', '"cloudy:ws:openX"', "C4"),
    mut("WebApps stops using Weby's page", SITES + "/SitesUi.kt", "CloudyWebApps.webApps(", "CloudyWebApps.webAppsX(", "C7"),
    mut("GH Pages stops listing the site's pages", SITES + "/SitesUi.kt", "CloudyWebApps.ghPages(", "CloudyWebApps.ghPagesX(", "C7"),
    mut("Local loses File Explorers and Pages", SITES + "/SitesUi.kt", "CloudyWebApps.local(", "CloudyWebApps.localX(", "C7"),
    mut("the C3 long-press stops copying", SITES + "/SitesUi.kt", "onLongClick = { copyUrl(", "onLongClick = { copyUrlX(", "C10"),
    mut("the chooser is skipped (a tap opens at once)", SITES + "/SitesUi.kt", "chosen = ct }", "onOpen(ct.publicUrl ?: \"\") }", "C10"),
    mut("C3 stops merging both addresses", SITES + "/Cloudy.kt", 'o.optString("public_url").takeIf', 'o.optString("public_urlX").takeIf', "C9"),
    mut("a Cloudy word is dropped", "ac_cloud-browser/build.json", '"ws_open": "Open Cloud-Webserver"', '"ws_openX": "Open Cloud-Webserver"', "C5"),
    mut("the categories become a hand list", "ac_cloud-browser/app/build.gradle", "categories: webappCategories", "categories: []", "C12"),
    mut("Weby loses its editable view", SITES + "/SitesUi.kt", "else -> folders", "else -> emptyList()", "W6"),
    mut("Weby loses the Bookmarks (all) view", SITES + "/SitesUi.kt", "view == WEBY_ALL -> WebyBookmarks.all(", "view == WEBY_ALL -> WebyBookmarksX.all(", "W5"),
    mut("Bookmarks (all) drops the Quickmarks", SITES + "/Weby.kt", "WebyFolder(QUICK, emptyList(), quick)", "WebyFolder(QUICK, emptyList(), emptyList())", "W5"),
    mut("Weby drops its add-bookmark tag", SITES + "/SitesUi.kt", '"weby:add_bookmark"', '"weby:add_bookmarkX"', "W1"),
    mut("Weby drops a word", "ac_cloud-browser/build.json", '"add_folder": "New folder"', '"add_folderX": "New folder"', "W2"),
    mut("a Tools entry is removed", bn, "listOf(BACK, UP, HOME, BACKGROUND, FORCE_CLOSE, RESTART)", "listOf(BACK, UP, HOME, BACKGROUND, RESTART)", "T2"),
    mut("a Pages entry is removed", bn, "listOf(AI, PROFILE, CONFIGS)", "listOf(AI, CONFIGS)", "T1"),
    mut("the app's tools slot is ignored", bn, "decl.tools.forEach", "emptyList<UrlUriItem>().forEach", "T3"),
    mut("the browser stops filling its slots", APP + "/MainActivity.kt", "UrlUriDecl.parse(sites.urlUri.toString())", "UrlUriDecl()", "T5"),
    mut("the Clean row loses the dropdown", BARS + "/NavBarTopCleanRow.kt", "leading?.invoke()", "", "T7"),
    mut("the Classic row loses the dropdown", BARS + "/NavBarTopFirstRow.kt", "leading?.invoke()", "", "T7"),
    mut("the path dropdown loses the Tools group", BARS + "/NavBarTopFirstRow.kt", "UrlUriToolsGroup(uu, colors) { open = false }", "", "T7"),
    mut("the path dropdown loses the Pages group", BARS + "/NavBarTopFirstRow.kt", "UrlUriPagesGroup(uu, colors) { open = false }", "", "T7"),
    mut("the path popup loses Configs About", BARS + "/NavBarTopFirstRow.kt", "TopFirstRowTags.CONFIGS_ABOUT)", "TopFirstRowTags.CONFIGS_ABOUTX)", "T14"),
    mut("Parent Pages stops carrying the off-bar sections", BARS + "/NavBarTopFirstRow.kt", "mergedParentPages(m.sections, sections)", "m.sections", "T15"),
    mut("the page drawer slot returns to the path popup", BARS + "/NavBarTopFirstRow.kt", "val slot = c.actionsContent", "val slot = c.drawerContent", "T15"),
    mut("the browser loses its actions", APP + "/MainActivity.kt", "TopBarDrawerWithActions {", "TopBarDrawer {", "T15"),
    mut("the chain-link button returns on the Classic row", BARS + "/NavBarTopFirstRow.kt", "leaf = leaf ?: leafState.text,\n", "leaf = leaf ?: leafState.text, leading = urlUriControl,\n", "T7"),
    mut("the right icons grow back", BARS + "/NavBarTopFirstRow.kt", "ICON_RIGHT: Dp = 17.dp", "ICON_RIGHT: Dp = 24.dp", "T8"),
    mut("the right icons ignore the status size", BARS + "/NavBarTopFirstRow.kt", "modifier = Modifier.size(TopFirstRowSpec.ICON_RIGHT))\n    }\n}", "modifier = Modifier.size(TopFirstRowSpec.ICON))\n    }\n}", "T8"),
    mut("the Clean hamburger is removed", BARS + "/NavBarTopCleanRow.kt", "controller.openDrawer()", "Unit", "T9"),
    mut("the right Configs icon is removed", BARS + "/NavBarTopFirstRow.kt", "PlainIcon(Icons.Filled.MoreVert, labels.configs", "PlainIcon(Icons.Filled.Menu, labels.configs", "T11"),
    mut("a slot entry names no page", "ac_cloud-browser/build.json", '"icon": "search", "page": "search"', '"icon": "search"', "T13"),
    mut("an app stops drawing the shared first row", calc_rel, "NavBarTopFirstRowScaffold(", "NavBarTopFirstRowScaffoldX(", "T12"),
])
PYEOF
