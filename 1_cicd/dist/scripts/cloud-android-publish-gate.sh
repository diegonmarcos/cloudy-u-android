# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-publish-gate.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-publish-gate — publish only when the source moved  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Runs as the first step of every ship job, right after checkout, and answers
# one question: has anything that can change this APK changed since the bytes
# currently on the release were published?
#
#   check <app-dir> [variant-id]
#       sets step output skip=true|false (plus identity/asset/tag).
#       skip=true  → the whole rest of the job is if:'d out. The run is GREEN,
#                    takes ~30s instead of ~30min, and the release asset's
#                    updated_at does not move — so no phone sees an update.
#   stamp <app-dir> [variant-id]
#       last step of a job that DID publish: uploads <asset>.source carrying
#       the identity those bytes were built from (line 1) and the full 40-hex
#       git commit they were built from (line 2).
#   verify-source <file>
#       exit 0 only when line 2 of a .source file is 40 hex AND names a commit
#       in this repository. stamp refuses to upload a sidecar that fails it.
#
# ONE JOB, MANY ASSETS: --asset NAME --paths-from FILE
#   ship-cloud-libs builds every module under ab_cloud-libs-shared/libs/ as its
#   own APK, so there is no single asset for build.json to name and the
#   resolution below yields nothing - which made this gate fail open and
#   republish all 24 on any touch to any shared module. With both options the
#   caller gates each asset on its own inputs, so a change to libs/updater
#   republishes the updater APK and the one library that depends on it rather
#   than the whole set. --asset without --paths-from is refused: it would gate
#   one asset against the whole app'"'"'s identity, which is the bug, not the fix.
#
# The identity lives as a small sidecar asset beside the APK, in the same
# place and with the same lifetime as the .sha256 sidecar. It is not derived
# from the APK, so a non-reproducible rebuild cannot perturb it, and it is
# not kept in a separate store that could drift away from the asset it
# describes.
#
# WHEN THE GATE DOES NOT APPLY (fail-open, always publish):
#   * a tag push — cutting a tag is an explicit intent to publish
#   * PUBLISH_GATE_FORCE=1
#   * the release/sidecar/sha256 is missing or unreadable — an incomplete
#     publish must be repaired, never skipped
#   * build.json declares no gh_release asset name
#
# Requires: gh (authenticated via GH_TOKEN), jq, git.
set -eu

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

# THE SIDECAR CARRIES TWO FACTS, ONE PER LINE.
#   line 1  the input identity — the digest `check` compares, unchanged, so a
#           push that moves nothing still skips. It is NOT a commit: it is a
#           hash of build inputs, and reading it as one is how a release came
#           to name no commit at all.
#   line 2  the full 40-hex git sha those bytes were built from — what the
#           owner, the Store and a cache comparison read to say "this build".
# A legacy one-line sidecar still gates correctly (line 1 is all `check`
# reads) and simply names no commit until its next publish.
_verify_source() {
    sha="$(sed -n 2p "$1" | tr -d '[:space:]')"
    case "$sha" in
        *[!0-9a-f]*|"") echo "[publish-gate] $1: line 2 is '$sha', not a git sha" >&2; return 1 ;;
    esac
    [ "${#sha}" -eq 40 ] || { echo "[publish-gate] $1: '$sha' is ${#sha} hex, a full git sha is 40" >&2; return 1; }
    git -C "$ROOT" cat-file -e "$sha^{commit}" 2>/dev/null \
        || { echo "[publish-gate] $1: $sha is not a commit in this repository" >&2; return 1; }
}
if [ "${1:-}" = "verify-source" ]; then
    [ -n "${2:-}" ] || { echo "usage: $(basename "$0") verify-source <file>" >&2; exit 2; }
    _verify_source "$2"; exit 0
fi

CMD="${1:-}"
APP="${2:-}"; APP="${APP%/}"
if [ $# -ge 2 ]; then shift 2; else shift $#; fi

VARIANT=""
ASSET_OVERRIDE=""
PATHS_FROM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --asset)      ASSET_OVERRIDE="${2:-}"; shift 2 ;;
        --paths-from) PATHS_FROM="${2:-}";     shift 2 ;;
        --*)          echo "unknown option: $1" >&2; exit 2 ;;
        *)            VARIANT="$1";            shift ;;
    esac
done

[ -n "$CMD" ] && [ -n "$APP" ] || {
    echo "usage: $(basename "$0") check|stamp <app-dir> [variant-id]" \
         "[--asset NAME --paths-from FILE]" >&2; exit 2; }

[ -z "$ASSET_OVERRIDE" ] || [ -n "$PATHS_FROM" ] || {
    echo "--asset needs --paths-from: gating one asset of many against the" \
         "whole app identity republishes all of them" >&2; exit 2; }

BJ="$ROOT/$APP/build.json"
log() { printf '[publish-gate] %s\n' "$1"; }

# Emit a step output AND echo it, so the same script is readable in a local
# shell and consumable by `if: steps.gate.outputs.skip != 'true'`.
out() {
    [ -n "${GITHUB_OUTPUT:-}" ] && printf '%s\n' "$1" >> "$GITHUB_OUTPUT"
    log "$1"
    return 0
}

