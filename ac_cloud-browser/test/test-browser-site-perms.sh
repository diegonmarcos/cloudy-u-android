#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1401 ⋮ = Configs; Browsing Tools on the tab row; five sections;  ║
# ║       every site permission, per site, with a global default      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   T1 the app bar's ⋮ opens the app's Configs (MainActivity onConfigs), never the page menu;
#   T2 the tab row's icon (browser:tabrow:tools) opens the Browsing Tools sheet, and the page bar has no ⋮ of its own;
#   T3 the sheet's sections are, in order: Actions, Page Tools, Site Data, Site Perms, Dev Tools; Site Perms is one row, "Perms";
#   T4 every action the sheet held before #1401 is still a row of it (none lost);
#   T5 the Perms list: the most-used first, in the owner's order, then all the rest; the list is complete;
#      each permission is either enforced (webview + enforced_by) or declared not supported in WebView with a reason;
#   T6 per-site state persists (the browser_site_permissions prefs, a global default under host `*`) and is enforced:
#      each WebView-enforced id is read where WebView decides it;
#   T7 Configs > Site settings > Sites Permissions (defaults, then the sites with overrides) reuses the Perms component.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

BEFORE = ["back", "forward", "reload", "bookmark", "download_page", "translate", "desktop", "hard_refresh", "light_refresh",
          "find", "share", "copy_url", "open_external", "zoom", "summarize_topics", "reader", "print", "add_to_home", "save_page",
          "save_site", "fill_profile", "site_settings", "shield_trackers", "shield_js", "shield_images", "clear_site_cookies",
          "clear_site_data", "site_autocomplete", "view_source", "dev_console", "dev_network", "dev_elements", "dev_storage",
          "dev_remote_debug"]
TOP = ["geolocation", "camera", "microphone", "notifications", "popups", "clipboard", "autoplay", "javascript",
       "third_party_cookies", "downloads", "site_storage", "protected_media"]
REST = ["sensors", "midi", "bluetooth", "usb", "serial", "hid", "nfc", "background_sync", "idle_detection", "wake_lock",
        "window_management", "local_fonts", "file_system", "payment_handler", "vr_ar", "display_capture", "fullscreen", "pip",
        "ads", "intrusive_ads", "sound", "images", "insecure_content", "federated_identity", "storage_access"]
# where WebView decides each enforced id: a permState read, or the webkit resource onPermissionRequest matches
ENFORCED_BY_STATE = ["popups", "autoplay", "third_party_cookies", "site_storage", "insecure_content", "fullscreen"]

