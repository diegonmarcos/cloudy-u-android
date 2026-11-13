#!/usr/bin/env bash
# #1693 the Dagu bearer token are typed in Cloud Account (App credentials): libs:ops's login page types no password,
# reads both through OpsCredentials, and the SuperApp wires that hook to its AccountClient. Mutations are planted in a scratch copy.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
CH=ab_cloud-libs-shared/libs/ops/src/main/java/com/diegonmarcos/superapp/ops
APP=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/App.kt
C3=ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt
check() {
python3 - "$1" "$CH" "$APP" "$C3" <<'PY'
import os, sys
R, CH, APP, C3 = sys.argv[1:5]
rd = lambda p: open(os.path.join(R, p), encoding="utf-8").read()
frag, cred, app, c3 = rd(CH + "/dagu/DaguFragment.kt"), rd(CH + "/OpsCredentials.kt"), rd(APP), rd(C3)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok("PasswordVisualTransformation(" not in frag and "KeyboardType.Password" not in frag, "M1 the sign-in page types no secret", "M1 the Dagu page types a token")
ok("OpsCredentials.read(" in frag and "OpsCredentials.ID_DAGU_TOKEN" in frag and "OpsCredentials.open(" in frag, "M2 the page reads the id and links to Cloud Account", "M2 the page does not read Cloud Account")
ok('"dagu.token"' in cred and "<redacted>" in cred, "M3 the ids are the catalogue's and the log form is redacted", "M3 ids or redaction lost")
ok("OpsCredentials.read =" in app and "AccountClient.credential(" in app and "OpsCredentials.open =" in app and "AccountClient.openCredentials(" in app, "M4 the SuperApp wires both hooks to AccountClient", "M4 the SuperApp does not wire the hooks")
ok("OpsCredentials.read =" in c3 and "AccountClient.credential(" in c3 and "OpsCredentials.open =" in c3, "M5 Cloud C3 wires both hooks too", "M5 Cloud C3 does not wire the hooks")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file old new
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$CH" "$TMP/r/$(dirname "$APP")" "$TMP/r/$(dirname "$C3")"; cp -r "$ROOT/$CH/." "$TMP/r/$CH/"; cp "$ROOT/$APP" "$TMP/r/$APP"; cp "$ROOT/$C3" "$TMP/r/$C3"
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "a secret field is back" "$CH/dagu/DaguFragment.kt" 'Header("Dagu — Sign in")' 'Header("x"); val v = PasswordVisualTransformation()'
mutate "the page stops reading the token" "$CH/dagu/DaguFragment.kt" 'OpsCredentials.ID_DAGU_TOKEN' 'ID_OTHER'
mutate "the link is dropped" "$CH/dagu/DaguFragment.kt" 'OpsCredentials.open(ctx)' 'null'
mutate "the id drifts from the catalogue" "$CH/OpsCredentials.kt" '"dagu.token"' '"dg.t"'
mutate "the log form leaks" "$CH/OpsCredentials.kt" '<redacted>' '$value'
mutate "the SuperApp stops wiring the read" "$APP" 'AccountClient.credential(' 'Other.credential('
mutate "Cloud C3 stops wiring the read" "$C3" 'AccountClient.credential(' 'Other.credential('
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
