#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 3 — libs:file-editor is a FULL editor: the buffer's verbs are  ║
# ║ reached from the screen, the colouring catalogue is data the tests read, ║
# ║ and a save never truncates the file                                      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The buffer (undo/redo, find/replace, geometry, line endings, encodings) is
# proven by EditorBufferTest and the colouring by SyntaxHighlighterTest against
# the REAL asset, in every drive ship's unit phase. This tester pins the shape:
#
#   F1  every editor capability the brief implies is a verb on EditorBuffer
#       (undo, redo, find, findNext, findPrevious, replace, replaceAll,
#       lineOf, columnOf, offsetOfLine) …
#   F2  … and each is reached from FileEditorScreen.kt — a capability with no
#       button is one the owner does not have.
#   F3  the colouring catalogue is DATA: the screen opens the asset by the ONE
#       name the highlighter declares, the JVM test reads that same file, and
#       every language entry has a non-empty extension list (an entry that can
#       never be matched is dead data).
#   F4  the save path is atomic — the document writes beside the target and
#       renames — and the screen guards unsaved changes on close.
#   F5  the buffer and highlighter import nothing from android.*: they are the
#       JVM-tested half.
#
# OWN-SOURCE ONLY: everything read here is under a module dir this app's
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:file-editor"]["dir"])))' "$APP/build.json" "$APP")"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/fileeditor"
BUFFER="$PKG/EditorBuffer.kt"
HIGHLIGHTER="$PKG/SyntaxHighlighter.kt"
SCREEN="$PKG/FileEditorScreen.kt"
ASSET_DIR="$MOD/src/main/assets"
BUFFER_TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/fileeditor/EditorBufferTest.kt"
HL_TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/fileeditor/SyntaxHighlighterTest.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BUFFER" "$HIGHLIGHTER" "$SCREEN" "$BUFFER_TEST" "$HL_TEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

VERBS="undo redo find findNext findPrevious replace replaceAll lineOf columnOf offsetOfLine"
echo "── F1/F2 every buffer verb is declared AND reached from the screen ──"
for verb in $VERBS; do
    if grep -qE "^\s*fun $verb\(" "$BUFFER"; then pass "buffer declares $verb()"; else fail "buffer lacks fun $verb("; fi
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches .$verb("; else fail "screen never calls .$verb("; fi
    if grep -qE "\.$verb\(" "$BUFFER_TEST"; then pass "test exercises .$verb("; else fail "no test calls .$verb("; fi
done

echo "── F3 the colouring catalogue is one named runtime dataset (#1547: served by Cloud Store, no lib asset), read by screen and test alike ──"
DS_NAME="$(grep -oE 'const val DATASET = "[^"]+"' "$HIGHLIGHTER" | sed 's/.*= "//; s/"$//')"
DS_FILE="$ROOT/data/fleet/$DS_NAME.json"
if [ -n "$DS_NAME" ] && [ -f "$DS_FILE" ]; then pass "dataset $DS_NAME is served from data/fleet where the highlighter says"; else fail "SyntaxHighlighter.DATASET ('$DS_NAME') does not name a file in data/fleet"; fi
if [ -z "$(find "$ASSET_DIR" -type f 2>/dev/null)" ]; then pass "the lib carries no data asset"; else fail "file-editor still ships assets: $(ls "$ASSET_DIR")"; fi
if grep -qE "FleetData\.get\(.*SyntaxHighlighter\.DATASET" "$SCREEN"; then pass "screen reads the dataset through FleetData"; else fail "screen does not read SyntaxHighlighter.DATASET through FleetData"; fi
if grep -qE "SyntaxHighlighter\.DATASET" "$HL_TEST"; then pass "test reads the same served file"; else fail "SyntaxHighlighterTest does not read the served dataset"; fi
python3 - "$DS_FILE" <<'PYTHON' && pass "every language has an id and a non-empty extension list; no extension is claimed twice" || fail "catalogue shape (see above)"
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
seen = {}
bad = 0
for l in d["languages"]:
    if not l.get("id") or not l.get("extensions"):
        print("    entry without id/extensions: %r" % l); bad += 1
    for e in l.get("extensions", []):
        if e in seen: print("    extension %s claimed by %s and %s" % (e, seen[e], l.get("id"))); bad += 1
        seen[e] = l.get("id")
sys.exit(1 if bad else 0)
PYTHON

echo "── F4 saves are atomic and the close is guarded ──"
if grep -qE "renameTo\(file\)" "$BUFFER"; then pass "EditorDocument.write renames a temp file into place"; else fail "write() does not rename a temp file into place"; fi
if grep -qE "cloud-drive-tmp" "$BUFFER_TEST"; then pass "the test asserts no temp file is left behind"; else fail "no test checks the temp file is gone after write"; fi
if grep -qE "confirmClose = true" "$SCREEN" && grep -qE "Unsaved changes" "$SCREEN"; then pass "unsaved changes are confirmed before close"; else fail "no unsaved-changes guard on close"; fi

echo "── F5 the JVM-tested half is pure ──"
for f in "$BUFFER" "$HIGHLIGHTER"; do
    if grep -qE "^import android\." "$f"; then fail "$(basename "$f") imports android.*"; else pass "$(basename "$f") has no android.* import"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-file-editor-engine: all checks passed"; else echo "test-drive-file-editor-engine: $FAILURES check(s) FAILED"; exit 1; fi
