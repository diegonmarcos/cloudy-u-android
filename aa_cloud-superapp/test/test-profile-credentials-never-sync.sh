#!/usr/bin/env bash
# Tester: the Account credential fields (Account ▸ connect), and the guarantee that
# neither credential can reach the profile sync payload.
#
# The View-based page this first pinned (ProfileFragment) was deleted by the Cloud Account redesign
# (a0_docs/eng-specs/cloud-account-redesign.md section 7). Its credential rules moved with the sign-in logic:
# ConnectWays (a plain object) and AccountConnectPage / AccountProfilePage in libs:account, hosted by Cloud Account.
# Every check below that named the fragment names those now; a "must not" over a file that is gone would pass on
# nothing, so each one reads the live Account sources (ACCOUNT_SRC).
#
# WHY THIS FILE EXISTS. ProfileSync.document() POSTs the profile to
# /c3-infra-api/fleet/profile, where it is stored as a plain JSON file on
# oci-apps. The screen that builds that document now also edits an Authelia
# bearer token and the WireGuard interface private key. If either ever lands in
# the payload, a live session credential and the credential for mesh access are
# on the wire and at rest on a host — the precise failure that holding them
# on-device is meant to prevent, and one that would be completely silent.
#
# Today the document enumerates its keys, so the leak is impossible by
# construction. This tester exists for the refactor that has not happened yet:
# the day someone replaces the enumeration with a sweep of the preference map,
# T3 fails here rather than in production.
#
# Static wiring tester (no device / no gradle run): asserts the exact markers.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"        # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }

