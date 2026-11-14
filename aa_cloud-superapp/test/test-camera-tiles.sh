#!/usr/bin/env bash
# Tester: the two camera entries the owner asked for on 2026-09-10 land on the
# two DIFFERENT pages he named, and the Cloud one keeps the position he named.
#
#   "cloud-sa/cloud/apps/tools primary: as last Camera (the Cloud-camera);
#    phone/apps/Tools Primary: add here the Samsung Camera App"
#
# THE FAILURE THIS EXISTS FOR is that both pages contain a grouping called
# "Tools · Primary" and they are backed by unrelated structures:
#
#   Cloud ▸ Apps ▸ Tools Primary   ui.sections[id=cloud].tile_groups[title=
#                                  "Tools Primary"].tiles — the constellation's
#                                  OWN apps, each an extapp:<id> into
#                                  ui.external_apps.
#   Phone ▸ Apps ▸ Tools · Primary ui.phone_sections[prefix="="] — a SECTION,
#                                  whose members are the ui.phone_folders whose
#                                  label starts with "=", populated by
#                                  `pkg:` keywords over INSTALLED THIRD-PARTY
#                                  packages.
#
# Put the Samsung package on the first and the Cloud Camera on the second and
# every file still parses, every other tester still passes, and only the owner's
# eye on the device finds out. So each assertion below names the structure it
# reads, and the two package identifiers are checked against each other's
# absence as much as their own presence.
#
# ── THE HEADER ABOVE WAS ITSELF THE BUG, AND T9 ONWARDS IS THE CORRECTION ──
# "phone/apps/Tools Primary" is read above as ui.phone_sections[prefix="="],
# the ALL-APPS taxonomy. That is a THIRD structure whose grouping is also
# called Tools · Primary, and it is not what the owner asked to add to — he
# capitalised QUICKMARK the second time round. The page he means is
#
#   Phone ▸ Apps ▸ Quickmarks ▸ Tools Primary
#       ui.sections[id=phone].phone_app_groups[title="Tools Primary"].packages
#       — a HAND-WRITTEN shortlist, baked into
#       BuildConfig.UI_SUITE_PHONE_GROUPS_B64 and rendered by
#       SuitePhoneAppsFragment. No keyword, no classifier, no folder.
#
# So there are THREE same-named groupings, not two, and the taxonomy assertions
# T5/T6 pass with the camera absent from the curated list entirely — which is
# exactly what shipped. T5/T6 stay: they pin the All-apps classification so it
# cannot drift in silence. T9 onwards adds the surface nobody was testing, and
# T11 is the assertion the previous two attempts were missing — it re-runs T9's
# own predicate against a document with ONLY the Quickmark deleted and requires
# it to answer "absent" while the package is still written elsewhere in that
# same document. An assertion that cannot distinguish the three pages is
# decoration, and this is the check that refuses to let it be one.
#
# THE SECOND FAILURE is "as last". Nothing in this row carries an `order`:
# GroupedTilesFragment walks group.tiles in declared order and applies no sort,
# so LAST IS ARRAY POSITION and a presence-only assertion would pass while the
# instruction was broken. T1 reads the last element and nothing else.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
REPO="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
CAMERA_BJ="$REPO/ac_cloud-camera/build.json"
CAMERA_GRADLE="$REPO/ac_cloud-camera/app/build.gradle.kts"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# Fail CLOSED. A missing jq does not fail an assertion, it makes every jq
# expression answer "" and every "is this absent" check agree enthusiastically —
# which is how four testers in this directory passed for a week on a runner with
# no ripgrep. Same for the two files in the sibling application directory: this
# tester's whole claim about cld.camera is that it was read from THEM.
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$BJ" "$CAMERA_BJ" "$CAMERA_GRADLE"; do
  [ -r "$f" ] || { echo "ERROR: cannot read $f" >&2; exit 2; }
done

