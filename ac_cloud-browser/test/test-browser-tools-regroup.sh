#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1856 Tools sheet regroup + Cloudy subpages: field parity (Rule 16) ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# REORGANISE = new display, no data field lost. The sheet's old menu is frozen in tools-parity-baseline-1856.json (every row, its old
# section and label, plus the add-on rows that joined Page Tools). This tester proves:
#   F1 every pre-change row is still declared, in its NEW place (the MOVED table below), with its old label (RELABELLED lists the one row
#      whose words changed) and its old kind; the ONLY rows gone are ALLOWED_REMOVALS = pin_site and download_site, which the owner ordered
#      deleted (an authorised removal, not a regroup). Any other loss fails; so does either of the two coming back unannounced;
#   F2 every pre-change add-on row (scraper, ai_chat, ai_summarize, vault_fill) is still a menu row;
#   F3 the sheet is Nav | Scrapper | Reading | Site Data | Perms | Dev Tools, in that order (section ids are stable);
#   F4 Nav has Zoom; Scrapper has all of Save & Export plus the headless-browser and scrapper-api tools, each a declared fleet tool with a
#      body that carries the page URL ({url}), mesh names first and no public TLD host for a WG-only service; Reading has no View source;
#      Dev Tools has it; Site Data has Open Local Folder wired to the app; Perms holds the permission list and the three blockers as toggles;
#   F5 Cloudy's subpages are WebApps | GH Pages | Local | C3, GH Pages is fed at runtime by diegonmarcos.github.io's own front-topology.json
#      (no baked list), Local is File Explorers then Pages, WebApps rows come from the Cloud SuperApp > Cloud categories.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

ALLOWED_REMOVALS = {"pin_site", "download_site"}          # the owner's explicit deletion, #1856
RELABELLED = {"site_settings": "Every site permission…"}     # sits under the tab named Perms now; the row still opens the same screen
MOVED = {   # id -> new section id
    "zoom": "actions",
    "print": "scrapper", "convert_pdf": "scrapper", "download_page": "scrapper", "save_page": "scrapper", "save_site": "scrapper",
    "view_source": "dev_tools",
    "shield_trackers": "site_perms", "shield_images": "site_perms", "shield_js": "site_perms",
}
FLEET = ["fleet_readable", "fleet_text", "fleet_links", "fleet_html", "fleet_screenshot", "fleet_pdf", "fleet_ocr",
         "fleet_crawl", "fleet_youtube_video", "fleet_youtube_audio"]

