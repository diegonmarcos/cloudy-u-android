#!/usr/bin/env bash
# #1915 Configs > Launcher > UI is a strip over Display | Background | Screen Saver, and the Background picker is a
# declared catalogue "{name} - {2D face} / {N} faces" grouped 3D Polyhedra / 4D Polytopes, then by face type.
#
#   P1  panels-ui is a strip over exactly ui-display, ui-background, ui-screensaver (hidden tabs, routed in SectionPages)
#   P2  PARITY against the pre-split UI page: every control it drew is still drawn, under the subpage it belongs to,
#       and the union scope (ui / all) still draws all of them in the old order
#   P3  every catalogue shape is present with its exact label, group, face group, order, dim and vertex/edge/face data
#   M*  mutations: each check must go RED on a corrupted copy (labels, groups, missing shape, missing subpage, moved control)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BJ="$APP/build.json"
CFG="$KT/settings/LauncherConfigFragment.kt"
PAGES="$KT/launcher/SectionPages.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null 2>&1 || { echo "  ABORT: python3 missing"; exit 2; }
for f in "$BJ" "$CFG" "$PAGES"; do [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }; done

CHK="$(mktemp -d)"; trap 'rm -rf "$CHK"' EXIT
cat > "$CHK/check.py" <<'PY'
import json, re, sys
mode, bj, cfg, pages = sys.argv[1:5]
probs = []
sec = next(s for s in json.load(open(bj))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}

# (id, name, 2D face, N, group, face_group, dim, vertices, edges)
T3, T4 = "3D Polyhedra", "4D Polytopes"
CAT = [
 ("tetrahedron", "Tetrahedron", "Triangle", 4, T3, "Triangle", 3, 4, 6),
 ("octahedron", "Octahedron", "Triangle", 8, T3, "Triangle", 3, 6, 12),
 ("icosahedron", "Icosahedron", "Triangle", 20, T3, "Triangle", 3, 12, 30),
 ("great_icosahedron", "Great icosahedron", "Triangle", 20, T3, "Triangle", 3, 12, 30),
 ("cube", "Cube", "Square", 6, T3, "Square", 3, 8, 12),
 ("dodecahedron", "Dodecahedron", "Pentagon", 12, T3, "Pentagon", 3, 20, 30),
 ("great_dodecahedron", "Great dodecahedron", "Pentagon", 12, T3, "Pentagon", 3, 12, 30),
 ("small_stellated_dodecahedron", "Small stellated dodecahedron", "Pentagram", 12, T3, "Pentagram", 3, 12, 30),
 ("great_stellated_dodecahedron", "Great stellated dodecahedron", "Pentagram", 12, T3, "Pentagram", 3, 20, 30),
 ("hexagonal_prism", "Hexagonal prism", "Hexagon + Rectangle", 8, T3, "Hexagon hybrid", 3, 12, 18),
 ("hexagonal_pyramid", "Hexagonal pyramid", "Hexagon + Triangle", 7, T3, "Hexagon hybrid", 3, 7, 12),
 ("truncated_tetrahedron", "Truncated tetrahedron", "Hexagon + Triangle", 8, T3, "Hexagon hybrid", 3, 12, 18),
 ("truncated_octahedron", "Truncated octahedron", "Hexagon + Square", 14, T3, "Hexagon hybrid", 3, 24, 36),
 ("truncated_icosahedron", "Truncated icosahedron (soccer ball)", "Hexagon + Pentagon", 32, T3, "Hexagon hybrid", 3, 60, 90),
 ("cell5", "5-cell (4D simplex)", "Triangle", 10, T4, "Triangle", 4, 5, 10),
 ("cell16", "16-cell", "Triangle", 32, T4, "Triangle", 4, 8, 24),
 ("cell24", "24-cell (self-dual)", "Triangle", 96, T4, "Triangle", 4, 24, 96),
 ("cell600", "600-cell", "Triangle", 1200, T4, "Triangle", 4, 120, 720),
 ("icosahedral_120_cell", "Icosahedral 120-cell", "Triangle", 1200, T4, "Triangle", 4, 120, 720),
 ("tesseract", "8-cell · Tesseract", "Square", 24, T4, "Square", 4, 16, 32),
 ("cell120", "120-cell", "Pentagon", 720, T4, "Pentagon", 4, 600, 1200),
 ("great_120_cell", "Great 120-cell", "Pentagon", 720, T4, "Pentagon", 4, 120, 720),
 ("grand_120_cell", "Grand 120-cell", "Pentagon", 720, T4, "Pentagon", 4, 120, 720),
 ("small_stellated_120_cell", "Small stellated 120-cell", "Pentagram", 720, T4, "Pentagram", 4, 120, 720),
 ("great_stellated_120_cell", "Great stellated 120-cell", "Pentagram", 720, T4, "Pentagram", 4, 120, 720),
 ("truncated_5_cell", "Truncated 5-cell", "Hexagon + Triangle", 30, T4, "Hexagon hybrid", 4, 20, 40),
 ("truncated_16_cell", "Truncated 16-cell (truncated hexadecachoron)", "Hexagon + Triangle", 96, T4, "Hexagon hybrid", 4, 48, 120),
]

