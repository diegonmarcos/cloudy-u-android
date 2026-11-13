#!/usr/bin/env bash
# #1703 Cloud Mail's OAuth AUTHORIZATION-CODE grant (custom-tab redirect + PKCE) is run by Cloud Account, like the device grant (#1693). Static, no build, no network.
#   C1 Cloud Mail holds no part of the grant: no PKCE draw, verifier, authorization URL, code exchange or browser view of an authorization page
#      (OAuthCodeSignIn, OAuthRedirect, OAuthClient, MailRepository); it hands the redirect's code + state to Cloud Account and takes the tokens back;
#   C2 the Connect screen opens CLOUD ACCOUNT's browser activity for the handle (never the authorization page itself);
#   C3 the broker speaks the two code methods of the contract, and the repository only reaches them through it;
#   C4 Cloud Account answers them behind the fleet signature check AND the mail.oauth grant (default deny), passing the caller so the redirect is the caller's own scheme;
#   C5 Cloud Account's browser activity is signature-permission gated, owner-checked, and opens only a request still in flight;
#   C6 the grant's rules live there: the state is compared before the code or the error is read, the exchange carries the verifier of THAT request, S256 only,
#      a forged return does not consume the real request, nothing is logged;
#   C7 the JVM suites exist (Account: MailOAuthCodeSessionsTest + MailOAuthProviderTest, fleet-endpoints: MailOAuthContractTest, Mail: OAuthRedirectTest + OAuthCodeWiringTest + CloudAccountOAuthBrokerTest).
# Every rule is mutation-proved; each mutation is checked to have APPLIED.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"; PROF="$LIBS/account/src/main/java/com/diegonmarcos/superapp/profile"
SIGN="$APP/app/src/main/kotlin/app/sterna/ui/connect/OAuthCodeSignIn.kt"
RED="$APP/app/src/main/kotlin/app/sterna/ui/connect/OAuthRedirect.kt"
CS="$APP/app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
BR="$APP/app/src/main/kotlin/app/sterna/fleet/CloudAccountOAuthBroker.kt"
OC="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap/OAuthClient.kt"
MR="$APP/core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
AD="$PROF/AccountData.kt"; SES="$PROF/MailOAuthCodeSessions.kt"; ACT="$PROF/MailOAuthCodeActivity.kt"
MF="$ROOT/ac_cloud-account/app/src/main/AndroidManifest.xml"
FILES=("$SIGN" "$RED" "$CS" "$BR" "$OC" "$MR" "$AD" "$SES" "$ACT" "$MF")
TESTS=("$ROOT/ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/MailOAuthCodeSessionsTest.kt" "$ROOT/ac_cloud-account/app/src/test/java/com/diegonmarcos/cloudaccount/MailOAuthProviderTest.kt"
       "$LIBS/fleet-endpoints/src/test/kotlin/com/diegonmarcos/superapp/fleetendpoints/MailOAuthContractTest.kt" "$APP/app/src/test/kotlin/app/sterna/ui/connect/OAuthRedirectTest.kt"
       "$APP/app/src/test/kotlin/app/sterna/ui/connect/OAuthCodeWiringTest.kt" "$APP/app/src/test/kotlin/app/sterna/fleet/CloudAccountOAuthBrokerTest.kt")
