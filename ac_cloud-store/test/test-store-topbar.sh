#!/usr/bin/env bash
# Cloud Store ADOPTS the fleet's AppTopBar (libs:bottomnav): hamburger + page drawer, title, AI and Settings.
# What this holds, statically (no build, no device, no network):
#   T1 build.json::ui.top_bar declares the bar: drawer, ai, settings [account, configs] (the title is the app's build.json name, passed by the host)
#   T2 Configs MOVED: ui.bottom_nav no longer lists settings (five island items, Settings among none), the settings section is still
#      declared (it is the page Configs opens) and MainActivity still draws SettingsPage for it
#   T3 MainActivity draws the declared bar with the SHARED AppTopBarScaffold: TopBarDecl from BuildConfig,
#      Account = rememberAccountLauncher(), Configs = the settings section, AI = libs:ai's panel over the
#      gateway; and it has no bar of its own (no DropdownMenu, no menu/AI icon, no second hamburger)
#   T4 the drawer is the PAGE's: MainActivity calls TopBarDrawer { StoreDrawer }, StoreDrawer lists the open
#      section's pages and the island sections from the NavDecl (no section or page typed in Kotlin)
#   T5 the build carries it: gradle bakes UI_TOP_BAR_B64 and AI_GATEWAY_URL, applies libs:ai's ai-gateway.gradle,
#      links libs:ai; build.json::modules has libs:ai + libs:model-catalogue and the app depends on them
#   T6 the shared bar's Compose UI suite runs through this app: tests.unit.task is storeUnitTests, which depends on libs:bottomnav's unit task, and
#      AppTopBarTest still drives the drawer, the AI panel, the Settings entries, the Account intent, Configs
# Then each planted defect must turn it red (a check that cannot fail is not a check).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APP="ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore"
BN="ab_cloud-libs-shared/libs/bottomnav"
PASS=0; FAIL=0

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

check() {  # check <root> -> number of failures
  local R="$1" f=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; f=$((f+1)); }
  local BJ="$R/ac_cloud-store/build.json" MA="$R/$APP/MainActivity.kt" DR="$R/$APP/StoreDrawer.kt"
  local GR="$R/ac_cloud-store/app/build.gradle" TS="$R/$BN/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBarTest.kt"
  for x in "$BJ" "$MA" "$DR" "$GR" "$TS"; do [ -f "$x" ] || { bad "missing ${x#$R/}"; return 1; }; done
  python3 - "$BJ" <<'PY' && ok "T1 ui.top_bar declares drawer, ai and settings [account, configs] and restates no app name" || bad "T1 build.json ui.top_bar is not {drawer:true, ai:true, settings:[account, configs]} (or restates a title)"
