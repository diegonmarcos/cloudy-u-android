#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ cloud-writer has a design system, and has not slid back out of one   ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# WHAT THIS FILE CAN AND CANNOT PROVE, said first so no one reads more into a
# green than is there. It proves the SOURCE still has the properties the UI work
# gave it. It does not prove the screen looks right — only a device can do that,
# and "a Material component is imported" is not "a screen rendered". Nothing
# below asserts that a composable exists, because that assertion is worthless.
#
# What it does assert is the set of things that would silently UNDO the work:
#
#   T1  the toolchain restated in build.gradle still matches build.json. The
#       plugins{} block takes literals only, so the versions cannot be
#       interpolated out of the JSON and there are deliberately two copies. A
#       mirror nothing compares is a copy, and a copy is how a bump lands in the
#       JSON and never reaches the build.
#   T2  the hand-rolled palette is gone and stays gone. Four hex literals were
#       copy-pasted into two files and had already drifted apart. A
#       Color.parseColor in this application is a colour that ignores the theme,
#       and on the owner's black Power Saving phone that is a lit grey rectangle.
#   T3  no raw-pixel spacing. Every margin in this app used to be
#       setPadding(36, ...) — 36 PHYSICAL PIXELS, so about 18dp on a 2x phone and
#       9dp on a 4x one. The spacing was a different size on every device and
#       roughly half the intended one on a modern Samsung.
#   T4  every screen is drawn INSIDE CloudWriterTheme. A setContent that forgets
#       it compiles, runs, and draws the stock purple Material palette in light
#       mode on a phone that is in dark mode — which is the single most visible
#       way this work could regress and the least likely to be noticed in a diff.
#   T5  the price table's cells are still single-line. Task 214 is one line per
#       model; restyling is exactly how a cell is let go multi-line "so it fits",
#       and the existing settings tester checks the column widths but not this.
#   T6  the four home-screen cards each have a description, in BOTH locales. The
#       cards are the point of the home-screen change and their descriptions are
#       new strings — task 221 was 44 English labels reaching a Spanish phone.
#
# FAIL CLOSED. Every check below treats a file it cannot read, a value it cannot
# parse or a tool it cannot run as a FAILURE, never as a pass. Four testers in
# this repository once passed only because ripgrep was absent and their `rg` call
# failed open; nothing here shells out to anything but python3 and grep.
#
# COMMENTS ARE STRIPPED BEFORE MATCHING. A grep that matches its own explanatory
# prose is a real defect shipped in this repository more than once — including in
# this application's sibling tester. The prose above names Color.parseColor and
# setPadding, and would satisfy T2 and T3 if the file were read raw.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
APP="$ROOT/ac_cloud-writer"
SRC="$APP/app/src/main/java/com/diegonmarcos/cloudwriter"
RES="$APP/app/src/main/res"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

command -v python3 >/dev/null || { echo "FAIL   python3 is absent; build.json and both string files would go unread"; exit 1; }
[ -d "$SRC" ] || { echo "FAIL   $SRC is missing — every check below would read nothing and pass"; exit 1; }

# Same stripper as test-cloud-writer-settings-pages.sh, and for the same reason.
# A `//` preceded by a colon is a URL scheme, not a comment.
code() {
    sed -e 's,^[[:space:]]*//.*,,' -e 's,\([^:]\)//.*,\1,' "$@" | awk '
        /\/\*/ { inblock = 1 }
        inblock == 0 { print }
        /\*\// { inblock = 0 }
    '
}

