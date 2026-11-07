#!/usr/bin/env bash
# #865 Cloud Store is the Store, split out of cloud-superapp.
#
# What this holds, statically (no build, no device, no network):
#  1. Cloud Store hosts the three Store pages from libs:appstore (no copy of
#     the store code in this app) and claims the unattended fleet pass.
#  2. libs:appstore honours AppStoreHost.runsFleetPass at all four entry
#     points of that pass: schedule (start), Wi-Fi/screen kick, the
#     launch-time checkNow, and doWork itself. A missed one is two apps
#     racing the same downloads and install sessions.
#  3. SuperApp hands the pass over exactly when Cloud Store is installed, and
#     routes all three Store pages through the hand-off.
#  4. One package name: SuperApp's hand-off, this app's build.json and the
#     fleet manifest row agree, and the OPEN action SuperApp sends is the one
#     this manifest declares.
#  5. (#866) Parity with SuperApp's Store pages: the shelves come from the ONE
#     taxonomy (StoreShelves in libs:appstore, wired in both apps), the
#     download resolver is the one StoreDns path in libs:appstore, and the fleet
#     bearer reaches the feeds through SuperApp's read-only CONSTELLATION_DATA
#     provider (own token entry as the fallback, never logged).
#  6. (#869) Dense, data-first: ONE density declaration (StoreDensity in
#     libs:appstore) is dense (scales below 1.0) and every Cloud Store screen and
#     libs:appstore shelf reads it - no dp/sp literal anywhere else.
#
# Then it plants each mutation below in a scratch copy and requires the
# checks to go red, so a check that cannot fail does not count as a check.
#
# Usage: ./test-store-app.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

