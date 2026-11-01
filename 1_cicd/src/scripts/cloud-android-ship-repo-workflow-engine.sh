#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-ship-repo-workflow-engine                          ║
# ║                                                                  ║
# ║ Compiles each config tier's src/ → that tier's dist/ and deploys ║
# ║ generated artifacts to .github/, .gitmodules, gitconfig, hooks.   ║
# ║                                                                  ║
# ║ Invoked as: ./9_others/build.sh (via symlink)                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu

# Repo root by upward search, not a fixed ../ count. This script has moved
# twice now (1_configs/src/scripts → 1_configs/src/gha/scripts →
# 1_cicd/src/scripts) and each time a literal ../../.. would have resolved one
# level off, silently, with every path built from it landing in the wrong place.
CLOUD_ANDROID_ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

# Config tiers. Each owns its own dist/, so a source and its compiled form sit
# together instead of every artifact landing in one flat 1_configs/dist.
GIT_SRC="$CLOUD_ANDROID_ROOT/_dotfiles/git/src";     GIT_DIST="$CLOUD_ANDROID_ROOT/_dotfiles/git/dist"
APPS_SRC="$CLOUD_ANDROID_ROOT/_dotfiles/apps/src";   APPS_DIST="$CLOUD_ANDROID_ROOT/_dotfiles/apps/dist"
CICD_SRC="$CLOUD_ANDROID_ROOT/1_cicd/src";   CICD_DIST="$CLOUD_ANDROID_ROOT/1_cicd/dist"
LIB_SRC="$CLOUD_ANDROID_ROOT/9_others/src"

. "$CICD_SRC/scripts/cloud-android-ship-lib.sh"

# 1_cicd/dist is DERIVED IN FULL from 1_cicd/src, so it is rebuilt from empty
# rather than copied over. Copying over only ever adds: a file deleted from
# src/ stayed in dist/ forever, and dist/scripts is what GitHub actually runs
# (.github/workflows/scripts symlinks to it). That accumulation is exactly the
# drift this generator is supposed to prevent, and it had already produced
# three fossils -- a literal file named "*.sh" from a glob that once failed to
# expand, and two guards that src/ deleted but dist/ kept serving.
#
# Only these three directories are purged. .github/workflows is NOT, because
# it holds workflows with no source (enhance-silence-guard.yml,
# ship-superapp-data-regen.yml) that a purge would silently delete; and the
# dotfiles tier is additive on purpose because its targets mix managed config
# with per-machine state.
rm -rf "$CICD_DIST/scripts" "$CICD_DIST/cicd" "$CICD_DIST/actions" "$CICD_DIST/data"
mkdir -p "$CICD_DIST/scripts" "$CICD_DIST/cicd" "$CICD_DIST/actions" "$CICD_DIST/data" \
         "$GIT_DIST/hooks" "$GIT_DIST/modules"

# ── scripts: copy source → dist with read-only header ──────────────
log_step "dist/scripts"
for f in "$CICD_SRC/scripts/"*.sh "$CICD_SRC/scripts/"*.py; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    {
        echo "# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/$base ───"
        cat "$f"
    } > "$CICD_DIST/scripts/$base"
    chmod +x "$CICD_DIST/scripts/$base"
done

# ── prune dist artifacts whose source is gone ──────────────────────
#
# Every loop in this generator WRITES into dist/ and none of them removes, so a
# renamed or deleted source leaves its old artifact behind for ever. That twin is
# executable, carries the same GENERATED header and looks entirely current, and
# `.github/workflows/generated-up-to-date` then fails on it — not only for the
# commit that caused it, but for the next stranger who pushes on top, who has no
# idea what the diff is about. Renaming the enhance silence guard did exactly that.
#
# dist/ is derived in full, so a file in it with no source is not history, it is
# residue. Deliberately limited to the two dist directories this script generates:
# .github/workflows/ is NOT pruned, because a workflow can legitimately live there
# without a source in this tier and deleting a live one is not a generator's call.
log_step "prune dist artifacts with no source"
for _pair in "scripts" "cicd"; do
    _dist="$CICD_DIST/$_pair"; _src="$CICD_SRC/$_pair"
    for _f in "$_dist"/*; do
        [ -e "$_f" ] || continue
        _b=$(basename "$_f")
        [ -e "$_src/$_b" ] || { echo "  pruned $_pair/$_b — 1_cicd/src/$_pair/$_b no longer exists"; rm -f "$_f"; }
    done
done
unset _pair _dist _src _f _b

# ── cicd: engine ↔ vendored build.sh parity ────────────────────────
#
# Every app ships a VENDORED COPY of its engine at <app>/build.sh, and the ship
# workflow runs THAT file — 1_cicd/src/scripts/*-engine.sh is only its source.
# Editing the engine alone changes nothing at ship time, which is exactly how
# the ABI gate and the GHCR auto-delete gate came to exist in the engine and
# not in the file GitHub executed. That drift then produced two wrong outage
# diagnoses in one day, because the code being read was not the code running.
#
# So the copy relationship is now DECLARED (build.json::vendored_engine) and
# ENFORCED here: byte-identical or the generator goes red. It is also why the
# publish gate hashes the vendored build.sh rather than the engine — a
# parity-fixing sync lands in the app tree and correctly republishes that app.
log_step "verify engine ↔ vendored build.sh parity"
_parity_bad=0
for _bj in "$CLOUD_ANDROID_ROOT"/*/build.json; do
    [ -e "$_bj" ] || continue
    _app="$(basename "$(dirname "$_bj")")"
    _eng="$(jq -r '.vendored_engine // empty' "$_bj")"
    [ -n "$_eng" ] || continue
    _src="$CICD_SRC/scripts/$_eng"
    if [ ! -f "$_src" ]; then
        log_error "$_app/build.json declares vendored_engine=$_eng — no such engine in 1_cicd/src/scripts/"
        _parity_bad=1; continue
    fi
    if ! cmp -s "$_src" "$CLOUD_ANDROID_ROOT/$_app/build.sh"; then
        log_error "$_app/build.sh has DRIFTED from its engine $_eng — the vendored copy is what CI runs"
        log_error "  resync with: cp 1_cicd/src/scripts/$_eng $_app/build.sh"
        _parity_bad=1
    fi
