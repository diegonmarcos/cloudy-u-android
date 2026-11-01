#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #887 site data: offline copies, cookies, stored files            ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The rules (scope, limits, sizes, index, cookie names) are JVM-tested in BrowserSiteDataTest. This holds the wiring:
#   D1 page menu: Save this page / Save this site offline and Clear cookies for this site (per-site), each with
#      its runAction branch; Configs ▸ Data & storage: Offline copies and Storage & cookies (global);
#   D2 a site save reads its three limits from Configs, stays inside the start page's origin, and is a hidden
#      WebView + saveWebArchive (no second HTTP stack);
#   D3 offline copies are listed with size and date, open without the network (file access ONLY for a copy;
#      links inside a copy that the copy holds stay inside it), and delete one by one or all;
#   D4 storage lists every item with its size, each id has its clear, and Clear selected / Clear everything go
#      through a confirm that names what goes;
#   D5 "only this site's cookies" never calls removeAllCookies.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, re
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

NEEDED = ["cache", "dom", "indexeddb", "cookies", "form", "offline", "downloads"]

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    off = src.get(LIB + "/OfflineSites.kt", "")
    sto = src.get(LIB + "/BrowserStorage.kt", "")
    scr = src.get(LIB + "/BrowserDataScreens.kt", "")
    dat = src.get(LIB + "/BrowserSiteData.kt", "")
    b = build_json(root)["ui"]["browser"]
    mi = {i["id"]: i for i in b["menu"]["items"]}
    st = {s["key"]: s for s in b["settings"]}
    # D1
    for i, sec in (("save_page", "page_actions"), ("save_site", "page_actions"), ("clear_site_cookies", "site")):
        ok(i in mi and mi[i]["section"] == sec and "page" in mi[i].get("requires", []), "D1 `%s` is a page-menu row (per-site)" % i)
        ok('"%s" ->' % i in frag, "D1 `%s` has its runAction branch" % i)
    crow = {r["id"]: r for r in b["configs"]["rows"]}
    for i, pg in (("offline_manage", "downloads"), ("storage_manage", "privacy")):
        ok(i in crow and crow[i].get("page") == pg and i not in mi, "D1 `%s` is a row of Configs > %s, not of the page menu" % (i, pg))
        ok('"%s" ->' % i in frag, "D1 `%s` has its runAction branch" % i)
    for k in ("offline_depth", "offline_max_pages", "offline_max_mb"):
        ok(k in st and st[k]["section"] == "downloads" and st[k]["type"] == "int", "D2 limit `%s` is a Downloads setting" % k)
    # D2
    ok('CrawlLimits.of(browserSettings.int("offline_depth"), browserSettings.int("offline_max_pages"), browserSettings.int("offline_max_mb"))' in frag, "D2 a site save reads its limits from the settings")
    ok("SiteScope.sameOrigin(startUrl, finalUrl)" in off, "D2 a redirect off the site is not saved")
    ok("SiteCrawl(startUrl, limits)" in off and "crawl.next()" in off and "crawl.offer(" in off, "D2 the crawl is the tested queue")
    ok("saveWebArchive(" in off and "CloudWeb.create(ctx, WebProfile.BROWSER)" in off, "D2 pages are archived by a WebView")
    ok(not re.search(r"HttpURLConnection|OkHttp|URL\(", off), "D2 no second HTTP stack")
    ok("fun cancel()" in off and "finish(\"stopped by you\")" in off and "BrowserSavePanel(saveState, onStop = { job.cancel() }" in frag, "D2 a save can be stopped, keeping what was saved")
    ok("if (SiteScope.origin(url).isEmpty())" in frag, "D2 only http(s) pages are saved")
    # D3
    ok("SiteData.human(s.bytes)" in scr and "DateFormat" in scr, "D3 each copy shows its size and date")
    ok("onOpen" in scr and "onDelete" in scr and "onDeleteAll" in scr and "KitConfirmDialog(\"Delete every offline copy?\"" in scr, "D3 open, delete one, delete all (confirmed)")
    ok("fileAccess = offlineSites.isOffline(url) || mhtml.allowsFile(url)" in frag, "D3 file access only for a page of an offline copy")
    ok("offlineSites.resolve(target)" in frag and "offlineSites.isOffline(view?.url)" in frag, "D3 links inside a copy that the copy holds stay inside it")
    ok("if (offlineSites.isOffline(u)) return" in frag, "D3 an offline copy is not recorded as a visit")
    # D4
    ids = re.findall(r'Triple\("(\w+)"', sto)
    for i in NEEDED:
        ok(i in ids, "D4 storage lists `%s`" % i)
    clearfn = sto[sto.find("fun clear(ctx"):]
    for i in ids:
        ok('"%s"' % i in clearfn, "D4 storage item `%s` has its clear" % i)
    ok("fun breakdown(ctx: Context)" in sto and "SiteData.Item(id, label, bytes[id], detail)" in sto, "D4 every item carries a size")
    ok(scr.count("KitConfirmDialog(") >= 2 and "onConfirm = { confirm = null; onClear(ids)" in scr
       and "TextButton({ confirm = picked }" in scr and "TextButton({ confirm = SiteData.everything(items) }" in scr, "D4 clearing is behind a confirm")
    ok('Text("Clear selected")' in scr and 'Text("Clear everything")' in scr and "SiteData.everything(items)" in scr, "D4 clear selected and clear everything")
    ok("• ${it.label}" in scr, "D4 the confirm names what goes")
    # D5
    site = sto[sto.find("fun clearSiteCookies"):]
    ok("CookieScope.expiries(host" in site and "removeAllCookies" not in site, "D5 per-site cookie clear expires only that site's cookies")
    ok('"cookies" -> Unit' in clearfn and "removeAllCookies" in clearfn, "D5 clear all cookies is the all-sites item")

