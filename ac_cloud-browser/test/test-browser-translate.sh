#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #886 Translate page in place, the engine choice, topic summary   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies (the pure rules are JVM-tested in PageTranslateTest / PageTopicsTest):
#   R1 the engine is a declared setting (on-device ML | OpenRouter) and the host builds the backend FROM it;
#   R2 the two engines stay apart: on-device only ever calls translate, OpenRouter only ever calls the
#      model (enhanceWith), and neither the key nor an HTTP client to a model is in this app (the key
#      stays in Cloud Writer / Cloud Keyboard, reached over the text-tools binder);
#   R3 the page scripts, run under node against a fake DOM (translate_js_harness.js): only text-node values
#      change (no innerHTML), script/style/code/pre/numbers/translate=no/hidden are skipped, whitespace is
#      kept, batches are claimed progressively, restore brings every original back;
#   R4 the host wires it: toggle (menu `translate` is a toggle), progressive on scroll, abandoned on navigation,
#      a chip to restore, the target language a setting;
#   R5 Summarise by topics sits beside Translate, uses the LLM route when OpenRouter is chosen, and says in
#      words when only on-device ML is chosen that it cannot summarise.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, os, re, subprocess
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

ASSETS = "ab_cloud-libs-shared/libs/browser/src/main/assets/browser"
ASSETS_REL = ASSETS

def body(text, start, end_markers):
    i = text.find(start)
    if i < 0:
        return ""
    ends = [text.find(m, i + len(start)) for m in end_markers]
    ends = [e for e in ends if e > 0]
    return text[i:min(ends)] if ends else text[i:]

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    pt = src.get(LIB + "/PageTranslate.kt", "")
    ptr = src.get(LIB + "/PageTranslator.kt", "")
    port = src.get(LIB + "/TextToolsClientPort.kt", "")
    top = src.get(LIB + "/PageTopics.kt", "")
    b = build_json(root)["ui"]["browser"]
    st = {s["key"]: s for s in b["settings"]}
    # R1
    e = st.get("translate_engine", {})
    ok(e.get("type") == "enum" and e.get("values") == ["on_device", "openrouter"] and e.get("section") == "browsing", "R1 translate_engine is an enum of the two engines, on the Browser page")
    ok("translate_target" in st and st["translate_target"]["default"] == "device", "R1 the target language is a setting, defaulting to the phone's")
    ok('browserSettings.string("translate_engine")' in frag and "PageTranslate.backend(engine, textToolsPort())" in frag, "R1 the host builds the backend from the setting")
    # R2
    on = body(pt, "class OnDeviceBackend", ["class LlmBackend"])
    llm = body(pt, "class LlmBackend", ["fun backend("])
    ok("tools.translate(" in on and "enhanceWith" not in on and "summariseWith" not in on, "R2 on-device ML only calls translate")
    ok("tools.enhanceWith(" in llm and "tools.translate(" not in llm, "R2 OpenRouter only calls the model, never the translator")
    ok("c.enhanceWith(text, systemPrompt, \"\", \"\")" in port and "c.translate(text, tag)" in port, "R2 both go over the text-tools binder, provider and model left to the serving app")
    newfiles = "\n".join(src.get(LIB + "/" + f + ".kt", "") for f in ("PageTranslate", "PageTranslator", "PageTopics", "TextToolsClientPort", "BrowserTopicsPanel", "BrowserTranslateChip"))
    ok(not re.search(r"(?i)api[_-]?key|authorization|bearer|openrouter\.ai|HttpURLConnection|OkHttp", newfiles), "R2 no key and no HTTP client to a model in the translate/summary code")
    ok("BuildConfig" not in newfiles, "R2 nothing is baked into the app for it")
    # R3
    for n in ("translate_collect", "translate_apply", "translate_restore"):
        js = open(os.path.join(root, ASSETS, n + ".js"), encoding="utf-8").read()
        ok("innerHTML" not in js and "outerHTML" not in js and "insertAdjacent" not in js, "R3 %s never touches markup" % n)
    r = subprocess.run(["node", "translate_js_harness.js", os.path.join(root, ASSETS)], capture_output=True, text=True)
    lines = r.stdout.splitlines()
    ok(r.returncode == 0 and len(lines) >= 15, "R3 the page scripts run under node", r.stderr[-300:])
    for l in lines:
        ok(l.startswith("ok "), "R3 " + l[4:], l)
    # R4
    ok('"translate" -> { wv ?: return needPage(); done(toggleTranslate()) }' in frag, "R4 the menu action toggles")
    ok("if (translator.active) { translator.restore()" in frag, "R4 the second tap restores the originals")
    ok("setOnScrollChangeListener" in frag and "translator.more()" in frag, "R4 a long page is translated progressively as the reader scrolls")
    ok("readerOn = false; translator.abandon() }" in frag, "R4 a navigation abandons the translation")
    ok("BrowserTranslateChip(translateState.value" in frag, "R4 there is a chip to see progress and to restore")
    ok("for (b in PageTranslate.batches(c.items, be.maxChars, be.maxItems))" in ptr and "io.background" in ptr, "R4 batches go off the main thread")
    mi = {i["id"]: i for i in b["menu"]["items"]}
    ok(mi.get("translate", {}).get("kind") == "toggle" and mi["translate"].get("checked") == "translated", "R4 the menu row is a toggle with a state")
    ok('"translated" to translator.active' in frag, "R4 the fact is the translator's state")
    # R5
    ok("summarize_topics" in mi and mi["summarize_topics"]["section"] == "addons", "R5 Summarise by topics is a Page Tool (#1401)")
    ok('"summarize_topics" -> { wv ?: return needPage(); showTopics(wv)' in frag, "R5 and wired")
    ok("port.summariseWith(PageTopics.llmInput(page, cap)" in frag, "R5 OpenRouter summarises through the binder")
    ok("if (engine != PageTranslate.OPENROUTER)" in frag and "PageTopics.ON_DEVICE_NOTE" in frag, "R5 on-device ML shows the honest note instead")
    ok("cannot write a summary" in top and "OpenRouter" in top, "R5 the note says ML cannot summarise and where to switch")
    ok("st.note = \"The model could not answer" in frag, "R5 a failing model falls back to the outline and says so")

