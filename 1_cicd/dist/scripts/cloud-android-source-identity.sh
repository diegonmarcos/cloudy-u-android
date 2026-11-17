# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-source-identity.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-source-identity                                    ║
# ║                                                                  ║
# ║ Deterministic identity of ONE app's build inputs.                ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Android builds are not byte-reproducible: the same source rebuilt gives a
# different APK, a different sha256 sidecar and a different OCI digest. The
# Constellation store compares exactly those, so any rebuild — including the
# ~10 cascade rebuilds a shared-lib touch triggers — showed up on every phone
# in the fleet as an "update" with nothing in it.
#
# The fix is to stop comparing OUTPUT bytes and start comparing INPUT identity:
# hash the git object ids of everything that can change the APK, and publish
# only when that hash moves. git already stores a content hash per directory
# (the tree sha), so this costs one `rev-parse` per path and is exact — no
# timestamps, no run ids, no file walking.
#
# WHAT IS IN THE IDENTITY
#   Exactly the `on: push: paths` list of the app's own ship workflow, plus the
#   app directory and that workflow file, whether or not the list names them.
#
#   Reading the trigger list rather than re-deriving one is the whole safety
#   property: the gate can only ever skip a build that something started, so
#   the two lists must not be able to disagree. If they were computed
#   separately, any path watched but not hashed would let a REAL change trigger
#   a build the gate then skipped — a silently suppressed update, strictly
#   worse than the phantom updates this replaces. The workflow generator
#   manages that list from each build.json module map, so both stay true as the
#   module maps evolve.
#
#   Concretely that covers: the app's own tree (its source, its build.json — so
#   a signing config, version, ABI filter or release-name change republishes —
#   and its VENDORED build.sh, the file CI actually executes), every shared
#   library directory it compiles by reference out of ab_cloud-libs-shared/,
#   and the workflow itself with its SDK/JDK pins and matrix.
#
# WHAT IS DELIBERATELY NOT IN IT
#   run ids, timestamps, the commit sha itself, and the 1_cicd engine SOURCES.
#   The engine source is excluded because it is not what runs: the vendored
#   <app>/build.sh is, and that is already inside the app tree at (1). The
#   generator's parity check is what guarantees the two agree, so a
#   parity-fixing sync lands in the app tree and correctly republishes.
#
# A NARROWER SCOPE THAN THE WHOLE APP: --paths-from FILE
#   One ship workflow publishes MANY assets: ship-cloud-libs builds every
#   module under ab_cloud-libs-shared/libs/ as its own APK. Hashing the app's
#   whole trigger list gives all 24 of them the SAME identity, so a change to
#   one module republishes every one of them - which is what the fleet saw on
#   2026-09-09, when a fix to libs/updater put 24 unchanged library APKs on the
#   release and six update prompts on the owner's phone.
#
#   With --paths-from, the caller supplies the exact input set for ONE asset
#   and the identity is scoped to it. The caller owes the same safety property
#   the workflow list gives by construction: the UNION of the path sets it
#   passes for all of its assets must cover the workflow's whole trigger list,
#   or a real change could trigger a build that every asset's gate then skips.
#   ab_cloud-libs-shared/lib-apks/build.sh derives its sets from this script's
#   own `paths` output for exactly that reason, and
#   1_cicd/src/scripts/test/publish-gate-blast-radius.test.sh asserts the
#   union property rather than trusting it.
#
# USAGE
#   cloud-android-source-identity.sh paths   <app-dir>   # inputs, one per line
#   cloud-android-source-identity.sh explain <app-dir>   # per-path object ids
#   cloud-android-source-identity.sh compute <app-dir>   # the 64-hex identity
#     ... [--paths-from FILE]                            # scope to one asset
set -eu

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

CMD="${1:-}"
APP="${2:-}"
APP="${APP%/}"
if [ $# -ge 2 ]; then shift 2; else shift $#; fi

PATHS_FROM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --paths-from) PATHS_FROM="${2:-}"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[ -n "$CMD" ] && [ -n "$APP" ] || {
    echo "usage: $(basename "$0") paths|explain|compute <app-dir> [--paths-from FILE]" >&2; exit 2; }

