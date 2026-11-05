#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I2 every declared setting is READ, and stored in one place  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The catalogue (build.json::ui.browser.settings) is the one declaration of
# what a user can set. A key declared and never read is a toggle that does
# nothing; a second getSharedPreferences("browser_settings") is a second
# writer the catalogue's validation never sees. Plus: the routes that serve
# it are registered, the generated doc is current, and the store is declared
# in the fleet manifest (or FleetConfig cannot carry it to a new phone).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, HEADLESS, MANIFEST, kotlin, build_json, render_catalogue, main

def check(root, ok):
    bj = build_json(root)
    cat = bj["ui"]["browser"].get("settings", [])
    ok(len(cat) > 0, "the catalogue declares settings")
    src = kotlin(root, LIB, APP, HEADLESS)
    allsrc = "\n".join(src.values())
    for s in cat:
        k = s["key"]
        # A read is the key as a string literal in code other than the catalogue parser.
        readers = [p for p, t in src.items() if '"%s"' % k in t]
        ok(readers, "setting `%s` is read somewhere in libs:browser, libs:headless-browser or the app" % k)
        ok(s.get("type") in ("bool", "int", "string", "enum", "set"), "`%s` has a known type" % k)
        ok(s.get("class") in ("config", "device"), "`%s` has a migration class" % k)
        if s.get("type") == "int":
            ok("min" in s and "max" in s, "int `%s` declares its range" % k)
    writers = [p for p, t in src.items() if 'getSharedPreferences("browser_settings"' in t]
    ok([os.path.basename(p) for p in writers] == ["BrowserSettings.kt"],
       "only BrowserSettings opens the browser_settings prefs", "found in %s" % writers)
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    for op in ("settings", "settings/catalogue", "settings/set"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok("settings.set(key" in api and '"error"' in api, "settings/set validates through the catalogue and reports the refusal")
    ok('Op("state"' not in api, "no op named state (update-ack-guard owns GET /api/state)")
    doc = os.path.join(root, "ac_cloud-browser/docs/settings-catalogue.md")
    ok(os.path.exists(doc) and open(doc, encoding="utf-8").read() == render_catalogue(bj),
       "docs/settings-catalogue.md is current (./build.sh catalogue)")
    m = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))
    st = m["stores"].get("browser_settings", {})
    ok(st.get("kind") == "prefs" and st.get("class") == "config", "browser_settings is a declared config prefs store")
    for s in cat:
        if s.get("class") == "device":
            ok(st.get("keys", {}).get(s["key"]) == "device", "device-class `%s` is overridden to device in the manifest" % s["key"])
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    ok("BrowserBus.listener = { change ->" in frag, "the live screen listens for writes made over the API")

BJ = "ac_cloud-browser/build.json"
FRAG = LIB + "/BrowserHostFragment.kt"
API = APP + "/debugapi/BrowserDebugApi.kt"
main("settings catalogue", check, [
    ("a declared setting nobody reads", BJ, '"settings": [', '"settings": [{"key": "never_read", "type": "bool", "default": false, "class": "config"},', "`never_read` is read"),
    ("a second writer of browser_settings", FRAG, "private fun applySettings(", 'private val rogue = requireContext().getSharedPreferences("browser_settings", 0)\n    private fun applySettings('),
    ("settings/set stops validating", API, "val err = settings.set(key, q[\"value\"].orEmpty())", "val err: String? = null"),
    ("the generated doc goes stale", BJ, "WebSettings.textZoom, in percent.", "WebSettings.textZoom."),
    ("the store drops out of the manifest", MANIFEST, '"browser_settings"', '"browser_settings_gone"'),
    ("the live screen stops listening", FRAG, "BrowserBus.listener = {", "val unused = {"),
])
PYEOF