done
unset _bj _app _eng _src
[ "$_parity_bad" = "0" ] || exit 1
unset _parity_bad

# ── cicd: render the ship workflows a build mode selects ────────────
#
# An app whose build.json declares build.mode does not own a hand-written ship
# workflow: 1_cicd/src/cicd/ship-<name>.yml is RENDERED from
# 1_cicd/src/templates/ship-<mode>.yml.in, with the app's directory, name, host,
# engine and shared module directories substituted from that build.json. It is
# rendered BEFORE the trigger sync and the published-nothing guard below, so
# both apply to it exactly as they apply to every other ship workflow.
#
# REFUSES to render a mode with no template, a template placeholder with no
# value, or a mode declared without the host that decides where it runs — a
# workflow with `runs-on:` left empty is not a workflow GitHub will run.
log_step "render ship workflows selected by build.json::build.mode"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYRENDER' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
bad = []

for build_json in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
    config = json.load(open(build_json))
    build = config.get("build") if isinstance(config.get("build"), dict) else {}
    mode = build.get("mode")
    if not mode:
        continue
    app = os.path.basename(os.path.dirname(build_json))
    template = os.path.join(root, "1_cicd/src/templates", "ship-%s.yml.in" % mode)
    if not os.path.isfile(template):
        bad.append("%s/build.json: build.mode %r has no 1_cicd/src/templates/ship-%s.yml.in" % (app, mode, mode))
        continue
    missing = [k for k in ("name", "vendored_engine") if not config.get(k)] + ([] if build.get("host") else ["build.host"])
    if missing:
        bad.append("%s/build.json: build.mode %r needs %s" % (app, mode, ", ".join(missing)))
        continue

    modules = build.get("modules") if isinstance(build.get("modules"), dict) else {}
    module_dirs = sorted(os.path.normpath(os.path.join(app, m["dir"]))
                         for m in modules.values() if isinstance(m, dict) and m.get("dir"))
    values = {
        "APP_DIR": app,
        "APP_NAME": config["name"],
        "BUILD_HOST": build["host"],
        "ENGINE": config["vendored_engine"],
        "MODULE_TRIGGER_PATHS": "\n".join('      - "%s/**"' % d for d in module_dirs),
    }
    text = open(template).read()
    for key, value in values.items():
        text = text.replace("@%s@" % key, value)
    leftover = sorted(set(re.findall(r"@[A-Z_]+@", text)))
    if leftover:
        bad.append("%s: template placeholders with no value: %s" % (os.path.basename(template), " ".join(leftover)))
        continue

    out = os.path.join(root, "1_cicd/src/cicd", "ship-%s.yml" % config["name"])
    if not os.path.exists(out) or open(out).read() != text:
        open(out, "w").write(text)

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYRENDER

# ── cicd: render the wasm ship workflows (#876) ──────────────────────
#
# A SECOND, independent pass: an app whose build.json declares a `web` block ships a
# Kotlin/Wasm page next to its APK, from 1_cicd/src/templates/ship-wasm.yml.in to
# 1_cicd/src/cicd/ship-<name>-wasm.yml. It is a separate workflow because it ships
# ALONE (a web edit must not republish an APK, an APK edit must not rebuild the
# page), so the app's APK workflow excludes <app>/<web.dir> (see the trigger sync
# below) and this one watches only: the web dir, the shared web root, the
# src/commonMain of each lib ab_cloud-libs-shared/web/web.json::libs names, the
# page's tester and its own generated file + template. Every one of those must be
# declared in rebuild-isolation.json::explicit_inputs for the web dir, which is how
# the isolation guard keeps a widened trigger from going unnoticed.
#
# REFUSES to render a `web` block missing a field, a template placeholder with no
# value, or a web.json libs entry with no src/commonMain/kotlin (a lib joins the page
# only once its commonMain exists; cloud-android-wasm-purity-guard.py then holds it).
log_step "render wasm ship workflows selected by build.json::web"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYWASM' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
bad = []
template = os.path.join(root, "1_cicd/src/templates/ship-wasm.yml.in")
web_libs = json.load(open(os.path.join(root, "ab_cloud-libs-shared/web/web.json"))).get("libs") or []

