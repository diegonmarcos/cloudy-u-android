#!/usr/bin/env bash
# The Store's Fleet Data tab is a composable PANE in the Store page (like Ventoy), not a launched Activity.
# T1 StoreCloudFragment draws the host's fleetDataPane in the page and never startActivity()s FleetDataActivity.
# T2 FleetDataPage is an embeddable composable taking the host's modifier; only the Activity (standalone host) owns a theme.
# T3 The Store app wires the pane to FleetDataPage; the Activity stays for Configs' "Open the data browser".
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FRAG="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
PAGE="$ROOT/ab_cloud-libs-shared/libs/fleet-data-ui/src/main/java/com/diegonmarcos/superapp/fleetdata/ui/FleetDataPage.kt"
MAIN="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt"
CFG="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt"
F=0; ok() { echo "  ok: $1"; }; bad() { echo "  FAIL: $1"; F=$((F+1)); }
for f in "$FRAG" "$PAGE" "$MAIN" "$CFG"; do [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }; done
echo "== T1: KIND_FLEETDATA renders inline =="
grep -qF 'KIND_FLEETDATA -> { ventoyOpen = false; fleetOpen = true }' "$FRAG" && ok "tap opens the in-page pane" || bad "KIND_FLEETDATA does not open the pane"
grep -qF 'pane(STORE_AUTHORITY, Modifier.weight(1f)' "$FRAG" && ok "the pane is drawn in the Store page" || bad "the pane is not drawn in the page"
grep -nE 'FleetDataActivity|openFleetData' "$FRAG" && bad "StoreCloudFragment launches the Activity" || ok "no Activity launch in the fragment"
echo "== T2: the page is an embeddable composable =="
grep -qE '^fun FleetDataPage\(authority: String, modifier: Modifier = Modifier\)' "$PAGE" && ok "FleetDataPage(authority, modifier)" || bad "FleetDataPage signature changed"
body="$(sed -n '/^fun FleetDataPage/,/^}/p' "$PAGE")"
echo "$body" | grep -qE 'CloudKitTheme|Scaffold|statusBars|systemBars|WindowInsets|safeDrawing' && bad "the composable owns a theme/scaffold/inset" || ok "no own theme, scaffold or inset in the composable"
grep -qF 'CloudKitTheme(palette) { Box(' "$PAGE" && ok "the Activity (standalone host) supplies the theme" || bad "the Activity no longer hosts the composable"
echo "== T3: hosts =="
grep -qF 'StorePages.fleetDataPane = ' "$MAIN" && grep -qF 'FleetDataPage(authority, modifier)' "$MAIN" && ok "Store app wires the pane" || bad "Store app does not wire fleetDataPane"
grep -qF 'FleetDataActivity.open(' "$CFG" && ok "Configs still opens the Activity host" || bad "Configs lost its data browser"
echo "== RESULT: $F failed =="
[ "$F" = 0 ]
