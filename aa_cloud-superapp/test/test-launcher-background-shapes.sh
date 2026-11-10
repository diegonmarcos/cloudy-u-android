#!/usr/bin/env bash
# #1621 — the launcher animated BACKGROUND gains a config surface and a
# 3D/4D regular-polytope shape picker with auto-cycle.
#
# The background was one hard-wired rotating cube with no settings. This pins
# the three things #1621 adds, each from the ONE declaration (build.json) and
# the ONE renderer (PolytopeMath / Home3DFragment), so none can quietly rot:
#
#   T1  the quick-actions row reads Presets | UI | Panels (UI promoted to parent)
#   T2  build.json declares the Background block: enable, a REFERENCE to the
#       existing stars_anim toggle (not a copy), shape_key, default_shape=cube,
#       and the auto-cycle (minutes default 5)
#   T3  the eleven regular polytopes are declared, ids + dims: the 5 Platonic
#       solids (dim 3) and their 6 4D analogues (dim 4), 24-cell self-dual
#   T4  PolytopeMath owns the geometry: the same eleven ids, cube default, the
#       4D rotation+projection and the auto-cycle index math
#   T5  the renderer reads the config and draws through PolytopeMath, and still
#       runs exactly ONE frame loop (reused for the 4D clock — battery)
#   T6  the config UI (LauncherConfigFragment UI scope) wires enable / star /
#       shape picker / auto-cycle, and the prefs expose the keys
#   T7  the geometry is CODE (PolytopeMath), not data baked into build.json —
#       no 600-vertex table in the APK's declaration
#
# Static tester (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BJ="$APP/build.json"
POLY="$KT/launcher/themes/cloud/PolytopeMath.kt"
HOME3D="$KT/launcher/themes/cloud/Home3DFragment.kt"
PREFS="$KT/settings/LauncherSettingsPrefs.kt"
CFG="$KT/settings/LauncherConfigFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }
command -v python3 >/dev/null 2>&1 || { echo "  ABORT: python3 missing"; exit 2; }
for f in "$BJ" "$POLY" "$HOME3D" "$PREFS" "$CFG"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done

echo "== T1: Quick Actions row = Presets | UI | Panels (UI promoted) =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
ctrl = [by[i]['label'] for i in by['controls']['tabs']]
print('OK' if by['controls']['tabs'] == ['presets','panels-ui','panels'] and ctrl == ['Presets','UI','Panels']
      and by['panels']['tabs'] == ['panels-all','panels-battery','panels-tools'] else 'controls=%r panels=%r' % (by['controls']['tabs'], by['panels']['tabs']))
PY
)" "controls strip is Presets | UI | Panels; panels is All | Battery Hungers | Tools"

echo "== T2: the Background declaration =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))['ui']['launcher_settings'].get('background')
if not b: print('no launcher_settings.background block'); sys.exit()
probs = []
if b.get('enable', {}).get('key') != 'background_enabled': probs.append('enable key')
if b.get('stars_toggle') != 'stars_anim': probs.append('stars_toggle must REFERENCE stars_anim, not copy it')
if b.get('shape_key') != 'background_shape': probs.append('shape_key')
if b.get('default_shape') != 'cube': probs.append('default_shape must be cube')
cy = b.get('cycle', {})
if cy.get('enable_key') != 'background_cycle_enabled': probs.append('cycle enable_key')
if cy.get('minutes', {}).get('default') != 5: probs.append('cycle minutes default must be 5')
if cy.get('minutes', {}).get('key') != 'background_cycle_minutes': probs.append('cycle minutes key')
print('; '.join(probs) or 'OK')
PY
)" "background: enable/stars-ref/shape_key/default cube + auto-cycle (5 min default)"

echo "== T3: the eleven regular polytopes, ids + dims =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))['ui']['launcher_settings']['background']
got = [(s['id'], s['dim']) for s in b['shapes']]
want = [('tetrahedron',3),('cube',3),('octahedron',3),('dodecahedron',3),('icosahedron',3),
        ('cell5',4),('tesseract',4),('cell16',4),('cell120',4),('cell600',4),('cell24',4)]
