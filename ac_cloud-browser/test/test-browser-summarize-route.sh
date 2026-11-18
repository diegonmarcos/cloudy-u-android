#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #823 Summarize the page: Model or on-device, one rule, named     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The #799/#800 contract for the browser's summary: the user's route
# (setting summarize_route = the two declared routes), one fallback rule
# (PageSummary.routed, never a second hand-rolled one), the on-device
# engine really asked (ML Kit, then the extractive summary), every answer
# naming route/engine/fell_back, and the token read per call and never
# put into an answer. Matched in COMMENT-STRIPPED Kotlin.
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
    ai = [a for a in b.get("addons", []) if a["id"] == "ai"][0]
    sm = ai.get("summarize") or {}
    st = [s for s in b.get("settings", []) if s["key"] == "summarize_route"]
    ok(len(st) == 1 and st[0].get("type") == "enum" and st[0].get("values") == ["model", "on_device"],
       "the summarize_route setting offers exactly model and on_device")
    ok(st and sm.get("default_route") == st[0].get("default") == "model", "Model is the declared default, in the setting and the add-on alike")
    ok(sm.get("fallback") is True, "the model route falls back on device")
    ok(any(m.get("id") == "ai_summarize" for m in ai.get("menu", [])), "the add-on has its Summarize row")
    src = kotlin(root, LIB, APP)
    ps = src.get(os.path.join(LIB, "PageSummary.kt"), "")
    sz = src.get(os.path.join(APP, "search", "PageSummarizer.kt"), "")
    runner = src.get(os.path.join(APP, "search", "AgentRunner.kt"), "")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    ok(sz.count("PageSummary.routed(") == 1, "PageSummarizer runs every summary through the one rule, PageSummary.routed")
    ok("if (m.ok || !fallback) return" in ps and "fellBack = true, reason = " in ps, "a failed model falls back on device and says why")
    ok("skipModel(online, hasToken)" in ps and "online == false -> OFFLINE" in ps, "offline, the model is not even attempted")
    ok("checkFeatureStatus()" in sz and "FeatureStatus.AVAILABLE -> {" in sz and "runInference(" in sz, "the on-device route asks ML Kit's summarizer")
    ok("PageSummary.extractive(text, sentences)" in sz, "where ML Kit cannot answer, the extractive summary does")
    for f in ('.put("route"', '.put("engine"', '.put("fell_back"', '.put("reason"', '.put("requested"'):
        ok(f in ps, "every answer names %s" % f.split('"')[1])
    ok("search.backend(SearchAddon.relay(app))" in sz, "the model route goes through the AI-key owner engine (#1801), no key read here")
    ok(not re.search(r'put\("(token|authorization|key)"', sz + ps, re.I) and "Log." not in sz, "no answer or log carries the token")
    ok('Op("ai/summarize"' in api and '"ai/summarize" ->' in api, "route ai/summarize is documented and handled")
    ok('settings.string("summarize_route")' in api, "the route follows his setting")
    ok('"ai_summarize" -> { showPageSummary()' in frag, "the Summarize row is wired")
    ok('c.name == "summarize_page" && route(' in runner and "summarizer.summarizePage(PageSummary.ON_DEVICE)" in runner,
       "the assistant's summarize_page tool summarizes on device when that is his route")

BJ = "ac_cloud-browser/build.json"
LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
APPP = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/"
main("summarize route", check, [
    ("the fallback is switched off", BJ, '"fallback": true,\n', '"fallback": false,\n', "falls back on device"),
    ("a failed model is returned as is", LIBP + "PageSummary.kt", "if (m.ok || !fallback) return", "if (true) return", "falls back on device and says why"),
    ("ML Kit is never asked", APPP + "search/PageSummarizer.kt", "FeatureStatus.AVAILABLE -> {", "-1 -> {", "asks ML Kit"),
    ("the answer hides its route", LIBP + "PageSummary.kt", '.put("route", route)', '.put("rout", route)', "names route"),
    ("the token leaks into the answer", APPP + "search/PageSummarizer.kt", 'return r.json().put("title"', 'return r.json().put("token", "x").put("title"', "carries the token"),
    ("the tool ignores the route", APPP + "search/AgentRunner.kt", 'c.name == "summarize_page" && route(', 'c.name == "never" && route(', "summarize_page tool"),
])
PYEOF
