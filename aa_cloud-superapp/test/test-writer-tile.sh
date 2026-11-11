#!/usr/bin/env bash
# Tester: the Writer tile on Cloud ▸ Tools Primary.
#
# The owner asked for one thing — "cloud-sa/cloud/tools primary: add here
# 'Writer' the cloud-writer icon link" — and the ways a change like that
# reports green while giving him a dead tile are all known, because this
# repository has shipped every one of them:
#
#   • "build.json contains the string Writer" proves a string is in a file.
#     It cannot tell the Cloud page from the Phone page, and those two have
#     an identically-named "Tools Primary" grouping (see build.json's
#     _doc_samsung_camera, where two earlier attempts landed in the wrong
#     one). So T1 resolves the page the way Sections.parse does — section by
#     `id`, group by `title` — and asserts the tile is IN THAT GROUP.
#
#   • A package-name typo compiles, ships, and gives him a tile that opens
#     nothing. T3 never writes the package down: it reads it out of
#     ac_cloud-writer's OWN two declarations and asserts all four copies
#     agree. A tester that restated the string would pass on the typo.
#
#   • A tile whose extapp: id has no ui.external_apps entry snacks
#     "Unknown app" AFTER the tap. T4 pins the entry the not-installed path
#     needs; T1 of test-app-identity-resolves.sh enforces the same rule
#     fleet-wide and this states it for the tile the owner asked for.
#
# Every check fails closed: an empty jq result, a missing file and an
# unreadable field are all FAIL, never "nothing to compare so pass".
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"
WRITER="$ROOT/ac_cloud-writer"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }

# The renderer's own lookup, in jq. GroupedTilesFragment is handed
# Sections.Section.tileGroups — parsed from ui.sections[] selected by `id`,
# then tile_groups[] selected by `title` — and walks group.tiles in declared
# order with no sort. This is that, and nothing else: no search of the whole
# document, so a Writer tile anywhere ELSE in build.json cannot satisfy it.
GROUP='.ui.sections[] | select(.id == "cloud") | .tile_groups[]
       | select(.title == "Primary")'

echo "== T1: the Writer tile is in Cloud ▸ Primary (renamed from 'Tools Primary', #1627) =="
N="$(jq -r "[$GROUP] | length" "$BJ")"
eq "exactly one Cloud/Primary group" "$N" "1"

HIT="$(jq -r "[$GROUP | .tiles[] | select(.id == \"writer\")] | length" "$BJ")"
eq "one tile with id 'writer' in that group" "$HIT" "1"

LABEL="$(jq -r "$GROUP | .tiles[] | select(.id == \"writer\") | .label" "$BJ")"
eq "its label is the owner's word" "$LABEL" "Writer"

TARGET="$(jq -r "$GROUP | .tiles[] | select(.id == \"writer\") | .target" "$BJ")"
eq "its target hands off to the app" "$TARGET" "extapp:cloud-writer"

# The negative half of the section assertion: the Phone page's identically
# titled group must NOT have grown a Writer entry. Without this, a tile added
# to BOTH pages still passes every check above — and the Phone group is a
# `packages` list of quickmarks, where a Cloud tile does not belong.
PHONEHIT="$(jq -r '[.ui.sections[] | select(.id == "phone") | .phone_app_groups[]
                   | select(.title == "Tools Primary") | .packages[]
                   | tostring | select(test("cloudwriter|Writer"))] | length' "$BJ")"
eq "the PHONE page's Tools Primary was not touched" "$PHONEHIT" "0"

echo "== T2: the icon it names exists as a drawable =="
ICON="$(jq -r "$GROUP | .tiles[] | select(.id == \"writer\") | .icon" "$BJ")"
eq "tile names an icon" "$ICON" "ic_writer"
# Sections.iconResFor falls back to ic_link_tile IN SILENCE when a name
# resolves to nothing, so a misspelt or missing icon is invisible at runtime.
[ -f "$APP/app/src/main/res/drawable/$ICON.xml" ] \
  && ok "res/drawable/$ICON.xml exists" \
  || bad "res/drawable/$ICON.xml missing — iconResFor would silently fall back to ic_link_tile"

echo "== T3: the package is ac_cloud-writer's own, in all four places =="
# Read, never restate. Both of the app's own declarations, then both copies
# in the SuperApp. A tester that hardcoded the string would agree with a typo.
PKG_BJ="$(jq -r '.android.application_id' "$WRITER/build.json")"
PKG_GR="$(sed -n "s/^[[:space:]]*namespace[[:space:]]*'\\(.*\\)'[[:space:]]*$/\\1/p" \
          "$WRITER/app/build.gradle" | head -1)"
case "$PKG_BJ" in
  com.*) ok "ac_cloud-writer/build.json::android.application_id = $PKG_BJ" ;;
  *)     bad "could not read android.application_id from ac_cloud-writer/build.json (got '$PKG_BJ')" ;;
