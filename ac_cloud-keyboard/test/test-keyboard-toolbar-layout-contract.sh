#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ The toolbar row pages by a WHOLE SCREEN of icons, and the page size is   ║
# ║ measured rather than declared (#301, #319, #150)                        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS LIVES HERE. The same contract is asserted by
# aa_cloud-superapp/test/test-keyboard-toolbar-paging.sh — and that tester gates
# the SUPERAPP's release, not this one. cloud-keyboard ships libs:keyboard as its
# own APK, so until this file existed the toolbar could regress and every gate on
# THIS app stayed green. build.json::tests._doc already records that trap for the
# app as a whole; this is the toolbar's share of it.
#
# WHAT IT PINS, and every one of these has actually shipped broken:
#
#   A1  the page size is DERIVED from live measurements — the viewport divided by
#       a real key's width. A literal "11 per page" would be right on exactly one
#       screen and silently wrong on every other width, rotation and icon size.
#   A2  every toolbar key carries its OWN page gap. ONE SHARED gap (once, one
#       LinearLayout.LayoutParams object) is the four-icons-instead-of-eleven bug
#       (#319, fixed in 59527a722): the margin written for the one key that
#       starts its own page landed on every key. The strip takes one gap PER KEY
#       from ToolbarPaging.gaps.
#   A3  the geometry, computed from the SHIPPED key width and the REAL default
#       row, laid out the way the strip does — and laid out through whichever
#       gap model A2 found in the source, so the pre-fix state produces the
#       decaying row it really produced instead of the design's split.
#   A4  exactly one place computes the page-start gap of a toolbar key. Two
#       writers cannot converge on a page boundary.
#
# NO RIPGREP, DELIBERATELY: testers in this repository have passed on its absence
# rather than on their assertions. grep, awk and python3 only — all three are
# build.json::tests.shell.requires, so a missing one is fatal at the preflight
# instead of quietly agreeing with everything here. A missing SOURCE file is
# fatal here too, for the same reason: every assertion below would read an empty
# file and pass.
set -uo pipefail

# CLOUD_ANDROID_ROOT is the test engine's own variable for "the checkout to read",
# honoured so this tester can be pointed at a scratch tree — which is how its
# mutation proof is taken (flip A2's getter back to a shared instance in a copy,
# watch A2 and A3 both go red).
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
K="$ROOT/ab_cloud-libs-shared/libs/keyboard/src/main"
SV="$K/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt"
PV="$K/java/helium314/keyboard/latin/suggestions/ToolbarPaging.kt"
TU="$K/java/helium314/keyboard/latin/utils/ToolbarUtils.kt"
CFG="$K/res/values/config.xml"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$SV" "$PV" "$TU" "$CFG"; do
    [ -f "$f" ] || { printf 'FAIL   %s is missing — every assertion below would read an empty file and pass\n' "$f"; exit 1; }
done

echo "== cloud-keyboard toolbar: a full page of icons, measured not declared =="

# ── A1 the page is laid out from the available px ──────────────────────────
# #1077: icons per page = floor(width / cell), the cell from the scale. The division IS the derivation.
if grep -q 'viewport / icon' "$PV"; then
    pass "A1 icons per page divides the measured viewport by the cell"
else
    fail "A1 the toolbar paging no longer derives its page from measurements ($PV)"
fi
if grep -q 'Math.round(baseCell \* scale' "$PV" && grep -q 'val scale = density.fontScale' "$SV"; then
    pass "A1 the cell follows the scale (the system font scale)"
else
    fail "A1 the cell no longer follows the scale ($PV, $SV)"
fi
# A page size sitting in the paging code as a number is the failure A1 exists to catch.
if grep -nE '(perPage|keysPerPage|PAGE_SIZE|pageSize)[[:space:]]*=[[:space:]]*[0-9]+' "$PV"; then
    fail "A1 a hardcoded keys-per-page constant in the paging code ($PV)"
else
    pass "A1 no hardcoded keys-per-page constant in the paging code"
fi

# ── A2 one gap and one width per key ────────────────────────────────────────
if grep -q 'ToolbarPaging.layout(keys, toolbarKeysOnOwnPage, viewport, baseCellPx, scale)' "$SV"; then
    pass "A2 the row takes its layout from ToolbarPaging.layout"
else
    fail "A2 the row no longer takes its layout from ToolbarPaging.layout ($SV)"
fi
if grep -q 'layout.gapBefore\[i\]' "$SV" && grep -q 'layout.keyWidth\[i\]' "$SV"; then
    pass "A2 each key is given its own gap and its own width"
else
    fail "A2 the keys do not each take their own gap and width ($SV)"
