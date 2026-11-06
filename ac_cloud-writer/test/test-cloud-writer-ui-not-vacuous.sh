#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-ui.sh can actually go red                          ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# AN ASSERTION NOBODY HAS WATCHED FAIL IS DECORATION. This repository has shipped
# the proof repeatedly: an assertion comparing an expression to itself, a
# pipeline's status read through `tail`, `case $OUT in *"[NEW]"*)` where the
# brackets made a one-character class, four testers that passed only because
# ripgrep was absent, a grep that matched the commented-out line it was written
# to detect as missing, and a guard satisfied by its own KDoc prose.
#
# THE LAST ONE IS THE LIVE RISK FOR THE SIBLING. Its header paragraph NAMES
# Color.parseColor and setPadding while explaining why they are forbidden, so if
# its comment stripper ever broke, T2 and T3 would be satisfied by their own
# documentation and would go green over a fully reverted UI. Break 2 and break 3
# below are exactly that scenario, planted in the source rather than in the prose.
#
# So this runs the sibling against a DELIBERATELY BROKEN COPY, once per planted
# defect, and requires the matching assertion to NAME it.
#
# THE BREAKS ARE APPLIED TO A COPY IN A TEMPORARY DIRECTORY, never the working
# tree. A harness that edits the repository and restores afterwards is one failed
# restore away from committing its own sabotage.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="$ROOT/ac_cloud-writer/test/test-cloud-writer-ui.sh"
[ -f "$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" <<'PYEOF'
import os, shutil, subprocess, sys, tempfile

root = sys.argv[1]
APP = "ac_cloud-writer"
SRC = APP + "/app/src/main/java/com/diegonmarcos/cloudwriter"
RES = APP + "/app/src/main/res"

# (what a later change would plausibly do, which file, the exact edit, the text
#  the failing assertion must contain).
BREAKS = [
    # ── T1: the two copies of the toolchain drift ───────────────────────────
    ("Kotlin is bumped in build.json and not in the build", APP + "/build.json",
     ('"kotlin": "2.1.0"', '"kotlin": "2.1.20"'),
     "but build.json::toolchain.kotlin says"),

    ("the compose BOM is bumped in the build and not in build.json", APP + "/app/build.gradle",
     ("androidx.compose:compose-bom:2025.11.01", "androidx.compose:compose-bom:2025.06.00"),
     "does not resolve the compose BOM build.json pins"),

    # ── T2/T3: the hand-drawn screen creeps back ────────────────────────────
    #
    # Planted in a REAL source file, not in prose. If the sibling's comment
    # stripper breaks, these two still fail — but its own header would then also
    # satisfy the greps, which is the failure this pair is here to rule out.
    ("a colour is hardcoded again instead of taken from the theme", SRC + "/ui/Theme.kt",
     ("private val WriterBlue = Color(TokenSet.DARK.primary)",
      "private val WriterBlue = Color(android.graphics.Color.parseColor(\"#78c8ff\"))"),
     "T2 Color.parseColor is back"),

    ("raw-pixel padding comes back with a hand-built view", SRC + "/MainActivity.kt",
     ("    private fun say(line: String) {",
      "    private fun legacyPad(v: android.view.View) { v.setPadding(36, 36, 36, 36) }\n"
      "    private fun say(line: String) {"),
     "T3 setPadding() is back"),

    # ── T7..T10 (#1532): the premium design is undone ───────────────────────
    ("an sp literal comes back", SRC + "/MainActivity.kt",
     ("    private fun say(line: String) {",
      "    private val legacySize = 14.sp\n"
      "    private fun say(line: String) {"),
     "T7 an sp literal is in the source"),

    ("a filled icon creeps back next to the rounded ones", SRC + "/MainActivity.kt",
     ("    private fun say(line: String) {",
      "    private val legacyIcon = Icons.Filled.Add\n"
      "    private fun say(line: String) {"),
     "T8 a non-rounded icon"),

    ("the theme stops painting from the tokens", SRC + "/ui/Theme.kt",
     ("private val WriterDarkScheme = TokenSet.DARK.toScheme(dark = true)", "private val WriterDarkScheme = darkColorScheme()"),
     "T9 the theme no longer paints from core TokenSet"),

    ("the AA contrast test is deleted", APP + "/core/src/test/kotlin/com/diegonmarcos/cloudwriter/core/DesignTest.kt",
     ("fun everyTextPairReachesAA", "fun everyTextPairIsIgnored"),
     "T9 the theme no longer paints from core TokenSet, or the AA contrast test"),

    ("a bare Material button is back on the main screen", SRC + "/MainActivity.kt",
     ("    private fun say(line: String) {",
      "    @Composable private fun legacyButton() { Button(onClick = {}) { Text(\"x\") } }\n"
      "    private fun say(line: String) {"),
     "T10 a bare Material button"),

    # ── T4: a screen draws outside the theme ────────────────────────────────
    ("a screen is drawn without the theme around it", SRC + "/WriterSettingsUi.kt",
     ("        setContent {\n            CloudWriterTheme {", "        setContent {\n            run {"),
     "calls setContent without CloudWriterTheme"),

    # ── T5: task 214 is undone by a restyle ─────────────────────────────────
    ("a price cell is allowed to wrap, undoing task 214", SRC + "/WriterSettingsUi.kt",
     ("                    maxLines = 1,", "                    maxLines = 2,"),
     "no longer capped at one line"),

    # ── T6: his phone reads English ─────────────────────────────────────────
    ("a card description ships with no Spanish twin", RES + "/values-es/strings.xml",
     ('    <string name="settings_screen_grammar_summary">Modo de corrección, las correcciones que se aplican en el teléfono y el servidor de LanguageTool</string>\n', ""),
     "has no Spanish; that card would read English"),

    ("a card loses its description entirely", SRC + "/MainActivity.kt",
     ("                        R.string.settings_screen_translation_summary,",
      "                        R.string.settings_screen_translation,"),
     "page description(s) (expected 5"),
]


def build_copy(work):
    dst = os.path.join(work, APP)
    # A walk with shutil.copy, not shutil.copytree, and the difference is extended
    # attributes: copytree reproduces them on files AND on directories, and on Android
    # every source carries a security.selinux attribute that cannot be set on the
    # destination, so copytree raises PermissionError and this control test could only
    # ever run on the build machine — the one place it is least needed. shutil.copy copies
    # the bytes and the mode and stops there, and the mode has to come along because the
    # copied tester is later executed.
    src = os.path.join(root, APP)
    for base, _dirs, files in os.walk(src):
        target = os.path.join(dst, os.path.relpath(base, src))
        os.makedirs(target, exist_ok=True)
        for name in files:
            shutil.copy(os.path.join(base, name), os.path.join(target, name))
    # The tester finds the repository root by walking up to a .git; without this
    # marker it would walk out of the copy and silently assert about the REAL
    # tree, reporting green while testing nothing that was broken.
    open(os.path.join(work, ".git"), "w").close()


failures = 0
for label, rel, (old, new), expected in BREAKS:
    with tempfile.TemporaryDirectory() as work:
        build_copy(work)
        target = os.path.join(work, rel)
        body = open(target, encoding="utf-8").read()
        # A silent no-op — the anchor moved and nothing was replaced — would run
        # the tester against PRISTINE source and report the assertion as blind.
        # It is the harness that is stale then, not the assertion, so it is its
        # own error rather than a failure of the check under test.
        if body.count(old) < 1:
            print("FAIL   plant failed: the anchor for %r is not in %s. The harness tested nothing." % (label, rel))
            failures += 1
            continue
        open(target, "w", encoding="utf-8").write(body.replace(old, new, 1))

        shutil.copy(os.path.join(root, "ac_cloud-writer/test/test-cloud-writer-ui.sh"),
                    os.path.join(work, APP, "test", "test-cloud-writer-ui.sh"))
        run = subprocess.run(["bash", os.path.join(work, APP, "test", "test-cloud-writer-ui.sh")],
                             capture_output=True, text=True)
        out = run.stdout + run.stderr
        if run.returncode == 0:
            print("FAIL   %s — the tester still PASSED. That assertion is blind." % label)
            failures += 1
        elif expected not in out:
            print("FAIL   %s — the tester went red but never named it (wanted %r)." % (label, expected))
            print("       it said: " + " | ".join(l for l in out.splitlines() if l.startswith("FAIL")))
            failures += 1
        else:
            print("ok     %s — caught" % label)

print()
if failures:
    print("FAILED %d of %d checks: test-cloud-writer-ui.sh is not seeing what it claims to" % (failures, len(BREAKS)))
    sys.exit(1)
print("PASS   all %d planted defects were caught and named" % len(BREAKS))
PYEOF
