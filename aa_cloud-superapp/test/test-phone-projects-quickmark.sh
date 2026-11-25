#!/usr/bin/env bash
# Tester: the "Projects" group is on the QUICKMARKS page, in the position the
# owner named, and every tile in it can actually be tapped.
#
# THE FAILURE THIS EXISTS TO KEEP FIXED. build.json holds TWO unrelated
# phone-apps structures and they render as two different pages:
#
#   ui.sections[id=phone].phone_app_groups   QUICKMARKS -- curated, hand-written
#   ui.phone_sections + ui.phone_folders     ALL APPS   -- auto-classified
#
# On 2026-09-10 the owner asked for a Projects section "after Tools Primary and
# Before Configs". Configs is a quickmarks GROUP and appears nowhere in
# ui.phone_sections, so the anchor named quickmarks and could name nothing else.
# It shipped in ui.phone_sections instead -- the wrong page -- and every check
# that ran against it passed, because every check asked "does Projects exist"
# and none asked "where". Existence was never the question.
#
# So Q1 asserts POSITION, by index, with both neighbours named, and Q2 asserts
# the wrong surface is EMPTY. Either one alone is green on the shipped defect.
#
# Usage: ./test-phone-projects-quickmark.sh    (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> ~/git/cloud-u-android
BUILD_JSON="$ROOT/aa_cloud-superapp/build.json"
FRAGMENT="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and inputs, before any assertion runs. A tester whose
# tool is missing must refuse to answer, never answer from the absence -- four
# testers here were once green only because ripgrep was not installed.
for tool in jq grep wc; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
for f in "$BUILD_JSON" "$FRAGMENT"; do
    [ -f "$f" ] || { echo "FATAL: $f not found -- refusing to report a verdict"; exit 2; }
done

# q <filter> -- run jq, and DIE if jq itself fails. Assignment from $(...)
# preserves jq's exit status; a pipe would hand back the last stage's status
# instead, which is how a pipeline's verdict gets read through a `tail`.
q() {
    local out
    if ! out="$(jq -r "$1" "$BUILD_JSON")"; then
        echo "FATAL: jq failed (exit $?) on filter: $1"; exit 2
    fi
    printf '%s\n' "$out"
}
q '.ui.sections | length' >/dev/null

PHONE_GROUPS='.ui.sections[] | select(.id == "phone") | .phone_app_groups'

echo "== Q1: Projects sits at index 8, the last group, after Setup (#1916 MOVED from index 4 between Me and Services · City) =="
# THE ASSERTION THAT WOULD HAVE CAUGHT THE SHIPPED DEFECT. The index is read
# from the array rather than searched for by title, and both neighbours are
# named, because the owner specified a POSITION and not an existence: a group
# that exists at the wrong index is the same page with the wrong answer on it.
TITLES="$(q "[$PHONE_GROUPS | .[].title] | @tsv")"
INDEX_OF_PROJECTS="$(q "[$PHONE_GROUPS | .[].title] | index(\"Projects\") // -1")"
if [ "$INDEX_OF_PROJECTS" = "8" ]; then
    ok "phone_app_groups[8] is 'Projects'"
else
    bad "'Projects' is at phone_app_groups[$INDEX_OF_PROJECTS], expected 8 — groups are: $TITLES"
fi
BEFORE="$(q "[$PHONE_GROUPS | .[].title] | .[7] // \"\"")"
AFTER="$(q "[$PHONE_GROUPS | .[].title] | .[9] // \"\"")"
[ "$BEFORE" = "Setup" ] \
    && ok "the group before Projects is 'Setup'" \
    || bad "the group before Projects is '${BEFORE:-NOTHING}', expected 'Setup'"
[ -z "$AFTER" ] \
    && ok "no group follows Projects" \
    || bad "the group after Projects is '$AFTER', expected none"
LAST="$(q "[$PHONE_GROUPS | .[].title] | last")"
[ "$LAST" = "Projects" ] \
    && ok "'Projects' is the last group (#1916), right after Setup" \
    || bad "the last group is '$LAST', expected 'Projects'"

echo "== Q2: Projects is on QUICKMARKS and on no other surface =="
# The negative half, and the one the shipped defect needed. Guarding only the
# positive passes while a duplicate Projects sits in the All Apps taxonomy,
# which is precisely what the owner rejected.
SECTION_HIT="$(q '[.ui.phone_sections[] | select(.title == "Projects")] | length')"
[ "$SECTION_HIT" = "1" ] \
    && ok "ui.phone_sections declares the Projects section (#1756: All Apps has Projects, holding the Lnktree apps)" \
    || bad "ui.phone_sections declares $SECTION_HIT 'Projects' sections, expected 1"
FOLDER_HIT="$(q '[.ui.phone_folders[] | select(.label | test("Projects"))] | length')"
[ "$FOLDER_HIT" = "0" ] \
    && ok "ui.phone_folders declares no folder named Projects (the section holds SuperApp, Omniverse, FinTerm, CAD, BioMed)" \
    || bad "ui.phone_folders declares $FOLDER_HIT folder(s) named Projects"

