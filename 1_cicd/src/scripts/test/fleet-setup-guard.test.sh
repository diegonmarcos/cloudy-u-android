#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ fleet-setup-guard.test — prove the guard FAILS when an app cannot ║
# ║ answer the setup contract, and passes on the real fleet           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #873. The green case is the real repository; every mutation runs on a small fixture
# (the contract's two files, one manifest, three apps) and must turn it red for the named reason:
#   M1  an app's build.json drops libs:fleetconfig-model                  -> S1
#   M2  libs:core stops `api`-ing the module                              -> S0
#   M3  the provider is not exported / not behind the permission / authority changed -> S0
#   M4  a datastore store with no handler and no `unserved` entry         -> S3
#   M5  a hand-written settings file includes libs:core, not the model    -> S1
#   M6  a stale `unserved` entry (the store is served by the default)     -> S3
#   M7  an exemption under the reason length / an exemption for a linked app -> S2
#   M8  a bundle route to an undeclared store / a device key              -> S4
#   M9  an empty checkout                                                 -> S5
#   M10 the provider stops re-checking the caller                         -> S0
# Controls stay green: a registered handler (SetupStores.register) serves a datastore store,
# an exempted app that cannot link, a comment that mentions the register call.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-fleet-setup-guard.py"
POLICY="$ROOT/1_cicd/src/data/fleet-setup-guard.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

[ -f "$GUARD" ] || { echo "ERROR missing $GUARD — this test is unrun, not passing"; exit 1; }

MODEL=ab_cloud-libs-shared/libs/fleetconfig-model
fixture() {   # fixture <dir>
  local T="$1"; rm -rf "$T"; mkdir -p "$T/$MODEL/src/main/assets" "$T/$MODEL/src/main/java/x" "$T/ab_cloud-libs-shared/libs/core" "$T/ac_cloud-a" "$T/ac_cloud-b" "$T/ac_cloud-c"
  cp "$ROOT/$MODEL/src/main/AndroidManifest.xml" "$T/$MODEL/src/main/AndroidManifest.xml"
  cp "$ROOT/$MODEL/src/main/java/com/diegonmarcos/superapp/fleetconfig/SetupProvider.kt" "$T/$MODEL/src/main/java/x/SetupProvider.kt"
  printf "dependencies {\n    api project(':libs:devtools')\n    api project(':libs:fleetconfig-model')\n}\n" > "$T/ab_cloud-libs-shared/libs/core/build.gradle"
  cat > "$T/$MODEL/src/main/assets/fleet-config.json" <<'JSON'
{"migrate":["config","secret"],
 "bundle":{"mail.password":[{"store":"mail_prefs","key":"password"}]},
 "apps":{"a":{"package":"p.a","module":"ac_cloud-a","libs":["lib-core"],"items":[]},
         "b":{"package":"p.b","module":"ac_cloud-b","libs":[],"items":[]},
         "c":{"package":"p.c","module":"ac_cloud-c","libs":[],"items":[]}},
 "stores":{"mail_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["ac_cloud-a"],"keys":{"password":"secret","seen":"device"}},
           "a_ds":{"kind":"datastore","class":"config","doc":"d","used_by":["ac_cloud-b"]},
           "c_sec":{"kind":"encrypted","class":"secret","doc":"d","used_by":["ac_cloud-c"]}}}
JSON
  for a in a b c; do
    printf '{"modules":{"libs:core":{"dir":"../x"},"libs:fleetconfig-model":{"dir":"../y"}}}\n' > "$T/ac_cloud-$a/build.json"
    printf "def b = new groovy.json.JsonSlurper().parse(file('build.json'))\n" > "$T/ac_cloud-$a/settings.gradle"
  done
  mkdir -p "$T/ac_cloud-b/app/src/main/kotlin"
  printf 'class App { fun onCreate() { SetupStores.register("a_ds", H) } }\n' > "$T/ac_cloud-b/app/src/main/kotlin/App.kt"
  python3 - "$POLICY" "$T/policy.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
c["manifest"] = "fleet-private-data/fleet-config.json"
c["provider_source"] = "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/java/x/SetupProvider.kt"
c["register_roots"] = ["ac_cloud-b"]
c["exempt"] = {}; c["unserved"] = {}
json.dump(c, open(sys.argv[2], "w"))
PY
}
run() { python3 "$GUARD" --root "$1" --policy "${2:-$1/policy.json}" 2>&1; }
red()   { local out; out="$(run "$1" "${3:-}")"; if [ $? -ne 0 ] && grep -q "$2" <<<"$out"; then ok "$4"; else fail "$4 (wanted red with: $2) :: $out"; fi; }
green() { local out; out="$(run "$1" "${2:-}")"; if [ $? -eq 0 ]; then ok "$3"; else fail "$3 :: $out"; fi; }
setpol() { python3 - "$1/policy.json" "$2" <<'PY'
import json, sys
c = json.load(open(sys.argv[1])); exec(sys.argv[2]); json.dump(c, open(sys.argv[1], "w"))
PY
}

# real repository
out="$(python3 "$GUARD" --root "$ROOT" 2>&1)"; [ $? -eq 0 ] && ok "real fleet is green" || fail "real fleet is red :: $out"

T="$WORK/f"
fixture "$T"; green "$T" "" "fixture baseline is green (a_ds served by a registered handler)"

