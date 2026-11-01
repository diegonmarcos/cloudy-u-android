#!/usr/bin/env bash
# #1411 Configs: General | Perms | About (fleet pattern) + SIM, Status, Usage declared and wired; secrets only encrypted.
set -u
cd "$(dirname "$0")/.."
fail=0; ok() { echo "ok   $1"; }; bad() { echo "FAIL $1"; fail=1; }
S=app/src/main/kotlin/org/fossify/phone/sim
jq -e '[.configs_pages.general.sections[].id] | index("perms") and index("app")' build.json >/dev/null && ok "General declares perms + app" || bad "General sections"
jq -e '.configs_pages.about.tagline | length > 0' build.json >/dev/null && ok "About declared" || bad "About"
for p in sim status usage; do
  jq -e --arg p "$p" '.configs_pages.app_pages | map(.id) | index($p)' build.json >/dev/null && ok "app page $p declared" || bad "app page $p"
done
for x in phone_state phone_numbers sms_read usage_access; do
  jq -e --arg x "$x" '.perms.needs | index($x)' build.json >/dev/null && ok "perm $x via libs:perms" || bad "perm $x"
done
grep -q 'FleetConfigsEntries.addTo(' app/src/main/kotlin/org/fossify/phone/activities/MainActivity.kt && ok "General/About wired" || bad "FleetConfigsEntries not wired"
grep -q 'PAGE_USAGE' app/src/main/kotlin/org/fossify/phone/activities/MainActivity.kt && ok "SIM/Status/Usage wired" || bad "menu"
grep -q '\.sim\.SimConfigsActivity' app/src/main/AndroidManifest.xml && ok "activity in manifest" || bad "manifest"
grep -q 'SafeEncryptedPrefs.prefs' $S/SimManualStore.kt && ok "manual SIM data encrypted" || bad "not encrypted"
if grep -rnE 'getSharedPreferences|PreferenceManager|EncryptedSharedPreferences\.create' $S; then bad "plain prefs in sim/"; else ok "no plain prefs in sim/"; fi
if grep -rnE 'Log\.[dviwe]\(|println\(' $S; then bad "logging in sim/ (never log PIN/PUK)"; else ok "no logging in sim/"; fi
exit $fail