def check(root, ok):
    base = json.load(open(os.path.join(root, "ac_cloud-browser/test/tools-parity-baseline-1856.json"), encoding="utf-8"))
    bj = build_json(root); b = bj["ui"]["browser"]; m = b["menu"]
    items = {i["id"]: i for i in m["items"]}
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    run = frag[frag.find("private fun runAction(id: String, args"):]
    # F1 field parity against the frozen pre-change menu
    gone = [r["id"] for r in base["rows"] if r["id"] not in items]
    ok(set(gone) == ALLOWED_REMOVALS, "F1 the only rows removed are pin_site and download_site (the owner's order)", "removed: %s" % sorted(gone))
    for r in base["rows"]:
        i = r["id"]
        if i in ALLOWED_REMOVALS:
            continue
        it = items.get(i)
        ok(it is not None, "F1 `%s` is reachable" % i)
        if it is None:
            continue
        want = MOVED.get(i, r["section"])
        ok(it["section"] == want, "F1 `%s` is in `%s`" % (i, want), "is in %s" % it["section"])
        ok(it["label"] == RELABELLED.get(i, r["label"]), "F1 `%s` keeps its label" % i, "%r" % it["label"])
        ok(it.get("kind", "action") == r["kind"], "F1 `%s` keeps its kind" % i)
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(i), run) is not None, "F1 `%s` still runs" % i)
    for i in ALLOWED_REMOVALS:   # the engine stays, only the rows left
        ok(i not in items, "F1 `%s` is out of the menu as ordered" % i)
        ok('"pin_site", "download_site" ->' in frag, "F1 the engine behind `%s` stays" % i)
    # F2 add-on rows
    merged = set(items)
    for a in base["addon_rows"]:
        ok(a["id"] in merged or any(m2.get("id") == a["id"] for ad in b["addons"] for m2 in ad.get("menu", [])), "F2 add-on row `%s` is still declared" % a["id"])
    # F3 sections
    ok([s["label"] for s in m["sections"]] == ["Nav", "Scrapper", "Reading", "Site Data", "Perms", "Dev Tools"], "F3 Nav | Scrapper | Reading | Site Data | Perms | Dev Tools")
    ok([s["id"] for s in m["sections"]] == ["actions", "scrapper", "page_tools", "site_data", "site_perms", "dev_tools"], "F3 section ids are stable")
    # F4 contents
    ok(items["zoom"]["section"] == "actions" and "Zoom" in items["zoom"]["label"], "F4 Zoom (this site) is in Nav")
    scr = next(a for a in b["addons"] if a["id"] == "scraper")
    fleet = scr.get("fleet", {}); tools = {t["id"]: t for t in fleet.get("tools", [])}
    ok(set(tools) == set(FLEET), "F4 the Scrapper tab's fleet tools are the headless-browser and scrapper-api tools", str(sorted(tools)))
    for t in FLEET:
        ok(items.get(t, {}).get("section") == "scrapper" and "page" in items.get(t, {}).get("requires", []), "F4 `%s` is a Scrapper row on the current page" % t)
        ok("{url}" in json.dumps(tools.get(t, {}).get("body", {})), "F4 `%s` acts on the page URL" % t)
        ok(tools.get(t, {}).get("service") in fleet.get("services", {}), "F4 `%s` names a declared service" % t)
    ok(any(i["section"] == "scrapper" and i.get("category") == "Save & Export" for i in m["items"]), "F4 Save & Export lives in Scrapper")
    for sid, svc in fleet.get("services", {}).items():
        ok(svc["bases"] and all(x.startswith("http://") and ".mesh.diegonmarcos.com" in x for x in svc["bases"][:1]), "F4 service `%s` is mesh first" % sid)
        ok(not any("bearer" in json.dumps(x).lower() and "token" in x.lower() and "=" in x for x in svc["bases"]), "F4 service `%s` holds no secret value" % sid)
    ok(tools.get("fleet_readable", {}).get("service") == "headless" and fleet["services"]["headless"].get("bearer_key"), "F4 headless is bearer-gated by a store KEY name, never a value")
    ok("in fleet.tools.keys ->" in run and "FleetScrapper.call(" in frag and "FleetScrapper.download(" in frag, "F4 one runAction branch runs the declared tools off the main thread")
    ok(items["view_source"]["section"] == "dev_tools" and items["view_source"].get("category") is None, "F4 View source moved to Dev Tools")
    ok(items.get("open_local_folder", {}).get("section") == "site_data" and '"open_local_folder" ->' in run and "onOpenLocalFolder" in frag, "F4 Site Data has Open Local Folder, wired to the app")
    act = src.get(APP + "/MainActivity.kt", "")
    ok("f.onOpenLocalFolder = { openLocalFolder() }" in act and "SitesRoute.localFolder" in act, "F4 the app opens Domains > Files Cached for it")
    for i in ("shield_trackers", "shield_images", "shield_js"):
        ok(items[i]["section"] == "site_perms" and items[i]["kind"] == "toggle", "F4 `%s` is a Perms toggle" % i)
    ok(items["site_settings"]["section"] == "site_perms", "F4 Perms lists every site permission (the permission screen row)")
    ok(not any(i["section"] == "page_tools" and i["id"] == "view_source" for i in m["items"]), "F4 Reading has no View source")
    # F5 Cloudy
    secs = {s["id"]: s for s in bj["ui"]["sections"]}
    ok([(p["id"], p["label"]) for p in secs["cloudy"]["pages"]] == [("webapps", "WebApps"), ("gh_pages", "GH Pages"), ("local", "Local"), ("c3_urls", "C3")], "F5 Cloudy is WebApps | GH Pages | Local | C3")
    gs = bj["ui"]["cloudy"].get("gh_site", {})
    ok(gs.get("repo") == "diegonmarcos.github.io" and gs.get("topology_url", "").startswith("https://raw.githubusercontent.com/diegonmarcos/diegonmarcos.github.io/") and gs["topology_url"].endswith("/front-topology.json"),
       "F5 GH Pages' full list is that repo's own front-topology.json, read at runtime")
    ok(not os.path.exists(os.path.join(root, "ac_cloud-browser/app/src/main/assets/front-topology.json")), "F5 no baked copy of the list (runtime-data guard)")
    sites = src.get(APP + "/sites/SitesUi.kt", ""); weby = src.get(APP + "/sites/Weby.kt", ""); cl = src.get(APP + "/sites/Cloudy.kt", "")
    ok('"gh_pages" -> CloudyGhPagesPage(' in sites and '"local" -> CloudyLocalPage(' in sites and '"c3_urls" -> C3UrlsPage(' in sites, "F5 each subpage has its page")
    ok("cache.refreshTopology(" in sites and "fun sitePages(" in cl, "F5 GH Pages reads the topology with a cached fallback")
    ok(re.search(r'fun local\([^)]*\)[^\n]*\n\s*listOf\(WebyFolder\(FILE_EXPLORERS[^\n]*WebyFolder\(LOCAL_PAGES', weby) is not None, "F5 Local is File Explorers, then Pages")
    ok('const val FILE_EXPLORERS = "File Explorers"' in weby and 'const val LOCAL_PAGES = "Pages"' in weby, "F5 the two Local groups are named")
    ok("fun webApps(" in weby and "WebyFolder(c.optString(\"label\")" in weby, "F5 WebApps is one section per Cloud Store category (the SuperApp's Cloud rows)")
    for fn in ("webserverExplorers", "webserverPages", "declaredWebserver", "storeEntries", "seedPages", "merge"):
        ok(re.search(r"fun %s\(" % fn, cl) is not None, "F5 Cloudy.%s is still there" % fn)
    ok('"cloudy:ws:open"' in sites, "F5 the Cloud-Webserver open row is kept (on Local)")