# M1
fixture "$T"; printf '{"modules":{"libs:core":{"dir":"../x"}}}\n' > "$T/ac_cloud-a/build.json"
red "$T" "S1 ac_cloud-a" "" "M1 an app without libs:fleetconfig-model fails"
# exempt control
setpol "$T" 'c["exempt"]={"ac_cloud-a":"cannot take a project dependency: a prebuilt AAR build (test)"}'
green "$T" "" "control: the same app exempted with a reason is green"
# M7
setpol "$T" 'c["exempt"]={"ac_cloud-a":"short"}'; red "$T" "S2 exemption ac_cloud-a: the reason" "" "M7a a thin exemption reason fails"
fixture "$T"; setpol "$T" 'c["exempt"]={"ac_cloud-a":"stale exemption for an app that links the provider already"}'
red "$T" "S2 exemption ac_cloud-a is stale" "" "M7b an exemption for a linked app is stale"
# M2
fixture "$T"; printf "dependencies { api project(':libs:devtools') }\n" > "$T/ab_cloud-libs-shared/libs/core/build.gradle"
red "$T" "S0 .*no longer .api.s" "" "M2 core not api-ing the module fails"
printf "dependencies { // api project(':libs:fleetconfig-model')\n}\n" > "$T/ab_cloud-libs-shared/libs/core/build.gradle"
red "$T" "S0 .*no longer .api.s" "" "M2b a commented-out api line is not the dependency"
# M3
for edit in 's/android:exported="true"/android:exported="false"/' 's/\.fleetsetup/.fleetconfigx/' 's#android:permission="[^"]*"##'; do
  fixture "$T"; sed -i "$edit" "$T/$MODEL/src/main/AndroidManifest.xml"; red "$T" "S0 " "" "M3 provider manifest mutated: $edit"
done
# M10
fixture "$T"; sed -i 's/checkCallingPermission(SetupContract.PERMISSION)/checkCallingPermission("x")/' "$T/$MODEL/src/main/java/x/SetupProvider.kt"
red "$T" "S0 .*re-check the caller" "" "M10 provider that stops re-checking the caller fails"
# M4
fixture "$T"; rm "$T/ac_cloud-b/app/src/main/kotlin/App.kt"; red "$T" "S3 ac_cloud-b: store a_ds (datastore) has no handler" "" "M4 a datastore store with no handler fails"
printf '// SetupStores.register("a_ds", H)\n' > "$T/ac_cloud-b/app/src/main/kotlin/App.kt"; red "$T" "S3 ac_cloud-b: store a_ds" "" "M4b a comment that names the register call is not a handler"
setpol "$T" 'c["unserved"]={"ac_cloud-b":{"a_ds":"waiting for the app to expose its DataStore instance"}}'; green "$T" "" "control: an unserved entry with a reason is green"
setpol "$T" 'c["unserved"]={"ac_cloud-b":{"a_ds":"short"}}'; red "$T" "S3 ac_cloud-b: the .unserved. reason" "" "M4c a thin unserved reason fails"
# M6
fixture "$T"; setpol "$T" 'c["unserved"]={"ac_cloud-c":{"c_sec":"served by the default but listed anyway, so stale"}}'
red "$T" "S3 ac_cloud-c: .unserved. entry for c_sec is stale" "" "M6 a stale unserved entry fails"
# M5
fixture "$T"; printf "include ':libs:core'\n" > "$T/ac_cloud-a/settings.gradle"
red "$T" "S1 ac_cloud-a.*by hand and not libs:fleetconfig-model" "" "M5 a hand-written settings file without the model fails"
printf "include ':libs:core', ':libs:fleetconfig-model'\n" > "$T/ac_cloud-a/settings.gradle"; printf '{"name":"a"}\n' > "$T/ac_cloud-a/build.json"
green "$T" "" "control: a hand-written settings file that includes both links the provider"
# M8
fixture "$T"; python3 - "$T/$MODEL/src/main/assets/fleet-config.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["bundle"]["x.y"] = [{"store": "nope", "key": "k"}]; json.dump(m, open(p, "w"))
PY
red "$T" "S4 bundle route x.y -> nope: the store is not declared" "" "M8a a bundle route to an undeclared store fails"
fixture "$T"; python3 - "$T/$MODEL/src/main/assets/fleet-config.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["bundle"]["x.y"] = [{"store": "mail_prefs", "key": "seen"}]; json.dump(m, open(p, "w"))
PY
red "$T" "S4 bundle route x.y -> mail_prefs.seen: a device key never migrates" "" "M8b a bundle route to a device key fails"
# M8c
fixture "$T"; python3 - "$T/$MODEL/src/main/assets/fleet-config.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["bundle"]["x.y"] = [{"store": "mail_prefs", "key": "password", "pull": "ac_cloud-zzz"}]; json.dump(m, open(p, "w"))
PY
red "$T" "S4 bundle route x.y -> mail_prefs: .pull. names ac_cloud-zzz" "" "M8c a pull from an app that is not in the manifest fails"
fixture "$T"; python3 - "$T/$MODEL/src/main/assets/fleet-config.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["bundle"]["x.y"] = [{"store": "mail_prefs", "key": "password", "pull": "b"}]; json.dump(m, open(p, "w"))
PY
red "$T" "S4 bundle route x.y -> mail_prefs: .pull. app b does not declare that store" "" "M8d a pull from an app that does not declare the store fails"
# M9
mkdir -p "$WORK/empty"; cp "$POLICY" "$WORK/empty/policy.json"; red "$WORK/empty" "S5" "" "M9 an empty checkout is not a clean fleet"

echo
[ "$FAILURES" -eq 0 ] && echo "fleet-setup-guard.test: all checks passed" || { echo "fleet-setup-guard.test: $FAILURES FAILED"; exit 1; }