# Same as hasnt(), but over CODE only — whole-line comments are dropped first.
# These files document what they must not do ("never reads the bearer store",
# "city_from may still arrive from an older client"), and a plain grep cannot
# tell that prose from a call. Stripping comment lines keeps the assertions
# about behaviour instead of forbidding the words that explain it.
codeof()     { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$ROOT/$1"; }
hasnt_code() { codeof "$1" | grep -qF "$2" && bad "$3 ($1)" || ok "$3"; }
# Over the whole Account page surface (ACCOUNT_SRC), and loudly absent: a file that is missing is a failure, not a pass.
hasnt_acct() {   # <needle> <what it asserts>
    local f hit=""
    for f in $ACCOUNT_SRC; do
        [ -f "$ROOT/$f" ] || { bad "$2 (missing $f)"; return; }
        [ -z "$(codeof "$f" | grep -F -- "$1")" ] || hit="$hit $f"
    done
    [ -z "$hit" ] && ok "$2" || bad "$2 ($hit)"
}

PROFILE_DIR="../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
SYNC="$PROFILE_DIR/ProfileSync.kt"
# The Account page surface that replaced the fragment: the sign-in logic, the pages, the Profiles pages, the host's shell.
CW="$PROFILE_DIR/ConnectWays.kt"
ACCT_PAGES="$PROFILE_DIR/AccountPages.kt"
PROF_PAGES="$PROFILE_DIR/ProfilesPages.kt"
ACCT_MAIN="../ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
DEVICE_VAULT="$PROFILE_DIR/DeviceVault.kt"
ACCOUNT_SRC="$CW $ACCT_PAGES $PROF_PAGES $ACCT_MAIN"
for f in $ACCOUNT_SRC; do [ -f "$ROOT/$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }; done
# The Mesh capabilities MOVED here rather than being deleted; the test
# follows them, which is the only way "merged, not dropped" is provable.
WGFRAG="app/src/main/java/com/diegonmarcos/superapp/network/WireGuardFragment.kt"
# #877 the screen is Compose now: the host fragment keeps the pickers and the export; the page is network/mesh/.
MESHDIR="app/src/main/java/com/diegonmarcos/superapp/network/mesh"
MESH_WIDGETS="$MESHDIR/MeshWidgets.kt"; MESH_CTRL="$MESHDIR/MeshControlsPage.kt"; MESH_PROFILES="$MESHDIR/MeshProfilesPage.kt"
MESH_STORE="$MESHDIR/MeshStore.kt"; MESH_PORT="$MESHDIR/AndroidMeshPort.kt"; MESH_STATUS="$MESHDIR/MeshStatusPage.kt"; MESH_MODEL="$MESHDIR/MeshModel.kt"
PAGETABS="../ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt"
SECTABS="app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
# #587 the sign-in surface (the bearer dialog among it) is the fleet's shared libs:auth.
AUTH_UI="../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"
PREFS="$PROFILE_DIR/ProfilePrefs.kt"
SAFE_PREFS="../ab_cloud-libs-shared/libs/crash/src/main/java/com/diegonmarcos/superapp/crash/SafeEncryptedPrefs.kt"
CONFIGS_PREFS="../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/ConfigsPrefs.kt"
WG_PREFS="app/src/main/java/com/diegonmarcos/superapp/network/WireGuardPrefs.kt"
CONTRACT="docs/profile-sync-contract.md"

echo "== T1: the credential entry points exist, and are one-shot boxes (#573: the journey) =="
# The bearer is no longer a permanent box on the page: step 1 of the journey
# takes it in a dialog (libs:auth's BearerDialog, #587 — the same one cloud-drive
# opens), uses it for ONE fetch and, once it has proved itself, THIS fragment
# stores it paired with the address it proved.
has "$AUTH_UI"  'private fun BearerDialog'              "Authelia bearer entry (the shared dialog)"
has "$AUTH_UI"  'auth_way_bearer'                       "the bearer route is a step-1 pill"
has "$ACCT_PAGES" 'SignInWays(host = signInHost'       "the Authelia way hosts the shared sign-in surface"
has "$AUTH_UI"  'autoCorrectEnabled = false'            "the token box is kept out of the keyboard's learned words"
has "$MESH_CTRL" '"secret" ->' "WireGuard private key field (Controls tab, kind secret)"
has "$MESH_WIDGETS" "PasswordVisualTransformation"      "the private-key box is password-masked"
has "$ACCT_PAGES" 'KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)' "the Connect token and key boxes are kept out of the keyboard's learned words"
has "$ACCT_PAGES" 'visualTransformation = PasswordVisualTransformation(), keyboardOptions = keep' "and masked"
hasnt_acct "ContentType." "no autofill content type is put on a credential box (Compose autofill is opt-in; none is)"

echo "== T2: each credential reuses its EXISTING store, no parallel copy =="
# The bearer goes to the encrypted blob the config importers already read at
# auth.authelia_token; the WG key goes to the tunnel's own field. A second
# store would mean two values that can disagree, and a private key duplicated
# into a second place is strictly worse than one held in one place.
has "$CW" "setAutheliaCredential(who, result.bearer)"      "bearer writes ConfigsPrefs, paired with the address it proved"
has "$MESH_PORT" "prefs.interfacePrivateKey = t"    "WG key writes WireGuardPrefs"
has "$CONFIGS_PREFS" "SafeEncryptedPrefs.prefs("             "ConfigsPrefs is encrypted at rest (through the one safe opener, #1106)"
has "$SAFE_PREFS" "EncryptedSharedPreferences.create"        "the safe opener builds an EncryptedSharedPreferences"
has "$CONFIGS_PREFS" 'K_AUTHELIA_TOKEN = "authelia_token"'   "bearer at the existing auth.authelia_token path"
has "$WG_PREFS" 'K_IF_PRIVKEY     = "if_privkey"'            "WG key is the tunnel's own stored field"
# No copy of either credential in the synced profile store.
hasnt_code "$PREFS" "authelia"   "ProfilePrefs holds no Authelia token"
hasnt "$PREFS" "privateKey"      "ProfilePrefs holds no private key"
hasnt "$PREFS" "if_privkey"      "ProfilePrefs holds no WireGuard key"

echo "== T3: credentials CANNOT enter the sync payload =="
# 3a — proof by construction: the document names every key it puts.
has "$SYNC" 'put("profile", enforceAllowlist(' "profile object passes the allowlist filter"
has "$SYNC" "private val ALLOWED_PROFILE_KEYS" "an explicit allowlist exists"
# 3b — belt and braces: the allowlist itself must not name a credential.
for forbidden in authelia_token authelia_email bearer token private_key privkey if_privkey secret wireguard otp 2fa mail_code; do
    if awk '/private val ALLOWED_PROFILE_KEYS/,/\)/' "$ROOT/$SYNC" | grep -qF "$forbidden"; then
        bad "ALLOWED_PROFILE_KEYS must not contain '$forbidden'"
    else
        ok "ALLOWED_PROFILE_KEYS does not contain '$forbidden'"
    fi
done
# 3c — the sync files must not so much as reference the credential stores, so
# there is no route by which a value could be read and then put().
hasnt_code "$SYNC" "ConfigsPrefs"   "ProfileSync never reads the bearer store"
hasnt_code "$SYNC" "WireGuardPrefs" "ProfileSync never reads the WireGuard store"
# 3d — a sweep of the preference map is exactly the refactor this guards
# against; the document must keep enumerating.
hasnt "$SYNC" "sp.all"           "document() does not sweep the preference map"
hasnt "$SYNC" "getAll()"         "document() does not sweep the preference map (getAll)"

echo "== T4: the removed fields are gone everywhere, not just from the form =="
# A field dropped from the UI but still uploaded is personal data the user can
# no longer see or edit — worse than either keeping it or removing it.
hasnt_acct "City of origin"      "no City of origin label"
hasnt_acct "Social profiles"     "no Social profiles section"
hasnt_acct "socialEditor"        "social editor removed"
hasnt "$PREFS"    "cityFrom"            "cityFrom removed from ProfilePrefs"
hasnt "$PREFS"    "socialLinks"         "socialLinks removed from ProfilePrefs"
hasnt_code "$SYNC" "city_from"          "city_from not in the sync document"
hasnt_code "$SYNC" "social_media_links" "social_media_links not in the sync document"
# #781 the auto-import (ConfigAutoImport) is deleted with the "Your config" block that was its only caller.
[ -f "$ROOT/$PROFILE_DIR/ConfigAutoImport.kt" ] && bad "ConfigAutoImport.kt survives #781" || ok "the auto-import is gone (#781)"
has   "$PREFS"    "SCHEMA_VERSION = 2"  "schema bumped for the removal"
has   "$PREFS"    'remove("city_from")' "stored city_from is migrated away"
has   "$PREFS"    'remove("social_links")' "stored social_links is migrated away"
hasnt "build.json" '"city_from"'        "build.json profile_default drops city_from"
hasnt "app/build.gradle" "UI_PROFILE_CITY_FROM" "no orphan BuildConfig field"

echo "== T5: 'Titles …' is renamed to 'About' (label only, wire key unchanged) =="
hasnt_acct '"Titles'             "no Titles label (a string starting Titles; isTitles() is a function name, not a label)"
hasnt_acct 'label(ctx, "About")' "the contact-card form and its About label are gone (#781)"
# The stored key and the wire key stay `titles` — renaming those would break
# the server contract and every previously synced record for no user benefit.
has "$SYNC"  'put("titles"' "wire key is still titles"
has "$PREFS" 'K_TITLES   = "titles_v2"' "stored key is still titles_v2"

echo "== T6: the contract doc matches the client =="
hasnt "$CONTRACT" '"city_from"'          "contract no longer lists city_from"
hasnt "$CONTRACT" '"social_media_links"' "contract no longer lists social_media_links"
has   "$CONTRACT" "Retired fields"       "contract explains the removal"
has   "$CONTRACT" "No credential of any kind" "contract states credentials are never sent"

echo "== T7: the Cloud provider preset carries no private key =="
# The Provider dropdown fills the tunnel's PUBLIC half from the fleet preset.
# The private key is per-device by definition: two devices sharing one are a
# single peer to the hub and knock each other off the mesh. So the preset must
# fill everything EXCEPT that, and the seed data must not contain one either.
has "$MESH_PROFILES" 'control("provider")'  "Provider selector exists (Profiles tab)"
has "$WG_PREFS" "fun applyCloudPreset"          "the preset is applied from one place"
# The preset is derived from build.json via BuildConfig — not a literal here,
# so it follows the fleet when the hub moves.
has "$WG_PREFS" "BuildConfig.UI_WG_PEERS_JSON_B64" "preset peers come from the baked build.json data"
hasnt_code "$WG_PREFS" 'interfaceAddress    = "10.' "no hardcoded address literal in the preset"
# applyCloudPreset() must not touch the private key.
if awk '/fun applyCloudPreset/,/^    }/' "$ROOT/$WG_PREFS" | grep -qF "interfacePrivateKey"; then
    bad "applyCloudPreset() must not write interfacePrivateKey"
else
    ok "applyCloudPreset() leaves the private key alone"
fi
# Switching provider must not silently eat a config the user entered.
has "$WG_PREFS"  "fun matchesCloudPreset"  "drift from the preset is detectable"
has "$MESH_STORE"  "confirmCloudPreset"      "Cloud asks before overwriting a custom config"
# The seeded WireGuard data itself must carry no private key. Scoped to the
# wireguard_default block: ui.import_schema.wg documents the IMPORT format and
# legitimately names *_private_key fields, which a whole-file grep would hit.
if python3 -c "
import json,sys
wg = json.load(open('$ROOT/build.json'))['ui'].get('wireguard_default', {})
blob = json.dumps(wg)
sys.exit(1 if ('private_key' in blob or 'privkey' in blob) else 0)
" 2>/dev/null; then
    ok "build.json wireguard_default seeds no private key"
else
    bad "build.json wireguard_default must not seed a private key"
fi
hasnt "app/build.gradle" "UI_WG_INTERFACE_PRIVATE_KEY" "no private key is baked into BuildConfig"

echo "== T8: a bearer token cannot be stored without an email identity =="
# Authelia issues tokens per account and the fleet has several, so a lone token
# cannot say whose access it carries. The pairing is enforced at the STORE, not
# in the form, so the config-import path cannot become the hole the UI closed.
has "$CONFIGS_PREFS" "fun setAutheliaCredential" "one writer for the pair"
has "$CONFIGS_PREFS" "fun clearAutheliaCredential" "clearing removes both halves"
has "$CONFIGS_PREFS" "K_AUTHELIA_EMAIL = \"authelia_email\"" "the address has a declared path"
has "$CONFIGS_PREFS" "EMAIL_PATTERN"             "the address is shape-checked, not free text"
# The token property must be read-only: a public setter is a second writer, and
# a second writer is how the orphan state comes back.
has   "$CONFIGS_PREFS" "val autheliaToken"       "the token is read-only from outside"
hasnt_code "$CONFIGS_PREFS" "var autheliaToken"  "no public token setter"
# The reader — not the UI — is what makes an unpaired token unusable.
if awk '/private fun credential\(\)/,/^    }/' "$ROOT/$CONFIGS_PREFS" | grep -qF "EMAIL_PATTERN"; then
    ok "an unidentified token reads back as absent"
else
    bad "credential() must require a valid address before returning a token"
fi
# An existing token must not be silently adopted or destroyed by the new rule.
has "$CONFIGS_PREFS" "fun hasOrphanToken"        "a pre-pairing token is detected, not deleted"
has "$CONFIGS_PREFS" "fun adoptOrphanToken"      "linking it is explicit and user-driven"
has "$CW"            '" as $who"'                "the identity the bearer belongs to is named in the sign-in's result line on Account ▸ connect"
has "build.json"     '"authelia_email"'          "the import contract declares the pairing"
# NO email box at all since #573: the address is the one the sign-in proved
# (or the registry's primary), never typed — a box would invite disagreement.
hasnt_acct "autheliaEmailEditor"     "no typed account-email editor"
if [ "$(codeof "$CW" | grep -c 'setAutheliaCredential(')" = "1" ]; then
    ok "exactly one bearer store, after the sign-in that proved it (ConnectWays.signedIn)"
else
    bad "there must be exactly one bearer store"
fi

echo "== T9: pairing the token to a synced field did not widen the payload =="
# The email IS a synced profile field and the token must never be. Now that
# they are stored together, the document must still name every key it sends.
has "$SYNC" 'put("profile", enforceAllowlist(' "payload still passes the allowlist"
PUTS=$(awk '/put\("profile", enforceAllowlist\(/,/\}\)\)/' "$ROOT/$SYNC" | grep -c 'put("')
# 8 named contact fields + the put("profile", …) wrapper line itself.
if [ "$PUTS" = "9" ]; then
    ok "document() still enumerates exactly 8 profile fields"
else
    bad "document() should enumerate 8 profile fields (found $((PUTS-1)))"
fi
hasnt_code "$SYNC" "autheliaEmail" "ProfileSync never reads the paired address"
hasnt_code "$SYNC" "credential"    "ProfileSync never reads the credential pair"

echo "== T10: the Account page strip, and the mailed 2FA code is never stored =="
# What must survive the move is WHERE each field is stored, not where it is drawn. The strip is the fleet's shared
# NavBarTopSecondRow (Cloud Account's MainActivity), and the pages are plain composables: no child-fragment
# machinery, which swaps panes into fixed host ids and leaves one blank after a redraw.
has "$ACCT_MAIN" "NavBarTopSecondRow("             "the page strip is the fleet's shared NavBarTopSecondRow (#1154 moved it off PageTabs)"
hasnt_acct "childFragmentManager"               "the pages use no child fragments"
hasnt_acct "SectionTabsFragment"                "no second tab mechanism"
hasnt_acct "R.id.section_pane"                  "no pane host ids are borrowed"
# A redraw must not throw the user back to the first page.
has "$ACCT_MAIN" 'var selectedPage by remember { mutableStateOf<String?>(null) }' "the selected page survives a recomposition"

# Connect IS the declared ways (ConnectWays.ways()); Mesh Data carries the tunnel key.
has "$ACCT_PAGES" 'val ways = remember { ConnectWays.ways() }'          "the declared ways are the Connect page"
has "$DEVICE_VAULT" '(vault.connection("forge.${f.id}.token") as? String)' "Fetch now reuses a token the owner already filed: no second sign-in"
has "$ACCT_PAGES" 'KitAction("Fetch now", AccountPageTags.FETCH)'       "Fetch now is a button on Connect"
has "$MESH_PROFILES" 'ChoiceLine('   "Provider is on the WireGuard screen"
hasnt_acct "WireGuardPrefs"          "Account no longer touches tunnel settings at all"
# #781 the contact card FORM is deleted: its fields are the About topic's rows.
hasnt_acct 'private fun renderPerson('                      "the contact card form is gone (#781)"
hasnt_acct 'sectionHeader(ctx, "Personal Data")'            "its header is the declared label, not a literal"
hasnt_acct 'sectionHeader(ctx, "Imports")'                  "there is no Imports row: Import file is a way on Connect"
has "$ACCT_PAGES" 'ConnectWays.Handler.File -> KitSelectableTile('  "the manual file route survives as Connect's Import file way"
# The orphan-token affordance must stay reachable after the move: a stored bearer with no address is reported, and
# linking it is the owner's explicit tap (ConfigsPrefs never deletes it for lacking one).
has "$ACCT_PAGES" 'KitAction("Link it to $orphan", AccountPageTags.LINK_ORPHAN)' "the orphan-token link affordance survived the redesign"
has "$CW" 'ConfigsPrefs(ctx.applicationContext).adoptOrphanToken(address.trim())' "linking goes through the store's own address check"
has "$CW" 'if (!ConfigsPrefs(app).hasOrphanToken()) return null'        "it is offered only when a token is stranded"

# WHAT THE MAILED CODE IS. This fleet's Authelia enables webauthn + totp and no duo_api, so there is no email second
# FACTOR; notifier.smtp exists only to deliver the identity-validation one-time code for enrolling a factor or
# resetting a password. The box takes a TRANSIENT CODE, not a seed - and a transient code that gets persisted is a
# stored value that expired minutes ago, while a seed that gets persisted is a permanent second factor sitting
# next to the bearer. Neither may happen. The Account pages carry no such box any more (the leg is libs:auth's
# VaultConnect, which holds a code in memory for one call: test-profile-vault-connect.sh T5).
hasnt_acct "mailConfirmationField"        "the Account pages hold no mailed-code field"
hasnt_acct "showMailCodeDialog"           "nor a dialog for one"
hasnt_acct "confirmMailCode"              "nor a handler that could keep one"
# No seed anywhere: the code is not a secret to keep, and nothing may start keeping one.
hasnt_code "$CONFIGS_PREFS" "totp"        "ConfigsPrefs stores no TOTP seed"
hasnt_code "$CONFIGS_PREFS" "K_2FA"       "ConfigsPrefs has no 2FA key"
hasnt_code "$PREFS"         "2fa"         "ProfilePrefs holds no 2FA value"
hasnt "build.json" '"totp_secret"'        "build.json seeds no TOTP secret"
# And it must not have opened a new route into the payload.
hasnt_code "$SYNC" "mailCode"     "ProfileSync never reads the mailed code"
hasnt_code "$SYNC" "confirmation" "ProfileSync carries no confirmation field"

echo "== T11: the Account pages are declared {id, label} in build.json and mounted by id; the export carries no private key =="
WG_PROFILES="app/src/main/java/com/diegonmarcos/superapp/network/WireGuardProfiles.kt"
# The id -> page map is the ONLY page knowledge in Kotlin; every LABEL is the declaration's
# (ac_cloud-account/build.json::ui.sections, read through NavDecl).
has "$ACCT_MAIN" 'NavDecl.fromBuildConfig(' "the island and the strips are built from the declaration"
for page in '"signin" -> AccountConnectPage()' '"working" -> ProfilesWorkingPage()' '"diff" -> ProfilesDiffPage(model)' '"files" -> ProfilesDevicesPage()' '"apps" -> AppProfilesPage()'; do
    has "$ACCT_MAIN" "$page" "the page is mounted: $page"
done
# #1438/#1439 Personal Info > Others > Me is libs:account-ui's router; Cloud Account's host hands it to AccountProfilePage.
has "../ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/AccountUiHostImpl.kt" "= AccountProfilePage(go)" "the page is mounted: Others > Me -> AccountProfilePage(go)"
# No page label is a Kotlin literal, and none of the tabs the strip lost comes back.
for gone in 'Tab("Setup"' 'Tab("Infos"' 'Tab("Connect"' 'Tab("Profiles"' 'Tab("Runtime"' 'Tab("Drift"' 'Tab("Vault"' 'Tab("Repos"' 'Tab("Store"' 'Tab("WireGuard"' 'Tab(getString(R.string.vault_tab_imported)'; do
    hasnt_acct "$gone" "no tab label literal $gone"
done
# Every surface renders on a page (#778: Setup's index, wizard, cockpit and repos were deleted by design).
for gone in 'renderTokens(' 'renderDevicePick(' 'renderVault(' 'renderRepos(' 'renderConfigApply(' 'renderRuntime('; do
    hasnt_acct "$gone" "T11: the old Connect/Runtime renderer is gone ($gone)"
done
# And every page HAS a body: #614's null-column launch tabs are gone.
hasnt_acct 'val column: View?' "no null-column launch tab survives"
# The declaration is DATA: ids, order and labels come from the baked build.json arrays.
has "../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt" "UI_PROFILE_TABS_B64" "the legacy strip still comes from the baked build.json blob"
has "../ab_cloud-libs-shared/libs/account/build.gradle" "UI_PROFILE_TABS_B64"       "the blob is baked"
# AI is not a page of Account; and #723 deleted the Configs AI page, so no link to it survives.
hasnt_acct 'Tab("AI"'               "AI is not a top-level Account tab"
hasnt_acct 'page:config/ai'         "no link to the deleted AI page survives"
hasnt_acct "AiFragment"             "the AI page is not re-hosted here"
# #778 the cockpit's WireGuard link went with the cockpit page; no wrong target may come back.
hasnt_acct "page:config/wg"            "not the page target, which only rewrites to section:wg"
hasnt_acct "page:wg/config"            "not the double-push target"
# Still no child fragments and no borrowed launcher machinery.
hasnt_acct "childFragmentManager"   "the pages still use no child fragments"
hasnt_acct "SectionTabsFragment"    "the pages still avoid the section mechanism"
# #626 the page is called ACCOUNT, and the words live in ONE declaration.
name_is_account() {   # $1 = build.json; 0 iff the Configs identity page reads "Account"
    python3 - "$1" <<'PYNAME'
import json, sys
pages = [s for s in json.load(open(sys.argv[1]))["ui"]["sections"] if s["id"] == "config"][0]["pages"]
page = [p for p in pages if p["id"] == "profile"][0]
# The label is the rename. The id is the page's IDENTITY (routes, the
# cross-app deep link) and must NOT be a user-visible label (#380/#381/#499).
sys.exit(0 if page["label"] == "Account" else 1)
PYNAME
}
name_is_account "$ROOT/build.json" && ok "T11-name: Configs ▸ Account — the tile/page label is the ONE declaration" \
                                   || bad "T11-name: the Configs identity page is not labelled Account"

echo "-- T11-order: the strip is EXACTLY [connect, profiles, runtime, drift], each with a label (#778) --"
tab_order_ok() {   # $1 = build.json path; returns 0 iff the declared ids match, in order, and every tab has a label
    python3 - "$1" <<'PY'
import json, sys
want = ["connect", "profiles", "runtime", "drift"]
tabs = (json.load(open(sys.argv[1]))["ui"].get("profile") or {}).get("tabs") or []
labelled = all(isinstance(t, dict) and str(t.get("label", "")).strip() for t in tabs)
sys.exit(0 if labelled and [t.get("id") for t in tabs] == want else 1)
PY
}
wg_has_externals() {   # $1 = build.json path; returns 0 iff warp+proton are declared
    python3 - "$1" <<'PY'
import json, sys
ext = (json.load(open(sys.argv[1]))["ui"].get("wireguard_external_profiles") or {}).get("profiles", [])
ids = {p["id"] for p in ext}
sys.exit(0 if {"cloudflare-warp", "proton-vpn"} <= ids else 1)
PY
}
if tab_order_ok "$ROOT/build.json"; then ok "T11-order: build.json declares the four tabs, labelled, in the required order"
else bad "T11-order: build.json tab order or labels are wrong"; fi

echo "-- T11-mutation: a FIFTH tab, a dropped tab, a reordered tab, an unlabelled tab and a missing WG profile each go red --"
SCRATCH="$(mktemp -d)"; trap 'rm -rf "$SCRATCH"' EXIT
cp "$ROOT/build.json" "$SCRATCH/build.json"
tab_order_ok "$SCRATCH/build.json" && wg_has_externals "$SCRATCH/build.json" \
    && ok "T11-mutation: the unmutated tree passes both gates" \
    || bad "T11-mutation: the unmutated tree should pass both gates"
tab_mut() {   # $1 = python statement over t (the tabs list); prints nothing; 0 iff the mutation was caught
    cp "$ROOT/build.json" "$SCRATCH/build.json"
    python3 - "$SCRATCH/build.json" "$1" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); t=d["ui"]["profile"]["tabs"]; exec(sys.argv[2]); json.dump(d,open(p,"w"))
PY
    cmp -s "$ROOT/build.json" "$SCRATCH/build.json" && return 2
    ! tab_order_ok "$SCRATCH/build.json"
}
for m in 't[:] = [x for x in t if x["id"] != "runtime"]' \
         't.append({"id": "setup", "label": "Cloud Constellation Setup"})' \
         't[0], t[1] = t[1], t[0]' \
         't[2]["label"] = ""'; do
    tab_mut "$m"; rc=$?
    case $rc in
        0) ok "T11-mutation: caught — $m" ;;
        2) bad "T11-mutation: the mutation did not apply (tester stale) — $m" ;;
        *) bad "T11-mutation: NOT caught — $m" ;;
    esac
