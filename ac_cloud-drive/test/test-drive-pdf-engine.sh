#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ The PDF reader is native, has ONE engine, and every feature #577 promised ║
# ║ is wired end to end                                                       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. #577: the reader was pdf.js in a WebView — one page on one canvas, the whole
# file base64'd across the bridge, a 24 MB ceiling — and was replaced by a native reader on pdfium
# (io.legere:pdfiumandroid). Gradle cannot see most of what makes that replacement true: a feature
# that is written but never reachable from the toolbar, a second engine creeping back in beside the
# first (#463), the pdf.js files still riding in the APK, the manifest still routing "Open with" to
# the old activity. Those are STRINGS and WIRING, so they are checked here as facts about the source.
#
# What is NOT here is behaviour that can be executed: the converter and the page layout are pure
# Kotlin, and app/src/test (PdfConvertTest, PdfLayoutTest) runs them on every ship through
# build.json::tests.unit. This file only holds the pieces that need a device or an emulator to run
# for real — drawing, gestures, the pdfium native calls — to their wiring, and says so. STILL NOT
# COVERED: that a page actually renders sharp at 6x, that a fling feels smooth, that pdfium opens a
# given hostile file. Those need a phone.
#
# NO RIPGREP: grep and python3 only, both in build.json::tests.shell.requires. A missing SOURCE file
# is fatal here, so a renamed file cannot turn the assertions below into a silent no-op.
set -uo pipefail

# CLOUD_ANDROID_ROOT is the test engine's variable for "the checkout to read", honoured so this can be
# pointed at a scratch copy — which is how its mutation proof is taken.
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
TESTSRC="$APP/app/src/test/java/com/diegonmarcos/clouddrive"
ENGINE="$SRC/PdfEngine.kt"
VIEW="$SRC/PdfReaderView.kt"
ACTIVITY="$SRC/PdfReaderActivity.kt"
CONVERT="$SRC/PdfConvert.kt"
LAYOUT="$SRC/PdfLayout.kt"
CONVERSION="$SRC/PdfConversion.kt"
FILES="$SRC/files/FilesScreen.kt"
MAIN="$SRC/MainActivity.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
GRADLE="$APP/app/build.gradle"
BUILD_JSON="$APP/build.json"
ASSETS="$APP/app/src/main/assets"
VENDOR="$APP/app/src/main/assets/vendor"

FAILURES=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for required in "$ENGINE" "$VIEW" "$ACTIVITY" "$CONVERT" "$LAYOUT" "$CONVERSION" "$FILES" "$MAIN" "$MANIFEST" "$GRADLE" "$BUILD_JSON" \
                "$TESTSRC/PdfConvertTest.kt" "$TESTSRC/PdfLayoutTest.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

