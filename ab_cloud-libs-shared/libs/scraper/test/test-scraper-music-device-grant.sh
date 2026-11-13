#!/usr/bin/env bash
# #1693 the music device grant left libs:scraper: only Cloud Account runs it (libs:account MusicDeviceGrant, owner-checked), the Music page
# takes the client and tokens it filed over the per-credential grant (MusicCredentials). Mutations are planted in a scratch copy.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SC=ab_cloud-libs-shared/libs/scraper/src/main/java/com/diegonmarcos/superapp/scraper
AC=ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile
check() {
python3 - "$1" "$SC" "$AC" <<'PY'
import os, sys
R, SC, AC = sys.argv[1:4]
rd = lambda p: open(os.path.join(R, p), encoding="utf-8").read()
auth, ui, cred = rd(SC + "/MusicAuth.kt"), rd(SC + "/MusicUi.kt"), rd(SC + "/MusicCredentials.kt")
grant, panel = rd(AC + "/MusicDeviceGrant.kt"), rd(AC + "/AppCredentialsPanel.kt")
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
scraper_all = "".join(rd(SC + "/" + f) for f in os.listdir(os.path.join(R, SC)) if f.endswith(".kt"))
ok("device_code" not in scraper_all and "startDevice" not in scraper_all and "pollDevice" not in scraper_all, "S1 libs:scraper holds no device grant", "S1 the device grant is back in libs:scraper")
ok("MusicCredentials.take(ctx, spec.id)" in ui and "takeFromAccount(spec)" in ui and "MusicCredentials.open(ctx)" in ui, "S2 the Music page takes the tokens from Cloud Account and links there", "S2 the Music page does not read Cloud Account")
ok('"music.$service.$field"' in cred and "<redacted>" in cred, "S3 the ids are the catalogue's and the log form is redacted", "S3 ids or redaction lost")
ok("SignInOwner.isOwner(ctx)" in grant and "grant-type:device_code" in grant and "AppCredentialsStore.put(" in grant, "S4 the grant is Cloud Account's: owner-checked, files the tokens", "S4 the grant lost its owner check or its filing")
ok("MusicDeviceGrant.run(" in panel and "SignInOwner.isOwner(ctx)" in panel, "S5 Cloud Account's App credentials page runs it", "S5 the page does not run the grant")
ok("class MusicAccounts" in auth and "MusicOAuth.exchange" in ui, "S6 the other sign-in kinds and the accounts store are untouched (rule 16)", "S6 something else was removed")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file old new
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$SC" "$TMP/r/$AC"; cp "$ROOT/$SC"/*.kt "$TMP/r/$SC/"; cp "$ROOT/$AC/MusicDeviceGrant.kt" "$ROOT/$AC/AppCredentialsPanel.kt" "$TMP/r/$AC/"
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "the grant is back in libs:scraper" "$SC/MusicAuth.kt" 'class Callback' 'class Callback0; val g = "urn:ietf:params:oauth:grant-type:device_code"; class Callback'
mutate "startDevice is back" "$SC/MusicAuth.kt" 'object MusicOAuth {' 'object MusicOAuth { fun startDevice() {}'
mutate "the page stops taking" "$SC/MusicUi.kt" 'MusicCredentials.take(ctx, spec.id)' 'null'
mutate "the page drops the link" "$SC/MusicUi.kt" 'MusicCredentials.open(ctx)' '""'
mutate "the id drifts" "$SC/MusicCredentials.kt" '"music.$service.$field"' '"m.$service"'
mutate "the log form leaks" "$SC/MusicCredentials.kt" '<redacted>' '$value'
mutate "the owner check is dropped" "$AC/MusicDeviceGrant.kt" 'SignInOwner.isOwner(ctx)' 'true'
mutate "the tokens are not filed" "$AC/MusicDeviceGrant.kt" 'AppCredentialsStore.put(' 'Unit.put('
mutate "the page stops running it" "$AC/AppCredentialsPanel.kt" 'MusicDeviceGrant.run(' 'Other.run('
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
