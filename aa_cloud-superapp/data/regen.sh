#!/usr/bin/env bash
# Regenerate the three data snapshots the APK ships with:
#
#   mesh.json             — wg0 mesh nodes / peers / transports
#                            (source: cloud/a_solutions/bb-net_wireguard-mesh/
#                             src/data/mesh.json, schema wg-mesh/v1)
#
#   services_public.json  — containers WITH proxy.domain (caddy edge)
#   services_private.json — containers WITHOUT proxy (internal DBs,
#                            queues, MCPs, dev tooling, …)
#                            (source: cloud-data's
#                             _cloud-data-consolidated.json)
#
# Both upstream files are gitignored, so we commit the derived snapshots
# here. Re-run this script any time the upstream changes; the gradle
# build picks the new bytes up automatically on the next APK assembly.
#
# Usage:
#   ./regen.sh                       # auto-discover upstream paths
#   ./regen.sh <consolidated.json> <mesh.json>
#   ./regen.sh --constellation-only  # just constellation-fleet.json (no
#                                     # gitignored upstream files needed —
#                                     # this is what app/build.gradle runs
#                                     # automatically on every build)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# ── constellation-fleet.json — the Constellation AppStore's app registry.
# Auto-scanned from each sibling */build.json that declares constellation
# membership in its data (self-registering, DRY),
# in TWO shapes, no hand-maintained list (FIRE 4/6):
#   • top-level apps (browser/vault/wallet/superapp/nav/ide) — identity at
#     .android.application_id + .release.ghcr.image.
#   • fork-apps (dialer/chat/mail/matrix) — the promoted ex-cloud-comms forks,
#     each now its OWN ac_cloud-<id> dir + ship-cloud-<id>.yml CI; identity
#     lives under .forks.<key>. Scanned ONLY for non-top-level dirs, so the IDE
#     hub's own nested forks (editor/files/utils) are never pulled in.
# The cloud-comms HUB is decommissioned (archived to z_archive/ac_cloud-comms),
# so it is no longer scanned and drops out of the fleet automatically.
# Baked into BuildConfig.CONSTELLATION_FLEET_B64 by app/build.gradle. Depends
# ONLY on the sibling repos (always present), so it runs before the cloud-data
# snapshot resolution below.
UNIX="$(cd "$HERE/../.." && pwd)"
regen_constellation() {
    command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; return 1; }
    # owner/repo = the monorepo these apps ship from (invariant identity).
    #
    # EVERY ASSET URL BELOW NAMES ITS TAG EXPLICITLY, as
    # <rel>/download/<tag>/<asset> — never <rel>/<tag>/download/<asset>.
    #
    # The two are different GitHub routes and only one of them is stable.
    # `/releases/latest/download/<asset>` is a MAGIC route: GitHub resolves
    # "whichever release is currently latest" and only then looks for the asset
    # on it. Our rolling release is TAGGED the literal word `latest`, so the two
    # coincide right up to the moment any per-app tagged release is published —
    # cloud-comms-mail-*, firestack-aar-*, cloud-unix-termux-boot-v* all are —
    # whereupon the magic route resolves to THAT release, which does not carry
    # our assets, and every install URL in the fleet answers 302 then 404.
    #
    # Measured 2026-09-10: at 11:57:46Z
    # /releases/latest/download/cloud-nixdroid.apk redirected to
    # /releases/download/firestack-aar-20260910.114222/cloud-nixdroid.apk → 404
    # (x-github-request-id CAF4:289D54:E9BFD0:14410E7:6AA29B0B). Three minutes
    # later the same URL served 200. It FLAPS with whatever shipped last, which
    # is why this read for days as an intermittent phone fault rather than as a
    # broken URL, and why Cloud Terminal (Nix) — the one app with no registry
    # package to fall back to — was the one that surfaced it, as a bare 403.
    #
    # `/releases/download/<tag>/<asset>` names the tag and resolves to nothing
    # else, so no later publish can hijack it.
    local rel="https://github.com/diegonmarcos/cloudy-u-android/releases"
    local tree="https://github.com/diegonmarcos/cloudy-u-android/tree/main"
    local pkg="https://github.com/diegonmarcos/cloudy-u-android/pkgs/container"

    # ── Per-ABI release assets: { "<abi>": "<gh_asset>", … } ──────────────────
    # A fleet entry carried ONE flat `asset`/`release_url`, taken from
    # release.gh_release.asset_name — which is the arm64 name, because arm64 is
    # the default publish. The updater's GHCR path was already ABI-aware (the
    # `latest-x86_64` tag comes from these same variants), but the GitHub
    # Release path was not, so an x86_64 device that took the release channel —
    # which is tried FIRST — downloaded the arm64 APK and failed with
    # INSTALL_FAILED_NO_MATCHING_ABIS, or 404ed. Half the ABI dimension was
    # described and the other half was silently wrong.
    #
    # Keyed by supported_abis (falling back to abis, then to the variant id for
    # older entries that only carry one) because supported_abis is EXACTLY the
    # key set AbiUpdateTag matches Build.SUPPORTED_ABIS against for the tag —
    # two lookups keyed differently would be two rules again.
    # `asset` is left untouched: it is the fallback and the dedup key.
    #
    # #876: `wasm` is NOT an ABI. An app whose build.json declares a `web` block
    # also ships its Kotlin/Wasm page, and `assets.wasm` names that release asset
    # (web.gh_asset). The updater matches Build.SUPPORTED_ABIS against these keys,
    # so a key that is no ABI is never picked for an install; the cloud-web portal
    # (diegonmarcos.github.io Pages deploy; sources: cloudy-h-webapps-desk a_Portals/cloud-web) lists exactly the rows that
    # carry it and fetches that zip.
    local variant_assets='
        (. as $bj
         | [ (.release.variants // [])[]
          | . as $v
          | ($v.supported_abis // $v.abis // [$v.id])[]?
          | select(type == "string" and . != "")
          | { key: ., value: ($v.gh_asset // "") } ]
        | map(select(.value != "")) | from_entries
        | . + (if (($bj.web // {}).gh_asset // "") != "" then { wasm: $bj.web.gh_asset } else {} end))'

    # ── OUR declared version, one jq expression for all three emitters (#631) ──
    # The store's identity line must show the FLEET's version, and the only
    # other version a phone can see is the installed APK's — which for a comms
    # fork is upstream's string and for our own apps already has `(sha-…)`
    # folded in by app/build.gradle. The declared pair is build.json::android
    # .version_name / .version_code, so it is emitted from the same declaration
    # gradle reads, and NOT emitted at all where nothing declares it: an absent
    # key is the honest answer and FleetIdentity prints a marker for it, which
    # is the whole point — a fork must not borrow upstream's number.
    #
    # NOT the APK's effective versionCode. That is codeFinal, minutes since
    # 2026, computed at build time and unknowable here; the row labels it
    # `apk build` and reads it off the installed package.
    local vers='
        ( if ((.android.version_name // "") != "")
          then { version_name: .android.version_name } else {} end )
        + ( if ((.android.version_code // 0) > 0)
            then { version_code: .android.version_code } else {} end )'

    # #728 THE ENGINES AN APP BINDS, carried onto its own fleet row. Store ▸
    # Mesh draws every app -> engine link from this, so the link set is the
    # apps' own build.json::engines (#705: fleet row, action, min_contract) and
    # never a list in Kotlin. Emitted only where an app declares one; `binding`
    # is the declaration's key (gh, cal, feed, news ...).
    local engines='
        ( (.engines // {}) | to_entries
          | map(select(.key != "_doc" and (.value | type) == "object")
                | { binding: .key, fleet: .value.fleet, action: .value.action,
                    min_contract: .value.min_contract }) ) as $e
        | if ($e | length) > 0 then { engines: $e } else {} end'

    # #678 THE LIBS AN APP LINKS, carried onto its own fleet row. Store ▸ Cloud
    # ▸ Details lists them, so the list is the app's own
    # build.json::modules.app.depends_on (the gradle project edges — compiled
    # in by definition) and never a list in Kotlin. Emitted as fleet ids
    # (lib-<module>), the id the lib's own Libs row carries. Engines installed
    # separately are the `engines` above, not this.
    local libdeps='
        ( [ (.modules.app.depends_on // [])[]
            | select(type == "string" and startswith("libs:"))
            | "lib-" + ltrimstr("libs:") ] ) as $l
        | if ($l | length) > 0 then { libs: $l } else {} end'

    # Scan EVERY sibling repo's build.json, not just ac_cloud-*/. Membership is a
    # property of the DATA, not of the directory name: a dir self-registers as a
    # top-level app (.android.application_id + .release.ghcr), a multi-lib repo
    # (.lib_apks) or a fork-app (a real .forks.<key> entry), and anything else is
    # skipped. The old ac_cloud-* glob made the prefix load-bearing, so
    # cloud-infra-desktop-termux-boot - named for the host it targets - was invisible to
    # the store no matter what its build.json said. This predicate admits exactly
    # the same repos the glob did, plus any correctly-declared one.
    # id = dir basename sans its family's strip prefix (app-families.json: ac_cloud- / ac_c3- / ad_) if it has one.
    # The c3- rebrand renames apps out of the ac_cloud- namespace; without the
    # second strip an app's fleet id changes shape the moment it is renamed
    # (watchdog -> ac_c3-watchdog), which is a gratuitous id change for what is
    # meant to be a directory rename.
    local apps="[]" bj id dir reldir
    # One level deep AND one nested level: ab_cloud-libs-shared holds the two
    # build harnesses (lib-apks, keyboard-engines) as subdirectories since the
    # 2026-08-30 consolidation, and a root-only glob dropped them silently --
    # the fleet fell from 51 entries to 17 with no error, taking every
    # Cloud-Lib-*.apk out of the store. Membership stays a property of the
    # DATA (the jq predicate below), never of the depth or the prefix.
    for bj in "$UNIX"/*/build.json "$UNIX"/*/*/build.json; do
        [ -f "$bj" ] || continue
        jq -e '(.lib_apks != null) or (.forks != null)
               or (.android.application_id != null and .release.ghcr != null)' \
           "$bj" >/dev/null 2>&1 || continue
        dir="$(basename "$(dirname "$bj")")"
        # The fleet id is the folder without its family's strip prefix (1_cicd/src/data/app-families.json: ac_cloud- / ac_c3- for
        # the Cloud family, ad_ lower-cased for the Lnktree family); a folder of no family keeps its name.
        id="$(jq -r --arg d "$dir" '
            ((.families | map(select(. as $fam | $d | startswith($fam.folder_prefix))) | first) // null) as $f
            | if $f == null then $d else
                (($f.id_strip | map(select(. as $s | $d | startswith($s))) | first) // "") as $s
                | ($d | ltrimstr($s)) | if $f.lowercase_id then ascii_downcase else . end end' \
            "$UNIX/1_cicd/src/data/app-families.json")"
        # repo_url must survive nesting. The glob reaches two levels, so a
        # dir can be a_solutions/ae-tool_termux-boot - basename alone built
        # .../tree/main/ae-tool_termux-boot, a 404. Identical to $dir for
        # every top-level app, so nothing else moves.
        reldir="$(realpath --relative-to="$UNIX" "$(dirname "$bj")")"
        if jq -e '.lib_apks.scan' "$bj" >/dev/null 2>&1; then
            # ── Multi-lib repo (ab_cloud-libs-shared/lib-apks) ────────────────────────────────
            # One repo, one APK per library module, so it expands into MANY fleet
            # entries instead of one. The module set is not listed anywhere: it is
            # the same scan+exclude that settings.gradle and build.sh apply, so a
            # new module under ab_cloud-libs-shared/libs/ appears in the Libs tab
            # automatically. Everything else here is derived from the dir name.
            local lroot lscan lrel lpair lexcl lprefix laprefix limgprefix lmod lasset
            lexcl="$(jq -r '(.lib_apks.exclude // {}) | keys[]' "$bj" 2>/dev/null | tr '\n' ' ')"
            lprefix="$(jq -r '.lib_apks.application_id_prefix' "$bj")"
            laprefix="$(jq -r '.lib_apks.asset_prefix' "$bj")"
            limgprefix="$(jq -r '.release.ghcr.image_prefix' "$bj")"
            # lib_apks.scan is a LIST of roots (library modules live beside the
            # app that grew them), so flatten to "<module>|<repo-relative dir>"
            # first: the directory is what repo_url has to point at, and it is
            # no longer always aa_cloud-superapp/libs (consolidated into ab_cloud-libs-shared/libs 2026-08-30).
            for lpair in $(
                for lroot in $(jq -r '.lib_apks.scan | if type == "array" then .[] else . end' "$bj"); do
                    lscan="$(dirname "$bj")/$lroot"
                    [ -d "$lscan" ] || continue
                    lrel="$(realpath --relative-to="$UNIX" "$lscan")"
                    for lmod in $(ls -1 "$lscan" 2>/dev/null); do
                        [ -f "$lscan/$lmod/build.gradle" ] || continue
                        printf '%s|%s\n' "$lmod" "$lrel"
                    done
                done | sort
            ); do
                lmod="${lpair%%|*}"; lrel="${lpair##*|}"
                case " $lexcl " in *" $lmod "*) continue ;; esac
                # Cloud-Lib-Ml-L-Voice-Vosk.apk — each '-' segment capitalised,
                # matching build.sh's asset naming exactly.
                lasset="$laprefix$(echo "$lmod" | awk -F- '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) substr($i,2)}}1' OFS=-).apk"
                apps="$(jq --argjson acc "$apps" --arg id "lib-$lmod" --arg dir "$dir" \
                           --arg mod "$lmod" --arg asset "$lasset" \
                           --arg pkgid "$lprefix.$(echo "$lmod" | tr -d '-')" \
                           --arg img "$limgprefix$lmod" \
                           --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" \
                           --arg libdir "$lrel" '
                    ($rel + "/download/" + (.release.gh_release.rolling_tag // "latest")
                          + "/" + $asset) as $url
                    # First scan root to offer a module wins. The scan roots
                    # OVERLAP: ab_cloud-libs and ab_cloud-libs-shared/lib-apks
                    # both harness the same consolidated ab_cloud-libs-shared/libs,
                    # and the pre-consolidation per-app libs/ trees still exist,
                    # so without this guard every shared module was emitted two
                    # or three times and a plain regen turned 56 entries into
                    # 114. One module is one lib APK is one fleet entry.
                    | if ($acc | any(.id == $id)) then $acc else $acc + [
                              { id: $id,
                                # ONE canonical display name for every lib: the
                                # same cloud-{name} rule #351 applies to apps,
                                # spelled cloud-lib-{name} with consistent dashes
                                # — NOT the old ad-hoc "Lib: {name}" with no
                                # cloud prefix (the #474 naming mess). $mod is
                                # the module dir name (already correctly dashed,
                                # e.g. kde-connect, net-wg, text-tools), so the
                                # canonical label falls out of that ONE
                                # declaration here; a second display-name field
                                # (the #380 mistake) is not the fix, this line is.
                                label: ("cloud-lib-" + $mod),
                                 package: $pkgid,
                                 alt_id: null,
                                 registry: .release.ghcr.registry,
                                 namespace: .release.ghcr.namespace,
                                 image: $img,
                                 tag: (.release.auto_update.tag // "latest"),
                                 asset: $asset,
                                 release_url: $url,
                                 repo_url: ($tree + "/" + $libdir + "/" + $mod),
                                 ghcr_page: ($pkg + "/" + $img),
                                 blocked: false,
                                 kind: "lib" }
                                # #631 OUR version, so the one identity pattern
                                # has a declared version to print rather than
                                # the installed APK version string. Adds nothing
                                # where nothing declares one: absent is the
                                # honest answer and the UI says so.
                                + ('"$vers"') ] end' "$bj")"
            done
        elif jq -e '.release.ghcr.image and .android.application_id' "$bj" >/dev/null 2>&1; then
            # ── Top-level app (browser/vault/wallet/superapp/nav/ide) ─────────
            # Publishes a rolling `latest` release → stable direct-download URL.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$reldir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                ($rel + "/download/" + (.release.gh_release.rolling_tag // "latest")
                      + "/" + .release.gh_release.asset_name) as $url
                | ('"$variant_assets"') as $assets
                | $acc + [ ( { id: $id,
                             label: (.name // $id),
                             display_name: (.display_name // .name // $id),
                             package: .android.application_id,
                             alt_id: null,
                             registry: .release.ghcr.registry,
                             namespace: .release.ghcr.namespace,
                             image: .release.ghcr.image,
                             tag: (.release.auto_update.tag // "latest"),
                             asset: .release.gh_release.asset_name,
                             release_url: $url,
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + .release.ghcr.image),
                             # AN APP WITH NO BUILD HOST HAS NOTHING TO OFFER.
                             # The fork branch below has always derived `blocked`
                             # from declared data; this branch hardcoded false, so
                             # a top-level app had no way to say "I cannot be
                             # published yet" and the store offered it regardless.
                             # ac_cloud-office is the case that proved it: it
                             # stopped mirroring the official Collabora APK and
                             # became a source build, so its build.json now names
                             # Cloud-Office.apk, com.diegonmarcos.cloudoffice and
                             # the cloud-office GHCR image — none of which exist,
                             # because build.host is null and ship-cloud-office.yml
                             # refuses to run without one. Every field here is
                             # correct and every one of them points at a 404.
                             # Derived, not declared: `build.host` present-but-null
                             # IS the statement "a host is required and there is
                             # none", so no second field can disagree with it.
                             # Fleet.kt::status returns State.Blocked and
                             # StoreCloudFragment hides Install/Direct, so the
                             # row still shows and still opens — it just stops
                             # promising an install that cannot happen.
                             blocked: (((.build // {}) | has("host")) and (.build.host == null)),
                             kind: (.release.kind // "app") }
                             + ('"$vers"')
                             + ('"$engines"')
                             + ('"$libdeps"')
                             + (if ($assets | length) > 0 then { assets: $assets } else {} end) ) ]' "$bj")"
        elif jq -e '(.forks // {}) | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | length > 0' "$bj" >/dev/null 2>&1; then
            # ── Fork-app (dialer/chat/mail/matrix) ───────────────────────────
            # A standalone ac_cloud-<id> promoted from an ex-cloud-comms fork:
            # identity under .forks.<key> (one real fork per dir). Forks publish
            # --latest=false tagged releases → link the releases page, not a
            # /latest/download. keystore-signed package id is preserved.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$reldir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                (.release.ghcr) as $cg
                | ( .forks | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | .[0].value ) as $f
                | ('"$variant_assets"') as $assets
                | $acc + [ ( { id: $id,
                             # .name, the SAME field the top-level branch reads
                             # (#351). Fork-apps used to declare their name as
                             # .forks.<key>.label, a second field for one fact, and
                             # it is where "Cloud Chat (Mattermost)" and
                             # "Messenger (Element)" lived while every top-level
                             # app already said cloud-<x>.
                             label: (.name // $id),
                             display_name: (.display_name // .name // $id),
                             package: $f.app_id,
                             alt_id: ($f.alt_id // null),
                             registry: $cg.registry,
                             namespace: $cg.namespace,
                             image: $f.image,
                             tag: "latest",
                             asset: ($f.image + ".apk"),
                             # A DIRECT download URL when the fork publishes to
                             # a rolling tag, the releases page otherwise.
                             #
                             # This branch used to always hand back the page,
                             # on the reasoning that forks publish
                             # --latest=false tagged releases and so have no
                             # stable /latest/download. True of the comms
                             # forks; false of any fork declaring
                             # release.gh_release.rolling_tag, which is exactly
                             # what a stable install URL is.
                             #
                             # The updater does not render a page: ReleaseSource
                             # fetched the HTML, failed to verify it, and fell
                             # through to GHCR — whose digest then did not match
                             # the bytes, so a missing URL surfaced as "digest
                             # mismatch" and pointed at the wrong thing entirely.
                             release_url: (
                               if (.release.gh_release.rolling_tag // "") != ""
                               then $rel + "/download/" + .release.gh_release.rolling_tag
                                    + "/" + ($f.image + ".apk")
                               else $rel end ),
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + $f.image),
                             blocked: ($f.blocked_on != null),
                             kind: (.release.kind // "app") }
                             + ('"$vers"')
                             + ('"$engines"')
                             + ('"$libdeps"')
                             + (if ($assets | length) > 0 then { assets: $assets } else {} end) ) ]' "$bj")"
        fi
    done

    # Cross-repo fleet members. The scan above walks THIS monorepo's siblings,
    # so an app that ships from another repo cannot be discovered no matter how
    # correctly its build.json is declared. Kept as data in
    # aa_cloud-superapp/build.json::constellation.external (FIRE RULE #6) and
    # merged here, with its own release/repo/ghcr URLs pointing at its own repo.
    local selfbj="$UNIX/aa_cloud-superapp/build.json"
    if [ -f "$selfbj" ]; then
        apps="$(jq --argjson acc "$apps" '$acc + (.constellation.external // [])' "$selfbj")"
    fi

    # ── Runtime payloads are LIBRARIES (#618 -> #624 -> #628) ────────────────
    # #618 moved the two terminals' ~400 MB runtime trees out of their APKs and
    # onto the release beside them, addressed by content. #624 gave each one a
    # fleet row — but a CATALOGUE row, which carries no package and no
    # versionCode, so the store could draw it and nothing else, and each terminal
    # went on downloading its own runtime at first launch from a url baked into
    # the APK.
    #
    # #628 finished it: each payload is a REAL LIBRARY. Its own signed, versioned
    # APK, built and published as a companion beside the app's own
    # (build.json::release.companions[]), and listed HERE, in `apps`, under the
    # same Libs group as cloud-lib-cal — because `apps` is what Fleet.parse
    # reads, and therefore the only place the store's install and update paths
    # can be handed it at all. The terminal downloads nothing; the store does.
    #
    # Resolved by the fleet-manifest guard's own --emit-rootfs-libs, not by jq
    # here: the version is a sha256-over-sha256s content address the guard must
    # recompute anyway to prove the manifest current, and two implementations of
    # an address would be two versions of one library. The guard emits every
    # field EXCEPT the three urls, whose constants live once at the top of this
    # function — so a rootfs lib row and any other lib APK row are assembled
    # from the same two halves, and the group stamp below treats them alike.
    local rootfs_libs
    rootfs_libs="$(python3 "$UNIX/1_cicd/src/scripts/cloud-android-fleet-manifest-guard.py" \
                        --root "$UNIX" --emit-rootfs-libs)" \
        || { echo "ERROR: runtime payload libraries do not resolve (#628)" >&2; return 1; }
    # A content address computed on a base that predates the newest change to
    # the payload's tree is a true address of an OLD tree: writing it regressed
    # the row twice (c36c865f4 -> e6a74e461). Refuse, and name the fix.
    local stale_dir stale_n
    while IFS= read -r stale_dir; do
        stale_n="$(git -C "$UNIX" rev-list --count "HEAD..origin/main" -- "$stale_dir" 2>/dev/null || echo 0)"
        if [ "${stale_n:-0}" -gt 0 ]; then
            echo "ERROR: stale base - origin/main has $stale_n newer commit(s) touching $stale_dir, so its content address here is out of date." >&2
            echo "       git fetch && rebase (or new worktree) onto origin/main, then re-run regen.sh." >&2
            return 1
        fi
    done < <(printf '%s' "$rootfs_libs" | jq -r '.[].app_dir')
    apps="$(jq -n --argjson apps "$apps" --argjson libs "$rootfs_libs" \
               --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
        $apps + [ $libs[]
          | . as $l
          # module / app_dir / what are the guard'"'"'s resolution inputs, not fleet
          # fields: dropped so a rootfs lib row is key-for-key the same shape as
          # every other lib row and nothing downstream needs a case for it.
          | del(.module, .app_dir, .what)
          + { release_url: ($rel + "/download/latest/" + $l.asset),
              repo_url: ($tree + "/" + $l.app_dir),
              ghcr_page: ($pkg + "/" + $l.image) } ]')"

    # Reference rows are resolved here too, by the SAME ml_group, so the
    # catalogue's tab and the catalogue's name are one fact as well. installable
    # is stamped rather than trusted from the data, so no catalogue row can ever
    # claim to be installable.
    #
    # `kind` extends the ONE group rule to the catalogue instead of adding a
    # second one: an ML reference names its own tab, anything else takes the tab
    # whose default_for_kind is its kind — exactly what the apps line above does.
    # A row with no kind keeps landing in no group and keeps failing below, so
    # the existing ML naming requirement is untouched.
    # One asset is one installable APK is one fleet entry. Duplicates reach
    # here two ways: two build.json describing the same APK
    # (ab_cloud-keyboard-libs and ab_cloud-libs-shared/keyboard-engines both
    # publish Cloud-Keyboard-Libs.apk), and a stale .constellation.external
    # member shadowing a scanned entry with a GHCR image that does not exist
    # (cloud-infra-desktop-termux-boot vs the real cloud-unix-termux-boot).
    # Either way the store would list one app twice, and in the second case the
    # duplicate is uninstallable. Scanned entries are appended before external
    # ones, so first-wins keeps the entry backed by a real build.json. Written
    # as a reduce, not unique_by, because unique_by would also re-sort the fleet.
    apps="$(jq -n --argjson apps "$apps" '
        $apps | reduce .[] as $e ([];
            if any(.[]; .asset == $e.asset) then . else . + [$e] end)')"

    # ── Groups: which Constellation tab each entry is drawn in (#343, #405) ──
    # build.json::constellation.groups lists the tabs in render order.
    #
    # An ML entry NAMES ITS OWN TAB (#405): the scheme is
    # ml-{t|l}-{domain}-{name}, so the weight-class segment of an id matching
    # ^lib-ml-([tl])- IS the tab. Everything else takes the group whose
    # default_for_kind is its kind (a lib module defaults to "libs").
    #
    # This replaced a hand-written group_members map of {fleet id -> tab}. That
    # map was a SECOND statement of a fact the name already made: rename or
    # reclassify a module and the two silently disagreed, and nothing could tell
    # which one was right. keyboard-engines was pinned into Lite-ML there purely
    # because the APK bundles the two engines - the owner asked for it back in
    # Libs (#405), and it gets there by saying nothing at all.
    #
    # ONE rule, applied to BOTH apps and catalogue rows, defined once here so the
    # two can never drift. `capture` emits nothing when the id is not an ML name,
    # so `// null` is what turns "not ML" into a value the validator below can
    # see - without it the whole row would silently vanish from the output.
    # StoreCloudFragment renders exactly these groups and names none of them,
    # so a missing or unknown group is a row in no tab at all - every way the
    # data can disagree with itself therefore fails the regen instead of
    # shipping.
    local ml_group='def ml_group($id): ($id | capture("^lib-ml-(?<weight>[tl])-") | "libs-\(.weight)-ml");'
    apps="$(jq --argjson apps "$apps" "$ml_group"'
        ((.constellation.groups // []) | map(select(.default_for_kind != null)
              | { key: .default_for_kind, value: .id }) | from_entries) as $defaults
        | $apps | map(. + { group: (ml_group(.id) // $defaults[.kind]) })' "$selfbj")"
    local catalogue
    catalogue="$(jq "$ml_group"'
        ((.constellation.groups // []) | map(select(.default_for_kind != null)
              | { key: .default_for_kind, value: .id }) | from_entries) as $defaults
        | [ ((.constellation.catalogue // [])[] | . + { kind: null }) ]
        | map({ id, label: (.label // .id),
                group: (ml_group(.id) // (if .kind then $defaults[.kind] else null end)),
                description: (.description // ""), installable: false }
              # `version` only where there IS one, so the 40-odd ML reference rows
              # stay byte-identical and the baked base64 does not grow for them.
              + (if .version then { version: .version } else {} end))' "$selfbj")"
    local group_problems
    group_problems="$(jq -r --argjson apps "$apps" --argjson catalogue "$catalogue" '
        ((.constellation.groups // []) | map(.id)) as $declared
        | ( $apps[] | select(.group == null or (.group | IN($declared[]) | not))
              | "fleet entry \(.id) (kind \(.kind)) is in no declared group: \(.group)" ),
          ( $catalogue[] | select(.group == null or (.group | IN($declared[]) | not))
              | "catalogue row \(.id) is in no declared group: \(.group) - every catalogue row is an ML reference and must be named ml-{t|l}-{domain}-{name} behind the lib- prefix" ),
          ( ($apps | map(.id)) + ($catalogue | map(.id)) | group_by(.)[]
              | select(length > 1)
              | "id \(.[0]) is used more than once across apps and catalogue" )' "$selfbj")"
    if [ -n "$group_problems" ]; then
        printf 'ERROR: constellation groups disagree with the fleet:\n%s\n' "$group_problems" >&2
        return 1
    fi

    # ── Lib categories: the tables INSIDE the Libs tab (#642) ────────────────
    # A group is a TAB; a category is a table within one. Apps get their tables
    # from the launcher's central classification, which knows nothing about a
    # library package - so every lib row landed in one unnamed run and the tab
    # read as a flat list. The categories are declared once, in render order,
    # beside the scan rule that already owns what the libs ARE, and are carried
    # here in the SAME {id,label,members} shape as the groups so the page reads
    # one shape for both. No per-row `category` field: a second statement of
    # this same fact is the group_members mistake (#405) with a new name.
    local libcatbj libcats libcat_problems
    libcatbj="$UNIX/ab_cloud-libs-shared/lib-apks/build.json"
    libcats="$(jq -c '[ (.lib_apks.categories // [])[]
        | { id, label: (.label // .id), members: (.members // []) } ]' "$libcatbj")"
    # A member that is not a lib row is a category left pointing at a renamed or
    # deleted module: the row it names is drawn nowhere and the table it should
    # head silently loses an entry. An UNLISTED lib is NOT an error - FIRE RULE
    # #6 says a new module ships with no edit here, and the page heads it
    # "Other" - so only the stale direction fails.
    libcat_problems="$(jq -rn --argjson apps "$apps" --argjson cats "$libcats" '
        [ $apps[] | select(.kind == "lib") | .id ] as $libs
        | ( $cats[] as $c | $c.members[] | select(IN($libs[]) | not)
              | "lib category \($c.id) names \(.), which is not a lib row in the fleet" ),
          ( [ $cats[].members[] ] | group_by(.)[] | select(length > 1)
              | "lib \(.[0]) is filed under more than one category" )')"
    if [ -n "$libcat_problems" ]; then
        printf 'ERROR: lib_apks.categories disagrees with the fleet:\n%s\n' "$libcat_problems" >&2
        return 1
    fi

    # ── App categories: the tables INSIDE the Cloud ▸ Apps tab ───────────────
    # DERIVED from the SuperApp's Cloud ▸ Apps page (build.json ui.sections[id=cloud]
    # .tile_groups) by app-categories.py: same titles, same order, each app under the
    # group whose tile opens it, the rest under a declared last "Other". Never typed;
    # test-store-cloud-categories.sh re-derives it independently and compares.
    local appcats
    appcats="$(printf '%s' "$apps" > "$HERE/.apps.tmp.json" \
        && python3 "$HERE/app-categories.py" "$selfbj" "$HERE/.apps.tmp.json"; rc=$?; rm -f "$HERE/.apps.tmp.json"; exit $rc)" \
        || { echo "ERROR: app-categories.py failed" >&2; return 1; }

    # Reference rows go OUT of `apps` on purpose: Fleet.parse reads only `apps`,
    # so the updater, both auto-update workers, PrivilegedGrants and the recovery
    # screens can never be handed an entry with no package. installable:false is
    # stamped here rather than trusted from the data, so no catalogue row can
    # ever claim to be installable.
    #
    # ONE COMPACT ENTRY PER LINE, NOT PRETTY-PRINTED. libs:updater and
    # libs:appstore bake this file's RAW bytes into a BuildConfig String, and
    # javac refuses any constant past 65,535 bytes. Pretty-printed, the 63-entry
    # fleet already baked to 61,368 base64 bytes before the groups existed, so
    # the indentation alone was about to break every build that links the
    # updater. One entry per line keeps a diff readable per entry at compact size.
    jq -r --argjson apps "$apps" --argjson catalogue "$catalogue" --argjson libcats "$libcats" --argjson appcats "$appcats" '
        # members is the same group stamp read from the tab side, so the page
        # builds its tabs without reading any entry field Fleet.parse owns.
        [ (.constellation.groups // [])[] | .id as $id
            # build_time_libs ("shared" | "ml") names the page that draws that build-time lib
            # table (#1441); only where declared, so the other groups stay byte-identical.
            | { id, label: (.label // .id), blurb: (.blurb // "") }
              + (if .build_time_libs then { build_time_libs } else {} end)
              + { members: [ ($apps[], $catalogue[]) | select(.group == $id) | .id ] } ] as $groups
        | "{\"schemaVersion\":1,\"version\":1,",
          "\"groups\":[", ($groups | map(tojson) | join(",\n")), "],",
          "\"lib_categories\":[", ($libcats | map(tojson) | join(",\n")), "],",
          "\"app_categories\":[", ($appcats | map(tojson) | join(",\n")), "],",
          "\"apps\":[", ($apps | map(tojson) | join(",\n")), "],",
          "\"catalogue\":[", ($catalogue | map(tojson) | join(",\n")), "]}"
        ' "$selfbj" > "$HERE/constellation-fleet.json"
    jq -e '.apps | length > 0' "$HERE/constellation-fleet.json" >/dev/null \
        || { echo "ERROR: constellation-fleet.json is not valid JSON with apps" >&2; return 1; }
    echo "constellation apps: $(jq '.apps | length' "$HERE/constellation-fleet.json")"
    # lib-consumers.json: which apps bind each engine at runtime and which compile each
    # non-engine shared lib in, derived from every build.json / manifest by the script the
    # guard re-runs, so the Store's Libs tab never reads a typed list. Its own file (not a
    # key of the fleet file) so the fleet blob stays small.
    python3 "$UNIX/1_cicd/src/scripts/cloud-android-lib-consumers.py" write "$UNIX" \
        && jq -e '.build_time | length > 0' "$HERE/lib-consumers.json" >/dev/null \
        || { echo "ERROR: lib-consumers.json did not generate" >&2; return 1; }
    # fleet-agents.json: every agent each app declares in its build.json::fleet_agents,
    # consolidated by the script fleet-agents-guard.yml re-runs (Configs ▸ Fleet ▸ Agents).
    # After the fleet file, because it reads the packages from it.
    python3 "$UNIX/1_cicd/src/scripts/cloud-android-fleet-agents.py" write "$UNIX" \
        || { echo "ERROR: fleet-agents.json did not generate" >&2; return 1; }
    # apps-mcp-api.json (libs:appstore asset): the endpoints every Store > Cloud > Apps app declares in its
    # build.json::api_endpoints, consolidated for Store > Access > Apps MCP-API. After the fleet file (it reads
    # the fleet rows and groups from it).
    python3 "$UNIX/1_cicd/src/scripts/cloud-android-apps-mcp-api.py" write "$UNIX" \
        || { echo "ERROR: apps-mcp-api.json did not generate" >&2; return 1; }
}
regen_constellation

[ "${1:-}" = "--constellation-only" ] && exit 0

CONSOLIDATED="${1:-}"
MESH="${2:-}"
LINKTREE="${3:-}"

if [ -z "$CONSOLIDATED" ]; then
    for cand in \
        "$HOME/git/cloud-t-infra/1_cloud-configs/dist/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-t-infra/I_cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-infra-desktop/cloud-data/_cloud-data-consolidated.json"; do
        [ -f "$cand" ] && { CONSOLIDATED="$cand"; break; }
    done
fi
if [ -z "$MESH" ]; then
    for cand in \
        "$HOME/git/cloud-t-infra/1_cloud-configs/dist/mesh-snapshot.json" \
        "$HOME/git/cloud-t-infra/a_solutions/infra-net_wireguard-mesh/src/code/src/data/mesh.json" \
        "$HOME/git/cloud-data/cloud-data-wg-mesh-snapshot.json"; do
        [ -f "$cand" ] && { MESH="$cand"; break; }
    done
fi
if [ -z "$LINKTREE" ]; then
    for cand in \
        "$HOME/git/front-diegonmarcos/a-Portals/linktree/src/data/projects.json"; do
        [ -f "$cand" ] && { LINKTREE="$cand"; break; }
    done
fi

: "${CONSOLIDATED:?consolidated.json not found; pass as arg 1}"
: "${MESH:?mesh.json not found; pass as arg 2}"
: "${LINKTREE:?projects.json not found; pass as arg 3}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 1; }

echo "→ mesh:         $MESH"
echo "→ consolidated: $CONSOLIDATED"
echo "→ linktree:     $LINKTREE"
echo "→ writing into: $HERE/"

# Mesh is a verbatim snapshot (wg-mesh/v1 schema; the APK parser owns
# the field-set). No transform.
cp "$MESH" "$HERE/mesh.json"

# Linktree projects.json is the source of truth for what shows
# under the Tools aggregator (Apps mode = SUITE+LAB+CIRCUS slides;
# Admin mode = CLOUD slide). Verbatim copy — the APK parser picks
# which slide to render based on build.json::ui.sections[tools].stack_*.
cp "$LINKTREE" "$HERE/linktree.json"

# Public services = containers WITH proxy.domain or proxy.parent_domain.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(type == "object")
 | select(.proxy.domain or .proxy.parent_domain)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     public_url: (if .proxy.domain then .proxy.domain else (.proxy.parent_domain + (.proxy.base_path // "")) end),
     auth: (.proxy.auth // "none"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     category: ($s.value.category // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_public.json"

# Private services = containers WITHOUT proxy.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(type == "object")
 | select((.proxy.domain or .proxy.parent_domain) | not)
 | select(.container_name)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     protocol: (.protocol // "tcp"),
     category: ($s.value.category // null),
     db_engine: (.db_engine // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_private.json"

echo "mesh nodes:       $(jq '.nodes | length' "$HERE/mesh.json")"
echo "mesh peers:       $(jq '.peers | length' "$HERE/mesh.json")"
echo "public services:  $(jq length    "$HERE/services_public.json")"
echo "private services: $(jq length    "$HERE/services_private.json")"
echo "linktree slides:  $(jq '.slides | length' "$HERE/linktree.json")"
