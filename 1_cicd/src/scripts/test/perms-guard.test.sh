#!/usr/bin/env bash
# #1360 the perms guard must be RED for every way an app can lose a permission (code needs it undeclared, manifest and
# build.json disagreeing, another app's engine used without its grant checked, a stale generated asset, no page) and
# GREEN on a conforming tree. One fixture fleet per case (the real catalogue and policy); the real tree is the next step.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
GUARD="$HERE/../cloud-android-perms-guard.py"
fails=0
OUT="$(mktemp)"
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ab_cloud-libs-shared/libs/perms/src/main/assets"
  cp "$REPO/1_cicd/src/data/perms-guard.json" "$r/1_cicd/src/data/"
  python3 - "$r/1_cicd/src/data/perms-guard.json" <<'PY'
import json, sys
p = json.load(open(sys.argv[1])); p["pending_page"] = {}; p["pending_page_max"] = 0; p["not_android"] = {}
op = p["one_place"]; op["baseline"] = {}; op["baseline_max"] = 0; op["forks"] = {}; op["min_files"] = 1; op["allow"] = op["allow"] + ["a/PermsPage.kt"]
op["exceptions"] = [{"path": "ac_cloud-a/app/src/main/java/a/bg/", "tokens": ["appops set"], "why": "acts on other apps through the shell broker"}]
json.dump(p, open(sys.argv[1], "w"))
PY
  cp "$REPO/ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json" "$r/ab_cloud-libs-shared/libs/perms/src/main/assets/"
  # ac_cloud-a: a Compose app on the shared scaffold that runs git in cld.termux and posts notifications
  mkdir -p "$r/ac_cloud-a/app/src/main/java/a" "$r/ac_cloud-termux/app/src/main/java/t"
  echo 'buildJson.modules.each { name, _spec -> include ":${name}" }' > "$r/ac_cloud-a/settings.gradle"
  cat > "$r/ac_cloud-a/build.json" <<'J'
{"android": {"application_id": "com.x.a"}, "modules": {"libs:ui-nav-bars": {"dir": "../x"}, "libs:perms": {"dir": "../ab_cloud-libs-shared/libs/perms"}},
 "perms": {"needs": ["notifications"], "helpers": [{"package": "cld.termux", "label": "Cloud Termux", "needs": ["all_files"], "why": "git runs there"}], "first_run": "libs:perms"}}
J
  cat > "$r/ac_cloud-a/app/src/main/AndroidManifest.xml" <<'X'
<manifest><uses-permission android:name="android.permission.POST_NOTIFICATIONS" /><uses-permission android:name="android.permission.INTERNET" />
<queries><package android:name="cld.termux" /></queries><application/></manifest>
X
  printf 'class A { fun f() { NotificationCompat.Builder(c, "x"); TermuxGitWire.run(c) } }\n' > "$r/ac_cloud-a/app/src/main/java/a/A.kt"
  echo 'buildJson.modules.each { name, _spec -> include ":${name}" }' > "$r/ac_cloud-termux/settings.gradle"
  echo '{"modules": {"libs:perms": {"dir": "../ab_cloud-libs-shared/libs/perms"}}, "perms": {"needs": ["all_files"], "helpers": [], "first_run": "libs:perms"}}' > "$r/ac_cloud-termux/build.json"
  echo 'android { defaultConfig { applicationId "cld.termux" } }' > "$r/ac_cloud-termux/app/build.gradle"
  echo '<manifest><uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" /><application/></manifest>' > "$r/ac_cloud-termux/app/src/main/AndroidManifest.xml"
  printf 'class T { fun f() = Environment.isExternalStorageManager() }\n' > "$r/ac_cloud-termux/app/src/main/java/t/T.kt"
  python3 "$GUARD" "$r" --write >/dev/null
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi
  rm -rf "${3:?}"
}
jset() { # file python-statement-on-d
  python3 - "$1" "$2" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); exec(sys.argv[2]); json.dump(d, open(sys.argv[1], "w"))