def catalogue():
    b = json.load(open(bj))['ui']['launcher_settings']['background']
    got = b['shapes']
    if [s['id'] for s in got] != [c[0] for c in CAT]:
        probs.append('shape ids/order differ: missing=%r extra=%r' % (
            [c[0] for c in CAT if c[0] not in {s['id'] for s in got}], [s['id'] for s in got if s['id'] not in {c[0] for c in CAT}]))
    byid = {s['id']: s for s in got}
    for (i, name, face, n, grp, fg, dim, v, e) in CAT:
        s = byid.get(i)
        if not s: continue
        want = '%s - %s / %d faces' % (name, face, n)
        if s.get('label') != want: probs.append('%s label %r != %r' % (i, s.get('label'), want))
        if s.get('group') != grp: probs.append('%s group %r != %r' % (i, s.get('group'), grp))
        if s.get('face_group') != fg: probs.append('%s face_group %r != %r' % (i, s.get('face_group'), fg))
        if s.get('dim') != dim: probs.append('%s dim' % i)
        if (s.get('vertices'), s.get('edges'), s.get('faces')) != (v, e, n): probs.append('%s V/E/F %r' % (i, (s.get('vertices'), s.get('edges'), s.get('faces'))))
    # grouped: the declared order walks 3D then 4D, and inside each the face types in the owner's order
    order = {'Triangle': 0, 'Square': 1, 'Pentagon': 2, 'Pentagram': 3, 'Hexagon hybrid': 4}
    keys = [((0 if s.get('group') == T3 else 1), order.get(s.get('face_group'), 9)) for s in got]
    if keys != sorted(keys): probs.append('shapes are not grouped 3D/4D then by face type')
    if b.get('groups') != [T3, T4] or b.get('face_groups') != list(order): probs.append('groups / face_groups declaration')
    if b.get('default_shape') != 'cube': probs.append('default shape')

def subpages():
    p = by.get('panels-ui')
    if not p or p.get('tabs') != ['ui-display', 'ui-background', 'ui-screensaver']: probs.append('panels-ui is not the strip Display | Background | Screen Saver: %r' % (p and p.get('tabs'),))
    if p and (p.get('hidden') or p.get('label') != 'UI' or p.get('group') != 'Launcher'): probs.append('panels-ui is no longer the visible Launcher UI tile')
    for i, lab in (('ui-display', 'Display'), ('ui-background', 'Background'), ('ui-screensaver', 'Screen Saver')):
        q = by.get(i)
        if not q or q.get('label') != lab or not q.get('hidden'): probs.append('%s missing, mislabelled or not a hidden tab' % i)
    grp = next(g for g in sec['groups'] if g['id'] == 'launcher')['pages']
    for i in ('ui-display', 'ui-background', 'ui-screensaver'):
        if i not in grp: probs.append('%s not in the launcher group' % i)
    src = open(pages).read()
    for i, sc in (('ui-display', 'SCOPE_UI_DISPLAY'), ('ui-background', 'SCOPE_UI_BACKGROUND'), ('ui-screensaver', 'SCOPE_UI_SCREENSAVER'), ('panels-ui', 'SCOPE_UI')):
        if 'pageId == "%s" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.%s)' % (i, sc) not in src: probs.append('%s is not routed to %s' % (i, sc))