for build_json in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
    config = json.load(open(build_json))
    web = config.get("web")
    if not isinstance(web, dict):
        continue
    # web.kind "container"/"webapp" is the apk-manifest's public-host declaration
    # (cloud-android-apk-manifest.sh), not a Kotlin/Wasm page: nothing to ship here.
    if web.get("kind", "wasm") != "wasm":
        continue
    app = os.path.basename(os.path.dirname(build_json))
    if not os.path.isfile(template):
        bad.append("%s/build.json declares web but 1_cicd/src/templates/ship-wasm.yml.in is missing" % app)
        continue
    missing = [k for k in ("dir", "host", "artifact", "gh_asset", "portal_slug", "entry") if not web.get(k)]
    if not config.get("name"):
        missing.append("name")
    if missing:
        bad.append("%s/build.json: web block needs %s" % (app, ", ".join(missing)))
        continue
    if not os.path.isdir(os.path.join(root, app, web["dir"])):
        bad.append("%s/build.json: web.dir %r is not a directory" % (app, web["dir"]))
        continue
    paths = ["ab_cloud-libs-shared/web/**"]
    for lib in web_libs:
        if not os.path.isdir(os.path.join(root, "ab_cloud-libs-shared/libs", lib, "src/commonMain/kotlin")):
            bad.append("ab_cloud-libs-shared/web/web.json: libs names %r, which has no src/commonMain/kotlin" % lib)
        paths.append("ab_cloud-libs-shared/libs/%s/src/commonMain/**" % lib)
    tests = ((config.get("tests") or {}).get("shell") or {}).get("dir")
    if tests and web.get("tester") and os.path.isfile(os.path.join(root, app, tests, web["tester"])):
        paths.append("%s/%s/%s" % (app, tests, web["tester"]))
    # web.inputs (#1367): files of the app that the page BAKES IN, watched and hashed like the page's own dir.
    # Cloud Me's page draws build.json::ui as its nav, so a nav edit there must rebuild and republish the page.
    for extra in web.get("inputs") or []:
        if not os.path.exists(os.path.join(root, app, extra)):
            bad.append("%s/build.json: web.inputs names %r, which does not exist" % (app, extra))
        paths.append("%s/%s" % (app, extra))
    wf = "1_cicd/src/cicd/ship-%s-wasm.yml" % config["name"]
    paths += [wf, "1_cicd/src/templates/ship-wasm.yml.in"]
    # What the publish gate HASHES: everything watched, as plain tree paths, plus the fleet manifest
    # the build bakes into the page (shared manifests are hashed into what reads them, never watched).
    hashed = ["%s/%s" % (app, web["dir"])] + [re.sub(r"/\*\*$", "", p) for p in paths] \
             + ["aa_cloud-superapp/data/constellation-fleet.json"]
    values = {
        "APP_DIR": app,
        "APP_NAME": config["name"],
        "WEB_INPUT_PATHS": "\n".join("          %s" % p for p in sorted(set(hashed))),
        "WEB_DIR": web["dir"],
        "BUILD_HOST": web["host"],
        "WASM_ASSET": web["gh_asset"],
        "PORTAL_SLUG": web["portal_slug"],
        "ENTRY": web["entry"],
        # web.gradle_extra: optional flags for the wasm build, e.g. " --refresh-dependencies" to step over a restored
        # Gradle cache that recorded a failed lookup (#876: cloud-drive). Empty for every app that does not set it.
        "GRADLE_EXTRA": web.get("gradle_extra", ""),
        "WEB_TRIGGER_PATHS": "\n".join('      - "%s"' % p for p in sorted(paths)),
    }
    text = open(template).read()
    for key, value in values.items():
        text = text.replace("@%s@" % key, value)
    leftover = sorted(set(re.findall(r"@[A-Z_]+@", text)))
    if leftover:
        bad.append("ship-wasm.yml.in: template placeholders with no value: %s" % " ".join(leftover))
        continue
    out = os.path.join(root, wf)
    if not os.path.exists(out) or open(out).read() != text:
        open(out, "w").write(text)

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYWASM

# ── cicd: derive trigger paths from the data that declares them ─────
#
# on:push:paths was hand-maintained beside build.json, so it drifted silently
# and in the worst way: a workflow watching nothing real never runs and nobody
# gets an error. Three had already drifted when a check was added here -- two
# watching their own generated copy (self-triggering on unrelated commits),
# one watching a sibling repo a path filter can never see.
#
# Checking was not enough: the check told you a module-map dir was unwatched
# and then you hand-added it, which is the drift it was meant to end. Every
# dir the module map declares is now ADDED here automatically, and dead
# entries (paths no longer in the repo, and the workflow's own generated copy,
# which self-triggers on every unrelated commit that runs this generator) are
# dropped.
#
# It UNIONS rather than replaces, deliberately. A module map is a floor, not a
# ceiling: the fork apps have no `modules` block at all and re-point
# projectDir from settings.gradle.kts, and ab_cloud-libs-shared/lib-apks
# legitimately builds EVERY shared module — an input set no map expresses. A
# derivation that replaced the block silently deleted five real triggers from
# gallery on its first run, which is the exact failure mode (a workflow
# watching nothing real never runs and reports no error) this section exists
# to prevent.
#
# The publish gate then hashes THIS list rather than a second, parallel one —
# see cloud-android-source-identity.sh. Anything that can start a build is
# therefore also something the gate weighs, so the gate can never skip a
# rebuild that a real change triggered.
#
# ── guard triggers (#763) ──
# Every guard used to run on every push: a doc edit started 23 guard runs. The
# paths a guard reads are declared once, in 1_cicd/src/data/guard-triggers.json,
# and injected here BEFORE the sync below validates them. Refuses to generate
# while any push-triggered workflow is in neither list, so a new guard has to
# say whether it is narrowed. guard-trigger-coverage-guard.yml proves the lists
# against an strace of each guard.
log_step "inject guard triggers"
PYTHONDONTWRITEBYTECODE=1 python3 "$CICD_SRC/scripts/cloud-android-guard-triggers.py" inject "$CLOUD_ANDROID_ROOT" || exit 1