BJ = "ac_cloud-browser/build.json"
FRAG = LIB + "/BrowserHostFragment.kt"
SITES = APP + "/sites"
main("tools sheet regroup parity", check, [
    ("a row is lost in the regroup (Hard refresh)", BJ, '"id": "hard_refresh",', '"id": "hard_refresh_gone",', "`hard_refresh` is reachable"),
    ("Convert to PDF is lost", BJ, '"id": "convert_pdf",', '"id": "convert_pdf_gone",', "`convert_pdf` is reachable"),
    ("a removed row comes back", BJ, '"id": "open_local_folder",', '"id": "pin_site",', "the only rows removed"),
    ("Zoom stays in Reading", BJ, '"id": "zoom",\n            "section": "actions",', '"id": "zoom",\n            "section": "page_tools",', "`zoom` is in `actions`"),
    ("View source stays in Reading", BJ, '"id": "view_source",\n            "section": "dev_tools",', '"id": "view_source",\n            "section": "page_tools",', "`view_source` is in `dev_tools`"),
    ("Block JS goes back to Dev Tools", BJ, '"id": "shield_js",\n            "section": "site_perms",', '"id": "shield_js",\n            "section": "dev_tools",', "`shield_js` is in `site_perms`"),
    ("Save this site offline is not in Scrapper", BJ, '"id": "save_site",\n            "section": "scrapper",', '"id": "save_site",\n            "section": "page_tools",', "`save_site` is in `scrapper`"),
    ("a row changes its words", BJ, '"label": "Light refresh (cache)"', '"label": "Light refresh"', "`light_refresh` keeps its label"),
    ("a blocker stops being a toggle", BJ, '"id": "shield_images",\n            "section": "site_perms",\n            "label": "Block images here",\n            "kind": "toggle",', '"id": "shield_images",\n            "section": "site_perms",\n            "label": "Block images here",\n            "kind": "action",', "keeps its kind"),
    ("Actions is not renamed Nav", BJ, '"id": "actions",\n            "label": "Nav"', '"id": "actions",\n            "label": "Actions"', "F3 Nav | Scrapper"),
    ("Page Tools is not renamed Reading", BJ, '"id": "page_tools",\n            "label": "Reading"', '"id": "page_tools",\n            "label": "Page Tools"', "F3 Nav | Scrapper"),
    ("Open Local Folder loses its branch", FRAG, '"open_local_folder" ->', '"open_local_folder_gone" ->', "Open Local Folder"),
    ("a fleet tool loses its URL", BJ, '{"id": "fleet_text", "service": "headless", "path": "/run", "show": 1, "body": {"steps": [{"cmd": "open", "url": "{url}"}', '{"id": "fleet_text", "service": "headless", "path": "/run", "show": 1, "body": {"steps": [{"cmd": "open", "url": "https://x/"}', "`fleet_text` acts on the page URL"),
    ("a fleet tool has no row", BJ, '"id": "fleet_ocr",\n            "section": "scrapper",', '"id": "fleet_ocr_gone",\n            "section": "scrapper",', "`fleet_ocr` is a Scrapper row"),
    ("the headless service goes public first", BJ, '"http://cloudy-browser-headless.mesh.diegonmarcos.com:3118"', '"https://mcp.diegonmarcos.com/cloudy-browser-headless"', "mesh first"),
    ("the tools stop running", FRAG, "in fleet.tools.keys ->", "in emptySet<String>() ->", "one runAction branch"),
    ("Cloudy loses GH Pages", BJ, '{"id": "gh_pages", "label": "GH Pages", "icon": "public"},', '', "F5 Cloudy is WebApps"),
    ("Cloudy loses Local", BJ, '{"id": "local", "label": "Local", "icon": "folder_open"},', '', "F5 Cloudy is WebApps"),
    ("GH Pages goes back to a baked list", BJ, '"topology_url": "https://raw.githubusercontent.com/diegonmarcos/diegonmarcos.github.io/main/front-topology.json"', '"topology_url": ""', "front-topology.json, read at runtime"),
    ("Local swaps its groups", SITES + "/Weby.kt", "listOf(WebyFolder(FILE_EXPLORERS, tiles(explorers)), WebyFolder(LOCAL_PAGES, tiles(pages)))", "listOf(WebyFolder(LOCAL_PAGES, tiles(pages)), WebyFolder(FILE_EXPLORERS, tiles(explorers)))", "Local is File Explorers, then Pages"),
    ("the GH page is not routed", SITES + "/SitesUi.kt", '"gh_pages" -> CloudyGhPagesPage(', '"gh_pages" -> CloudyWebAppsPage(', "each subpage has its page"),
    ("the topology is not read", SITES + "/SitesUi.kt", "cache.refreshTopology(", "cache.refreshTopologyX(", "GH Pages reads the topology"),
])
PYEOF