# Checked HERE, not where the file is read. `compute` runs `_explain |
# sha256sum`, which puts _paths in a subshell, so an `exit 3` down there kills
# the subshell and leaves sha256sum to hash the empty set and return 0 - a
# confident 64-hex identity for no inputs at all, which every gate would then
# compare equal on the next run and skip forever. This script already carries
# that scar in _external; the guard belongs before the first pipe.
[ -z "$PATHS_FROM" ] || [ -f "$PATHS_FROM" ] || {
    echo "$APP: --paths-from names no such file: $PATHS_FROM" >&2; exit 3; }
[ -d "$ROOT/$APP" ] || { echo "no such app dir: $APP (root=$ROOT)" >&2; exit 2; }

# ── a --paths-from scope must name something, entry by entry (#635) ──
# An entry git cannot resolve at HEAD (a typo, a moved directory, a `foo/**`
# glob this mode does not expand) hashes below as the literal "missing" — a
# constant. So does a scope that names nothing at all. Either way the identity
# stops seeing the asset's real inputs while still printing a confident 64-hex
# answer with status 0, and the gate then skips a republish that was needed:
# the silently suppressed update this file exists to prevent. The workflow
# mode keeps "missing" (a watched path that was deleted must still move the
# identity once); an explicit scope is a declaration, and a declaration that
# names nothing is wrong. Checked here, in the main shell, for the same reason
# as the two guards above. Same resolution as _explain, so the two cannot
# disagree about what "names something" means.
if [ -n "$PATHS_FROM" ]; then
    _named=0
    while IFS= read -r _p || [ -n "$_p" ]; do
        case "$_p" in *[![:space:]]*) ;; *) continue ;; esac   # blank: _paths skips it too
        git -C "$ROOT" rev-parse -q --verify "HEAD:$_p" >/dev/null || {
            echo "$APP: --paths-from entry '$_p' names nothing at HEAD ($PATHS_FROM) — it would hash as a constant and silently narrow the gate" >&2; exit 3; }
        _named=$((_named + 1))
    done < "$PATHS_FROM"
    [ "$_named" -gt 0 ] || {
        echo "$APP: --paths-from $PATHS_FROM names no inputs at all — an identity of nothing never moves, so the gate would skip forever" >&2; exit 3; }
fi

# Checked HERE, in the main shell, for exactly the reason the line above is:
# the tester-directory exclusion (_tests_dir, below) is read from build.json
# with jq, and `compute` runs `_explain | sha256sum`, so every `exit` inside
# that pipeline kills only a subshell and leaves sha256sum to hash a short
# list into a confident 64-hex identity. With no jq the exclusion would vanish
# silently, the tester directory would re-enter the identity, and every tester
# edit would republish a byte-identical APK to every phone in the fleet — run
# 34850225588, which this exclusion exists to prevent. A dependency that
# degrades quietly costs more than one that fails loudly.
if [ -f "$ROOT/$APP/build.json" ] && ! command -v jq >/dev/null 2>&1; then
    echo "$APP: jq is not installed, so build.json::tests.shell.dir cannot be read — refusing to compute an identity that would silently hash the tester directory" >&2
    exit 3
fi

# ── _paths MUST NOT SIT ON THE LEFT OF A PIPE ──────────────────────────────
# It refuses with `exit 3` when it cannot see the app's ship workflow, and that
# refusal is the guard against a partial input set. On the left of a pipe the
# exit kills only the subshell: `sort` still succeeds, the caller receives the
# handful of lines already printed, AND A ZERO STATUS. So `paths` handed out a
# short list while claiming success, and `explain`/`compute` hashed it into a
# confident 64-hex identity for inputs that were never all read — which the
# publish gate then compares equal on the next run and skips. A silently
# suppressed update, which this file's own comments call strictly worse than
# the phantom updates it exists to stop; the guard was already spelled out for
# --paths-from and for _external, and this was the third door.
# ab_cloud-libs-shared/lib-apks/build.sh derives its per-asset scopes from this
# very `paths` output, so a partial answer there scopes a gate to a partial
# input set — the same failure one layer down.
# Redirecting into a file keeps _paths in THIS shell, where exit means exit.
PATHS_TMP="$(mktemp)"
RAW_TMP="$(mktemp)"
trap 'rm -f "$PATHS_TMP" "$RAW_TMP"' EXIT INT TERM

