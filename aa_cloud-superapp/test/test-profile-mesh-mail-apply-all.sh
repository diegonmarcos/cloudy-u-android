#!/usr/bin/env bash
# Tester (#573, the A37): Configs ▸ Account ▸ Runtime ▸ "Apply all" for the mesh and mail cards.
#
# The owner's ask: import the vault bundle through Account, store ALL FOUR mesh profiles of THIS
# phone (its own peer, its own key — never the S21+'s), make config-v4-split the active tunnel
# and bring it up, and configure EVERY declared mail account for cloud-mail. The behaviour
# (the key rule, ownership by Address line, the active-name pick) is executed by the JVM tests;
# this file pins what a JVM test cannot see:
#   T1  the profile SET is stored by name, one tunnel active, the key in ONE slot — stored texts
#       carry <PROVIDED_BY_DEVICE>, and the stored set is never a second key copy
#   T2  config-v4-split is the declared default, as DATA (build.json), read by the Kotlin
#   T3  Apply all EXISTS and is a BUTTON: declared per card as data (runtime.apply_all on the mesh
#       and mail sections), drawn as a tagged ActionButton on the Runtime tab, dispatching to ONE
#       model function the debug API also calls — never run by a render path
#   T4  the mesh apply obeys THE KEY RULE: the device is the explicit pick (never inferred for a
#       write), the key is checked against the device's declared wg_public_key, a foreign key is
#       refused, and the no-key case names the Generate/import path instead of inventing a source
#   T5  the mail apply writes EVERY account with its password and the endpoints (JMAP/IMAP/SMTP)
#   T6  NO PRIVATE KEY EVER LEAVES THE DEVICE: the reports, the card status and the profile-sync
#       document carry no key; the only key source is the vault bundle or the phone's own slot
#   T7  the vault emitter declares the A37's own profiles under mesh.devices (its own dir), and the
#       cockpit files them under the same key shape
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF -- "$2" "$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF -- "$2" "$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }
code()  { sed -E 's://.*$::; /^\s*\*/d; /^\s*\/\*/d' "$1"; }   # comment lines dropped
codehasnt() { code "$1" | grep -qF -- "$2" && bad "$3 ($1)" || ok "$3"; }

BJ="$ROOT/build.json"
LIB="$ROOT/../ab_cloud-libs-shared/libs"
VC="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/VaultCockpit.kt"
RT="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountRuntime.kt"
MD="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt"
TABS="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountTabs.kt"
DBG="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt"
HOST="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountHost.kt"
SYNC="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/ProfileSync.kt"
JMAP="$LIB/mail/src/main/java/com/diegonmarcos/superapp/mail/JmapPrefs.kt"
PREFS="$ROOT/app/src/main/java/com/diegonmarcos/superapp/network/WireGuardPrefs.kt"
MESH="$ROOT/app/src/main/java/com/diegonmarcos/superapp/network/AccountMesh.kt"
STR="$LIB/account/src/main/res/values/strings.xml"
VAULT_SRC="$ROOT/../../cloud-me-pvt-vault/Shared/configs/mesh/sources.json"

echo "T1 four profiles stored by name, one active, the key in ONE slot"
has "$PREFS" 'fun saveProfiles(profiles: Map<String, String>)' "WireGuardPrefs stores a named profile set"
has "$PREFS" 'fun profiles(): Map<String, String>' "...and reads it back by name"
has "$PREFS" 'var activeProfile: String' "...with ONE active profile name"
has "$PREFS" 'fun activateProfile(name: String): Config' "...activated through the ONE import path"
has "$PREFS" 'hydrateFromConfig(cfg)' "activateProfile hydrates the single tunnel (no second parser)"
has "$PREFS" 'const val PROVIDED_BY_DEVICE = "<PROVIDED_BY_DEVICE>"' "the stored marker is the bundle's own"
has "$PREFS" 'o.put(name, stripPrivateKey(conf))' "a stored profile text has its PrivateKey stripped"
has "$PREFS" 'conf.replace(PROVIDED_BY_DEVICE, key)' "the device key is spliced only at parse time"