FRAG = LIB + "/BrowserHostFragment.kt"
PT = LIB + "/PageTranslate.kt"
main("translate", check, [
    ("the host ignores the engine setting", FRAG, "PageTranslate.backend(engine, textToolsPort())", "PageTranslate.backend(PageTranslate.ON_DEVICE, textToolsPort())", "builds the backend from the setting"),
    ("on-device ML calls the LLM", PT, "val r = tools.translate(s.text, targetTag)", "val r = tools.enhanceWith(s.text, \"x\")", "on-device ML only calls translate"),
    ("OpenRouter routes through the translator", PT, "val r = tools.enhanceWith(llmPayload(segs), prompt)", "val r = tools.translate(llmPayload(segs), \"en\")", "only calls the model"),
    ("a key is baked into the app", PT, "object PageTranslate {", "object PageTranslate {\n    const val OPENROUTER_API_KEY = \"sk-or-xxx\"", "no key and no HTTP client"),
    ("the collect script rewrites markup", ASSETS + "/translate_apply.js", "e.n.nodeValue = lead + m[k] + trail;", "e.n.parentElement.innerHTML = lead + m[k] + trail;", "never touches markup"),
    ("code blocks get translated", ASSETS + "/translate_collect.js", "CODE: 1, ", "", "script, style, code and pre are skipped"),
    ("whitespace is dropped", ASSETS + "/translate_apply.js", "e.n.nodeValue = lead + m[k] + trail;", "e.n.nodeValue = m[k];", "whitespace is kept"),
    ("restore forgets to restore", ASSETS + "/translate_restore.js", "e.n.nodeValue = e.o;", "e.t = e.t;", "original text again"),
    ("nodes are claimed twice", ASSETS + "/translate_collect.js", "S.seen.add(cand[i].n);", "", "claimed twice"),
    ("the toggle only ever starts", FRAG, "if (translator.active) { translator.restore()", "if (false) { translator.restore()", "second tap restores"),
    ("scroll stops translating more", FRAG, "translator.more()", "Unit", "progressively"),
    ("a navigation leaves a stale job", FRAG, "readerOn = false; translator.abandon() }", "readerOn = false }", "abandons"),
    ("batches run on the main thread", LIB + "/PageTranslator.kt", "io.background {", "io.main {", "off the main thread"),
    ("the on-device note goes", LIB + "/PageTopics.kt", "On-device ML can translate but cannot write a summary.", "On-device ML can translate.", "cannot summarise"),
    ("on-device gets a fake AI summary", FRAG, "if (engine != PageTranslate.OPENROUTER) {", "if (false) {", "honest note"),
])
PYEOF