import json, sys
t = (json.load(open(sys.argv[1]))['ui'].get('top_bar') or {})
sys.exit(0 if 'title' not in t and t.get('drawer') is True and t.get('ai') is True and t.get('settings') == ['account', 'configs'] else 1)
PY
  python3 - "$BJ" <<'PY' && ok "T2 settings left the island (five items: cloud, phone, search, torrent, access; opens on cloud) and stays a declared section" || bad "T2 ui.bottom_nav still lists settings, or the settings section is gone"
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
secs = [s['id'] for s in ui['sections']]
sys.exit(0 if ui['bottom_nav'] == ['cloud', 'phone', 'search', 'torrent', 'access'] and ui['default_section'] == 'cloud' and 'settings' in secs else 1)
PY
  local ma; ma="$(strip "$MA")"
  printf '%s' "$ma" | grep -q 'if (selected == TAB_SETTINGS) section?.let { ConfigsPage(pageOf(it)) }' \
    && ok "T2 the settings section still draws the Configs page (ConfigsPage, with its declared pages)" || bad "T2 MainActivity no longer draws ConfigsPage for the settings section"
  printf '%s' "$ma" | grep -q 'StorePages.select(TAB_PHONE, StorePhoneFragment.PAGE_SEARCH)' && printf '%s' "$ma" | grep -q 'selected == TAB_PHONE || selected == TAB_SEARCH' && python3 -c 'import json,sys; s={x["id"]:x for x in json.load(open(sys.argv[1]))["ui"]["sections"]}; sys.exit(0 if s["search"]["icon"]=="search" and "\"search\" to" in open(sys.argv[2]).read() else 1)' "$BJ" "$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt" \
    && ok "T2 Search is its own island item hosted by the Phone fragment's search page (no second copy), with its own icon (the catalog's search glyph)" || bad "T2 MainActivity does not host the Search section on StorePhoneFragment's search page"
  printf '%s' "$ma" | grep -q 'NavBarTopFirstRowScaffold(' && printf '%s' "$ma" | grep -q 'TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64)' \
    && ok "T3 MainActivity draws the declared bar with the shared AppTopBarScaffold" || bad "T3 MainActivity does not feed AppTopBarScaffold from the baked TopBarDecl"
  printf '%s' "$ma" | grep -q 'onAccount = rememberAccountLauncher()' \
    && ok "T3 Account launches Cloud Account through the shared launcher" || bad "T3 Account is not the shared rememberAccountLauncher()"
  printf '%s' "$ma" | grep -q 'onConfigs = { selected = TAB_SETTINGS }' \
    && ok "T3 Configs opens the Store's own Configs (settings) page" || bad "T3 Configs does not open the settings section"
  printf '%s' "$ma" | grep -q 'aiPanel = { AiFleetChatPanel(BuildConfig.AI_GATEWAY_URL) }' \
    && ok "T3 the AI panel is libs:ai's chat over the gateway" || bad "T3 the AI panel is not AiFleetChatPanel(AI_GATEWAY_URL)"
  local own=0 f2
  for f2 in "$MA" "$DR"; do   # the shell and its drawer; other pages may have their own, unrelated dropdowns
    if strip "$f2" | grep -Eq 'DropdownMenu\(|Icons\.Filled\.(Menu|AutoAwesome)|Icons\.Default\.Menu|ModalNavigationDrawer\('; then echo "    hand-rolled bar part in $(basename "$f2")"; own=$((own+1)); fi
  done
  [ "$own" -eq 0 ] && ok "T3 the shell draws no bar part of its own" || bad "T3 $own file(s) hand-roll a menu, a dropdown or a drawer"
  printf '%s' "$ma" | grep -q 'TopBarDrawer {' && printf '%s' "$ma" | grep -q 'StoreDrawer(NAV, selected' \
    && ok "T4 the page supplies the drawer (TopBarDrawer { StoreDrawer })" || bad "T4 MainActivity does not supply the drawer through TopBarDrawer"
  local dr; dr="$(strip "$DR")"
  printf '%s' "$dr" | grep -q 'nav.bottomSections().forEach' && printf '%s' "$dr" | grep -q 'section.pages.forEach' \
    && ok "T4 the drawer lists the island sections and the open section's pages from the NavDecl" || bad "T4 StoreDrawer does not read the NavDecl"
  if printf '%s' "$dr" | grep -Eq '"(Cloud|Phone|Feed|Access|Installed|Declared|Commits)"'; then bad "T4 StoreDrawer types a section or page name"; else ok "T4 no section or page name typed in StoreDrawer"; fi
  local bg; bg="$(cat "$GR")"
  printf '%s' "$bg" | grep -q 'UI_TOP_BAR_B64' && printf '%s' "$bg" | grep -q 'AI_GATEWAY_URL' \
    && printf '%s' "$bg" | grep -q 'libs/ai/ai-gateway.gradle' && printf '%s' "$bg" | grep -q "project(':libs:ai')" \
    && ok "T5 gradle bakes the bar and the gateway and links libs:ai" || bad "T5 gradle misses UI_TOP_BAR_B64 / AI_GATEWAY_URL / ai-gateway.gradle / libs:ai"
  python3 - "$BJ" <<'PY' && ok "T5 build.json::modules carries libs:ai and libs:model-catalogue, and the app depends on them" || bad "T5 the module graph does not carry libs:ai / libs:model-catalogue"
import json, sys
m = json.load(open(sys.argv[1]))['modules']
dep = m['app']['depends_on']
sys.exit(0 if 'libs:ai' in m and 'libs:model-catalogue' in m and 'libs:ai' in dep and 'libs:model-catalogue' in dep else 1)
PY
  python3 - "$BJ" <<'PY' && ok "T6 tests.unit runs the storeUnitTests task (libs:bottomnav's suite among them) through this app" || bad "T6 tests.unit is not storeUnitTests"