echo "T2 config-v4-split is the declared default, as data"
has "$BJ" '"mesh_default_profile": "config-v4-split"' "build.json declares the default profile"
has "$VC" 'o.optString("mesh_default_profile")' "VaultCockpit reads it from the cockpit declaration"
has "$VC" 'fun meshActive(profiles: Map<String, String>, default: String): String?' "the active name is picked from the declared default"
codehasnt "$VC" '"config-v4-split"' "no profile name is a Kotlin literal in the cockpit"
codehasnt "$RT" '"config-v4-split"' "no profile name is a Kotlin literal in the runtime"

echo "T3 Apply all exists and is a button"
grep -qF '"apply": "mesh"' "$BJ" && grep -A1 -F '"apply": "mesh"' "$BJ" | grep -qF '"apply_all": true' && ok "mesh card declares apply_all" || bad "mesh card declares apply_all"
grep -A1 -F '"apply": "mail"' "$BJ" | grep -qF '"apply_all": true' && ok "mail card declares apply_all" || bad "mail card declares apply_all"
grep -A1 -F '"apply": "keyboard"' "$BJ" | grep -qF '"apply_all": true' && ok "keyboard card declares apply_all (#781 write half)" || bad "keyboard card declares apply_all"
has "$RT" '"keyboard" -> applyKeyboard(ctx, section, server)' "the keyboard has an apply (vault autocomplete → ITextTools.importClipboardLists)"
has "$RT" 'if (i == 0) client.importClipboardLists(chunk.toString()) else client.appendClipboardLists(chunk.toString())' "...in binder-sized chunks: the first replaces, the rest append"
has "$RT" 'fun keyboardChunks(export: JSONObject, maxChars: Int): List<JSONObject>' "...cut by keyboardChunks"
has "$VC" 'optBoolean("apply_all", false)' "the Kotlin reads apply_all as data"
has "$TABS" 'fun runtimeApplyAll(id: String) = "runtime:apply_all:$id"' "the button has a test tag"
has "$TABS" '?.runtime?.applyAll == true) ActionButton(stringResource(R.string.account_apply_all), AccountTags.runtimeApplyAll(section.id)' "...drawn only for a card that declares it, as an ActionButton"
has "$TABS" 'm.applySection(id)' "the button calls the model"
has "$MD" 'fun applySection(id: String): String' "the model has ONE apply-all function"
has "$MD" 'AccountRuntime.applyAll(ctx, section, body)' "...dispatching to the runtime"
has "$DBG" '"apply" -> done(m.applySection(q["app"].orEmpty()), m)' "the debug op calls the same function"
has "$STR" '<string name="account_apply_all">' "the label is a resource"
# a render never applies: applyAll is reached from the tap's coroutine only
n=$(code "$TABS" | grep -c 'applySection('); [ "$n" = "1" ] && ok "applySection is called from exactly one place in the tab (the tap)" || bad "applySection call sites in the tab: $n"
codehasnt "$RT" 'applyAll(ctx, section' "readOne never calls applyAll (reading writes nothing)"

