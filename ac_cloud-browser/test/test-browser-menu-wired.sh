#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I3 every declared menu row DOES something                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The overflow menu is data (build.json::ui.browser.menu). A row declared with
# no branch in runAction is a button that does nothing; a `requires` fact that
# facts() never produces is a row that is disabled forever; an api:true row the
# route does not gate is a destructive action reachable from loopback. All of
# it is matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    menu = build_json(root)["ui"]["browser"].get("menu", {})
    src = kotlin(root, LIB, APP)
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    items = menu.get("items", [])
    ok(len(items) >= 15, "the menu declares its rows (%d)" % len(items))
    sections = [s["id"] for s in menu.get("sections", [])]
    run = frag[frag.find("private fun runAction(id: String, args"):]
    facts = frag[frag.find("private fun facts()"):frag.find("private fun showMenuSheet")]
    whys = menu.get("requires_why", {})
    for it in items:
        i = it["id"]
        ok(it.get("section") in sections, "row `%s` is in a declared section" % i)
        # a branch may name several ids: "a", "b" -> { ... }
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(i), run), "row `%s` has its branch in runAction" % i)
        for f in it.get("requires", []):
            ok('"%s" to' % f in facts, "fact `%s` (needed by `%s`) is produced by facts()" % (f, i))
            ok(f in whys, "fact `%s` says why in requires_why" % f)
        if it.get("checked"):
            ok('"%s" to' % it["checked"] in facts, "toggle `%s` shows a fact facts() produces" % i)
    # #900 a Configs row is a button too: it needs its branch, its facts and its words, and sits on a declared page.
    bj = build_json(root)["ui"]["browser"]
    pages = [s["id"] for s in bj.get("settings_sections", [])]
    addon_rows = [dict(m, page=m.get("page")) for a in bj.get("addons", []) for m in a.get("menu", []) if m.get("scope") == "global"]
    for r in bj.get("configs", {}).get("rows", []) + addon_rows:
        i = r["id"]
        ok(r.get("page") in pages, "Configs row `%s` is on a declared page" % i, str(r.get("page")))
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(i), run), "Configs row `%s` has its branch in runAction" % i)
        for f in r.get("requires", []):
            ok(f.startswith("addon:") or '"%s" to' % f in facts, "fact `%s` (needed by Configs row `%s`) is produced by facts()" % (f, i))
            ok(f.startswith("addon:") or f in whys, "fact `%s` says why in requires_why" % f)
    allsrc = "\n".join(src.values())
    ok("PopupMenu" not in allsrc, "no PopupMenu is left (the menus are the Compose sheet)")
    ok("Mozilla/5.0" not in allsrc, "no user-agent string is a Kotlin literal")
    ua = build_json(root)["ui"]["browser"].get("user_agents", {})
    ok("mobile" not in ua and ua.get("desktop", "").startswith("Mozilla/5.0"),
       "both user agents are declared in build.json (#1421 mobile = WebView's own, never pinned)")
    ok('config.userAgents["desktop"]' in frag and 'config.userAgents["mobile"]' in frag, "desktop mode reads the declared agents")
    ok("!item.api ->" in api, "menu/act refuses a row not declared api:true")
    for op in ("menu", "menu/act", "page/find", "page/text", "page/reader"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok("BrowserBus.page = pageHost" in frag, "the live page is reachable by the routes while on screen")
    ok("onBackPressedDispatcher.addCallback" in frag and "wv?.canGoBack() == true -> wv.goBack()" in frag,
       "system back walks the page history before leaving it")
    for js in ("reader", "page_text"):
        ok(os.path.exists(os.path.join(root, "ab_cloud-libs-shared/libs/browser/src/main/assets/browser/%s.js" % js)),
           "assets/browser/%s.js exists" % js)

BJ = "ac_cloud-browser/build.json"
FRAG = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt"
API = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt"
main("menu wiring", check, [
    ("a declared row loses its branch", FRAG, '"print" ->', '"print_gone" ->', "`print` has its branch"),
    ("a PopupMenu comes back", FRAG, "private fun closeOverlays() {", "private val pm = android.widget.PopupMenu::class\n    private fun closeOverlays() {", "PopupMenu"),
    ("a UA literal in Kotlin", FRAG, "private fun closeOverlays() {", 'private val ua = "Mozilla/5.0 (X11)"\n    private fun closeOverlays() {', "user-agent"),
    ("menu/act stops gating api:false rows", API, "!item.api ->", "false ->", "refuses a row"),
    ("a required fact is never produced", FRAG, '"can_go_forward" to', '"cannot_go_forward" to', "`can_go_forward`"),
    ("a row in an undeclared section", BJ, '"id": "site_settings",\n            "section": "site_perms"', '"id": "site_settings",\n            "section": "nowhere"', "declared section"),
    ("a Configs row loses its branch", FRAG, '"sites_manage" ->', '"sites_manage_gone" ->', "`sites_manage` has its branch"),
])
PYEOF
