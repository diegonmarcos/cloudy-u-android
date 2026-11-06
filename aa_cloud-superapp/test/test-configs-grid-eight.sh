#!/usr/bin/env bash
# Tester: the Configs page grid is 8 icons per row, and a `separator` page is an INLINE
# character that takes no column slot. Static (no device, no build); the 360 dp arithmetic is
# GridColumnsTest.configsGridIsEightWideAndItsIconFitsAt360dp (JVM suite).
#   G1 build.json declares the config section grid_columns 8 (not the Cloud stepper)
#   G2 sectionGrid hands the section's count on; TileGridFragment draws the fixed 5-across small tile (owner, 6th ask)
#   G3 the separator is a thin TileDivider overlay on the neighbouring tile's edge (#1069), outside the slot count
#   G4 Network reads Firewall, DNS, Webserver, Cloud Mesh, Apps Access (Cloud Store > Access), ADB Shell, WiFi (#1263)
#   G5 the Home Configs row skips the separator (no cell there either)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$KT/TileGridFragment.kt" "$KT/LauncherNavController.kt" "$KT/Sections.kt" "$KT/GroupedTilesFragment.kt"; do
  [ -f "$f" ] || { echo "  ABORT: missing $f"; exit 2; }; done

echo "== G1: declared 8 =="
python3 - "$APP/build.json" <<'PY' && ok "config section grid_columns = 8" || bad "config section does not declare grid_columns 8"
import json, sys
s = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
sys.exit(0 if s.get('grid_columns') == 8 else 1)
PY

echo "== G2: the count reaches the grid =="
grep -q 'columns = section.gridColumns' "$KT/LauncherNavController.kt" && ok "sectionGrid passes section.gridColumns" || bad "sectionGrid does not pass the section's columns"
grep -q 'o.optInt("grid_columns"' "$KT/Sections.kt" && ok "Sections parses grid_columns" || bad "Sections does not parse grid_columns"
# Owner (6th ask): every tile grid is the ONE small tile, CHIP_COLUMNS (5) across, so all Configs fit one A37 screen.
grep -q 'val cols = CHIP_COLUMNS' "$KT/TileGridFragment.kt" && grep -q 'const val CHIP_COLUMNS = 5' "$KT/TileGridFragment.kt" \
  && ok "TileGridFragment draws CHIP_COLUMNS (5) across" || bad "TileGridFragment is not 5 across"
grep -q 'CompactTile(' "$KT/TileGridFragment.kt" && ! grep -q 'LauncherTile(' "$KT/TileGridFragment.kt" \
  && ok "the grid draws the small CompactTile, never the old big tile" || bad "grid draws the old big tile"

# #1075: Configs itself is no longer a TileGridFragment: it is ConfigsFragment, Cloud > Apps' tile strip wrapped onto lines.
grep -q 'ConfigsFragment.newInstance(tiles)' "$KT/LauncherNavController.kt" && grep -q 'TileStrip(' "$KT/ConfigsFragment.kt" \
  && ok "Configs is drawn by ConfigsFragment through the Apps TileStrip" || bad "Configs is not drawn through the shared Apps tile strip"

echo "== G3: the separator is inline =="
# #1069: the separator is the composable TileDivider (LauncherTiles.kt): a 1 dp rule drawn as an OVERLAY on the edge of the
# neighbouring tile (never a row child), so it takes no slot and no width, centred on the icon line, not clickable.
sep="$(awk '/^internal fun TileDivider/{f=1} f{print} f&&/^}$/{exit}' "$KT/LauncherTiles.kt")"
echo "$sep" | grep -q 'size(1.dp, SEP_HEIGHT)' && ! echo "$sep" | grep -qE 'weight\(|fillMaxWidth|clickable|Text\(' && ok "separator is a 1 dp rule (no weight, no fill, no slot, not clickable)" || bad "separator is not wrap_content"
# Data Apps' separators (GroupedTilesFragment) and this grid's are the ONE TileDivider composable, so they cannot differ
# in ink, centring or accessibility; the faint 0x66 ink and the a11y-hidden semantics are pinned on it below.
grep -q 'TileDivider(' "$KT/GroupedTilesFragment.kt" && grep -q 'TileDivider(' "$KT/TileGridFragment.kt" \
  && ok "Data Apps and the section grid draw their separators with the same TileDivider" || bad "a grid draws its own separator again"
echo "$sep" | grep -q 'alpha = 0.4f' && ok "the separator ink is the faint 0x66 (40%) ink" || bad "separator ink is not the faint 0x66 ink"
echo "$sep" | grep -q 'iconCentreY' && ok "the separator is centred on the icon line" || bad "separator is not centred on the icon line"
echo "$sep" | grep -q 'clearAndSetSemantics' && ok "the separator is hidden from accessibility" || bad "separator is not hidden from accessibility"
grep -q 'if (!sep) slots++' "$KT/TileGridFragment.kt" && grep -q 'if (!sep && slots == cols) break' "$KT/TileGridFragment.kt" \
  && ok "separators are not counted against the column slots" || bad "separators count as column slots"

echo "== G4: Network order =="
got="$(python3 - "$APP/build.json" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
print(','.join(p['label'] for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')))
PY
)"
[ "$got" = "Firewall,DNS,Webserver,Cloud Mesh,Apps Access,ADB Shell,WiFi" ] && ok "Network = $got" || bad "Network is: $got"

echo "== G5: Home row =="
grep -q 'referenced.pages.filterNot { it.separator }' "$KT/Sections.kt" && ok "Home Configs row skips separator pages" || bad "Home row would draw the separator as a cell"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