STORE="$ROOT/ac_cloud-store"
LIB="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
SUPER="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp"
LIBAPPS="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/apps"
LIBGRADLE="$ROOT/ab_cloud-libs-shared/libs/appstore/build.gradle"
SUPERMF="$ROOT/aa_cloud-superapp/app/src/main/AndroidManifest.xml"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <store-dir> <lib-dir> <superapp-dir> <fleet.json> <lib-apps-dir> <lib-build.gradle> <superapp-manifest>  → PASS/FAIL lines, exit = #fails
check() {
  local S="$1" L="$2" P="$3" F="$4" LA="$5" LG="$6" PM="$7" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local MA="$S/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt"
  local AP="$S/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"
  local MF="$S/app/src/main/AndroidManifest.xml"
  local W="$L/ConstellationWorker.kt" H="$L/AppStoreHost.kt"
  local HO="$P/apps/CloudStoreHandoff.kt" SA="$P/App.kt" SP="$P/launcher/SectionPages.kt"
  for f in "$MA" "$AP" "$MF" "$W" "$H" "$HO" "$SA" "$SP" "$S/build.json" "$S/app/build.gradle" "$F"; do
    [ -f "$f" ] || { bad "missing $f"; return 99; }
  done

  # 1. hosts the lib's pages, owns no store code, claims the pass
  local ma; ma="$(strip "$MA")"
  for frag in StoreCloudFragment StorePhoneFragment; do
    printf '%s' "$ma" | grep -Eq -- "AndroidFragment<$frag>" && ok "Cloud Store shows $frag" || bad "Cloud Store does not show $frag"
  done
  grep -q "project(':libs:appstore')" "$S/app/build.gradle" && ok "app links :libs:appstore" || bad "app does not link :libs:appstore"
  if find "$S/app/src" -name '*.kt' | xargs grep -l "^package com.diegonmarcos.superapp.appstore" 2>/dev/null | grep -q .; then
    bad "a copy of libs:appstore code lives in ac_cloud-store"; else ok "no copy of the store code in this app"; fi
  local ap; ap="$(strip "$AP")"
  printf '%s' "$ap" | grep -q 'runsFleetPass *= *{ *true *}' && ok "Cloud Store claims the fleet pass" || bad "Cloud Store does not claim the fleet pass"
  printf '%s' "$ap" | grep -q 'ConstellationWorker.start(this)' && ok "Cloud Store schedules the fleet pass" || bad "Cloud Store never schedules the fleet pass"

  # 2. the lib honours the hook at all four entry points
  grep -q 'var runsFleetPass: (android.content.Context) -> Boolean = { true }' "$H" && ok "hook declared, default true" || bad "AppStoreHost.runsFleetPass missing or not defaulting to true"
  local w; w="$(strip "$W")"
  body() { printf '%s' "$w" | sed -n "/$1/,/^        }/p"; }
  printf '%s' "$w" | sed -n '/override suspend fun doWork/,/StoreAuto.attach/p' | grep -q 'runsFleetPass(applicationContext)' && ok "doWork stands down when another app owns the pass" || bad "doWork runs the pass regardless of runsFleetPass"
  body 'fun kick(' | grep -q 'runsFleetPass(context)) return' && ok "kick() gated" || bad "kick() not gated on runsFleetPass"
  body 'fun checkNow(' | grep -q 'runsFleetPass(context)) return' && ok "checkNow() gated" || bad "checkNow() not gated on runsFleetPass"
  local st; st="$(body 'fun start(')"
  if printf '%s' "$st" | sed -n '/runsFleetPass/,/StoreAuto.attach/p' | grep -q 'StoreAuto.attach' &&
     printf '%s' "$st" | sed -n '/runsFleetPass/,/return/p' | grep -q 'cancelUniqueWork("$WORK_NAME-kick")'; then
    ok "start() cancels queued work and attaches no Wi-Fi trigger when it does not own the pass"
  else bad "start() schedules or attaches before checking runsFleetPass, or leaves the kick queued"; fi

  # 3. #894 SuperApp never runs the pass, Cloud Store installed or not
  strip "$SA" | grep -q 'runsFleetPass *= *{ *false *}' && ok "SuperApp never runs the fleet pass" || bad "SuperApp's runsFleetPass is not { false }"
  local sp; sp="$(strip "$SP")"
  for pg in store-cloud store-phone; do
    printf '%s' "$sp" | grep -q "pageId == \"$pg\" *-> *com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)" && ok "SuperApp page $pg goes through the hand-off" || bad "SuperApp page $pg bypasses the hand-off"
  done
  # Apps Mesh left SuperApp's Configs for Store > Access (one Apps Access tile, test-apps-mesh.sh C1): SuperApp routes no apps-mesh page of its own.
  printf '%s' "$sp" | grep -q 'pageId == "apps-mesh"' && bad "SuperApp still routes an apps-mesh page (it is Store > Access now)" || ok "SuperApp routes no apps-mesh page of its own (Store > Access hosts Apps Mesh)"
  # #1211 the Store's Apps Mesh view carries no App API Endpoints button (Access > Apps MCP-API replaced it)
  grep -q 'AppsMeshFragment.ARG_SHOW_ENDPOINTS to false' "$S/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt" \
    && ok "the Store's Apps Mesh view hides the App API Endpoints button" || bad "the Store's Apps Mesh view still offers App API Endpoints"

  # 4. one package name, one OPEN action
  local appid pkg action
  appid="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['android']['application_id'])" "$S/build.json")"
  pkg="$(strip "$HO" | sed -n 's/.*const val PKG = "\([^"]*\)".*/\1/p')"
  [ -n "$appid" ] && [ "$appid" = "$pkg" ] && ok "hand-off package = build.json application_id ($appid)" || bad "hand-off package '$pkg' != application_id '$appid'"
  grep -q "\"package\":\"$appid\"" "$F" && ok "fleet manifest lists $appid (installable and updated by the Store)" || bad "fleet manifest has no row for $appid"
  action="$(strip "$HO" | sed -n 's/.*ACTION_OPEN = "\([^"]*\)".*/\1/p')"
  grep -q "<action android:name=\"$action\" */>" "$MF" && ok "manifest answers SuperApp's $action" || bad "manifest does not declare the action SuperApp sends ('$action')"

  # 5. #866 parity with SuperApp's Store pages
  local sa; sa="$(strip "$SA")"
  [ -f "$LA/StoreShelves.kt" ] && [ -f "$LA/PhoneFolders.kt" ] && [ -f "$LA/PhoneTaxonomy.kt" ] && ok "the taxonomy and StoreShelves live in libs:appstore" || bad "taxonomy/StoreShelves missing from libs:appstore"
  [ ! -e "$P/apps/PhoneFolders.kt" ] && [ ! -e "$P/apps/StoreShelves.kt" ] && ok "SuperApp holds no second copy of the taxonomy" || bad "a copy of the taxonomy/StoreShelves is back in SuperApp"
  printf '%s' "$ap" | grep -q 'classify *= *StoreShelves::of' && ok "Cloud Store shelves by StoreShelves" || bad "Cloud Store does not set AppStoreHost.classify"
  printf '%s' "$sa" | grep -q 'classify *= *com.diegonmarcos.superapp.apps.StoreShelves::of' && ok "SuperApp shelves by the same StoreShelves" || bad "SuperApp does not shelve by StoreShelves"
  grep -q 'UI_PHONE_FOLDERS_B64' "$LG" && grep -q 'UI_PHONE_SECTIONS_B64' "$LG" && grep -q 'aa_cloud-superapp/build.json' "$LG" && ok "libs:appstore bakes the taxonomy (SuperApp's build.json as the fallback source)" || bad "libs:appstore does not bake the taxonomy for a host without one"
  [ ! -e "$P/network/StoreDns.kt" ] && [ -f "$L/StoreDns.kt" ] && ok "StoreDns lives once, in libs:appstore" || bad "StoreDns is not a single copy in libs:appstore"
  printf '%s' "$ap" | grep -q 'StoreDns.start(this)' && ok "Cloud Store resolves downloads through StoreDns" || bad "Cloud Store does not start StoreDns"
  printf '%s' "$sa" | grep -q 'StoreDns.start(' && ok "SuperApp resolves downloads through the same StoreDns" || bad "SuperApp does not start StoreDns"
  local sd; sd="$(strip "$L/StoreDns.kt")"
  printf '%s' "$sd" | grep -q 'DownloadFailure.activeResolver *=' && printf '%s' "$sd" | grep -Eq 'var resolve:' && ok "StoreDns names the resolvers tried and takes the preset from the host" || bad "StoreDns lost the failure wording or the preset hook"
  printf '%s' "$ap" | grep -q 'FeedViewer.fleetBearer *=.*FleetBearer.resolve(this)' && ok "Cloud Store feeds read the bearer through FleetBearer.resolve" || bad "Cloud Store feeds do not read FleetBearer.resolve"
  local fb; fb="$(strip "$L/FleetBearer.kt")"
  printf '%s' "$fb" | grep -q 'fun resolve(ctx: Context): String = fromAccount(ctx).ifEmpty { fromSuperApp(ctx) }.ifEmpty { Own(ctx).token' && ok "bearer: Cloud Account first, then SuperApp, own entry as the fallback" || bad "FleetBearer.resolve order is not Account, SuperApp, own"
  # the settings line names the source that really answered (never "SuperApp" for a token Cloud Account supplied)
  printf '%s' "$fb" | grep -q 'enum class Source { ACCOUNT, SUPERAPP, OWN, NONE }' && printf '%s' "$fb" | grep -q 'fromAccount(ctx).isNotEmpty() -> Source.ACCOUNT' && printf '%s' "$fb" | grep -q 'fromSuperApp(ctx).isNotEmpty() -> Source.SUPERAPP' && ok "FleetBearer.source tells Cloud Account from SuperApp, in the resolve order" || bad "FleetBearer.source does not name Cloud Account / SuperApp separately"
  local ma; ma="$(strip "$S/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt")"
  local cp; cp="$(strip "$S/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt")"
  printf '%s' "$cp" | grep -q 'Source.ACCOUNT -> "Supplied by Cloud Account' && printf '%s' "$cp" | grep -q 'Source.SUPERAPP -> "Supplied by Cloud SuperApp' && printf '%s' "$cp" | grep -q 'Source.OWN -> "Using the token entered below' && ok "the Store's bearer line names its real source (Account, SuperApp or its own entry)" || bad "the Store's bearer line does not name Cloud Account / SuperApp / its own entry"
  local pv; pv="$(strip "$P/cloud/FleetBearerProvider.kt")"
  printf '%s' "$pv" | grep -q 'checkCallingPermission(FleetConfig.PERMISSION)' && printf '%s' "$pv" | grep -q 'method != FleetBearer.METHOD_BEARER' && ok "provider re-checks CONSTELLATION_DATA in call() and serves one method" || bad "provider does not re-check the permission or accepts other methods"
  python3 - "$PM" <<'PY' && ok "manifest exports the provider behind CONSTELLATION_DATA" || bad "provider missing from the manifest or not signature-guarded"
import re, sys
m = re.search(r'<provider\b[^>]*FleetBearerProvider[^>]*>', open(sys.argv[1], encoding='utf-8').read(), re.S)
sys.exit(0 if m and 'fleetbearer' in m.group(0) and 'permission.CONSTELLATION_DATA' in m.group(0) else 1)
PY
  if printf '%s%s' "$pv" "$fb" | grep -Eq 'Log\.|println|\.bearerToken *=|DaguPrefs\(ctx\)\.edit|\.clear\('; then bad "the bearer path logs or writes the token"; else ok "the bearer path never logs and the provider never writes"; fi
  grep -q 'TAB_SETTINGS' "$MA" && grep -q 'ConfigsPage(pageOf' "$MA" && grep -q 'FleetBearer.Own(' "$S/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt" && ok "Cloud Store has a token entry in Configs > Sources" || bad "Cloud Store has no token entry"
  # 6. #869 density
  local DN="$L/StoreDensity.kt"
  [ -f "$DN" ] || { bad "StoreDensity.kt (the density declaration) is missing"; return $((fails+1)); }
  python3 - "$DN" <<'PY' && ok "the density declaration exists and is dense (SCALE and TEXT_SCALE < 1)" || bad "StoreDensity does not declare SCALE/TEXT_SCALE below 1.0"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
v = [float(re.search(r'const val %s = ([0-9.]+)f' % n, s).group(1)) if re.search(r'const val %s = ([0-9.]+)f' % n, s) else 9 for n in ('SCALE', 'TEXT_SCALE')]
sys.exit(0 if 'object StoreDensity' in s and all(0 < x < 1 for x in v) else 1)
PY
  local lit=0 f
  for f in "$L"/*.kt "$LA"/*.kt "$MA" "$S"/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt "$S"/app/src/main/java/com/diegonmarcos/cloudstore/TorrentPage.kt "$S"/app/src/main/java/com/diegonmarcos/cloudstore/TorrentConfigPage.kt "$S"/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt; do
    [ "$(basename "$f")" = StoreDensity.kt ] && continue
    if strip "$f" | grep -Eq 'dp\([^()]+(\(\))?, *[0-9]+\)|textSize *= *[0-9.]+f|, *[0-9]{2}f *[,)]|[0-9] *\* *[a-z]*\.?resources\.displayMetrics\.density|[1-9][0-9.]*\.(dp|sp)\b'; then
      echo "    size literal in $(basename "$f")"; lit=$((lit+1)); fi
  done
  [ $lit -eq 0 ] && ok "no dp/sp literal outside the density declaration" || bad "$lit file(s) hold their own dp/sp literals"
  local nodel=0
  for f in "$L"/*.kt; do
    [ "$(basename "$f")" = StoreDensity.kt ] && continue
    grep -qE 'fun (dp|sdp)\(' "$f" && ! grep -qE 'fun dp\(ctx: Context, v: Int\) = StoreDensity.dp\(ctx, v\)|fun sdp\(step: Int\): Dp = StoreDensity.dpValue\(step\).dp' "$f" && nodel=$((nodel+1))
  done
  [ $nodel -eq 0 ] && ok "every local dp helper delegates to StoreDensity" || bad "$nodel dp helper(s) scale on their own"
  grep -q 'StoreDensity\.' "$S/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt" && ok "Cloud Store's screens read StoreDensity" || bad "Cloud Store's Configs page does not read StoreDensity"
  grep -q 'StoreDensity\.T_' "$L/StoreCloudFragment.kt" && grep -q 'StoreDensity\.T_' "$L/StorePhoneFragment.kt" && ok "the Cloud and Phone shelves draw their type from the ramp" || bad "a shelf page does not use the StoreDensity type ramp"
  # 7. #868 the fleet nav pattern: the island's five pages (#896) are build.json::ui.sections, the bar is the shared island
  python3 - "$S/build.json" "$L/../../../../../assets/appstore-feeds.json" <<'PY' && ok "ui.bottom_nav is EXACTLY cloud, phone, search, torrent, access (five, each a ui.sections id; settings is the section the top bar's Configs opens), Phone's pages are installed|declared, Search's the source subpages search-cloudy|search-android|search-torrent, Feed is not a destination (it is two panels on the Cloud page), Access's mesh|ipc|identity|android|cloud, default Cloud" || bad "build.json ui.bottom_nav/sections/default_section do not declare cloud, phone, search, feed, access (+ the settings section), default cloud"
import json, os, sys
ui = json.load(open(sys.argv[1], encoding='utf-8')).get('ui') or {}
secs = {s.get('id'): s for s in ui.get('sections') or []}
feeds = json.load(open(sys.argv[2]))['feeds'] if os.path.exists(sys.argv[2]) else None
assert feeds is None or [f['id'] for f in feeds][:2] == ['commits', 'cicd'], 'the Cloud page panels need the commits + cicd feeds'
pg = lambda i: [p.get('id') for p in secs.get(i, {}).get('pages') or []]
ok = (ui.get('bottom_nav') == ['cloud', 'phone', 'search', 'torrent', 'access'] and 'feed' not in secs and 'torrent' in secs
      and set(ui['bottom_nav']) <= set(secs) and 'settings' in secs and 'mesh' not in secs and 'perms' not in secs
      and pg('access') == ['mesh', 'mcpapi', 'ipc', 'identity', 'android', 'cloud']
      and pg('phone') == ['installed', 'declared'] and 'search' in secs and pg('search') == ['search-cloudy', 'search-android', 'search-torrent']
      and ui.get('default_section') == 'cloud')
sys.exit(0 if ok else 1)
PY
  local bg; bg="$(cat "$S/app/build.gradle")"
  printf '%s' "$bg" | grep -q 'UI_BOTTOM_NAV' && printf '%s' "$bg" | grep -q 'UI_SECTIONS_B64' && printf '%s' "$bg" | grep -q "project(':libs:bottomnav')" && ok "build.gradle bakes the declaration and links libs:bottomnav" || bad "build.gradle does not bake UI_BOTTOM_NAV/UI_SECTIONS_B64 or link libs:bottomnav"
  printf '%s' "$ma" | grep -q 'NavBarBottomHost(' && printf '%s' "$ma" | grep -q 'NavDecl.fromBuildConfig(' && printf '%s' "$ma" | grep -q 'islandEntries' && ok "MainActivity draws the island from the baked NavDecl" || bad "MainActivity does not feed the shared island from NavDecl"
  # 8. #896 the Feed and Perms pages are hosted, the child-page strips are the fleet's PageTabs, and the Cloud page no longer carries the moved parts
  printf '%s' "$ma" | grep -q 'TorrentPage()' && printf '%s' "$ma" | grep -q 'AccessPage(pageOf(it))' && ! printf '%s' "$ma" | grep -q 'FeedPage' && ok "MainActivity hosts the Torrent and Access pages (Feed moved into Cloud)" || bad "MainActivity does not host TorrentPage and AccessPage, or still hosts FeedPage"
  printf '%s' "$ma" | grep -q 'NavSectionLayout(section, pageOf(section)' && printf '%s' "$ma" | grep -q 'StorePages.hostDrawsStrip = true' && ok "MainActivity draws the declared child pages with PageTabs and tells the pages it does" || bad "MainActivity does not draw section.pages with PageTabs"
  local CS="$S/app/src/main/java/com/diegonmarcos/cloudstore"
  [ -f "$CS/TorrentPage.kt" ] && [ -f "$CS/AccessPage.kt" ] && [ ! -f "$CS/FeedPage.kt" ] && grep -q '@Composable' "$CS/TorrentPage.kt" && grep -q '@Composable' "$CS/AccessPage.kt" && grep -q 'AndroidFragment<AppsMeshFragment>' "$CS/AccessPage.kt" && grep -q 'TorrentEngines.get(' "$CS/TorrentPage.kt" && grep -q 'FeedViewer.load(' "$L/StoreFeedPanels.kt" && ok "the Torrent and Access pages are Compose (the ratchet's rule), Access hosts Apps Mesh, the Torrent page drives the engine, and the feeds are libs:appstore's StoreFeedPanels over FeedViewer" || bad "TorrentPage/AccessPage are missing, not Compose, FeedPage is back, or the feed panels no longer read FeedViewer"
  grep -q 'object StoreTabs' "$L/StoreBar.kt" && ok "the shared strip builder (StoreTabs) lives once in libs:appstore" || bad "StoreTabs is missing from libs:appstore"
  ! grep -qE 'renderPerms|renderFeed|FeedViewer' "$L/StoreCloudFragment.kt" && grep -q 'StoreFeedPanels.Buttons' "$L/StoreCloudFragment.kt" && grep -q 'StoreFeedPanels.Page(' "$L/StoreCloudFragment.kt" && ok "the Cloud page draws its Commits / CI-CD feeds only as StoreFeedPanels buttons and pages (no Perms)" || bad "the Cloud page carries the old Feed/Perms parts, or lost the feed panels"
  grep -q 'StoreTabs.Bar(' "$L/StorePhoneFragment.kt" && grep -q 'StoreTabs.Bar(' "$L/StoreCloudFragment.kt" && ok "Cloud and Phone draw their top tabs with the one StoreTabs.Bar" || bad "a Store page draws its tabs with something other than StoreTabs.Bar"
  # 8b. #896.3 Phone's action bar sits UNDER the top tabs inside the scrolling page, as Cloud's header does (no bottom frame)
  grep -q 'StorePage.Frame(strip) {' "$L/StorePhoneFragment.kt" && ! grep -qE 'fun Frame\(.*actions' "$L/StoreBar.kt" && ok "Phone's action bar is in its page under the tabs, like Cloud's (no bottom bar)" || bad "Phone's action bar left the spot under the tabs"
  # 9. #896 density: the Store is data-dense, so NO Store tab, chip, page or action button may force a
  # minimum height (a 40dp floor made them huge, 2026-10-07). A control is as tall as its text and padding.
  local mh
  mh="$(grep -nE 'minHeight|minimumHeight|setMinimumHeight|defaultMinSize|heightIn\(|MIN_TAP|minTap' "$L"/*.kt "$S"/app/src/main/java/com/diegonmarcos/cloudstore/*.kt 2>/dev/null | grep -vE '^[^:]+:[0-9]+:\s*(//|\*|/\*)')"
  [ -z "$mh" ] && ok "no Store tab, chip, page or action button sets a minimum height" || bad "a Store control forces a minimum height: $(printf '%s' "$mh" | head -3 | tr '\n' ' ')"
  # ... and the strip's own vertical padding never grows past the pre-#896 step (S8).
  python3 - "$L/StoreBar.kt" <<'PY' && ok "the tab strip's vertical padding is at most the pre-change step (S8)" || bad "the tab strip's vertical padding grew past S8 (or was not found)"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
body = s[s.index('fun RowScope.Tab('):]
body = body[:body.index('\n    }\n')]
v = [int(x) for x in re.findall(r'(?:vertical|top|bottom) = sdp\(StoreDensity\.S(\d+)\)', body)]
sys.exit(0 if v and max(v) <= 8 else 1)
PY
  printf '%s' "$ma" | grep -Eq '(^|[^A-Za-z])TabRow *\(' && bad "MainActivity still draws its own TabRow" || ok "no hand-rolled tab row"
  return $fails
}

echo "── real tree ──"
check "$STORE" "$LIB" "$SUPER" "$FLEET" "$LIBAPPS" "$LIBGRADLE" "$SUPERMF"; REAL=$?

# ── mutations: each must turn the checks red ──
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <relative-file-under-root-of-copy> <python-replace-old> <new>
  local label="$1" which="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  cp -r "$STORE" "$d/store"; cp -r "$LIB" "$d/lib"; cp -r "$SUPER" "$d/super"; cp "$FLEET" "$d/fleet.json"
  cp -r "$LIBAPPS" "$d/libapps"; cp "$LIBGRADLE" "$d/lib.gradle"; cp "$SUPERMF" "$d/super.xml"
  python3 - "$d/$which" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d/store" "$d/lib" "$d/super" "$d/fleet.json" "$d/libapps" "$d/lib.gradle" "$d/super.xml" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
W=lib/ConstellationWorker.kt
mutate "doWork gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(applicationContext)) {' 'if (false) {' || M=$((M+1))
mutate "kick gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(context)) return   // #865
                val req' 'val req' || M=$((M+1))
mutate "start gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(context)) {' 'if (false) {' || M=$((M+1))
mutate "hook default flipped" lib/AppStoreHost.kt 'var runsFleetPass: (android.content.Context) -> Boolean = { true }' 'var runsFleetPass: (android.content.Context) -> Boolean = { false }' || M=$((M+1))
mutate "SuperApp keeps the pass" super/App.kt 'runsFleetPass = { false }' 'runsFleetPass = { true }' || M=$((M+1))
mutate "store page bypasses hand-off" super/launcher/SectionPages.kt 'pageId == "store-phone"    -> com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)' 'pageId == "store-phone"    -> com.diegonmarcos.superapp.appstore.StorePhoneFragment()' || M=$((M+1))
mutate "package name drifts" super/apps/CloudStoreHandoff.kt 'const val PKG = "com.diegonmarcos.cloudstore"' 'const val PKG = "com.diegonmarcos.store"' || M=$((M+1))
mutate "OPEN action dropped" store/app/src/main/AndroidManifest.xml '<action android:name="com.diegonmarcos.cloudstore.OPEN" />' '' || M=$((M+1))
mutate "Cloud Store gives up the pass" store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt 'runsFleetPass = { true }' 'runsFleetPass = { false }' || M=$((M+1))
mutate "Apps Mesh dropped from Access" store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt 'AndroidFragment<AppsMeshFragment>' 'AndroidFragment<StoreCloudFragment>' || M=$((M+1))
mutate "Store Apps Mesh offers App API Endpoints again" store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt 'AppsMeshFragment.ARG_SHOW_ENDPOINTS to false' 'AppsMeshFragment.ARG_SHOW_ENDPOINTS to true' || M=$((M+1))
mutate "SuperApp routes its own apps-mesh page again" super/launcher/SectionPages.kt 'sectionId == "config" && pageId == "adb-shell"' 'pageId == "apps-mesh" -> null
        sectionId == "config" && pageId == "adb-shell"' || M=$((M+1))
mutate "island replaced by a TabRow" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'NavBarBottomHost(' 'TabRow(' || M=$((M+1))
mutate "island no longer fed by NavDecl" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'NavDecl.fromBuildConfig(' 'NavDeclx.fromBuildConfig(' || M=$((M+1))
mutate "a section leaves the bar" store/build.json '"bottom_nav": [
      "cloud",
      "phone",
      "search",
      "torrent",
      "access"
    ]' '"bottom_nav": [
      "cloud",
      "phone",
      "search",
      "torrent",
      "perms"
    ]' || M=$((M+1))
mutate "Phone's action bar goes to the bottom again" lib/StorePhoneFragment.kt 'StorePage.Frame(strip) {' 'StorePage.Frame(strip, actions = col) {' || M=$((M+1))
mutate "a tab gets a minimum height back" lib/StoreBar.kt 'base.storeSurface(style, on).testTag(tag)' 'base.storeSurface(style, on).heightIn(min = 120.dp).testTag(tag)' || M=$((M+1))
mutate "a button gets a minimum height back" lib/StoreBar.kt '.storeSurface(style, false, if (onClick != null) fill else Ink.DISABLED)' '.heightIn(min = 120.dp).storeSurface(style, false, if (onClick != null) fill else Ink.DISABLED)' || M=$((M+1))
mutate "the app opens on Search, not Cloud" store/build.json '"default_section": "cloud"' '"default_section": "search"' || M=$((M+1))
mutate "Search goes back under Phone" store/build.json '"phone",
      "search",' '"phone",' || M=$((M+1))
mutate "Phone loses its Installed tab" store/build.json '"id": "installed",' '"id": "installd",' || M=$((M+1))
mutate "Torrent page dropped" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'if (selected == TAB_TORRENT) TorrentPage()' 'Unit' || M=$((M+1))
mutate "Cloud loses the feed buttons" lib/StoreCloudFragment.kt 'StoreFeedPanels.Buttons { feedOpen = it }' 'Unit' || M=$((M+1))
mutate "Access page dropped" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'AccessPage(pageOf(it))' 'Unit' || M=$((M+1))
mutate "host stops drawing the page strip" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'NavSectionLayout(section, pageOf(section)' 'NavSectionLayoux(section, pageOf(section)' || M=$((M+1))
mutate "gradle stops baking the sections" store/app/build.gradle '"UI_SECTIONS_B64"' '"UI_SECTIONS"' || M=$((M+1))
A=store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt
mutate "Cloud Store drops the shelves" "$A" 'classify = StoreShelves::of' '' || M=$((M+1))
mutate "SuperApp drops the shelves" super/App.kt 'classify = com.diegonmarcos.superapp.apps.StoreShelves::of' '' || M=$((M+1))
mutate "taxonomy no longer baked for Cloud Store" lib.gradle 'aa_cloud-superapp/build.json' 'build.json' || M=$((M+1))
mutate "Cloud Store skips StoreDns" "$A" 'StoreDns.start(this)' '' || M=$((M+1))
mutate "SuperApp skips StoreDns" super/App.kt 'com.diegonmarcos.superapp.appstore.StoreDns.start(dnsCtx)' '' || M=$((M+1))
mutate "StoreDns loses the preset hook" lib/StoreDns.kt '@Volatile var resolve:' '@Volatile var resolveX:' || M=$((M+1))
mutate "Cloud Store feeds lose the bearer" "$A" 'FleetBearer.resolve(this)' '""' || M=$((M+1))
mutate "own token preferred over the fleet's" lib/FleetBearer.kt 'fromAccount(ctx).ifEmpty { fromSuperApp(ctx) }.ifEmpty { Own(ctx).token.trim() }' 'Own(ctx).token.trim().ifEmpty { fromAccount(ctx) }.ifEmpty { fromSuperApp(ctx) }' || M=$((M+1))
mutate "a token from Cloud Account labelled SuperApp" lib/FleetBearer.kt 'fromAccount(ctx).isNotEmpty() -> Source.ACCOUNT' 'fromAccount(ctx).isNotEmpty() -> Source.SUPERAPP' || M=$((M+1))
mutate "the bearer line loses Cloud Account" store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt 'Source.ACCOUNT -> "Supplied by Cloud Account' 'Source.ACCOUNT -> "Supplied by Cloud SuperApp' || M=$((M+1))
mutate "provider skips the permission re-check" super/cloud/FleetBearerProvider.kt 'checkCallingPermission(FleetConfig.PERMISSION)' 'checkCallingPermission("x")' || M=$((M+1))
mutate "provider unguarded in the manifest" super.xml 'android:authorities="${applicationId}.fleetbearer"
            android:exported="true"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' 'android:authorities="${applicationId}.fleetbearer"
            android:exported="true"' || M=$((M+1))
mutate "provider logs the token" super/cloud/FleetBearerProvider.kt 'return Bundle().apply { putBoolean(FleetBearer.KEY_OK, true)' 'android.util.Log.i("x", token); return Bundle().apply { putBoolean(FleetBearer.KEY_OK, true)' || M=$((M+1))
mutate "Cloud Store loses the token entry" store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt 'FleetBearer.Own(' 'FleetBearer.Ownx(' || M=$((M+1))
mutate "a dp literal back in the Cloud shelf" lib/StoreCloudFragment.kt 'verticalScroll(scroll).padding(sdp(StoreDensity.S12))' 'verticalScroll(scroll).padding(14.dp)' || M=$((M+1))
mutate "a font size literal back in the Phone shelf" lib/StorePhoneFragment.kt 'color = Ink.WHITE.ink(), fontSize = StoreDensity.T_TITLE.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))' 'color = Ink.WHITE.ink(), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))' || M=$((M+1))
mutate "a note size literal back in the mesh" lib/AppsMesh.kt 'size = StoreDensity.T_BODY, color = if (gaps.isEmpty()) GREEN else RED' 'size = 13.sp.value, color = if (gaps.isEmpty()) GREEN else RED' || M=$((M+1))
mutate "density turned off" lib/StoreDensity.kt 'const val SCALE = 0.7f' 'const val SCALE = 1.0f' || M=$((M+1))
mutate "declaration deleted" lib/StoreDensity.kt 'object StoreDensity' 'object Other' || M=$((M+1))
mutate "Cloud Store hardcodes a dp" store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt 'padding(top = StoreDensity.dpValue(StoreDensity.S8).dp)' 'padding(top = 8.dp)' || M=$((M+1))
mutate "a helper scales on its own" lib/StoreBar.kt 'internal fun sdp(step: Int): Dp = StoreDensity.dpValue(step).dp' 'internal fun sdp(step: Int): Dp = step.dp' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
