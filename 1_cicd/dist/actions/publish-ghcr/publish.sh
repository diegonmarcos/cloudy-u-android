#!/usr/bin/env bash
# publish-ghcr/publish.sh — the single publish implementation (see action.yml).
#
# WHY ONE MODULE. A GHCR package's visibility is fixed when it is CREATED. Created
# by a PAT (the old PAT-or-GITHUB_TOKEN fallback pattern) it
# belongs to the account and is born PRIVATE; created by the workflow's own
# GITHUB_TOKEN with org.opencontainers.image.source naming the repository it is
# linked to that repository and inherits its visibility. Every push site that
# hand-rolled the login re-decided that, and the Cloud Notes images were born
# private and 403'd the fleet Ship. So the login, the labels and the check live
# here and nowhere else.
set -euo pipefail

: "${P_KIND:?}" "${P_TOKEN:?}" "${P_OWNER:?}" "${P_REPO:?}"
OWNER="$(printf '%s' "$P_OWNER" | tr '[:upper:]' '[:lower:]')"
SRC="https://github.com/${P_REPO}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tags="${P_TAGS:-latest ${P_SHA:-}}"

repo_is_private() {
    GH_TOKEN="$P_TOKEN" gh api "repos/${P_REPO}" --jq .private 2>/dev/null || echo unknown
}

# The result must be what the repository's visibility promises: a public
# repository's package is anonymously pullable. Authenticated reads cannot tell.
verify_visibility() {
    local priv; priv="$(repo_is_private)"
    if [ "$priv" = "false" ]; then
        local tag; tag="$(printf '%s' "$tags" | awk '{print $1}')"
        local tok code
        tok="$(curl -fsS "https://ghcr.io/token?scope=repository:${OWNER}/${P_NAME}:pull" 2>/dev/null | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' || true)"
        code=000
        [ -z "$tok" ] || code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" \
            -H 'Accept: application/vnd.oci.image.index.v1+json' -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
            -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
            "https://ghcr.io/v2/${OWNER}/${P_NAME}/manifests/${tag}" || true)"
        if [ "$code" != "200" ]; then
            echo "::error::ghcr.io/${OWNER}/${P_NAME}:${tag} is NOT anonymously pullable (HTTP ${code}) but ${P_REPO} is public." >&2
            echo "::error::The package was created with the wrong visibility (a PAT push, or no source label). Delete it once (gh api -X DELETE user/packages/container/${P_NAME}); the next run recreates it linked to ${P_REPO}." >&2
            exit 1
        fi
        echo "ghcr.io/${OWNER}/${P_NAME}:${tag} is public, like ${P_REPO}"
    fi
}

case "$P_KIND" in
image)
    : "${P_NAME:?name required}"
    ctx="${P_CONTEXT:-.}"; df="${P_DOCKERFILE:-$ctx/Dockerfile}"
    img="ghcr.io/${OWNER}/${P_NAME}"
    printf '%s' "$P_TOKEN" | docker login ghcr.io -u "${P_ACTOR:-github-actions}" --password-stdin
    args=(buildx build --platform "$P_PLATFORMS" -f "$df" --push)
    for t in $tags; do args+=(-t "$img:$t"); done
    for kv in "org.opencontainers.image.source=$SRC" "org.opencontainers.image.revision=${P_SHA:-}" \
              "org.opencontainers.image.description=${P_DESCRIPTION:-$P_NAME}" \
              "org.opencontainers.image.licenses=${P_LICENSES:-}"; do
        args+=(--label "$kv" --annotation "index:$kv" --annotation "manifest:$kv")
    done
    docker "${args[@]}" "$ctx"
    verify_visibility
    ;;
oras)
    : "${P_NAME:?name required}" "${P_FILE:?file required}"
    ref="ghcr.io/${OWNER}/${P_NAME}"
    printf '%s' "$P_TOKEN" | oras login ghcr.io -u "${P_ACTOR:-github-actions}" --password-stdin
    for t in $tags; do
        ( cd "$(dirname "$P_FILE")" && oras push "$ref:$t" "$(basename "$P_FILE"):$P_MEDIA_TYPE" \
            --artifact-type "$P_MEDIA_TYPE" \
            --annotation "org.opencontainers.image.source=$SRC" \
            --annotation "org.opencontainers.image.revision=${P_SHA:-}" \
            --annotation "org.opencontainers.image.description=${P_DESCRIPTION:-$P_NAME}" \
            --annotation "org.opencontainers.image.licenses=${P_LICENSES:-}" \
            --annotation "org.opencontainers.image.title=$(basename "$P_FILE")" )
    done
    verify_visibility
    ;;
login)
    # Only the registry login, for a step that pushes through the app's own script (an APK's build.sh oras-push stamps
    # the code-identity annotation the in-app updater compares). The token is the workflow's GITHUB_TOKEN, as everywhere here.
    printf '%s' "$P_TOKEN" | oras login ghcr.io -u "${P_ACTOR:-github-actions}" --password-stdin
    ;;
release)
    : "${P_TAG:?tag required}" "${P_FILE:?file required}"
    export GH_TOKEN="$P_TOKEN"
    gh release view "$P_TAG" >/dev/null 2>&1 || gh release create "$P_TAG" --latest=false --title "$P_TAG" --target "${P_SHA:-main}" --notes "$P_TAG"
    # shellcheck disable=SC2086
    gh release upload "$P_TAG" $P_FILE --clobber
    ;;
*) echo "::error::publish-ghcr: unknown kind '$P_KIND'" >&2; exit 2 ;;
esac