# Public asset name for the active variant — the SAME resolution every
# build.sh uses (release.variants[].gh_asset, else release.gh_release
# .asset_name), so the sidecar always lands beside the asset it describes and
# each ABI variant is gated independently.
_asset() {
    n=""
    if [ -n "$VARIANT" ] && [ -f "$BJ" ]; then
        n="$(jq -r --arg v "$VARIANT" \
             '(.release.variants[]? | select(.id==$v) | .gh_asset) // empty' "$BJ")"
    fi
    [ -n "$n" ] || n="$(jq -r '.release.gh_release.asset_name // empty' "$BJ" 2>/dev/null)"
    printf '%s' "$n"
}
# An explicit asset wins over build.json: a job publishing N assets has no
# single name to declare there, and the caller is the only thing that knows
# which of the N this call is about.
_asset_resolved() {
    if [ -n "$ASSET_OVERRIDE" ]; then printf '%s' "$ASSET_OVERRIDE"; else _asset; fi
}
_tag() {
    t="$(jq -r '.release.gh_release.rolling_tag // empty' "$BJ" 2>/dev/null)"
    printf '%s' "${t:-latest}"
}

if [ -n "$PATHS_FROM" ]; then
    IDENTITY="$(sh "$SELF_DIR/cloud-android-source-identity.sh" compute "$APP" \
                   --paths-from "$PATHS_FROM")"
else
    IDENTITY="$(sh "$SELF_DIR/cloud-android-source-identity.sh" compute "$APP")"
fi
ASSET="$(_asset_resolved)"
TAG="$(_tag)"

case "$CMD" in

check)
    out "identity=$IDENTITY"
    out "asset=$ASSET"
    out "tag=$TAG"

    publish() { out "skip=false"; log "$1"; exit 0; }

    case "${GITHUB_REF:-}" in
        refs/tags/*) publish "tag push — gate bypassed, publishing" ;;
    esac
    if [ "${PUBLISH_GATE_FORCE:-0}" = "1" ]; then
        publish "PUBLISH_GATE_FORCE=1 — gate bypassed, publishing"
    fi
    if [ -z "$ASSET" ]; then
        publish "$APP declares no gh_release asset name — gate not applicable"
    fi

    names="$(gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null || true)"
    has() { printf '%s\n' "$names" | grep -qxF "$1"; }
    if ! has "$ASSET"; then       publish "$ASSET absent from release $TAG — publishing"; fi
    if ! has "$ASSET.sha256"; then publish "$ASSET.sha256 sidecar missing on $TAG — publishing"; fi
    if ! has "$ASSET.source"; then publish "$ASSET.source identity sidecar missing on $TAG — publishing"; fi

    tmp="$(mktemp -d)"
    if ! gh release download "$TAG" --pattern "$ASSET.source" --dir "$tmp" --clobber >/dev/null 2>&1; then
        rm -rf "$tmp"; publish "could not read $ASSET.source from $TAG — publishing"
    fi
    prev="$(head -n 1 "$tmp/$ASSET.source" | tr -d '[:space:]')"
    rm -rf "$tmp"

    if [ "$prev" = "$IDENTITY" ]; then
        out "skip=true"
        log "unchanged since $(git -C "$ROOT" rev-parse --short HEAD) — publish skipped"
        log "  $ASSET on release $TAG already carries source identity $IDENTITY"
        log "  inputs hashed:"
        if [ -n "$PATHS_FROM" ]; then
            sh "$SELF_DIR/cloud-android-source-identity.sh" explain "$APP" \
               --paths-from "$PATHS_FROM"
        else
            sh "$SELF_DIR/cloud-android-source-identity.sh" explain "$APP"
        fi | awk '{ print "    " $0 }'
        exit 0
    fi
    out "skip=false"
    log "source identity moved ${prev} → ${IDENTITY} — publishing"
    ;;

stamp)
    if [ -z "$ASSET" ]; then log "no gh_release asset name for $APP — nothing to stamp"; exit 0; fi
    # A manual dispatch can build WITHOUT publishing (create_release=false).
    # Stamping there would claim the release carries bytes built from this
    # identity when it still holds the old ones, and every later push would
    # then skip against a lie. Only the push path — which always publishes —
    # stamps by default.
    if [ "${GITHUB_EVENT_NAME:-}" = "workflow_dispatch" ] && [ "${PUBLISH_GATE_STAMP:-0}" != "1" ]; then
        log "workflow_dispatch: not stamping (set PUBLISH_GATE_STAMP=1 to force)"; exit 0
    fi
    tmp="$(mktemp -d)"
    # The SAME value the APK baked (#826): every app's build.gradle derives
    # versionName "(sha-…)" + BuildConfig.GIT_SHORT_SHA from GITHUB_SHA first,
    # `git rev-parse HEAD` only when that is unset — and build.sh tags/annotates
    # the OCI layer the same way. Stamping HEAD alone let the sidecar and the
    # installed versionName name different commits (workflow_dispatch), so the
    # Store's Installed/Available built-from disagreed for one build.
    COMMIT="${GITHUB_SHA:-$(git -C "$ROOT" rev-parse HEAD)}"
    printf '%s\n%s\n' "$IDENTITY" "$COMMIT" > "$tmp/$ASSET.source"
    if ! _verify_source "$tmp/$ASSET.source"; then
        rm -rf "$tmp"; log "refusing to stamp $ASSET.source: it would name no real commit"; exit 1
    fi
    sh "$ROOT/1_cicd/dist/scripts/cloud-android-release-swap.sh" "$TAG" "$tmp/$ASSET.source"   # #1783 P4: swap, never delete-then-upload
    rm -rf "$tmp"
    log "stamped $ASSET.source on $TAG = $IDENTITY @ $COMMIT"
    ;;

*)  echo "unknown command: $CMD" >&2; exit 2 ;;
esac