# ── the app's ship workflow ────────────────────────────────────────
# Found by its declared WORK_DIR, not by filename: two lib aggregators build a
# directory under ab_cloud-libs-shared/ whose name does not match their slug.
# PUBLISH_GATE_WORKFLOW overrides.
_workflow() {
    if [ -n "${PUBLISH_GATE_WORKFLOW:-}" ]; then printf '%s' "$PUBLISH_GATE_WORKFLOW"; return 0; fi
    for f in "$ROOT"/1_cicd/src/cicd/ship-*.yml; do
        [ -e "$f" ] || continue
        if grep -qx "  WORK_DIR: $APP" "$f"; then
            printf '%s' "1_cicd/src/cicd/$(basename "$f")"; return 0
        fi
    done
    printf '%s' "1_cicd/src/cicd/ship-${APP#*_}.yml"
}

# ── the input path set ─────────────────────────────────────────────
_paths() {
    # An explicit set replaces the derivation entirely - including the app dir,
    # which for a per-asset scope is the harness shared by every asset and is
    # supplied by the caller when it belongs. Refusing a missing file rather
    # than falling back keeps a typo from silently widening the scope back to
    # the whole app, which would look like it worked.
    if [ -n "$PATHS_FROM" ]; then
        grep -v '^[[:space:]]*$' "$PATHS_FROM" >"$RAW_TMP" || true
        grep -v '^[[:space:]]*$' "$PATHS_FROM" || true
        return 0
    fi

    # Fail loud rather than hash a short list. An identity that quietly omits
    # the shared libs would let the gate skip a real update — strictly worse
    # than the phantom updates it exists to stop.
    wf="$(_workflow)"
    [ -f "$ROOT/$wf" ] || {
        echo "$APP: no ship workflow found ($wf) — refusing to compute a partial identity" >&2
        exit 3
    }

    # The workflow's `on: push: paths` list, minus the glob tail, PLUS the
    # #836 deferred inputs the generator writes as `#   input: "…"` comment
    # lines inside the managed fence: cross-app dirs and engine libs (which ship their own APK),
    # and the workflow file itself. GitHub does not watch them; this
    # identity still hashes them, so a refresh- or dispatch-started run
    # publishes exactly when they moved. Triggers stay a SUBSET of the
    # identity, which is the property the gate's safety rests on. A dir entry
    # hashes as its git tree, a file entry as its blob — so `ac_cloud-nav/**`
    # and `ac_cloud-nav` are the same object either way.
    entries="$(awk '
        /^    paths:$/            { inblock = 1; next }
        inblock && /^      - "/   { gsub(/^      - "|"$/, ""); print; next }
        inblock && /^      #   input: "/ { sub(/^      #   input: "/, ""); sub(/"[[:space:]]*$/, ""); print; next }
        inblock && /^      /      { next }
        inblock && NF             { exit }
    ' "$ROOT/$wf")"

    # A `!` entry in the trigger list is an EXCLUSION and is still honoured
    # here exactly as GitHub honours it, so an author who writes one gets the
    # same answer from both.
    #
    # ── AND THE TESTER DIRECTORY, WHICH IS NOT A `!` ENTRY ANY MORE (#370) ──
    # It used to be, and that single marker was answering two questions at
    # once: "may this start a run?" and "may this publish an APK?". Run
    # 34850225588 republished the SuperApp for a commit that touched only
    # aa_cloud-superapp/test/ — the app tree sha, test/ included, was the
    # identity — and the `!` fixed that by removing the directory from BOTH.
    # Removing it from the trigger was never the goal and was the more
    # expensive bug: editing a tester then started nothing, so the one pipeline
    # that executes that tester was the one its own edit could not reach.
    #
    # So the trigger watches it and the identity does not, and the two halves
    # still come from ONE declaration — build.json::tests.shell.dir — read here
    # and in cloud-android-ship-repo-workflow-engine.sh. Sharing the
    # declaration rather than the marker is what keeps them from drifting now
    # that their answers legitimately differ.
    _excl_marked="$(printf '%s\n' "$entries" | sed -n 's/^!//p')"
    excluded="$(printf '%s\n%s\n' "$_excl_marked" "$(_tests_dir)" \
                | grep -v '^[[:space:]]*$' | _strip_glob)"
    list="$(printf '%s\n%s\n' "$APP" "$wf"; printf '%s\n' "$entries" | grep -v '^!' | _strip_glob)"
    printf '%s\n' "$list" >"$RAW_TMP"   # #1783: before the exclusions, to know whether the fleet manifest is an input
    while IFS= read -r x; do
        [ -n "$x" ] || continue
        list="$(printf '%s\n' "$list" | while IFS= read -r p; do
                    [ -n "$p" ] && _without "$p" "$x"
                done)"
    done <<EOF
