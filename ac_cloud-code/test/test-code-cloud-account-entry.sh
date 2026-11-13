#!/usr/bin/env bash
# #1693 Cloud Code keeps the Acode account login (the plugin marketplace's own, an upstream login) and adds the fleet's entry: the sidebar's
# sign-in choice offers "Sign in via Cloud Account", which opens Cloud Account at the Code page through src/lib/cloudAccount.js (a vitest
# suite proves its three outcomes). Static, no build. Each rule is mutation-proved.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
L="$APP/src/lib/cloudAccount.js"; SB="$APP/src/components/sidebar/index.js"; CX="$APP/config.xml"; T="$APP/tests/unit/cloudAccount.test.js"
for f in "$L" "$SB" "$CX" "$T"; do [ -f "$f" ] || { echo "FAIL: $f missing - unrun, not passing"; exit 1; }; done
PASS=0; FAIL=0; ok() { PASS=$((PASS+1)); echo "  ok: $1"; }; bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
check() {  # lib sidebar config.xml
    grep -q 'CLOUD_ACCOUNT_PACKAGE = "com.diegonmarcos.cloudaccount"' "$1" && grep -q 'provider: "code"' "$1" || { echo "the lib does not target Cloud Account's Code page"; return 1; }
    grep -q 'system.launchApp(' "$1" && grep -q 'say("Cloud Account is not installed' "$1" || { echo "the lib does not launch Cloud Account or say it is missing"; return 1; }
    grep -Eq 'fetch\(|XMLHttpRequest|localStorage|password|token' "$1" && { echo "the lib reaches the network or holds a credential"; return 1; }
    grep -q 'await openCloudAccountSignIn(globalThis.system' "$2" && grep -q 'value: "cloud-account"' "$2" && grep -q 'value: "acode"' "$2" || { echo "the sidebar sign-in choice lacks the Cloud Account entry or lost the Acode login"; return 1; }
    grep -q '<package android:name="com.diegonmarcos.cloudaccount" />' "$3" || { echo "Cloud Account is not visible to this app"; return 1; }
    return 0
}
msg=$(check "$L" "$SB" "$CX") && ok "the lib, the sidebar choice (Acode login kept) and the manifest query" || bad "$msg"
mut() { local label="$1" f="$2" old="$3" new="$4"; local d="$TMP/$(basename "$f")"; cp "$f" "$d"
    python3 - "$d" "$old" "$new" <<'PY' || { bad "mutation did not APPLY: $label"; return; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding='utf-8').read()
if o not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(o, n, 1))
PY
    local a=("$L" "$SB" "$CX"); case "$f" in "$L") a[0]="$d";; "$SB") a[1]="$d";; "$CX") a[2]="$d";; esac
    check "${a[@]}" >/dev/null 2>&1 && bad "mutation SURVIVED: $label" || ok "mutation caught: $label"; }
mut "another page" "$L" 'provider: "code"' 'provider: "x"'
mut "no missing-app sentence" "$L" 'say("Cloud Account is not installed' 'say("x'
mut "the lib fetches" "$L" 'export function openCloudAccountSignIn' 'const x = () => fetch("https://x");
export function openCloudAccountSignIn'
mut "the entry dropped" "$SB" 'value: "cloud-account"' 'value: "x"'
mut "the Acode login dropped" "$SB" 'value: "acode"' 'value: "x"'
mut "not visible" "$CX" '<package android:name="com.diegonmarcos.cloudaccount" />' ''
echo; echo "== RESULT: $PASS ok, $FAIL failed =="; [ "$FAIL" -eq 0 ]