echo "== Q3: the Projects group holds NO folder called Projects =="
# The owner objected to this one by name ("alsk fucking creayed a folser called
# Projects???"). It was never a design choice: the All Apps renderer draws a
# section as subhead + folder grid and has no slot for a loose app, so building
# Projects there forced a folder to hold the apps. A quickmarks group carries
# `packages` AND `folders`, so on this page the folder is unnecessary.
SELF_FOLDER="$(q "[$PHONE_GROUPS | .[] | select(.title == \"Projects\") | (.folders // [])[] | select(.label == \"Projects\")] | length")"
[ "$SELF_FOLDER" = "0" ] \
    && ok "no folder inside the Projects group repeats the group's own name" \
    || bad "the Projects group contains a folder called 'Projects' — the apps belong loose in the group"
LOOSE="$(q "[$PHONE_GROUPS | .[] | select(.title == \"Projects\") | .packages[]] | length")"
LINKS="$(q "[$PHONE_GROUPS | .[] | select(.title == \"Projects\") | .links[] | select(.label == \"Linktree\" and .url == \"https://linktree.diegonmarcos.com\")] | length")"
[ "$LOOSE" = "0" ] && [ "$LINKS" = "1" ] \
    && ok "#1764: the Projects group carries no apps and exactly one web shortcut, Linktree" \
    || bad "the Projects group carries $LOOSE app tiles and $LINKS Linktree links, expected 0 and 1 (#1764)"
MOVED="$(q "[$PHONE_GROUPS | .[] | (.packages[]?, (.folders[]?.packages[]?)) | select(.pkg == \"co.mangotechnologies.clickup\" or .pkg == \"com.google.android.apps.fitness\" or .pkg == \"com.fatsecret.android\" or .pkg == \"mobi.eup.easygerman\" or .pkg == \"com.nomadmania.presentation\")] | length")"
[ "$MOVED" = "4" ] \
    && ok "#1764: ClickUp, FatSecret, Todaii German and NomadMania kept in their real groups (#1769: Google Fit is a duplicate of Samsung Health, dropped from Quickmarks, still in All Apps)" \
    || bad "#1764: $MOVED of the five former Projects apps are in a group, expected 4"

echo "== Q4: the Money folder is inside the Services · City group (#1764, was Projects; #1902 split) and holds the bank apps =="
# DERIVED, never a second hand-written list. This file's own answer to "which
# packages are the bank apps" is the -Money folder in ui.phone_folders, so the
# expected set is read from there -- exact `pkg:` rules only, because the
# thirteenth keyword is the `pkg^com.btg.pactual.` family pattern and a
# classifier pattern is not something a tile can launch. Writing the twelve ids
# out here again would give the assertion its own copy of the answer, and two
# copies of an answer are two things that can disagree.
MONEY_TILES="$(q "[$PHONE_GROUPS | .[] | select(.title == \"Services · City\") | (.folders // [])[] | select(.label == \"Money\") | .packages[] | if type == \"string\" then . else .pkg end] | sort | join(\" \")")"
MONEY_DECLARED="$(q '[.ui.phone_folders[] | select(.id == "svc_money" or .id == "prod_wallet") | .match_keywords[] | select(startswith("pkg:")) | .[4:]] | sort | join(" ")')"
if [ -z "$MONEY_DECLARED" ]; then
    bad "ui.phone_folders has no svc_money folder to derive the bank-app list from"
else
    MISS=""
    for p in $MONEY_TILES; do case " $MONEY_DECLARED " in *" $p "*) ;; *) MISS="$MISS $p";; esac; done
    [ -z "$MISS" ] \
        && ok "every Money folder tile is pinned by Money (or Wallet, the Google Wallet split out in #1756)" \
        || bad "Money folder tiles not pinned by Money/Wallet:$MISS"
fi

echo "== Q5: every Projects tile resolves to a package the taxonomy pins =="
# NO DEAD TILES. Since 149c5b9 a curated entry whose package is not installed
# renders as a visible "Not installed" tile that offers to install it, so a
# package id that exists nowhere is permanent furniture the owner taps for
# nothing -- com.clickup.android, the guessable and wrong ClickUp id, is the
# live example.
#
# EXACT `pkg:` pins only, deliberately. Every package on this page is pinned
# with an exact rule (ui._doc_phone_folders: "Every app installed at the
# 2026-09-07 rebuild is pinned with an exact `pkg:` rule"), so demanding an
# exact pin is both achievable and stricter than "classifies somehow" -- a
# package that only matched a `pkg^` family or a bare substring would be
# classified by accident rather than declared.
#
# This covers the shape test-app-identity-resolves.sh T3 cannot see: T3 reads
# `packages` arrays with `select(type == "string")`, so the {"pkg","label"}
# form -- which is what every entry added since 2026-09-10 uses, including all
# of Projects -- contributes zero assertions there.
while IFS=$'\t' read -r where pkg owner; do
    if [ -n "$owner" ]; then
        ok "$where / $pkg is pinned by ui.phone_folders '$owner'"
    else
        bad "$where / $pkg is pinned by no folder — it would render as a dead 'Not installed' tile (add pkg:$pkg to ui.phone_folders)"
    fi
done < <(q '
  . as $root
  | ( [ $root.ui.phone_folders[] | . as $f | (.match_keywords // [])[]
        | select(startswith("pkg:")) | { key: .[4:], value: $f.id } ] | from_entries ) as $pins
  | $root.ui.sections[] | select(.id == "phone") | .phone_app_groups[]
  | select(.title == "Services · City" or .title == "Services · Leisure" or .title == "Primary")
  | .title as $group
  | ( (.packages[]? | { where: $group, entry: . }),
      ((.folders // [])[] | .label as $folder | .packages[] | { where: ($group + " > " + $folder), entry: . }) )
  | .where as $where
  | (if (.entry | type) == "string" then .entry else .entry.pkg end) as $pkg
  | select(([$root.ui.external_apps[].hub_package] | index($pkg)) | not)
  | [ $where, $pkg, ($pins[$pkg] // "") ] | @tsv')

echo "== Q6: miDNI is a quickmark of the Me group (Gov & ID subtype, #1756) =="
MIDNI="$(q "[$PHONE_GROUPS | .[] | select(.title == \"Me\") | (.packages[]?, (.folders[]?.packages[]?)) | if type == \"string\" then . else .pkg end | select(. == \"es.gob.interior.policia.midni\")] | length")"
[ "$MIDNI" = "1" ] \
    && ok "es.gob.interior.policia.midni is a Me quickmark" \
    || bad "es.gob.interior.policia.midni appears $MIDNI times in the quickmarks Me group, expected once"

echo "== Q7: the Nutrition folder is under Tools · Me in the ALL APPS taxonomy (#1756) =="
# The one part of the 2026-09-10 request that genuinely belongs on the other
# page: "Tools · Health" is a ui.phone_sections section and has no counterpart
# in quickmarks, so a Nutrition folder can only exist as a ui.phone_folders
# entry carrying that section's prefix.
HEALTH_PREFIX="$(q '[.ui.phone_sections[] | select(.title == "Tools · Me") | .prefix] | join("")')"
if [ -z "$HEALTH_PREFIX" ]; then
    bad "ui.phone_sections declares no 'Tools · Me' section to file a Nutrition folder under"
else
    ok "Tools · Me is declared on prefix '$HEALTH_PREFIX'"
    NUTRITION_LABEL="$(q '[.ui.phone_folders[] | select(.id == "hlth_nutrition") | .label] | join("")')"
    if [ "$NUTRITION_LABEL" = "${HEALTH_PREFIX}Nutrition" ]; then
        ok "hlth_nutrition is labelled '$NUTRITION_LABEL' — it files into Tools · Me"
    else
        bad "hlth_nutrition label is '${NUTRITION_LABEL:-ABSENT}', expected '${HEALTH_PREFIX}Nutrition' — a folder whose label loses the prefix belongs to no section"
    fi
    # A folder with no rules can never receive an app: renderAllApps skips
    # empty folders, so it would be a subhead over nothing.
    NUTRITION_RULES="$(q '[.ui.phone_folders[] | select(.id == "hlth_nutrition") | (.match_keywords // [])[]] | length')"
    [ "$NUTRITION_RULES" -ge 1 ] \
        && ok "hlth_nutrition carries $NUTRITION_RULES keyword(s), so apps can reach it" \
        || bad "hlth_nutrition carries no keywords — no app can ever land in it and renderAllApps would hide it"
fi

echo "== Q8: nothing re-sorts the groups at render time =="
# The position asserted in Q1 is only meaningful if the array order IS the
# screen order. A list alphabetised in Kotlin would make every JSON reorder
# invisible, and this repository has shipped that mistake before on this exact
# kind of task. SuitePhoneAppsFragment.parseGroups appends in document order
# and buildPage walks the result with a plain for-loop; a sort appearing on
# either is what this catches.
if grep -q 'for (group in groups)' "$FRAGMENT"; then
    ok "SuitePhoneAppsFragment iterates the parsed groups in declaration order"
else
    bad "SuitePhoneAppsFragment no longer contains 'for (group in groups)' — check how it orders groups now"
fi
# `.*` and not `[^\n]*`: grep is line-oriented, and inside a GNU-grep bracket
# expression `\n` is not a newline at all, it is the two characters backslash
# and n — so `[^\n]` would quietly mean "not a backslash and not the letter n".
SORTS="$(grep -nE '\bgroups\b.*\.(sorted|sortedBy|sortedWith|sortBy)\b' "$FRAGMENT" || true)"
if [ -z "$SORTS" ]; then
    ok "no sort is applied to the group list"
else
    bad "the group list is sorted at render time — the index asserted in Q1 would not be what the owner sees:"
    echo "$SORTS" | sed 's/^/        /'
fi

echo
echo "-- test-phone-projects-quickmark: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