# Everything the UI page drew before the split, and the subpage it now lives under.
PARITY = {
  'showUiScreensaver': ['SectionHeaderUi("Screensaver"', 'LauncherSettingsPrefs.Config.screensavers', 'LauncherSettingsPrefs.Config.screensaverTimeout'],
  'showUiDisplay': ['SectionHeaderUi("Icons per row"', 'stepperRow("Cloud apps"', 'stepperRow("Phone apps"', 'LauncherSettingsPrefs.Config.brightness', 'DisplayControlsSection()'],
  'showUiBackground': ['BackgroundSection(settingsPrefs, epoch)'],
}
def block(src, head):
    lines = src.split('\n'); out = []; on = False; ind = 0
    for ln in lines:
        if not on and head in ln and ln.strip().startswith('if ('):
            on = True; ind = len(ln) - len(ln.lstrip()); out.append(ln); continue
        if on:
            out.append(ln)
            if len(ln) - len(ln.lstrip()) == ind and ln.strip().startswith('}'): break
    return '\n'.join(out)
def parity():
    src = open(cfg).read()
    for flag, needs in PARITY.items():
        b = block(src, 'if (%s) {' % flag)
        if not b: probs.append('no `if (%s)` block' % flag); continue
        for n in needs:
            if n not in b: probs.append('%s lost: %s' % (flag, n))
        for other, onn in PARITY.items():
            if other != flag:
                for n in onn:
                    if n in b: probs.append('%s also draws %s (belongs to %s)' % (flag, n, other))
    # the union scope still draws all three subpages, in the old order
    for flag, sc in (('showUiScreensaver', 'SCOPE_UI_SCREENSAVER'), ('showUiDisplay', 'SCOPE_UI_DISPLAY'), ('showUiBackground', 'SCOPE_UI_BACKGROUND')):
        if not re.search(r'val %s = all \|\| scope == SCOPE_UI \|\| scope == %s' % (flag, sc), src): probs.append('%s is not on for scope ui / all and its own scope' % flag)
    u = [src.find('if (%s) {' % f) for f in ('showUiScreensaver', 'showUiDisplay', 'showUiBackground')]
    if not (0 < u[0] < u[1] < u[2]): probs.append('union order is not Screensaver, Display, Background')
    for sc in ('SCOPE_UI_DISPLAY', 'SCOPE_UI_BACKGROUND', 'SCOPE_UI_SCREENSAVER'):
        if 'const val %s = ' % sc not in src: probs.append('%s not declared' % sc)

{'catalogue': catalogue, 'subpages': subpages, 'parity': parity}[mode]()
print('OK' if not probs else '; '.join(probs[:6]))
PY
chk() { python3 "$CHK/check.py" "$1" "${2:-$BJ}" "${3:-$CFG}" "${4:-$PAGES}"; }
expect_ok()  { local r; r="$(chk "$@")"; [ "$r" = OK ] && ok "$DESC" || bad "$DESC — $r"; }

echo "== P1: Launcher > UI is a strip over Display | Background | Screen Saver =="
DESC="panels-ui tabs = ui-display, ui-background, ui-screensaver; hidden tabs in the launcher group; routed in SectionPages"; expect_ok subpages
echo "== P2: parity with the pre-split UI page =="
DESC="Screensaver (+ timer) -> Screen Saver; Icons per row, brightness, Scale/Font/Zoom -> Display; Background -> Background; ui/all union draws all in the old order"; expect_ok parity
echo "== P3: the catalogue, exact labels and groups =="
DESC="27 shapes: label {name} - {face} / {N} faces, 3D Polyhedra / 4D Polytopes, face type order, dim, vertices, edges, faces"; expect_ok catalogue