echo "T4 the key rule"
has "$VC" 'fun meshKey(device: Device, profiles: Map<String, String>, phonePublicKey: String, derive: (String) -> String?): MeshKey' "the rule is one pure function"
has "$VC" 'val publicKey: String = ""' "a device carries its declared public key"
has "$VC" 'entry.opt("wg_public_key")' "...read from electronics wg_public_key"
has "$VC" 'if (declared.isNotBlank() && pub != declared)' "a vault profile key that is not the device's is refused"
has "$VC" 'if (declared.isNotBlank() && phonePublicKey != declared)' "a phone key that is not the device's is refused"
has "$VC" 'another peer'"'"'s key is never reused' "the refusal says so"
has "$VC" 'Generate keypair' "the no-key case names the Generate path"
has "$VC" 'nothing written' "every refusal writes nothing"
has "$RT" 'val picked = VaultCockpit.selectedDevice(ctx)' "the mesh apply uses the EXPLICIT pick"
codehasnt "$RT" 'deviceFor(VaultCockpit.devices(server), VaultCockpit.selectedDevice(ctx), AccountHost.mesh?.interfaceAddress' "...never the inferred device for a write"
has "$RT" 'VaultCockpit.meshKey(d, profiles, host.publicKey(ctx), ::derivePublicKey)' "the apply asks the rule before the host"
has "$RT" 'is VaultCockpit.MeshKey.Refused -> k.why' "a refusal is the report, nothing written"
has "$RT" '· key ${d.publicKey.take(8)}…' "the card names the device with its declared key"
has "$MESH" 'fun publicKey(ctx: Context): String = WgState.prefs(ctx).derivedInterfacePublicKey()' "the host answers the phone's PUBLIC half only"
has "$MESH" 'if (privateKey != null) prefs.interfacePrivateKey = privateKey' "the vault's key lands in the one slot only when the rule allowed it"
has "$MESH" 'backend.setState(WgState.tunnel, Tunnel.State.UP, prefs.toTunnelConfig())' "Apply all connects through the same engine call as Mesh ▸ Connect"
has "$MESH" 'latestHandshakeEpochMillis()' "...and reports the handshake"

echo "T5 mail: every account, password and endpoints"
has "$JMAP" 'fun saveAccounts(list: List<Account>, active: String)' "JmapPrefs stores an account list"
has "$JMAP" 'data class Account(val email: String, val password: String, val jmap: String, val imap: String, val smtp: String)' "...each with password and the three hosts"
has "$VC" 'fun applyMailAll(prefs: JmapPrefs, accounts: List<MailDeclared>, endpoints: MailEndpoints, owner: String): String' "the cockpit applies ALL declared accounts"
has "$VC" 'ports.optJSONObject(it)?.optString("sni")' "IMAP/SMTP hosts come from mail.endpoints l4_ports SNI"
has "$RT" 'VaultCockpit.applyMailAll(JmapPrefs(ctx), accounts, VaultCockpit.mailEndpoints(server), owner)' "the mail apply-all dispatches to it"
has "$RT" 'VaultCockpit.mailAccounts(server, owner.substringAfter' "...over every account at the owner's domain"

echo "T6 no private key ever leaves the device"
has "$HOST" 'fun status(ctx: Context): String = ""' "the status line is a host string"
has "$MESH" 'Never a key.' "...documented key-free"
codehasnt "$MESH" 'interfacePrivateKey}' "no report interpolates the private key"
codehasnt "$MESH" '${key' "no report interpolates the key variable"
codehasnt "$VC" '${key}' "no cockpit report interpolates a key"
codehasnt "$RT" 'interfacePrivateKey' "the runtime never reads the private key"
codehasnt "$SYNC" 'profiles_json' "the profile set is not a profile-sync field"
codehasnt "$SYNC" 'if_privkey' "the key slot is not a profile-sync field"
codehasnt "$SYNC" 'accounts_json' "the mail accounts are not a profile-sync field"
has "$RT" 'fun derivePublicKey(privateKey: String): String?' "the only key use in the runtime is deriving its public half"

echo "T7 the A37's own profiles reach the bundle under its own id"
if [ -f "$VAULT_SRC" ]; then
  has "$VAULT_SRC" '"devices": {' "mesh/sources.json declares per-device profile sets"
  has "$VAULT_SRC" 'c0-wireguard/samsung-a37-public' "...the A37's from its OWN dir (its own key)"
  hasnt "$VAULT_SRC" '"samsung-a37": {"profiles": {"kind": "files", "repo": "cloud-vault", "dir": "Shared/Providers/C_TOOLS-INFRA/c0-wireguard/termux-public"' "...never the S21+'s dir"
else
  echo "  skip: vault not checked out beside this repo"
fi
has "$VC" 'optJSONObject("mesh")?.optJSONObject("devices")' "the cockpit files mesh.devices.<id>.profiles"
has "$VC" 'candidates.putIfAbsent("devices/$id/$name", it)' "...keyed so a name shared with mesh.profiles survives"
has "$RT" 'parts.size == 3 && parts[0] == "devices"' "the runtime maps the key back to its vault path"

echo; echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