import json, sys
u = (json.load(open(sys.argv[1])).get('tests') or {}).get('unit') or {}
sys.exit(0 if u.get('task') == 'storeUnitTests' and u.get('enabled') is True and ':libs:bottomnav:testDebugUnitTest' in open(sys.argv[1].replace('build.json', 'build.gradle')).read() else 1)
PY
  local n=0 t
  for t in 'hamburger opens the drawer' 'AI opens an anchored panel' 'Settings lists exactly the declared entries' 'Account launches Cloud Account by its OPEN intent' "Configs calls the host's callback"; do
    grep -q "$t" "$TS" && n=$((n+1)) || echo "    AppTopBarTest lost: $t"
  done
  [ "$n" -eq 5 ] && ok "T6 AppTopBarTest drives the drawer, the AI panel, the Settings entries, the Account intent and Configs" || bad "T6 AppTopBarTest is missing a case ($n/5)"
  return $f
}

out="$(check "$ROOT")"; rc=$?; printf '%s\n' "$out"
PASS=$(printf '%s\n' "$out" | grep -c '  PASS:'); FAIL=$rc

# planted defects: each must turn the check red
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mirror() {
  rm -rf "$TMP/r"; mkdir -p "$TMP/r"
  for d in ac_cloud-store/build.json "$APP/MainActivity.kt" "$APP/StoreDrawer.kt" ac_cloud-store/app/build.gradle \
           "$BN/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBarTest.kt"; do
    mkdir -p "$TMP/r/$(dirname "$d")"; cp "$ROOT/$d" "$TMP/r/$d"; done
}
while IFS='|' read -r file expr; do
  [ -n "$file" ] || continue
  mirror
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$expr; open(p,'w').write(t); sys.exit(0 if t!=s else 3)" "$TMP/r/$file" \
    || { echo "  FAIL: mutation did not apply: $file ${expr:0:60}"; FAIL=$((FAIL+1)); continue; }
  if check "$TMP/r" >/dev/null 2>&1; then echo "  FAIL: NOT caught: $file ${expr:0:70}"; FAIL=$((FAIL+1))
  else echo "  PASS: caught: $file ${expr:0:70}"; PASS=$((PASS+1)); fi
done <<MUT
ac_cloud-store/build.json|s.replace('"drawer": true,', '"drawer": false,')
ac_cloud-store/build.json|s.replace('"drawer": true,', '"drawer": true,\n      "title": "Cloud Store",')
ac_cloud-store/build.json|s.replace('"access"\n    ],\n    "top_bar"', '"access",\n      "settings"\n    ],\n    "top_bar"')
ac_cloud-store/build.json|s.replace('"id": "settings",', '"id": "settingz",')
ac_cloud-store/build.json|s.replace('"storeUnitTests"', '":app:testDebugUnitTest"')
ac_cloud-store/build.json|s.replace('"libs:ai",\n        "libs:model-catalogue",\n        "libs:torrent"\n      ]', '"libs:model-catalogue",\n        "libs:torrent"\n      ]')
$APP/MainActivity.kt|s.replace('NavBarTopFirstRowScaffold(', 'NavBarTopFirstRowScaffoldx(')
$APP/MainActivity.kt|s.replace('onAccount = rememberAccountLauncher()', 'onAccount = { }')
$APP/MainActivity.kt|s.replace('onConfigs = { selected = TAB_SETTINGS }', 'onConfigs = { }')
$APP/MainActivity.kt|s.replace('aiPanel = { AiFleetChatPanel(BuildConfig.AI_GATEWAY_URL) }', 'aiPanel = { }')
$APP/MainActivity.kt|s.replace('TopBarDrawer {', 'TopBarDrawerx {')
$APP/MainActivity.kt|s.replace('if (selected == TAB_SETTINGS) section?.let { ConfigsPage(pageOf(it)) }', '')
$APP/MainActivity.kt|s.replace('import com.diegonmarcos.superapp.ai.AiFleetChatPanel', 'import com.diegonmarcos.superapp.ai.AiFleetChatPanel\nimport androidx.compose.material3.DropdownMenu') + '\nfun x() { DropdownMenu(expanded = false, onDismissRequest = {}) {} }\n'
$APP/StoreDrawer.kt|s.replace('nav.bottomSections().forEach', 'listOf<NavSection>().forEach')
$APP/StoreDrawer.kt|s.replace('Eyebrow("Store")', 'Eyebrow("Cloud")')
ac_cloud-store/app/build.gradle|s.replace('"UI_TOP_BAR_B64"', '"UI_TOP_BAR"')
ac_cloud-store/app/build.gradle|s.replace('libs/ai/ai-gateway.gradle', 'libs/ai/none.gradle')
$BN/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBarTest.kt|s.replace('Account launches Cloud Account by its OPEN intent', 'Account is tested elsewhere')
MUT

echo "== RESULT(Cloud Store adopts AppTopBar): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