FRAG = LIB + "/BrowserHostFragment.kt"
main("site data", check, [
    ("a limit stops being read", FRAG, 'browserSettings.int("offline_max_mb")', "null", "reads its limits"),
    ("a save follows redirects off the site", LIB + "/OfflineSites.kt", "if (!SiteScope.sameOrigin(startUrl, finalUrl)) return step()", "", "off the site is not saved"),
    ("the crawl is hand-rolled", LIB + "/OfflineSites.kt", "SiteCrawl(startUrl, limits)", "ArrayDeque<Int>()", "tested queue"),
    ("a second HTTP stack appears", LIB + "/OfflineSites.kt", "class OfflineSites(context: Context) {", "class OfflineSites(context: Context) {\n    private fun net() = java.net.URL(\"https://x\").openConnection()", "no second HTTP stack"),
    ("file access is always on", FRAG, "fileAccess = offlineSites.isOffline(url) || mhtml.allowsFile(url)", "fileAccess = true", "only for a page of an offline copy"),
    ("copy links go to the network", FRAG, "val local = offlineSites.resolve(target)", "val local: String? = null", "stay inside it"),
    ("an offline copy is logged as a visit", FRAG, "if (offlineSites.isOffline(u)) return", "", "not recorded as a visit"),
    ("a storage item has no clear", LIB + "/BrowserStorage.kt", '"form" -> @Suppress("DEPRECATION") WebViewDatabase.getInstance(ctx).clearFormData()', "", "`form` has its clear"),
    ("IndexedDB drops out of the list", LIB + "/BrowserStorage.kt", 'Triple("indexeddb"', 'Triple("idb"', "lists `indexeddb`"),
    ("clearing skips the confirm", LIB + "/BrowserDataScreens.kt", "TextButton({ confirm = picked }", "TextButton({ onClear(picked) }", "behind a confirm"),
    ("the confirm stops naming items", LIB + "/BrowserDataScreens.kt", '"• ${it.label}', '"• ?', "names what goes"),
    ("clear everything is gone", LIB + "/BrowserDataScreens.kt", 'Text("Clear everything")', 'Text("Clear all")', "clear selected and clear everything"),
    ("per-site cookie clear wipes every site", LIB + "/BrowserStorage.kt", "for (secure in listOf(true, false)) CookieScope.expiries(host, names, secure)", "CookieManager.getInstance().removeAllCookies(null); for (secure in listOf(true, false)) CookieScope.expiries(host, names, secure)", "only that site's cookies"),
    ("delete all loses its confirm", LIB + "/BrowserDataScreens.kt", 'KitConfirmDialog("Delete every offline copy?"', 'KitConfirmDialog("Delete?"', "delete one, delete all"),
    ("the clear-site-cookies row goes", "ac_cloud-browser/build.json", '"id": "clear_site_cookies"', '"id": "clear_site_cookiez"', "clear_site_cookies"),
])
PYEOF
