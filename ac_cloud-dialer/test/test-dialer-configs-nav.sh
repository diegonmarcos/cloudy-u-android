#!/usr/bin/env bash
# #1415 Cloud Dialer navigation contract, mutation-proven:
#   C  ONE Configs entry: the top bar's Configs icon opens DialerConfigsActivity; no second entry (no Configs menu items,
#      no FleetConfigs* menu helpers, no overflow ⋮ by the search bar, no SimConfigsActivity).
#   P  the Configs pages are tabs of the top nav bar, declared in build.json::configs_pages.top_nav in the order
#      General | Perms | SIM | Status | Usage | About, baked into BuildConfig and each drawn by DialerConfigsActivity.
#   S  Status / SIM / Usage / SIMs use the shared Configs v2 rows and tables (no hand-made rows that overflow), Configs has back.
#   B  the bottom nav is Contacts | Keypad | Home | Calls | SMS | SIMs.
#   R  every compileOnly dependency of a linked lib that the app reaches is linked by the app (sha-61326f78 crashed with
#      NoClassDefFoundError androidx.security.crypto.MasterKey$Builder: libs:crash links security-crypto compileOnly).
# Each assertion is then proven red by its own mutation of a copy of the tree.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"

check() { # $1 = app dir; prints FAIL lines, returns the number of failures
  local d="$1" n=0 K="$1/app/src/main/kotlin/org/fossify/phone"
  local MA="$K/activities/MainActivity.kt" CA="$K/configs/DialerConfigsActivity.kt" SP="$K/sim/SimPages.kt"
  bad() { echo "FAIL $1"; n=$((n + 1)); }
  # C
  [ "$(grep -rho 'DialerConfigsActivity\.intent(' "$K" | wc -l)" = 1 ] || bad "C1 DialerConfigsActivity.intent( must be called exactly once"
  grep -q 'startActivity(DialerConfigsActivity.intent(this, page))' "$MA" && grep -q 'onConfigs = { openConfigs() }' "$MA" \
    && grep -q 'onConfigsPage = { p -> openConfigs(p.id) }' "$MA" || bad "C2 the top bar's Configs (and the dropdown's General / About) do not open the one DialerConfigsActivity"
  grep -rqE 'FleetConfigs(Entries|Menu|Buttons|Activity)\b|menu\.add\(.*Configs' "$K" && bad "C3 a second Configs entry (FleetConfigs* helper or a Configs menu item)"
  grep -qE 'showAsAction="(never|ifRoom|collapseActionView)' "$d/app/src/main/res/menu/menu.xml" && bad "C4 menu.xml has an item that can fall into the ⋮ overflow"
  grep -q 'overflowIcon = null' "$MA" || bad "C5 the search bar's ⋮ overflow icon is not removed"
  { [ -e "$K/sim/SimConfigsActivity.kt" ] || grep -q 'SimConfigsActivity' "$d/app/src/main/AndroidManifest.xml"; } && bad "C6 the parallel SimConfigsActivity is back"
  # P
  [ "$(jq -c '[.configs_pages.top_nav[].id]' "$d/build.json" 2>/dev/null)" = '["general","perms","about","sim","status","usage"]' ] \
    || bad "P1 configs_pages.top_nav is not general|perms|about|sim|status|usage"
  [ "$(jq -c '[.configs_pages.top_nav[].label]' "$d/build.json" 2>/dev/null)" = '["General","Perms","About","SIM","Status","Usage"]' ] \
    || bad "P2 configs_pages.top_nav labels are not General|Perms|About|SIM|Status|Usage"
  grep -q '\["top_nav"\]' "$d/app/build.gradle.kts" && grep -q '"CONFIGS_TOP_NAV"' "$d/app/build.gradle.kts" || bad "P3 top_nav is not baked as BuildConfig.CONFIGS_TOP_NAV"
  grep -q 'parse(BuildConfig.CONFIGS_TOP_NAV)' "$CA" && grep -q 'NavBarTopSecondRow(' "$CA" || bad "P4 Configs does not draw top_nav in the top nav bar"
  for id in general perms sim status usage about; do grep -q "\"$id\" -> " "$CA" || bad "P5 Configs draws no page for tab $id"; done
  grep -q 'android:name=".configs.DialerConfigsActivity"' "$d/app/src/main/AndroidManifest.xml" || bad "P6 DialerConfigsActivity not in the manifest"
  # S
  grep -q 'TAG_BACK' "$CA" && grep -q 'onBack = { finish() }' "$CA" || bad "S1 Configs has no working back"
  for f in ConfigsRow ConfigsTable ConfigsCard; do grep -q "$f(" "$SP" || bad "S2 SIM pages do not use $f"; done
  grep -qE 'Text\(r\.(label|value)' "$SP" && bad "S3 a hand-made label/value row in the SIM pages"
  grep -q 'SimsOverviewPage(' "$MA" || bad "S4 the SIMs tab draws no SimsOverviewPage"
  # B
  [ "$(jq -c '.ui.bottom_nav' "$d/build.json" 2>/dev/null)" = '["contacts","keypad","home","recents","sms","sims"]' ] \
    || bad "B1 ui.bottom_nav is not contacts|keypad|home|recents|sms|sims"
  [ "$(jq -c '[.ui.bottom_nav[] as $b | .ui.sections[] | select(.id == $b) | .label]' "$d/build.json" 2>/dev/null)" = '["Contacts","Keypad","Home","Calls","SMS","SIMs"]' ] \
    || bad "B2 bottom nav labels are not Contacts|Keypad|Home|Calls|SMS|SIMs"
  grep -q 'setOf(KEYPAD, SIMS)' "$K/nav/DialerNav.kt" || bad "B3 Keypad / SIMs are filtered out of the island (DialerNav.NON_PAGER)"
  grep -q 'KeypadPage(c, onCall' "$MA" && grep -q 'DialerNav.KEYPAD, DialerNav.SIMS -> { selectedSection.value = sectionId' "$MA" || bad "B4 Keypad is not the in-shell keypad page"
  # R
  local libs lib dir co ga grp bridge
  libs="$(grep -oE 'project\("(:libs:[^"]+)"\)\.projectDir = file\("[^"]+"\)' "$d/settings.gradle.kts" | sed -E 's/.*file\("([^"]+)"\)/\1/')"
  for dir in $libs; do
    lib="$d/$dir"; [ -d "$lib" ] || continue
    for co in $(grep -hoE "compileOnly[ (]+['\"][^'\":]+:[^'\":]+" "$lib"/build.gradle* 2>/dev/null | sed -E "s/compileOnly[ (]+['\"]//"); do
      grp="${co%%:*}"; ga="$co"
      for bridge in $(grep -rlE "^import ${grp//./\\.}\." "$lib/src/main" 2>/dev/null | xargs -r -n1 basename | sed 's/\.kt$//;s/\.java$//'); do
        if grep -rqw "$bridge" "$K"; then
          grep -q "\"$ga\"\|\"$ga:" "$d/gradle/libs.versions.toml" "$d/app/build.gradle.kts" 2>/dev/null \
            || bad "R1 the app uses $bridge ($dir), which links $ga compileOnly: the app must link $ga"
        fi
      done
    done
  done
  grep -q 'implementation(libs.androidx.security.crypto)' "$d/app/build.gradle.kts" || bad "R2 security-crypto is not linked from the version catalog"
  grep -q 'keep class androidx.security.crypto' "$d/app/proguard-rules.pro" || bad "R3 no R8 keep rule for security-crypto"
  return $n
}

echo "== real tree"; check "$APP"; real=$?
[ "$real" = 0 ] && echo "ok   all assertions green" || { echo "FAIL $real assertion(s) red on the real tree"; exit 1; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
proven=0; unproven=0
mutant() { # $1 name, $2 shell mutation run inside the copy, $3 expected FAIL id
  rm -rf "$W/a"; mkdir -p "$W/a/app/src/main/res" "$W/a/gradle"
  cp "$APP/build.json" "$APP/settings.gradle.kts" "$W/a/"; cp "$APP/gradle/libs.versions.toml" "$W/a/gradle/"
  cp "$APP/app/build.gradle.kts" "$APP/app/proguard-rules.pro" "$W/a/app/"; cp "$APP/app/src/main/AndroidManifest.xml" "$W/a/app/src/main/"
  cp -r "$APP/app/src/main/kotlin" "$W/a/app/src/main/"; cp -r "$APP/app/src/main/res/menu" "$W/a/app/src/main/res/"
  ln -s "$APP/../ab_cloud-libs-shared" "$W/ab_cloud-libs-shared"
  ( cd "$W/a" && eval "$2" ) >/dev/null 2>&1
  local out; out="$(check "$W/a")"
  rm -f "$W/ab_cloud-libs-shared"
  if grep -q "FAIL $3" <<<"$out"; then echo "ok   mutation proven red: $1 ($3)"; proven=$((proven + 1)); else echo "FAIL mutation NOT caught: $1 ($3)"; unproven=$((unproven + 1)); fi
}
K=app/src/main/kotlin/org/fossify/phone
mutant "a second entry: Configs menu item back" "sed -i 's|requireToolbar().overflowIcon = null|requireToolbar().menu.add(\"Configs ▸ SIM\"); requireToolbar().overflowIcon = null|' $K/activities/MainActivity.kt" C3
mutant "FleetConfigsEntries.addTo back" "sed -i 's|requireToolbar().overflowIcon = null|FleetConfigsEntries.addTo(requireToolbar().menu)|' $K/activities/MainActivity.kt" C3
mutant "top bar Configs opens the old settings" "sed -i 's|onConfigs = { openConfigs() }|onConfigs = { }|' $K/activities/MainActivity.kt" C2
mutant "dropdown General/About back on the scaffold's parallel dialog" "sed -i '/onConfigsPage = { p -> openConfigs(p.id) },/d' $K/activities/MainActivity.kt" C2
mutant "a second launcher of Configs" "printf 'fun x(c: android.content.Context) = DialerConfigsActivity.intent(c)\n' >> $K/sim/SimPages.kt" C1
mutant "Settings back in the ⋮ overflow" "sed -i '0,/app:showAsAction=\"always\"/s//app:showAsAction=\"never\"/' app/src/main/res/menu/menu.xml" C4
mutant "overflow icon kept" "sed -i 's|requireToolbar().overflowIcon = null||' $K/activities/MainActivity.kt" C5
mutant "SimConfigsActivity back" "touch $K/sim/SimConfigsActivity.kt" C6
mutant "pages out of order" "jq '.configs_pages.top_nav |= [.[1], .[0]] + .[2:]' build.json > b && mv b build.json" P1
mutant "Usage page dropped" "jq '.configs_pages.top_nav |= map(select(.id != \"usage\"))' build.json > b && mv b build.json" P1
mutant "a label renamed" "jq '.configs_pages.top_nav[1].label = \"Permissions\"' build.json > b && mv b build.json" P2
mutant "top_nav not baked" "sed -i 's|\"CONFIGS_TOP_NAV\"|\"CONFIGS_X\"|' app/build.gradle.kts" P3
mutant "pages not in the top nav bar" "sed -i 's|NavBarTopSecondRow(|SomeOtherRow(|' $K/configs/DialerConfigsActivity.kt" P4
mutant "Status tab draws nothing" "sed -i 's|\"status\" -> |\"st\" -> |' $K/configs/DialerConfigsActivity.kt" P5
mutant "Configs not in the manifest" "sed -i 's|.configs.DialerConfigsActivity|.configs.Gone|' app/src/main/AndroidManifest.xml" P6
mutant "back does nothing" "sed -i 's|onBack = { finish() }|onBack = { }|' $K/configs/DialerConfigsActivity.kt" S1
mutant "Status off the shared table" "sed -i 's|ConfigsTable(|Table(|g' $K/sim/SimPages.kt" S2
mutant "a hand-made row in Status" "printf '// Text(r.label)\n' >> $K/sim/SimPages.kt" S3
mutant "SIMs tab draws nothing" "sed -i 's|SimsOverviewPage(c)|Unit|' $K/activities/MainActivity.kt" S4
mutant "bottom nav order" "jq '.ui.bottom_nav = [\"home\",\"contacts\",\"keypad\",\"recents\",\"sms\",\"sims\"]' build.json > b && mv b build.json" B1
mutant "SIMs off the island" "jq '.ui.bottom_nav -= [\"sims\"]' build.json > b && mv b build.json" B1
mutant "Calls relabelled" "jq '(.ui.sections[] | select(.id == \"recents\") | .label) = \"Recents\"' build.json > b && mv b build.json" B2
mutant "Keypad filtered out" "sed -i 's|setOf(KEYPAD, SIMS)|setOf(SIMS)|' $K/nav/DialerNav.kt" B3
mutant "Keypad opens nothing" "sed -i 's|KeypadPage(c, onCall|Unit(c, onCall|' $K/activities/MainActivity.kt" B4
mutant "security-crypto not linked (the sha-61326f78 crash)" "sed -i '/androidx-security-crypto\|securityCrypto/d' gradle/libs.versions.toml; sed -i '/libs.androidx.security.crypto/d' app/build.gradle.kts" R1
mutant "security-crypto linked by hand, off the catalog" "sed -i 's|implementation(libs.androidx.security.crypto)|implementation(\"androidx.security:security-crypto:1.1.0\")|' app/build.gradle.kts" R2
mutant "keep rule dropped" "sed -i '/keep class androidx.security.crypto/d' app/proguard-rules.pro" R3
echo "test-dialer-configs-nav: $proven mutation(s) proven red, $unproven not proven"
[ "$unproven" = 0 ]