done
cp "$ROOT/build.json" "$SCRATCH/build.json"
python3 - "$SCRATCH/build.json" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); w=d["ui"]["wireguard_external_profiles"]; w["profiles"]=[x for x in w["profiles"] if x["id"]!="proton-vpn"]; json.dump(d,open(p,"w"))
PY
wg_has_externals "$SCRATCH/build.json" && bad "T11-mutation: a missing WG profile was NOT caught" || ok "T11-mutation: a missing WG profile is caught"
rm -rf "$SCRATCH"; trap - EXIT

echo "-- T11-infos: the Profiles read-out is the DECLARED copy, not a hand-listed set (#695 → #778) --"
# The mask rule and its proof live in test-account-four-tabs.sh; this block pins
# that #626's hand-listed sections stay gone and the read-out is the declared file itself.
AT_FILE="../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountTabs.kt"
PROF_FN=$(awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}' "$ROOT/$AT_FILE")
grep -qF 'val shown = m.shown()' <<<"$PROF_FN" && ok "T11-infos: Profiles reads the declared copy (L, else S)" || bad "T11-infos: Profiles does not read the declared copy"
grep -qF 'InfoMask.declared.schemaRows(section, shown?.opt(section.id))' <<<"$PROF_FN" && ok "T11-infos: every row comes from the declared copy, through the mask, per declared field" || bad "T11-infos: rows are not drawn from the declared copy"
grep -qE '"(person|tokens|repos|fleet|vault|wireguard|store)"' <<<"$(codeof "$AT_FILE" | awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}')" \
    && bad "T11-infos: Profiles still names a hand-listed section" || ok "T11-infos: Profiles names no section of its own"
