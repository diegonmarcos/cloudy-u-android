#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1418 Cloud Writer's tool tabs, boxes, OCR and edit modes
# ╚══════════════════════════════════════════════════════════════════╝
#
# Static (python3 only). What this certifies:
#   T1 build.json::ui.bottom_nav is EXACTLY core WriterTabs.ORDER = enhancer|drafter|home|translator|grammar,
#      default home, and each is a declared section.
#   T2 MainActivity draws each tab (EnhancerScreen/DrafterScreen/TranslatorScreen/GrammarScreen) from its section id,
#      and every tab run goes through WriterToolRunner (runner.run / runner.draft), the shared ITextTools path.
#   T3 every tab has an input box (WriterBox(..., input = true)) and an output box (input = false);
#      an input box offers Load file, Load image, Take picture and Export; OCR lands through core Ocr.intoBox.
#   T4 OCR routing is core Ocr.run over Routing.run, ML (libs:ml-ocr MlKitOcr) and OpenRouter (VisionOcr),
#      and Configs carries the OCR section (engine + model).
#   T5 the edit modes: EditMode.DEFAULT is WYSIWYG, the box offers both, and the rich editor is linked and inventoried.
#   T6 export offers md, txt and html.
# Each check is then run against planted mutations of copies of the real sources; a mutation that
# leaves every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
ROOT = os.path.dirname(APP)
J = "app/src/main/java/com/diegonmarcos/cloudwriter/"
C = "core/src/main/kotlin/com/diegonmarcos/cloudwriter/core/"
FILES = ["build.json", "app/build.gradle", J + "MainActivity.kt", J + "WriterBoxes.kt", J + "WriterRoutes.kt",
         C + "Boxes.kt", C + "HtmlText.kt"]
INVENTORY = os.path.join(ROOT, "licenses", "inventory.json")


def load():
    f = {rel: open(os.path.join(APP, rel), encoding="utf-8").read() for rel in FILES}
    f["inventory"] = open(INVENTORY, encoding="utf-8").read() if os.path.exists(INVENTORY) else ""
    return f


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    try:
        ui = json.loads(f["build.json"])["ui"]
    except (ValueError, KeyError):
        return ["build.json is unreadable"]
    m = re.search(r"val ORDER = listOf\(([^)]*)\)", f[C + "Boxes.kt"])
    consts = dict(re.findall(r'const val ([A-Z]+) = "([a-z]+)"', f[C + "Boxes.kt"]))
    order = [consts.get(x.strip()) for x in m.group(1).split(",")] if m else []
    need("T1 WriterTabs.ORDER is enhancer|drafter|home|translator|grammar", order == ["enhancer", "drafter", "home", "translator", "grammar"])
    need("T1 build.json ui.bottom_nav is WriterTabs.ORDER", ui.get("bottom_nav") == order and len(order) == 5)
    need("T1 ui.default_section is home", ui.get("default_section") == "home")
    secs = {s["id"] for s in ui.get("sections", [])}
    need("T1 every tab is a declared section", all(t in secs for t in order))
    main = f[J + "MainActivity.kt"]
    for scr in ("ENHANCER", "DRAFTER", "TRANSLATOR", "GRAMMAR"):
        name = scr.capitalize() + "Screen"
        need("T2 Screen.%s draws %s()" % (scr, name), re.search(r"Screen\.%s -> %s\(\)" % (scr, name), main) is not None)
        need("T2 WriterNav.%s opens Screen.%s" % (scr, scr), "WriterNav.%s -> screen.value = Screen.%s" % (scr, scr) in main)
    need("T2 Enhancer runs WriterTool.ENHANCE through the runner", "runTab(WriterTool.ENHANCE" in main)
    need("T2 Translator runs WriterTool.TRANSLATE through the runner", "runTab(WriterTool.TRANSLATE" in main)
    need("T2 Grammar runs WriterTool.GRAMMAR through the runner", "runTab(WriterTool.GRAMMAR" in main)
    need("T2 Drafter runs runner.draft", "runner.draft(" in main)
    need("T2 runTab calls runner.run", "runner.run(tool, input.text()" in main)
    need("T2 Translator has the ML / OpenRouter engine picker on the Translation route",
         "WriterRoutes.setRoute(this@MainActivity, WriterRoutes.Function.TRANSLATION" in main)
    need("T3 the tab draws an input box", "WriterBox(inputLabel, input, io, input = true)" in main)
    need("T3 the tab draws an output box", "WriterBox(outputLabel, output, io, input = false)" in main)
    box = f[J + "WriterBoxes.kt"]
    for action in ("io.loadFile(state)", "io.loadImage(state)", "io.takePicture(state)", "io.export(state, it)"):
        need("T3 the box offers %s" % action, action in box)
    need("T3 Load file / Take picture / Load image are input-box actions", re.search(r"if \(input\) \{\s*Action\(Icons\.Filled\.FileOpen", box) is not None)
    need("T3 OCR text lands in the box through Ocr.intoBox", "box.setHtml(Ocr.intoBox(box.html()" in box)
    need("T3 Load image and Take picture both run OCR", "pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ocr(uri) }" in box
         and "ok -> shot?.takeIf { ok }?.let { ocr(it) }" in box)
    need("T3 a loaded file is converted by core Import", "Import.toHtml(kind, it)" in box)
    routes = f[J + "WriterRoutes.kt"]
    need("T4 OCR routes through core Ocr.run", "Ocr.run(" in routes)
    need("T4 the ML engine is libs:ml-ocr's MlKitOcr", "MlKitOcr.read(bitmap)" in routes)
    need("T4 the OpenRouter engine is libs:ml-ocr's VisionOcr", "VisionOcr {" in routes and "OpenRouter.visionBody(" in routes)
    need("T4 Ocr.run is Routing.run", re.search(r"fun run\(engine: OcrEngine[^=]*=\s*Routing\.run\(route\(engine\)", f[C + "Boxes.kt"]) is not None)
    need("T4 OCR engine defaults to ML", 'val DEFAULT = ML' in f[C + "Boxes.kt"])
    need("T4 Configs has the OCR section", "OcrSection()" in main and "WriterRoutes.setOcrEngine(" in main and "WriterRoutes.setOcrModel(" in main)
    need("T4 build.json declares libs:ml-ocr and the app depends on it",
         "libs:ml-ocr" in json.loads(f["build.json"]).get("modules", {}) and "libs:ml-ocr" in json.loads(f["build.json"])["modules"]["app"]["depends_on"])
    need("T4 app compiles :libs:ml-ocr", "project(':libs:ml-ocr')" in f["app/build.gradle"])
    ht = f[C + "HtmlText.kt"]
    need("T5 EditMode.DEFAULT is WYSIWYG", "val DEFAULT = WYSIWYG" in ht)
    need("T5 the box starts in EditMode.DEFAULT", "class BoxState(initial: EditMode = EditMode.DEFAULT)" in box)
    need("T5 the box offers both modes", "state.switchTo(EditMode.WYSIWYG)" in box and "state.switchTo(EditMode.TEXT)" in box)
    need("T5 WYSIWYG is the rich editor", "RichTextEditor(state = state.rich" in box)
    need("T5 the rich editor is linked", "com.mohamedrejeb.richeditor:richeditor-compose:" in f["app/build.gradle"])
    need("T5 the rich editor is in the licence inventory", '"maven:com.mohamedrejeb.richeditor:richeditor-compose"' in f["inventory"])
    for fmt in ("MD", "TXT", "HTML"):
        need("T6 export offers %s" % fmt, "ExportFormat.%s to R.string.box_export_%s" % (fmt, fmt.lower()) in box)
    return bad


