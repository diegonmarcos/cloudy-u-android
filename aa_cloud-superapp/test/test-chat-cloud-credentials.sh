#!/usr/bin/env bash
# #1693 the Mattermost login and password are typed in Cloud Account (App credentials): libs:chat's login page types no password,
# reads both through ChatCredentials, and the SuperApp wires that hook to its AccountClient. Mutations are planted in a scratch copy.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
CH=ab_cloud-libs-shared/libs/chat/src/main/java/com/diegonmarcos/superapp/chat
APP=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/App.kt
check() {
python3 - "$1" "$CH" "$APP" <<'PY'
import os, sys
R, CH, APP = sys.argv[1:4]
rd = lambda p: open(os.path.join(R, p), encoding="utf-8").read()
frag, cred, app = rd(CH + "/MattermostFragment.kt"), rd(CH + "/ChatCredentials.kt"), rd(APP)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok("PasswordVisualTransformation(" not in frag and "KeyboardType.Password" not in frag, "M1 the login page types no secret", "M1 the Mattermost page types a password")
ok("ChatCredentials.read(" in frag and "ChatCredentials.ID_PASSWORD" in frag and "ChatCredentials.ID_LOGIN" in frag and "ChatCredentials.open(" in frag, "M2 the page reads both ids and links to Cloud Account", "M2 the page does not read Cloud Account")
ok('"mattermost.login"' in cred and '"mattermost.password"' in cred and "<redacted>" in cred, "M3 the ids are the catalogue's and the log form is redacted", "M3 ids or redaction lost")
ok("ChatCredentials.read =" in app and "AccountClient.credential(" in app and "ChatCredentials.open =" in app and "AccountClient.openCredentials(" in app, "M4 the SuperApp wires both hooks to AccountClient", "M4 the SuperApp does not wire the hooks")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file old new
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$(dirname "$CH")" "$TMP/r/$(dirname "$APP")"; cp -r "$ROOT/$CH" "$TMP/r/$CH"; cp "$ROOT/$APP" "$TMP/r/$APP"
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "a password field is back" "$CH/MattermostFragment.kt" 'Header("Mattermost — Sign in")' 'Header("x"); val v = PasswordVisualTransformation()'
mutate "the page stops reading the password" "$CH/MattermostFragment.kt" 'ChatCredentials.ID_PASSWORD' 'ID_OTHER'
mutate "the link is dropped" "$CH/MattermostFragment.kt" 'ChatCredentials.open(ctx)' 'null'
mutate "the id drifts from the catalogue" "$CH/ChatCredentials.kt" '"mattermost.password"' '"mm.pw"'
mutate "the log form leaks" "$CH/ChatCredentials.kt" '<redacted>' '$value'
mutate "the SuperApp stops wiring the read" "$APP" 'AccountClient.credential(' 'Other.credential('
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
