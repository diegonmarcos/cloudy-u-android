#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-routes.sh can actually go red                  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# One planted defect per assertion, each in a COPY of the app (never the
# working tree), and the sibling tester must go red on the named check. An
# anchor that no longer matches exactly once is itself a failure: a break that
# plants nothing proves nothing.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="ac_cloud-writer/test/test-cloud-writer-routes.sh"
[ -f "$ROOT/$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" "$TESTER" <<'PYEOF'
import json, os, shutil, subprocess, sys, tempfile

root, tester = sys.argv[1], sys.argv[2]
APP = "ac_cloud-writer"
SRC = APP + "/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter/"
RES = APP + "/../ab_cloud-libs-shared/libs/writer-ui/src/main/res/"


def drop_key(key):
    def edit(text):
        d = json.loads(text)
        del d["update"][key]
        return json.dumps(d, indent=2)
    return edit


def routes_edit(fn, key, value):
    def edit(text):
        d = json.loads(text)
        d["writer_routes"][fn][key] = value
        return json.dumps(d, indent=2)
    return edit


# (label, file, (old, new) or callable, the check that must go red)
BREAKS = [
    ("probe asks before the bind", SRC + "ServingApp.kt",
     ("            if (!runner.awaitBound(bindWaitMs)) return ServingApp(pkg, label, version, State.SILENT)\n", ""), "S1"),
    ("awaitBound stops polling", SRC + "WriterTools.kt",
     ("while (!client.isConnected()) {", "if (!client.isConnected()) {"), "S1"),
    ("too-old string names no app", RES + "values/strings.xml",
     ("<string name=\"status_serving_app_too_old\">%1$s (version", "<string name=\"status_serving_app_too_old\">The app (version"), "S2"),
    ("silent folded into too old", SRC + "WriterSurface.kt",
     ("ServingApp.State.SILENT -> getString(", "ServingApp.State.SILENT -> getText("), "S2"),
    ("no Store button", SRC + "WriterSurface.kt",
     ("storeRepair.value = serving.needsStore", "storeRepair.value = false"), "S3"),
    ("no Store fallback", APP + "/build.json", drop_key("fallback_url"), "S4"),
    ("speech defaults to on-device", APP + "/build.json", routes_edit("speech", "default_route", "ml"), "R1"),
    ("translation defaults to a decision model", APP + "/build.json", routes_edit("translation", "default_model", "typesafe/jev-1.13"), "R1"),
    ("translate decides ad hoc", SRC + "WriterRoutes.kt",
     ("        val answer = Routing.run(\n            requested,\n            blocker,\n            model = { OpenRouter.translate(",
      "        val answer = runBoth(\n            requested,\n            blocker,\n            model = { OpenRouter.translate("), "R2"),
    ("on-device translation stops using libs:translate", SRC + "WriterRoutes.kt",
     ("textTools(context).translate(text, tag)", "textTools(context).enhance(text, tag)"), "R2"),
    ("Listen stops feeding the on-device engine", SRC + "Listen.kt",
     ("if (onDevice) v.feed(buf, n)", "if (false) v.feed(buf, n)"), "R3"),
    ("Listen skips auto-translation", SRC + "Listen.kt",
     ("WriterRoutes.translate(app, heardText, target)", "WriterRoutes.lastTranslation"), "R3"),
    ("the fallback is hidden on Translate", SRC + "WriterSurface.kt",
     ("R.string.route_fell_back", "R.string.route_answered_by"), "R4"),
    ("/api/writer/translate unregistered", SRC + "WriterDebugApi.kt",
     ('                "translate" -> translate(app, q).toString()\n', ""), "R5"),
    ("the debug provider is exported", APP + "/app/src/main/AndroidManifest.xml",
     ('android:authorities="${applicationId}.writerdebugapi"\n            android:exported="false"',
      'android:authorities="${applicationId}.writerdebugapi"\n            android:exported="true"'), "R5"),
    ("Listen is not a microphone service", APP + "/app/src/main/AndroidManifest.xml",
     ('android:foregroundServiceType="microphone"', 'android:foregroundServiceType="dataSync"'), "R6"),
    ("no route to the Routes page", SRC + "WriterSurface.kt",
     ("RoutesActivity::class.java", "AiRoutingActivity::class.java"), "R7"),
    ("documents stop being searched", SRC + "WriterSurface.kt", ("store.search(q)", "store.list()"), "D1"),
    ("the editor drops the rich drawing", SRC + "WriterSurface.kt", ("visualTransformation = look", "visualTransformation = VisualTransformation.None"), "D2"),
    ("bold becomes italic", APP + "/../ab_cloud-libs-shared/libs/writer-core/src/main/kotlin/com/diegonmarcos/cloudwriter/core/Format.kt", ('Markdown.wrap(text, start, end, "**")', 'Markdown.wrap(text, start, end, "_")'), "D3"),
    ("the toolbar stops drawing its groups", SRC + "WriterSurface.kt", ("FormatAction.GROUPS.forEachIndexed", "FormatAction.GROUPS.take(0).forEachIndexed"), "D3"),
    ("the outline does not jump", SRC + "WriterSurface.kt", ("selection = TextRange(h.offset)", "selection = TextRange(0)"), "D4"),
    ("find stops wrapping", SRC + "WriterSurface.kt", ("((findIndex.value + by) % n + n) % n", "(findIndex.value + by).coerceIn(0, n - 1)"), "D5"),
    ("no .txt export", SRC + "WriterSurface.kt", ('CreateDocument("text/plain")', 'CreateDocument("text/markdown")'), "D6"),
    ("the theme ignores the choice", SRC + "ui/Theme.kt", ("darkTheme: Boolean = WriterTheme.isDark()", "darkTheme: Boolean = isSystemInDarkTheme()"), "D7"),
    ("Listen keeps writing into a stopped editor", SRC + "WriterSurface.kt",
     ("        // From here on Listen appends to the document FILE: a stopped activity recomposes nothing.\n        ListenEngine.sink = null\n", ""), "D8"),
    ("a tool replaces the whole document", SRC + "WriterSurface.kt",
     ("text.substring(0, from) + r.text + text.substring(to)", "r.text"), "D9"),
    ("shared text is dropped", SRC + "WriterSurface.kt", ("open(store.create(shared))", "newDoc()"), "D10"),
    ("the empty state is dropped", SRC + "WriterSurface.kt", ('list.isEmpty() -> item(key = "empty")', 'list.isEmpty() -> item(key = "nothing")'), "D11"),
    ("the empty illustration is dropped", SRC + "ui/WriterComponents.kt", ("        EmptyDocsIllustration()\n", ""), "D11"),
    ("the route reply reads the token", SRC + "WriterRoutes.kt",
     ('            .put("online", online(context))\n', '            .put("online", online(context))\n            .put("t", accountToken(context))\n'), "R8"),
]

failures = []
for label, rel, edit, expect in BREAKS:
    stage = tempfile.mkdtemp(prefix="writer-routes-")
    try:
        open(os.path.join(stage, ".git"), "w").close()
        shutil.copytree(os.path.join(root, APP), os.path.join(stage, APP),
                        ignore=shutil.ignore_patterns("build", ".gradle"))
        # #1652 the app's UI and logic live in shared libs now; the planted defects are in those copies.
        for lib in ("writer-ui", "writer-core"):
            shutil.copytree(os.path.join(root, "ab_cloud-libs-shared/libs", lib), os.path.join(stage, "ab_cloud-libs-shared/libs", lib),
                            ignore=shutil.ignore_patterns("build", ".gradle"))
        target = os.path.join(stage, rel)
        text = open(target, encoding="utf-8").read()
        if callable(edit):
            try:
                planted = edit(text)
            except (KeyError, ValueError) as exc:
                failures.append("%s: the planted edit could not be applied (%s)" % (label, exc)); continue
        else:
            old, new = edit
            if text.count(old) != 1:
                failures.append("%s: the anchor matched %d times, not once — this harness no longer breaks what it names"
                                % (label, text.count(old))); continue
            planted = text.replace(old, new, 1)
        if planted == text:
            failures.append("%s: the planted edit changed nothing" % label); continue
        open(target, "w", encoding="utf-8").write(planted)
        out = subprocess.run(["bash", os.path.join(stage, tester)], capture_output=True, text=True)
        red = [l for l in out.stdout.split("\n") if l.startswith("FAIL") and (" %s " % expect) in l]
        if out.returncode == 0:
            failures.append("%s: the tester still passed — %s proves nothing" % (label, expect))
        elif not red:
            seen = "; ".join(l for l in out.stdout.split("\n") if l.startswith("FAIL"))
            failures.append("%s: went red but not on %s (saw: %s)" % (label, expect, seen or out.stderr[-300:]))
        else:
            print("ok     watched red: %s (%s)" % (label, expect))
    finally:
        shutil.rmtree(stage, ignore_errors=True)

print("── %d of %d planted defects were caught ──" % (len(BREAKS) - len(failures), len(BREAKS)))
for f in failures:
    print("FAIL   " + f)
sys.exit(1 if failures else 0)
PYEOF
