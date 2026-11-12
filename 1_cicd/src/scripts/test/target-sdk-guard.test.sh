#!/usr/bin/env bash
# #1675 the target-sdk guard must be RED for each way a fleet app can miss the silent-update floor (a low build.json value, a
# low Gradle literal, a low gradle.properties / version-catalog value, an unresolvable app, an exemption without a reason, a
# stale exemption, a floor that drifts from the Android rule) and GREEN on a conforming tree. A fixture tree is planted per
# case; the real tree is checked by the workflow's next step.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-target-sdk-guard.py"
fails=0
OUT="$(mktemp)"
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ac_a/app" "$r/ac_b/gradle" "$r/ac_t" "$r/ac_lib/x" "$r/ac_notfleet"
  cat > "$r/1_cicd/src/data/target-sdk-guard.json" <<'J'
{"floor": 35, "newest_device_sdk": 37, "exempt_max": 1, "exempt": {"ac_t": "exec from the data dir needs 28"}, "unresolved": {}}
J
  echo '{"android": {"application_id": "a.a", "target_sdk": 35}, "release": {"ghcr": {"image": "a"}}}' > "$r/ac_a/build.json"
  printf "android {\n    defaultConfig {\n        targetSdk 35\n        // targetSdk 30\n    }\n}\n" > "$r/ac_a/app/build.gradle"
  echo '{"forks": {"b": {"app_id": "b.b"}}}' > "$r/ac_b/build.json"
  printf '[versions]\napp-build-targetSDK = "36"\n' > "$r/ac_b/gradle/libs.versions.toml"
  echo '{"android": {"application_id": "t.t"}, "release": {"ghcr": {"image": "t"}}}' > "$r/ac_t/build.json"
  echo 'targetSdkVersion=28' > "$r/ac_t/gradle.properties"
  echo '{"lib_apks": {"scan": ["x"]}, "android": {"target_sdk": 35}}' > "$r/ac_lib/build.json"
  printf "android { targetSdkVersion safeExtGet('targetSdkVersion', 30) }\n" > "$r/ac_lib/x/build.gradle"
  echo '{"android": {"target_sdk": 20}}' > "$r/ac_notfleet/build.json"
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi
  rm -rf "$3"
}
expect "a conforming tree is green (an exempt app, a library default, a commented literal, a non-fleet dir)" 0 "$(mk)"
r="$(mk)"; sed -i 's/"target_sdk": 35/"target_sdk": 34/' "$r/ac_a/build.json"; expect "T2 a build.json target_sdk of 34 is red" 1 "$r"
r="$(mk)"; sed -i 's/targetSdk 35/targetSdk 34/' "$r/ac_a/app/build.gradle"; expect "T2 a Gradle literal below the floor is red even when build.json says 35" 1 "$r"
r="$(mk)"; sed -i 's/"36"/"33"/' "$r/ac_b/gradle/libs.versions.toml"; expect "T2 a version-catalog targetSdk below the floor is red" 1 "$r"
r="$(mk)"; sed -i 's/"exec from the data dir needs 28"/""/' "$r/1_cicd/src/data/target-sdk-guard.json"; expect "T2 an exemption with no reason is red" 1 "$r"
r="$(mk)"; sed -i 's/=28/=35/' "$r/ac_t/gradle.properties"; expect "T3 a stale exemption (the app is at the floor now) is red" 1 "$r"
r="$(mk)"; sed -i 's/"exempt_max": 1/"exempt_max": 0/' "$r/1_cicd/src/data/target-sdk-guard.json"; expect "T3 more exemptions than exempt_max is red" 1 "$r"
r="$(mk)"; rm "$r/ac_b/gradle/libs.versions.toml"; expect "T1 an app with no resolvable targetSdk is red" 1 "$r"
r="$(mk)"; sed -i 's/"newest_device_sdk": 37/"newest_device_sdk": 36/' "$r/1_cicd/src/data/target-sdk-guard.json"; expect "T4 a floor that is not the rule for newest_device_sdk is red" 1 "$r"
r="$(mk)"; rm "$r/ac_b/gradle/libs.versions.toml"; sed -i 's/"unresolved": {}/"unresolved": {"ac_b": "upstream tree"}/' "$r/1_cicd/src/data/target-sdk-guard.json"; expect "T5 a declared unresolvable app is reported, not red" 0 "$r"
r="$(mk)"; sed -i 's/"unresolved": {}/"unresolved": {"ac_b": "upstream tree"}/' "$r/1_cicd/src/data/target-sdk-guard.json"; expect "T5 an unresolved entry that resolves now is red" 1 "$r"
rm -f "$OUT"
[ "$fails" -eq 0 ] && echo "target-sdk-guard.test: all cases hold" || { echo "target-sdk-guard.test: $fails case(s) wrong"; exit 1; }