# Lines where a rendered list gets reordered. Two assertions depend on this —
# T2 for the Cloud row and T12 for the Quickmarks grid — and both stated their
# own pattern until it was demonstrated not to work.
#
# THE PATTERN WAS `<token>[^)]*\.(sorted|…)` AND IT COULD NOT SEE A SORT. A
# character class excluding ')' cannot cross the ')' of `.map(::curated)`, so
# the plainest possible way to introduce one —
#     val packageTiles = group.entries.map(::curated).sortedBy { it.label }
# — was planted in SuitePhoneAppsFragment and the assertion reported PASS. It
# was a guard against nothing, exactly the shape the vacuous-assertion lint
# exists to catch and narrow enough that the lint did not.
#
# Comment lines are dropped because BOTH fragments explain the no-sort rule in
# prose, and a guard that reads its own documentation as a violation fails on
# the sentence describing the fix.
reordering_lines() {  # reordering_lines <kotlin-file> <token-alternation>
  grep -nE "($2)\b.*\.(sorted|sortBy|sortedWith|reversed)" "$1" \
    | awk '{ body = $0; sub(/^[0-9]+:[[:space:]]*/, "", body); if (body !~ /^(\/\/|\*|\/\*)/) print }'
}

CLOUD_TILE_TARGET="extapp:cloud-camera"
SAMSUNG_PKG="com.sec.android.app.camera"
OPUS_PKG="pl.mobimax.cameraopus"

echo "== T1: Camera sits at the owner's declared place in Cloud ▸ Apps ▸ Primary (renamed from 'Tools Primary', #1627) =="
# Read the whole row in array order, not a membership test: array position IS
# render position, so the order in build.json is the order on the screen, and a
# future `sort_by` in GroupedTilesFragment would show up here as well as in T2.
# This pinned Camera as the LAST element until the owner named the full order on
# 2026-09-13 — Me, Wallet, Keyboard, Writer, Browser, Navigation, Camera, C3 —
# which moves Camera to seventh of eight and puts C3 behind it. The requirement
# never was "Camera is last"; it was "Camera is where he put it", and the
# stronger check is to pin every neighbour rather than only the tail.
# #324 then lifted C3 out of this row into the new "Configs" group, so Camera is
# the tail again — by subtraction this time, not by decree. Pinning the whole row
# is what caught that move, which is the point of pinning the whole row.
# Light then left the row for Cloud Calc > Toolkit (#1200).
# #1155: the owner named the nine primary tools in a new order (test-primary-tools-order.sh);
# Account, the intentional duplicate, left this row for the Me group (test-me-account-tile.sh).
# #1627 renamed the group to 'Primary' and set the order Drive, Browser | Calc, Navigation, Camera, Keyboard,
# Writer || Wallet ('|' single separator, '||' double separator); Drive moved in from Data, Vault out to Data.
WANT_ROW="Drive,Browser,|,Calc,Navigation,Camera,Keyboard,||,Wallet"
GOT_ROW="$(jq -r '
  .ui.sections[] | select(.id == "cloud")
  | .tile_groups[] | select(.title == "Primary")
  | [ .tiles[].label ] | join(",")' "$BJ")"
CAM_TARGET="$(jq -r '
  .ui.sections[] | select(.id == "cloud")
  | .tile_groups[] | select(.title == "Primary")
  | .tiles[] | select(.label == "Camera") | .target // ""' "$BJ")"
[ "$GOT_ROW" = "$WANT_ROW" ] && [ "$CAM_TARGET" = "$CLOUD_TILE_TARGET" ] \
  && ok "the row reads $GOT_ROW, with Camera → $CAM_TARGET" \
  || bad "Cloud Primary reads '$GOT_ROW' with Camera → '$CAM_TARGET', expected '$WANT_ROW' with Camera → $CLOUD_TILE_TARGET (array position is render position)"

