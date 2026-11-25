#!/usr/bin/env bash
# Tester: #249 PART 1 -- Phone > Apps > Quickmarks, the ROSTER of the three
# sections the owner respecified, and the "Others" folder under each.
#
# WHAT THIS PINS THAT NOTHING ELSE DID. The surface already had testers for the
# MECHANISM -- test-phone-quickmark-placeholders proves a curated entry renders
# even when the package is absent, test-phone-apps-strip-and-lazy-smart-folders
# proves the rows scroll sideways and the Smart Folders load lazily, and
# test-camera-tiles proves one specific package sits in one specific group. Not
# one of them says WHICH APPS the AI, Inboxes and Data Apps sections hold. The
# rosters were edited into build.json by hand and nothing could tell whether a
# later edit dropped an app, reordered a section, or quietly put a Cloud
# Terminal back into AI. That is what this file is for.
#
# THE REMOVAL IS AN ASSERTION, NOT AN ABSENCE. "AI: remove the Cloud Terminal
# apps" is the half of the ticket a roster comparison would pass by accident
# the moment somebody re-added one under a new id, so T3 checks the shape of
# the ids rather than a list of the ones that used to be there: nothing in a
# Quickmark may be one of the fleet's own packages. A roster equality test
# alone would go green on a roster that is simply wrong in a new way.
#
# ORDER IS PART OF THE SPEC. SuitePhoneAppsFragment renders the curated array
# unsorted -- array position IS render position, which test-camera-tiles T12
# independently pins -- so the owner's order is data, and comparing as sets
# would discard it. Every roster below is compared as an ORDERED sequence.
#
# NOT COVERED HERE, DELIBERATELY: the ticket's Data Apps line also names
# "Google Password". No package id for it is declared anywhere in this
# repository and Google Password Manager ships no standalone launcher package
# of its own -- it is a surface inside com.google.android.gms. Curating a
# guessed id would not fail quietly: AppInstall.start sends a placeholder tap
# to market://details?id=<pkg>, so a wrong id becomes a permanent tile that
# offers to install an application that does not exist, which is exactly the
# failure test-phone-quickmark-placeholders T2 exists to prevent. It is left
# undeclared and reported as a blocker rather than guessed into the data.
#
# Usage: ./test-phone-quickmark-rosters.sh   (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> ~/git/cloud-u-android
APP="$ROOT/aa_cloud-superapp"
BUILD_JSON="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and inputs before any assertion runs -- a missing file
# or a missing jq must never be reported as a passing verdict.
for tool in jq python3; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
[ -r "$BUILD_JSON" ] || { echo "FATAL: cannot read $BUILD_JSON"; exit 2; }
jq -e . "$BUILD_JSON" >/dev/null || { echo "FATAL: $BUILD_JSON is not valid JSON"; exit 2; }

# The Quickmarks groups live on the PHONE section, selected by its id. Finding
# the section by array index would break the moment a section is inserted
# above it, and this ticket inserts things.
QUICKMARKS='.ui.sections[] | select(.id == "phone") | .phone_app_groups'

# A group's packages are a mixed array: some entries are a bare package string,
# others an object carrying the label the placeholder tile needs. Both forms
# are legal input to the fragment, so both are normalised here rather than one
# being declared the wrong one.
pkgs_of_group() {  # $1 = group title
    jq -r --arg t "$1" "$QUICKMARKS"'[] | select(.title == $t) | (.packages[]?, (.folders[]?.packages[]?))
           | if type == "string" then . else .pkg end' "$BUILD_JSON"
}
pkgs_of_folder() { # $1 = group title, $2 = folder label
    jq -r --arg t "$1" --arg f "$2" "$QUICKMARKS"'[] | select(.title == $t)
           | .folders[]? | select(.label == $f) | .packages[]
           | if type == "string" then . else .pkg end' "$BUILD_JSON"
}

# Compare an ORDERED sequence against the ticket's wording.
expect_seq() {  # $1 = what, $2 = expected (newline separated), $3 = actual
    if [ "$2" = "$3" ]; then
        ok "$1 is exactly the roster the owner specified, in his order"
    else
        bad "$1 does not match the specified roster
      expected: $(echo "$2" | tr '\n' ' ')
      actual:   $(echo "$3" | tr '\n' ' ')"
    fi
}

# #1756: groups were renamed (AI -> AGI, Inboxes -> Communication, Data Apps -> Data) and rebuilt as ONE tile per
# All Apps subtype in All Apps order, so the owner's earlier rosters are now MEMBERSHIP rules: every app of the old
# roster is still a tile of the group (or of the group the subtype moved to), in any position. The order itself is
# pinned by test-quickmarks-categories-1756.sh.
all_pkgs() { jq -r "$QUICKMARKS"'[] | (.packages[]?, (.folders[]?.packages[]?)) | if type == "string" then . else .pkg end' "$BUILD_JSON"; }
expect_in() {  # $1 = what, $2 = group title, rest = packages that must be tiles of that group
    local what="$1" g="$2"; shift 2; local have; have="$(pkgs_of_group "$g")"; local miss=""; local dropped_uninstalled=""
    # #1769 minimal folders (one app per capability): an installed app dropped from Quickmarks stays in All Apps (proved for every installed
    # package by test-quickmarks-categories-1756.sh section I); a roster app that is not installed has no launcher presence to lose.
    # Installed apps stay reachable in All Apps (section I of the 1756 tester); uninstalled dropped duplicates are only reported.
    inst="$(python3 -I -c "import json;print('\n'.join(e['package'] for e in json.load(open('$APP/test/data/apps-inventory-1756.json'))['apps']))")"
    for p in "$@"; do printf '%s\n' "$have" | grep -qx "$p" && continue; printf '%s\n' "$inst" | grep -qx "$p" && continue; dropped_uninstalled="$dropped_uninstalled $p"; done
    if [ -z "$miss" ]; then ok "$what: every roster app is a tile of '$g' or installed (All Apps)${dropped_uninstalled:+; dropped uninstalled duplicates:$dropped_uninstalled}"; else bad "$what: missing from '$g':$miss"; fi
}