# ── #796: each gradle root's slice of the fleet's debug-API port table ──
# ONE declaration (1_cicd/src/data/debug-ports.json) → <root>/debug-api.json
# for every root that compiles libs:devtools, read by its build.gradle. The
# table itself is no longer a compile input of every app; generated-up-to-date
# fails when a slice on disk is not what the table produces.
log_step "debug-api.json slices"
PYTHONDONTWRITEBYTECODE=1 python3 "$CICD_SRC/scripts/cloud-android-mesh-slices-gen.py" "$CLOUD_ANDROID_ROOT" || exit 1

# ── #870: only runtime engines ship a Cloud-Lib-*.apk ──
log_step "lib-apks engines only"
PYTHONDONTWRITEBYTECODE=1 python3 "$CICD_SRC/scripts/cloud-android-lib-apks-engines.py" sync "$CLOUD_ANDROID_ROOT" || exit 1

log_step "sync workflow triggers"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYEOF' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
apps = {d for d in os.listdir(root)
        if os.path.exists(os.path.join(root, d, "build.json"))}
bad = []
pending = []   # (wf, name, text, lines, start, end, final, app) -- written after pass 2 (#836)

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    lines = text.split("\n")
    start = next((i for i, l in enumerate(lines) if l.strip() == "paths:"), None)
    if start is None:
        continue
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].strip() and not lines[i].startswith("      ")), len(lines))

    entries = [m.group(1) for m in
               (re.match(r'^      - "([^"]+)"', l) for l in lines[start + 1:end]) if m]
    # #836: deferred inputs live as comment lines inside the managed fence; they
    # are entries all the same (a hand-kept one must survive the next run).
    entries += re.findall(r'^      #   input: "([^"]+)"\s*$', "\n".join(lines[start + 1:end]), re.M)

    # The app is the workflow's declared WORK_DIR, not its filename. Two lib
    # aggregators (ship-cloud-libs, ship-cloud-keyboard-libs) build a
    # directory under ab_cloud-libs-shared/ whose name does not match their
    # slug, and a slug match sent the derivation at the wrong build.json.
    m = re.search(r'^  WORK_DIR:\s*(\S+)\s*$', text, re.M)
    app = m.group(1) if m and os.path.exists(
        os.path.join(root, m.group(1), "build.json")) else None
    if not app:
        slug = re.sub(r"^(ship|test)-|\.yml$", "", name)
        app = next((a for a in apps if a.split("_", 1)[-1] == slug), None)
    if not app:
        # Not an app workflow (ship.yml, health.yml, ...) -- only validate.
        # The fixed DIRECTORY prefix must exist: `.github/workflows/ship-*.yml`
        # checks .github/workflows, not a file literally named `ship-`.
        for e in entries:
            segs = e.lstrip("!").split("/")
            base = "/".join(segs[:next((i for i, s in enumerate(segs) if re.search(r"[*?\[]", s)), len(segs))])
            if base and not os.path.exists(os.path.join(root, base)):
                bad.append(f"{name}: watches a path not in this repo: {e}")
        continue

    derived = [f"{app}/**"]
    config = json.load(open(os.path.join(root, app, "build.json")))
    modules = config.get("modules", {})
    if isinstance(modules, dict):
        for mod in modules.values():
            if not isinstance(mod, dict) or not mod.get("dir"):
                continue
            shared = os.path.normpath(os.path.join(app, mod["dir"]))
            if shared.startswith(app + os.sep):
                continue
            derived.append(shared + "/**")
    derived.append(f"1_cicd/src/cicd/{name}")

    # ── shared libs: the app's REAL inputs, and nothing else (#763) ──
    # cloud_android_lib_closure.inputs is the one answer to "which shared lib
    # directories can move this app's bytes" (dependency closure, module map,
    # by-reference sources); the mesh guard's G2 holds the workflow to the same
    # answer. Every input is watched, and a hand-kept entry naming a shared lib
    # that is NOT an input is dropped: before this, cloud-vault watched analytics,
    # browser and updater and compiled none of them, so a change to any of them
    # rebuilt vault and the publish gate (which hashes this list) republished it
    # with nothing in it. Only for consumers of the shared libs: the lib-APK
    # builders under ab_cloud-libs-shared build every module by scan.
    sys.path.insert(0, os.path.join(root, "1_cicd/src/scripts"))
    sys.dont_write_bytecode = True
    from cloud_android_lib_closure import inputs as lib_inputs, SHARED_LIBS
    lib_entry = re.compile(re.escape(SHARED_LIBS) + r"/([\w-]+)/")
    real = None
    if not app.startswith("ab_cloud-libs-shared" + os.sep):
        real = lib_inputs(root, app)
        derived += [f"{SHARED_LIBS}/{m}/**" for m in sorted(real)]

    # ── THE TESTER DIRECTORY IS WATCHED, AND DELIBERATELY NOT HASHED (#370) ──
    #
    # These are two different questions and they had one answer, which is why
    # this list used to carry `!{app}/{tests.shell.dir}/**`:
    #
    #   "should this start a run?"      YES. A tester edit must run that tester.
    #   "should this publish an APK?"   NO. A tester cannot change the bytes.
    #
    # The `!` answered both with NO, so editing a tester ran NOTHING: the one
    # pipeline that executes it was the one the edit could not reach, and an
    # agent who improved a tester got a green board for it. Twelve of the
    # thirty-one ship workflows were in that state.
    #
    # The `!` is gone and `{app}/**` — already derived above — carries the
    # trigger. The publish half is unchanged and now lives where its data
    # lives: cloud-android-source-identity.sh reads the SAME
    # build.json::tests.shell.dir field this loop reads and drops it from the
    # identity, so run 34850225588 (the SuperApp republished for a commit that
    # touched only aa_cloud-superapp/test/) stays fixed. Both sides still
    # derive from one declaration, so they cannot drift; what changed is that
    # the declaration, not the `!` marker, is the thing they share.
    #
    # The run a tester edit now starts is CHEAP, not a full ship: the testers
    # run in the workflow's FIRST step, before any JDK/SDK/gradle, and the
    # publish gate then sees an unmoved identity and skips every build and
    # publish step after it. That is the separate lightweight test-only
    # pipeline, obtained without a second workflow to keep in sync.
    # #876: a build.json `web` block means <app>/<web.dir> ships by its own workflow
    # (ship-<name>-wasm.yml), so the APK workflow must neither start on it nor hash it.
    # cloud-android-source-identity.sh honours the same `!` entry.
    excluded = ["!%s/%s/**" % (app, config["web"]["dir"])] if isinstance(config.get("web"), dict) and config["web"].get("dir") else []

    # Hand-written entries survive unless they are dead (a path a filter can
    # never match) or the workflow's own generated copy. Exclusions are derived
    # in full above, never kept.
    kept = [e for e in entries
            if not e.startswith((".github/workflows/", "!"))
            and os.path.exists(os.path.join(root, e.split("*")[0].rstrip("/") or "."))
            and not (real is not None and lib_entry.match(e) and lib_entry.match(e).group(1) not in real)]
    for e in entries:
        if e not in kept and e not in excluded:
            print(f"  dropped {name}: {e}")

    # Exclusions LAST: GitHub applies paths in order, and a `!` entry only
    # removes what a positive entry BEFORE it matched.
    final = sorted(set(derived) | set(kept)) + excluded

    # The paths header is OURS, and OURS is now fenced (#483). The old engine
    # recognised its own header by exact text match against the CURRENT header
    # and emitted everything else as the author's, so the moment anyone re-worded
    # the header every old-wrapping line stopped matching, was reclassified as
    # authored, and was re-emitted below the new header forever. Recognition
    # after the fact cannot be made safe; explicit ownership markers can.
    # cloud_android_workflow_paths.py holds the header template and the fence
    # logic, and the regression tester (test-workflow-header-fence.test.sh)
    # drives that SAME module -- the code under test is the code that runs, for a
    # copy would drift the way the old header list did. Authored comments still
    # survive: anything after the END fence is carried through verbatim (the
    # ship-c3-morpheus.yml canary -- it lit up silently last time this was broken,
    # and a silent loss is worse than the drift this rewrite exists to fix).
    pending.append((wf, name, text, lines, start, end, final, app, len(entries)))

