#!/usr/bin/env bash
# Tester (#785): every producer of the Store's progress names its app and stage, and the API answers the same line.
# (#1524 the bar under the buttons is gone: the one Activity panel draws from the engine and the job board; what remains
# here guards the producers and /api/store/progress.)
#
# The owner's report: the bar showed progress without saying what it was doing.
# "Downloading 42%" with no app, no stage, no position; in a 12-app Update all
# nobody could tell which app was moving, what was next, or which had failed —
# the failure was gone the moment the next app started.
#
# The behaviour is proven in Robolectric (StoreProgressBarTest reads the rendered
# bar; StoreCacheStagesTest's #785 cases record every line a real batch
# publishes). What a Robolectric run cannot see is a producer that stops naming
# its app — a batch loop, a Fleet phase, a stage — while every test that drives
# the other producers stays green. So this guards the WIRING, and PROVES ITSELF
# BY MUTATION: each mutant below is a realistic regression applied to the real
# source text, and the validator must go RED on every one of them.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs"
exec python3 - "$LIB" <<'PY'
import re, sys
lib = sys.argv[1]
A = lib + "/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
U = lib + "/updater/src/main/java/com/diegonmarcos/superapp/updater/"
FILES = {"page": A + "StoreCloudFragment.kt", "stages": A + "StoreStages.kt", "api": A + "StoreDebugApi.kt",
         "fleet": U + "Fleet.kt", "prog": U + "UpdateProgress.kt"}
P = F = 0
def ok(m):
    global P; P += 1; print("  PASS: " + m)
def bad(m):
    global F; F += 1; print("  FAIL: " + m)
def code(t):  # comments say what was removed and why; never let them satisfy a check
    return "\n".join(l.split("//")[0] for l in re.sub(r"/\*.*?\*/", "", t, flags=re.S).splitlines())
src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}

def fun(t, name):
    """The body of `fun <name>(`, up to the next top-level member."""
    m = re.search(r"\n    (?:private |internal )?fun %s\(.*?(?=\n    (?:private |internal |@|/\*\*|//|fun |class |const |val |var )|\Z)" % re.escape(name), t, re.S)
    return m.group(0) if m else ""

CHECKS = [
    ("/api/store/progress answers the SAME object",
     lambda s: '"progress" -> progress()' in code(s["api"]) and "StoreStages.progress()" in fun(code(s["api"]), "progress")),
    ("Download all runs each app as its own keyed job (rows run concurrently, none shares a position)",
     lambda s: "StoreJobs.board.begin(app.pkg, app.label)" in fun(code(s["stages"]), "downloadAllRun") and "inParallel(go)" in fun(code(s["stages"]), "downloadAllRun")),
    ("Update all runs each app as its own keyed job",
     lambda s: "StoreJobs.board.begin(app.pkg, app.label)" in fun(code(s["stages"]), "updateAllRun") and "inParallel(go" in fun(code(s["stages"]), "updateAllRun")),
    ("Fleet's download phase (Install all, the auto chain) names each app, position and next",
     lambda s: re.search(r"STAGE_DOWNLOADING, versions\[app\.id\]\.orEmpty\(\), i \+ 1, batch\.size,\s*batch\.getOrNull\(i \+ 1\)", code(s["fleet"]))),
    ("Fleet's install phase names each app, position and next",
     lambda s: re.search(r"STAGE_INSTALLING, versions\[app\.id\]\.orEmpty\(\), i \+ 1, staged\.size,\s*staged\.getOrNull\(i \+ 1\)", code(s["fleet"]))),
    ("installing from the cache says verifying, then installing",
     lambda s: (lambda b: 0 <= b.find("STAGE_VERIFYING") < b.find("STAGE_INSTALLING") < b.find("installer(ctx, app, v)"))(fun(code(s["stages"]), "installCachedGated"))),
    ("Clear says clearing",
     lambda s: "STAGE_CLEARING" in fun(code(s["stages"]), "clear")),
    ("a stopped verb publishes the app AND the stage it stopped at",
     lambda s: re.search(r"State\.Failed\(s\.text, app\.id, app\.pkg,\s*stage = UpdateProgress\.job\?\.stage[^)]*\), app = app\.label\)", fun(code(s["stages"]), "named"))),
    ("a batch ends on its failures, not on the last app's frame",
     lambda s: "State.Failed(" in fun(code(s["stages"]), "finishBatch") and "finishBatch(out)" in fun(code(s["stages"]), "updateAllRun")),
    ("every batch end drops the job",
     lambda s: re.search(r"fun endBatch\(\)\s*\{\s*job = null;", code(s["prog"]))),
]

def validate(s):
    return [name for name, check in CHECKS if not check(s)]

print("== T1: every producer names its app, and the bar and the API read one line ==")
v = validate(src)
for name, _ in CHECKS:
    (bad if name in v else ok)(name)

print("== T2: the validator sees each regression (mutation) ==")
MUTANTS = [
    ("the API route is dropped", "api", '"progress" -> progress().toString()', ""),
    ("Download all stops keying its jobs", "stages",
     "StoreJobs.board.begin(app.pkg, app.label)\n            val s = download(ctx, app)",
     "val s = download(ctx, app)"),
    ("Update all stops keying its jobs", "stages",
     "StoreJobs.board.begin(app.pkg, app.label)\n            val before = installedCode(ctx, app)",
     "val before = installedCode(ctx, app)"),
    ("Fleet's download phase stops naming apps", "fleet",
     "UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label,\n                UpdateProgress.STAGE_DOWNLOADING",
     "UpdateProgress.beginBatch(UpdateProgress.Job(app.id, app.pkg, app.label,\n                UpdateProgress.STAGE_DOWNLOADING_X"),
    ("verifying is never said", "stages",
     "            UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)\n            // Re-verify", "            // Re-verify"),
    ("the failure forgets its stage", "stages",
     "stage = UpdateProgress.job?.stage ?: stageOf(s.failedAt), app = app.label))",
     "app = app.label))"),
    ("a batch ends clean whatever failed", "stages",
     "if (!dryRun) finishBatch(out)\n        return Batch(\"updateAll\"", "if (!dryRun) UpdateProgress.endBatch()\n        return Batch(\"updateAll\""),
    ("endBatch keeps a stale job", "prog", "fun endBatch() { job = null; ", "fun endBatch() { "),
]
for name, key, old, new in MUTANTS:
    if src[key].count(old) != 1:
        bad("mutant '%s' no longer applies — its anchor moved; re-aim it" % name); continue
    m = dict(src); m[key] = src[key].replace(old, new)
    (ok if validate(m) else bad)(("mutation goes RED: " if validate(m) else "mutation stayed GREEN: ") + name)

print("== RESULT(#785 store progress bar): %d passed, %d failed ==" % (P, F))
sys.exit(1 if F else 0)
PY
