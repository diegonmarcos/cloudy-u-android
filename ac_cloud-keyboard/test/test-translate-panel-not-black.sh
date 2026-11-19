#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Tapping Translate opens a readable bar, not a black box           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# THE DEFECT. The owner tapped the Translate toolbar key and got a box that was
# completely black and did nothing. Two causes, both checked here:
#
#   1. A STALE HOST. LatinIME creates each bar once and keeps it, but the input
#      view the bar lives in is rebuilt by a day/night switch, a keyboard theme
#      change, a rotation and the locked-config re-inflation of #776. The toggle
#      added the bar to the frame only on the call that CREATED it, so after any
#      rebuild the bar stayed a child of the old, detached view. Tapping Translate
#      made that invisible view VISIBLE; onComputeInsets still subtracted its
#      remembered height, pushing the app up over a strip of IME window nothing
#      draws in (black), and every key went into a box nobody could see.
#   2. A FIXED DARK PALETTE. The bar painted itself 95%-opaque #1B1B20 with dark
#      grey chips and grey hint text whatever the keyboard theme: a black slab on
#      a light keyboard, and dark-on-dark text on it.
#
# CHECKS
#   P1  every toggle re-hosts its bar into the CURRENT frame on every open, never
#       only inside its `== null` creation branch.
#   P2  setInputView carries the bars into a rebuilt input view.
#   P3  a bar counts as up (keys routed to it, height reserved in the insets) only
#       while it is a child of the CURRENT frame.
#   P4  the bar has no colour of its own: no ARGB literal and no Color.WHITE/BLACK
#       in TranslateBarView; LatinIME builds a TranslatePalette from the keyboard
#       theme (mColors) and hands it over on open and on rebuild.
#   P5  the bar fits: LatinIME caps its height at the keys' height and the bar's
#       onMeasure honours the cap.
#   P6  the bar closes back to the keyboard: a labelled close control, and system
#       back hides the bar instead of the whole keyboard.
#   P7  the contrast rule is EXECUTED: TranslatePaletteTest (JVM, light + dark +
#       transparent + dark-on-dark + a sweep) exists and Ship -> cloud-keyboard
#       runs libs:keyboard's unit tests.
#
# SELF-PROVING: after the real tree passes, each mutation puts one piece of the
# old defect back in a sandbox copy and must turn its named check red. The
# repository is shared with other live agents, so nothing is mutated in place.
# python3 only; a missing source file is fatal, never a pass.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
IME=ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/latin/LatinIME.java
BAR=ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/TranslateBarView.kt
PAL=ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/TranslatePalette.kt
TEST=ab_cloud-libs-shared/libs/keyboard/src/test/java/helium314/keyboard/latin/TranslatePaletteTest.kt
WF=.github/workflows/ship-cloud-keyboard.yml
BUILD=ac_cloud-keyboard/build.sh
FILES=("$IME" "$BAR" "$PAL" "$TEST" "$WF" "$BUILD")

for f in "${FILES[@]}"; do
    [ -f "$ROOT/$f" ] || { echo "FAIL   missing input: $f — every assertion below would read an empty file"; exit 1; }
done

ROOT="$ROOT" IME="$IME" BAR="$BAR" PAL="$PAL" TEST="$TEST" WF="$WF" BUILD="$BUILD" python3 - <<'PY'
import os, re, sys
R = os.environ["ROOT"]
def rd(p): return open(os.path.join(R, os.environ[p]), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)       # block comments, KDoc
    return re.sub(r"//[^\n]*", "", s)
def body(src, sig):
    m = re.search(sig, src)
    if not m: return ""
    i = src.find("{", m.end() - 1); d = 0
    for k in range(i, len(src)):
        d += {"{": 1, "}": -1}.get(src[k], 0)
        if d == 0: return src[i:k + 1]
    return ""
fails = 0
def check(ok, name):
    global fails
    print(("ok     " if ok else "FAIL   ") + name)
    if not ok: fails += 1

ime = code(rd("IME")); bar = code(rd("BAR"))
BARS = {"toggleTranslateBar": "mTranslateBar", "toggleEmojiSearchBar": "mEmojiSearchBar",
        "toggleEnhanceBar": "mEnhanceBar", "toggleVoiceBar": "mVoiceBar"}

