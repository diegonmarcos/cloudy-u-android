#!/usr/bin/env bash
# #1693 Cloud Matrix keeps the upstream Element X login and adds the fleet's entry: "Sign in via Cloud Account" opens Cloud Account at the
# Matrix page. Static. The entry is on the onboarding buttons, names its strings and tag, starts nothing itself (an intent to Cloud Account
# only), says so in a toast when Cloud Account is missing, and the manifest makes the package visible. Each rule is mutation-proved.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
V="$APP/features/login/impl/src/main/kotlin/io/element/android/features/login/impl/screens/onboarding/OnBoardingView.kt"
S="$APP/features/login/impl/src/main/res/values/cloud_account.xml"
M="$APP/app/src/main/AndroidManifest.xml"
T="$APP/libraries/testtags/src/main/kotlin/io/element/android/libraries/testtags/TestTags.kt"
for f in "$V" "$S" "$M" "$T"; do [ -f "$f" ] || { echo "FAIL: $f missing - unrun, not passing"; exit 1; }; done
PASS=0; FAIL=0; ok() { PASS=$((PASS+1)); echo "  ok: $1"; }; bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
check() {  # view strings manifest tags
    grep -q 'text = stringResource(id = R.string.cloud_account_sign_in)' "$1" && grep -q 'testTag(TestTags.onBoardingCloudAccount)' "$1" || { echo "the onboarding lacks the Cloud Account entry"; return 1; }
    grep -q 'putExtra("provider", "matrix")' "$1" && grep -q '"\$CLOUD_ACCOUNT_PACKAGE.OPEN"' "$1" || { echo "the entry does not open Cloud Account at the Matrix page"; return 1; }
    grep -q 'R.string.cloud_account_missing' "$1" && grep -q 'runCatching { context.startActivity(intent) }' "$1" || { echo "a missing Cloud Account is not a toast"; return 1; }
    grep -q 'name="cloud_account_sign_in"' "$2" && grep -q 'name="cloud_account_missing"' "$2" || { echo "the strings are missing"; return 1; }
    grep -q '<package android:name="com.diegonmarcos.cloudaccount" />' "$3" || { echo "Cloud Account is not visible to this app (API 30+)"; return 1; }
    grep -q 'val onBoardingCloudAccount' "$4" || { echo "the test tag is missing"; return 1; }
    grep -Eq 'AuthenticationService|login\(|password' <(sed -n '/private fun openCloudAccountSignIn/,$p' "$1") && { echo "the entry signs in by itself"; return 1; }
    return 0
}
msg=$(check "$V" "$S" "$M" "$T") && ok "the entry, its strings, its tag, the intent, the toast and the manifest query" || bad "$msg"
mut() { local label="$1" f="$2" old="$3" new="$4"; local d="$TMP/$(basename "$f")"; cp "$f" "$d"
    python3 - "$d" "$old" "$new" <<'PY' || { bad "mutation did not APPLY: $label"; return; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding='utf-8').read()
if o not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(o, n, 1))
PY
    local a=("$V" "$S" "$M" "$T"); case "$f" in "$V") a[0]="$d";; "$S") a[1]="$d";; "$M") a[2]="$d";; "$T") a[3]="$d";; esac
    check "${a[@]}" >/dev/null 2>&1 && bad "mutation SURVIVED: $label" || ok "mutation caught: $label"; }
mut "the entry dropped" "$V" 'text = stringResource(id = R.string.cloud_account_sign_in)' 'text = ""'
mut "opens another page" "$V" 'putExtra("provider", "matrix")' 'putExtra("provider", "x")'
mut "a missing Cloud Account crashes" "$V" 'runCatching { context.startActivity(intent) }' 'run { context.startActivity(intent) }'
mut "the package is not visible" "$M" '<package android:name="com.diegonmarcos.cloudaccount" />' ''
mut "the string is missing" "$S" 'name="cloud_account_missing"' 'name="x"'
mut "the entry signs in by itself" "$V" '    val intent = android.content.Intent("$CLOUD_ACCOUNT_PACKAGE.OPEN")' '    login(password)
    val intent = android.content.Intent("$CLOUD_ACCOUNT_PACKAGE.OPEN")'
echo; echo "== RESULT: $PASS ok, $FAIL failed =="; [ "$FAIL" -eq 0 ]
