#!/usr/bin/env bash
# sign-in-owner-guard.test (#1693): prove cloud-android-sign-in-owner-guard.py FAILS when an app other
# than Cloud Account starts a sign-in, when an exemption goes stale and when the owner is emptied,
# REFUSES (2) with no owner, and passes on a clean fleet and on the real tree. A guard only ever seen
# green proves nothing. Nothing outside coreutils + python3.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-sign-in-owner-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "${WORK:?}"' EXIT

POL="$WORK/policy.json"
cat > "$POL" <<'JSON'
{"owner": "ac_cloud-account", "owner_impl": {"ab_cloud-libs-shared/libs/auth": "impl"},
 "owner_must_hold": [{"file": "ac_cloud-account/app/build.gradle", "needles": ["project(':libs:account')"], "why": "hosts Connect"}],
 "scan_prefixes": ["aa_", "ab_", "ac_"], "extensions": [".kt", ".java", ".json"],
 "skip_dirs": ["build"], "skip_path_parts": ["/src/test/"],
 "rules": {
  "gh-auth-login": {"pattern": "\"auth\"\\s*,\\s*\"login\"", "why": "gh auth login"},
  "engine-login-call": {"pattern": "\\b[gG]h[eE]ngine\\.login\\(|\\bengine\\.login\\(", "why": "engine login"},
  "engine-login-decl": {"pattern": "\"login_poll_ms\"", "why": "engines.gh"},
  "device-grant": {"pattern": "grant-type:device_code", "why": "device grant"},
  "oauth-browser": {"pattern": "CustomTabsIntent|net\\.openid\\.appauth", "why": "oauth browser"},
  "signin-ui": {"pattern": "(?<!fun )\\bSignInWays\\(", "why": "sign-in surface"},
  "credential-prompt": {"pattern": "PasswordVisualTransformation\\(", "file_context": "\\b(sign[ _-]?in|log[ _-]?in)\\b", "why": "password"}},
 "exempt": [{"path": "ac_cloud-fork/", "rules": ["oauth-browser"], "why": "upstream", "until": "permanent"}]}
JSON

fixture() {
  local t="$1"; rm -rf "$t"; mkdir -p "$t/ac_cloud-account/app/src/main/java" "$t/ab_cloud-libs-shared/libs/auth/src/main/java" \
    "$t/ac_cloud-demo/app/src/main/java" "$t/ac_cloud-demo/app/src/test/java" "$t/ac_cloud-fork/src"
  echo "implementation project(':libs:account')" > "$t/ac_cloud-account/app/build.gradle"
  echo 'fun go() { SignInWays(host); ghEngine.login(h) {} ; listOf("auth", "login") }' > "$t/ac_cloud-account/app/src/main/java/A.kt"
  echo 'fun SignInWays(host: Any) {} // grant-type:device_code' > "$t/ab_cloud-libs-shared/libs/auth/src/main/java/S.kt"
  echo 'fun row() { openCloudAccount("github") } // a password field elsewhere: PasswordVisualTransformation()' > "$t/ac_cloud-demo/app/src/main/java/Row.kt"
  echo 'fun t() { SignInWays(fake) } // sign in' > "$t/ac_cloud-demo/app/src/test/java/T.kt"
  echo 'import androidx.browser.customtabs.CustomTabsIntent;' > "$t/ac_cloud-fork/src/Auth.java"
}
run() { python3 "$GUARD" "$1" --policy "$POL" >"$WORK/out" 2>&1; echo $?; }

F="$WORK/f"; fixture "$F"
[ "$(run "$F")" = 0 ] && ok "clean fleet is green (owner, owner impl, test sources, a declared exemption, a password field off a sign-in page)" || { fail "clean fleet red"; cat "$WORK/out"; }

i=0
for bad in 'listOf("auth", "login")' 'ghEngine.login(host) { _, _ -> }' 'SignInWays(host = h)' 'val g = "urn:ietf:params:oauth:grant-type:device_code"' \
           'val t = CustomTabsIntent.Builder()' 'Text("Sign in"); OutlinedTextField(p, {}, visualTransformation = PasswordVisualTransformation())'; do
  i=$((i+1)); fixture "$F"; echo "fun x() { $bad }" > "$F/ac_cloud-demo/app/src/main/java/Bad$i.kt"
  [ "$(run "$F")" = 1 ] && ok "RED: an app starts a sign-in ($bad)" || fail "stayed green for: $bad"
done
fixture "$F"; echo '{"engines": {"gh": {"login_poll_ms": 1000}}}' > "$F/ac_cloud-demo/build.json"
[ "$(run "$F")" = 1 ] && ok "RED: an app declares engines.gh login timings" || fail "engines.gh decl stayed green"
fixture "$F"; echo 'class Auth {}' > "$F/ac_cloud-fork/src/Auth.java"
[ "$(run "$F")" = 1 ] && grep -q 'matches nothing' "$WORK/out" && ok "RED: a stale exemption (the list only shrinks)" || fail "stale exemption stayed green"
fixture "$F"; echo 'nothing' > "$F/ac_cloud-account/app/build.gradle"
[ "$(run "$F")" = 1 ] && ok "RED: the owner no longer hosts the Account page" || fail "emptied owner stayed green"
fixture "$F"; rm -rf "$F/ac_cloud-account"
[ "$(run "$F")" = 2 ] && ok "REFUSES (2) without the owner app" || fail "no owner did not refuse"

python3 "$GUARD" "$ROOT" >"$WORK/out" 2>&1 && ok "real tree green" || { fail "real tree red"; cat "$WORK/out"; }
[ "$FAILURES" = 0 ] && { echo "sign-in-owner-guard.test: all green"; exit 0; }
echo "sign-in-owner-guard.test: $FAILURES failure(s)"; exit 1