echo "== M: mutations — every check must go RED on a corrupted copy =="
python3 - "$BJ" "$CFG" "$PAGES" "$CHK" <<'PY'
import json, sys, copy, re
bj, cfg, pages, out = sys.argv[1:5]
b = json.load(open(bj)); c = open(cfg).read(); p = open(pages).read()
def wj(name, f):
    d = copy.deepcopy(b); f(d); json.dump(d, open(out + '/' + name, 'w'))
def bg(d): return d['ui']['launcher_settings']['background']
def cfgsec(d): return next(s for s in d['ui']['sections'] if s['id'] == 'config')
def pg(d, i): return next(x for x in cfgsec(d)['pages'] if x['id'] == i)
def setlabel(d): bg(d)['shapes'][0]['label'] = 'Tetrahedron - Triangle / 5 faces'
def setgroup(d): bg(d)['shapes'][3]['group'] = '4D Polytopes'
def dropshape(d): bg(d)['shapes'] = [s for s in bg(d)['shapes'] if s['id'] != 'great_stellated_120_cell']
def swapfg(d):
    s = bg(d)['shapes']; s[0], s[4] = s[4], s[0]
def badcount(d): next(s for s in bg(d)['shapes'] if s['id'] == 'truncated_16_cell')['edges'] = 119
def dropsub(d): pg(d, 'panels-ui')['tabs'] = ['ui-display', 'ui-background']
def unhide(d): pg(d, 'ui-screensaver')['hidden'] = False
wj('m_label.json', setlabel); wj('m_group.json', setgroup); wj('m_drop.json', dropshape); wj('m_swap.json', swapfg)
wj('m_count.json', badcount); wj('m_sub.json', dropsub); wj('m_unhide.json', unhide)
# a control moved to the wrong subpage / dropped
open(out + '/cfg_moved.kt', 'w').write(c.replace('DisplayControlsSection()', '// moved', 1))
open(out + '/cfg_dup.kt', 'w').write(c.replace('BackgroundSection(settingsPrefs, epoch)', 'BackgroundSection(settingsPrefs, epoch)\n    DisplayControlsSection()', 1))
open(out + '/cfg_union.kt', 'w').write(c.replace('val showUiBackground = all || scope == SCOPE_UI ||', 'val showUiBackground = all ||', 1))
open(out + '/pages_unrouted.kt', 'w').write(p.replace('SCOPE_UI_BACKGROUND', 'SCOPE_UI_DISPLAY'))
PY
mut() { # name mode json cfg pages
  local r; r="$(chk "$2" "${3:-$BJ}" "${4:-$CFG}" "${5:-$PAGES}")"
  case "$r" in *Error*|*Traceback*|*unbound*) bad "mutation $1 crashed the checker: $r"; return;; esac
  [ "$r" != OK ] && ok "mutation $1 -> RED" || bad "mutation $1 stayed GREEN (the check is blind to it)"; }
mut "wrong face count in a label" catalogue "$CHK/m_label.json"
mut "shape moved to the other group" catalogue "$CHK/m_group.json"
mut "a listed shape deleted" catalogue "$CHK/m_drop.json"
mut "face types out of order" catalogue "$CHK/m_swap.json"
mut "declared edge count wrong" catalogue "$CHK/m_count.json"
mut "a subpage dropped from the strip" subpages "$CHK/m_sub.json"
mut "a subpage shown as its own tile" subpages "$CHK/m_unhide.json"
mut "a subpage not routed" subpages "$BJ" "$CFG" "$CHK/pages_unrouted.kt"
mut "Scale/Font/Zoom dropped from Display" parity "$BJ" "$CHK/cfg_moved.kt"
mut "a control drawn under two subpages" parity "$BJ" "$CHK/cfg_dup.kt"
mut "union scope no longer draws Background" parity "$BJ" "$CHK/cfg_union.kt"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
