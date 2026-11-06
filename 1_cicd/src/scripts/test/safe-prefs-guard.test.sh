#!/usr/bin/env bash
# #1106 the safe-prefs guard must be RED for a direct EncryptedSharedPreferences call and GREEN on the helper and the allow list.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/../cloud-android-safe-prefs-guard.py"
fails=0
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/lib/src/main"
  echo '{"allow":{}}' > "$r/1_cicd/src/data/safe-encrypted-prefs-guard.json"
  printf 'import androidx.security.crypto.EncryptedSharedPreferences\n%s\n' "$1" > "$r/lib/src/main/Store.kt"
  echo "$r"
}
chk() {
  local r; r="$(mk "$2")"
  python3 "$GUARD" "$r" > "$r/out" 2>&1; got=$?
  if [ "$got" -eq "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $3)"; cat "$r/out"; fails=$((fails+1)); fi
  rm -rf "$r"
}
chk "direct create is red"          'val p = EncryptedSharedPreferences.create(ctx, "x", k, a, b)' 1
chk "MasterKey.Builder is red"      'val k = MasterKey.Builder(ctx).build()' 1
chk "SafeEncryptedPrefs is green"   'val p = SafeEncryptedPrefs.prefs(ctx, "x")' 0
chk "a comment is green"            '// EncryptedSharedPreferences.create( was here' 0
# #1474 runtime classpath cases
mk2() { # $1 = crash dep line, $2 = lib dep lines
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ab_cloud-libs-shared/libs/crash" "$r/ab_cloud-libs-shared/libs/x"
  echo '{"allow":{}}' > "$r/1_cicd/src/data/safe-encrypted-prefs-guard.json"
  printf "dependencies {\n    %s\n}\n" "$1" > "$r/ab_cloud-libs-shared/libs/crash/build.gradle"
  printf "dependencies {\n    %s\n}\n" "$2" > "$r/ab_cloud-libs-shared/libs/x/build.gradle"
  echo "$r"
}
chk2() {
  local r; r="$(mk2 "$2" "$3")"
  python3 "$GUARD" "$r" > "$r/out" 2>&1; got=$?
  if [ "$got" -eq "$4" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $4)"; cat "$r/out"; fails=$((fails+1)); fi
  rm -rf "$r"
}
CO="compileOnly 'androidx.security:security-crypto:1.1.0'"
IM="implementation 'androidx.security:security-crypto:1.1.0'"
chk2 "crash compileOnly is red"            "$CO" "implementation project(':libs:crash')" 1
chk2 "crash implementation is green"       "$IM" "$CO"$'\n    '"implementation project(':libs:crash')" 0
chk2 "compileOnly without crash is red"    "$IM" "$CO" 1
[ "$fails" -eq 0 ] && echo "safe-prefs-guard: all cases pass" || exit 1