real = load()
bad = checks(real)
for b in bad:
    print("FAIL   " + b)
if bad:
    print("FAILED %d assertion(s)" % len(bad)); sys.exit(1)
print("ok     every assertion green on the real tree")

MUT = [
    ("build.json", '"enhancer",\n      "drafter",', '"drafter",\n      "enhancer",', "bar order swapped"),
    ("build.json", '"default_section": "home"', '"default_section": "enhancer"', "default not home"),
    (C + "Boxes.kt", "val ORDER = listOf(ENHANCER, DRAFTER, HOME, TRANSLATOR, GRAMMAR)", "val ORDER = listOf(ENHANCER, DRAFTER, HOME, GRAMMAR, TRANSLATOR)", "core order swapped"),
    (J + "MainActivity.kt", "Screen.TRANSLATOR -> TranslatorScreen()", "Screen.TRANSLATOR -> GrammarScreen()", "Translator draws Grammar"),
    (J + "MainActivity.kt", "runTab(WriterTool.GRAMMAR", "runTab(WriterTool.ENHANCE", "Grammar runs Enhance"),
    (J + "MainActivity.kt", "WriterBox(inputLabel, input, io, input = true)", "WriterBox(inputLabel, input, io, input = false)", "input box loses its actions"),
    (J + "WriterBoxes.kt", "box.setHtml(Ocr.intoBox(box.html()", "box.setHtml((box.html()", "OCR result dropped"),
    (J + "WriterBoxes.kt", "io.takePicture(state)", "io.loadImage(state)", "no Take picture"),
    (J + "WriterRoutes.kt", "MlKitOcr.read(bitmap)", "Outcome.failed(\"x\") ?: MlKitOcr", "ML engine unplugged"),
    (C + "Boxes.kt", "val DEFAULT = ML", "val DEFAULT = OPENROUTER", "OCR default OpenRouter"),
    (C + "HtmlText.kt", "val DEFAULT = WYSIWYG", "val DEFAULT = TEXT", "Text-only default"),
    (J + "WriterBoxes.kt", "ExportFormat.HTML to R.string.box_export_html", "ExportFormat.MD to R.string.box_export_html", "no HTML export"),
    ("inventory", '"maven:com.mohamedrejeb.richeditor:richeditor-compose"', '"maven:x:y"', "editor not inventoried"),
]
missed = 0
for rel, old, new, why in MUT:
    if old not in real[rel]:
        print("FAIL   mutation '%s' no longer applies (%s changed): update this tester" % (why, rel)); missed += 1; continue
    f = dict(real); f[rel] = real[rel].replace(old, new, 1)
    if checks(f):
        print("ok     mutation '%s' goes red" % why)
    else:
        print("FAIL   mutation '%s' stays green" % why); missed += 1
if missed:
    print("FAILED %d mutation(s) not proven red" % missed); sys.exit(1)
print("All assertions green on the real tree and each mutation proven red.")
PY