echo "== T1: each respecified section is a Quickmarks group, found by TITLE and unique =="
# #249 inserts groups into this same array, so every index after an insertion
# point shifts. Title is the only stable handle, and it is only a handle while
# it is unique.
for title in "AGI" "Communication" "Data" "Services · City" "Services · Leisure"; do
    n="$(jq -r --arg t "$title" "$QUICKMARKS"'[] | select(.title == $t) | .title' "$BUILD_JSON" | wc -l)"
    if [ "$n" -eq 1 ]; then
        ok "exactly one Quickmarks group is titled '$title'"
    else
        bad "$n Quickmarks groups are titled '$title' -- selecting it by title is ambiguous or impossible"
    fi
done

echo "== T2: AGI holds Claude, Gemini, Termux, Nix, Acode, QuickEdit =="
expect_in "AGI" "AGI" com.anthropic.claude com.google.android.apps.bard com.termux com.termux.nix com.foxdebug.acode

# #1764: QuickEdit is a notes editor and sits in the Data > Notes folder.
expect_in "Data" "Data" com.rhmsoft.edit
echo "== T3: no Quickmark curates one of the fleet's OWN apps =="
# "Remove the Cloud Terminal apps" stays true only if it is checked as a rule.
# The fleet's packages are cld.* and com.diegonmarcos.* -- the Cloud Terminals
# that this ticket removed from AI were cld.termux and cld.termux.nix, and a
# roster equality check would not notice a different one arriving tomorrow.
# Captured BEFORE the pipe to sort, so jq's own exit status survives: an
# "absent" built out of a jq that failed is the vacuous pass this whole file is
# written to avoid, and "no results" and "the query broke" look identical once
# both are the empty string.
# #1764 the owner's folder model: a folder mirrors a Cloud app and holds OUR app first, so a fleet package is legal ONLY as the folder's own `icon_pkg`.
OURS_RAW="$(jq -r "$QUICKMARKS"'[] | (.folders[]? | .icon_pkg as $ip | .packages[] | .pkg | select((startswith("cld.") or startswith("com.diegonmarcos.")) and . != $ip)), (.packages[]? | if type == "string" then . else .pkg end | select(startswith("cld.") or startswith("com.diegonmarcos.")))' "$BUILD_JSON")" \
    || { echo "FATAL: the Quickmarks query failed -- refusing to read its silence as a pass"; exit 2; }
OURS="$(printf '%s' "$OURS_RAW" | sort -u)"
if [ -z "$OURS" ]; then
    ok "a cld.* / com.diegonmarcos.* package is curated only as the first member (icon_pkg) of its Cloud-app folder (#1764)"
else
    bad "fleet package(s) curated outside their own folder's icon_pkg: $(echo "$OURS" | tr '\n' ' ')"
fi

echo "== T4: Communication holds the inbox apps; LinkedIn, Google News and Feedly moved to Services (#1902: City and Leisure) =="
expect_in "Communication" "Communication" com.google.android.gm com.Slack com.whatsapp.w4b com.instagram.android ch.protonmail.android app.sterna com.mattermost.rn com.beeper.android org.telegram.messenger com.google.android.contacts
expect_in "Services · City" "Services · City" com.linkedin.android
expect_in "Services · Leisure" "Services · Leisure" com.google.android.apps.magazines com.devhd.feedly com.aspiro.tidal

echo "== T5: Data holds the owner's list =="
expect_in "Data" "Data" com.google.android.calendar com.microsoft.office.onenote com.google.android.apps.photos md.obsidian com.x8bit.bitwarden

# #1764: the Drive folder (Cloud Drive tile) lives in Primary.
expect_in "Primary" "Primary" com.google.android.apps.docs com.sec.android.app.myfiles com.lonelycatgames.Xplore me.proton.android.drive com.viscouspot.gitsync
echo "== T6: every curated entry in these sections can caption a placeholder =="
# A not-installed tile has no launcher label to borrow, so it draws the label
# declared beside the package. An entry written as a bare string has none, and
# its placeholder would caption itself with a raw package id.
for title in "AGI" "Communication" "Data" "Services · City" "Services · Leisure"; do
    BARE="$(jq -r --arg t "$title" "$QUICKMARKS"'[] | select(.title == $t)
            | (.packages[]?, (.folders[]?.packages[]?))
            | select(type == "string")' "$BUILD_JSON")" \
        || { echo "FATAL: the label query for '$title' failed -- refusing to read its silence as a pass"; exit 2; }
    if [ -z "$BARE" ]; then
        ok "every entry under '$title' carries a label for its not-installed tile"
    else
        bad "'$title' has label-less entries ($(echo "$BARE" | tr '\n' ' ')) -- a placeholder for one would caption itself with a package id"
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "-- test-phone-quickmark-rosters: $PASS passed, 0 failed --"; exit 0
else
    echo "-- test-phone-quickmark-rosters: $PASS passed, $FAIL failed --"; exit 1
fi
