#!/usr/bin/env bash
# Tester (#772): the Calc tile on Cloud ▸ Tools Primary, immediately before
# Writer. Resolves the group the way Sections.parse does (section by id, group
# by title) so the identically-named Phone grouping can never satisfy it, and
# reads the package from ac_cloud-calc's own declarations instead of restating
# it. Fails closed on any empty read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"
CALC="$ROOT/ac_cloud-calc"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }

GROUP='.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Primary")'

echo "== T1: Calc sits in Cloud ▸ Primary (renamed from 'Tools Primary', #1627), after the '|' and before Navigation =="
eq "one tile with id 'calc' in that group" "$(jq -r "[$GROUP | .tiles[] | select(.id == \"calc\")] | length" "$BJ")" "1"
IDS="$(jq -r "[$GROUP | .tiles[].id] | join(\",\")" "$BJ")"
NEXT="$(jq -r "[$GROUP | .tiles[].id] as \$t | (\$t | index(\"calc\")) as \$i | if \$i == null then \"\" else \$t[\$i+1] end" "$BJ")"
eq "the tile right after calc is maps/Navigation (#1627 order: $IDS)" "$NEXT" "maps"
eq "its target hands off to the app" "$(jq -r "$GROUP | .tiles[] | select(.id == \"calc\") | .target" "$BJ")" "extapp:cloud-calc"
eq "the PHONE page's Tools Primary was not touched" \
   "$(jq -r '[.ui.sections[] | select(.id == "phone") | .phone_app_groups[] | select(.title == "Tools Primary") | .packages[] | tostring | select(test("cloudcalc"))] | length' "$BJ")" "0"

echo "== T2: the icon exists =="
ICON="$(jq -r "$GROUP | .tiles[] | select(.id == \"calc\") | .icon" "$BJ")"
eq "tile names ic_calc" "$ICON" "ic_calc"
[ -f "$APP/app/src/main/res/drawable/$ICON.xml" ] && ok "res/drawable/$ICON.xml exists" || bad "res/drawable/$ICON.xml missing"

echo "== T3: one package in every copy, read from ac_cloud-calc =="
PKG="$(jq -r '.android.application_id' "$CALC/build.json")"
case "$PKG" in com.*) ok "application_id = $PKG" ;; *) bad "no application_id in ac_cloud-calc/build.json ('$PKG')" ;; esac
eq "app/build.gradle::namespace agrees" \
   "$(sed -n "s/^[[:space:]]*namespace[[:space:]]*'\\(.*\\)'[[:space:]]*$/\\1/p" "$CALC/app/build.gradle" | head -1)" "$PKG"
eq "external_apps.hub_package agrees"     "$(jq -r '.ui.external_apps[] | select(.id == "cloud-calc") | .hub_package' "$BJ")" "$PKG"
eq "external_apps.install_package agrees" "$(jq -r '.ui.external_apps[] | select(.id == "cloud-calc") | .install_package' "$BJ")" "$PKG"
eq "constellation-fleet.json agrees"      "$(jq -r '.apps[] | select(.id == "calc") | .package' "$FLEET")" "$PKG"

echo "== T4: the not-installed path installs the fleet's asset =="
URL="$(jq -r '.ui.external_apps[] | select(.id == "cloud-calc") | .install_apk_url' "$BJ")"
ASSET="$(jq -r '.apps[] | select(.id == "calc") | .assets["arm64-v8a"] // ""' "$FLEET")"
case "$ASSET" in ?*.apk) ok "fleet arm64 asset $ASSET" ;; *) bad "fleet has no arm64 asset for calc" ;; esac
case "$URL" in https://*/"$ASSET") ok "install_apk_url names that asset" ;; *) bad "install_apk_url '$URL' is not .../$ASSET" ;; esac
FOLDER="$(jq -r '.ui.external_apps[] | select(.id == "cloud-calc") | .folder' "$BJ")"
eq "its folder is a declared phone_folder ($FOLDER)" "$(jq -r --arg f "$FOLDER" '[.ui.phone_folders[] | select(.id == $f)] | length' "$BJ")" "1"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