PY
}
regen() { python3 "$GUARD" "$1" --write >/dev/null; }
A=ac_cloud-a/app/src/main
expect "a conforming fleet is green" 0 "$(mk)"
r="$(mk)"; printf 'class B { fun g() = (c.getSystemService(UsageStatsManager::class.java)) }\n' > "$r/$A/java/a/B.kt"; expect "P1 code using UsageStatsManager without usage_access declared is red" 1 "$r"
r="$(mk)"; printf 'class B { fun g() = Environment.getExternalStorageDirectory() }\n' > "$r/$A/java/a/B.kt"; expect "P1 code reading shared storage without all_files declared is red" 1 "$r"
r="$(mk)"; printf 'class B { fun g() = am.setExactAndAllowWhileIdle(0, 0L, p) }\n' > "$r/$A/java/a/B.kt"; expect "P1 an exact alarm without exact_alarm declared is red" 1 "$r"
r="$(mk)"; printf 'class B { // Environment.getExternalStorageDirectory()\n}\n' > "$r/$A/java/a/B.kt"; expect "a gated API named only in a comment is green" 0 "$r"
r="$(mk)"; printf 'class B { fun g() = UsageStatsManager::class }\n' > "$r/$A/java/a/B.kt"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["waived"]={"usage_access":"only shows the toggle, reads nothing"}'; regen "$r"; expect "a waiver with a reason is green" 0 "$r"
r="$(mk)"; printf 'class B { fun g() = UsageStatsManager::class }\n' > "$r/$A/java/a/B.kt"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["waived"]={"usage_access":"x"}'; regen "$r"; expect "a waiver without a reason is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["needs"].append("camera")'; regen "$r"; expect "P2 a declared need the manifest does not ask for is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["needs"].append("usage_access")'; regen "$r"; expect "P2 usage_access declared without PACKAGE_USAGE_STATS in the manifest is red" 1 "$r"
r="$(mk)"; sed -i 's#<application/>#<uses-permission android:name="android.permission.CAMERA" /><application/>#' "$r/$A/AndroidManifest.xml"; expect "P3 a manifest permission missing from perms.needs is red" 1 "$r"
r="$(mk)"; sed -i 's#<application/>#<application><service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"/></application>#' "$r/$A/AndroidManifest.xml"; expect "P3 an accessibility service missing from perms.needs is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/ab_cloud-libs-shared/libs/lib1/src/main"; echo '<manifest><uses-permission android:name="android.permission.RECORD_AUDIO" /></manifest>' > "$r/ab_cloud-libs-shared/libs/lib1/src/main/AndroidManifest.xml"; jset "$r/ac_cloud-a/build.json" 'd["modules"]["libs:lib1"]={}'; expect "P3 a permission merged in from a linked lib is red when undeclared" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["helpers"]=[]'; regen "$r"; expect "P4 running git in cld.termux without declaring the helper is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["helpers"][0]["needs"]=[]'; regen "$r"; expect "P4 the helper declared without the grant its engine needs is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-termux/build.json" 'd["perms"]["needs"]=[]'; sed -i 's#<uses-permission[^>]*/>##' "$r/ac_cloud-termux/app/src/main/AndroidManifest.xml"; : > "$r/ac_cloud-termux/app/src/main/java/t/T.kt"; regen "$r"; expect "P4 the helper app not declaring the grant itself is red" 1 "$r"
r="$(mk)"; sed -i 's#<queries><package android:name="cld.termux" /></queries>##' "$r/$A/AndroidManifest.xml"; expect "P4 no <queries> for the helper (its state is unreadable) is red" 1 "$r"
r="$(mk)"; printf 'class S { val a = "cld.termux.sharedstore" }\n' > "$r/$A/java/a/S.kt"; : > "$r/$A/java/a/A.kt"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["helpers"]=[]; d["perms"]["needs"]=[]'; sed -i 's#<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />##' "$r/$A/AndroidManifest.xml"; regen "$r"; expect "P4 opening the shared-store provider without the helper is red" 1 "$r"
r="$(mk)"; jset "$r/data/fleet/app-perms.json" 'd["apps"]["com.x.a"]["needs"]=[]'; expect "P5 a stale app-perms dataset entry is red" 1 "$r"
r="$(mk)"; : > "$r/data/fleet/app-perms.json"; expect "P5 an emptied app-perms dataset is red" 1 "$r"
r="$(mk)"; rm "$r/data/fleet/app-perms.json"; expect "P5 a missing app-perms dataset is red" 1 "$r"
r="$(mk)"; jset "$r/data/fleet/app-perms.json" 'del d["apps"]["com.x.a"]'; expect "P5 an app missing from the dataset is red" 1 "$r"
r="$(mk)"; mkdir -p "$r/$A/assets"; echo '{"needs": []}' > "$r/$A/assets/app-perms.json"; expect "P5 a baked per-app assets/app-perms.json copy is red (#1547)" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'del d["modules"]["libs:perms"]'; expect "P5 linking ui-nav-bars without libs:perms is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-termux/build.json" 'del d["modules"]["libs:perms"]'; expect "P5 a Gradle app with needs and no libs:perms is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'del d["perms"]'; expect "P0 an app without a perms block is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["needs"].append("no_such_grant")'; regen "$r"; expect "an unknown catalogue id is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["first_run"]="own:Missing.kt"'; regen "$r"; expect "P6 first_run own:<file> naming no file is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["perms"]["first_run"]="own:A.kt"'; regen "$r"; expect "P6 first_run own:<file> naming a real file is green" 0 "$r"
r="$(mk)"; jset "$r/ac_cloud-a/build.json" 'd["ui"]={"general":{"perms":[{"permissions":["android.permission.INTERNET"]}]}}'; expect "P5 an app's own Perms section missing a manifest permission is red" 1 "$r"
r="$(mk)"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["pending_page"]={"ac_cloud-a":"a fork that asks in its own screens"}; d["pending_page_max"]=1'; expect "a pending app that links libs:perms must leave the list (red)" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-termux/build.json" 'del d["modules"]["libs:perms"]'; jset "$r/1_cicd/src/data/perms-guard.json" 'd["pending_page"]={"ac_cloud-termux":"a fork that asks in its own screens"}; d["pending_page_max"]=0'; expect "the pending list growing past its max is red" 1 "$r"
r="$(mk)"; jset "$r/ac_cloud-termux/build.json" 'del d["modules"]["libs:perms"]'; jset "$r/1_cicd/src/data/perms-guard.json" 'd["pending_page"]={"ac_cloud-termux":"a fork that asks in its own screens"}; d["pending_page_max"]=1'; regen "$r"; expect "a listed pending app within the max is green" 0 "$r"
# P7 (#1515): one place asks. Each forbidden call outside the Perms page is red; the declared places are green.
r="$(mk)"; printf 'class B { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/B.kt"; expect "P7 requestPermissions outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {} }\n' > "$r/$A/java/a/B.kt"; expect "P7 an ActivityResultContracts.RequestPermission launcher outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }\n' > "$r/$A/java/a/B.kt"; expect "P7 opening a special-access settings intent outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }\n' > "$r/$A/java/a/B.kt"; expect "P7 opening App info outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { val c = "pm grant com.x android.permission.WRITE_SECURE_SETTINGS" }\n' > "$r/$A/java/a/B.kt"; expect "P7 a pm grant line outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { val c = "cmd appops set com.x RUN_ANY allow" }\n' > "$r/$A/java/a/B.kt"; expect "P7 an appops set line outside the Perms page is red" 1 "$r"
r="$(mk)"; printf 'class B { // requestPermissions( and pm grant, only prose\n}\n' > "$r/$A/java/a/B.kt"; expect "P7 the calls named only in a comment are green" 0 "$r"
r="$(mk)"; printf 'class P { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/PermsPage.kt"; expect "P7 the same call inside an allowed Perms page file is green" 0 "$r"
r="$(mk)"; mkdir -p "$r/$A/java/a/bg"; printf 'class C { val c = "cmd appops set com.other RUN_ANY allow" }\n' > "$r/$A/java/a/bg/C.kt"; expect "P7 the declared bgpolicy exception (acts on other apps) is green" 0 "$r"
r="$(mk)"; mkdir -p "$r/$A/java/a/bg"; printf 'class C { val c = "pm grant com.x android.permission.CAMERA" }\n' > "$r/$A/java/a/bg/C.kt"; expect "P7 the exception covers only its declared tokens (pm grant there is red)" 1 "$r"
r="$(mk)"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["exceptions"][0]["why"]="x"'; expect "P7 an exception without a why is red" 1 "$r"
r="$(mk)"; printf 'class B { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/B.kt"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["baseline"]={"ac_cloud-a/app/src/main/java/a/B.kt":"not moved into a Perms row yet"}; d["one_place"]["baseline_max"]=1'; expect "P7 a baselined site is green" 0 "$r"
r="$(mk)"; printf 'class B { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/B.kt"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["baseline"]={"ac_cloud-a/app/":"a vendored fork that asks itself"}; d["one_place"]["baseline_max"]=1'; expect "P7 a baselined directory prefix is green" 0 "$r"
r="$(mk)"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["baseline"]={"ac_cloud-a/app/src/main/java/a/A.kt":"not moved into a Perms row yet"}; d["one_place"]["baseline_max"]=1'; expect "P7 a baseline entry that no longer violates must be dropped (red)" 1 "$r"
r="$(mk)"; printf 'class B { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/B.kt"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["baseline"]={"ac_cloud-a/app/src/main/java/a/B.kt":"not moved into a Perms row yet"}; d["one_place"]["baseline_max"]=0'; expect "P7 the baseline growing past its max is red" 1 "$r"
r="$(mk)"; printf 'class B { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/$A/java/a/B.kt"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["baseline"]={"ac_cloud-a/app/src/main/java/a/B.kt":"x"}; d["one_place"]["baseline_max"]=1'; expect "P7 a baseline entry without a reason is red" 1 "$r"
r="$(mk)"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["min_files"]=50'; expect "P7 a guard that scans nothing (lost the fleet) is red" 1 "$r"
# P7 strict (#1515): a vendored fork is exempt file by file and token by token, never by directory.
FK='d["one_place"]["forks"]={"ac_cloud-termux/":{"why":"vendored upstream fork kept as is for rebases","files":{"ac_cloud-termux/app/src/main/java/t/F.kt":["requestPermissions("]}}}'
r="$(mk)"; printf 'class F { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/ac_cloud-termux/app/src/main/java/t/F.kt"; jset "$r/1_cicd/src/data/perms-guard.json" "$FK"; expect "P7 a listed fork file with its listed token is green" 0 "$r"
r="$(mk)"; printf 'class G { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/ac_cloud-termux/app/src/main/java/t/G.kt"; printf 'class F { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/ac_cloud-termux/app/src/main/java/t/F.kt"; jset "$r/1_cicd/src/data/perms-guard.json" "$FK"; expect "P7 an unlisted file inside a listed fork is red" 1 "$r"
r="$(mk)"; printf 'class F { fun g(a: Activity) { a.requestPermissions(arrayOf("x"), 1); Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) } }\n' > "$r/ac_cloud-termux/app/src/main/java/t/F.kt"; jset "$r/1_cicd/src/data/perms-guard.json" "$FK"; expect "P7 a new token in a listed fork file is red" 1 "$r"
r="$(mk)"; jset "$r/1_cicd/src/data/perms-guard.json" "$FK"; expect "P7 a listed fork file that no longer asks is red (the list only shrinks)" 1 "$r"
r="$(mk)"; printf 'class F { fun g(a: Activity) = a.requestPermissions(arrayOf("x"), 1) }\n' > "$r/ac_cloud-termux/app/src/main/java/t/F.kt"; jset "$r/1_cicd/src/data/perms-guard.json" 'd["one_place"]["forks"]={"ac_cloud-termux/":{"why":"x","files":{"ac_cloud-termux/app/src/main/java/t/F.kt":["requestPermissions("]}}}'; expect "P7 a fork without a why is red" 1 "$r"
# P8 (#1550): a needed permission a compiled lib caps with maxSdkVersion below target_sdk, not overridden, is red.
bt() {  # add a capping bluetooth-style lib the app compiles, give the app target_sdk and a fine_location need
  local r="$1"
  mkdir -p "$r/ab_cloud-libs-shared/libs/bt/src/main"
  echo 'dependencies { }' > "$r/ab_cloud-libs-shared/libs/bt/build.gradle"
  echo '<manifest><uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" /></manifest>' > "$r/ab_cloud-libs-shared/libs/bt/src/main/AndroidManifest.xml"
  jset "$r/ac_cloud-a/build.json" 'd["android"]["target_sdk"]=34; d["modules"]["libs:bt"]={"dir":"../ab_cloud-libs-shared/libs/bt"}; d["perms"]["needs"]=d["perms"]["needs"]+["fine_location"]'
  jset "$r/ac_cloud-a/app/src/main/AndroidManifest.xml.tmp" 'pass' 2>/dev/null || true
}
addfine() {  # add the app's own FINE uses-permission, optionally with tools:remove
  local r="$1"; local attr="$2"
  python3 - "$r/ac_cloud-a/app/src/main/AndroidManifest.xml" "$attr" <<'PY'
import sys
p,attr=sys.argv[1],sys.argv[2]
s=open(p).read().replace('<application/>', '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"%s /><application/>' % attr)
open(p,'w').write(s)
PY
  regen "$r"
}
r="$(mk)"; bt "$r"; addfine "$r" ""; expect "P8 a lib-capped fine_location the app needs without an override is red" 1 "$r"
r="$(mk)"; bt "$r"; addfine "$r" ' tools:remove="android:maxSdkVersion"'; expect "P8 the same with tools:remove on the app's declaration is green" 0 "$r"
rm -f "${OUT:?}"
[ "$fails" -eq 0 ] && echo "perms-guard.test: all green" || { echo "perms-guard.test: $fails FAILED"; exit 1; }
