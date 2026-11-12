#!/usr/bin/env bash
# Tester (#566/#569/#570; ported by the Cloud Account redesign task 8): Account > connect > Vault configs, and the
# cockpit's apps (the vault-backed fleet configurator).
#
# The page this first pinned (ProfileFragment, with FleetCockpitView and the Setup cockpit hero) was deleted by the
# redesign (a0_docs/eng-specs/cloud-account-redesign.md section 7). The vault fetch is ConnectWays.land now (every
# sign-in's one landing), the device pick is AccountDevice / the Account profile page, and the cockpit is Kit
# composables over the same VaultCockpit layout. So this reads those, not a file that is gone.
#
# The behaviour (wire format, section grouping, failure hints, device selection, mesh ownership by address, the
# per-section comparisons and applies) is executed by app/src/test/.../profile/VaultConnectTest.kt and
# VaultCockpitTest.kt. This file pins what a JVM test cannot see:
#   T1  every build.json::ui.vault_connect key reaches BuildConfig, and the Kotlin reads each BuildConfig field it
#       bakes (data, not literals); the route's endpoints are the fleet's ONE shared declaration
#   T2  every R.string.vault_* the Kotlin uses exists in EVERY locale file
#   T3  (#778) the cockpit's apps are Account > Runtime / Drift's apps: the runtime reader and pusher dispatch on
#       each declared `apply` (and every declared one has a reader), labels live only in build.json
#   T4  READING WRITES NOTHING: the fetch path and the runtime readers run no apply and touch no app's store; the
#       few things a sign-in or a fetch files are enumerated; every write is AccountRuntime.push, reached only
#       from the model's server -> runtime, reached only from a tap (or its debug op)
#   T5  the one-time code and the browser session are never stored; a pasted credential's box is emptied once sent
#   T6  the device is CHOSEN from the vault's peers (a typed id is only a new device's NAME): only its id is
#       persisted, and no address, key or hostname is a Kotlin literal on the mesh path
#   T7  the apps reuse the #565 inventory parser - no second parser, no installer
#   T8  the cockpit chrome is the Kit's: state lights and colours come from the palette, no private colour, no
#       animation (Power-Saving-safe); badge icons declared as data and present as drawables
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GR="$APP/../ab_cloud-libs-shared/libs/account/build.gradle"   # #867 baked by libs:account
PKG="$APP/../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
CW="$PKG/ConnectWays.kt"       # every way's fetch and landing
AP="$PKG/AccountPages.kt"      # the Account pages (profile, connect, settings)
PP="$PKG/ProfilesPages.kt"
CP="$PKG/VaultCockpit.kt"
AR="$PKG/AccountRuntime.kt"
AT="$PKG/AccountTabs.kt"
AM="$PKG/AccountModel.kt"
# #587 the vault route and the sign-in live in the fleet's libs:auth; the endpoints in the ONE shared declaration.
LIB="$APP/../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth"
SHARED="$APP/../ab_cloud-libs-shared/build.json"
VC="$LIB/VaultConnect.kt"
RES="$APP/../ab_cloud-libs-shared/libs/account/src/main/res"   # #867 strings/ids moved with the page
APPRES="$APP/app/src/main/res"
for f in "$BJ" "$GR" "$CW" "$AP" "$PP" "$CP" "$AR" "$AT" "$AM" "$VC" "$SHARED" "$RES/values/strings.xml"; do
    [ -f "$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }
done

# Code only - whole-line comments dropped, so prose about what must not happen
# does not read as it happening.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }
# A member function of an object (4-space indent), to its closing `    }`; an expression body to the next blank line.
memof() { awk -v n="$2" '
    f==0 && $0 ~ "^    (private |internal |override )?fun " n "\\(" { f=1; brace=($0 ~ /\{[[:space:]]*$/); print; next }
    f==1 { if (!brace && $0 ~ /^[[:space:]]*$/) exit; print; if (brace && $0 ~ /^    }[[:space:]]*$/) exit }' "$1"; }

echo "== T1: build.json::ui.vault_connect -> BuildConfig -> Kotlin =="
KEYS=$(jq -r '.ui.vault_connect | keys[] | select(startswith("_") | not)' "$BJ" 2>/dev/null)
[ -n "$KEYS" ] && ok "T1: ui.vault_connect declares $(echo $KEYS | wc -w) keys" \
               || bad "T1: build.json has no ui.vault_connect"
for k in $KEYS; do
    v=$(jq -c --arg k "$k" '.ui.vault_connect[$k]' "$BJ")
    [ -n "$v" ] && [ "$v" != null ] || bad "T1: ui.vault_connect.$k is empty"
    grep -qE "vaultConnect\.$k\b" "$GR" && ok "T1: gradle reads ui.vault_connect.$k" \
                                     || bad "T1: gradle never reads ui.vault_connect.$k"
done
FIELDS=$(grep -oE '"UI_VAULT_CONNECT_[A-Z_0-9]+"' "$GR" | tr -d '"' | sort -u)
[ "$(echo "$FIELDS" | grep -c .)" = "$(echo $KEYS | wc -w)" ] \
    && ok "T1: one BuildConfig field per declared key" \
    || bad "T1: BuildConfig fields ($(echo $FIELDS)) do not match the declared keys ($(echo $KEYS))"
for f in $FIELDS; do
    grep -q "BuildConfig.$f" "$CP" && ok "T1: the cockpit layout reads $f" \
                                   || bad "T1: $f is baked but never read"
done
# #587: the route's endpoints, timeouts and schema versions are the fleet's ONE
# declaration (ab_cloud-libs-shared/build.json::auth.vault_connect), read through
# libs:auth's AuthDeclaration - never a second copy in this app.
for k in base_url start_path fetch_path connect_timeout_ms read_timeout_ms known_schema_versions; do
    jq -e --arg k "$k" '.auth.vault_connect[$k] | select(. != null and . != "")' "$SHARED" >/dev/null \
        && ok "T1: shared auth.vault_connect.$k declared" || bad "T1: shared build.json lacks auth.vault_connect.$k"
    jq -e --arg k "$k" '.ui.vault_connect[$k]' "$BJ" >/dev/null 2>&1 \
        && bad "T1: ui.vault_connect.$k is ALSO declared in this app - two declarations" || ok "T1: $k lives only in the shared declaration"
done
# The Account code names no endpoint: it lands what the route returned and reads the schema gate off the lib.
CWC=$(codeof "$CW")
grep -qF 'VaultConnect.knownSchemaVersions' <<<"$CWC" && ok "T1: ConnectWays reads the schema versions off the lib's declaration" || bad "T1: ConnectWays does not read VaultConnect.knownSchemaVersions"
grep -qE 'https?://|/c3-infra|/vault/' <<<"$(codeof "$CW" "$AP" "$PP")" && bad "T1: an endpoint is a Kotlin literal in the Account pages" || ok "T1: no endpoint is a Kotlin literal in the Account pages"
grep -q 'AuthDeclaration.knownSchemaVersions' "$VC" && ok "T1: the schema gate reads the shared declaration" || bad "T1: VaultConnect does not read the shared schema versions"

echo "== T2: every vault string exists in every locale =="
USED=$(grep -ohE 'R\.string\.vault_[a-z_]+' "$CW" "$AP" "$PP" "$VC" "$CP" | sed 's/R\.string\.//' | sort -u)
[ -n "$USED" ] && ok "T2: $(echo "$USED" | wc -l) vault strings used" || bad "T2: no R.string.vault_* used - labels are literals"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T2: $s missing from ${loc#$APP/}"
    done
done
[ "$FAIL" = 0 ] && ok "T2: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

echo "== T3: the cockpit's apps are Account > Runtime / Drift's apps (#778), and they are data =="
AD="$PKG/AccountDrift.kt"
# #783 Runtime reads, and Drift compares, the cockpit layout's apps: first the layout, then every fleet app's settings.
grep -qF 'VaultCockpit.layout.sections.map { section ->' "$AR" && grep -qF 'for (section in m.apps) {' "$AT" \
    && ok "T3: Runtime reads, and draws, every app of the baked layout" || bad "T3: Runtime does not iterate ui.vault_connect.cockpit.sections"
grep -qF 'VaultCockpit.layout.sections.map { AccountDrift.App(it.id, it.label, it.vault) }' "$AM" \
    && ok "T3: Drift's apps - which vault sections each consumes - are the same layout" || bad "T3: Drift's app ownership is not the cockpit layout"
grep -qF 'val byApp = AccountDrift.byApp(fields, model.apps)' "$PP" \
    && ok "T3: Profiles > diff groups its rows by those apps" || bad "T3: Profiles > diff does not group by the model's apps"
# The reader dispatches on each section's declared APPLIER (`apply`); a `reports: false` app has none.
DECLARED=$(jq -r '.ui.vault_connect.cockpit.sections[] | select(.runtime.reports != false) | .apply' "$BJ" | sort)
DISPATCHED=$(awk '/    private fun readOne\(/{f=1} f&&/return when \(section.apply\) \{/{g=1;next} g&&/^            else ->/{exit} g' "$AR" | grep -oE '^            "[a-z-]+" ->' | sed -E 's/^ *"([a-z-]+)" ->/\1/' | sort)
[ -n "$DISPATCHED" ] && ok "T3: the runtime reader dispatches on $(echo $DISPATCHED | wc -w) declared appliers" || bad "T3: no when(section.apply) dispatch found"
for id in $DISPATCHED; do
    grep -qx "$id" <<<"$DECLARED" && ok "T3: read app '$id' is declared" || bad "T3: the reader dispatches on '$id', which build.json does not declare"
done
for id in $DECLARED; do
    grep -qx "$id" <<<"$DISPATCHED" && ok "T3: declared app '$id' has a reader" || bad "T3: build.json declares '$id' but nothing reads it"
done
LABELS=$(jq -r '.ui.vault_connect.cockpit.sections[].label' "$BJ")
while IFS= read -r l; do
    grep -qF "\"$l\"" <<<"$(codeof "$CW"; codeof "$AP"; codeof "$PP"; codeof "$AR"; codeof "$AT")" && bad "T3: section label '$l' is also a Kotlin literal" || ok "T3: label '$l' lives only in build.json"
done <<< "$LABELS"
VAULT_IDS=$(jq -r '.ui.vault_connect.cockpit.sections[].vault[]' "$BJ" | sort -u)
# #695 the vault re-lettered E0_configs/ -> Shared/configs/; the old path made this
# cross-check silently skip (a moved path disarms a tester).
SCHEMA="$APP/../../cloud-vault/Shared/configs/schema.json"
[ -f "$SCHEMA" ] || SCHEMA="$(cd "$APP/../.." 2>/dev/null && pwd)/cloud-vault/Shared/configs/schema.json"
if [ -f "$SCHEMA" ]; then
    for v in $VAULT_IDS; do
        if jq -e --arg v "$v" '.sections[] | select(.id == $v)' "$SCHEMA" >/dev/null; then ok "T3: vault section '$v' exists in cloud-vault schema.json"
        elif jq -e --arg v "$v" '.ui.profile.infos.schema.sections[] | select(.id == $v and .staged == true)' "$BJ" >/dev/null; then ok "T3: vault section '$v' is staged (declared staged in ui.profile.infos.schema, not yet in cloud-vault schema.json)"
        else bad "T3: cockpit names vault section '$v', which cloud-vault schema.json does not declare"; fi
    done
else
    ok "T3: (cloud-vault checkout not beside this repo - vault section ids not cross-checked here)"
fi

echo "== T4: reading writes nothing; every apply is a tap =="
# The fetch path (VaultConnect.kt, ConnectWays's fetch and landing functions) and the runtime readers may run no
# apply and touch no app's store. A sign-in files its bearer (signedIn) and a read files the token that proved
# itself (fetchWith, giteaToken) in the vault - enumerated below, nothing else.
FETCH_FNS=$(for fn in land fetchWith fetchNow ghLogin ghPrompt pat ssh importFile; do memof "$CW" "$fn"; done | codeof /dev/stdin)
READ_FNS=$(awk '/    fun read\(ctx: Context/{f=1} f{print} f&&/^    \/\/ ── pushing/{f=0}' "$AR")
[ -n "$FETCH_FNS" ] && [ -n "$READ_FNS" ] && ok "T4: found the fetch functions and the runtime readers" || bad "T4: fetch or reader functions not found"
for pat in 'ConfigAutoImport' '.edit()' 'putString' 'putSecret' 'setAutheliaCredential' 'writeText' 'hydrateFromConfig' 'setAiRouting' 'applyMesh' 'applyMail' 'setProfileField'; do
    if grep -qF "$pat" "$VC" || grep -qF "$pat" <<<"$FETCH_FNS" || grep -qF "$pat" <<<"$READ_FNS"; then
        bad "T4: the fetch/read path touches $pat"
    else
        ok "T4: no $pat on the fetch/read path"
    fi
done
# What ConnectWays files, enumerated: the last-fetch record, the token that proved itself, the Gitea token.
KEYS_FILED=$(codeof "$CW" | grep -oE 'putConnection\("[^"]+"' | sort -u | paste -sd' ' -)
[ "$KEYS_FILED" = 'putConnection("fetch.last" putConnection("forge.${f.id}.token" putConnection("forge.gitea.token"' ] \
    && ok "T4: ConnectWays files exactly fetch.last, forge.<id>.token and forge.gitea.token" || bad "T4: ConnectWays files something else in the vault: $KEYS_FILED"
[ "$(codeof "$CW" | grep -c 'setAutheliaCredential(')" = 1 ] && grep -qF 'setAutheliaCredential' <<<"$(memof "$CW" signedIn)" \
    && ok "T4: the only bearer store is the sign-in's, paired with the address it proved" || bad "T4: a bearer is stored outside signedIn"
# Every write is AccountRuntime.push; push is called only by the model's server -> runtime, which the Drift tab calls
# only from a click and the debug API only from its sync op.
[ "$(grep -c 'AccountRuntime.push(' "$AM")" = 1 ] && grep -qF 'fun pushServerToRuntime(' "$AM" \
    && ok "T4: AccountRuntime.push has one caller, the model's server -> runtime" || bad "T4: AccountRuntime.push is called from more than the model's push"
grep -q . <<<"$(grep -rn 'AccountRuntime.push(' "$APP/app/src/main/java" "$APP/../ab_cloud-libs-shared/libs/account/src/main/java" "$APP/../ac_cloud-account/app/src/main/java" | grep -v 'AccountModel.kt')" && bad "T4: something else pushes into an app" || ok "T4: nothing else pushes into an app"
PUSHERS=$(grep -rn 'pushServerToRuntime(' "$PKG" "$APP/../ac_cloud-account/app/src/main/java" | grep -v 'fun pushServerToRuntime(' | grep -v 'AccountModel.kt' | cut -d: -f1,2)
[ -n "$PUSHERS" ] && ok "T4: $(echo "$PUSHERS" | wc -l) call sites push server -> runtime" || bad "T4: nothing pushes"
# Each call site is behind a tap: the diff page's applyToPhone is reached only through io { } from a KitAction/TextButton click,
# the Drift tab's from an ActionButton click, the debug API's from its sync op.
PPC=$(codeof "$PP")
CALLS=$(grep 'applyToPhone(' <<<"$PPC" | grep -vc 'fun applyToPhone(')
[ "$CALLS" -ge 3 ] && [ "$(grep -cE 'onClick = \{ io \{ applyToPhone|\) \{ io \{ applyToPhone' <<<"$PPC")" = "$CALLS" ] \
    && ok "T4: Profiles > diff applies only from a tap (all, per app, per row)" || bad "T4: Profiles > diff applies from something other than a tap"
for ln in $(grep -n 'm.pushServerToRuntime(' "$AT" | cut -d: -f1); do
    grep -qE 'onClick = \{|ActionButton\(' <<<"$(sed -n "$((ln-1)),${ln}p" "$AT")" && ok "T4: the push at AccountTabs.kt:$ln is behind a tap" || bad "T4: the push at AccountTabs.kt:$ln is not behind a tap"
done
MESH="$APP/app/src/main/java/com/diegonmarcos/superapp/network/AccountMesh.kt"   # #867 the mesh apply is the host's (AccountHost.mesh)
grep -q 'fun applyMesh' "$MESH" && grep -q 'Config.parse' "$MESH" \
    && ok "T4: the mesh apply goes through the WireGuard parser" || bad "T4: mesh apply does not parse"

echo "== T5: the code and the browser session are never stored =="
# The mailed one-time code and the browser session are libs:auth's (VaultConnect.Auth): held in memory for one call.
grep -qE 'Prefs|putString|\.edit\(|writeText|putConnection|putSecret' <<<"$(codeof "$VC")" && bad "T5: VaultConnect stores something (a code, a session, a bearer)" \
                                                                                               || ok "T5: VaultConnect stores nothing - the code and the session live for one call"
grep -q 'class Cookie' "$VC" && grep -q '"Cookie" to cookie' "$VC" \
    && ok "T5: the session travels as a Cookie header (same gate, Remote-User)" || bad "T5: no cookie auth on the vault route"
grep -qF 'SignIn.Current.session = SignIn.Session(result.provider.id, who)' <<<"$(codeof "$CW")" \
    && grep -qF 'data class Session(val provider: String, val identity: String)' "$LIB/SignIn.kt" \
    && ok "T5: the signed-in session is a provider id and an identity - no token, cookie or code in it" || bad "T5: the session holds more than a provider and an identity"
WF=$(awk '/^private fun WayForm\(/{f=1} f{print} f&&/^}$/{exit}' "$AP")
[ "$(grep -cF 'secret = ""; pass = ""' <<<"$WF")" -ge 2 ] && grep -qF 'val s = secret; val pw = pass' <<<"$WF" \
    && ok "T5: a pasted credential's box is emptied the moment it is sent (and on cancel)" || bad "T5: the credential stays on screen after use"

echo "== T6: the device is chosen, and only its id is kept =="
grep -q 'fun selectDevice(ctx: Context, id: String)' "$CP" && ok "T6: only an id is stored for the device" \
                                                            || bad "T6: device selection API changed"
grep -q 'fun devices(bundle: JSONObject)' "$CP" && grep -q '"wg_peer"' "$CP" \
    && ok "T6: devices come from the bundle's electronics wg_peer entries" || bad "T6: devices are not derived from the vault"
grep -q 'addressesOf(conf).any { it in mine }' "$CP" \
    && ok "T6: a profile is the device's when its Address line carries the declared address" || bad "T6: mesh ownership is not by address"
if grep -qE '10\.0\.0\.[0-9]+|fd0c:1d0[01]::|termux|galaxy|surface' <<<"$(codeof "$CP")"; then
    bad "T6: an address, hostname or device name is a Kotlin literal in VaultCockpit"
else
    ok "T6: no address, hostname or device name literal in VaultCockpit"
fi
SETID=$(awk '/    fun setId\(ctx: Context, id: String\) \{/{f=1} f{print} f&&/^    }$/{exit}' "$AP" | codeof /dev/stdin)
grep -qF 'AccountVault(ctx).putConnection("device.id", id.trim())' <<<"$SETID" && grep -qF 'VaultCockpit.selectDevice(ctx, id.trim())' <<<"$SETID" \
    && [ "$(grep -c 'putConnection(' <<<"$SETID")" = 2 ] \
    && ok "T6: choosing a device persists its trimmed id (and clears the derived mark), nothing else" || bad "T6: setId persists more than the device id"
APC=$(codeof "$AP")
grep -qF 'val devices = remember { AccountDevice.declared(ctx) }' <<<"$APC" && grep -qF 'AccountDevice.setId(ctx, d.id); picking = false' <<<"$APC" \
    && ok "T6: the device is picked from the vault's declared devices (the profile page's dialog)" || bad "T6: the pick is not the vault's declared devices"
grep -qF 'enabled = typed.isNotBlank() && typed.trim() != DeviceProfile.DEFAULT_ID' <<<"$APC" \
    && ok "T6: a typed id is only a new device's name: never blank, never the DEFAULT file" || bad "T6: a typed device id can be blank or the DEFAULT profile"
grep -qF 'fun declared(ctx: Context): List<VaultCockpit.Device> =' "$AP" && grep -qF 'VaultCockpit.devices(it)' "$AP" \
    && ok "T6: AccountDevice.declared is VaultCockpit.devices over the landed vault" || bad "T6: AccountDevice.declared does not read the vault's peers"

echo "== T7: Apps reuses #565 =="
grep -q 'AppInventory.parse(' "$CP" && grep -q 'AppInventory.KIND' "$CP" \
    && ok "T7: an inventory in the vault is read by the one parser" || bad "T7: a second inventory parser"
grep -qF 'VaultCockpit.appsDeclared(declared!!, picked.first, fleet)' "$AR" && ok "T7: Runtime's apps reading is the same declared set" || bad "T7: Runtime counts apps some other way"
grep -qE 'ACTION_INSTALL_PACKAGE|installPackage\(' <<<"$(codeof "$CW" "$AP" "$PP" "$AR" "$AT" "$PKG/AppsPage.kt")" && bad "T7: the Account installs on its own" \
                                                                   || ok "T7: no installer in the Account (Setup > apps hands the missing to the Store)"

echo "== T8: the cockpit chrome is the Kit's - lights and colours from the palette, no animation, icons as data =="
IDS="$RES/values/ids.xml"
PAGES=$(codeof "$CW" "$AP" "$PP" "$PKG/AppsPage.kt" "$PKG/PermsPage.kt" "$PKG/RunbookPage.kt" "$PKG/AccountVaultTabs.kt" "$PKG/FleetSetupTab.kt" "$AT")
# A state is a KitState (ok / warn / bad / idle), drawn by the Kit from the palette: no page owns a colour.
grep -qE '0x[0-9A-Fa-f]{6,8}|Color\.parseColor|Color\(0x|#[0-9A-Fa-f]{6}' <<<"$PAGES" \
    && bad "T8: an Account page carries a colour literal - a private copy of a palette or light colour" \
    || ok "T8: no colour literal in the Account pages (palette + KitState only)"
grep -qE 'KitState\.(OK|WARN|BAD|IDLE)' <<<"$(codeof "$PP")" && grep -qE 'KitStatusBanner\(|KitStatePill\(' <<<"$(codeof "$PP")" \
    && ok "T8: the pages draw their lights through the Kit's banner and pill" || bad "T8: the pages do not draw state through the Kit"
# Power-Saving-safe: drawn once, no animation, no ticker.
grep -qE 'animate[A-Za-z]*\(|ObjectAnimator|ValueAnimator|rememberInfiniteTransition|postDelayed|AnimatedVisibility|Crossfade\(' <<<"$PAGES" \
    && bad "T8: an Account page animates or ticks - not Power-Saving-safe" || ok "T8: the Account pages draw once, no animation, no ticker"
# Badge icons are DATA: every cockpit section declares one and it is a drawable of this app.
for id in $DECLARED; do
    icon=$(jq -r --arg id "$id" '.ui.vault_connect.cockpit.sections[] | select(.id == $id) | .icon // ""' "$BJ")
    [ -n "$icon" ] || { bad "T8: section '$id' declares no icon"; continue; }
    [ -f "$APPRES/drawable/$icon.xml" ] && ok "T8: '$id' badge icon $icon is a drawable" || bad "T8: '$id' declares icon '$icon' but app res/drawable has no $icon.xml"
done
jq -e '.ui.vault_connect.cockpit.device_icons._default' "$BJ" >/dev/null && ok "T8: a default device icon is declared" || bad "T8: no device_icons._default"
for icon in $(jq -r '.ui.vault_connect.cockpit.device_icons[]' "$BJ"); do
    [ -f "$APPRES/drawable/$icon.xml" ] && ok "T8: device icon $icon is a drawable" || bad "T8: device icon '$icon' has no drawable"
done
grep -qE '"ic_[a-z_]+"' <<<"$PAGES" && bad "T8: an icon name is a Kotlin literal on an Account page" || ok "T8: icon names live only in build.json"
# #781 the keyboard is read now; what cannot be read is an app that reports nothing (cloud-drive's token).
jq -e '[.ui.vault_connect.cockpit.sections[] | select(.runtime.reports == false) | select(.observed != false)] | length == 0' "$BJ" >/dev/null \
    && jq -e '[.ui.vault_connect.cockpit.sections[] | select(.observed == false)] | length > 0' "$BJ" >/dev/null \
    && ok "T8: every app that reports nothing is declared unobservable as data (its light is Not verifiable, not a guessed colour)" \
    || bad "T8: an app that reports nothing is not declared observed:false - its light would be a guess"
# The Compose tests that draw these pages exist, and the debug API tags are the ones they read.
# #1693 the libs:account suites run in Cloud Account now (libs:account is its own).
CT="$APP/../ac_cloud-account/app/src/test/java/com/diegonmarcos/superapp/profile/AccountTabsComposeTest.kt"
[ -f "$CT" ] && grep -qF 'AccountTags.' "$CT" && ok "T8: AccountTabsComposeTest draws the Account composables and reads their tags" || bad "T8: no Compose test for the Account composables"
grep -q 'FleetCockpitViewTest\|VaultCockpitTest' "$GR" && bad "T8: a test class is named in gradle (should be found by the unit task, not listed)" || ok "T8: tests are discovered, not listed"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