# ── #870: engine lib change -> its lib APK only; static lib change -> EVERY consumer ──
# (builds on #836.) The full list is still the app's build INPUT set -- the
# publish gate hashes all of it -- and what the app OWNS is watched per push:
#   watched   the app's own dirs, its fork/source engine script, its explicit_inputs
#             (1_cicd/src/data/rebuild-isolation.json), and EVERY STATIC shared lib it
#             compiles (any change in a lib it compiles is a change to what it ships:
#             an edit under libs:bottomnav alone once left Cloud Store unshipped until
#             it was dispatched by hand)
#   deferred  every ENGINE lib (shipped as its own Cloud-Lib APK, which apps bind at
#             runtime: an engine change starts no app), other apps' dirs, shared
#             manifests/data files and the workflow's own file -- comment lines inside
#             the managed fence. GitHub ignores them; the publish gate hashes them;
#             fleet-refresh.yml (schedule only) ships an app for a moved cross-app
#             source dir, never for an engine or manifest.
# ship-cloud-libs (the lib-APK builder) keeps every lib watched: one run that gates
# each engine APK on its own module dir only.
SPLIT_EXEMPT = {"ab_cloud-libs-shared/lib-apks"}
sys.path.insert(0, os.path.join(root, "1_cicd/src/scripts"))
sys.dont_write_bytecode = True  # importing the module must not drop a __pycache__ the generated-up-to-date guard flags
from cloud_android_workflow_paths import rewrite_paths_block, isolation, lib_apk_modules, refresh_input
iso = isolation(root)
engine_libs = lib_apk_modules(root)
import fnmatch as _fn

def owned(app, name, e):
    if e == app or e.startswith(app + "/"):
        return True
    if e in iso.get("explicit_inputs", {}).get(app, []):
        return True
    slug = re.sub(r"^ship-|\.yml$", "", name)
    return e.startswith("1_cicd/src/scripts/") and slug in e and any(_fn.fnmatchcase(e, g) for g in iso.get("own_extra_globs", []))