jq -e '.ui.profile.infos.sections' "$ROOT/build.json" >/dev/null 2>&1 \
    && bad "T11-infos: build.json still hand-lists ui.profile.infos.sections" || ok "T11-infos: no hand-listed section list is declared"
for gone in 'UI_PROFILE_INFOS_B64, android.util.Base64.NO_WRAP))' 'profileInfoSections()' 'MODE_LINK' 'INFOS_NO_RENDERER' 'private fun renderInfos('; do
    hasnt_acct "$gone" "T11-infos: the old read-out is gone ($gone)"
done
echo "-- T11-infos-mutation: a hand-listed read-out goes red --"
SCRATCH2="$(mktemp -d)"; trap 'rm -rf "$SCRATCH2"' EXIT
sed 's/    val shown = m.shown()/    val shown = org.json.JSONObject().put("person", org.json.JSONObject())/' "$ROOT/$AT_FILE" > "$SCRATCH2/mut.kt"
if cmp -s "$ROOT/$AT_FILE" "$SCRATCH2/mut.kt"; then
    bad "T11-infos-mutation: the hardcode mutation did not apply (tester stale)"
else
    awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}' "$SCRATCH2/mut.kt" | grep -qF 'val shown = m.shown()' \
        && bad "T11-infos-mutation: a hand-listed Profiles read-out was NOT caught" \
        || ok "T11-infos-mutation: a hand-listed Profiles read-out is caught"
