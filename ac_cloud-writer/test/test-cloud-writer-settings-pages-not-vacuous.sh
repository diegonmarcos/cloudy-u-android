#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-settings-pages.sh can actually go red              ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# AN ASSERTION NOBODY HAS WATCHED FAIL IS DECORATION. This repository has shipped
# the proof repeatedly: an assertion comparing an expression to itself, a
# pipeline's status read through `tail`, `case $OUT in *"[NEW]"*)` where the
# brackets made a one-character class, markers written relative to the wrong
# directory, four testers that passed only because ripgrep was absent, a grep
# that matched the commented-out line it was written to detect as missing, and a
# guard satisfied by its own KDoc. Every one printed green over a defect. The
# sibling tester itself shipped one on its very first run: its comment stripper
# ate the `//` of `https://` and then reported the URL as missing.
#
# So this runs the sibling against a DELIBERATELY BROKEN COPY, once per planted
# defect, and requires the matching assertion to NAME it. If a check goes blind,
# this goes red and says which one.
#
# EVERY BREAK BELOW IS A REAL REGRESSION SHAPE. Four of them are precisely how
# task 209 happened — a page pointed at the other application's storage, a
# settings file renamed to the shared one, a keyboard symbol imported, a page
# opened by Intent into the keyboard — because those are the ones that look
# completely fine on screen and pass any test that only asks whether a page
# exists.
#
# THE BREAKS ARE APPLIED TO A COPY IN A TEMPORARY DIRECTORY, never the working
# tree. A harness that edits the repository and restores afterwards is one failed
# restore away from committing its own sabotage.
#
# python3 plants them: an in-place edit that must match EXACTLY is one place
# where a silent no-op — the anchor moved, nothing was replaced — has to be an
# error rather than an empty diff, and that case is checked explicitly.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="$ROOT/ac_cloud-writer/test/test-cloud-writer-settings-pages.sh"

