#!/usr/bin/env bash
# #1693 Cloud Mail types no mailbox password, API token or gateway token: Connect, the import sign-in, the account screen and Authentication take
# them from Cloud Account (App credentials) through CloudSecretField / CloudSecrets. Mutations are planted in a scratch copy and must go RED.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
U=ac_cloud-mail/app/src/main/kotlin/app/sterna/ui
FILES="$U/connect/ConnectScreen.kt $U/settings/SettingsScreen.kt $U/settings/SettingsComponents.kt $U/settings/AccountAuthSection.kt $U/CloudSecretField.kt ac_cloud-mail/app/src/main/kotlin/app/sterna/fleet/CloudSecrets.kt"
check() {
python3 - "$1" $FILES <<'PY'
import os, sys
R = sys.argv[1]; F = sys.argv[2:]
rd = lambda p: open(os.path.join(R, p), encoding="utf-8").read()
connect, screen, comps, auth, field, secrets = [rd(p) for p in F]
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok(all("PasswordVisualTransformation(" not in t and "KeyboardType.Password" not in t for t in (connect, screen, comps, auth)), "C1 no screen of Cloud Mail types a secret", "C1 a Mail screen types a secret")
ok(connect.count("CloudSecretField(") >= 2 and "CloudSecrets.passwordId(account.email)" in connect and "CloudSecrets.tokenId(email)" in connect, "C2 Connect (walk, manual form, import sign-in) takes the secret from Cloud Account", "C2 Connect lost the Cloud Account read")
ok("CloudSecretField(" in screen and "CloudSecrets.tokenId(username)" in screen and "CloudSecrets.passwordId(username)" in screen, "C3 the account screen takes the password or API token from Cloud Account", "C3 the account screen lost the read")
ok("CloudSecrets.GATEWAY_TOKEN_ID" in auth and "onFleetToken(it.trim())" in auth, "C4 Authentication takes the gateway token from Cloud Account into the same callback", "C4 Authentication lost the read")
ok("AccountClient.credential(" in secrets and "AccountClient.openCredentials(" in secrets and "AppCredentialsContract.slug(" in secrets and "<redacted>" in secrets, "C5 CloudSecrets reads through the AccountClient with catalogue ids, redacted", "C5 CloudSecrets lost the catalogue read")
ok("onTaken(t.secret)" in field and "CloudSecrets.open(ctx)" in field and "Dispatchers.IO" in field, "C6 the field reads off the main thread, hands the value to the caller and links to Cloud Account", "C6 the field is broken")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file old new
  rm -rf "$TMP/r"; for f in $FILES; do mkdir -p "$TMP/r/$(dirname "$f")"; cp "$ROOT/$f" "$TMP/r/$f"; done
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "a password field is back on the account screen" "$U/settings/SettingsScreen.kt" 'CloudSecretField(' 'val v = PasswordVisualTransformation(); CloudSecretField('
mutate "the masked field is back in SettingTextField" "$U/settings/SettingsComponents.kt" 'singleLine = true,' 'singleLine = true, visualTransformation = PasswordVisualTransformation(),'
mutate "the import sign-in drops the read" "$U/connect/ConnectScreen.kt" 'CloudSecrets.passwordId(account.email)' '""'
mutate "the API-token id is dropped" "$U/settings/SettingsScreen.kt" 'CloudSecrets.tokenId(username)' '""'
mutate "the gateway token is no longer taken" "$U/settings/AccountAuthSection.kt" 'onFleetToken(it.trim())' 'Unit'
mutate "the read is dropped" "ac_cloud-mail/app/src/main/kotlin/app/sterna/fleet/CloudSecrets.kt" 'AccountClient.credential(' 'Other.credential('
mutate "the value leaks in the log form" "ac_cloud-mail/app/src/main/kotlin/app/sterna/fleet/CloudSecrets.kt" '<redacted>' '$secret'
mutate "the link is dropped" "$U/CloudSecretField.kt" 'CloudSecrets.open(ctx)' '""'
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
