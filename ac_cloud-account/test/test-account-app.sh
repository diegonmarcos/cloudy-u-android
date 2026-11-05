#!/usr/bin/env bash
# #867 Cloud Account is the Account page, split out of cloud-superapp.
#
# What this holds, statically (no build, no device, no network):
#  1. Cloud Account hosts libs:account's pages (no copy of the account code in this
#     app), owns the data (read-through off) and exports it ONLY
#     through a provider that is exported behind the fleet's signature permission.
#  2. The provider is read-only, re-checks its caller, and answers two methods (the export
#     and, #874, one granted secret), each behind a grant; the
#     copy into another host never overwrites a store that host already holds, and
#     is marked done only after the provider answered.
#  3. SuperApp opens Cloud Account from its Account entry (extapp:cloud-account, a
#     page that is NOT an action), has an external_apps row that resolves, keeps the
#     page as AccountHandoff (open Cloud Account, else install it), and wires the
#     one-shot copy and the read-through.
#  4. One package name: SuperApp's hand-off, the library, this app's build.json, the
#     external_apps row and the fleet manifest row agree, and the OPEN action SuperApp
#     sends is the one this manifest declares.
#  5. the five sections (Personal Info, Configs, Data, Connect, Activity; #1081): build.json::ui, the
#     shell is the shared island + PageTabs fed by NavDecl, every declared leaf (section,
#     page) id is a branch of MainActivity's Page(), the debug API's tabs op answers the
#     islands, and Account ▸ connect dispatches every declared way on its kind
#     (ConnectWays.handler has a branch per declared kind, none left unwired).
#  6. the vault, the grants, the setup contract.
#
# Then it plants each mutation below in a scratch copy and requires the checks to go
# red, so a check that cannot fail does not count as a check.
#
# Usage: ./test-account-app.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

