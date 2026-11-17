#!/bin/sh
# cloud-android-release-swap -- #1783 P4: replace GitHub release assets without a missing-asset window.
#
# `gh release upload --clobber` DELETES the old asset first and then uploads: for the whole transfer
# (minutes for a 200 MB APK) the release has no asset and the Store falls back to GHCR (HTTP 403 for a
# private package: "not published"), or reads a sha256 sidecar that names bytes not yet there.
# This sends the new bytes first, under a temporary name, and only then swaps the names with two
# metadata-only API calls (no transfer in between):
#   1. upload   <name>.swap-<pid>             (the live <name> is untouched for the whole transfer)
#   2. rename   <name>      -> <name>.old-<pid>
#   3. rename   <name>.swap-<pid> -> <name>   (step 3 failing renames the old asset back)
#   4. delete   <name>.old-<pid>
# GitHub has no atomic asset swap; the remaining gap is steps 2-3, two API round trips.
# Sidecars (<name>.sha256) are swapped the same way, the APK first and the sidecar last.
#
# USAGE  cloud-android-release-swap.sh <tag> <file>...      (GH_TOKEN / gh auth as for `gh release upload`)
# ENV    SWAP_REPO=<owner/repo> (default: the cwd repo)
set -eu
TAG="${1:?usage: cloud-android-release-swap.sh <tag> <file>...}"; shift
[ $# -gt 0 ] || { echo "release-swap: no files" >&2; exit 2; }
REPO="${SWAP_REPO:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT INT TERM
SFX="$$"

_id() { gh api "repos/$REPO/releases/tags/$TAG" --jq ".assets[] | select(.name==\"$1\") | .id" | head -n1; }
_rename() { gh api -X PATCH "repos/$REPO/releases/assets/$1" -f name="$2" >/dev/null; }

for f in "$@"; do
    name="$(basename "$f")"; tmpn="$name.swap-$SFX"; oldn="$name.old-$SFX"
    [ -f "$f" ] || { echo "release-swap: no such file $f" >&2; exit 2; }
    cp "$f" "$TMP/$tmpn"
    gh release upload "$TAG" "$TMP/$tmpn"
    new_id="$(_id "$tmpn")"
    [ -n "$new_id" ] || { echo "release-swap: uploaded $tmpn but it is not on $TAG" >&2; exit 1; }
    old_id="$(_id "$name")"
    if [ -n "$old_id" ]; then
        _rename "$old_id" "$oldn"
        _rename "$new_id" "$name" || { _rename "$old_id" "$name"; echo "release-swap: could not rename $tmpn to $name; old asset restored" >&2; exit 1; }
        gh api -X DELETE "repos/$REPO/releases/assets/$old_id" >/dev/null || echo "release-swap: leftover $oldn on $TAG (harmless)" >&2
    else
        _rename "$new_id" "$name"
    fi
    rm -f "$TMP/$tmpn"
done
