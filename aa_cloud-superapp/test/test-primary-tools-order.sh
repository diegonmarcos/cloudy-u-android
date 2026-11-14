#!/usr/bin/env bash
# Tester (#1155, relabelled #1627): Cloud > Apps > Primary (renamed from 'Tools Primary') reads, in this order
# with dividers, Drive, Browser | Calc, Navigation, Camera, Keyboard, Writer || Wallet - declared in build.json
# (array position is render position; '|' = single separator tile, '||' = double separator tile). #1627 moved
# Drive in (from Data) and Vault out (to Data); the single '|' sits after Browser, the DOUBLE '||' after Writer.
# Light left this row for Cloud Calc > Toolkit; libs:light stays (the home circle gesture toggles it, its page and service stay in the manifest).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
GROUP='.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Primary")'
SRC="$APP/app/src/main"

echo "== T0: the group is renamed to 'Primary' and the old title is gone (#1627) =="
eq "exactly one Cloud group titled Primary" "$(jq -r '[.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Primary")] | length' "$BJ")" "1"
eq "no Cloud group is still titled 'Tools Primary'" "$(jq -r '[.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Tools Primary")] | length' "$BJ")" "0"

echo "== T1: the full row (tiles + dividers), in the owner's order =="
GOT="$(jq -r "$GROUP | [.tiles[].label] | join(\",\")" "$BJ")"
eq "full tile row incl. dividers" "$GOT" "Drive,Browser,|,Calc,Navigation,Camera,Keyboard,||,Wallet"
APPS="$(jq -r "$GROUP | [.tiles[] | select(.separator != true) | .label] | join(\",\")" "$BJ")"
eq "the seven tools, dividers removed" "$APPS" "Drive,Browser,Calc,Navigation,Camera,Keyboard,Wallet"
eq "single '|' divider sits after Browser" "$(jq -r "$GROUP | ([.tiles[].label] | index(\"|\")) as \$i | \"\(.tiles[\$i-1].label)>\(.tiles[\$i].separator)\"" "$BJ")" "Browser>true"
eq "DOUBLE '||' divider sits after Keyboard, double:true" "$(jq -r "$GROUP | ([.tiles[].label] | index(\"||\")) as \$i | \"\(.tiles[\$i-1].label)>\(.tiles[\$i].double)\"" "$BJ")" "Keyboard>true"
eq "exactly one Wallet tile, still an app hand-off" "$(jq -r "$GROUP | [.tiles[] | select(.label == \"Wallet\") | .target] | join(\",\")" "$BJ")" "extapp:cloud-wallet"
eq "Drive leads the row, launching the Drive APK (moved from Data, #1627)" "$(jq -r "$GROUP | \"\(.tiles[0].label)>\(.tiles[0].target)\"" "$BJ")" "Drive>extapp:cloud-drive"
eq "Vault left this row for Data (#1627)" "$(jq -r "$GROUP | [.tiles[] | select(.label == \"Vault\")] | length" "$BJ")" "0"
[ -f "$ROOT/ac_cloud-wallet/build.json" ] && ok "the Wallet app exists in the fleet (ac_cloud-wallet)" || bad "ac_cloud-wallet is gone: Wallet needs a documented unavailable state"

echo "== T2: the order is declared, not sorted by the renderer =="
FRAG="$SRC/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt"
if grep -nE 'group\.tiles[^\n]*(sort|reorder|shuffle)|(sort|reorder)[A-Za-z]*\([^)]*group\.tiles' "$FRAG" >/dev/null; then bad "GroupedTilesFragment sorts group.tiles"; else ok "GroupedTilesFragment does not sort group.tiles"; fi

echo "== T3: the Light tile is gone, the shared engine is not =="
eq "no Light tile in the group" "$(jq -r "$GROUP | [.tiles[] | select(.id == \"light\" or .target == \"action:light\")] | length" "$BJ")" "0"
grep -q 'actionType == "light"' "$SRC/java/com/diegonmarcos/superapp/ShellActivity.kt" && bad "action:light is still routed" || ok "action:light has no route (no tile uses it)"
grep -q '"toggle_light" -> com.diegonmarcos.superapp.light.Light.engine(this).quickTap()' "$SRC/java/com/diegonmarcos/superapp/ShellActivity.kt" \
  && ok "the circle gesture still toggles libs:light" || bad "toggle_light is not routed to libs:light"
grep -q 'android:name=".light.LightActivity"' "$SRC/AndroidManifest.xml" && grep -q 'light.LightService' "$ROOT/ab_cloud-libs-shared/libs/light/src/main/AndroidManifest.xml" "$SRC/AndroidManifest.xml" \
  && ok "Light page and service are in the manifest" || bad "LightActivity/LightService missing from the manifest"
grep -q 'FOREGROUND_SERVICE_SPECIAL_USE' "$SRC/AndroidManifest.xml" && ok "foreground-service permission held" || bad "FOREGROUND_SERVICE_SPECIAL_USE missing"

echo "== T4: persistence store declared in fleet-config.json =="
FC="$ROOT/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
eq "light_prefs class" "$(jq -r '.stores.light_prefs.class // ""' "$FC")" "config"
# the store is the lib-light module's (generated used_by names the owner of the file, not the app that embeds the lib)
eq "light_prefs used_by" "$(jq -r '.stores.light_prefs.used_by | join(",")' "$FC")" "lib-light"
grep -q 'getSharedPreferences("light_prefs"' "$ROOT/ab_cloud-libs-shared/libs/light/src/main/java/com/diegonmarcos/superapp/light/LightAndroid.kt" && ok "code opens the declared file" || bad "code does not open light_prefs"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