ACC="$ROOT/ac_cloud-account"
LIB="$ROOT/ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp"
SUPER="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
SPBJ="$ROOT/aa_cloud-superapp/build.json"

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <acc-dir> <lib-dir> <superapp-dir> <fleet.json> <superapp build.json>  → PASS/FAIL lines, exit = #fails
check() {
  local A="$1" L="$2" P="$3" F="$4" B="$5" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local MA="$A/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
  local AP="$A/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt"
  local MF="$A/app/src/main/AndroidManifest.xml"
  local AD="$L/profile/AccountData.kt" HK="$L/profile/AccountHost.kt" CP="$L/settings/ConfigsPrefs.kt"
  local HO="$P/apps/AccountHandoff.kt" SA="$P/App.kt" SP="$P/launcher/SectionPages.kt"
  for f in "$MA" "$AP" "$MF" "$AD" "$HK" "$CP" "$HO" "$SA" "$SP" "$A/build.json" "$A/app/build.gradle" "$F" "$B"; do
    [ -f "$f" ] || { bad "missing $f"; return 99; }
  done

  # 1. hosts the lib's page, owns no account code, owns the data, exports it behind the signature permission
  strip "$MA" | grep -q 'ProfileFragment' && bad "Cloud Account still names ProfileFragment (deleted by redesign task 3)" || ok "no ProfileFragment host left"
  [ ! -e "$L/profile/ProfileFragment.kt" ] && ok "ProfileFragment is deleted from libs:account" || bad "ProfileFragment.kt still exists"
  grep -q "project(':libs:account')" "$A/app/build.gradle" && ok "app links :libs:account" || bad "app does not link :libs:account"
  if find "$A/app/src" -name '*.kt' | xargs grep -l "^package com.diegonmarcos.superapp.profile" 2>/dev/null | grep -q .; then
    bad "a copy of libs:account code lives in ac_cloud-account"; else ok "no copy of the account code in this app"; fi
  strip "$AP" | grep -Eq 'readThrough *= *false' && ok "Cloud Account owns the data (read-through off)" || bad "Cloud Account reads through to another host"
  python3 - "$MF" <<'PY' && ok "provider AccountData\$Provider: exported, authority \${applicationId}.accountdata, behind CONSTELLATION_DATA" || bad "provider missing, wrongly named, or not behind the signature permission"
import re, sys
m = open(sys.argv[1], encoding='utf-8').read()
for p in re.findall(r'<provider\b.*?/>', m, flags=re.S):
    if 'AccountData$Provider' in p:
        ok = ('android:exported="true"' in p and 'android:authorities="${applicationId}.accountdata"' in p
              and 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' in p)
        sys.exit(0 if ok else 1)
sys.exit(1)
PY

  # 2. the provider: read-only, caller re-checked, one method; the copy never overwrites
  local ad; ad="$(strip "$AD")"
  printf '%s' "$ad" | grep -q 'checkCallingPermission(FleetConfig.PERMISSION)' && ok "provider re-checks the caller (call() is not permission-checked by the framework)" || bad "provider does not re-check its caller"
  printf '%s' "$ad" | grep -q 'METHOD_EXPORT -> {' && printf '%s' "$ad" | grep -q 'METHOD_SECRET -> secretFor(' && printf '%s' "$ad" | grep -q 'else -> error("unknown method $method")' && ok "provider answers the export and one secret, nothing else" || bad "provider answers more (or less) than the export and one secret"
  printf '%s' "$ad" | grep -q '!AccountVault(ctx).grants.allow(who, GRANT_EXPORT)' && printf '%s' "$ad" | grep -q 'caller != ctx.packageName && !vault.grants.allow(caller, path)' && ok "both methods are behind the Secrets grants (#874)" || bad "a provider method is not behind a grant"
  printf '%s' "$ad" | sed -n '/class Provider/,$p' | grep -Eq 'ConfigsPrefs|\.json *=|store\.write|\.edit\(|AccountData\.apply|apply\(ctx' && bad "provider writes or reads outside the export" || ok "provider does not write"
  printf '%s' "$ad" | grep -q 'if (prefs.localJson.isBlank())' && ok "configs copied only into an empty blob" || bad "configs copy can overwrite"
  printf '%s' "$ad" | grep -q 'if (model.store.read(s) != null) continue' && ok "slots copied only into an empty slot" || bad "slot copy can overwrite"
  printf '%s' "$ad" | grep -q 'if (sp.all.keys.any { it !in generated }) return false' && ok "profile copied only into an empty profile" || bad "profile copy can overwrite"
  printf '%s' "$ad" | sed -n '/fun migrate(/,/^    }/p' | python3 -c "
import sys
s = sys.stdin.read(); f = s.find('val b = fetch(ctx) ?: return emptyList()'); d = s.find('putBoolean(K_DONE, true)')
sys.exit(0 if 0 <= f < d else 1)" \
    && ok "migration is marked done only after the provider answered" || bad "migration can be marked done without an answer"
  printf '%s' "$ad" | grep -q 'ctx.packageName == PKG' && ok "Cloud Account never reads from itself" || bad "Cloud Account could read from itself"
  strip "$CP" | grep -q 'AccountData.configsOrRemote(appContext, localJson)' && ok "ConfigsPrefs.json reads through when its own blob is empty" || bad "ConfigsPrefs does not read through"
  strip "$HK" | grep -q 'var readThrough: Boolean = false' && ok "read-through defaults off" || bad "read-through is not off by default"

  # 3. SuperApp: entry, row, hand-off, copy
  python3 - "$B" <<'PY' && ok "Configs Account entry launches extapp:cloud-account and is not an action" || bad "Account entry does not launch extapp:cloud-account (or is_action moves it to the Actions row)"
import json, sys
b = json.load(open(sys.argv[1]))
pg = [p for s in b['ui']['sections'] if s['id'] == 'config' for p in s['pages'] if p['id'] == 'profile']
sys.exit(0 if len(pg) == 1 and pg[0].get('action') == 'extapp:cloud-account' and not pg[0].get('is_action') else 1)
PY
  python3 - "$B" "$A/build.json" <<'PY' && ok "external_apps[cloud-account] resolves: package, folder and APK name match the app" || bad "external_apps[cloud-account] missing or disagrees with ac_cloud-account/build.json"
import json, sys
b = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2]))
r = [e for e in b['ui']['external_apps'] if e['id'] == 'cloud-account']
ok = (len(r) == 1 and r[0]['hub_package'] == r[0]['install_package'] == a['android']['application_id']
      and r[0]['folder'] == 'sys_settings' and r[0]['install_apk_url'].endswith('/' + a['release']['gh_release']['asset_name']))
