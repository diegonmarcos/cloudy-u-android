#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-tools.sh can actually go red                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# AN ASSERTION NOBODY HAS WATCHED FAIL IS DECORATION, and this repository has
# shipped the proof more than once: an assertion that compared an expression to
# itself, a pipeline whose exit status was read through `tail`, a
# `case "$OUT" in *"[NEW]"*)` where the brackets made a one-character class,
# markers written relative to the wrong directory, and four testers that passed
# only because ripgrep was absent and their call failed open. Every one of them
# printed green over a defect.
#
# So this runs the sibling tester against a DELIBERATELY BROKEN COPY of the
# application, once per planted defect, and requires the matching assertion to
# name it. If the tester stops seeing, this goes red and says which check has
# gone blind.
#
# THE BREAKS ARE APPLIED TO A COPY IN A TEMPORARY DIRECTORY, never to the
# working tree. A harness that edits the repository and restores afterwards is
# one failed restore away from committing its own sabotage, and on a CI runner
# it would race the publish gate's source hash. The copy carries an empty .git
# marker because the tester finds the repository root by walking up to one.
#
# python3 does the planting: it is already required by build.json::tests.shell,
# and an in-place text edit that must match EXACTLY is one place where a silent
# no-op (the anchor moved, nothing was replaced) has to be an error rather than
# an empty diff. That case is checked explicitly below.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="$ROOT/ac_cloud-writer/test/test-cloud-writer-tools.sh"