ALL_KT="$(mktemp)"; trap 'rm -f "$ALL_KT"' EXIT INT TERM
code "$SRC"/*.kt "$SRC"/ui/*.kt >"$ALL_KT"
[ -s "$ALL_KT" ] || { echo "FAIL   stripping comments left nothing to read — the stripper is broken, not the source"; exit 1; }

# ── T1  the toolchain is not two numbers that can drift ─────────────────────

python3 - "$APP" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = 0
try:
    tc = json.load(open(os.path.join(app, "build.json")))["toolchain"]
except Exception as exc:
    print("FAIL   T1 build.json::toolchain is unreadable (%s); the versions below cannot be checked" % exc)
    sys.exit(1)

try:
    root = open(os.path.join(app, "build.gradle")).read()
    appg = open(os.path.join(app, "app", "build.gradle")).read()
except Exception as exc:
    print("FAIL   T1 a build.gradle is unreadable (%s)" % exc)
    sys.exit(1)

# (what build.json calls it, where it is restated, how it is written there)
PINS = [
    ("kotlin", root, "org.jetbrains.kotlin.android"),
    ("kotlin", root, "org.jetbrains.kotlin.plugin.compose"),
    ("agp", root, "com.android.application"),
    ("agp", root, "com.android.library"),
]
for key, text, plugin in PINS:
    want = tc.get(key)
    if not want:
        print("FAIL   T1 build.json::toolchain.%s is missing" % key); bad += 1; continue
    found = re.search(r"id\s+'%s'\s+version\s+'([^']+)'" % re.escape(plugin), text)
    if not found:
        print("FAIL   T1 %s is not declared with a literal version in build.gradle" % plugin); bad += 1
    elif found.group(1) != want:
        print("FAIL   T1 %s is pinned to %s but build.json::toolchain.%s says %s"
              % (plugin, found.group(1), key, want)); bad += 1

bom = tc.get("compose_bom")
if not bom:
    print("FAIL   T1 build.json::toolchain.compose_bom is missing"); bad += 1
elif ("androidx.compose:compose-bom:%s" % bom) not in appg:
    print("FAIL   T1 app/build.gradle does not resolve the compose BOM build.json pins (%s)" % bom); bad += 1

if bad == 0:
    print("ok     T1 build.gradle restates exactly the versions build.json::toolchain declares")
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── T2  the hand-rolled palette is gone ─────────────────────────────────────

if grep -q 'Color.parseColor' "$ALL_KT"; then
    fail "T2 Color.parseColor is back. Colours belong to MaterialTheme.colorScheme; a literal ignores light mode, ignores the wallpaper palette and ignores the owner's black Power Saving theme."
else
    pass "T2 no hardcoded hex palette — colour comes from the theme"
fi

# ── T3  spacing is in dp, not in raw pixels ─────────────────────────────────

if grep -q 'setPadding(' "$ALL_KT"; then
    fail "T3 setPadding() is back, and it takes PIXELS. That is how every margin in this app became a different physical size on every phone."
else
    pass "T3 no raw-pixel spacing — Compose has no unit-less path"
fi

# ── T4  every screen is drawn inside the theme ──────────────────────────────

python3 - "$SRC" <<'PY'
import glob, os, re, sys
src = sys.argv[1]
bad = 0
drawing = []
for path in sorted(glob.glob(os.path.join(src, "*.kt")) + glob.glob(os.path.join(src, "ui", "*.kt"))):
    body = open(path).read()
    # setContent { — the call, not setContentTitle/Text/Intent on a notification (#800 Listen.kt).
    if not re.search(r"\bsetContent\s*\{", body):
        continue
    drawing.append(os.path.basename(path))
    # The theme must be the thing INSIDE setContent, not merely mentioned in the
    # file: an import, or a comment naming it, is not a screen that is themed.
    if not re.search(r"setContent\s*\{\s*(//[^\n]*\n\s*)*CloudWriterTheme", body):
        print("FAIL   T4 %s calls setContent without CloudWriterTheme immediately inside it. "
              "It would compile and draw the stock Material palette." % os.path.basename(path))
        bad += 1

# FAIL CLOSED: finding nothing at all means this check read the wrong directory,
# which must not be reported as every screen being themed.
if len(drawing) < 2:
    print("FAIL   T4 only %d file(s) call setContent; this app has a main screen and a settings "
          "base that both do, so this check is reading the wrong place" % len(drawing))
    bad += 1
elif bad == 0:
    print("ok     T4 every screen that draws (%s) draws inside CloudWriterTheme" % ", ".join(drawing))
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── T5  the price table is still one line per model (task 214) ──────────────

TABLE="$(code "$SRC/WriterSettingsUi.kt")"
if grep -q 'maxLines = 1' <<<"$TABLE"; then
    pass "T5/214 the price table's cells are single-line, so a model cannot wrap onto a second row"
else
    fail "T5/214 the price table's cells are no longer capped at one line. One cell wrapping is enough to take a model onto two rows, which is the layout task 214 was raised about."
fi

# ── T6  the home screen's four cards speak Spanish too ──────────────────────

python3 - "$SRC" "$RES" <<'PY'
import re, sys, os
src, res = sys.argv[1], sys.argv[2]
bad = 0
try:
    main = open(os.path.join(src, "MainActivity.kt")).read()
except Exception as exc:
    print("FAIL   T6 MainActivity.kt is unreadable (%s)" % exc); sys.exit(1)

used = sorted(set(re.findall(r"R\.string\.(settings_screen_\w*_summary)", main)))
# FAIL CLOSED: one description per page card, the cards COUNTED rather than assumed (#800 added
# Routes, the fifth), and never fewer than five. Finding none would otherwise report "every
# description is translated" about an empty set.
cards = len(re.findall(r"Page\(\s*R\.string\.settings_screen_", main))
if cards < 5 or len(used) != cards:
    print("FAIL   T6 the home screen names %d page description(s) (expected %d, one per card): %s"
          % (len(used), max(cards, 5), ", ".join(used) or "none"))
    bad += 1

def keys(path):
    try:
        return set(re.findall(r'<string name="([^"]+)"', open(path, encoding="utf-8").read()))
    except Exception as exc:
        print("FAIL   T6 %s is unreadable (%s)" % (path, exc))
        return None

en = keys(os.path.join(res, "values", "strings.xml"))
es = keys(os.path.join(res, "values-es", "strings.xml"))
if en is None or es is None:
    sys.exit(1)

for key in used:
    if key not in en:
        print("FAIL   T6 %s is used by a card but is not in values/strings.xml" % key); bad += 1
    if key not in es:
        print("FAIL   T6 %s has no Spanish; that card would read English on his phone" % key); bad += 1

# And the whole file, not just the new keys: task 221 was 44 of them at once.
missing = sorted(en - es)
extra = sorted(es - en)
if missing:
    print("FAIL   T6 %d string(s) have no Spanish: %s" % (len(missing), ", ".join(missing))); bad += 1
if extra:
    print("FAIL   T6 values-es carries %d string(s) values/ does not: %s" % (len(extra), ", ".join(extra))); bad += 1

if bad == 0:
    print("ok     T6 every card description exists in values/ and values-es/, and the two files are in parity")
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── T7  the type ramp, not sp literals (#1532) ──────────────────────────────

if grep -Eq '(^|[^A-Za-z0-9_.])[0-9]+(\.[0-9]+)?\.sp\b' "$ALL_KT"; then
    fail "T7 an sp literal is in the source. Sizes come from the type ramp (MaterialTheme.typography roles, KitDensity) and from em factors; a literal sp is a size the ramp does not know and the font scale cannot be designed around."
else
    pass "T7 no sp literal: every text size is a ramp role or an em factor"
fi

# ── T8  Material Symbols (rounded), consistently ────────────────────────────

if grep -Eq 'Icons\.(AutoMirrored\.)?(Filled|Outlined|Sharp|TwoTone)\.|material\.icons\.(automirrored\.)?(filled|outlined|sharp|twotone)\.' "$ALL_KT"; then
    fail "T8 a non-rounded icon is in the source. One family only: Icons.Rounded (Material Symbols rounded), so no screen mixes weights."
else
    pass "T8 every icon is Icons.Rounded"
fi

# ── T9  the design is token-driven and the contrast is held (#1532) ─────────

THEME="$(code "$SRC/ui/Theme.kt")"
DESIGN_TEST="$APP/core/src/test/kotlin/com/diegonmarcos/cloudwriter/core/DesignTest.kt"
if grep -q 'TokenSet.DARK.toScheme' <<<"$THEME" && grep -q 'TokenSet.LIGHT.toScheme' <<<"$THEME" \
   && [ -f "$DESIGN_TEST" ] && grep -q 'fun everyTextPairReachesAA' "$DESIGN_TEST" && grep -q 'Contrast.AA_TEXT' "$DESIGN_TEST"; then
    pass "T9 both schemes are painted from core TokenSet, and DesignTest holds every text pair at WCAG AA"
else
    fail "T9 the theme no longer paints from core TokenSet, or the AA contrast test (DesignTest.everyTextPairReachesAA) is gone. A palette nothing measures drifts below AA the first time someone nudges a colour."
fi

# ── T10 buttons are the component set's, not bare Material ones (#1532) ─────

MAINKT="$(code "$SRC/MainActivity.kt")"
if grep -Eq '(^|[^A-Za-z.])(Button|FilledTonalButton|OutlinedButton|ElevatedButton)\(' <<<"$MAINKT"; then
    fail "T10 a bare Material button is in MainActivity. Use WriterButton (filled / tonal / text, 40 to 48 dp, icon plus label) so every button has the same height, radius and states."
else
    pass "T10 MainActivity draws its buttons with WriterButton"
fi

# ── verdict ─────────────────────────────────────────────────────────────────

if [ "$FAILURES" -eq 0 ]; then
    echo
    echo "PASS   cloud-writer's design system is in place and has not been undone"
    exit 0
fi
echo
echo "FAILED $FAILURES assertion(s)"
exit 1
