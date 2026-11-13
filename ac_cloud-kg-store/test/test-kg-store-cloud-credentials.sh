#!/usr/bin/env bash
# #1693 KG Store's Me page types no GitHub token: it takes it from Cloud Account (App credentials) over the per-credential grant and
# hands it to GitConnect, as before. Mutations are planted in a scratch copy and must go RED.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
F=ac_cloud-kg-store/app/src/main/java/com/diegonmarcos/cloudkgstore/ui/NativePages.kt
G=ac_cloud-kg-store/app/build.gradle
check() {
python3 - "$1" "$F" "$G" <<'PY'
import os, sys
R, F, G = sys.argv[1:4]
rd = lambda p: open(os.path.join(R, p), encoding="utf-8").read()
src, gr = rd(F), rd(G)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok("PasswordVisualTransformation(" not in src and "KeyboardType.Password" not in src, "K1 the Me page types no secret", "K1 the Me page types a token")
ok('AccountClient.credential(ctx, "git.github_token"' in src and "gc.saveToken(c.value)" in src and "AccountClient.openCredentials(ctx)" in src, "K2 it takes the token from Cloud Account into GitConnect and links there", "K2 the Cloud Account read is gone")
ok("project(':libs:fleet-endpoints-android')" in gr, "K3 the app links the AccountClient half", "K3 the app does not link fleet-endpoints-android")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file old new
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$(dirname "$F")" "$TMP/r/$(dirname "$G")"; cp "$ROOT/$F" "$TMP/r/$F"; cp "$ROOT/$G" "$TMP/r/$G"
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "a token field is back" "$F" 'Text(credLine.ifBlank' 'val v = PasswordVisualTransformation(); Text(credLine.ifBlank'
mutate "the read is dropped" "$F" 'AccountClient.credential(' 'Other.credential('
mutate "the token no longer reaches GitConnect" "$F" 'gc.saveToken(c.value)' 'Unit'
mutate "the link is dropped" "$F" 'AccountClient.openCredentials(ctx)' '""'
mutate "the lib is unlinked" "$G" "project(':libs:fleet-endpoints-android')" "project(':libs:other')"
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