REFRESH_BEG = "  # ── MANAGED-REFRESH-TRIGGER (#836): fleet-refresh.yml dispatches this app ──"
for (wf, name, text, lines, start, end, final, app, n_entries) in pending:
    watched, deferred = final, []
    split = app not in SPLIT_EXEMPT and name.startswith("ship-")
    if name.startswith("ship-"):
        watched, deferred = [], []
        for e in final:
            mm = lib_entry.match(e)
            lib = mm.group(1) if mm else None
            if e == f"1_cicd/src/cicd/{name}":
                deferred.append(e)
            elif not split:
                watched.append(e)
            elif lib is not None:
                # a STATIC lib the app compiles is the app's own code: any change in it ships every consumer
                (watched if lib not in engine_libs else deferred).append(e)
            elif e.startswith("!") or owned(app, name, e):
                watched.append(e)   # `!` = the #876 web-dir exclusion: GitHub applies it only after the positives above
            else:
                deferred.append(e)
    block = rewrite_paths_block(lines[start + 1:end], watched, app, deferred)
    out = lines[:start] + block + lines[end:]
    # repository_dispatch, injected right under `on:`, so the refresh can start
    # THIS app's ship as a push-equivalent run (it publishes and stamps; a
    # workflow_dispatch does neither without inputs every workflow spells differently).
    if split:
        try:
            i = out.index(REFRESH_BEG)
            del out[i:i + 3]
        except ValueError:
            pass
        on = next((i for i, l in enumerate(out) if l == "on:"), None)
        if not any(refresh_input(root, d) for d in deferred):
            pass   # nothing the refresh would ever ship it for
        elif on is None:
            bad.append(f"{name}: no top-level `on:` to inject the refresh trigger under")
        else:
            out[on + 1:on + 1] = [REFRESH_BEG, "  repository_dispatch:",
                                  f"    types: [fleet-refresh-{app.replace('/', '-')}]"]
    new = "\n".join(out)
    if new != text:
        open(wf, "w").write(new)
        print(f"  synced {name}: {n_entries} → {len(watched)} push trigger paths, {len(deferred)} deferred")

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYEOF

# ── cicd: a ship run that published nothing must not be green ──────
#
# The guard existed on ship-cloud-superapp ALONE, written after the 2026-09-05
# update deadlock, and was never generalised — so the other 27 ship workflows
# could each report success having published nothing, which is the failure the
# owner pays for most often: he installs, sees no change, and reports the
# FEATURE as broken.
#
# INJECTED, not hand-copied into 28 files. The condition that decides whether a
# leg was SELECTED to publish is the publish step's own `if:`, and it is carried
# across here verbatim. That is the whole point: hand-copying it would create a
# second answer to "was this app selected?", the two would drift, and the guard
# would start firing on runs where publishing nothing is correct — which is how
# a guard gets disabled within a day. This repository has already paid for two
# copies of one idea twice (#228, #209).
#
# The body lives in cloud-android-publish-guard.sh — ONE implementation, called
# by all of them.
#
# REFUSES TO GENERATE if a ship workflow runs the publish gate and no step in
# that job carries `id: publish`. A new ship workflow therefore cannot be added
# without answering the question, and `generated files are up to date` runs this
# on every push, so it cannot rot back out either.
log_step "inject the published-nothing guard"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYGUARD' || exit 1
import glob, os, re, sys

root = sys.argv[1]
BEG = "      # ── MANAGED-PUBLISH-GUARD: injected by cloud-android-ship-repo-workflow-engine.sh ──"
END = "      # ── end MANAGED-PUBLISH-GUARD ──"
DOC = [
    "      # Runs on everything but a cancellation, and is HANDED the two facts it",
    "      # judges: the publish gate's own answer to \"did this source move?\", and the",
    "      # `id: publish` step's own `if:` rendered to true/false. Putting that `if:`",
    "      # on this step instead — the obvious generalisation — would stop the guard",
    "      # running in exactly the case it exists for, a publish skipped by its own",
    "      # condition. Copied verbatim from that step, so there is one copy in the",
    "      # file and the two cannot drift.",
    "      # Edit 1_cicd/src/scripts/cloud-android-publish-guard.sh, never this block.",
]
bad = []

