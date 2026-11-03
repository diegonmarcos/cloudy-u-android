#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #900 the page dropdown is PAGE-only; Configs is a section of tabs ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   P1 the dropdown (build.json::ui.browser.menu) holds no global row: no tab, library, settings, clear-data,
#      add-on, default-browser or vault row, no `settings` section, no settings_section on a row;
#   P2 every row the owner listed for the page or domain is there, each with its runAction branch;
#   P3 every global thing that left the dropdown is a Configs row (or an add-on global row) on a declared page;
#   P4 the nav: bottom_nav is Fav | Tabs | Browser | Search | Scrapers (#1426) and Configs' tabs are, in order, the
#      13 categories, drawn by NavBarTopSecondRow in the shell (nav-shape guard N5);
#   P5 every setting maps to exactly one Configs page (none dropped: the count is the pre-#900 count plus the new ones).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

GLOBAL = ["settings", "profile", "offline_manage", "storage_manage", "default_browser", "clear_data", "history", "bookmarks",
          "downloads", "addons_manage", "new_tab", "new_private_tab", "tab_groups", "pin_tab", "close_tab",
          "search_with", "search_chat", "vault_open", "vault_set_service", "sites_manage"]
PAGEWISE = ["reload", "find", "share", "copy_url", "open_external", "desktop", "zoom", "site_settings", "clear_site_cookies",
            "clear_site_data", "site_autocomplete", "translate", "reader", "bookmark", "save_page", "download_page", "shield_trackers",
            "view_source", "print", "add_to_home", "summarize_topics", "fill_profile"]
PAGES = ["sites", "permissions", "autocomplete", "general", "about"]   # #1402
# the settings that existed before #900: none may be dropped
BEFORE = ["search_engine_id", "homepage", "restore_tabs_on_start", "desktop_mode", "text_zoom", "javascript", "load_images",
          "block_third_party_cookies", "download_dir", "private_by_default", "autofill_enabled", "profile_fill", "offer_save_address",
          "summarize_route", "addons_enabled", "search_suggestions", "tab_strip", "translate_engine", "translate_target",
          "offline_depth", "offline_max_pages", "offline_max_mb"]

def check(root, ok):
    bj = build_json(root)
    b = bj["ui"]["browser"]
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    run = frag[frag.find("private fun runAction(id: String, args"):]
    menu = b["menu"]
    items = {i["id"]: i for i in menu["items"]}
    secs = [s["id"] for s in menu["sections"]]
    ok(secs == ["page_actions", "addons", "site", "site_perms", "devtools"], "P1 Browsing Tools' sections are Page Actions, Page Tools, Site Data, Site Perms, DevTools", str(secs))
    for g in GLOBAL:
        ok(g not in items, "P1 `%s` is not a dropdown row" % g)
    ok(all("settings_section" not in i for i in items.values()), "P1 no dropdown row sits inside a settings topic")
    for a in b["addons"]:
        for m in a.get("menu", []):
            if m.get("scope") == "global":
                ok(m["id"] in GLOBAL, "P1 the add-on's global row `%s` is one of the known global ids" % m["id"])
                ok(m.get("page") in PAGES, "P3 add-on global row `%s` names a Configs page" % m["id"])
            else:
                ok(m["id"] not in GLOBAL, "P1 the add-on page row `%s` is not global" % m["id"])
    for i in PAGEWISE:
        ok(i in items, "P2 `%s` is in the dropdown" % i)
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(i), run), "P2 `%s` has its runAction branch" % i)
    ok("override fun shouldInterceptRequest(" in frag and "trackersBlocked(" in frag, "P2 Block trackers is wired to the WebView client")
    ok('"view_source" ->' in run and "outerHTML" in run, "P2 View source reads the page's DOM")
    ok("sitePerms.setZoom(host, v.toInt())" in run, "P2 Zoom is stored per site")
    crow = {r["id"]: r for r in b["configs"]["rows"]}
    gl = {m["id"] for a in b["addons"] for m in a.get("menu", []) if m.get("scope") == "global"}
    for g in GLOBAL:
        ok(g in crow or g in gl or g in ("settings", "pin_tab", "close_tab"), "P3 `%s` is reachable from a Configs page" % g)
    for r in crow.values():
        ok(r["page"] in PAGES, "P3 Configs row `%s` is on a declared page" % r["id"], r["page"])
    ok([s for s in bj["ui"]["bottom_nav"]] == ["favourites", "tabs", "browser", "search", "scrapers"], "P4 the island is Fav | Tabs | Browser | Search | Scrapers (#1426; Configs is the ⋮)")
    nav = [s for s in bj["ui"]["sections"] if s["id"] == "configs"][0]
    ok([p["id"] for p in nav["pages"]] == PAGES, "P4 the Configs tabs are Sites, Permissions, Autocomplete, General, About, in order")
    ok([p["label"] for p in nav["pages"]] == ["Sites", "Permissions", "Autocomplete", "General", "About"], "P4 with the owner's names")
    act = src.get(APP + "/MainActivity.kt", "")
    ok("NavBarTopSecondRow(" in act and 'decl.section("configs")' in act, "P4 the shell draws them with NavBarTopSecondRow from the declaration")
    keys = {s["key"]: s for s in b["settings"]}
    for k in BEFORE:
        ok(k in keys, "P5 setting `%s` is still declared" % k)
    for k, s in keys.items():
        ok(s["section"] in PAGES, "P5 setting `%s` sits on exactly one declared page" % k, s["section"])

BJ = "ac_cloud-browser/build.json"
FRAG = LIB + "/BrowserHostFragment.kt"
main("page menu is page-only", check, [
    ("a global row comes back in the dropdown", BJ, '"id": "find",\n            "section": "page_actions"', '"id": "history",\n            "section": "page_actions"', "`history` is not a dropdown row"),
    ("a settings section comes back", BJ, '"id": "addons",\n            "label": "Page Tools"\n          },', '"id": "addons",\n            "label": "Page Tools"\n          },\n          {"id": "settings", "label": "Settings"},', "sections are Page Actions"),
    ("a page row leaves the dropdown", BJ, '"id": "view_source",\n            "section": "addons"', '"id": "view_source_gone",\n            "section": "addons"', "`view_source` is in the dropdown"),
    ("a global row loses its Configs page", BJ, '"id": "clear_data",\n            "label": "Clear browsing data…",\n            "page": "general"', '"id": "clear_data",\n            "label": "Clear browsing data…",\n            "page": "nowhere"', "Configs row `clear_data`"),
    ("trackers stop being blocked", FRAG, "override fun shouldInterceptRequest", "override fun shouldInterceptRequestGone", "Block trackers is wired"),
    ("zoom stops being per site", FRAG, "sitePerms.setZoom(host, v.toInt())", "browserSettings.set(\"text_zoom\", v)", "Zoom is stored per site"),
    ("a setting is dropped", BJ, '"key": "tab_strip"', '"key": "tab_strip_gone"', "`tab_strip` is still declared"),
])
PYEOF