echo "== T2: the row is rendered in DECLARED order, with no sort =="
# The assertion T1 depends on. If the renderer ever sorts, "last in the array"
# stops meaning "last on the page" and T1 becomes decoration that passes.
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt"
[ -r "$FRAG" ] || { echo "ERROR: cannot read $FRAG" >&2; exit 2; }
SORTS="$(reordering_lines "$FRAG" 'group\.tiles')"
[ -z "$SORTS" ] \
  && ok "GroupedTilesFragment renders group.tiles unsorted, so array position IS render position" \
  || bad "GroupedTilesFragment now reorders group.tiles — 'as last' can no longer be expressed by appending: $SORTS"

echo "== T3: the Cloud tile points at OUR camera, and the id is not written twice =="
# extapp:<id> is an indirection on purpose — the package lives in
# ui.external_apps and nowhere else. Three independent sources have to agree,
# and none of them is this tester's own opinion of what the package is:
#   the SuperApp's external_apps entry, ac_cloud-camera/build.json, and the
#   Gradle file the APK is actually built from.
HUB="$(jq -r '.ui.external_apps[] | select(.id == "cloud-camera") | .hub_package // ""' "$BJ")"
DECLARED="$(jq -r '.android.application_id // ""' "$CAMERA_BJ")"
# awk, not sed: the field wanted is the quoted value, and asking for the field
# is clearer than describing the characters around it.
BUILT="$(awk -F'"' '/^[[:space:]]*applicationId[[:space:]]*=/ { print $2; exit }' "$CAMERA_GRADLE")"
if [ -z "$HUB" ]; then
  bad "no ui.external_apps entry with id 'cloud-camera' — extapp:cloud-camera resolves to nothing and the tile can neither open nor offer an app"
elif [ -z "$DECLARED" ] || [ -z "$BUILT" ]; then
  bad "could not read the camera application id (build.json='$DECLARED', gradle='$BUILT') — the check that the tile names the right app did not run"
elif [ "$HUB" = "$DECLARED" ] && [ "$HUB" = "$BUILT" ]; then
  ok "extapp:cloud-camera → $HUB, matching ac_cloud-camera/build.json ($DECLARED) and app/build.gradle.kts ($BUILT)"
else
  bad "the Cloud tile would open '$HUB' but ac_cloud-camera builds '$BUILT' (declared '$DECLARED') — the tile names an app nobody publishes"
fi

echo "== T4: the Camera tile's icon resolves to a drawable that exists =="
# Sections.iconResFor falls back to ic_link_tile in silence when a name resolves
# to nothing, so a typo is a wrong icon and never an error. Reuse of an icon
# another tile already carries is the same defect the other way round.
ICON="$(jq -r '
  .ui.sections[] | select(.id == "cloud")
  | .tile_groups[] | select(.title == "Primary")
  | .tiles[] | select(.target == "extapp:cloud-camera") | .icon // ""' "$BJ")"
if [ -n "$ICON" ] && [ -f "$APP/app/src/main/res/drawable/$ICON.xml" ]; then
  USERS="$(jq -r --arg i "$ICON" '[ .. | objects | select((.icon // "") == $i) ] | length' "$BJ")"
  [ "$USERS" = "1" ] \
    && ok "icon '$ICON' exists as res/drawable/$ICON.xml and is used by this tile alone" \
    || bad "icon '$ICON' is declared by $USERS entries — two tiles drawing one glyph read as deliberate on a phone"
else
  bad "icon '$ICON' has no res/drawable/$ICON.xml — the tile silently falls back to ic_link_tile"
fi

