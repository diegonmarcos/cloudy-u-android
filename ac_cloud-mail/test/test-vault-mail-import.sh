#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-vault-mail-import — the vault's mail section configures      ║
# ║ cloud-mail through the fleet config contract, nothing hand-rolled ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# "add configs in our apps fleet for example in cloud-mail": the Account app's cockpit derives
# `settings › mail › sterna_account › vault_mail` (the vault's whole `mail` section as JSON text)
# and `vault_owner` (about.profile.email) exactly as #790/#802 derive the terminals' and the
# browser's stores (AccountFleet.derive); the migration / Fleet Setup import them over FleetConfig
# into the mail app's prefs and restart it; on start VaultMailImport adds every declared account
# through AccountStore.add (the password encrypted into its pw_<id> slot by that API), makes the
# owner's current and removes both keys. Static, no build:
#   V1 the cockpit layout holds the derived_settings entry: store, apps, keys exactly
#   V2 the manifest classes vault_mail / vault_owner secret on sterna_account, pw_* still device
#   V3 the adapter reads the key, adds through AccountStore.add, removes the key, never logs a password
#   V4 the Application calls it on create
#   V5 the JVM suite pins derive for mail
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LAYOUT="$ROOT/aa_cloud-superapp/build.json"
MANIFEST="$ROOT/fleet-private-data/fleet-config.json"
ADAPTER="$APP/core/data/src/main/kotlin/app/sterna/core/data/account/VaultMailImport.kt"
STORE="$APP/core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
APPLICATION="$APP/app/src/main/kotlin/app/sterna/SternaApplication.kt"
JVM="$ROOT/aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/profile/AccountFleetTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$LAYOUT" "$MANIFEST" "$ADAPTER" "$STORE" "$APPLICATION" "$JVM"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 2; }

echo "== V1: the cockpit layout derives sterna_account for mail from the vault's mail section =="
python3 - "$LAYOUT" <<'PY' && ok "derived_settings has {store sterna_account, apps [mail], keys vault_mail→[mail], vault_owner→[about,profile,email]}" || bad "the derived_settings entry for sterna_account is missing or not exactly as declared"
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["vault_connect"]["cockpit"]["derived_settings"]
e = [x for x in d if x.get("store") == "sterna_account"]
assert len(e) == 1, e
e = e[0]
assert e["apps"] == ["mail"], e["apps"]
keys = {k: v for k, v in e["keys"].items() if not k.startswith("_")}
assert keys == {"vault_mail": ["mail"], "vault_owner": ["about", "profile", "email"]}, keys
PY

echo "== V2: the manifest classes the transit keys secret on sterna_account; pw_* stays device =="
python3 - "$MANIFEST" <<'PY' && ok "sterna_account keys: pw_*→device, vault_mail→secret, vault_owner→secret; used by ac_cloud-mail" || bad "sterna_account does not declare the transit keys secret (or lost pw_*→device)"
import json, sys
s = json.load(open(sys.argv[1]))["stores"]["sterna_account"]
assert s["kind"] == "prefs" and s["class"] == "config", s
assert s["keys"]["pw_*"] == "device" and s["keys"]["vault_mail"] == "secret" and s["keys"]["vault_owner"] == "secret", s["keys"]
assert "ac_cloud-mail" in s["used_by"]
assert "vault_mail" in s["doc"] and "removes" in s["doc"], s["doc"]
PY

echo "== V3: the adapter consumes the key through AccountStore's own add API, once =="
grep -q 'const val KEY_MAIL = "vault_mail"' "$ADAPTER" && grep -q 'const val KEY_OWNER = "vault_owner"' "$ADAPTER" \
  && grep -q 'getSharedPreferences(AccountStore.PREFS_NAME, Context.MODE_PRIVATE)' "$ADAPTER" \
  && grep -q 'prefs.getString(KEY_MAIL, null) ?: return 0' "$ADAPTER" \
  && ok "reads vault_mail / vault_owner from the sterna_account prefs" || bad "the adapter does not read the transit keys from AccountStore's prefs"
grep -q 'prefs.edit().remove(KEY_MAIL).remove(KEY_OWNER).apply()' "$ADAPTER" \
  && ok "removes both keys (one-shot; a re-import re-runs it)" || bad "the adapter does not remove the transit keys"
grep -q 'store.add($' "$ADAPTER" && grep -q 'password = d.password' "$ADAPTER" \
  && grep -q 'fun add($' "$STORE" && grep -q 'writePassword(id, secret)' "$STORE" \
  && ok "adds through AccountStore.add, which encrypts the password into its pw_<id> slot" || bad "the adapter does not add through AccountStore.add"
grep -q 'current?.let(store::setCurrent)' "$ADAPTER" && grep -q 'd.email.equals(plan.owner, ignoreCase = true)' "$ADAPTER" \
  && ok "the owner's account is made current (else the first declared)" || bad "the owner's account is not made current"
grep -q 'it.username.equals(d.email, ignoreCase = true)' "$ADAPTER" \
  && ok "an account already stored under the address is not added again" || bad "no already-present check"
grep -q 'optString("pass_env")' "$ADAPTER" && grep -q '"$local@$domain"' "$ADAPTER" \
  && grep -q 'optString("sni").startsWith(prefix)' "$ADAPTER" && grep -q 'e.optString("domain")' "$ADAPTER" \
  && ok "mirrors VaultCockpit.mailAccounts / mailEndpoints: name@owner-domain, passwords[pass_env], domain + l4_ports SNI" \
  || bad "the parser does not mirror the Account lib's mail rules"
if grep 'Log\.' "$ADAPTER" | grep -o '"[^"]*"' | grep -q 'd\.password\|\${[^}]*password\|\$password'; then bad "a log line interpolates a password"; else ok "log lines carry the address only, never a password value"; fi
grep -q 'vault mail: ${d.email} added' "$ADAPTER" && ok "one line per account added" || bad "no per-account log line"
! grep -q 'libs:account\|com.diegonmarcos.superapp.profile' "$APP/core/data/build.gradle.kts" "$ADAPTER" \
  && ok "no libs:account dependency added to core:data (parser is local)" || bad "core:data pulls libs:account"

echo "== V4: the Application runs it on create, after the container exists =="
grep -q 'import app.sterna.core.data.account.VaultMailImport' "$APPLICATION" \
  && grep -A4 'container = AppContainer(this)' "$APPLICATION" | grep -q 'VaultMailImport.run(this, container.accountStore)' \
  && ok "SternaApplication.onCreate calls VaultMailImport.run right after AppContainer" || bad "the Application does not call VaultMailImport.run"

echo "== V5: the JVM suite pins derive for mail =="
grep -q "a pending mail becomes declared" "$JVM" && grep -q 'assertEquals(mail.toString(), st.getString("vault_mail"))' "$JVM" \
  && ok "AccountFleetTest: pending settings › mail becomes declared with sterna_account.vault_mail = the section text" \
  || bad "AccountFleetTest has no #mail derive case"

# 2026-10-08: the owner tapped me@ and saw no-reply@ — me@ existed before the import with no
# usable credential, and Sterna makes the first account that CAN log in current instead.
grep -q 'store.credentials(existing.id) == null && d.password != null' "$ADAPTER" \
    && grep -q 'store.updatePassword(existing.id, d.password)' "$ADAPTER" \
    && ok "an already-stored declared account without a usable credential takes the vault's password" \
    || bad "an existing account keeps its dead credential"

echo; echo "test-vault-mail-import: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