def check(root, ok):
    b = build_json(root)["ui"]["browser"]
    src = kotlin(root, LIB, APP)
    act = src.get(APP + "/MainActivity.kt", "")
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    strip = src.get(LIB + "/BrowserTabStrip.kt", "")
    priv = src.get(LIB + "/BrowserPrivacy.kt", "")
    screen = src.get(LIB + "/BrowserPermsScreen.kt", "")
    ok('onConfigs = { openSection("configs") }' in act, "T1 the app bar's ⋮ opens Configs")
    ok("onConfigs = { hostFragment?.openMenu() }" not in act, "T1 the app bar's ⋮ no longer opens the page menu")
    ok('TAB_ROW_TOOLS_TAG = "browser:tabrow:tools"' in strip and "if (onTools != null) BrowserTabRowToolsIcon(onTools)" in strip,
       "T2 the tab strip ends with the Browsing Tools icon")
    ok("onTools = { showMenuSheet() }" in frag and "BrowserTabRowToolsIcon { showMenuSheet() }" in frag, "T2 the icon opens the Browsing Tools sheet (strip on and off)")
    ok('BarButton("⋮")' not in frag, "T2 the page bar has no ⋮ of its own")
    m = b["menu"]
    secs = [(s["id"], s["label"]) for s in m["sections"]]
    ok([l for _, l in secs] == ["Actions", "Page Tools", "Site Data", "Site Perms", "Dev Tools"], "T3 the five sections, in order", str(secs))
    perms_rows = [i for i in m["items"] if i["section"] == "site_perms"]
    ok([(i["id"], i["label"]) for i in perms_rows] == [("site_settings", "Perms")], "T3 Site Perms is the single row Perms")
    ok('"site_settings" -> { showSiteSettings(' in frag, "T3 Perms opens the Perms page")
    ids = [i["id"] for i in m["items"]]
    for a in BEFORE:
        ok(a in ids, "T4 `%s` is still in Browsing Tools" % a)
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(a), frag[frag.find("private fun runAction(id: String, args"):]), "T4 `%s` still runs" % a)
    p = b["site_permissions"]
    pid = [x["id"] for x in p]
    ok([x["id"] for x in p if x.get("top")] == TOP, "T5 the most-used, in order", str([x["id"] for x in p if x.get("top")]))
    ok(pid[:len(TOP)] == TOP, "T5 the most-used come first")
    ok(sorted(pid) == sorted(TOP + REST + ["trackers"]), "T5 the list is complete", str(set(pid) ^ set(TOP + REST + ["trackers"])))
    for x in p:
        if x.get("webview"):
            ok(x.get("enforced_by", "").strip() != "", "T5 `%s` names its WebView hook" % x["id"])
        else:
            ok(x.get("why", "").startswith("not supported in WebView"), "T5 `%s` says why WebView cannot" % x["id"])
    ok("fun ordered(perms: List<BrowserSitePerm>): List<BrowserSitePerm> = perms.filter { it.top } + perms.filterNot { it.top }" in priv, "T5 the page lists the most-used first")
    ok("BrowserSitePolicy.ordered(perms)" in screen, "T5 the Perms component draws that order")
    ok('getSharedPreferences("browser_site_permissions"' in priv and "?: rules[key(ANY_HOST, perm)] ?: default" in priv, "T6 per-site rules persist; the global default sits under `*`")
    for e in ENFORCED_BY_STATE:
        ok('permState(host, "%s")' % e in frag or 'hostOf(view?.url), "%s")' % e in frag or 'hostOf(webView?.url), "%s")' % e in frag, "T6 `%s` is enforced where WebView decides it" % e)
    ok('config.sitePerms.firstOrNull { it.id == "downloads" }' in frag, "T6 downloads are gated per site")
    ok("request.resources.filter { r -> config.sitePerms.any { r in it.webkit } }" in frag, "T6 onPermissionRequest grants only declared, decided resources")
    for x in p:
        if x.get("webview") and x.get("webkit"):
            ok(all(w.startswith("android.webkit.resource.") or w == "geolocation" for w in x["webkit"]), "T6 `%s` maps to a WebView resource" % x["id"])
    ok("applySitePerms(wv, url)" in frag, "T6 the site's WebSettings are applied on every page")
    ok("BrowserPermsScreen(\"Sites Permissions\", null" in frag and "BrowserPermsScreen(\"Perms\", host" in frag, "T7 both pages reuse the one Perms component")
    ok('"site_perms_manage" -> { showSitePermsDefaults()' in frag and any(r["id"] == "site_perms_manage" for r in b["configs"]["rows"]), "T7 Configs holds Sites Permissions")

BJ = "ac_cloud-browser/build.json"
ACT = APP + "/MainActivity.kt"
FRAG = LIB + "/BrowserHostFragment.kt"
STRIP = LIB + "/BrowserTabStrip.kt"
PRIV = LIB + "/BrowserPrivacy.kt"
main("site perms + browsing tools", check, [
    ("⋮ opens the page menu again", ACT, 'onConfigs = { openSection("configs") }', "onConfigs = { hostFragment?.openMenu() }", "T1"),
    ("the tab row icon opens nothing", FRAG, "onTools = { showMenuSheet() }", "onTools = { }", "T2 the icon opens"),
    ("the strip drops the icon", STRIP, "if (onTools != null) BrowserTabRowToolsIcon(onTools)", "", "T2 the tab strip ends"),
    ("two sections swap", BJ, '"label": "Site Data"', '"label": "Site Perms"', "T3 the five sections"),
    ("an action is lost", BJ, '"id": "print",', '"id": "print_gone",', "T4 `print`"),
    ("a top perm moves down", BJ, '"top": true,\n          "webview": true,\n          "enforced_by": "WebChromeClient.onPermissionRequest (VIDEO_CAPTURE)"', '"webview": true,\n          "enforced_by": "WebChromeClient.onPermissionRequest (VIDEO_CAPTURE)"', "T5 the most-used"),
    ("a perm is dropped", BJ, '"id": "nfc"', '"id": "nfc_gone"', "T5 the list is complete"),
    ("an unsupported perm gives no reason", BJ, '"why": "not supported in WebView: WebView does not implement WebXR."', '"why": ""', "T5 `vr_ar`"),
    ("the list is not ordered", PRIV, "perms.filter { it.top } + perms.filterNot { it.top }", "perms", "T5 the page lists"),
    ("the global default is ignored", PRIV, "?: rules[key(ANY_HOST, perm)] ?: default", "?: default", "T6 per-site rules persist"),
    ("autoplay stops being enforced", FRAG, 'permState(host, "autoplay")', 'permState(host, "nothing")', "T6 `autoplay`"),
    ("popups stop being enforced", FRAG, 'hostOf(view?.url), "popups")', 'hostOf(view?.url), "nothing")', "T6 `popups`"),
    ("Configs loses Sites Permissions", FRAG, '"site_perms_manage" -> { showSitePermsDefaults()', '"site_perms_gone" -> { showSitePermsDefaults()', "T7 Configs holds"),
])
PYEOF
