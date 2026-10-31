#!/usr/bin/env bash
# The toolbar row pages by a whole screen of icons: eleven on page one (ending with the Android keyboard
# settings key), eleven on page two (opening with Cloud Vault), one on page three (#1103). It has now shown four instead of eleven twice, both
# times because something OUTSIDE the paging arithmetic (ToolbarPaging) changed the width a
# key occupies. This runner cannot lay out a view, so the proof is the arithmetic plus the
# two things that feed it:
#
#   T1  every toolbar key gets its OWN page gap. One shared LinearLayout.LayoutParams
#       object was the four-icon bug (the View strip): the margin written for the one key that
#       starts its own page landed on all 22 keys, so a page sized for eleven icons fit four.
#       The Compose strip takes one gap PER KEY from ToolbarPaging.gaps and nothing shared.
#   T2  exactly one place computes the page-start gap of a toolbar key, and it is that alignment.
#   T3  the geometry: page = as many whole keys as the viewport shows, and with the shipped
#       key width on a 411dp portrait screen that is eleven, splitting the 23 default keys
#       11 / 11 / 2. Fails if the key width or the default row grows past the design.
#   T4  #1103: Android keyboard settings sits right before Cloud Vault, Vault right after Switch language,
#       and no key is forced onto a page of its own any more.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
K="$APP/../ab_cloud-libs-shared/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
SV="$J/latin/suggestions/SuggestionStripView.kt"
PV="$J/latin/suggestions/ToolbarPaging.kt"
TU="$J/latin/utils/ToolbarUtils.kt"
DIM="$K/res/values/config.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
absent() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== keyboard toolbar paging: eleven icons on a page, Vault on its own =="

# ── T1 one gap and one width per key ─────────────────────────────────────────
# #1077: the row's layout comes from ToolbarPaging.layout (icons per page = floor(width / cell), the cell from the
# scale); a single gap value applied to every key would be the shared-params bug again.
has "$SV" 'ToolbarPaging.layout(keys, toolbarKeysOnOwnPage, viewport, baseCellPx, scale)' "T1 the layout comes from ToolbarPaging.layout"
has "$SV" 'layout.gapBefore\[i\]' "T1 each key is given ITS OWN gap"
has "$SV" 'layout.keyWidth\[i\]' "T1 and its own width, so a page is exactly one screen"
absent "$SV" 'gaps.max()' "T1 no single shared gap for the whole row"
absent "$SV" 'secondRowKeys.add(original)' "T1 the second-row copy does not share the original key's icon"

# ── T2 one writer of the page-start gap ──────────────────────────────────────
n=$(grep -c '\] = gapSlotsForOwnPage(' "$PV" "$SV" | awk -F: '{s+=$2} END {print s+0}')
[ "$n" = 1 ] && ok "T2 exactly one page-start gap writer" \
             || bad "T2 $n page-start gap writers on toolbar keys — gaps from two places cannot converge"
has "$PV" 'gaps\[i\] = gapSlotsForOwnPage(slot, perPage)' "T2 and it is the own-page alignment"

# ── T3 the arithmetic, against the shipped key width and default row ─────────
if python3 - "$DIM" "$TU" <<'EOF'
import re, sys
dim, tu = (open(p).read() for p in sys.argv[1:3])

key_dp = int(re.search(r'"config_suggestions_strip_edge_key_width">(\d+)dp', dim).group(1))
row = re.search(r'val default = listOf\((.*?)\n    \)', tu, re.S).group(1)
keys = [k for k in re.findall(r'\b([A-Z][A-Z_]+)\b', re.sub(r'//[^\n]*', '', row))]
m_own = re.search(r'toolbarKeysOnOwnPage: Set<ToolbarKey> = (?:emptySet\(\)|setOf\(([^)]*)\))', tu)
assert m_own, 'toolbarKeysOnOwnPage is gone'
own_page = set(re.findall(r'ToolbarKey\.([A-Z_]+)', m_own.group(1) or ''))

def slot_start(slot, per, page):
    return (slot // per) * page + (slot % per) * page // per

def layout(viewport, base, scale):
    cell = max(1, int(base * min(max(scale, 0.85), 1.3) + 0.5))
    per = max(1, viewport // cell)
    slot, starts, widths = 0, [], []
    for k in keys:
        if k in own_page: slot += (per - slot % per) % per
        starts.append(slot_start(slot, per, viewport))
        widths.append(slot_start(slot + 1, per, viewport) - starts[-1])
        slot += 1
    return per, starts, widths

# A 1188px viewport at 3 px/dp is 396dp: 11 cells of 36dp. At every scale the keys sit inside one page.
for scale in (0.85, 1.0, 1.15, 1.3):
    per, starts, widths = layout(1188, key_dp * 3, scale)
    assert per == 1188 // max(1, int(key_dp * 3 * scale + 0.5)), f'icons per page at scale {scale}'
    for k, st, w in zip(keys, starts, widths):
        assert st // 1188 == (st + w - 1) // 1188, f'{k} straddles a page at scale {scale}'
per, starts, widths = layout(1188, key_dp * 3, 1.0)
pages = {}
for k, st in zip(keys, starts): pages.setdefault(st // 1188, []).append(k)
counts = [len(pages[p]) for p in sorted(pages)]
assert per == 11 and counts == [11, 11, 2], f'pages hold {counts}, the design is 11 / 11 / 2'
assert pages[0][-1] == 'ANDROID_SETTINGS' and pages[1][0] == 'VAULT', f'page 1 ends {pages[0][-1]}, page 2 opens {pages[1][0]}'
assert 'ANSWER' in pages[0] and 'EMOJI' not in pages[0] and pages[1][1] == 'EMOJI', f'#1384: Draft Answer belongs on page 1 and Emoji opens page 2 after Vault, got {pages[0]} / {pages[1][:2]}'
print(f'{key_dp}dp keys, {per}/page, split {counts}')
EOF
then ok "T3 the page arithmetic gives 11 / 11 / 2 and holds at every scale"; else bad "T3 page arithmetic"; fi

# ── T4 Vault's place in the row ──────────────────────────────────────────────
has "$TU" 'toolbarKeysOnOwnPage: Set<ToolbarKey> = emptySet()' "T4 no key is forced onto its own page"
has "$TU" 'LANGUAGE_SWITCH, ANDROID_SETTINGS, VAULT,\|^        ANDROID_SETTINGS, VAULT, EMOJI,$' "T4 Android keyboard settings, then Vault (then Emoji, #1384), follow Switch language"

echo
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" = 0 ]