[ -f "$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" <<'PYEOF'
import os, shutil, subprocess, sys, tempfile

root = sys.argv[1]

# Exactly what the tester reads, and nothing else. The tester is own-source-only
# by design — that is what lets it FAIL a release rather than be downgraded to a
# warning — so this copy is one directory.
NEEDED = ["ac_cloud-writer", "ac_cloud-account/build.json", "ab_cloud-libs-shared/libs/writer-ui", "ab_cloud-libs-shared/libs/writer-core"]   # + the fleet declaration the LanguageTool default is baked from (#1093)

APP = "ac_cloud-writer"
SRC = APP + "/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter"
RES = APP + "/../ab_cloud-libs-shared/libs/writer-ui/src/main/res"

# (what a later change would plausibly do, which file, the exact edit, the text
#  the failing assertion must contain).
BREAKS = [
    # ── the four shapes of task 209 ──────────────────────────────────────────
    ("a page is pointed at the keyboard's own settings screen", SRC + "/WriterSurface.kt",
     ("                        onClick = { startActivity(Intent(activity, page.screen)) },",
      "                        onClick = {\n"
      "                            startActivity(Intent().setClassName(\"com.diegonmarcos.cloudkeyboard\",\n"
      "                                \"helium314.keyboard.settings.SettingsActivity\"))\n"
      "                        },"),
     "P2 this application's code names 'helium314'"),

    ("the settings file is renamed to the one the translate library shares", SRC + "/WriterPrefs.kt",
     ('    private const val FILE = "text_tools"',
      '    private const val FILE = "com.diegonmarcos.cloudwriter_preferences"'),
     "P2 WriterPrefs.FILE is not the private text_tools file"),

    ("a page opens a preference store of its own", SRC + "/GrammarCheckActivity.kt",
     ("    override fun PageContent() {",
      "    override fun PageContent() {\n"
      "        val other = getSharedPreferences(\"shared_text_tools\", android.content.Context.MODE_PRIVATE)\n"
      "        other.getString(\"grammar_mode\", null)"),
     "getSharedPreferences calls (expected exactly 1"),

    ("a page reads the keyboard's TranslatePrefs constants", SRC + "/TranslationActivity.kt",
     ("import java.util.Locale",
      "import com.diegonmarcos.superapp.translate.TranslatePrefs\nimport java.util.Locale"),
     "P2 this application's code names 'TranslatePrefs'"),

    # ── the pages themselves ────────────────────────────────────────────────
    ("a settings page is exported", APP + "/app/src/main/AndroidManifest.xml",
     ('<activity android:name="com.diegonmarcos.cloudwriter.AiRoutingActivity"\n'
      '            android:exported="false"',
      '<activity android:name="com.diegonmarcos.cloudwriter.AiRoutingActivity"\n'
      '            android:exported="true"'),
     "P1 AiRoutingActivity is exported"),

    ("a page is dropped from the manifest", APP + "/app/src/main/AndroidManifest.xml",
     ('<activity android:name="com.diegonmarcos.cloudwriter.GrammarCheckActivity"\n'
      '            android:exported="false"\n'
      '            android:label="@string/settings_screen_grammar"\n'
      '            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode" />',
      ""),
     "P1 GrammarCheckActivity is not declared in this manifest"),

    # The four pages are now one list of Page(label, summary, icon, screen) entries rather than
    # one list of `label to class` pairs, so what is removed here is the whole entry. The assertion
    # it must still trip is unchanged: MainActivity has to NAME every activity the manifest
    # declares, or the owner has a settings page nothing on the phone can open.
    ("the main screen stops opening one of the pages", SRC + "/WriterSurface.kt",
     ("                    Page(\n"
      "                        R.string.settings_screen_translation,\n"
      "                        R.string.settings_screen_translation_summary,\n"
      "                        Icons.Rounded.Translate,\n"
      "                        TranslationActivity::class.java,\n"
      "                    ),\n", ""),
     "P1 the main screen never starts TranslationActivity"),

    # ── the preview ─────────────────────────────────────────────────────────
    ("the prompt preview is hardcoded to what it says today", SRC + "/TextEnhanceActivity.kt",
     ("            WriterPrefs.enhancePrompt(this),",
      '            "You are a text rewriting engine inside a phone keyboard.",'),
     "P4 the Text Enhancements page does not build its preview"),

    # Deleting the bare `promptGeneration.value` read is the Compose shape of this
    # regression, and it is a realistic one precisely because the line looks inert.
    ("the preview stops being recomposed when a setting changes", SRC + "/TextEnhanceActivity.kt",
     ("        promptGeneration.value\n", ""),
     "P4 nothing re-reads the preview after a pick"),

    ("a menu that shapes the prompt is wired without the preview", SRC + "/TextEnhanceActivity.kt",
     ("{ WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_TONE, it); refreshPrompt() }",
      "{ WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_TONE, it) }"),
     "references to refreshPrompt()"),

    # ── the try-it box ──────────────────────────────────────────────────────
    ("the try-it box becomes decorative", SRC + "/TextEnhanceActivity.kt",
     ("        runner.run(WriterTool.ENHANCE, testInput.value) { outcome ->",
      "        testOutput.value = testInput.value; if (false) runner.run(WriterTool.SUMMARY, \"\") { outcome ->"),
     "P5 the try-it box does not run anything through WriterToolRunner"),

    # ── LanguageTool and the mesh ───────────────────────────────────────────
    ("the LanguageTool URL is 'fixed' to a public server", SRC + "/WriterPrefs.kt",
     ('val DEFAULT_GRAMMAR_REMOTE_URL: String get() = BuildConfig.LANGUAGETOOL_URL',
      'val DEFAULT_GRAMMAR_REMOTE_URL: String get() = "https://api.languagetool.org/v2/check"'),
     "P6 the LanguageTool default is not"),

    ("Remote mode quietly runs the AI rewrite instead of refusing", SRC + "/WriterTools.kt",
     ("                val why = app.getString(R.string.grammar_remote_unreachable, WriterPrefs.grammarRemoteUrl(app))",
      "                val why = app.getString(R.string.run_no_reason)"),
     "P6 nothing refuses Remote mode"),

    # ── the price table's four paid-for fixes ───────────────────────────────
    ("the price column is scaled again, as it was when it read 100x too high", SRC + "/AiRoutingActivity.kt",
     ('String.format(Locale.US, "%.3f", v)', 'String.format(Locale.US, "%.3f", v * 100)'),
     "P7/217 the price column scales its number"),

    ("the as-of date is dropped from under the table", SRC + "/AiRoutingActivity.kt",
     ("        Note(getString(R.string.ai_pricing_baked, provider.pricingAsOf ?: \"?\", provider.label))",
      "        Note(provider.label)"),
     "P7/219 the table does not print the date"),

    ("a column is added without a width", SRC + "/AiRoutingActivity.kt",
     ("                getString(R.string.ai_pricing_col_slug),",
      "                getString(R.string.ai_pricing_col_slug),\n"
      "                getString(R.string.ai_pricing_col_model),"),
     "column headings against"),

    ("the app starts fetching the live catalogue itself", APP + "/app/src/main/AndroidManifest.xml",
     ("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">",
      "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
      "    <uses-permission android:name=\"android.permission.INTERNET\" />"),
     "P7/247 the manifest declares INTERNET"),

    # ── his phone is in Spanish ─────────────────────────────────────────────
    ("a Spanish label is replaced with the English one", RES + "/values-es/strings.xml",
     ("<string name=\"enhance_test_title\">Pruébalo</string>",
      "<string name=\"enhance_test_title\">Try it</string>"),
     "P8 enhance_test_title does not read exactly"),

    ("a string is added with no Spanish twin", RES + "/values/strings.xml",
     ("    <string name=\"settings_heading\">Settings</string>",
      "    <string name=\"settings_heading\">Settings</string>\n"
      "    <string name=\"settings_untranslated\">Advanced</string>"),
     "would draw in English on his phone"),

    # ── the escape that actually broke a build ──────────────────────────────
    #
    # Not hypothetical: run 34590327712 died at :app:mergeDebugResources on two
    # bare apostrophes. XML parses them, this container has no aapt, and forty
    # minutes of CI were spent to publish nothing.
    ("an apostrophe is written unescaped, as it was in 34590327712", RES + "/values/strings.xml",
     ("<string name=\"settings_heading\">Settings</string>",
      "<string name=\"settings_heading\">The keyboard's settings</string>"),
     "a bare ' inside a string resource"),

    ("a broken unicode escape reaches a string", RES + "/values-es/strings.xml",
     ("<string name=\"settings_heading\">Ajustes</string>",
      "<string name=\"settings_heading\">Ajustes \\uZZZZ</string>"),
     "is not followed by four hex digits"),

    # ── the defaults the report describes ───────────────────────────────────
    ("a default is changed without the report being changed", SRC + "/WriterPrefs.kt",
     ("    const val DEFAULT_GRAMMAR_MODE = GRAMMAR_AI",
      "    const val DEFAULT_GRAMMAR_MODE = GRAMMAR_REMOTE"),
     "P9 DEFAULT_GRAMMAR_MODE is not GRAMMAR_AI"),

    ("the registry's Enhance default moves under the page", APP + "/build.json",
     ('"default_tone": "keep"', '"default_tone": "formal"'),
     "P9 writer_ai.default_tone"),

    # ── a page writing behind WriterPrefs' back ─────────────────────────────
    ("a page writes a raw preference key", SRC + "/GrammarCheckActivity.kt",
     ("        ) { WriterPrefs.put(this, WriterPrefs.KEY_GRAMMAR_PT_VARIANT, it) }",
      "        ) { WriterPrefs.put(this, \"grammar_pt_variant\", it) }"),
     "instead of a WriterPrefs constant"),
]


def build_copy(work):
    for rel in NEEDED:
        dst = os.path.join(work, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        # A walk with shutil.copy, not shutil.copytree, and the difference is extended
        # attributes: copytree reproduces them on files AND on directories, and on Android
        # every source carries a security.selinux attribute that cannot be set on the
        # destination, so copytree raises PermissionError and this control test could only
        # ever run on the build machine — the one place it is least needed. shutil.copy
        # copies the bytes and the mode and stops there. The mode is not decoration: the
        # copied tester is later EXECUTED, and without its executable bit the run dies in
        # subprocess instead of reporting on the planted defect.
        origin = os.path.join(root, rel)
        if os.path.isfile(origin):
            shutil.copy(origin, dst)
            continue
        for base, _dirs, files in os.walk(origin):
            target = os.path.join(dst, os.path.relpath(base, origin))
            os.makedirs(target, exist_ok=True)
            for name in files:
                shutil.copy(os.path.join(base, name), os.path.join(target, name))
    # The tester finds the repository root by walking up to a .git; without this
    # marker it would walk out of the copy and silently assert about the REAL
    # working tree, reporting every planted defect as absent.
    open(os.path.join(work, ".git"), "w").close()


def run_tester(work):
    return subprocess.run(
        [os.path.join(work, "ac_cloud-writer/test/test-cloud-writer-settings-pages.sh")],
        capture_output=True, text=True)


failures = 0

# THE CONTROL. An unbroken copy must PASS, or every "it went red" below proves
# nothing but that the copy itself is broken.
work = tempfile.mkdtemp(prefix="writer-pages-control-")
try:
    build_copy(work)
    r = run_tester(work)
    if r.returncode == 0:
        print("ok     control: the tester passes against an unbroken copy")
    else:
        print("FAIL   control: the tester FAILS against an unbroken copy, so nothing below means anything")
        print(r.stdout[-3000:])
        failures += 1
finally:
    shutil.rmtree(work, ignore_errors=True)

for what, rel, (old, new), must_say in BREAKS:
    work = tempfile.mkdtemp(prefix="writer-pages-")
    try:
        build_copy(work)
        path = os.path.join(work, rel)
        body = open(path, encoding="utf-8").read()
        if old not in body:
            # A no-op plant is the worst outcome available here: the tester would
            # pass against an UNBROKEN file and this harness would report that the
            # assertion is alive. It is an error, loudly.
            print("FAIL   plant failed: the anchor for '%s' is not in %s. The harness tested nothing." % (what, rel))
            failures += 1
            continue
        open(path, "w", encoding="utf-8").write(body.replace(old, new, 1))
        r = run_tester(work)
        out = r.stdout + r.stderr
        if r.returncode == 0:
            print("FAIL   %s — the tester still PASSED. That assertion is blind." % what)
            failures += 1
        elif must_say not in out:
            print("FAIL   %s — the tester went red but never said %r. It failed for some other reason."
                  % (what, must_say))
            failures += 1
        else:
            print("ok     %s — caught" % what)
    finally:
        shutil.rmtree(work, ignore_errors=True)

print()
if failures:
    print("FAILED %d of %d checks: test-cloud-writer-settings-pages.sh is not seeing what it claims to"
          % (failures, len(BREAKS) + 1))
    sys.exit(1)
print("PASS   all %d planted defects were caught and named" % len(BREAKS))
PYEOF