sys.exit(0 if ok else 1)
PY
  strip "$SP" | grep -q 'pageId == "profile" *-> *com.diegonmarcos.superapp.apps.AccountHandoff.page()' && ok "SuperApp's Account page goes through the hand-off" || bad "SuperApp's Account page bypasses the hand-off"
  strip "$HO" | grep -q 'if (installed) OpenCloudAccount() else InstallCloudAccount()' && strip "$HO" | grep -q 'AppInstall.start(ctx, PKG, LABEL)' && ! strip "$HO" | grep -q 'ProfileFragment' && ok "hand-off opens Cloud Account, else offers its install (no embedded page)" || bad "hand-off does not open-or-install Cloud Account"
  local sa; sa="$(strip "$SA")"
  printf '%s' "$sa" | grep -q 'readThrough *= *true' && ok "SuperApp reads through to Cloud Account" || bad "SuperApp does not read through"
  printf '%s' "$sa" | grep -q 'AccountData.migrate(applicationContext)' && ok "SuperApp runs the one-shot copy at start" || bad "SuperApp never runs the copy"

  # 4. one package name, one OPEN action
  local appid pkg libpkg action
  appid="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['android']['application_id'])" "$A/build.json")"
  libpkg="$(printf '%s' "$ad" | sed -n 's/.*const val PKG = "\([^"]*\)".*/\1/p')"
  [ -n "$appid" ] && [ "$appid" = "$libpkg" ] && ok "library package = build.json application_id ($appid)" || bad "library package '$libpkg' != application_id '$appid'"
  strip "$HO" | grep -q 'const val PKG = AccountData.PKG' && ok "hand-off uses the library's package" || bad "hand-off names its own package"
  grep -q "\"package\":\"$appid\"" "$F" && ok "fleet manifest lists $appid (installable and updated by the Store)" || bad "fleet manifest has no row for $appid"
  action="$(strip "$HO" | sed -n 's/.*ACTION_OPEN = "\([^"]*\)".*/\1/p')"
  [ "$action" = '$PKG.OPEN' ] && action="$appid.OPEN"
  grep -q "<action android:name=\"$action\" */>" "$MF" && ok "manifest answers SuperApp's $action" || bad "manifest does not declare the action SuperApp sends ('$action')"

  # 5. redesign task 3: five islands declared in build.json::ui, drawn by the shared island + strips
  python3 - "$A/build.json" <<'PY' && ok "ui.bottom_nav = vault, config, connect, devices, activity (+ the app's own configs); ui.sections hold every page" || bad "build.json ui.bottom_nav/sections are not the five sections"
import json, sys
ui = json.load(open(sys.argv[1], encoding='utf-8')).get('ui') or {}
# a0_docs/eng-specs/cloud-account-ia.md: a page's own `pages` is a second strip (Autofill, Phone, Setup)
want = {'vault': ['ids', 'cards', 'secrets', 'autocomplete'],
        'config': ['android', 'desktop', 'c3', 'repos'],
        'connect': ['status', 'auth', 'fleetsetup'],
        'devices': ['mesh', 'peer-control', 'backup', 'files', 'working', 'diff'],
        'activity': ['activity'],
        'configs': ['general', 'perms', 'about']}
strips = {'autocomplete': ['personal', 'sites', 'snippets'], 'android': ['cloud-apps', 'phone-apps', 'runbook'], 'c3': ['infra', 'containers'],
          'secrets': ['providers', 'keys', 'tokens', 'grants', 'import', 'entries'], 'backup': ['databases', 'bundle', 'me'],
          'status': ['connections', 'apps', 'endpoints'], 'auth': ['hub', 'signin', 'settings'], 'fleetsetup': ['services', 'drive', 'push', 'perms', 'adb-shell']}
got = {s.get('id'): [p.get('id') for p in s.get('pages') or []] for s in ui.get('sections') or []}
gotstrips = {p.get('id'): [q.get('id') for q in p.get('pages')] for s in ui.get('sections') or [] for p in s.get('pages') or [] if p.get('pages')}
ok = (ui.get('bottom_nav') == ['vault', 'config', 'connect', 'devices', 'activity'] and got == want and gotstrips == strips and ui.get('default_section') == 'vault'
      and isinstance(ui.get('account', {}).get('forges'), list)
      and ui.get('account', {}).get('connect', {}).get('file', {}).get('kind') == 'vault_file')
sys.exit(0 if ok else 1)
PY
  local bg; bg="$(cat "$A/app/build.gradle")"
  printf '%s' "$bg" | grep -q 'UI_BOTTOM_NAV' && printf '%s' "$bg" | grep -q 'UI_SECTIONS_B64' && printf '%s' "$bg" | grep -q "project(':libs:bottomnav')" && ok "build.gradle bakes the declaration and links libs:bottomnav" || bad "build.gradle does not bake UI_BOTTOM_NAV/UI_SECTIONS_B64 or link libs:bottomnav"
  local ma; ma="$(strip "$MA")"
  printf '%s' "$ma" | grep -q 'NavBarBottomHost(' && printf '%s' "$ma" | grep -q 'NavDecl.fromBuildConfig(' && printf '%s' "$ma" | grep -q 'islandEntries' && [ "$(printf '%s' "$ma" | grep -c 'NavBarTopSecondRow(')" -ge 2 ] && ok "MainActivity draws the island, the page strip and the second strip from the baked NavDecl" || bad "MainActivity does not feed the shared island/strips from NavDecl"
  python3 - "$A/build.json" "$MA" <<'PY' && ok "every declared section is a branch of MainActivity's Page() and every page branch names a declared page" || bad "MainActivity's Page() and build.json::ui.sections disagree"
import json, re, sys
ui = json.load(open(sys.argv[1], encoding='utf-8'))['ui']
src = open(sys.argv[2], encoding='utf-8').read()
body = src[src.index('private fun Page('):src.index('companion object')]
secs = re.findall(r'^            "(\w+)" -> ', body, flags=re.M)
pages = re.findall(r'^                "(\w+)" -> ', body, flags=re.M)
def leaves(pages):
    for p in pages:
        if p.get('pages'): yield from leaves(p['pages'])
        else: yield p['id']
decl = {s['id']: list(leaves(s.get('pages') or [])) for s in ui['sections']}
# every declared section has its branch; a page branch names a declared LEAF page (an undeclared one
# is dead code the island can never reach); a section's remaining leaves fall to its `else`
ok = set(secs) == set(decl) and bool(pages) and all(any(p in ps for ps in decl.values()) for p in pages)
sys.exit(0 if ok else 1)
PY
  strip "$AP" | grep -q 'nav = { navJson() }' && strip "$AP" | grep -q 'MainActivity.NAV.bottomSections()' && strip "$L/profile/AccountDebugApi.kt" | grep -q 'AccountHost.nav?.invoke()?.let { o.put("islands", it) }' && ok "the debug API's tabs op answers the five islands and their pages" || bad "the tabs op does not answer the declared islands"
  local CW="$L/profile/ConnectWays.kt" PG="$L/profile/AccountPages.kt"
  for f in "$CW" "$PG"; do [ -f "$f" ] || { bad "missing $f"; return 99; }; done
  python3 - "$A/build.json" "$CW" "$PG" <<'PY' && ok "Account ▸ connect: every declared way's kind has its own branch in ConnectWays.handler, and the page dispatches on handler(way.kind)" || bad "the connect dispatch is not by kind (a declared kind has no branch, or the page does not dispatch on it)"
import json, re, sys
acc = json.load(open(sys.argv[1], encoding='utf-8'))['ui']['account']
kinds = [w for f in acc['forges'] for w in f.get('ways') or []] + [acc['connect']['file']['kind']]
cw = open(sys.argv[2], encoding='utf-8').read(); pg = open(sys.argv[3], encoding='utf-8').read()
m = re.search(r'fun handler\(kind: String\): Handler = when \(kind\) \{(.*?)\n    \}', cw, flags=re.S)
if not m: sys.exit(1)
consts = dict(re.findall(r'const val (\w+) = "(\w+)"', cw))
branches = {consts.get(k, k) for k in re.findall(r'^\s+(\w+) -> Handler\.', m.group(1), flags=re.M)}
ok = set(kinds) <= branches and 'when (val h = ConnectWays.handler(way.kind))' in pg
sys.exit(0 if ok else 1)
PY
  strip "$CW" | grep -Eq 'Log\.[a-z]\(|println\(' && bad "ConnectWays logs (a token could reach the log)" || ok "ConnectWays never logs"
  strip "$CW" | grep -q 'putConnection("forge.${f.id}.token", token)' && strip "$CW" | grep -q 'if (o.ok && file)' && ok "a pasted token is filed only after it read the vault" || bad "a token is filed before (or without) a successful read"

  # 6. #873/#874 the vault, the pages, the setup contract
  local VT="$L/settings/AccountVault.kt" PL="$L/profile/SetupPlan.kt" FS="$L/profile/FleetSetup.kt" MG="$L/profile/AccountMigrate.kt"
  for f in "$VT" "$PL" "$FS" "$MG" "$L/profile/AccountVaultTabs.kt" "$L/profile/FleetSetupTab.kt"; do [ -f "$f" ] || { bad "missing $f"; return 99; }; done
  local vt; vt="$(strip "$VT")"
  printf '%s' "$vt" | grep -q 'AES/GCM/NoPadding' && printf '%s' "$vt" | grep -q 'PBKDF2WithHmacSHA256' && printf '%s' "$vt" | grep -q 'if (v > AccountVault.VERSION) throw Refused' && [ "$(printf '%s' "$vt" | grep -o 'updateAAD(aad(' | wc -l)" -ge 2 ] && ok "the bundle is AES-GCM under a PBKDF2 key, versioned, its header authenticated" || bad "the bundle cipher lost a property (GCM, PBKDF2, the version gate, the AAD)"
  printf '%s' "$vt" | grep -q 'class SecretGrants' && printf '%s' "$vt" | grep -q 'fun revoke(' && printf '%s' "$vt" | grep -q '!secret || caller == c.packageName || grants.allow(caller, "$store.$key")' && ok "Secrets: per-package grants, revocable, enforced by the setup authorizer" || bad "the grants are not revocable or not enforced by the setup authorizer"
  if grep -En 'Log\.[a-z]\(|println\(|printStackTrace' "$VT" "$PL" "$FS" "$MG" "$L/profile/AccountVaultTabs.kt" "$L/profile/FleetSetupTab.kt" >/dev/null 2>&1; then bad "a vault or setup file logs (values must never be logged)"; else ok "no vault or setup file logs"; fi
  strip "$AP" | grep -q 'AccountVault.install(this)' && strip "$AP" | grep -q 'AccountMigrate.run(this)' && ok "App installs the grant-aware authorizer and takes in what SuperApp kept" || bad "App.kt does not install the vault authorizer or run the migration"
  local FB="$P/cloud/FleetBearerProvider.kt"
  [ -f "$FB" ] && strip "$FB" | grep -q 'AccountData.secret(ctx, "fleet.bearer")' && ok "SuperApp's fleet bearer reads through Cloud Account first" || bad "SuperApp's FleetBearerProvider does not read the fleet bearer through Cloud Account"

  # 7. redesign task 4: Data > Devices files/working/diff
  local PP="$L/profile/ProfilesPages.kt"
  [ -f "$PP" ] || { bad "missing $PP"; return 99; }
  local ma2; ma2="$(strip "$MA")"
  for pg in 'ProfilesDevicesPage()' 'ProfilesWorkingPage()' 'ProfilesDiffPage(model)'; do
    printf '%s' "$ma2" | grep -qF "$pg" && ok "MainActivity mounts $pg" || bad "MainActivity does not mount $pg"
  done
  strip "$PP" | grep -q '{ confirmDelete = id }' && strip "$PP" | grep -q 'KitConfirmDialog(' && ok "Delete opens a confirm; only the dialog's confirm deletes" || bad "Delete can fire without a confirm"
  strip "$PP" | grep -q 'InfoMask.declared.hides(' && [ "$(strip "$PP" | grep -c 'if (secret) KitFingerprint(')" -ge 2 ] && strip "$PP" | grep -q 'if (secret) "file and phone hold a secret: fingerprints only" else' && ok "diff shows secret-class rows as presence only (fingerprints, never the value)" || bad "diff may render a secret-class value"
  strip "$PG" | grep -q 'profilesDriftCount(ctx, model)' && ! strip "$PG" | grep -q 'n/a (task 4)' && ok "Account profile's Drift tile reads the diff count" || bad "Drift tile is not wired to the diff count"
  return $fails
}

echo "── real tree ──"
check "$ACC" "$LIB" "$SUPER" "$FLEET" "$SPBJ"; REAL=$?

# ── mutations: each must turn the checks red ──
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file-under-copy-root> <old> <new>
  local label="$1" which="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  cp -r "$ACC" "$d/acc"; cp -r "$LIB" "$d/lib"; cp -r "$SUPER" "$d/super"; cp "$FLEET" "$d/fleet.json"; cp "$SPBJ" "$d/build.json"
  python3 - "$d/$which" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d/acc" "$d/lib" "$d/super" "$d/fleet.json" "$d/build.json" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
D=lib/profile/AccountData.kt
mutate "provider drops the permission" acc/app/src/main/AndroidManifest.xml 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA" />' '/>' || M=$((M+1))
mutate "provider caller check removed" "$D" 'ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED' 'false' || M=$((M+1))
mutate "provider answers any method" "$D" 'else -> error("unknown method $method")' 'else -> Bundle()' || M=$((M+1))
mutate "export without a grant" "$D" '!AccountVault(ctx).grants.allow(who, GRANT_EXPORT)' 'false' || M=$((M+1))
mutate "secret without a grant" "$D" 'caller != ctx.packageName && !vault.grants.allow(caller, path)' 'false' || M=$((M+1))
mutate "grants no longer revocable" lib/settings/AccountVault.kt 'fun revoke(' 'fun revokeX(' || M=$((M+1))
mutate "setup authorizer lets a secret through" lib/settings/AccountVault.kt '!secret || caller == c.packageName || grants.allow(caller, "$store.$key")' 'true' || M=$((M+1))
mutate "bundle loses its version gate" lib/settings/AccountVault.kt 'if (v > AccountVault.VERSION) throw Refused' 'if (false) throw Refused' || M=$((M+1))
mutate "bundle loses its authenticated header" lib/settings/AccountVault.kt 'updateAAD(aad(AccountVault.VERSION))' '' || M=$((M+1))
mutate "a vault file logs a value" lib/settings/AccountVault.kt 'fun connections(): JSONObject = parse(prefs.json)' 'fun connections(): JSONObject = parse(prefs.json).also { android.util.Log.d("x", it.toString()) }' || M=$((M+1))
mutate "App stops installing the authorizer" acc/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt 'AccountVault.install(this)' 'Unit' || M=$((M+1))
mutate "FleetBearerProvider stops reading through" super/cloud/FleetBearerProvider.kt 'AccountData.secret(ctx, "fleet.bearer")' '""' || M=$((M+1))
mutate "configs copy overwrites" "$D" 'if (prefs.localJson.isBlank())' 'if (true)' || M=$((M+1))
mutate "slot copy overwrites" "$D" 'if (model.store.read(s) != null) continue' '' || M=$((M+1))
mutate "profile copy overwrites" "$D" 'if (sp.all.keys.any { it !in generated }) return false' '' || M=$((M+1))
mutate "done set before the answer" "$D" 'val b = fetch(ctx) ?: return emptyList()' 'ctx.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply(); val b = fetch(ctx) ?: return emptyList()' || M=$((M+1))
mutate "Cloud Account reads through" acc/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt 'readThrough = false' 'readThrough = true' || M=$((M+1))
mutate "Account page bypasses hand-off" super/launcher/SectionPages.kt 'AccountHandoff.page()' 'ProfileFragment.newInstance()' || M=$((M+1))
mutate "SuperApp never copies" super/App.kt 'AccountData.migrate(applicationContext)' 'Unit' || M=$((M+1))
mutate "Account entry loses its action" build.json '"action": "extapp:cloud-account",' '' || M=$((M+1))
mutate "package name drifts" lib/profile/AccountData.kt 'const val PKG = "com.diegonmarcos.cloudaccount"' 'const val PKG = "com.diegonmarcos.account"' || M=$((M+1))
mutate "OPEN action dropped" acc/app/src/main/AndroidManifest.xml '<action android:name="com.diegonmarcos.cloudaccount.OPEN" />' '' || M=$((M+1))
mutate "island replaced by a plain box" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'NavBarBottomHost(' 'Box(' || M=$((M+1))
mutate "island no longer fed by NavDecl" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'NavDecl.fromBuildConfig(' 'NavDeclx.fromBuildConfig(' || M=$((M+1))
mutate "a section leaves build.json" acc/build.json '"connect", "devices", "activity"' '"connect", "activity"' || M=$((M+1))
mutate "a page leaves build.json" acc/build.json '"id": "working",' '"id": "workingx",' || M=$((M+1))
mutate "a strip leaves build.json" acc/build.json '"id": "android",' '"id": "androidx",' || M=$((M+1))
mutate "connect dispatch replaced by a literal" lib/profile/ConnectWays.kt 'fun handler(kind: String): Handler = when (kind) {' 'fun handler(kind: String): Handler = Handler.File
    private fun handlerOld(kind: String): Handler = when (kind) {' || M=$((M+1))
mutate "a declared kind loses its branch" lib/profile/ConnectWays.kt '        GITEA_TOKEN -> Handler.GiteaToken
' '' || M=$((M+1))
mutate "the page stops dispatching on kind" lib/profile/AccountPages.kt 'when (val h = ConnectWays.handler(way.kind))' 'when (val h = ConnectWays.handler("vault_file"))' || M=$((M+1))
mutate "a page id has no branch" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt '"diff" -> ProfilesDiffPage(model)' '"diffx" -> ProfilesDiffPage(model)' || M=$((M+1))
mutate "devices page not mounted" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt '"files" -> ProfilesDevicesPage()' '"devices" -> AccountPlaceholderPage(section, page, "task 4")' || M=$((M+1))
mutate "working page not mounted" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt '"working" -> ProfilesWorkingPage()' '"working" -> AccountPlaceholderPage(section, page, "task 4")' || M=$((M+1))
mutate "diff page not mounted" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt '"diff" -> ProfilesDiffPage(model)' '"diff" -> AccountPlaceholderPage(section, page, "task 4")' || M=$((M+1))
mutate "Delete fires without confirm" lib/profile/ProfilesPages.kt '{ confirmDelete = id }' '{ io { vault.delete(id).optString("result") } }' || M=$((M+1))
mutate "diff draws a secret-class value" lib/profile/ProfilesPages.kt 'if (secret) "file and phone hold a secret: fingerprints only" else' 'if (false) "file and phone hold a secret: fingerprints only" else' || M=$((M+1))
mutate "diff drops a secret fingerprint" lib/profile/ProfilesPages.kt 'if (secret) KitFingerprint(f.a' 'if (false) KitFingerprint(f.a' || M=$((M+1))
mutate "Drift tile says n/a again" lib/profile/AccountPages.kt 'runCatching { profilesDriftCount(ctx, model) }' 'runCatching { 0 }' || M=$((M+1))
mutate "tabs op stops answering the islands" lib/profile/AccountDebugApi.kt 'AccountHost.nav?.invoke()?.let { o.put("islands", it) }' 'Unit' || M=$((M+1))
mutate "token filed before the read" lib/profile/ConnectWays.kt 'if (o.ok && file)' 'if (file)' || M=$((M+1))
mutate "the strip is dropped" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'NavBarTopSecondRow(' 'Column(' || M=$((M+1))
mutate "hand-off embeds a page again" super/apps/AccountHandoff.kt 'if (installed) OpenCloudAccount() else InstallCloudAccount()' 'OpenCloudAccount()' || M=$((M+1))
mutate "gradle stops baking the sections" acc/app/build.gradle '"UI_SECTIONS_B64"' '"UI_SECTIONS"' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