for f in "${FILES[@]}" "${TESTS[@]}"; do [ -f "$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }; done
PASS=0; FAIL=0; ok() { PASS=$((PASS+1)); echo "  ok: $1"; }; bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
code() { grep -vE '^\s*(//|\*|/\*)' "$1"; }

# check SIGN RED CS BR OC MR AD SES ACT MF  (in FILES order)
check() {
    local sign="$1" red="$2" cs="$3" br="$4" oc="$5" mr="$6" ad="$7" ses="$8" act="$9" mf="${10}"
    # C1
    for f in "$sign" "$red" "$oc" "$mr"; do
        code "$f" | grep -E >/dev/null '\bPkce\b|codeVerifier|code_verifier|newCodeVerifier|buildAuthorizationUrl|fun exchangeCode|code_challenge' && { echo "C1 $(basename "$f") still holds part of the grant"; return 1; }
    done
    code "$sign" | grep >/dev/null 'Intent.ACTION_VIEW' && { echo "C1 the driver opens a browser itself"; return 1; }
    code "$sign" | grep >/dev/null 'repo.finishAuthorizationCode(request.handle, code = code, state = state, error = error)' && code "$sign" | grep >/dev/null 'repo.startAuthorizationCode(' || { echo "C1 the driver does not go through Cloud Account"; return 1; }
    code "$mr" | grep >/dev/null 'codeBroker().start(' && code "$mr" | grep >/dev/null 'codeBroker().finish(' || { echo "C3 the repository does not reach the grant through the broker"; return 1; }
    # C2
    code "$cs" | grep >/dev/null 'Intent(MailOAuthContract.ACTION_CODE_OPEN)' && code "$cs" | grep >/dev/null 'putExtra(MailOAuthContract.EXTRA_HANDLE, state.handle)' || { echo "C2 the screen does not open Cloud Account's browser activity for the handle"; return 1; }
    code "$cs" | grep >/dev/null 'Uri.parse(state.authorizationUrl)' && { echo "C2 the screen opens an authorization page itself"; return 1; }
    # C3
    code "$br" | grep >/dev/null 'MailOAuthContract.METHOD_CODE_START' && code "$br" | grep >/dev/null 'MailOAuthContract.METHOD_CODE_FINISH' || { echo "C3 the broker lacks the code methods"; return 1; }
    # C4
    python3 - "$ad" <<'PY' || { echo "C4 the code methods are not grant-gated behind the signature check, or do not pass the caller"; return 1; }
import sys
s = open(sys.argv[1], encoding='utf-8').read()
gate = s.index('caller is not a fleet app'); m = s.index('MailOAuthContract.METHOD_CODE_START, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_FINISH ->')
body = s[m:m + 900]
sys.exit(0 if gate < m and 'who != ctx.packageName' in body and 'grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.GRANT_REQUEST)' in body and 'error("no grant for $who")' in body and 'MailOAuthCodeSessions.start(ctx, arg, who)' in body else 1)
PY
    # C5
    python3 - "$mf" <<'PY' || { echo "C5 the browser activity is not exported behind the signature permission with its action"; return 1; }
import sys, re
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('com.diegonmarcos.superapp.profile.MailOAuthCodeActivity'); block = s[i:s.index('</activity>', i)]
sys.exit(0 if 'android:exported="true"' in block and 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' in block and 'com.diegonmarcos.cloudaccount.MAIL_OAUTH_CODE' in block else 1)
PY
    code "$act" | grep >/dev/null 'SignInOwner.isOwner(this)' && code "$act" | grep >/dev/null 'MailOAuthCodeSessions.authorizationUrl(' || { echo "C5 the activity is not owner-checked or opens something that is not a request in flight"; return 1; }
    # C6
    code "$ses" | grep -E >/dev/null 'Log\.[a-z]\(|println\(|printStackTrace' && { echo "C6 the grant logs"; return 1; }
    python3 - "$ses" <<'PY' || { echo "C6 the redirect verdict order, the verifier's provenance, S256 or the forged-return rule is gone"; return 1; }
import sys
s = open(sys.argv[1], encoding='utf-8').read()
v = s[s.index('fun redirectVerdict'):]; v = v[:v.index('\n    }')]
order = v.index('state != pending.state') < v.index('error.isNullOrBlank') < v.index('code.isNullOrBlank')
prov = 'v.request.codeVerifier' in s[s.index('fun tokenExchangeFor'):s.index('fun tokenExchangeFor') + 400]
s256 = 'CODE_CHALLENGE_METHOD = "S256"' in s and '"plain"' not in s
keep = 'if (v !is Verdict.NoPendingRequest && v !is Verdict.StateMismatch) map.remove(handle)' in s
spec = 'SignInOwner.isOwner(ctx)' in s
sys.exit(0 if order and prov and s256 and keep and spec else 1)
PY
    return 0
}
echo "== C1-C6 =="
msg=$(check "${FILES[@]}") && ok "Mail holds none of the grant; the screen opens Cloud Account's activity; the broker, the gated provider, the activity and the grant's rules" || bad "$msg"
echo "== C7 =="
ok "the six JVM suites exist"
echo "-- mutations --"
m() {  # label file old new
    local label="$1" f="$2" old="$3" new="$4" d="$TMP/$(basename "$2")"; cp "$f" "$d"
    python3 - "$d" "$old" "$new" <<'PY' || { bad "mutation did not APPLY: $label"; return; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding='utf-8').read()
if o not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(o, n, 1))
PY
    local a=("${FILES[@]}") i
    for i in "${!FILES[@]}"; do [ "${FILES[$i]}" = "$f" ] && a[$i]="$d"; done
    check "${a[@]}" >/dev/null 2>&1 && bad "mutation SURVIVED: $label" || ok "mutation caught: $label"
}
m "Mail draws a PKCE verifier again" "$SIGN" 'val answer = repo.finishAuthorizationCode(' 'val verifier = Pkce.newCodeVerifier(); val answer = repo.finishAuthorizationCode('
m "Mail builds the authorization URL again" "$MR" 'suspend fun finishAuthorizationCode' 'fun buildAuthorizationUrl() = "" ; suspend fun finishAuthorizationCode'
m "the OAuth client exchanges the code again" "$OC" 'suspend fun refresh(' 'suspend fun exchangeCode() {}
    suspend fun refresh('
m "the driver opens the browser itself" "$SIGN" 'slot.disarm()
        job = scope.launch {' 'slot.disarm(); appContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW))
        job = scope.launch {'
m "the redirect is not handed to Cloud Account" "$SIGN" 'repo.finishAuthorizationCode(request.handle, code = code, state = state, error = error)' 'null as DeviceTokenResult'
m "the screen opens the authorization page itself" "$CS" 'Intent(MailOAuthContract.ACTION_CODE_OPEN)' 'Intent(Intent.ACTION_VIEW, Uri.parse(state.authorizationUrl))'
m "the screen drops the handle" "$CS" 'putExtra(MailOAuthContract.EXTRA_HANDLE, state.handle)' 'putExtra("x", "y")'
m "the broker drops the finish method" "$BR" 'MailOAuthContract.METHOD_CODE_FINISH' 'MailOAuthContract.METHOD_POLL'
m "the repository skips the broker" "$MR" 'codeBroker().finish(' 'null.finish('
m "the code methods skip the grant" "$AD" 'MailOAuthCodeSessions.start(ctx, arg, who)' 'MailOAuthCodeSessions.start(ctx, arg, ctx.packageName)'
m "the code methods lose the grant check" "$AD" 'if (who != ctx.packageName && !AccountVault(ctx).grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.GRANT_REQUEST)) error("no grant for $who")
                    else if (method == com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_START)' 'if (false) error("no grant for $who")
                    else if (method == com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_START)'
m "the activity loses its permission" "$MF" 'android:exported="true"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
            android:excludeFromRecents' 'android:exported="true"
            android:excludeFromRecents'
m "the activity stops being owner-checked" "$ACT" 'SignInOwner.isOwner(this)' 'true'
m "the error is read before the state" "$SES" 'if (state.isNullOrBlank() || state != pending.state) return Verdict.StateMismatch
        if (!error.isNullOrBlank()) return Verdict.Refused(error)' 'if (!error.isNullOrBlank()) return Verdict.Refused(error)
        if (state.isNullOrBlank() || state != pending.state) return Verdict.StateMismatch'
m "the exchange redraws the verifier" "$SES" 'v.request.codeVerifier' 'Pkce.newCodeVerifier()'
m "a forged return consumes the real request" "$SES" 'if (v !is Verdict.NoPendingRequest && v !is Verdict.StateMismatch) map.remove(handle)' 'map.remove(handle)'
m "the grant announces plain" "$SES" 'CODE_CHALLENGE_METHOD = "S256"' 'CODE_CHALLENGE_METHOD = "plain"'
m "the grant logs the code" "$SES" 'private val slots = Slots()' 'private val slots = Slots(); private fun leak(c: String) = android.util.Log.d("x", c)'
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" = 0 ]
