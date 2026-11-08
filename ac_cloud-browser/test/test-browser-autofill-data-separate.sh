#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Clearing cookies / site data never wipes autofill data, and back ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# a0_docs/eng-specs/autofill-3-tier.md §3.1. Three categories, kept apart:
#   1 cookies (per site, all)   2 site data (WebView storage + the browser's own page data)
#   3 autofill data (profiles, addresses, site rules, snippets) — the Cloud Account SOT, read over its
#     provider and held in memory only; the one browser-local leftover is the legacy imported profile.
# The pure mapping (every clear id is 1 or 2, never 3; per-site origins are that site's only) is
# JVM-tested in DomAutofillTest. This holds the WIRING:
#   S1 no cookie / storage / clear-browsing-data branch touches autofill data (SOT client, the legacy
#      store, the in-memory copy), and the autofill forget touches no cookie and no WebView storage;
#   S2 autofill data is its own section with its own confirm, points to Cloud Account, and says what
#      Android's "Clear storage" keeps (the SOT) and loses (the browser-local leftover);
#   S3 the per-site clear has a box per category, deletes only its own origins, and is a page-menu row;
#   S4 the SOT is never written to WebView storage (no localStorage/IndexedDB/cookie writes in the engine)
#      and incognito never writes it.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def fn(src, sig):
    i = src.find(sig)
    if i < 0: return ""
    p = src.find("(", i); d = 0
    for k in range(p, len(src)):
        d += {"(": 1, ")": -1}.get(src[k], 0)
        if d == 0: break
    j = src.find("{", k); d = 0
    for k in range(j, len(src)):
        d += {"{": 1, "}": -1}.get(src[k], 0)
        if d == 0: return src[i:k + 1]
    return src[i:]

AUTOFILL = re.compile(r"BrowserProfileStore|AutofillSot|DomAutofill|domAutofill|forgetCache|BrowserAutofillData")
WEBDATA = re.compile(r"CookieManager|WebStorage|clearCache|WebViewDatabase|deleteOrigin")

def check(root, ok):
    src = kotlin(root, LIB, APP)
    sto = src.get(LIB + "/BrowserStorage.kt", "")
    priv = src.get(LIB + "/BrowserPrivacy.kt", "")
    data = src.get(LIB + "/BrowserAutofillData.kt", "")
    scr = src.get(LIB + "/BrowserDataScreens.kt", "")
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    engine = open(os.path.join(root, "ab_cloud-libs-shared/libs/browser/src/main/assets/browser/autofill_engine.js"), encoding="utf-8").read()
    # S1
    for name, body in (("BrowserStorage.clear", fn(sto, "fun clear(ctx: Context, ids: Set<String>")),
                       ("BrowserStorage.clearSiteData", fn(sto, "fun clearSiteData(")),
                       ("BrowserStorage.clearSiteCookies", fn(sto, "fun clearSiteCookies(")),
                       ("BrowserClearData.clear", fn(priv, "fun clear(ctx: Context, boxes: Set<String>"))):
        ok(body, "S1 %s exists" % name)
        ok(not AUTOFILL.search(body), "S1 %s touches no autofill data" % name)
    forget = fn(data, "fun clearLocal(")
    ok("BrowserProfileStore(ctx).clear()" in forget and "forgetCache()" in forget, "S1 the autofill forget clears the browser-local leftovers")
    ok(not WEBDATA.search(forget) and "AutofillSotClient" not in forget, "S1 the autofill forget touches no cookie, no WebView storage, no Cloud Account row")
    # S2
    sec = fn(scr, "fun BrowserAutofillDataSection(")
    ok('"Autofill data (this site / all sites)"' in sec, "S2 autofill data has its own section")
    ok("KitConfirmDialog(" in sec and "onConfirm = { confirm = false; onForget() }" in sec, "S2 it has its own confirm")
    ok("Edit in Cloud Account" in sec and "openCloudAccount()" in frag, "S2 it points to Cloud Account for editing")
    ok("Clear storage" in sec and "kept when this browser's storage is cleared" in sec, "S2 it says what Android's Clear storage keeps and loses")
    scrn = fn(scr, "fun BrowserStorageScreen(")
    ok("Autofill data (profiles, site rules, snippets in Cloud Account) is not touched." in scrn, "S2 the storage clear's confirm says autofill data is not touched")
    ok("BrowserAutofillDataSection(state.autofill" in scrn and "BrowserAutofillData.summary(" in frag, "S2 the section is measured apart and drawn under the storage items")
    # S3
    site = fn(sto, "fun clearSiteData(")
    ok("BrowserClearCategories.siteOrigins(host)" in site and "deleteOrigin(it)" in site and "deleteAllData" not in site, "S3 a per-site clear deletes only that site's origins")
    ok("BrowserClearCategories.SITE_COOKIES in boxes" in site and "BrowserClearCategories.SITE_STORAGE in boxes" in site, "S3 a box per category")
    mi = {i["id"]: i for i in build_json(root)["ui"]["browser"]["menu"]["items"]}
    ok(mi.get("clear_site_data", {}).get("section") == "site_data" and "page" in mi["clear_site_data"].get("requires", []) and '"clear_site_data" ->' in frag,
       "S3 Clear site data is a page-menu row with its branch")
    # S4
    code = re.sub(r"(?m)^\s*//.*$", "", engine)
    ok(not re.search(r"localStorage|sessionStorage|indexedDB|document\.cookie|caches\.", code), "S4 the engine keeps nothing in WebView storage")
    dom = src.get(LIB + "/DomAutofill.kt", "")
    ok("if (!yes || incognito) return" in fn(dom, "fun confirmSave(") and "incognito" in fn(dom, "fun submitted("), "S4 incognito never writes autofill data")

STO = LIB + "/BrowserStorage.kt"
DATA = LIB + "/BrowserAutofillData.kt"
SCR = LIB + "/BrowserDataScreens.kt"
main("autofill data separate", check, [
    ("clear storage also wipes autofill", STO, '            "tabstate" -> BrowserWebState.clearAll(ctx)', '            "tabstate" -> { BrowserWebState.clearAll(ctx); BrowserProfileStore(ctx).clear() }', "S1"),
    ("the autofill forget clears cookies", DATA, "        cache?.forgetCache()", "        cache?.forgetCache(); android.webkit.CookieManager.getInstance().removeAllCookies(null)", "S1"),
    ("no own confirm", SCR, "onConfirm = { confirm = false; onForget() }", "onConfirm = { confirm = false }", "S2"),
    ("per-site clear wipes every site", STO, "            origins.forEach { WebStorage.getInstance().deleteOrigin(it) }", "            WebStorage.getInstance().deleteAllData()", "S3"),
    ("the engine caches in localStorage", "ab_cloud-libs-shared/libs/browser/src/main/assets/browser/autofill_engine.js", "  function scan() {", "  function scan() { try { localStorage.setItem('cf', '1'); } catch (x) {}", "S4"),
])
PYEOF