print("== P1 every open re-hosts the bar into the current frame ==")
for fn, field in BARS.items():
    b = body(ime, r"public void %s\(\)" % fn)
    created = re.search(r"if \(%s == null\)\s*(\{.*?\n\s{8}\}|[^\n;]*;)" % field, b, re.S)
    inside = created.group(0) if created else ""
    outside = b.replace(inside, "") if inside else b
    ok = bool(b) and "hostPanel(%s, frame)" % field in outside and "addView(" not in b
    check(ok, f"P1 {fn} re-hosts {field} on every open, not only when it creates it")
hp = body(ime, r"private static void hostPanel\(")
check("removeView(" in hp and "addView(panel, 0)" in hp and "getParent() == frame" in hp,
      "P1 hostPanel moves a bar out of a stale parent into the current frame")

print("== P2 a rebuilt input view gets its bars back ==")
si = body(ime, r"public void setInputView\(")
rh = body(ime, r"private void rehostPanels\(")
check("rehostPanels()" in si, "P2 setInputView re-hosts the bars")
check(all("hostPanel(%s, frame)" % f in rh for f in BARS.values()) and "setPalette(" in rh,
      "P2 rehostPanels carries every bar and re-themes the translate bar")

print("== P3 only a bar in the current frame is up ==")
up = body(ime, r"private boolean isPanelUp\(")
check("getParent() == panelFrame()" in up and "View.VISIBLE" in up, "P3 isPanelUp asks for the current frame, not visibility alone")
check("return isPanelUp(mTranslateBar);" in body(ime, r"public boolean isTranslateBarActive\("),
      "P3 keys go to the translate bar only while it is up in the current frame")
ins = body(ime, r"public void onComputeInsets\(")
stale = [f for f in BARS.values() if re.search(re.escape(f) + r"\s*!=\s*null\s*&&\s*" + re.escape(f) + r"\.getVisibility\(\)", ins)]
check("isPanelUp(mTranslateBar)" in ins and not stale, f"P3 the insets reserve height only for a bar that is up {stale or ''}")

print("== P4 the bar wears the keyboard's colours ==")
lits = sorted(set(re.findall(r"0x[0-9A-Fa-f]{8}", bar)))
check(not lits, f"P4 TranslateBarView has no hard-coded ARGB colour {lits or ''}")
check(re.search(r"Color\.(WHITE|BLACK|DKGRAY|GRAY|White|Black|DarkGray|Gray)\b", bar) is None, "P4 TranslateBarView paints nothing in fixed Color.*")
check("fun setPalette(" in bar and "setBackgroundColor(p.background)" in body(bar, r"fun setPalette\("),
      "P4 the bar's background comes from the palette")
tp = body(ime, r"TranslatePalette translatePalette\(")
check("fromKeyboard(" in tp and "ColorType.MAIN_BACKGROUND" in tp and "ColorType.KEY_TEXT" in tp and "mColors" in tp,
      "P4 LatinIME builds the palette from the keyboard theme")
tt = body(ime, r"public void toggleTranslateBar\(\)")
check("setPalette(translatePalette())" in tt, "P4 every open hands the current palette over")
pal = code(rd("PAL"))
check("readableOn(" in pal and "MIN_TEXT_CONTRAST = 4.5" in pal and not re.search(r"^import android", pal, re.M),
      "P4 TranslatePalette enforces contrast and stays JVM-testable")

print("== P5 the bar fits above the keys ==")
check("setMaxHeight(translateBarMaxHeight())" in tt and "getWrapperView()" in body(ime, r"private int translateBarMaxHeight\("),
      "P5 LatinIME caps the bar at the keys' height")
om = body(bar, r"override fun Body\(")
check("maxBarHeight" in om and "heightIn(max" in om, "P5 the bar's onMeasure honours the cap")

print("== P6 the bar closes back to the keyboard ==")
# #1818 the close control moved into the ONE shared Writer panel (WriterPanelUi.kt), which every mode draws; the bar hands it onClose.
panel = code(open(os.path.join(R, os.path.dirname(os.environ["BAR"]), "WriterPanelUi.kt"), encoding="utf-8").read())
cl = re.search(r"contentDescription = closeLabel\s*\}\s*\.clickable \{ spec\.onClose\(\) \}", panel)
check(cl is not None and "closeLabel = stringResource(R.string.translate_bar_close)" in panel
      and "onClose = { onClose?.run() }" in bar,
      "P6 a labelled close control runs onClose")
check("this::hideTranslateBar);" in tt, "P6 onClose is hideTranslateBar (the keyboard stays up)")
kd = body(ime, r"public boolean onKeyDown\(")
check(re.search(r"KEYCODE_BACK[^;]*isTranslateBarActive\(\)\)\s*\{\s*hideTranslateBar\(\);\s*return true;", kd) is not None,
      "P6 system back closes the translate bar instead of hiding the keyboard")