# has FILE PATTERN MESSAGE — a fixed-string check that says which claim it backs.
has() { if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3   [missing: $2 in $(basename "$1")]"; fi; }
lacks() { if grep -qF -- "$2" "$1"; then fail "$3   [found: $2 in $(basename "$1")]"; else pass "$3"; fi; }

echo "── P1 the engine is DECLARED in data and the build reads it ──"
python3 - "$BUILD_JSON" "$GRADLE" <<'PYTHON'
import json, re, sys
build = json.load(open(sys.argv[1], encoding="utf-8"))
gradle = open(sys.argv[2], encoding="utf-8").read()
engine = (build.get("pdf") or {}).get("engine") or {}
failed = False
def check(ok, message):
    global failed
    print(("  PASS  " if ok else "  FAIL  ") + message)
    failed = failed or not ok
check(engine.get("group") == "io.legere", "build.json::pdf.engine.group is io.legere")
check("pdfiumandroid" in (engine.get("artifacts") or []) and "pdfiumandroid-core" in (engine.get("artifacts") or []),
      "build.json::pdf.engine.artifacts lists the binding AND its core (the constructor names a core class)")
check(re.fullmatch(r"\d+\.\d+\.\d+", engine.get("version") or "") is not None,
      "build.json::pdf.engine.version is a pinned release, not a range or a snapshot (%r)" % engine.get("version"))
check(engine.get("licence") == "Apache-2.0", "the declared engine licence is Apache-2.0 (permissive, nothing vendored)")
check("buildJson.pdf" in gradle and "pdfEngineCoordinates.each { implementation it }" in gradle,
      "app/build.gradle reads the pin from build.json and adds every declared artifact")
check(re.search(r"""['"]io\.legere""", gradle) is None,
      "app/build.gradle names no coordinate of its own — the pin lives in data, once")
check("testImplementation 'junit:junit" in gradle, "app/build.gradle has the JUnit dependency the unit phase needs")
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── P2 exactly ONE engine (#463): one file names the library, no second renderer, no pdf.js left ──"
IMPORTERS="$(grep -rlE 'io\.legere' "$APP/app/src/main/java" | sort)"
if [ "$IMPORTERS" = "$ENGINE" ]; then
    pass "only PdfEngine.kt names io.legere — every other file goes through it"
else
    fail "the pdfium library is named outside PdfEngine.kt: $(echo "$IMPORTERS" | tr '\n' ' ')"
fi
if grep -rqE 'android\.graphics\.pdf|androidx\.pdf|barteksc|tom_roush|mupdf' "$APP/app/src/main/java"; then
    fail "a second in-process PDF renderer is referenced in the Kotlin (android.graphics.pdf / androidx.pdf / barteksc / mupdf)"
else
    pass "no second in-process renderer in the Kotlin"
fi
for old in pdf.min.js pdf.worker.min.js VENDORED.md APACHE-LICENSE.txt; do
    if [ -e "$VENDOR/$old" ]; then fail "the old pdf.js reader's $old is still in assets/vendor"; else pass "assets/vendor/$old is gone (pdf.js no longer rides in the APK)"; fi
done
# #579: the WebView page is gone altogether; no asset and no Kotlin may bring pdf.js or a base64 carrier back.
if [ -d "$ASSETS" ] && grep -rqE 'pdfjsLib|vendor/pdf' "$ASSETS"; then fail "an asset references pdf.js again"; else pass "no asset references pdf.js (the WebView page is gone, #579)"; fi
if grep -rqE 'PDF_READER_BYTE_CEILING|fun readPdf\(|fun takeIncomingPdf\(' "$SRC"; then fail "a base64 PDF carrier is back in the Kotlin"; else pass "no base64 PDF carrier anywhere in the Kotlin — a PDF is a path or a URI, never a string"; fi

echo "── P3 fast open on large files: a descriptor, never the file in memory ──"
has "$ENGINE" "core.newDocument(pfd, password)" "the engine opens the document from a file descriptor"
has "$ENGINE" "openFileDescriptor" "a content:// document is opened through the resolver's descriptor"
lacks "$ENGINE" "readBytes(" "the engine never reads the whole file into memory"
lacks "$ACTIVITY" "readBytes(" "the reader activity never reads the whole file into memory"
has "$ACTIVITY" "io.execute" "the document opens on a background thread (the UI thread never waits on pdfium)"
has "$VIEW" "startSizeScan" "page sizes are read by a background scan while the first pages are already on screen"
has "$VIEW" "lay.setSize(idx[k]" "the scan feeds real page sizes into the layout"
has "$LAYOUT" "guessW" "the layout starts from page 1's size, so a 3,000-page file lays out at once"

echo "── P4 smooth continuous AND paged scrolling ──"
has "$VIEW" "scroller.fling(" "flings run on an OverScroller (momentum)"
has "$VIEW" "fun setPaged" "paged mode is a switch on the same view"
has "$VIEW" "FLING_PAGE_VELOCITY" "a paged fling turns one page"
has "$VIEW" "goToPage(lay.pageAt" "a slow release in paged mode snaps to the nearest page"
has "$VIEW" "drawScrollbar" "a fast scroll thumb for long documents"
has "$VIEW" "dragBar" "the thumb can be dragged"
has "$VIEW" "LruCache<Int, Rendered>" "rendered pages are cached under a memory budget"
has "$VIEW" "requestBase(first - 1" "one page of read-ahead each way"
has "$ACTIVITY" "toggleMode()" "the toolbar switches between continuous and paged"
has "$ACTIVITY" "pagedOn" "the toolbar shows which of the two modes is on"
has "$ACTIVITY" "PAGED" "the chosen mode is remembered"

echo "── P5 pinch zoom that stays sharp ──"
has "$VIEW" "scaleDetector.onTouchEvent(ev)" "pinch zoom: touch events reach the scale detector"
has "$VIEW" "setZoomAround(zoom * detector.scaleFactor" "pinch zoom: the scale factor drives the zoom about the fingers"
has "$VIEW" "override fun onDoubleTap(e: MotionEvent)" "double-tap zoom"
has "$VIEW" "MAX_ZOOM = 6f" "zoom reaches 6x"
has "$VIEW" "requestSharp" "a settled zoom re-renders the visible window at the real zoom"
has "$VIEW" "TILE_BYTE_CEILING" "a sharp tile is bounded, never a page-sized bitmap at 6x"
has "$ENGINE" "a NEGATIVE start renders just a" "the engine renders a window of a page (negative start), which is what keeps 6x affordable"
has "$MANIFEST" "configChanges" "a rotation does not reopen a large document"

echo "── P6 text search, selection, links, outline ──"
has "$ENGINE" "findStart" "search runs on pdfium's own text search"
has "$ENGINE" "fun findOnPage" "search is per page, so it streams"
has "$ACTIVITY" "BATCH_MS" "search results arrive in batches while later pages are still being searched"
has "$ACTIVITY" "(from + k) % n" "search starts at the reader's page and wraps"
has "$VIEW" "MARK_CURRENT" "the current match is drawn differently from the others"
has "$VIEW" "fun showHit" "Next/Previous scroll the match into view"
has "$ENGINE" "getTableOfContents" "the outline comes from the document"
has "$ACTIVITY" "showOutline" "the outline is reachable from the toolbar"
has "$VIEW" "selectWordAt" "long press selects a word"
has "$ACTIVITY" "setPrimaryClip(" "the selected word is copied"
has "$VIEW" "tapLink" "links inside the PDF are tappable"
has "$ACTIVITY" 'SAFE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")' "an external link is only followed for schemes a person expects (never file:, intent:, javascript:)"

echo "── P7 night mode without a re-render ──"
has "$VIEW" "ColorMatrixColorFilter(ColorMatrix(NIGHT_MATRIX))" "night mode is a colour filter on the page paint"
has "$VIEW" "NIGHT_MATRIX" "with a real invert-and-rotate matrix"
if grep -qE '0\.574f, -1\.430f, -0\.144f, 0f, 255f' "$VIEW"; then
    pass "the matrix inverts (offset 255) and keeps hue (the CSS invert+hue-rotate(180deg) coefficients)"
else
    fail "the night matrix is not the invert + hue-rotate one"
fi
python3 - "$VIEW" <<'PYTHON'
import sys
src = open(sys.argv[1], encoding="utf-8").read()
# Highlights and selection are drawn AFTER the page bitmap, with their own paint: a filter on the page
# paint must never tint them. drawPage must draw the bitmap first and drawMarks second.
body = src[src.index("private fun drawPage"):src.index("private fun drawMarks")]
if body.index("canvas.drawBitmap(base.bmp") < body.index("drawMarks(canvas, i)"):
    print("  PASS  highlights are drawn after (and outside) the night filter")
else:
    print("  FAIL  highlights are drawn before the page bitmap, so night mode would hide or tint them"); sys.exit(1)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
has "$ACTIVITY" "NIGHT" "the night setting is remembered"

echo "── P8 share and print ──"
has "$ACTIVITY" "Intent(Intent.ACTION_SEND)" "share goes through the system sheet"
has "$ACTIVITY" "FileProvider.getUriForFile" "share hands over a content:// URI, never file://"
has "$ACTIVITY" "PdfEngine.spool" "a hand-off document is shared from a private copy (its read grant is not ours to pass on)"
has "$ACTIVITY" "manager.print(displayName, object : PrintDocumentAdapter()" "print is wired through the system print service"
has "$ACTIVITY" "getSystemService(Context.PRINT_SERVICE)" "the print service is the platform PrintManager"
has "$ACTIVITY" "callback?.onWriteFinished" "print writes the original PDF bytes"

echo "── P9 conversion still works against the new engine ──"
has "$CONVERSION" "fun convertPdf(" "PdfConversion.convertPdf exists — the ONE conversion path (#579)"
has "$CONVERSION" "engine.pageText(" "conversion reads its text from the SAME engine as the reader"
has "$CONVERSION" "writeText(File(directory, name)" "conversion writes through the core's atomic save path (no second writer)"
has "$ACTIVITY" "PdfConversion.convertPdf(" "the reader's Convert menu calls the same function the Files tab does"
has "$FILES" "EntryAction.CONVERT_PDF" "the Files tab offers Convert on a PDF row"
has "$SRC/files/FilesController.kt" "PdfConversion.convertPdf(" "the Files tab converts through the same path"
python3 - "$CONVERSION" "$CONVERT" <<'PYTHON'
import re, sys
bridge = open(sys.argv[1], encoding="utf-8").read()
convert = open(sys.argv[2], encoding="utf-8").read()
start = bridge.index("fun convertPdf(")
body = bridge[start:]
# The scan refusal is control flow, not opinion: the no-text guard must run before any builder or writer.
guard = body.index("hasNoText(pages)")
build = body.index("PdfConvert.build(")
write = body.index("writeText(")
failed = False
if guard < build and guard < write:
    print("  PASS  the no-text-layer guard runs before the builder and the writer")
else:
    print("  FAIL  a scan could reach the builder or the writer — it would write an empty file"); failed = True
if "no text layer" in convert and "nothing was written" in convert:
    print("  PASS  the refusal names the reason and says nothing was written")
else:
    print("  FAIL  the refusal message lost its reason"); failed = True
# Exactly the four honest targets; docx/xlsx/odt are not pretended.
targets = re.search(r'TARGETS = listOf\(([^)]*)\)', convert).group(1)
if [t.strip().strip('"') for t in targets.split(",")] == ["txt", "md", "html", "csv"]:
    print("  PASS  the converter offers exactly txt, md, html, csv")
else:
    print("  FAIL  the converter's target list changed: " + targets); failed = True
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── P10 the manifest routes every PDF to the ONE reader ──"
python3 - "$MANIFEST" <<'PYTHON'
import re, sys
import xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
acts = {a.get(A + "name"): a for a in root.iter("activity")}
failed = False
def check(ok, message):
    global failed
    print(("  PASS  " if ok else "  FAIL  ") + message)
    failed = failed or not ok
def pdf_filter_label(activity):
    for f in activity.iter("intent-filter"):
        if "application/pdf" in [d.get(A + "mimeType") for d in f.iter("data")]:
            return f.get(A + "label")
    return None
def handles_pdf(activity):
    for f in activity.iter("intent-filter"):
        acts_ = [x.get(A + "name") for x in f.iter("action")]
        mimes = [d.get(A + "mimeType") for d in f.iter("data")]
        schemes = [d.get(A + "scheme") for d in f.iter("data")]
        if "android.intent.action.VIEW" in acts_ and "application/pdf" in mimes:
            return set(s for s in schemes if s)
    return None
reader = acts.get(".PdfReaderActivity")
check(reader is not None, "PdfReaderActivity is declared")
if reader is not None:
    check(reader.get(A + "exported") == "true", "it is exported (cloud-mail's chooser reaches it)")
    check(handles_pdf(reader) == {"content", "file"}, "it takes VIEW application/pdf for content and file (%r)" % handles_pdf(reader))
    check(pdf_filter_label(reader) == "@string/open_in_cloud_drive", "the chooser row other apps see is labelled by a declared string (%r)" % pdf_filter_label(reader))
main = acts.get(".MainActivity")
check(main is not None and handles_pdf(main) is None,
      "MainActivity no longer takes PDFs (one reader, not two doors)")
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
has "$ACTIVITY" "isReadablePlace" "a file:// hand-off is confined to places the app already browses"
has "$MAIN" "PdfReaderActivity.intent(" "the chrome opens a PDF row in the same reader (DriveActions.openPdf)"
has "$FILES" "EditorHook.open(" "the Files tab hands a PDF row to the Editor tab, whose PDF viewer is libs:pdf (#1537: every file tap opens the Editor)"

echo "── P11 the executable tests exist and are wired ──"
python3 - "$TESTSRC" "$BUILD_JSON" <<'PYTHON'
import re, sys, os, json
tests = sys.argv[1]
build = json.load(open(sys.argv[2], encoding="utf-8"))
failed = False
def check(ok, message):
    global failed
    print(("  PASS  " if ok else "  FAIL  ") + message)
    failed = failed or not ok
for name, floor in (("PdfConvertTest.kt", 10), ("PdfLayoutTest.kt", 6)):
    src = open(os.path.join(tests, name), encoding="utf-8").read()
    n = len(re.findall(r"^\s*@Test\s*$", src, re.M))
    check(n >= floor, "%s carries %d @Test methods (floor %d)" % (name, n, floor))
    check(src.count("assert") >= n * 2, "%s asserts something in every test (%d assertions for %d tests)" % (name, src.count("assert"), n))
unit = (build.get("tests") or {}).get("unit") or {}
check(unit.get("enabled") is True and unit.get("task") == "test", "build.json::tests.unit runs gradle test on every ship")
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS   test-drive-pdf-engine"; exit 0; fi
echo "FAIL   test-drive-pdf-engine: $FAILURES group(s) failed"; exit 1