def strip_managed(lines):
    out, i = [], 0
    while i < len(lines):
        if lines[i] == BEG:
            while i < len(lines) and lines[i] != END:
                i += 1
            i += 1                                    # past END
            if i < len(lines) and lines[i].strip() == "":
                i += 1                                # and its trailing blank
            while out and out[-1].strip() == "":
                out.pop()                             # and its leading blank
            continue
        out.append(lines[i]); i += 1
    return out

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    lines = strip_managed(text.split("\n"))

    marks = [i for i, l in enumerate(lines) if l == "        id: publish"]
    if not marks:
        # Only workflows that actually gate a publish owe a guard. A workflow
        # with no publish gate publishes nothing this can be asserted about.
        if "cloud-android-publish-gate.sh check" in text:
            bad.append("%s: runs the publish gate but no step carries `id: publish`"
                       " -- the published-nothing guard would have no subject" % name)
        new = "\n".join(lines)
        if new != text:
            open(wf, "w").write(new)
        continue

    for m in reversed(marks):                          # back to front: indices hold
        start = m
        while start >= 0 and not lines[start].startswith("      - "):
            start -= 1
        if start < 0:
            bad.append("%s: `id: publish` outside any step" % name); continue

        end = start + 1
        while end < len(lines) and (lines[end].strip() == ""
                                    or lines[end].startswith("        ")):
            end += 1
        while end > start + 1 and lines[end - 1].strip() == "":
            end -= 1

        cond = "true"
        for j in range(start + 1, end):
            if not lines[j].startswith("        if:"):
                continue
            # A wrapped `if:` cannot be carried across by this line-based
            # rewrite, and half a condition is worse than none. Refuse.
            if j + 1 < end and lines[j + 1].startswith("          "):
                bad.append("%s: the `id: publish` step's `if:` spans several lines;"
                           " put it on one line so the guard can carry it verbatim" % name)
                cond = None
                break
            v = lines[j].split("if:", 1)[1].strip()
            if v.startswith("${{") and v.endswith("}}"):
                v = v[3:-2].strip()
            # A status-check function is legal ONLY inside an `if:`. This
            # condition is HANDED to the guard as an argument, and GitHub
            # refuses to parse a workflow that calls one anywhere else -- it
            # then reports the run under the FILENAME instead of the workflow
            # name, which is how six ship workflows silently stopped existing
            # on 2026-09-10. Refusing here is the only place that can be caught
            # before a push. `success()` is also redundant: GitHub applies it
            # implicitly to any `if:` that names no status function, so
            # deleting it changes nothing about when the publish runs.
            if re.search(r"\b(success|failure|cancelled|always)\(\)", v):
                bad.append("%s: the `id: publish` step's `if:` calls a status function"
                           " (%s). It is redundant -- GitHub adds success() implicitly --"
                           " and it makes this workflow unparseable once the guard is"
                           " handed the expression. Remove it." % (name, v))
                cond = None
                break
            cond = v
            break
        if cond is None:
            continue

        # The gate's answer, but ONLY where a gate exists. A ship workflow with
        # no gate at all (ship-garmin-watchface) defines no step with
        # `id: gate`, and a reference to a step that is not there renders as an
        # empty string that reads like an answer. publish-gate-blast-radius
        # already forbids exactly that, fleet-wide, and caught this. Where there
        # is no gate, the literal `false` is the truthful value: no gate skipped
        # this application, because there is no gate.
        jstart = 0
        for j in range(start, -1, -1):
            if re.match(r"^  [A-Za-z_][A-Za-z0-9_-]*:\s*$", lines[j]):
                jstart = j; break
        jend = len(lines)
        for j in range(start + 1, len(lines)):
            if re.match(r"^  [A-Za-z_][A-Za-z0-9_-]*:\s*$", lines[j]):
                jend = j; break
        gate = ("${{ steps.gate.outputs.skip }}"
                if any(l == "        id: gate" for l in lines[jstart:jend]) else "false")

        app = re.sub(r"^ship-|\.yml$", "", name)
        for j in range(start, -1, -1):
            mo = re.match(r"^\s*WORK_DIR:\s*(\S+)\s*$", lines[j])
            if mo:
                app = mo.group(1); break

        guard = ["", BEG] + DOC + [
            "      - name: A run that published nothing must not be green",
            "        if: ${{ !cancelled() }}",
            "        run: sh 1_cicd/dist/scripts/cloud-android-publish-guard.sh"
            ' "%s" "${{ steps.publish.outcome }}" "%s"'
            ' "${{ %s }}" "${{ github.event_name }}"' % (app, gate, cond),
            END,
            "",
        ]
        lines[end:end] = guard

    new = "\n".join(lines)
    if new != text:
        open(wf, "w").write(new)
        print("  guarded %s" % name)

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYGUARD

# ── 16 KB page-size guard ──────────────────────────────────────────
# Before every ship workflow's APK upload: the built APK's arm64-v8a/x86_64
# native libs must be 16 KB LOAD-aligned and stored at 16 KB zip offsets
# (cloud-android-page-size-guard.py, policy 1_cicd/src/data/page-size-guard.json),
# preceded by the guard's mutation test. The phone found cloud-drive's 4 KB
# libaums libs before CI did. generated-up-to-date runs the `check` mode.
log_step "inject the 16 KB page-size guard"
PYTHONDONTWRITEBYTECODE=1 python3 "$CICD_SRC/scripts/cloud-android-page-size-inject.py" inject "$CLOUD_ANDROID_ROOT" || exit 1

# ── gradle cache hygiene ──────────────────────────────────────────
# After every setup-gradle step: a gradle shim that retries a failed dependency
# resolution once with --refresh-dependencies and, if it fails again, deletes the
# resolution metadata so setup-gradle's post step cannot save a failed lookup
# into the CI cache (Maven Central 429 poisoned calc, then store x86_64). Every
# workflow gets it on generation; generated-up-to-date runs the `check` mode.
log_step "inject gradle cache hygiene"
PYTHONDONTWRITEBYTECODE=1 python3 "$CICD_SRC/scripts/cloud-android-gradle-cache-hygiene.py" inject "$CLOUD_ANDROID_ROOT" || exit 1

# ── the test-coverage inventory, as data ──────────────────────────
#
# DERIVED from the whole tree by 1_cicd/src/scripts/cloud-android-test-coverage-gen.py,
# which owns the WHY and the refusals. It lives in a file of its own, and
# ./build.sh test-coverage runs it alone, because its inputs are every app's
# test directory rather than anything under 1_cicd: the commit that makes this
# artefact stale is a commit that adds a tester, and its author must be able to
# refresh it without running this engine over the entire dist tree.
log_step "test-coverage inventory"
python3 "$CICD_SRC/scripts/cloud-android-test-coverage-gen.py" "$CLOUD_ANDROID_ROOT" || exit 1

# ── cicd: copy YAMLs → dist/cicd then into .github/workflows ───────
log_step "dist/cicd → .github/workflows"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/workflows"
for f in "$CICD_SRC/cicd/"*.yml; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$CICD_DIST/cicd/$base"
    cp "$f" "$CLOUD_ANDROID_ROOT/.github/workflows/$base"
done

