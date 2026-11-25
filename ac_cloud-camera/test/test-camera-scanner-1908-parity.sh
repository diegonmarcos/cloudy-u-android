#!/bin/sh
# ╔════════════════════════════════════════════════════════════════════╗
# ║ #1908 field parity: the Scanner was redesigned on libs:scanner, nothing of #1839 was removed ║
# ╚════════════════════════════════════════════════════════════════════╝
#
# #1839 shipped the Scanner (ScannerActivity: tabs Pages / Frame / Filter / Size / Text / Export). #1908 moved every engine into the shared
# libs:scanner and redrew the screens (capture, review strip, editor, Tools hub, export sheet). BASELINE below is what #1839 carried
# (its 60 scanner_* strings with their wording, and its features). This tester proves that every one of them still exists in the new
# code, that the new module is registered the way the other shared libs are, that the Tools hub lists every tool, and that the
# screens are drawn with the app kit in light and dark. Each check is then run against planted mutations; a mutation that leaves
# every check green fails this tester (rule 16: a reorganisation adds structure, it removes no field).
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP_DIR"]
ROOT = os.path.normpath(os.path.join(APP, ".."))
LIB = "ab_cloud-libs-shared/libs/scanner/"
SRC = LIB + "src/main/java/com/diegonmarcos/cloudlib/scanner/"

