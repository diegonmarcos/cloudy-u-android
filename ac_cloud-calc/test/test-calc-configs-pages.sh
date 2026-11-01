#!/usr/bin/env bash
# #1406 Cloud Calc ▸ Configs carries the fleet's General | Permissions | About, the Wi-Fi tool's grants are declared,
# and its "Open Permissions" lands on Configs ▸ Permissions (MainActivity is the PermsHost).
#   P1 the configs tab's first three modes are General, Permissions, About, rendered by ModeScreen with the shared pages.
#   P2 perms.needs declares fine_location and nearby_wifi (API 33+) with a perms_why; configs_pages carries general + about.
#   P3 MainActivity implements PermsHost and openPermsPage shows the Permissions mode; the Wi-Fi card has an inline Grant.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP="$ROOT/ac_cloud-calc"; SRC="$APP/app/src/main/java/com/diegonmarcos/cloudcalc"
WIFI="$ROOT/ab_cloud-libs-shared/libs/wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiUi.kt"
fail=0; ok() { echo "  ok  $1"; }; bad() { echo "  FAIL $1"; fail=1; }
python3 - "$APP/build.json" <<'PY' || fail=1
import json, sys
d = json.load(open(sys.argv[1])); bad = []
cfg = [m for m in d["ui"]["modes"] if m.get("tab") == "configs"]
want = [("configs_general", "General", "fleet_general"), ("configs_permissions", "Permissions", "fleet_perms"), ("configs_about", "About", "fleet_about")]
if [(m["id"], m["label"], m["kind"]) for m in cfg[:3]] != want: bad.append("P1 configs tab does not start with General | Permissions | About")
needs = d["perms"]["needs"]
for p in ("fine_location", "nearby_wifi"):
    if p not in needs: bad.append("P2 perms.needs lacks " + p)
    if not d["configs_pages"].get("about", {}).get("perms_why", {}).get(p): bad.append("P2 no perms_why for " + p)
if "API 33" not in d["configs_pages"]["about"]["perms_why"].get("nearby_wifi", ""): bad.append("P2 nearby_wifi why must name API 33")
for k in ("general", "about"):
    if k not in d["configs_pages"]: bad.append("P2 configs_pages lacks " + k)
for b in bad: print("  FAIL " + b)
sys.exit(1 if bad else 0)
PY
for k in '"fleet_general" -> FleetGeneralMode' '"fleet_perms" -> FleetPermsMode' '"fleet_about" -> FleetAboutMode'; do
  grep -qF "$k" "$SRC/ui/ModeScreens.kt" && ok "P1 $k" || bad "P1 ModeScreen lacks $k"; done
grep -q 'FleetGeneralPage(' "$SRC/ui/ConfigsScreens.kt" && grep -q 'AppPermsSection(' "$SRC/ui/ConfigsScreens.kt" && grep -q 'FleetAboutPage(' "$SRC/ui/ConfigsScreens.kt" \
  && ok "P1 shared pages reused" || bad "P1 ConfigsScreens.kt must reuse FleetGeneralPage / AppPermsSection / FleetAboutPage"
grep -q 'CONFIGS_PERMISSIONS_MODE = "configs_permissions"' "$SRC/ui/ConfigsScreens.kt" && ok "P3 link target id" || bad "P3 CONFIGS_PERMISSIONS_MODE"
grep -q 'ComponentActivity(), PermsHost' "$SRC/MainActivity.kt" && grep -q 'state.show(CONFIGS_PERMISSIONS_MODE)' "$SRC/MainActivity.kt" \
  && ok "P3 Open Permissions -> Configs > Permissions" || bad "P3 MainActivity must be PermsHost showing CONFIGS_PERMISSIONS_MODE"
grep -q 'PermsLink.open(ctx, "Wi-Fi"' "$WIFI" && grep -q 'rememberPermsAsk' "$WIFI" && grep -q 'tag = "grant-perms"' "$WIFI" \
  && ok "P3 Wi-Fi inline Grant via libs:perms" || bad "P3 Wi-Fi card lacks inline Grant / PermsLink"
grep -q "project(':libs:perms')" "$APP/app/build.gradle" && ok "P3 libs:perms linked" || bad "P3 app/build.gradle lacks libs:perms"
[ $fail = 0 ] && echo "calc configs pages: all green" || { echo "calc configs pages: FAILED"; exit 1; }