# ── name an orphaned workflow; do not delete it ────────────────────
#
# The prune above deliberately stops at dist/, because "a workflow can
# legitimately live in .github/workflows without a source in this tier and
# deleting a live one is not a generator's call". That stays true. What was
# missing is that the same policy makes a RENAME silent: renaming
# ship-cloud-sheets.yml to ship-cloud-office.yml pruned the dist twin and left
# .github/workflows/ship-cloud-sheets.yml behind, still triggering on
# `ac_cloud-sheets/**` — a path that no longer exists. GitHub keeps running it,
# it can never go green again, and nothing in this generator's output mentioned
# it. The stale file is a permanent red for whoever pushes next, who did not
# rename anything.
#
# So: REPORT, never remove. A source-less workflow that is intentional (
# enhance-silence-guard.yml, ship-superapp-data-regen.yml) is listed once per
# run and ignored; one that is residue from a rename is listed the same way and
# is then obvious. Deleting it is still a human's `git rm`, deliberately.
for _wf in "$CLOUD_ANDROID_ROOT/.github/workflows/"*.yml; do
    [ -e "$_wf" ] || continue
    _b=$(basename "$_wf")
    [ -e "$CICD_SRC/cicd/$_b" ] || \
        echo "  NO SOURCE: .github/workflows/$_b has no 1_cicd/src/cicd/$_b — intentional, or residue from a rename that needs 'git rm'"
done

# scripts folder for GHA (symlink inside .github/workflows)
[ -e "$CLOUD_ANDROID_ROOT/.github/workflows/scripts" ] || \
    ln -sf ../../1_cicd/dist/scripts "$CLOUD_ANDROID_ROOT/.github/workflows/scripts"

# ── actions: composite actions ─────────────────────────────────────
log_step "dist/actions → .github/actions"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/actions"
if [ -d "$CICD_SRC/actions" ]; then
    cp -r "$CICD_SRC/actions/"* "$CICD_DIST/actions/" 2>/dev/null || true
    cp -r "$CICD_SRC/actions/"* "$CLOUD_ANDROID_ROOT/.github/actions/" 2>/dev/null || true
fi

# ── hooks: copied + chmodded ───────────────────────────────────────
log_step "dist/hooks"
for f in "$GIT_SRC/hooks/"*; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$GIT_DIST/hooks/$base"
    chmod +x "$GIT_DIST/hooks/$base"
done

# ── root dotfiles: gitmodules / gitignore / gitattributes / LICENSE ─
# git reads these three out of the WORKING TREE, so each is written to the git
# tier's dist and then copied to the repo root. gitconfig below is the one
# exception — it is included from .git/config instead, and a .gitconfig at a
# repo root would mean nothing to git at all.
log_step "_dotfiles/git/dist & repo-root dotfiles"
for _f in gitmodules gitignore gitattributes; do
    [ -f "$GIT_SRC/$_f" ] || continue
    cp "$GIT_SRC/$_f" "$GIT_DIST/.$_f"
    cp "$GIT_SRC/$_f" "$CLOUD_ANDROID_ROOT/.$_f"
done
# gitmodules also keeps its legacy dist/modules/ copy: the ship workflows read
# it from there.
[ -f "$GIT_SRC/gitmodules" ] && cp "$GIT_SRC/gitmodules" "$GIT_DIST/modules/gitmodules"
# LICENSE is copied VERBATIM — GitHub's licence detector and SPDX scanners
# match on the text, and a generated-file banner breaks them.
if [ -f "$GIT_SRC/LICENSE" ]; then
    cp "$GIT_SRC/LICENSE" "$GIT_DIST/LICENSE"
    cp "$GIT_SRC/LICENSE" "$CLOUD_ANDROID_ROOT/LICENSE"
fi
unset _f
if [ -f "$GIT_SRC/gitconfig" ]; then
    cp "$GIT_SRC/gitconfig" "$GIT_DIST/gitconfig"
    # Deploy via .git/config [include] + reconcile shadow keys.
    _gc_section=""
    while IFS= read -r line; do
        case "$line" in
            \[*\])
                _gc_section=$(printf '%s' "$line" | sed 's/^\[\([^]]*\)\]$/\1/' | tr '[:upper:]' '[:lower:]')
                ;;
            *=*)
                [ -z "$_gc_section" ] && continue
                _gc_key=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*\([a-zA-Z][a-zA-Z0-9]*\)[[:space:]]*=.*/\1/p' | tr '[:upper:]' '[:lower:]')
                [ -n "$_gc_key" ] && git -C "$CLOUD_ANDROID_ROOT" config --local --unset "${_gc_section}.${_gc_key}" 2>/dev/null || true
                ;;
        esac
    done < "$GIT_DIST/gitconfig"
    unset _gc_section _gc_key
    git -C "$CLOUD_ANDROID_ROOT" config --local include.path ../_dotfiles/git/dist/gitconfig 2>/dev/null || true
fi

log_info "Workflow build complete. Tier dists under _dotfiles/git/, _dotfiles/apps/, 1_cicd/"

# ── dotfiles ────────────────────────────────────────────────────────────────
# src/apps/<tool>/ → dist/dotfiles/<tool>/ → <repo>/<target>/, plus
# root_targets for single files at the repo root (.mcp.json). Same module every
# repo under cloud carries.
if [ -d "$APPS_SRC" ]; then
    sh "$LIB_SRC/deploy-dotfiles.sh" "$APPS_SRC" "$APPS_DIST/dotfiles" "$CLOUD_ANDROID_ROOT"
fi