[ -f "$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" <<'PYEOF'
import json, os, shutil, subprocess, sys, tempfile

root = sys.argv[1]

# Exactly what the tester reads. Copied rather than referenced so a planted
# defect cannot escape into the working tree.
NEEDED = [
    "ac_cloud-writer",
    "ab_cloud-libs-shared/libs/text-tools",
    "ab_cloud-libs-shared/libs/writer-ui",
    "ab_cloud-libs-shared/libs/writer-core",
    "aa_cloud-superapp/data/constellation-fleet.json",
]

APP = "ac_cloud-writer"
SRC = APP + "/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter"
WRITER_PKG = "com.diegonmarcos.cloudwriter"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"


def unregister_writer(text):
    """Re-point cloud-writer's Constellation entry at a package nobody publishes.

    A FUNCTION, NOT A LITERAL, AND THAT IS THE WHOLE POINT OF #363. This break
    used to be anchored on the string '"package": "com.diegonmarcos.cloudwriter"'
    — with a space after the colon, because that is how the file was formatted
    when the anchor was written. constellation-fleet.json is GENERATED, by
    `jq ... | map(tojson) | join(",\\n")` in aa_cloud-superapp/data/regen.sh, and
    #343 (8c5c001ee) moved to that compact one-entry-per-line emission to get the
    64-entry fleet under javac's 65,535-byte constant limit. tojson emits
    '"package":"..."' with no space. From that commit on the anchor matched zero
    times, planted nothing, and the harness reported a caught defect it had never
    planted — the exact vacuity it exists to detect, in the detector itself.

    Reading the file as JSON removes every way a generator is allowed to rewrite
    it from the blast radius: spacing, indentation, key order, line breaks,
    trailing newline. None of those are things cloud-writer controls or should
    ever be red about. What remains is the one condition that SHOULD break this
    break — no entry for this package — and that is not silence: StopIteration
    below is reported as a failure, and on an untouched tree it is also exactly
    what W8 in the sibling tester is already red about. The anchor and the
    assertion now fail together or not at all.
    """
    fleet = json.loads(text)
    entry = next(a for a in fleet["apps"] if a.get("package") == WRITER_PKG)
    entry["package"] = WRITER_PKG + ".absent"
    return json.dumps(fleet)

# (what a later change would plausibly do, which file, the edit, the assertion
#  that must name it). Every one of these is a real regression shape, not a
#  syntactic scribble: a service element added when somebody decides it is time
#  to serve, a summary routed through the rewrite engine, two tools quietly
#  sharing one model, a refusal deleted because it looked redundant.
BREAKS = [
    ("cloud-writer starts publishing the ITextTools action",
     APP + "/app/src/main/AndroidManifest.xml",
     ("        </activity>\n",
      '        </activity>\n\n'
      '        <service android:name=".WriterTextToolsService" android:exported="true"\n'
      '            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA">\n'
      '            <intent-filter>\n'
      '                <action android:name="com.diegonmarcos.superapp.texttools.ITextTools" />\n'
      '            </intent-filter>\n'
      '        </service>\n'),
     "W1"),

    ("Summary is sent through the rewrite engine", SRC + "/WriterTools.kt",
     ("            WriterTool.SUMMARY -> client.summariseWith(",
      "            WriterTool.SUMMARY -> client.enhanceWith("),
     "W2 SUMMARY does not call client.summariseWith"),

    ("Grammar Check quietly reuses Text Enhance's model", SRC + "/WriterTools.kt",
     ("WriterPrefs.modelFor(app, WriterTool.GRAMMAR, provider)",
      "WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider)"),
     "W2 the GRAMMAR arm"),

    # Was "Translate is handed a chat model it cannot use", planted against
    # `client.translate(text, translateTarget)`. Task 296 deleted that arm: Translate now
    # has a model of its own and goes through enhanceWith, so a resolved model is correct
    # rather than the defect. What is now the defect is Translate borrowing the ENHANCE
    # prompt, because enhanceWith carries the whole instruction in its prompt — with
    # enhancePrompt the reply comes back rewritten in the language it was typed in, which
    # looks like a working feature and is not one. So the planted break swaps the prompt,
    # not the model.
    # #800: Translate now runs on the Translation route, so the borrowed prompt is planted where
    # that route composes it.
    ("Translate silently borrows the Enhance prompt and rewrites instead of translating",
     SRC + "/WriterRoutes.kt",
     ("        val system = WriterRegistry.translatePrompt(languageId)\n",
      "        val system = WriterPrefs.enhancePrompt(context)\n"),
     "W2 the TRANSLATE arm does not send translatePrompt"),

    ("the Account token is written to a preference", SRC + "/WriterRoutes.kt",
     ("        val modelId = model(context, Function.TRANSLATION)\n",
      "        val modelId = model(context, Function.TRANSLATION)\n"
      "        WriterPrefs.put(context, \"x\", accountToken(context).orEmpty())\n"),
     "W4 WriterRoutes logs or stores something token-shaped"),

    ("a tap on an empty box returns without a word", SRC + "/WriterTools.kt",
     ("        if (text.isBlank()) {\n"
      "            onDone(WriterOutcome(tool, null, app.getString(R.string.run_nothing_to_send)))\n"
      "            return\n"
      "        }\n",
      "        if (text.isBlank()) {\n"
      "            return\n"
      "        }\n"),
     "W3 run() returns without reporting"),

    ("the owner's credential is read into this application", SRC + "/WriterPrefs.kt",
     ("    fun isSeeded(context: Context)",
      "    fun borrowKey(client: TextToolsClient, providerId: String) =\n"
      "        client.revealAiKey(providerId)\n\n"
      "    fun isSeeded(context: Context)"),
     "W4 revealAiKey is called from"),

    ("a token-shaped preference slot appears", SRC + "/WriterPrefs.kt",
     ('    const val KEY_PROVIDER = "ai_provider"',
      '    const val KEY_PROVIDER = "ai_provider"\n\n'
      '    const val KEY_TOKEN_PREFIX = "ai_token_"'),
     "W4 WriterPrefs declares a credential-shaped key"),

    ("Grammar Check falls back to the default style", SRC + "/WriterRegistry.kt",
     ("        val grammar = grammarStyle ?: return null",
      "        val grammar = grammarStyle ?: style(null)"),
     "W5 grammarPrompt() no longer returns null"),

    ("the Grammar refusal is deleted from run()", SRC + "/WriterTools.kt",
     ("        if (tool == WriterTool.GRAMMAR && WriterRegistry.grammarPrompt() == null) {",
      "        if (false) {"),
     "W5 run() no longer checks grammarPrompt()"),

    ("a provider defaults to a model it does not list", APP + "/build.json",
     # Anchored on the value task 296 put here, not the Google model it replaced: the
     # owner's "you will not use fuckkng google modelss" made z-ai/glm-5.3-flash the
     # default, and an anchor still naming the old value plants nothing.
     ('"default_model": "z-ai/glm-5.3-flash"',
      '"default_model": "z-ai/glm-withdrawn"'),
     "W6 openrouter: default_model"),

    ("the grammar style leaves the registry", APP + "/build.json",
     ('"grammar": {', '"grammar_disabled": {'),
     "W6 build.json::writer_ai.styles has no `grammar` entry"),

    ("the application id drifts from the reserved address", APP + "/build.json",
     ('"application_id": "com.diegonmarcos.cloudwriter"',
      '"application_id": "com.diegonmarcos.writer"'),
     "W7 application_id is"),

    ("the Constellation registry is never regenerated", FLEET,
     unregister_writer,
     "W8 constellation-fleet.json carries no entry"),
]

failures = []
for label, rel, edit, expect in BREAKS:
    stage = tempfile.mkdtemp(prefix="cloud-writer-vacuity-")
    try:
        os.mkdir(os.path.join(stage, ".git"))   # the marker the tester walks up to
        for item in NEEDED:
            src = os.path.join(root, item)
            dst = os.path.join(stage, item)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if os.path.isdir(src):
                # A walk with shutil.copy, not shutil.copytree, and the difference is
                # extended attributes: copytree reproduces them on files AND on
                # directories, and on Android every source carries a security.selinux
                # attribute that cannot be set on the destination, so copytree raises
                # PermissionError and this control test could only ever run on the build
                # machine — the one place it is least needed. shutil.copy copies the
                # bytes and the mode and stops there, and the mode has to come along
                # because the copied tester is later executed.
                for base, _dirs, files in os.walk(src):
                    target = os.path.join(dst, os.path.relpath(base, src))
                    os.makedirs(target, exist_ok=True)
                    for name in files:
                        shutil.copy(os.path.join(base, name), os.path.join(target, name))
            else:
                shutil.copy(src, dst)

        target = os.path.join(stage, rel)
        text = open(target).read()
        # A break is either a (find, replace) pair against source a human edits,
        # where a literal IS the right anchor, or a function against a GENERATED
        # file, where a literal is at the mercy of the generator's serialiser.
        if callable(edit):
            try:
                planted = edit(text)
            except (StopIteration, KeyError, ValueError) as exc:
                failures.append("%s: the planted edit could not be applied (%s: %s) — the anchor has moved "
                                "and this harness is no longer breaking what it names"
                                % (label, type(exc).__name__, exc))
                continue
        else:
            old, new = edit
            if text.count(old) != 1:
                failures.append("%s: the planted edit matched %d times, not once — the anchor has moved "
                                "and this harness is no longer breaking what it names"
                                % (label, text.count(old)))
                continue
            planted = text.replace(old, new, 1)
        # A find/replace whose two halves are equal plants nothing and leaves a
        # pristine copy the tester then passes. BYTES, so this catches that shape
        # for the literal pairs only: a callable that round-trips JSON rewrites
        # the whole file even when it changed no value, so `planted != text`
        # there proves nothing about the edit. The backstop for a callable is the
        # returncode check below — verified by deliberately neutering
        # unregister_writer, which this did NOT catch and "the tester still
        # passed" did. Left at bytes rather than grown into a per-format
        # semantic diff: the check that actually decides is already downstream.
        if planted == text:
            failures.append("%s: the planted edit changed nothing — this harness proved nothing" % label)
            continue
        open(target, "w").write(planted)

        out = subprocess.run(["bash", os.path.join(stage, "ac_cloud-writer/test/test-cloud-writer-tools.sh")],
                             capture_output=True, text=True)
        red = [l for l in out.stdout.split("\n") if l.startswith("FAIL") and expect in l]
        if out.returncode == 0:
            failures.append("%s: the tester still passed — that assertion proves nothing" % label)
        elif not red:
            seen = "; ".join(l for l in out.stdout.split("\n") if l.startswith("FAIL"))
            failures.append("%s: went red but NOT on %r (saw: %s)" % (label, expect, seen or out.stderr[-200:]))
        else:
            print("ok     watched red: %s" % label)
    finally:
        shutil.rmtree(stage, ignore_errors=True)

print("── %d of %d planted defects were caught ──" % (len(BREAKS) - len(failures), len(BREAKS)))
for f in failures:
    print("FAIL   " + f)
sys.exit(1 if failures else 0)
PYEOF
