#!/usr/bin/env bash
# #1490 the log-export guard must be RED for each way an app can lose the log-export IPC door, and GREEN on a conforming
# tree. A fixture tree is planted per case; the real tree is checked by the workflow's next step.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-log-export-guard.py"
fails=0
L=ab_cloud-libs-shared/libs
K=com/diegonmarcos/superapp
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/$L/devtools/src/main/java/$K/devtools" "$r/$L/devtools/src/main/java/$K/logexport" "$r/$L/crash/src/main/java/$K/crash" "$r/aa_cloud-superapp/data" "$r/ac_cloud-a/app/src/main"
  echo '<manifest><application><provider android:name="x.LogExportProvider" android:authorities="${applicationId}.logexport" android:exported="true" android:permission="p.CONSTELLATION_DATA"/></application></manifest>' > "$r/$L/devtools/src/main/AndroidManifest.xml"
  mkdir -p "$r/$L/log-export"; echo "srcDir('../devtools/src/main/java/com/diegonmarcos/superapp/logexport')" > "$r/$L/log-export/build.gradle"
  echo 'object FleetToken { const val PERMISSION = "p.CONSTELLATION_DATA" }' > "$r/$L/devtools/src/main/java/$K/devtools/FleetToken.kt"
  echo 'object CrashReports { const val DIR = "crashes" }' > "$r/$L/crash/src/main/java/$K/crash/CrashReports.kt"
  printf 'object LogExportContract { const val PERMISSION = "p.CONSTELLATION_DATA"\n const val CRASH_DIR = "crashes" }\n' > "$r/$L/devtools/src/main/java/$K/logexport/LogExportContract.kt"
  echo 'class P { fun call() { Binder.getCallingUid(); LogExportContract.refusal(g, m) } }' > "$r/$L/devtools/src/main/java/$K/logexport/LogExportProvider.kt"
  echo '{"apps":[{"id":"a","kind":"app","repo_url":"https://x/tree/main/ac_cloud-a"},{"id":"lib-x","kind":"lib","repo_url":"https://x/tree/main/none"}]}' > "$r/aa_cloud-superapp/data/constellation-fleet.json"
  echo '{"modules":{"libs:devtools":{"dir":"x"}}}' > "$r/ac_cloud-a/build.json"
  echo '<manifest/>' > "$r/ac_cloud-a/app/src/main/AndroidManifest.xml"
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > "$3.out" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$3.out"; fails=$((fails+1)); fi
  rm -rf "$3" "$3.out"
}
expect "a conforming tree is green" 0 "$(mk)"
r="$(mk)"; echo '{"modules":{}}' > "$r/ac_cloud-a/build.json"; expect "an app that does not link devtools is red" 1 "$r"
r="$(mk)"; echo '{"modules":{}}' > "$r/ac_cloud-a/build.json"; echo "include ':libs:core', ':libs:devtools'" > "$r/ac_cloud-a/settings.gradle"; expect "an app whose settings include devtools is green" 0 "$r"
r="$(mk)"; echo '<manifest><application><provider android:name="x.LogExportProvider" tools:node="remove"/></application></manifest>' > "$r/ac_cloud-a/app/src/main/AndroidManifest.xml"; expect "removing the provider in an app manifest is red" 1 "$r"
r="$(mk)"; echo '<manifest/>' > "$r/$L/devtools/src/main/AndroidManifest.xml"; expect "devtools not declaring the provider is red" 1 "$r"
r="$(mk)"; sed -i 's/exported="true"/exported="false"/' "$r/$L/devtools/src/main/AndroidManifest.xml"; expect "a non-exported provider is red" 1 "$r"
r="$(mk)"; sed -i 's/ android:permission="p.CONSTELLATION_DATA"//' "$r/$L/devtools/src/main/AndroidManifest.xml"; expect "a provider without the signature permission is red" 1 "$r"
r="$(mk)"; echo 'apply plugin: x' > "$r/$L/log-export/build.gradle"; expect "a log-export module that no longer compiles the devtools sources is red" 1 "$r"
r="$(mk)"; sed -i 's/"crashes"/"crash2"/' "$r/$L/devtools/src/main/java/$K/logexport/LogExportContract.kt"; expect "a crash dir that differs from libs:crash is red" 1 "$r"
r="$(mk)"; sed -i 's/PERMISSION = "p.CONSTELLATION_DATA"/PERMISSION = "other"/' "$r/$L/devtools/src/main/java/$K/logexport/LogExportContract.kt"; expect "a permission that differs from the fleet one is red" 1 "$r"
r="$(mk)"; echo 'class P { fun call() {} }' > "$r/$L/devtools/src/main/java/$K/logexport/LogExportProvider.kt"; expect "a provider that does not re-check the caller is red" 1 "$r"
r="$(mk)"; echo '{"apps":[{"id":"a","kind":"app","repo_url":"https://x/tree/main/ac_cloud-gone"}]}' > "$r/aa_cloud-superapp/data/constellation-fleet.json"; expect "a fleet app with no directory is red" 1 "$r"
r="$(mk)"; echo '{"build":{"modules":{"devtools":{"dir":"../ab_cloud-libs-shared/libs/devtools"}}}}' > "$r/ac_cloud-a/build.json"; expect "a fetched tree that materializes devtools is green" 0 "$r"
[ "$fails" -eq 0 ] && echo "log-export-guard.test: all green" || { echo "log-export-guard.test: $fails FAILED"; exit 1; }
