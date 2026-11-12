#!/usr/bin/env bash
# #1411/#1415 Configs: General | Perms | About (fleet pattern) + SIM, Status, Usage declared and wired; secrets only encrypted.
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
# #1415 SIM / Status / Usage are tabs of the one Configs (DialerConfigsActivity); test-dialer-configs-nav.sh holds the entry and the tabs.
C=app/src/main/kotlin/org/fossify/phone/configs/DialerConfigsActivity.kt
for f in SimCardsPage SimStatusPage SimUsagePage FleetGeneralPage FleetAboutPage AppPermsSection; do
  grep -q "$f(" $C && ok "Configs draws $f" || bad "Configs lacks $f"
done
grep -q '\.configs\.DialerConfigsActivity' app/src/main/AndroidManifest.xml && ok "activity in manifest" || bad "manifest"
grep -q 'SafeEncryptedPrefs.prefs' $S/SimManualStore.kt && ok "manual SIM data encrypted" || bad "not encrypted"
if grep -rnE 'getSharedPreferences|PreferenceManager|EncryptedSharedPreferences\.create' $S; then bad "plain prefs in sim/"; else ok "no plain prefs in sim/"; fi
if grep -rnE 'Log\.[dviwe]\(|println\(' $S; then bad "logging in sim/ (never log PIN/PUK)"; else ok "no logging in sim/"; fi
# #1528 PIN / PUK: declared secret, written only to the Cloud Account vault (never to this app's prefs), provider side present.
A=../data/fleet/app/com.diegonmarcos.comms.dialer/sim-fields.json   # #1547 the SIM field schema is the runtime dataset sim-fields (Cloud Store), no asset
[ -f "$A" ] && ok "sim-fields dataset present" || bad "sim-fields dataset missing at $A"
[ ! -e app/src/main/assets/sim-fields.json ] && ok "sim-fields is not baked into the APK" || bad "sim-fields.json is baked into the APK again"
[ "$(jq -c '[.fields[] | select(.secret == true) | .id]' $A)" = '["pin","puk"]' ] && ok "only pin and puk are secret fields" || bad "secret fields are not exactly pin,puk"
grep -q 'if (f.secret) return vault.put' $S/SimManualStore.kt && ok "secret fields go to the vault" || bad "secret fields not routed to the vault"
if grep -nE 'putString\("\$key\.(pin|puk)|"pin"|"puk"' $S/SimManualStore.kt $S/SimSubPages.kt; then bad "pin/puk literal in the store or pages (schema-driven only)"; else ok "no pin/puk literal outside the schema"; fi
grep -q 'METHOD_SIM_SECRET_PUT' ../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt && ok "Account provider holds sim_secret" || bad "Account provider lacks sim_secret"
for f in lineType dataAllowance minutes sms roamingPlan monthlyPrice renewalDate ownerName ownerEmail providerSite providerPhone providerWhatsapp notes; do
  jq -e --arg f "$f" '[.fields[].id] | index($f)' $A >/dev/null && ok "field $f declared" || bad "field $f not declared"
done
exit $fail