$excluded
EOF
    printf '%s\n' "$list"

    return 0
}

# ── the app's DECLARED shell-tester directory, or nothing ──────────
# Same field, same existence test, as the workflow generator's own reading of
# it. Only a DECLARED tests.shell.dir counts: an undeclared test/ (ac_cloud-chat
# carries upstream's) is not this repository's claim to make, and a declared
# directory that is not on disk is not one either. jq's presence is guaranteed
# by the main-shell guard above, so nothing in here can fail.
_tests_dir() {
    _bj="$ROOT/$APP/build.json"
    [ -f "$_bj" ] || return 0
    _td="$(jq -r '.tests.shell.dir // empty' "$_bj" 2>/dev/null)"
    _td="${_td#/}"; _td="${_td%/}"
    [ -n "$_td" ] || return 0
    [ -d "$ROOT/$APP/$_td" ] || return 0
    printf '%s\n' "$APP/$_td"
    return 0
}

_strip_glob() {
    while IFS= read -r e; do
        e="${e%/\*\*}"; e="${e%/\*}"; e="${e%\*\*}"; e="${e%/}"
        [ -n "$e" ] && printf '%s\n' "$e"
    done
    return 0
}

# _without <path> <excluded>: <path>, minus the <excluded> subtree. A path that
# CONTAINS the exclusion cannot be hashed as one tree any more, so it is replaced
# by its children as git records them at HEAD, recursing only down the branch
# that leads to the exclusion. Every other child still hashes as its own tree
# sha, and a child added or removed still moves the identity.
_without() {
    case "$1" in "$2"|"$2"/*) return 0 ;; esac
    case "$2" in
        "$1"/*)
            git -C "$ROOT" ls-tree --name-only HEAD -- "$1/" | while IFS= read -r child; do
                _without "$child" "$2"
            done ;;
        *)  printf '%s\n' "$1" ;;
    esac
    return 0
}

# ── external inputs: artifacts this APK bakes in from ANOTHER repo ──
# Declared as data in build.json::build.external_inputs[] — {repo, tag, asset}.
#
# WHY THIS EXISTS (2026-08-31): cloud-watchdog ships my-watchdog, the panel and
# the app shell, all downloaded from the cloud-u-linux rolling release at build
# time. None of it is in this repo, so the identity above could not see it: the
# panel was rebuilt with fixes, this app's source had not moved, and the gate
# skipped publishing — forever. The phone kept an APK days behind while every
# rebuild agreed it was current.
_external() {
    bj="$ROOT/$APP/build.json"
    [ -f "$bj" ] || return 0
    command -v jq >/dev/null 2>&1 || return 0
    # A `while` fed by a PIPE runs in a subshell, so the `exit 3` below would
    # kill only that subshell and leave the caller hashing a short list — the
    # silent partial identity this guard exists to prevent. A heredoc keeps the
    # loop in this shell, where exit means exit.
    _list="$(jq -r '(.build.external_inputs // [])[]
           | select(type=="object" and .repo != null and .tag != null and .asset != null)
           | "\(.repo)\t\(.tag)\t\(.asset)"' "$bj" 2>/dev/null)"
    [ -n "$_list" ] || return 0
    while IFS="$(printf '\t')" read -r repo tag asset; do
        [ -n "$repo" ] && [ -n "$tag" ] && [ -n "$asset" ] || continue
        id="$(gh release view "$tag" --repo "$repo" \
                --json assets \
                --jq "[.assets[]|select(.name==\"$asset\")|\"\(.size):\(.updatedAt)\"][0] // empty" \
              2>/dev/null)"
        [ -n "$id" ] || {
            echo "$APP: cannot read $asset from $repo@$tag — refusing to compute a partial identity" >&2
            exit 3
        }
        printf '%s  external:%s@%s/%s\n' "$id" "$repo" "$tag" "$asset"
    done <<EOF
$_list
EOF
}

# ── per-path git object ids ────────────────────────────────────────
# `git rev-parse HEAD:<path>` yields the TREE sha for a directory and the BLOB
# sha for a file — one content hash for an arbitrarily deep subtree, already
# computed and stored by git. A path git does not know (untracked, or removed)
# hashes as the literal "missing" so its disappearance still moves the
# identity instead of silently hashing to the same value.
# ── mirrors identify by their PIN, not by their directory ──────────
# A mirror does not build anything: it downloads an upstream artifact,
# verifies it against `upstream.sha256` and republishes those exact bytes.
# Its output is therefore a pure function of the pin, and hashing its
# directory asks the wrong question — every README fix, every doc edit, every
# repo-wide touch moves the tree sha and republishes an APK that is
# byte-identical to the one already there.
#
# That is not theoretical. On 2026-09-01 a repo-wide restore commit rewrote
# ac_cloud-sheets/, the tree sha moved, and 250 MB of unchanged Collabora
# Office went through GHCR, through a GitHub release, and down onto every
# phone as an "update" — for an app nobody had touched.
#
# Declared per app as `build.json::source_identity.mode = "pin"`, so the rule
# lives with the app it describes and any future mirror gets it by saying so.
_pin_identity() {
    bj="$ROOT/$APP/build.json"
    [ -f "$bj" ] || return 1
    command -v jq >/dev/null 2>&1 || return 1
    [ "$(jq -r '.source_identity.mode // empty' "$bj" 2>/dev/null)" = "pin" ] || return 1
    # The whole `upstream` object, compactly and with keys sorted, so the
    # identity moves when the pin moves and stays put when it does not.
    # Sorted because a reformat of build.json must not read as a new release.
    jq -Sc '.upstream // {}' "$bj"
}

# ── Gradle test source sets are not in the APK ─────────────────────
# <module>/src/test and <module>/src/androidTest compile only into test APKs,
# never into the release one, yet the tree sha of every hashed directory that
# holds them moved on a test edit. On 2026-09-30 ea868f634 touched nothing but
# libs/bottomnav/src/test/ and ship rebuilt and republished Cloud Me, SuperApp,
# Mail, Wallet, C3 and Drive — six "updates" with no shipped byte in them.
#
# The trigger still watches these directories: the run still starts, and
# whatever runs before the gate (the app's testers, the JVM unit phase) still
# runs. Only the publish decision stops counting them.
#
# Derived from the module's own declaration: a set is excluded only when its
# module HAS a build.gradle(.kts) and no non-comment line of it names
# src/<set> (src/testApp is a different set). ac_cloud-vault/ui routes
# src/test/res into its MAIN res, so that set ships and must stay hashed —
# any mention keeps it, which errs toward publishing. Any failure here prints
# nothing, i.e. excludes nothing.
# ponytail: only the module's own build file is read; a src/<set> routed in
# from ANOTHER module's build file would be missed — read every build file
# under the hashed path if that pattern ever appears.
_test_sets() {
    git -C "$ROOT" ls-tree -r -d --name-only HEAD -- "$1" 2>/dev/null \
        | grep -E '(^|/)src/(test|androidTest)$' | while IFS= read -r d; do
        m="${d%/src/*}"; ss="${d##*/}"
        files="$(git -C "$ROOT" ls-tree --name-only HEAD -- "$m/build.gradle" "$m/build.gradle.kts" 2>/dev/null)"
        [ -n "$files" ] || continue
        printf '%s\n' "$files" | while IFS= read -r f; do git -C "$ROOT" show "HEAD:$f"; done \
            | grep -v '^[[:space:]]*\(//\|\*\|/\*\)' | grep -qE "src/$ss([^A-Za-z0-9_]|\$)" && continue
        printf '%s\n' "$d"
    done
    return 0
}

