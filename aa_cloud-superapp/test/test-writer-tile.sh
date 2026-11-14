#!/usr/bin/env bash
# Tester: the Writer tile on Cloud ▸ Tools Primary.
#
# (#1707: the tile now opens the Writer tab of Cloud Keyboard; T3/T4 were rewritten for that.)
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

echo "== T1 (#1736): the Writer TILE is gone from Cloud > Primary; Writer is the Keyboard menu's action =="
N="$(jq -r "[$GROUP] | length" "$BJ")"
eq "exactly one Cloud/Primary group" "$N" "1"
HIT="$(jq -r "[$GROUP | .tiles[] | select(.id == \"writer\" or .label == \"Writer\")] | length" "$BJ")"
eq "no Writer tile in that group (it read as the retired Cloud Writer app)" "$HIT" "0"
ACT="$(jq -r '.ui.external_apps[] | select(.id == "cloud-keyboard") | .actions[] | select(.label == "Writer") | .target' "$BJ")"
eq "rule 16: Writer is a declared action of cloud-keyboard, same deep link the tile had" "$ACT" "extapp:cloud-keyboard#page:writer"
PHONEHIT="$(jq -r '[.ui.sections[] | select(.id == "phone") | .phone_app_groups[]
                   | select(.title == "Tools Primary") | .packages[]
                   | tostring | select(test("cloudwriter|Writer"))] | length' "$BJ")"
eq "the PHONE page's Tools Primary has no Writer" "$PHONEHIT" "0"

echo "== T2 (#1736): the Writer icon drawable went with the tile =="
[ ! -e "$APP/app/src/main/res/drawable/ic_writer.xml" ] && ok "ic_writer.xml is gone" || bad "ic_writer.xml is still there, unused"

echo "== T3: (#1707) the Writer tile opens the KEYBOARD's Writer page; the standalone app is retired =="
# The tile used to hand off to ac_cloud-writer. That app is gone (merged into Cloud Keyboard's Writer tab), so the tile
# targets extapp:cloud-keyboard with the deep target page:writer, which SettingsActivity resolves to the Writer tab.
KB="$ROOT/ac_cloud-keyboard"
PKG_KB="$(jq -r '.android.application_id' "$KB/build.json")"
case "$PKG_KB" in
  com.*) ok "ac_cloud-keyboard/build.json::android.application_id = $PKG_KB" ;;
  *)     bad "could not read android.application_id from ac_cloud-keyboard/build.json (got '$PKG_KB')" ;;
esac
EA_HUB="$(jq -r '.ui.external_apps[] | select(.id == "cloud-keyboard") | .hub_package' "$BJ")"
eq "ui.external_apps[cloud-keyboard].hub_package agrees" "$EA_HUB" "$PKG_KB"
EA_URL="$(jq -r '.ui.external_apps[] | select(.id == "cloud-keyboard") | .install_apk_url' "$BJ")"
case "$EA_URL" in
  https://*/Cloud-Keyboard.apk) ok "the not-installed path offers the keyboard APK ($EA_URL)" ;;
  *)                            bad "install_apk_url of cloud-keyboard is not a Cloud-Keyboard.apk URL: '$EA_URL'" ;;
esac
N_EA="$(jq -r '[.ui.external_apps[] | select(.id == "cloud-writer")] | length' "$BJ")"
eq "no ui.external_apps entry for the retired cloud-writer" "$N_EA" "0"
N_FL="$(jq -r '[.apps[] | select(.id == "writer")] | length' "$FLEET")"
eq "the fleet manifest no longer lists writer" "$N_FL" "0"
[ ! -e "$ROOT/ac_cloud-writer" ] && ok "ac_cloud-writer is gone" || bad "ac_cloud-writer still exists"

echo "== T4: the keyboard resolves the deep target =="
NAV="$ROOT/ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/settings"
grep -q 'it == "writer"' "$NAV/SettingsNavHost.kt" \
  && ok "SettingsDestination.externalRoute resolves 'writer' to the Writer tab" \
  || bad "SettingsDestination.externalRoute has no 'writer' route"
grep -q 'shortcut_action' "$NAV/SettingsActivity.kt" \
  && ok "SettingsActivity reads shortcut_action=page:<name>" \
  || bad "SettingsActivity does not read shortcut_action"

echo "== T5: the first-install path still picks the ABI asset (the rule Cloud Writer introduced, kept for every companion) =="
APP="$(cd "$(dirname "$0")/.." && pwd)"
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
