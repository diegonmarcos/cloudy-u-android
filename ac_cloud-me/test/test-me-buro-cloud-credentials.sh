#!/usr/bin/env bash
# #1693 Cloud Me's Buro backup asks for no passphrase of its own: the export / import / verify flows read it from Cloud Account
# (App credentials) over the per-credential grant, and a missing one is a sentence naming the fix. Mutations must go RED.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
F=ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/BuroScreens.kt
check() {
python3 - "$1" "$F" <<'PY'
import os, sys
R, F = sys.argv[1:3]
src = open(os.path.join(R, F), encoding="utf-8").read()
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok("PasswordVisualTransformation(" not in src and "PassphraseDialog" not in src, "B1 the Buro page types no passphrase", "B1 the Buro page asks for a passphrase")
ok('BURO_PASSPHRASE_ID = "me.buro_backup_passphrase"' in src and "AccountClient.credential(ctx, BURO_PASSPHRASE_ID" in src, "B2 the passphrase is read from Cloud Account under the catalogue id", "B2 the read is gone")
ok("AccountClient.openCredentials(ctx)" in src and 'me:backup:set-passphrase' in src, "B3 a link opens Cloud Account at App credentials", "B3 the link is gone")
ok("if (c.present) cont(c.value.toCharArray()) else message = c.line" in src, "B4 a missing passphrase is a sentence, the flow never crashes", "B4 a missing passphrase is not handled")
ok(all(t in src for t in ('me:backup:export', 'me:backup:import', 'me:backup:verify')), "B5 export, import and verify are all still there (rule 16)", "B5 a backup action was lost")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$(dirname "$F")"; cp "$ROOT/$F" "$TMP/r/$F"
  python3 - "$TMP/r/$F" "$2" "$3" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "a passphrase field is back" 'BURO_PASSPHRASE_ID = ' 'BURO_PASSPHRASE_ID = "me.buro_backup_passphrase"; val v = PasswordVisualTransformation(); val unused = '
mutate "the read is dropped" 'AccountClient.credential(' 'Other.credential('
mutate "the id drifts" '"me.buro_backup_passphrase"' '"me.pass"'
mutate "the link is dropped" 'AccountClient.openCredentials(ctx)' '""'
mutate "a missing passphrase is ignored" 'else message = c.line' 'else Unit'
mutate "export is lost" 'me:backup:export' 'me:backup:x'
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
