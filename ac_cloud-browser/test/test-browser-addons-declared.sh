#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I7 every declared add-on is wired, and says what it touches ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# An add-on row with no runAction branch is a dead button; a permission word
# outside the vocabulary is shown to nobody; a remote call that can answer an
# empty list on failure hides the failure. Matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    b = build_json(root)["ui"]["browser"]
    src = kotlin(root, LIB, APP)
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    reg = src.get(os.path.join(LIB, "BrowserAddons.kt"), "")
    remote = src.get(os.path.join(LIB, "ScrapeRemote.kt"), "")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    run = frag[frag.find("private fun runAction(id: String, args"):]
    vocab = re.findall(r'"([a-z_]+)" to "', reg[reg.find("val PERMISSIONS"):reg.find("val EMPTY")])
    ok(len(vocab) >= 5, "the permission vocabulary is declared in BrowserAddons")
    ok(any(s["id"] == "addons" for s in b["menu"]["sections"]), "the menu declares the Add-ons section")
    ok(any(i["id"] == "addons_manage" and i["page"] == "general" and i.get("group") == "advanced" for i in b["configs"]["rows"]), "Manage add-ons is a row of Configs > General > Advanced")
    ids = [a["id"] for a in b.get("addons", [])]
    ok(len(ids) == len(set(ids)) and ids, "add-on ids are declared and unique")
    for a in b.get("addons", []):
        for p in a.get("permissions", []):
            ok(p in vocab, "add-on `%s` permission `%s` is in the vocabulary" % (a["id"], p))
        ok(a.get("menu"), "add-on `%s` contributes at least one menu row" % a["id"])
        for m in a.get("menu", []):
            ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(m["id"]), run), "add-on row `%s` has its runAction branch" % m["id"])
    ok('"addon:$id"' in reg or '"addon:${a.id}"' in reg, "add-on rows require their addon:<id> fact")
    ok("config.addons.facts(" in frag and "facts() + " in frag, "the menu resolves against the add-on facts")
    ok('put("remote", "unconfigured")' in remote and '"error", "scrappers-api answered' in remote and "scrappers-api unreachable" in remote,
       "the remote scraper answers every failure in words (unconfigured, HTTP error, unreachable)")
    ok("http://" not in "".join(t for p, t in src.items() if p.endswith("ScrapeRemote.kt")), "the remote endpoint is data, not a Kotlin literal")
    ok("Thread {" in frag and "ScrapeRemote.crawl(" in frag, "the remote call runs off the main thread")
    for op in ("addons", "addons/set", "scraper/run", "scraper/last", "scraper/remote"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok('!config.addons.enabled("scraper"' in api, "scraper/run refuses while the add-on is off")

BJ = "ac_cloud-browser/build.json"
LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
main("add-ons declared", check, [
    ("a permission outside the vocabulary", BJ, '"page_read",', '"read_everything",', "read_everything"),
    ("the scraper row loses its branch", LIBP + "BrowserHostFragment.kt", '"scraper" -> { showScraper()', '"scraper_x" -> { showScraper()', "`scraper` has its runAction"),
    ("a remote failure becomes silent", LIBP + "ScrapeRemote.kt", '.put("error", "scrappers-api answered', '.put("rows", "', "answers every failure"),
    ("scraper/run ignores the switch", "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt", '!config.addons.enabled("scraper"', 'false && !config.addons.enabled("scrapr"', "refuses while the add-on is off"),
])
PYEOF