echo "== T5: the Samsung camera lands in a folder of the Phone section 'Tools · Primary' =="
# THE KEYWORD PASS OF PhoneAppClassifier.classify, in jq: folders sorted by
# `order`, first matching keyword wins, and our own apps contribute the `pkg:`
# keyword ui.external_apps[].folder derives. Membership in one folder's list is
# NOT the assertion — a folder earlier in `order` claiming the package with a
# broader rule is exactly the failure that makes a correct-looking diff wrong on
# the device, and only running the pass can see it.
CLASSIFY='
def folders_merged:
  . as $root
  | [ $root.ui.phone_folders[]
      | . as $folder
      | .match_keywords = ( (.match_keywords // [])
          + [ $root.ui.external_apps[]
              | select(.folder == $folder.id)
              | ( .hub_package, .alt_package, .install_package,
                  ((.forks // {}) | to_entries[] | .value) )
              | select(. != null and . != "")
              | "pkg:" + . ] ) ];
def fold($pkg):
  ($pkg | ascii_downcase) as $p
  | ( [ folders_merged[] | select((.match_keywords // []) | length > 0) ]
      | sort_by(.order) ) as $folders
  | ( first( $folders[]
      | select( any( .match_keywords[];
            ascii_downcase as $k
            | if   ($k | startswith("pkg:")) then $p == $k[4:]
              elif ($k | startswith("pkg^")) then ($p | startswith($k[4:]))
              elif ($k | startswith("lbl:")) or ($k | startswith("lbl~")) then false
              else (($k | length) >= 4 and ($p | contains($k)))
              end ) ) ) // null );
'
# The prefix is DERIVED from ui.phone_sections, never spelled "=" here: the
# section characters are data, and a tester that hardcodes one stops describing
# the file the moment somebody renumbers them.
PRIMARY_PREFIX="$(jq -r '.ui.phone_sections[] | select(.title == "Tools · Primary") | .prefix' "$BJ")"
[ -n "$PRIMARY_PREFIX" ] \
  && ok "Phone section 'Tools · Primary' declares prefix '$PRIMARY_PREFIX'" \
  || bad "no ui.phone_sections entry titled 'Tools · Primary' — the owner's target section does not exist under that name"

while IFS=$'\t' read -r pkg folder label; do
  case "$label" in
    "$PRIMARY_PREFIX"*) ok "$pkg classifies into '$label' ($folder), a folder of Tools · Primary" ;;
    "") bad "$pkg is claimed by no folder at all — it falls to the sink and renders under Other" ;;
    *)  bad "$pkg classifies into '$label' ($folder), which is not in Tools · Primary (prefix '$PRIMARY_PREFIX')" ;;
  esac
done < <(jq -r "$CLASSIFY"'
  . as $root
  | $pkg_in as $pkg
  | ($root | fold($pkg)) as $f
  | [ $pkg, ($f.id // ""), ($f.label // "") ] | @tsv' --arg pkg_in "$SAMSUNG_PKG" "$BJ")

echo "== T6: the Samsung camera left its old folder, and the other cameras did not move =="
# "add here" is a MOVE: a package written into two folders does not render
# twice, it renders in the earlier one, so the diff reads as done and the app
# never arrives. The general no-package-in-two-folders assertion already lives
# in test-phone-taxonomy-prefixes.sh T2 and is not repeated; this is the
# narrower fact that the OLD home specifically let go.
OLD="$(jq -r --arg p "pkg:$SAMSUNG_PKG" '
  [ .ui.phone_folders[] | select((.match_keywords // []) | index($p)) | .id ] | join(",")' "$BJ")"
[ "$OLD" = "prod_utils" ] \
  && ok "pkg:$SAMSUNG_PKG is declared by exactly one folder, prod_utils" \
  || bad "pkg:$SAMSUNG_PKG is declared by '$OLD' — it must be exactly prod_utils, or the move is a copy"

OPUS="$(jq -r --arg p "pkg:$OPUS_PKG" '
  [ .ui.phone_folders[] | select((.match_keywords // []) | index($p)) | .id ] | join(",")' "$BJ")"
[ "$OPUS" = "prod_storage" ] \
  && ok "$OPUS_PKG (Camera Opus, third-party) stayed in prod_storage" \
  || bad "$OPUS_PKG moved to '$OPUS' — it is not part of this change and belongs in prod_storage"

echo "== T7: neither camera appears on the other one's page =="
# The single assertion that would have caught the whole task being done
# backwards. The Cloud row carries constellation ids, never raw packages; the
# phone taxonomy carries third-party packages, and cld.camera is classified
# through ui.external_apps rather than a keyword of its own.
STRAY_CLOUD="$(jq -r --arg p "$SAMSUNG_PKG" '
  [ .ui.sections[] | select(.id == "cloud") | .tile_groups[].tiles[]
    | select((.target // "") | contains($p)) | .label ] | join(",")' "$BJ")"
[ -z "$STRAY_CLOUD" ] \
  && ok "the Samsung package appears on no Cloud ▸ Apps tile" \
  || bad "the Samsung stock camera is targeted by Cloud tile(s) '$STRAY_CLOUD' — that page lists the fleet's own apps"

STRAY_PHONE="$(jq -r --arg h "$HUB" '
  [ .ui.phone_folders[] | . as $f | (.match_keywords // [])[]
    | select(ascii_downcase == ("pkg:" + ($h | ascii_downcase))) | $f.id ] | join(",")' "$BJ")"
[ -z "$STRAY_PHONE" ] \
  && ok "$HUB carries no ui.phone_folders keyword — its folder comes from ui.external_apps alone" \
  || bad "ui.phone_folders '$STRAY_PHONE' restates pkg:$HUB, a package ui.external_apps already owns — two copies of one classification, free to disagree"

echo "== T8: both package identifiers are well-formed =="
# After the placeholder change, an unresolved curated entry is a VISIBLE
# install-me tile rather than a silent omission, so a mistyped identifier is a
# permanent dead tile offering an app that does not exist. Two or more
# dot-separated segments, each starting with a letter.
for pkg in "$SAMSUNG_PKG" "$HUB"; do
  printf '%s' "$pkg" | grep -qE '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$' \
    && ok "'$pkg' is a well-formed Android package id" \
    || bad "'$pkg' is not a well-formed Android package id"
done

echo "== T9: the Samsung camera is a QUICKMARK of Phone ▸ Apps ▸ Tools Primary =="
# The surface the owner actually named. Located BY TITLE and never by index:
# ui.sections[id=phone].phone_app_groups is under concurrent edit — a Projects
# group is being inserted between Tools Primary and Configs — so any index
# written here would be describing yesterday's file. app/build.gradle finds the
# section the same way (`sections.find { it.id == "phone" }`), so this reads the
# document along the path the APK is actually baked from.
QUICKMARK_GROUP="Tools Primary"
# An entry is either a bare package string or {"pkg","label"} — parseEntries()
# in SuitePhoneAppsFragment accepts both, so both are unwrapped here rather
# than assuming the shape this group happens to use today.
QUICKMARK_PKGS='
  .ui.sections[] | select(.id == "phone")
  | .phone_app_groups[] | select(.title == $t)
  | .packages[] | if type == "object" then (.pkg // "") else . end'

TITLED="$(jq -r --arg t "$QUICKMARK_GROUP" '
  [ .ui.sections[] | select(.id == "phone")
    | .phone_app_groups[] | select(.title == $t) ] | length' "$BJ")"
[ "$TITLED" = "1" ] \
  && ok "exactly one Quickmarks group is titled '$QUICKMARK_GROUP', so finding it by title is unambiguous" \
  || bad "$TITLED Quickmarks groups are titled '$QUICKMARK_GROUP' — every assertion below reads whichever one jq returns first"

CURATED="$(jq -r --arg t "$QUICKMARK_GROUP" "[ $QUICKMARK_PKGS ] | join(\",\")" "$BJ")"
case ",$CURATED," in
  *",$SAMSUNG_PKG,"*)
    ok "$SAMSUNG_PKG is a curated entry of ui.sections[id=phone].phone_app_groups[title=$QUICKMARK_GROUP]" ;;
  *)
    bad "$SAMSUNG_PKG is NOT in the Quickmarks group '$QUICKMARK_GROUP' (it holds: $CURATED) — the owner's page renders without it no matter what the All-apps taxonomy says" ;;
esac

echo "== T10: adding the camera dropped none of the packages already curated =="
# A superset check, not an equality one, and deliberately: miDNI is being added
# to this same list by another change in flight, so pinning the exact contents
# would fail on somebody else's correct work. What must not happen is a
# rewrite that loses an entry the owner put there.
for pkg in com.brave.browser \
           com.google.android.apps.maps \
           com.google.android.apps.walletnfcrel \
           com.google.android.apps.translate \
           com.sec.android.app.clockpackage \
           com.sec.android.app.popupcalculator; do
  case ",$CURATED," in
    *",$pkg,"*) ok "$pkg is still curated in '$QUICKMARK_GROUP'" ;;
    *)          bad "$pkg has left the Quickmarks group '$QUICKMARK_GROUP' — it was there before the camera was added and nothing asked for its removal" ;;
  esac
done

echo "== T11: T9 reads the QUICKMARK, and is not satisfied by the other two pages =="
# THE ASSERTION BOTH PREVIOUS ATTEMPTS LACKED. com.sec.android.app.camera is
# written in three places in this file — the curated list, a ui.phone_folders
# keyword, and the ui.camera_apps intent-fallback registry — so "the string is
# in build.json" is true for all three surfaces and proves none of them. This
# deletes ONLY the curated entry, leaves the document otherwise untouched, and
# requires T9's own predicate to go absent against it. Both halves must hold:
# if the decoys were gone the mutation would prove nothing, so their survival
# is asserted too rather than assumed.
MUTATED="$(jq --arg t "$QUICKMARK_GROUP" --arg p "$SAMSUNG_PKG" '
  ( .ui.sections[] | select(.id == "phone")
    | .phone_app_groups[] | select(.title == $t) | .packages )
  |= map(select((if type == "object" then (.pkg // "") else . end) != $p))' "$BJ")"

DECOYS="$(printf '%s' "$MUTATED" | jq -r --arg p "$SAMSUNG_PKG" '
  ( [ .ui.phone_folders[] | (.match_keywords // [])[] | select(. == "pkg:" + $p) ] | length )
  + ( [ .ui.camera_apps[]? | select((.package // "") == $p) ] | length )')"
STILL_CURATED="$(printf '%s' "$MUTATED" | jq -r --arg t "$QUICKMARK_GROUP" "[ $QUICKMARK_PKGS ] | join(\",\")")"

if [ -z "$DECOYS" ] || [ "$DECOYS" -lt 1 ]; then
  bad "with the Quickmark deleted, $SAMSUNG_PKG survives nowhere else in the document — the wrong-surface trap this check exists to prove cannot be reproduced, so T9 is unproven"
else
  case ",$STILL_CURATED," in
    *",$SAMSUNG_PKG,"*)
      bad "T9's predicate STILL finds $SAMSUNG_PKG after the curated entry was deleted — it is reading something other than the Quickmarks group and would pass with the owner's page empty" ;;
    *)
      ok "deleting the curated entry makes T9 answer absent while $DECOYS other declaration(s) of $SAMSUNG_PKG remain — T9 is scoped to the Quickmark and not to the file" ;;
  esac
fi

echo "== T12: the curated list is rendered in DECLARED order, with no sort =="
# The same dependency T2 covers for the Cloud row. Appending is only a
# placement claim while SuitePhoneAppsFragment maps group.entries in order and
# chunks the result; a sort anywhere on that path makes array position
# cosmetic, and then no diff to this file can put a tile where the owner wants
# it.
SUITE_FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
[ -r "$SUITE_FRAG" ] || { echo "ERROR: cannot read $SUITE_FRAG" >&2; exit 2; }
QM_SORTS="$(reordering_lines "$SUITE_FRAG" 'group\.entries|packageTiles|folderTiles')"
[ -z "$QM_SORTS" ] \
  && ok "SuitePhoneAppsFragment renders the curated entries unsorted, so array position IS render position" \
  || bad "SuitePhoneAppsFragment now reorders the curated entries — where a Quickmark sits in build.json no longer decides where it draws: $QM_SORTS"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