# the 24-cell is the one 4D shape with no 3D twin
twin_ok = all(('dual_of' in s) == (s['id'] != 'cell24') or s['dim'] == 3 for s in b['shapes'] if s['dim'] == 4)
c24 = next(s for s in b['shapes'] if s['id'] == 'cell24')
print('OK' if got == want and 'dual_of' not in c24 else 'shapes=%r' % (got,))
PY
)" "5 Platonic (dim3) + 6 4D analogues (dim4); 24-cell self-dual (no dual_of)"

echo "== T4: PolytopeMath owns the geometry =="
for id in TETRAHEDRON CUBE OCTAHEDRON DODECAHEDRON ICOSAHEDRON CELL5 TESSERACT CELL16 CELL120 CELL600 CELL24; do
  grep -q "const val $id" "$POLY" || bad "PolytopeMath missing shape const $id"
done
grep -q 'val SHAPE_IDS' "$POLY" && grep -q 'DEFAULT_SHAPE = CUBE' "$POLY" \
  && ok "SHAPE_IDS catalogue + cube default" || bad "no SHAPE_IDS / cube default"
grep -q 'fun project4to3' "$POLY" && grep -q 'W_DIST_4D' "$POLY" \
  && ok "4D→3D perspective projection present" || bad "no 4D projection"
grep -q 'fun cycleIndex' "$POLY" && ok "auto-cycle index math present" || bad "no cycleIndex"
grep -q 'fun deriveEdges' "$POLY" && ok "edges DERIVED by nearest-neighbour (no hand-typed edge tables)" || bad "edges not derived"

echo "== T5: the renderer reads the config, draws through PolytopeMath, one loop =="
grep -q 'PolytopeMath' "$HOME3D" && ok "Home3DFragment renders through PolytopeMath" || bad "renderer does not use PolytopeMath"
grep -q 'backgroundEnabled' "$HOME3D" && grep -q 'backgroundShape' "$HOME3D" \
  && grep -q 'backgroundCycleEnabled' "$HOME3D" && grep -q 'PolytopeMath.cycleIndex' "$HOME3D" \
  && ok "renderer reads enable / shape / cycle and advances the cycle itself" || bad "renderer ignores a background key"
n="$(grep -cE 'withFrameNanos[[:space:]]*\{' "$HOME3D")"
[ "$n" = "1" ] && ok "exactly ONE withFrameNanos frame loop (the 4D clock reuses it)" || bad "$n frame loops (want 1 — a second animator costs battery)"

echo "== T6: the config UI + prefs =="
grep -q 'BackgroundSection(settingsPrefs' "$CFG" && ok "UI scope draws the Background section" || bad "no Background section in the UI tab"
grep -q 'backgroundShape = s.id' "$CFG" && grep -q 'backgroundCycleEnabled = on' "$CFG" \
  && grep -q 'setCycleShapeEnabled' "$CFG" && ok "picker sets shape, cycle on/off and per-shape membership" || bad "a background control is not wired"
for acc in 'var backgroundEnabled' 'var backgroundShape' 'var backgroundCycleEnabled' 'var backgroundCycleMinutes' 'fun cycleShapeIds'; do
  grep -q "$acc" "$PREFS" || bad "LauncherSettingsPrefs missing: $acc"
done
grep -q "firstOrNull { it.id == bg.starsToggle }" "$CFG" \
  && ok "the star switch is a VIEW onto stars_anim, not a new store" || bad "star toggle is not the referenced stars_anim"

echo "== T7: the polytope geometry is CODE, not data in the APK declaration =="
# A 600-vertex table does not belong in build.json; only the catalogue + labels do.
nverts="$(python3 -c "import json;print(len(json.dumps(json.load(open('$BJ'))['ui']['launcher_settings']['background'])))")"
[ "$nverts" -lt 4000 ] && ok "the background block is small (catalogue + labels, ${nverts}B); meshes live in PolytopeMath" \
  || bad "the background block is ${nverts}B — vertex data leaked into the declaration"
grep -q 'evenPermsWithSigns\|allPermsWithSigns' "$POLY" && ok "the 600-cell/120-cell vertices are GENERATED in code" || bad "generators missing"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