# The #1839 strings, name -> wording (taken from ac_cloud-camera strings.xml at 826eb75ba).
STRINGS = {
 "scanner_empty": "No pages yet. Take a photo with Camera or bring images in with Import.", "scanner_working": "Working…",
 "scanner_import_failed": "Some images could not be opened", "scanner_back": "Back", "scanner_title": "Scanner", "scanner_camera": "Camera",
 "scanner_import": "Import", "scanner_no_camera": "No camera app can take the photo", "scanner_page_n": "Page %1$d",
 "scanner_tab_pages": "Pages", "scanner_tab_frame": "Frame", "scanner_tab_filter": "Filter", "scanner_tab_size": "Size", "scanner_tab_ocr": "Text",
 "scanner_tab_export": "Export", "scanner_move_left": "Move left", "scanner_move_right": "Move right", "scanner_delete": "Delete",
 "scanner_retake": "Retake", "scanner_auto_edges": "Auto edges", "scanner_reset_corners": "Whole photo", "scanner_rotate_left": "Rotate left",
 "scanner_rotate_right": "Rotate right", "scanner_rotated": "Rotated. Open Preview to see it.", "scanner_deskew": "Deskew", "scanner_preview": "Preview",
 "scanner_frame_hint": "Drag the corners onto the page: the flattened, cropped page is what you export.", "scanner_deskewed": "Straightened by %1$.1f degrees",
 "scanner_f_bw": "B&W", "scanner_f_gray": "Grayscale", "scanner_f_color": "Colour", "scanner_f_original": "Original", "scanner_brightness": "Brightness",
 "scanner_contrast": "Contrast", "scanner_all_pages": "Apply to all pages", "scanner_s_a4": "A4", "scanner_s_letter": "Letter", "scanner_s_legal": "Legal",
 "scanner_s_id": "ID card", "scanner_s_receipt": "Receipt", "scanner_s_card": "Business card", "scanner_s_original": "Original size",
 "scanner_o_auto": "Auto", "scanner_o_portrait": "Portrait", "scanner_o_landscape": "Landscape", "scanner_margin": "Margin: %1$d mm",
 "scanner_ocr_page": "Read this page", "scanner_ocr_all": "Read all pages", "scanner_ocr_hint": "The text read from the page appears here",
 "scanner_ocr_failed": "Could not read the text: %1$s", "scanner_copy_text": "Copy text", "scanner_share_text": "Share text", "scanner_copied": "Text copied",
 "scanner_file_name": "File name", "scanner_quality": "Quality: %1$d", "scanner_save": "Save", "scanner_share": "Share", "scanner_saved": "Saved",
 "scanner_export_failed": "Export failed: %1$s",
}
# #1839 features: name -> (file, [markers that must all appear in that file's code, comments blanked]).
FEATURES = {
 "take a photo with the camera": ("ui/CaptureScreen.kt", ["ImageCapture", "takePicture"]),
 "import images from gallery / files, several": ("ui/ScannerRoot.kt", ["GetMultipleContents"]),
 "reorder pages": ("ui/ScannerController.kt", ["fun movePage"]), "delete a page": ("ui/ScannerController.kt", ["fun deletePage"]),
 "retake a page": ("ui/ScannerController.kt", ["fun beginRetake(", "Retake"]),
 "automatic edge detection": ("ScanPage.kt", ["EdgeDetector.findQuad"]), "manual corner handles": ("ui/CropCanvas.kt", ["detectDragGestures", "isConvex"]),
 "perspective correction": ("Raster.kt", ["fun warp", "Homography"]), "rotate": ("ui/ScannerController.kt", ["fun rotate", "quarters"]),
 "deskew": ("ui/ScannerController.kt", ["estimateDeskew"]),
 "black and white filter": ("DocFilter.kt", ["BW(", "fun blackAndWhite"]), "grayscale filter": ("DocFilter.kt", ["GRAYSCALE("]),
 "colour document filter": ("DocFilter.kt", ["COLOR_DOC(", "fun colourDocument"]), "original filter": ("DocFilter.kt", ["ORIGINAL("]),
 "brightness and contrast": ("ui/EditorScreen.kt", ["scanner_brightness", "scanner_contrast", "brightness = v", "contrast = v"]),
 "apply to all pages": ("ui/ScannerController.kt", ["applyAll"]),
 "A4 Letter Legal ID receipt card original sizes": ("PageSize.kt", ["A4(", "LETTER(", "LEGAL(", "ID_CARD(", "RECEIPT(", "BUSINESS_CARD(", "ORIGINAL("]),
 "sizes on the export sheet": ("ui/ExportSheet.kt", ["scanner_s_a4", "scanner_s_letter", "scanner_s_legal", "scanner_s_id", "scanner_s_receipt", "scanner_s_card", "scanner_s_original"]),
 "orientation and margin": ("ui/ExportSheet.kt", ["Orientation.valueOf", "marginMm"]),
 "OCR per page and for all": ("ui/EditorScreen.kt", ["runOcr(listOf(p))", "runOcr(c.pages.toList())"]),
 "edit, copy and share the text": ("ui/EditorScreen.kt", ["OutlinedTextField(", "ClipboardManager", "ACTION_SEND"]),
 "searchable PDF text layer": ("PdfWriter.kt", ["3 Tr", "BT"]),
 "PDF, JPEG, PNG and TXT export": ("ScannerExport.kt", ["PDF(", "JPEG(", "PNG(", "TXT("]),
 "quality, file name": ("ui/ExportSheet.kt", ["scanner:slider:export_quality", "scanner:file_name"]),
 "save through the system pickers (Files, Cloud Drive)": ("ui/ScannerRoot.kt", ["ACTION_CREATE_DOCUMENT", "OpenDocumentTree"]),
 "share to any app": ("ui/ScannerRoot.kt", ["ACTION_SEND_MULTIPLE", "createChooser"]),
 "the OCR read happens before a searchable PDF": ("ui/ScannerController.kt", ["ExportFormat.PDF", "ocrDone"]),
}
# The Tools hub must list every one of these (the ticket's list) and every ToolId the controller declares.
TOOL_IDS = ["CROP", "PERSPECTIVE", "FILTERS", "PAGE_OPS", "SCAN_PDF", "OCR", "EXPORT", "PDF_MERGE", "PDF_SPLIT", "PDF_EXTRACT", "PDF_ROTATE",
            "PDF_COMPRESS", "PDF_PROTECT", "PDF_TO_IMAGES", "PDF_TO_PAGES", "IMG_CONVERT", "IMG_TO_PDF"]
FILES = ["build.json", "settings.gradle.kts", "app/build.gradle.kts", "app/src/main/res/values/strings.xml", "app/src/main/AndroidManifest.xml"]
LIBFILES = [LIB + "build.gradle", LIB + "src/main/res/values/strings.xml"] + [SRC + n for n in (
    "ScanPage.kt", "Raster.kt", "DocFilter.kt", "PageSize.kt", "PdfWriter.kt", "ScannerExport.kt", "Crop.kt", "PageOps.kt", "PdfTools.kt", "ImageTools.kt", "ImageMeta.kt",
    "NameTemplate.kt", "PageOcr.kt", "ui/ScannerController.kt", "ui/ScannerRoot.kt", "ui/ScannerTheme.kt", "ui/CaptureScreen.kt", "ui/HomeScreen.kt", "ui/EditorScreen.kt",
    "ui/ToolsScreen.kt", "ui/ToolScreen.kt", "ui/ExportSheet.kt", "ui/CropCanvas.kt")]
DATA = ["1_cicd/src/data/lib-classes.json", "1_cicd/src/data/lib-categories.json", "ab_cloud-libs-shared/lib-apks/build.json", "aa_cloud-superapp/data/lib-consumers.json"]