# Each path, minus its test source sets (same _without as the tester dir).
_drop_test_sets() {
    while IFS= read -r p; do
        list="$p"
        while IFS= read -r x; do
            [ -n "$x" ] || continue
            list="$(printf '%s\n' "$list" | while IFS= read -r q; do
                        [ -n "$q" ] && _without "$q" "$x"
                    done)"
        done <<EOF
$(_test_sets "$p")
EOF
        printf '%s\n' "$list"
    done
}

# ── #1783 P3: documentation inside a shared lib is not in the APK ──
# Declared in 1_cicd/src/data/identity-exclusions.json (doc_globs under doc_glob_scope,
# never under assets/ or res/). Same _without as the test sets: the run still starts,
# the publish decision stops counting a README edit.
_doc_files() {
    _cfg="$ROOT/1_cicd/src/data/identity-exclusions.json"
    [ -f "$_cfg" ] || return 0
    _scope="$(jq -r '.doc_glob_scope // empty' "$_cfg" 2>/dev/null || true)"
    [ -n "$_scope" ] || return 0
    case "$1" in "$_scope"*) ;; *) return 0 ;; esac
    _keep="$(jq -r '(.doc_glob_keep_under // [])[]' "$_cfg" 2>/dev/null || true)"
    jq -r '(.doc_globs // [])[]' "$_cfg" 2>/dev/null | while IFS= read -r g; do
        ext="${g#\*}"
        git -C "$ROOT" ls-tree -r --name-only HEAD -- "$1" 2>/dev/null | while IFS= read -r f; do
            case "$f" in *"$ext") ;; *) continue ;; esac
            skip=0
            while IFS= read -r k; do
                [ -n "$k" ] || continue
                case "/$f" in *"$k"*) skip=1 ;; esac
            done <<EOK