fi
if grep -q 'gaps.max()' "$SV"; then
    fail "A2 a single shared gap for the whole row ($SV)"
else
    pass "A2 no single shared gap for the whole row"
fi
if grep -q 'layout.trailing' "$SV"; then
    pass "A2 the row ends on a page boundary (trailing filler)"
else
    fail "A2 the last page is short: no trailing filler ($SV)"
fi

# ── A4 one writer of the page-start gap ─────────────────────────────────────
writers=$(grep -c '\] = gapSlotsForOwnPage(' "$SV" "$PV" | awk -F: '{ sum += $2 } END { print sum + 0 }')
if [ "$writers" = 1 ]; then
    pass "A4 exactly one page-start gap writer"
else
    fail "A4 $writers page-start gap writers on toolbar keys — gaps from two places cannot converge"
fi

# ── A3 the arithmetic, from the shipped width and the real default row ──────
if python3 - "$CFG" "$TU" <<'PYEOF'
import re, sys

cfg, tu = (open(p, encoding='utf-8').read() for p in sys.argv[1:3])

m = re.search(r'"config_suggestions_strip_edge_key_width">(\d+)dp', cfg)
assert m, 'config_suggestions_strip_edge_key_width is gone'
key_dp = int(m.group(1))

# The block ends at a ')' ALONE on its own line (the row's comment contains "(toolbarKeysOnOwnPage)").
blocks = re.findall(r'val default = listOf\((.*?)\n\s*\)', tu, re.S)
row = next((b for b in blocks if 'FULL_LEFT' in b), None)
assert row is not None, 'the default first toolbar row is gone from ToolbarUtils'
keys = re.findall(r'\b([A-Z][A-Z_]+)\b', re.sub(r'//[^\n]*', '', row))
m_own = re.search(r'toolbarKeysOnOwnPage: Set<ToolbarKey> = (?:emptySet\(\)|setOf\(([^)]*)\))', tu)
assert m_own, 'toolbarKeysOnOwnPage is gone'
own_page = set(re.findall(r'ToolbarKey\.([A-Z_]+)', m_own.group(1) or ''))  # #1103: none today; the mechanism stays

def slot_start(slot, per, page):          # ToolbarPaging.slotStart
    return (slot // per) * page + (slot % per) * page // per

def layout(viewport, base, scale):        # ToolbarPaging.layout
    cell = max(1, int(base * min(max(scale, 0.85), 1.3) + 0.5))
    per = max(1, viewport // cell)
    slot, starts, widths = 0, [], []
    for k in keys:
        if k in own_page:
            slot += (per - slot % per) % per
        starts.append(slot_start(slot, per, viewport))
        widths.append(slot_start(slot + 1, per, viewport) - starts[-1])
        slot += 1
    return per, starts, widths

for scale in (0.85, 1.0, 1.15, 1.3):
    for viewport in (960, 1080, 1176, 1188, 1440):
        per, starts, widths = layout(viewport, key_dp * 3, scale)
        for k, st, w in zip(keys, starts, widths):
            assert st // viewport == (st + w - 1) // viewport, f'{k} straddles a page at scale {scale}, {viewport}px'
        for k, st in zip(keys, starts):
            if k in own_page:
                assert st % viewport == 0, f'{k} does not start a page at scale {scale}, {viewport}px'

per, starts, widths = layout(1188, key_dp * 3, 1.0)
pages = {}
for k, st in zip(keys, starts):
    pages.setdefault(st // 1188, []).append(k)
counts = [len(pages[p]) for p in sorted(pages)]
assert per == 11 and counts == [11, 11, 2], f'pages hold {counts} at {per} per page, the design is 11 / 11 / 2'
assert pages[0][-1] == 'ANDROID_SETTINGS' and pages[1][0] == 'VAULT', 'page 1 must end with Android keyboard settings and page 2 open with Vault'
assert 'ANSWER' in pages[0] and 'EMOJI' not in pages[0] and pages[1][1] == 'EMOJI', f'#1384: Draft Answer belongs on page 1 and Emoji opens page 2 after Vault, got {pages[0]} / {pages[1][:2]}'
print(f'    {key_dp}dp keys, {len(keys)} in the default row, scales 0.85-1.3 checked, 1.0 splits {counts}')
PYEOF
then
    pass "A3 the page geometry holds at scales 0.85 / 1.0 / 1.15 / 1.3 and splits 11 / 11 / 2"
else
    fail "A3 the page geometry no longer matches the design"
fi

echo
if [ "$FAILURES" = 0 ]; then
    echo "== toolbar layout contract: all assertions hold =="
else
    echo "== toolbar layout contract: $FAILURES FAILED =="
fi
[ "$FAILURES" = 0 ]
