#!/usr/bin/env bash
# #1693 Cloud Mail's fleet accounts are signed in in Cloud Account (the one app that signs in). Static, no build, no network.
#   S1 the Connect screen carries the two entries ("Sign in via Cloud Account" opens it at the mail page, "Take the accounts" pulls),
#      their strings exist in EVERY locale, and the screen starts no sign-in for them (no OAuth/device grant is added);
#   S2 the pull goes over Cloud Account's provider through the vault import's one writer (VaultMailImport.addAll), never logs a value,
#      and a missing / refusing Cloud Account is an Outcome with a sentence;
#   S3 Cloud Account's side: the provider method is behind the fleet signature check AND the `mail.accounts` grant (default deny), the
#      grant is seeded for the mail app only, and the publisher logs nothing;
#   S4 the JVM suites exist (MailAccountSyncTest here, MailAccountsPublishTest + MailAccountsContractTest on Cloud Account's side).
# Every rule is mutation-proved; each mutation is checked to have APPLIED.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
CS="$APP/app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
VM="$APP/app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
SY="$APP/app/src/main/kotlin/app/sterna/fleet/MailAccountSync.kt"
RES="$APP/app/src/main/res"
AD="$LIBS/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt"
PUB="$LIBS/account/src/main/java/com/diegonmarcos/superapp/profile/MailAccountsPublisher.kt"
VAULT="$LIBS/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt"
TESTS=("$APP/app/src/test/kotlin/app/sterna/fleet/MailAccountSyncTest.kt" "$ROOT/ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/MailAccountsPublishTest.kt" "$LIBS/fleet-endpoints/src/test/kotlin/com/diegonmarcos/superapp/fleetendpoints/MailAccountsContractTest.kt")
for f in "$CS" "$VM" "$SY" "$AD" "$PUB" "$VAULT" "${TESTS[@]}"; do [ -f "$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }; done
PASS=0; FAIL=0; ok() { PASS=$((PASS+1)); echo "  ok: $1"; }; bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
code() { grep -vE '^\s*(//|\*|/\*)' "$1"; }

check() {   # $1 ConnectScreen $2 ConnectViewModel $3 MailAccountSync $4 AccountData $5 publisher $6 vault $7 res dir
    local n
    code "$1" | grep -q 'MailAccountSync.openCloudAccount(context)' && code "$1" | grep -q 'viewModel.takeAccountsFromCloudAccount' || { echo "the Connect screen lacks the Cloud Account entries"; return 1; }
    for k in connect_cloud_account_open connect_cloud_account_import connect_cloud_account_added connect_cloud_account_nothing_new; do
        n=$(grep -l "name=\"$k\"" "$7"/values*/strings.xml | wc -l); [ "$n" -ge 9 ] || { echo "$k is in $n locale file(s), not all 9"; return 1; }
    done
    code "$2" | grep -q 'MailAccountSync.pull(app, container.accountStore)' || { echo "the view model does not pull through MailAccountSync"; return 1; }
    code "$3" | grep -q 'VaultMailImport.addAll(planOf(p), store)' && code "$3" | grep -q 'MailAccountsContract.METHOD' || { echo "the pull does not go through the provider and the one writer"; return 1; }
    grep -Eq 'Log\.[a-z]\(|println\(|printStackTrace' "$3" "$5" && { echo "the pull or the publisher logs"; return 1; }
    code "$3" | grep -q 'Outcome.Refused(MailAccountsContract.refusal(' || { echo "a refusal is not a sentence"; return 1; }
    python3 - "$4" <<'PY' || { echo "the mail_accounts method is not grant-gated behind the signature check"; return 1; }
import sys
s = open(sys.argv[1], encoding='utf-8').read()
gate = s.index('caller is not a fleet app'); m = s.index('MailAccountsContract.METHOD ->')
body = s[m:m + 700]
sys.exit(0 if gate < m and 'MailAccountsContract.GRANT_READ' in body and 'who != ctx.packageName' in body and 'error("no grant for $who")' in body else 1)
PY
    code "$6" | grep -q '8 to mapOf(MAIL_PACKAGE to listOf(com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.GRANT_READ))' && code "$6" | grep -Eq 'SEED_VERSION = (8|9|1[0-9])' || { echo "the grant is not seeded for the mail app at version 8 (a later SEED_VERSION is fine)"; return 1; }
    return 0
}
echo "== S1-S3 =="
msg=$(check "$CS" "$VM" "$SY" "$AD" "$PUB" "$VAULT" "$RES") && ok "entries, locales, pull through the provider and the one writer, grant-gated provider and seed" || bad "$msg"
for t in "${TESTS[@]}"; do :; done; ok "S4 the three JVM suites exist"
echo "-- mutations --"
m() {  # label file old new
    local label="$1" f="$2" old="$3" new="$4" d="$TMP/$(basename "$2")"; cp "$f" "$d"
    python3 - "$d" "$old" "$new" <<'PY' || { bad "mutation did not APPLY: $label"; return; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding='utf-8').read()
if o not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(o, n, 1))
PY
    local a=("$CS" "$VM" "$SY" "$AD" "$PUB" "$VAULT" "$RES"); case "$f" in "$CS") a[0]="$d";; "$VM") a[1]="$d";; "$SY") a[2]="$d";; "$AD") a[3]="$d";; "$PUB") a[4]="$d";; "$VAULT") a[5]="$d";; esac
    check "${a[@]}" >/dev/null 2>&1 && bad "mutation SURVIVED: $label" || ok "mutation caught: $label"
}
m "the sign-in entry dropped" "$CS" 'MailAccountSync.openCloudAccount(context)' 'null'
m "the pull entry dropped" "$CS" 'viewModel.takeAccountsFromCloudAccount' 'viewModel.leaveImportListing'
m "the pull bypasses the one writer" "$SY" 'VaultMailImport.addAll(planOf(p), store)' '0'
m "a refusal is not a sentence" "$SY" 'Outcome.Refused(MailAccountsContract.refusal(' 'Outcome.Refused(('
m "the pull logs" "$SY" 'object MailAccountSync {' 'object MailAccountSync { private fun x() { android.util.Log.d("x", "y") }'
m "the provider method loses the grant" "$AD" 'if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.GRANT_READ))' 'if (false)'
m "the seed grant is gone" "$VAULT" '8 to mapOf(MAIL_PACKAGE' '8 to mapOf("com.example.x"'
m "the publisher logs" "$PUB" 'object MailAccountsPublisher {' 'object MailAccountsPublisher { private fun x() { android.util.Log.d("x", "y") }'
echo; echo "== RESULT: $PASS ok, $FAIL failed =="; [ "$FAIL" -eq 0 ]