$_keep
EOK
            [ "$skip" -eq 1 ] || printf '%s\n' "$f"
        done
    done
    return 0
}

_drop_docs() {
    while IFS= read -r p; do
        list="$p"
        while IFS= read -r x; do
            [ -n "$x" ] || continue
            list="$(printf '%s\n' "$list" | while IFS= read -r q; do
                        [ -n "$q" ] && _without "$q" "$x"
                    done)"
        done <<EOF
$(_doc_files "$p")
EOF
        printf '%s\n' "$list"
    done
    return 0
}

# ── #1783 P1/P3: the fleet manifest is hashed as canonical JSON, per app slice ──
# Its git blob moves on every declaration of every app (and on a doc string), which
# made a one-line edit for one app a new version of 42. The manifest is taken out of
# every hashed tree (_drop_manifest) and replaced by ONE line: the whole doc-stripped
# manifest for the planners (and for a --paths-from scope), the app's slice for the rest.
_manifest() {
    [ -f "$ROOT/1_cicd/src/data/identity-exclusions.json" ] || return 0   # a fixture without the data file: no manifest handling
    jq -r '(.normalized_json // [])[0] // empty' "$ROOT/1_cicd/src/data/identity-exclusions.json" 2>/dev/null || true
}

_drop_manifest() {
    _m="$(_manifest)"
    while IFS= read -r p; do
        if [ -n "$_m" ]; then _without "$p" "$_m"; else printf '%s\n' "$p"; fi
    done
    return 0
}

_fleet_identity() {
    _m="$(_manifest)"
    [ -n "$_m" ] || return 0
    _cov=0
    while IFS= read -r p; do
        case "$_m" in "$p"|"$p"/*) _cov=1 ;; esac
    done <"$RAW_TMP"
    [ "$_cov" -eq 1 ] || return 0
    _sc=""; [ -z "$PATHS_FROM" ] || _sc="--scoped"
    python3 -I "$ROOT/1_cicd/src/scripts/cloud-android-fleet-slice.py" --root "$ROOT" --rev HEAD identity "$APP" $_sc 2>/dev/null || {
        # fail towards publishing: a moving line, never a silently skipped update
        echo "$APP: fleet slice identity failed - publishing" >&2
        printf '%s  fleet-slice-failed\n' "$(date +%s%N)"
    }
}

_explain() {
    # A pin identity describes the WHOLE app, so it cannot answer a question
    # scoped to one of its assets. Spelled as an `if` rather than folded into
    # the `&&` chain below it: under `set -eu` an AND-OR list that ends false
    # is itself a failing statement, so the compact spelling exits the script
    # instead of falling through to the path hashing.
    if [ -z "$PATHS_FROM" ]; then
        _pin_identity && return 0
    fi
    _paths >"$PATHS_TMP"
    LC_ALL=C sort -u "$PATHS_TMP" | _drop_test_sets | _drop_docs | _drop_manifest | grep -v "^[[:space:]]*\$" | LC_ALL=C sort -u | while IFS= read -r p; do
        h="$(git -C "$ROOT" rev-parse "HEAD:$p" 2>/dev/null || printf 'missing')"
        printf '%s  %s\n' "$h" "$p"
    done
    # Sorted with the rest so the order of the declaration cannot change the
    # identity of an otherwise identical build.
    { _external; _fleet_identity; } | LC_ALL=C sort -u
}

case "$CMD" in
    paths)   _paths >"$PATHS_TMP"; LC_ALL=C sort -u "$PATHS_TMP" ;;
    explain) _explain ;;
    compute) _explain | sha256sum | cut -d' ' -f1 ;;
    *)       echo "unknown command: $CMD" >&2; exit 2 ;;
esac