print("== P7 the contrast rule is executed in CI ==")
t = rd("TEST")
check(all(n in t for n in ("lightKeyboardGetsALightBarNotABlackBox", "darkKeyboardGetsADarkBarThatStillReads",
                           "transparentOrImageBackgroundNeverBecomesTransparentBlack", "everyThemeInASweepIsReadable")),
      "P7 TranslatePaletteTest covers light, dark, transparent and a sweep")
check(":libs:keyboard:testDebugUnitTest" in rd("BUILD") and "./build.sh unit" in rd("WF"), "P7 Ship -> cloud-keyboard runs it")

print(f"== {fails} failed ==")
sys.exit(1 if fails else 0)
PY
rc=$?
[ "$rc" -eq 0 ] || { echo "test-translate-panel-not-black: the real tree is RED"; exit 1; }
[ "${PANEL_NO_MUTATIONS:-0}" = "1" ] && exit 0

MUT_FAIL=0
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {   # mutate <label> <file> <python edit of s> <check prefix that must go red>
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && cp --parents -t "$WORK/t" "${FILES[@]}")
    local before; before="$(cat "$WORK/t/$2")"
    python3 -c "import re,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    if [ "$before" = "$(cat "$WORK/t/$2")" ]; then echo "FAIL   mutation '$1' did not land — the fixture moved"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    local out; out="$(CLOUD_ANDROID_ROOT="$WORK/t" PANEL_NO_MUTATIONS=1 bash "$SELF" 2>&1)"
    if grep -q "^FAIL   $4" <<<"$out"; then echo "ok     mutation '$1' turns $4 red"
    else echo "FAIL   mutation '$1' left $4 green"; MUT_FAIL=$((MUT_FAIL + 1)); fi
}
echo "== mutations =="
mutate "the bar is added only when it is created (the shipped defect)" "$IME" \
    "s=s.replace('        if (mTranslateBar == null)\n            mTranslateBar = new com.diegonmarcos.superapp.translate.TranslateBarView(this);\n        hostPanel(mTranslateBar, frame);\n','        if (mTranslateBar == null) {\n            mTranslateBar = new com.diegonmarcos.superapp.translate.TranslateBarView(this);\n            frame.addView(mTranslateBar, 0);\n        }\n',1)" "P1 toggleTranslateBar"
mutate "a rebuilt input view forgets the bars" "$IME" \
    "s=s.replace('        rehostPanels();\n','',1)" "P2 setInputView"
mutate "visibility alone decides that the bar is up" "$IME" \
    "s=s.replace('return isPanelUp(mTranslateBar);','return mTranslateBar != null && mTranslateBar.getVisibility() == View.VISIBLE;',1)" "P3 keys go to"
mutate "the insets reserve a stale bar's height" "$IME" \
    "s=s.replace('else if (isPanelUp(mTranslateBar)) {','else if (mTranslateBar != null && mTranslateBar.getVisibility() == View.VISIBLE) {',1)" "P3 the insets"
mutate "the fixed near-black slab comes back" "$BAR" \
    "s=s.replace('        setBackgroundColor(p.background)\n','        setBackgroundColor(0xF21B1B20.toInt())\n',1)" "P4 TranslateBarView has no hard-coded"
mutate "white text whatever the theme" "$BAR" \
    "s=s.replace('Color(if (showingStatus) p.muted else p.text)','Color.White',1)" "P4 TranslateBarView paints nothing"
mutate "the open keeps an old palette" "$IME" \
    "s=s.replace('        } else {\n            mTranslateBar.setPalette(translatePalette());\n','        } else {\n',1)" "P4 every open"
mutate "no height cap" "$IME" \
    "s=s.replace('            mTranslateBar.setMaxHeight(translateBarMaxHeight());\n            // Commit','            // Commit',1)" "P5 LatinIME caps"
mutate "back hides the whole keyboard with the bar open" "$IME" \
    "s=s.replace('isTranslateBarActive()) {\n            hideTranslateBar();\n            return true;','false) {\n            hideTranslateBar();\n            return true;',1)" "P6 system back"
[ "$MUT_FAIL" -eq 0 ] || { echo "test-translate-panel-not-black: $MUT_FAIL mutation(s) not caught"; exit 1; }
echo
echo "test-translate-panel-not-black: OK (real tree green, every mutation red)"
