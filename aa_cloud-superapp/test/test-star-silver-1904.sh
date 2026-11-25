#!/usr/bin/env bash
# #1904 tester: the Phone Starred (Quickmark) star is silver, one theme colour resource, no yellow/amber literal;
# and the Cloud Social tile is the mySocials web link. Static (no device, no build); mutants prove it red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
RES="$APP/app/src/main/res"; KT="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/Quickmarks.kt"
FAIL=0
chk() { if [ "$2" = 0 ]; then echo "  PASS: $1"; else echo "  FAIL: $1"; FAIL=$((FAIL+1)); fi; }
run() { # $1 colors(day) $2 colors(night) $3 kotlin $4 build.json -> number of failures
  local f=0
  grep -q '<color name="star_silver">#FF8A8F98</color>' "$1" || f=$((f+1))
  grep -q '<color name="star_silver">#FFC0C4CC</color>' "$2" || f=$((f+1))
  grep -q 'colorResource(com.diegonmarcos.superapp.R.color.star_silver)' "$3" || f=$((f+1))
  grep -q 'testTag("quickmark:star:' "$3" || f=$((f+1))
  grep -n 'quickmark:star' "$3" -B2 | grep -qiE 'Color\(0x|FFC83D|FFC107|FFD|FFB3|amber|yellow|gold' && f=$((f+1))
  grep -q '"target": "https://diegonmarcos.github.io/mySocials/"' "$4" || f=$((f+1))
  grep -c 'StarBadge(' "$APP"/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt "$APP"/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsUi.kt >/dev/null || f=$((f+1))
  return $f
}
run "$RES/values/colors.xml" "$RES/values-night/colors.xml" "$KT" "$APP/build.json"; chk "star drawn through star_silver (day #8A8F98, night #C0C4CC), no yellow literal, Social = mySocials URL" $?
# every star draw site goes through Quickmarks.StarBadge: no other file draws the quickmark star
n=$(grep -rln 'quickmark:star' "$APP/app/src/main" | wc -l); [ "$n" = 1 ]; chk "one draw site for the quickmark star" $?
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
sed 's/FF8A8F98/FFFFC83D/' "$RES/values/colors.xml" > "$T/c"; run "$T/c" "$RES/values-night/colors.xml" "$KT" "$APP/build.json"; [ $? != 0 ]; chk "mutant: yellow day colour is red" $?
sed 's/colorResource(com.diegonmarcos.superapp.R.color.star_silver)/Color(0xFFFFC83D)/' "$KT" > "$T/k"; run "$RES/values/colors.xml" "$RES/values-night/colors.xml" "$T/k" "$APP/build.json"; [ $? != 0 ]; chk "mutant: yellow literal in Kotlin is red" $?
sed 's#diegonmarcos.github.io/mySocials/"#diegonmarcos.github.io/other/"#' "$APP/build.json" > "$T/b"; run "$RES/values/colors.xml" "$RES/values-night/colors.xml" "$KT" "$T/b"; [ $? != 0 ]; chk "mutant: Social URL changed is red" $?
exit $FAIL