fi
cp "$ROOT/build.json" "$SCRATCH2/build.json"
python3 - "$SCRATCH2/build.json" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); d["ui"]["profile"]["infos"]["sections"]=[{"id":"person","label":"Person","mode":"render","route":""}]; json.dump(d,open(p,"w"))
PY
jq -e '.ui.profile.infos.sections' "$SCRATCH2/build.json" >/dev/null 2>&1 \
    && ok "T11-infos-mutation: a re-grown hand-listed section list is caught" \
    || bad "T11-infos-mutation: a re-grown hand-listed section list was NOT caught"
rm -rf "$SCRATCH2"; trap - EXIT

echo "-- T11a: the profile matrix is DATA in build.json, not literals in Kotlin --"
has "$WG_PROFILES" "BuildConfig.UI_WG_PROFILES_JSON_B64" "profiles come from baked build.json data"
has "$WGFRAG" "WireGuardProfiles.all"  "the WireGuard screen owns the profile export"
has "app/build.gradle" "UI_WG_PROFILES_JSON_B64"         "the blob is baked"
has "build.json" '"wireguard_profiles"'                  "build.json declares the matrix"
# #614 the public-VPN profiles ride a SEPARATE baked blob, merged into .all.
has "$WG_PROFILES" "BuildConfig.UI_WG_EXTERNAL_PROFILES_JSON_B64" "the externals come from their own baked blob"
has "app/build.gradle" "UI_WG_EXTERNAL_PROFILES_JSON_B64" "the external blob is baked"
has "build.json" '"wireguard_external_profiles"'         "build.json declares the external profiles separately"
# No fleet literal may be spelled out in the renderer — it must follow the
# fleet the way applyCloudPreset() does, not rot when the hub moves.
hasnt_code "$WG_PROFILES" "35.226.147.64"  "no hub endpoint literal in the renderer"
hasnt_code "$WG_PROFILES" "129.151.228.66" "no second hub endpoint literal"
hasnt_code "$WG_PROFILES" "10.0.0.9"       "no address literal in the renderer"
# #522: each profile exports ITS OWN Address. A shared one exported v4-split's
# order into the v6 profiles, whose IPv4 then left from the wrong mesh.
has "$WG_PROFILES" 'Address = ${profile.address}' "the Address line is the profile's own"
hasnt_code "$WG_PROFILES" "interface_address"      "no shared Address to fall back to"
# Render-only: it must never write the tunnel's stored settings, or it becomes
# a second writer racing applyCloudPreset().
hasnt_code "$WG_PROFILES" "WireGuardPrefs" "the exporter never writes tunnel prefs"

