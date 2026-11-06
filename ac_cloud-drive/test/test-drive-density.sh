#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #621 — the app's DENSITY comes from ONE declaration (DriveMetrics) and   ║
# ║ no screen sizes itself: dense data, not launcher chrome                  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. "this app UI should fully scale down we are dense data here!
# this fucking huge buttons!!!" — and the reason it got that way is structural,
# not aesthetic: sixteen Compose files each carried their own dp literals, so a
# density decision was never made once, it was made ~120 times. Gradle compiles
# any number of them, and a screenshot of ONE screen looks fine while the next
# screen is a third looser. The defect this pins is therefore not "the rows are
# tall", it is "the app has no single density knob" — and the only way that stays
# true is if a new dp typed into a screen turns the build RED.
#
#   T1  the declaration EXISTS and is dense: DriveMetrics carries the density
#       vocabulary, rowHeight <= 40dp, islandHeight <= 48dp, cardPadding <= 8dp,
#       and the six-step spacing set is monotone (hairline<tight<gap<gapWide<pad<padWide).
#   T2  ONE source: no Kotlin file under app/src/main outside Chrome.kt holds a
#       dp OR sp literal of its own — every size is a DriveMetrics member.
#   T3  every screen READS it: the five tabs (Files, Volumes, Home, Sync, Configs),
#       #608's Git page, #613's Volumes sections, the dialogs, the Apps grid and
#       the chrome itself all reference DriveMetrics, so one edit moves them together.
#   T4  the TYPE scale is scaled once, in the theme (MaterialTheme typography =
#       denseTypography(), DriveMetrics.textScale < 1) — not with a fontSize per Text.
#   T5  touch targets stay usable: tap >= 36dp, and every IconButton in the app
#       is sized from the declaration rather than Material's 48dp default.
#   T6  #603 is not regressed by the theme edit: DriveTheme still provides
#       LocalContentColor, so dark-theme text is not black.
#   M   mutation-proof: a magic dp put back into a screen, a screen that stops
#       reading the declaration, a launcher-sized rowHeight, a dropped dense
#       typography and a dropped LocalContentColor each turn a check RED; the
#       unmutated tree stays green.
#
# OWN-SOURCE ONLY. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
CHROME="$SRC/ui/Chrome.kt"
THEME="$SRC/ui/DriveTheme.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$CHROME" "$THEME" "$SRC/files/FilesScreen.kt" "$SRC/volumes/VolumesScreen.kt" "$SRC/manage/OverviewPage.kt" "$SRC/sync/SyncScreen.kt" "$SRC/configs/ConfigsScreen.kt" "$SRC/sync/GitReposScreen.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block can run them on copies ──

# t1 <Chrome.kt> : the declaration exists, is dense, and its spacing steps are ordered
t1() {
    python3 - "$1" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"object DriveMetrics \{(.*?)\n\}", text, re.S)
if not m: print("    no `object DriveMetrics` — the app has no density declaration"); sys.exit(1)
body = m.group(1)
vals, bad = {}, []
for name, expr in re.findall(r"^\s*val (\w+) = ([^\n]+)$", body, re.M):
    e = expr.strip()
    lit = re.fullmatch(r"(\d+)\.(?:dp|sp)", e)
    if lit: vals[name] = int(lit.group(1)); continue
    ref = re.fullmatch(r"(\w+)(?:\s*\+\s*(\w+))?", e)
    if ref and ref.group(1) in vals:
        vals[name] = vals[ref.group(1)] + (vals.get(ref.group(2), 0) if ref.group(2) else 0)
for need in ("hairline", "tight", "gap", "gapWide", "pad", "padWide", "rowHeight", "islandHeight",
             "cardPadding", "gutter", "tap", "tapSmall", "glyph", "icon", "iconSmall", "textScale"):
    if need not in vals and need != "textScale": bad.append("DriveMetrics has no `%s` — the vocabulary is incomplete" % need)