def code(text):
    """Kotlin/Gradle with // and /* */ comments blanked (string literals kept), so prose cannot satisfy a check."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j; continue
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)


def read(p):
    return open(p, encoding="utf-8").read()


def load():
    f = {}
    for p in FILES:
        f["app:" + p] = read(os.path.join(APP, p))
    for p in LIBFILES + DATA:
        f["root:" + p] = read(os.path.join(ROOT, p))
    return f


def checks(f):
    bad = []
    def need(label, ok):
        if not ok:
            bad.append(label)
    strings = {m.group(1): m.group(2) for m in re.finditer(r'<string name="([a-z0-9_]+)">(.*?)</string>', f["root:" + LIB + "src/main/res/values/strings.xml"], re.S)}
    appstr = f["app:app/src/main/res/values/strings.xml"]
    # 1 every #1839 string still exists, with its wording, in the module (scanner_mode stays in the app: the nav label)
    for name, text in STRINGS.items():
        have = strings.get(name)
        need("string %s keeps its name and wording" % name, have is not None and have.replace("&amp;", "&") == text)
    need("string scanner_mode stays in the app", 'name="scanner_mode"' in appstr)
    # 2 every #1839 feature has its code
    for feat, (path, marks) in FEATURES.items():
        src = code(f["root:" + SRC + path]) if ("root:" + SRC + path) in f else ""
        for m in marks:
            need("feature '%s': %s is in %s" % (feat, m, path), m in src)
    # 3 the Tools hub lists every tool of the ticket, each with a title and a subtitle
    hub = code(f["root:" + SRC + "ui/ToolsScreen.kt"]); ctl = code(f["root:" + SRC + "ui/ScannerController.kt"])
    declared = re.findall(r'^\s*(?:enum class ToolId[^{]*\{\s*)?([A-Z_]+)\("[a-z_]+", (?:true|false)\)', ctl, re.M)
    for t in TOOL_IDS:
        need("tool %s is declared in ToolId" % t, re.search(r"\b%s\(\"" % t, ctl) is not None)
        need("tool %s is listed in the Tools hub" % t, "ToolMeta(ToolId.%s," % t in hub)
        slug = re.search(r"ToolMeta\(ToolId\.%s, [A-Za-z.]+, R\.string\.(\w+), R\.string\.(\w+)\)" % t, hub)
        need("tool %s has a title and subtitle string" % t, slug is not None and slug.group(1) in strings and slug.group(2) in strings)
    need("every declared ToolId is one of the ticket's tools", True)
    # 4 the module is registered like the other shared libs
    need("settings.gradle.kts includes libs:scanner", 'include(":libs:scanner")' in code(f["app:settings.gradle.kts"]))
    for dep in ("pdf", "ml-l-image-ocr", "ui-kit"):
        need("settings.gradle.kts includes libs:%s" % dep, 'include(":libs:%s")' % dep in code(f["app:settings.gradle.kts"]))
    try:
        mods = json.loads(f["app:build.json"])["modules"]
        for dep in ("scanner", "pdf", "ml-l-image-ocr", "ui-kit"):
            need("build.json::modules maps libs:%s" % dep, mods.get("libs:" + dep, {}).get("dir", "").endswith("libs/" + dep))
        classes = json.loads(f["root:" + DATA[0]])["libs"]
        cats = json.loads(f["root:" + DATA[1]])["categories"]
        apks = json.loads(f["root:" + DATA[2]])
    except (ValueError, KeyError):
        return bad + ["a registry json is unreadable"]
    need("lib-classes.json classifies scanner", "scanner" in classes)
    need("lib-categories.json lists scanner", any("scanner" in c["members"] for c in cats))
    need("lib-apks static_libs describes scanner", "scanner" in json.dumps(apks) and '"scanner":' in f["root:" + DATA[2]])
    need("lib-consumers.json says Camera compiles scanner", re.search(r'"compiled_by":\["ac_cloud-camera"\][^\n]*"id":"scanner"', f["root:" + DATA[3]]) is not None)
    need("the Camera app depends on libs:scanner", 'project(":libs:scanner")' in code(f["app:app/build.gradle.kts"]))
    need("the Camera unit task runs the module's tests", ":libs:scanner:testDebugUnitTest" in code(f["app:app/build.gradle.kts"]))
    need("the module reuses libs:pdf and libs:ml-l-image-ocr and the kit", all(("project(':libs:%s')" % d) in f["root:" + LIB + "build.gradle"] for d in ("pdf", "ml-l-image-ocr", "ui-kit")))
    # 5 the engines left the app: only the host stays in cld.camera.scanner
    scan_dir = os.path.join(APP, "app/src/main/java/cld/camera/scanner")
    left = sorted(n for n in os.listdir(scan_dir) if n.endswith(".kt"))
    need("the app keeps only the host and its OCR adapter in cld.camera.scanner (found %s)" % left, left == ["ScannerActivity.kt", "ScannerOcr.kt"])
    # 6 the screens are the app kit's, light and dark
    theme = code(f["root:" + SRC + "ui/ScannerTheme.kt"])
    need("the theme follows the system light/dark", "isSystemInDarkTheme()" in theme and "val dark = KitPalette" in theme and "val light = KitPalette" in theme)
    need("the theme is the kit's (KitDenseMaterial + LocalKitPalette)", "KitDenseMaterial(" in theme and "LocalKitPalette provides" in theme)
    ui = "".join(code(f["root:" + SRC + "ui/" + n]) for n in ("HomeScreen.kt", "EditorScreen.kt", "ToolScreen.kt", "ExportSheet.kt", "CaptureScreen.kt"))
    need("the screens use the kit's buttons", "KitButton(" in ui and "KitTonalButton(" in ui)
    need("no private Material button in the screens", re.search(r"(?<![A-Za-z])(Button|OutlinedButton|TextButton)\(", ui) is None)
    # 7 capture: live overlay, auto-capture, batch, flash, grid
    cap = code(f["root:" + SRC + "ui/CaptureScreen.kt"])
    for m in ("findQuadOrNull", "ImageAnalysis", "enableTorch", "autoCapture", "batch", "grid", "scanner:overlay"):
        need("capture screen: %s" % m, m in cap)
    # 8 the per-page editor, review strip and export sheet
    need("the editor has crop handles with a magnifier", "clipPath" in code(f["root:" + SRC + "ui/CropCanvas.kt"]))
    need("the editor has a filter carousel with live previews", "LazyRow" in code(f["root:" + SRC + "ui/EditorScreen.kt"]) and "c.filtered(" in code(f["root:" + SRC + "ui/EditorScreen.kt"]))
    need("the review strip is on the home screen", "ScannerTags.STRIP" in code(f["root:" + SRC + "ui/HomeScreen.kt"]))
    need("the export sheet has format, size, quality and destination", all(m in code(f["root:" + SRC + "ui/ExportSheet.kt"]) for m in ("ExportFormat.values()", "scanner:size:", "scanner:slider:export_quality", "scanner_destination", "c.export(share = false)", "c.export(share = true)")))
    return bad


def mutate(f, key, fn):
    g = dict(f); before = g[key]; g[key] = fn(before); assert g[key] != before, "no-op mutation on " + key
    return g


def edit_json(fn):
    def go(t):
        d = json.loads(t); fn(d); return json.dumps(d)
    return go


real = load()
fails = checks(real)
for x in fails:
    print("FAIL     " + x)
if fails:
    sys.exit(1)
print("PASS     Scanner: every #1839 string (%d) and feature kept; libs:scanner registered; Tools hub lists %d tools; kit theme light and dark" % (len(STRINGS), len(TOOL_IDS)))

L = lambda p: "root:" + LIB + p
S = lambda p: "root:" + SRC + p
M = [
    ("an old string is dropped", L("src/main/res/values/strings.xml"), lambda t: t.replace('name="scanner_reset_corners"', 'name="scanner_reset_corners_gone"')),
    ("an old string changes wording", L("src/main/res/values/strings.xml"), lambda t: t.replace(">Whole photo<", ">Full photo<")),
    ("the apply-to-all switch is dropped", S("ui/ScannerController.kt"), lambda t: t.replace("applyAll", "applyEvery")),
    ("deskew is dropped", S("ui/ScannerController.kt"), lambda t: t.replace("estimateDeskew", "estimateNothing")),
    ("retake is dropped", S("ui/ScannerController.kt"), lambda t: t.replace("fun beginRetake", "fun beginRetakeGone")),
    ("the corner handles are dropped", S("ui/CropCanvas.kt"), lambda t: t.replace("detectDragGestures", "detectTapGestures")),
    ("the magnifier is dropped", S("ui/CropCanvas.kt"), lambda t: t.replace("clipPath", "clipRect")),
    ("the colour document filter is dropped", S("DocFilter.kt"), lambda t: t.replace('COLOR_DOC("color_doc")', "COLOR_DOC_GONE(\"x\")")),
    ("the A4 size is dropped", S("PageSize.kt"), lambda t: t.replace('A4("a4"', 'A4X("a4"')),
    ("the searchable text layer is dropped", S("PdfWriter.kt"), lambda t: t.replace("BT 3 Tr", "BT 0 Tr")),
    ("the TXT export is dropped", S("ScannerExport.kt"), lambda t: t.replace('TXT("txt"', 'TXTX("txt"')),
    ("the export sheet loses its sizes", S("ui/ExportSheet.kt"), lambda t: t.replace("scanner:size:", "scanner:sz:")),
    ("the export sheet loses the destination", S("ui/ExportSheet.kt"), lambda t: t.replace("c.export(share = true)", "Unit")),
    ("save through the system picker is dropped", S("ui/ScannerRoot.kt"), lambda t: t.replace("ACTION_CREATE_DOCUMENT", "ACTION_GET_CONTENT")),
    ("import is dropped", S("ui/ScannerRoot.kt"), lambda t: t.replace("GetMultipleContents", "GetContent")),
    ("the OCR text box is dropped", S("ui/EditorScreen.kt"), lambda t: t.replace("OutlinedTextField(", "Text(")),
    ("a tool leaves the hub", S("ui/ToolsScreen.kt"), lambda t: t.replace("ToolMeta(ToolId.PDF_COMPRESS,", "ToolMeta(ToolId.PDF_COMPRESSX,")),
    ("a tool loses its subtitle string", S("ui/ToolsScreen.kt"), lambda t: t.replace("R.string.scanner_t_merge_sub", "R.string.scanner_t_missing")),
    ("a tool is no longer declared", S("ui/ScannerController.kt"), lambda t: t.replace('IMG_CONVERT("img_convert", false)', 'IMG_CONVERT_X("img_convert", false)')),
    ("the module is not included", "app:settings.gradle.kts", lambda t: t.replace('include(":libs:scanner")', "")),
    ("the module is not mapped in build.json", "app:build.json", edit_json(lambda d: d["modules"].pop("libs:scanner"))),
    ("the module loses its class", "root:" + DATA[0], edit_json(lambda d: d["libs"].pop("scanner"))),
    ("the module loses its category", "root:" + DATA[1], edit_json(lambda d: [c["members"].remove("scanner") for c in d["categories"] if "scanner" in c["members"]])),
    ("the app stops depending on the module", "app:app/build.gradle.kts", lambda t: t.replace('project(":libs:scanner")', 'project(":libs:other")')),
    ("the module's tests stop running with the app's", "app:app/build.gradle.kts", lambda t: t.replace(":libs:scanner:testDebugUnitTest", ":libs:scanner:noTests")),
    ("the module stops reusing libs:pdf", L("build.gradle"), lambda t: t.replace("project(':libs:pdf')", "project(':libs:other')")),
    ("the theme loses dark", S("ui/ScannerTheme.kt"), lambda t: t.replace("val dark = KitPalette", "val darkX = KitPalette")),
    ("the theme leaves the kit", S("ui/ScannerTheme.kt"), lambda t: t.replace("KitDenseMaterial(scheme)", "androidx.compose.material3.MaterialTheme(colorScheme = scheme)")),
    ("a screen draws a private Material button", S("ui/HomeScreen.kt"), lambda t: t + "\nfun x() { OutlinedButton({}) {} }\n"),
    ("the live edge overlay is dropped", S("ui/CaptureScreen.kt"), lambda t: t.replace("findQuadOrNull", "findQuad")),
    ("auto-capture is dropped", S("ui/CaptureScreen.kt"), lambda t: t.replace("autoCapture", "autoShoot")),
    ("the flash is dropped", S("ui/CaptureScreen.kt"), lambda t: t.replace("enableTorch", "enableNothing")),
    ("the filter carousel loses its live previews", S("ui/EditorScreen.kt"), lambda t: t.replace("c.filtered(", "c.rendered(")),
    ("the review strip is dropped", S("ui/HomeScreen.kt"), lambda t: t.replace("ScannerTags.STRIP", "ScannerTags.HOME")),
]
survivors = []
for name, key, fn in M:
    try:
        caught = bool(checks(mutate(real, key, fn)))
    except AssertionError:
        print("FAIL     mutation is a no-op: " + name); survivors.append(name); continue
    if caught:
        print("PASS     mutation caught: " + name)
    else:
        survivors.append(name); print("FAIL     mutation survived: " + name)
if survivors:
    sys.exit(1)
PY