echo "-- T11b: the export omits the private key --"
hasnt_code "$WG_PROFILES" "interfacePrivateKey" "the renderer cannot read the private key"
hasnt_code "$WG_PROFILES" "if_privkey"          "the renderer cannot reach the stored key"
has "$WG_PROFILES" "PrivateKey = "              "an empty, named PrivateKey line is emitted"
has "$WG_PROFILES" "NOT EXPORTED"               "the file says the key was withheld"
if awk '/fun exportProfilesTo/,/^    }$/' "$ROOT/$WGFRAG" | grep -qF "interfacePrivateKey"; then
    bad "exportProfilesTo() must not touch the private key"
else
    ok "the export path never reads the private key"
fi

echo "-- T11c: the matrix itself (a wrong prefix here is a real outage) --"
# One python pass: it prints the same "  PASS:/  FAIL:" lines the shell helpers
# do, then a trailing tally the shell folds into its own counters.
MATRIX=$(python3 - "$ROOT/build.json" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
blk = ui["wireguard_profiles"]
ext = ui.get("wireguard_external_profiles", {}) or {}
P, F = [], []
def chk(c, m): (P if c else F).append(m)

profiles = blk["profiles"]
MESH = {"v4-split", "v4-full", "v6-split", "v6-full"}
# The mesh block is guard-locked to the cloud-infra vault dist, so it stays
# EXACTLY four (cloud-android-wireguard-profiles-guard.py compares it).
chk(len(profiles) == 4, "exactly 4 mesh profiles (one merged tunnel each, not 8)")
chk({p["id"] for p in profiles} == MESH, "the four mesh ids are v{4,6}-{split,full}")
chk("interface_address" not in blk, "no shared interface_address overrides the per-profile order")
chk(blk["interface_mtu"] == "1380", "MTU is 1380, not the in-app form's 1280")
byid = {p["id"]: p for p in profiles}

# ── the four MESH profiles carry both meshes + both v6 identities (#522) ──
for i in sorted(MESH):
    p = byid[i]
    addr = p.get("address", "")
    chk("fd0c:1d00::9/64" in addr, f"{i}: Address carries the wg0 identity fd0c:1d00::9")
    chk("fd0c:1d01::9/64" in addr, f"{i}: Address carries the wg-public identity fd0c:1d01::9")
    peers = {q["name"]: q for q in p["peers"]}
    chk(set(peers) == {"gcp-proxy", "oci-analytics"}, f"{i}: both meshes present as two peers")
    g, o = peers["gcp-proxy"], peers["oci-analytics"]
    chk(g["endpoint"].endswith(":443"), f"{i}: gcp-proxy uses udp/443, not the filtered 51820")
    # The prefix that causes the 14-30s stall if it goes to the wrong peer.
    chk("fd0c:1d00::/64" in g["allowed_ips"], f"{i}: fd0c:1d00::/64 routes to gcp-proxy")
    chk("fd0c:1d00" not in o["allowed_ips"], f"{i}: fd0c:1d00 is NOT handed to oci-analytics")
    chk("1.1.1.1" not in p["dns"] and "1.0.0.1" not in p["dns"], f"{i}: DNS is mesh-only")

# split vs full must actually differ, or the matrix is decoration.
for fam in ("v4", "v6"):
    chk("0.0.0.0/0" not in json.dumps(byid[f"{fam}-split"]), f"{fam}-split routes no default v4")
    chk("0.0.0.0/0" in json.dumps(byid[f"{fam}-full"]), f"{fam}-full routes the default v4")

# ── #614 the two PUBLIC-VPN import-templates, in the SEPARATE (unguarded)
#    block so the vault-locked mesh block above stays exactly the guard's four ──
EXTERNAL = {"cloudflare-warp", "proton-vpn"}
exts = ext.get("profiles", []) or []
extid = {p["id"]: p for p in exts}
chk(set(extid) == EXTERNAL, "the two external profiles are cloudflare-warp + proton-vpn")
for i in sorted(EXTERNAL):
    p = extid.get(i, {})
    chk(p.get("address", "") == "", f"{i}: external template carries no baked Address")
    for q in p.get("peers", []):
        chk(q.get("public_key", "x") == "" and q.get("endpoint", "x") == "",
            f"{i}: external peer has no baked key/endpoint (filled on import)")

# NO profile, mesh or external, carries a private key.
for p in profiles + exts:
    blob = json.dumps(p)
    chk("private_key" not in blob and "privkey" not in blob, f"{p['id']}: carries no private key")

# The separation that protects matchesCloudPreset().
chk("fd0c" not in json.dumps(ui["wireguard_default"]),
    "wireguard_default is untouched (still v4-only), so no install reads as drifted")

for m in P: print(f"  PASS: {m}")
for m in F: print(f"  FAIL: {m}")
print(f"TALLY {len(P)} {len(F)}")
PY
)
echo "$MATRIX" | grep -v '^TALLY '
TALLY=$(echo "$MATRIX" | awk '/^TALLY /{print $2" "$3}')
PASS=$((PASS + ${TALLY% *})); FAIL=$((FAIL + ${TALLY#* }))

echo "-- T11d: the status readout admits what it cannot see --"
has "app/src/main/res/values/strings.xml" ">CANNOT TELL<"    "status has a third, cannot-tell state"
has "$MESH_PORT" "isEngineInstalled"  "cannot-tell is decided by the engine being absent"
has "app/src/main/res/values/strings.xml" ">CONNECTED<"      "status can say connected"
has "app/src/main/res/values/strings.xml" ">NOT CONNECTED<"  "status can say not connected"
# A DOWN reading with no engine is not evidence — it must not be reported as
# "not connected", which is the lie that sends someone chasing a working mesh.
if grep -qF '!engine -> Health.UNKNOWN' "$ROOT/$MESH_MODEL" && grep -qF 'Light.UNKNOWN' "$ROOT/$MESH_MODEL"; then
    ok "state is only read as down when the engine can actually answer"
else
    bad "the mesh reducer must not trust getState() without the engine"
fi
# No claim to see a tunnel this app does not own.
hasnt_code "$MESH_PORT" "wg show"        "no pretence of reading the OS tunnel table"
hasnt_code "$MESH_PORT" "latest-handshake" "no pretence of reading wg state files"

echo "== T12: the pill SIZING is shared, and nothing is duplicated =="
# apply() paints the chrome; equalise() measures it. Profile got the first and
# not the second, which is what "ragged pills" was — the sizing pass was a
# private method of SectionTabsFragment, so no other strip could reach it.
has "$PAGETABS" "fun planPills"      "the sizing pass lives in the shared strip (libs:bottomnav)"
has "$PAGETABS" "minChars"           "its floor moved with it"
has "$PAGETABS" "scrollable"         "and its honest last resort"
hasnt_code "app/src/main/java/com/diegonmarcos/superapp/App.kt" "styleTabs" "App.kt hands AccountHost no styling hook: the strip sizes itself"
has "$SECTABS"  "PageTabsView"       "the launcher's strip uses the same one"
# ONE copy. A second would drift, and the drift would be invisible until a
# label got long enough to clip on one strip and not the other.
hasnt_code "$SECTABS" "private fun applyEqualTabs" "no private copy left behind"
hasnt_code "$SECTABS" "private fun findLabel"      "nor its label walker"
hasnt_code "$SECTABS" "PILL_PAD_DP"                "nor makePill's own numbers"
# The "|" between destination tabs and launch tabs is drawn by the strip itself now.
hasnt_code "$SECTABS" "addGroupDivider"            "the group divider is the strip's, not a private view here"
has "$PAGETABS" "val divider = pages.indexOfFirst" "the strip derives the divider from the launch pages"
[ ! -e "app/src/main/java/com/diegonmarcos/superapp/launcher/AppTabsStyle.kt" ] && ok "AppTabsStyle.kt is deleted" || bad "AppTabsStyle.kt is back"

echo "-- T12a: Profile no longer duplicates the WireGuard screen --"
# The whole point of the merge: capability moved, so Profile must not still
# carry a second, partial copy of it.
hasnt_acct "WireGuardProfiles" "Account no longer exports profiles"
hasnt_acct "providerSelector"  "Account no longer has a Provider dropdown"
hasnt_acct "meshStatusView"    "Account no longer renders tunnel status"
hasnt_acct "generateInterfaceKeyPair" "Account no longer mints tunnel keys"
# ...and the WireGuard screen must have gained every one of them.
has "$MESH_PROFILES" "ChoiceLine"     "WireGuard gained the Provider choice"
has "$WGFRAG" "profileFolderPicker"  "WireGuard gained the 4-profile export"
has "$MESH_STATUS" "fun MeshStatusPage"  "WireGuard gained the honest status"
has "app/src/main/res/values/strings.xml" "mesh_provider_text"        "WireGuard gained the provider explainer"
# Its own plaintext key box was the one privacy regression in the merge.
if grep -qF "PasswordVisualTransformation" "$ROOT/$MESH_WIDGETS" && grep -qF 'if (secret) "" else value' "$ROOT/$MESH_WIDGETS"; then
    ok "the WireGuard private-key box is masked and never prefilled"
else
    bad "the WireGuard private-key box must not render the key in plaintext"
fi
# The two exports are different features and must stay distinguishable: the
# .conf export DOES carry the key (it moves a tunnel you own), the 4-profile
# export never does (they are templates).
has "$WGFRAG" "toWgQuickString"  "the single-config export still exists"
has "$WGFRAG" "no private key included" "the profile export says it withheld the key"

echo "== T13: the Google OAuth client is wired, and its client_secret is NEVER in this public repo (#611) =="
# The provider list is the shared libs:auth declaration, not the superapp's build.json.
SHARED_BUILD="../ab_cloud-libs-shared/build.json"
# client_id is PUBLIC (Google documents it as not-secret for a 'TV and Limited Input'
# client) and lives in that declaration; the client_secret must be EMPTY there — it is
# baked from the private vault at build time (libs:auth build.gradle), never committed.
if python3 - "$ROOT/$SHARED_BUILD" <<'PY'
import json, sys
provs = json.load(open(sys.argv[1]))["auth"]["sign_in"]["providers"]
g = next((p for p in provs if p.get("id") == "google"), None)
ok = (g is not None
      and g.get("client_id", "").endswith(".apps.googleusercontent.com")
      and g.get("client_secret", "") == "")
sys.exit(0 if ok else 1)
PY
then ok "google provider carries a client_id and an EMPTY client_secret in the public repo"
else bad "google provider must carry client_id + empty client_secret in ab_cloud-libs-shared/build.json"; fi
# No client-secret literal (Google's client-secret prefix) may appear in any TRACKED
# file of this PUBLIC repository. The needle is assembled from pieces so this guard never
# matches its own source; git grep scopes the sweep to tracked files.
REPO="$(cd "$ROOT/.." && pwd)"
NEEDLE="GOCSPX""-"
sweep() { git -C "$1" grep -I -l -e "$NEEDLE" -- . 2>/dev/null; }
LEAK="$(sweep "$REPO")"
if [ -z "$LEAK" ]; then ok "no client-secret literal is committed anywhere in the public repo"
else bad "a Google client-secret literal is committed: $(echo "$LEAK" | tr '\n' ' ')"; fi
# Mutation: the sweep MUST catch a planted secret, or its silence proves nothing.
SCRATCH="$(mktemp -d)"; git -C "$SCRATCH" init -q
printf 'GOOGLE_CLIENT_SECRET=%sPLANTED123\n' "$NEEDLE" > "$SCRATCH/leak.env"
git -C "$SCRATCH" add -A 2>/dev/null
if [ -n "$(sweep "$SCRATCH")" ]; then ok "T13-mutation: a planted client-secret is caught"
else bad "T13-mutation: the sweep failed to catch a planted client-secret"; fi
rm -rf "$SCRATCH"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