if "textScale" not in body: bad.append("DriveMetrics declares no textScale — the type scale is not part of the density")
steps = [vals.get(k) for k in ("hairline", "tight", "gap", "gapWide", "pad", "padWide")]
if None in steps or steps != sorted(steps) or len(set(steps)) != len(steps):
    bad.append("the spacing steps are not a monotone set: %s" % steps)
for name, cap, why in (("rowHeight", 40, "a data row is not a launcher row"),
                       ("islandHeight", 48, "the top pill is chrome, not content"),
                       ("cardPadding", 8, "a card frames facts, it does not pad them"),
                       ("gutter", 8, "the page gutter is the narrow step")):
    if vals.get(name, 999) > cap: bad.append("DriveMetrics.%s is %sdp, over the %sdp density ceiling — %s" % (name, vals[name], cap, why))
if vals.get("tap", 0) < 36: bad.append("DriveMetrics.tap is %sdp — below the usable touch floor" % vals.get("tap"))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <src dir> <chrome path relative to src> : no dp/sp literal anywhere but the declaration
t2() {
    python3 - "$1" "$2" <<'PYTHON'
import os, re, sys
src, chrome = sys.argv[1], sys.argv[2]
bad = []
for d, _, fs in os.walk(src):
    for f in sorted(fs):
        if not f.endswith(".kt"): continue
        p = os.path.join(d, f)
        if os.path.relpath(p, src) == chrome: continue
        for i, line in enumerate(open(p, encoding="utf-8"), 1):
            if line.strip().startswith(("//", "*", "/*")): continue
            for hit in re.findall(r"(?<![\w.])\d+\.(?:dp|sp)", line):
                bad.append("%s:%d holds its own size `%s` — density belongs to DriveMetrics" % (os.path.relpath(p, src), i, hit))
for b in bad[:12]: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3 <src dir> : every surface reads the declaration
t3() {
    python3 - "$1" <<'PYTHON'
import os, sys
src = sys.argv[1]
surfaces = ["files/FilesScreen.kt", "volumes/VolumesScreen.kt", "volumes/VolumeClasses.kt", "manage/ManageScreen.kt", "manage/OverviewPage.kt",
            "sync/SyncScreen.kt", "sync/GitReposScreen.kt", "sync/RcloneMountsScreens.kt", "configs/ConfigsScreen.kt",
            "configs/GeneralPage.kt", "configs/OthersPage.kt", "configs/SignInCard.kt", "backups/BackupsScreen.kt",
            "apps/AppsGrid.kt", "files/FilesDialogs.kt", "ui/Chrome.kt"]
bad = []
for rel in surfaces:
    p = os.path.join(src, rel)
    if not os.path.isfile(p): bad.append("%s is gone — the surface list is stale" % rel); continue
    if "DriveMetrics." not in open(p, encoding="utf-8").read():
        bad.append("%s references no DriveMetrics member — it does not follow the density" % rel)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t4 <DriveTheme.kt> <Chrome.kt> : the type scale is shrunk once, in the theme
t4() {
    python3 - "$1" "$2" <<'PYTHON'
import re, sys
theme, chrome = (open(p, encoding="utf-8").read() for p in sys.argv[1:3])
bad = []
if "typography = denseTypography()" not in theme: bad.append("MaterialTheme is not given the dense typography — every Text keeps the launcher type scale")
if theme.count(".dense()") < 15: bad.append("denseTypography scales %d styles, not the whole 15-style Material scale" % theme.count(".dense()"))
m = re.search(r"val textScale = ([0-9.]+)f", chrome)
if not m: bad.append("DriveMetrics.textScale is not a declared factor")
elif not 0.5 < float(m.group(1)) < 1.0: bad.append("DriveMetrics.textScale is %s — not a scale-DOWN" % m.group(1))
if "DriveMetrics.textScale" not in theme: bad.append("the theme does not read DriveMetrics.textScale — the type density is a second source")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t6 <DriveTheme.kt> : #603 still holds
t6() {
    grep -q 'LocalContentColor provides' "$1"
}

echo "── T1 the density declaration exists and is dense ──"
t1 "$CHROME" && pass "DriveMetrics is the declaration: ordered spacing steps, 40dp rows, 8dp card padding, a 36dp touch floor" || fail "DriveMetrics is missing, incomplete or still launcher-sized"

echo "── T2 ONE source: no screen sizes itself ──"
t2 "$SRC" "ui/Chrome.kt" && pass "no dp or sp literal outside the declaration" || fail "a screen holds its own size"

echo "── T3 every screen reads the declaration ──"
t3 "$SRC" && pass "all five tabs, the Git page, the Volumes sections, the dialogs and the grid read DriveMetrics" || fail "a surface does not follow the density"

echo "── T4 the type scale is scaled once, in the theme ──"
t4 "$THEME" "$CHROME" && pass "the whole Material type scale is multiplied by DriveMetrics.textScale in DriveTheme" || fail "the type density is not one declaration"

echo "── T5 touch targets are declared, not Material's 48dp ──"
python3 - "$SRC" <<'PYTHON' && pass "every IconButton is sized from the declaration" || fail "an IconButton keeps the 48dp Material box"
import os, re, sys
bad = []
for d, _, fs in os.walk(sys.argv[1]):
    for f in sorted(fs):
        if not f.endswith(".kt"): continue
        p = os.path.join(d, f); text = open(p, encoding="utf-8").read()
        for m in re.finditer(r"IconButton\((?:[^()]|\([^()]*\))*\)", text):
            if "DriveMetrics." not in m.group(0):
                bad.append("%s: an IconButton with no declared size" % os.path.relpath(p, sys.argv[1]))
for b in sorted(set(bad)): print("    " + b)
sys.exit(1 if bad else 0)
PYTHON

echo "── T6 #603's dark-theme ink survives the theme edit ──"
t6 "$THEME" && pass "DriveTheme still provides LocalContentColor" || fail "LocalContentColor is gone — dark-theme Text goes back to black"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
cp -r "$SRC"/. "$TMP"/
printf '\nprivate val mutatedPadding = androidx.compose.ui.unit.Dp(24f)\nval mutatedRow = 56.dp\n' >> "$TMP/files/FilesScreen.kt"
t2 "$TMP" "ui/Chrome.kt" >/dev/null && fail "M: a magic dp put back into a screen passed T2" || pass "M: magic dp in a screen → T2 RED"
grep -v 'DriveMetrics\.' "$SRC/sync/GitReposScreen.kt" > "$TMP/sync/GitReposScreen.kt"
t3 "$TMP" >/dev/null && fail "M: a screen that reads no DriveMetrics passed T3" || pass "M: #608's Git page cut loose from the declaration → T3 RED"
cp -r "$SRC"/. "$TMP"/
python3 -c 'import sys; p=sys.argv[1]; s=open(p).read(); open(p,"w").write(s.replace("val rowHeight = 40.dp","val rowHeight = 56.dp"))' "$TMP/ui/Chrome.kt"
grep -q 'val rowHeight = 56.dp' "$TMP/ui/Chrome.kt" || fail "M: the rowHeight mutation did not change the file (tester is stale)"
t1 "$TMP/ui/Chrome.kt" >/dev/null && fail "M: a 56dp launcher row passed T1" || pass "M: the ONE knob turned back up → T1 RED (and every screen would follow it)"
python3 -c 'import sys; p=sys.argv[1]; s=open(p).read(); open(p,"w").write(s.replace("typography = denseTypography(), ","").replace(", typography = denseTypography()",""))' "$TMP/ui/DriveTheme.kt"
t4 "$TMP/ui/DriveTheme.kt" "$CHROME" >/dev/null && fail "M: a theme without the dense typography passed T4" || pass "M: dropped dense typography → T4 RED"
grep -v 'LocalContentColor provides' "$SRC/ui/DriveTheme.kt" > "$TMP/ui/DriveTheme.kt"
t6 "$TMP/ui/DriveTheme.kt" && fail "M: a theme without LocalContentColor passed T6" || pass "M: dropped LocalContentColor → T6 RED"
t1 "$CHROME" >/dev/null && t2 "$SRC" "ui/Chrome.kt" >/dev/null && t3 "$SRC" >/dev/null && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-density: all checks passed"; else echo "test-drive-density: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