esac
eq "app/build.gradle::namespace agrees" "$PKG_GR" "$PKG_BJ"

EA_HUB="$(jq -r '.ui.external_apps[] | select(.id == "cloud-writer") | .hub_package' "$BJ")"
EA_INS="$(jq -r '.ui.external_apps[] | select(.id == "cloud-writer") | .install_package' "$BJ")"
FL_PKG="$(jq -r '.apps[] | select(.id == "writer") | .package' "$FLEET")"
eq "ui.external_apps.hub_package agrees"     "$EA_HUB" "$PKG_BJ"
eq "ui.external_apps.install_package agrees" "$EA_INS" "$PKG_BJ"
eq "constellation-fleet.json agrees"         "$FL_PKG" "$PKG_BJ"

echo "== T4: the not-installed path has something to install =="
# launchExternalApp: no candidate package launches → Updater.installApk with
# install_apk_url. Blank url or blank package → a bare "not installed" snack
# and no offer. Neither is ActivityNotFoundException (this path never builds
# an ACTION_VIEW intent), but a blank url is still a tile that does nothing.
EA_URL="$(jq -r '.ui.external_apps[] | select(.id == "cloud-writer") | .install_apk_url' "$BJ")"
case "$EA_URL" in
  https://*/Cloud-Writer.apk) ok "install_apk_url offers a real APK ($EA_URL)" ;;
  *)                          bad "install_apk_url is not a Cloud-Writer.apk URL: '$EA_URL'" ;;
esac
EA_FOLDER="$(jq -r '.ui.external_apps[] | select(.id == "cloud-writer") | .folder' "$BJ")"
FOLDER_OK="$(jq -r --arg f "$EA_FOLDER" '[.ui.phone_folders[] | select(.id == $f)] | length' "$BJ")"
eq "its folder is a declared phone_folder ($EA_FOLDER)" "$FOLDER_OK" "1"
# Identity and taxonomy are ONE record for our own apps: PhoneFolders derives
# the pkg: keyword from .folder, so a literal keyword here is a second owner.
DUP="$(jq -r --arg p "$PKG_BJ" '[.ui.phone_folders[].match_keywords // []
       | .[] | select(ascii_downcase == ("pkg:" + ($p | ascii_downcase)))] | length' "$BJ")"
eq "no ui.phone_folders keyword restates the package" "$DUP" "0"

echo "== T5: both ABI variants resolve, and the install path picks between them =="
A_ARM="$(jq -r '.apps[] | select(.id == "writer") | .assets["arm64-v8a"] // ""' "$FLEET")"
A_X86="$(jq -r '.apps[] | select(.id == "writer") | .assets["x86_64"]    // ""' "$FLEET")"
eq "fleet declares the arm64 asset"  "$A_ARM" "Cloud-Writer.apk"
eq "fleet declares the x86_64 asset" "$A_X86" "Cloud-Writer-x86_64.apk"
[ "$A_ARM" != "$A_X86" ] \
  && ok "the two variants are different files (a map that maps everything to one name is not an ABI map)" \
  || bad "arm64 and x86_64 name the same asset — #43's hardcoded-arm64 shape, wearing a map"

# ShellActivity must ROUTE the flat install_apk_url through that map. Grepped
# against the file with its comments STRIPPED, because the paragraph above
# this code in ShellActivity.kt names AbiUpdateTag, Fleet.parse and
# installApk in prose — a grep over the raw file would match its own
# explanation and pass on code that was deleted.
SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
[ -f "$SHELL_KT" ] || bad "ShellActivity.kt not found at $SHELL_KT"
# Drop whole-line comments (// …, /* …, * … continuation) and trailing // …
sed -e 's://[^"]*$::' "$SHELL_KT" \
  | grep -vE '^[[:space:]]*(//|/\*|\*)' > "$CODE"
codehas() {
  grep -qF "$1" "$CODE" && ok "$2" || bad "$2 (not in ShellActivity.kt, comments stripped)"
}
codehas 'AbiUpdateTag'        "install path selects with AbiUpdateTag, not a second ABI rule"
codehas '.currentFrom(f.assets, f.asset)' "…driven by the fleet entry's per-ABI assets map"
codehas 'this, app.installPackage, app.label, installUrl,' \
        "the install (ExtAppInstalls.start -> Updater.installApk) receives the RESOLVED url, not app.installApkUrl"
# Fail closed: the old flat call must be gone, or both exist and the wrong
# one could still be the live call.
grep -qE 'this, (app\.installApkUrl, app\.installPackage, app\.label|app\.installPackage, app\.label, app\.installApkUrl),' "$CODE" \
  && bad "the flat arm64 install call is still present" \
  || ok "the flat arm64 install call is gone"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
