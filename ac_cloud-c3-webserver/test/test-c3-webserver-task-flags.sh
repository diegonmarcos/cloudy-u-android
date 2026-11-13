#!/usr/bin/env bash
# #1712 the app is ONE normal task that stays in Android's Recents and can be switched back to, also when another app's ADB button opened it.
#
# THE FAILURE THIS EXISTS FOR. The Store's ADB chip starts the owner's exported ShellArmActivity (libs:shell-broker, excludeFromRecents)
# with FLAG_ACTIVITY_NEW_TASK. With the default package affinity a cold start made that trampoline the ROOT of the webserver's task, and
# Android hides a task whose root carries excludeFromRecents: the app was gone from Recents after the user swapped away. The trampoline now has
# taskAffinity="" (its own throwaway task) and starts MainActivity (singleTask, package affinity) into the app's own task.
#
# Reads the MERGED manifest the build produces: the app's own AndroidManifest.xml plus the manifest of every library the build links
# (build.json::modules, and adb_owner_modules while 1_cicd/src/data/adb-owner.json names this app). The CI also checks the built APK
# (check-apk-task-flags.sh, run by the ship workflow after the build).
#   F1 MainActivity is the only LAUNCHER activity and it is a normal task root: launchMode standard|singleTask|singleTop, no excludeFromRecents /
#      noHistory / finishOnTaskLaunch / documentLaunchMode / singleInstance / allowTaskReparenting / taskAffinity other than the package's own
#   F2 every other activity that hides itself (excludeFromRecents, noHistory, finishOnTaskLaunch, documentLaunchMode) either has taskAffinity=""
#      (never the root of the app's task) or is declared in HIDDEN_OK with the reason it cannot become the root
#   F3 ShellArmActivity (the ADB deep link's entry) is exported behind the signature permission and has taskAffinity=""
#   F4 the trampoline starts MainActivity with NEW_TASK (so the deep link lands in the app's own task, never in the caller's)
# Each rule is proven red by a mutation (run with MUTATE=1).
set -u
cd "$(dirname "$0")/.." || exit 2

check() { # $1 = app dir, $2 = libs root
  python3 - "$1" "$2" <<'PY'
import json, os, re, sys
import xml.etree.ElementTree as ET
app, libs = sys.argv[1], sys.argv[2]
A = '{http://schemas.android.com/apk/res/android}'
bj = json.load(open(os.path.join(app, 'build.json')))
dirs = []
for name, spec in bj['modules'].items():
    if name.startswith('_'): continue
    dirs.append(os.path.normpath(os.path.join(app, (spec or {}).get('dir', name.replace(':', '/')))))
owner = json.load(open(os.path.join(app, '../1_cicd/src/data/adb-owner.json'))).get('adb_owner') == 'ac_cloud-c3-webserver'
if owner:
    for name in bj.get('adb_owner_modules', {}):
        if not name.startswith('_'): dirs.append(os.path.join(libs, name.split(':', 1)[1]))
mans = [os.path.join(app, 'src-tauri/gen/android/app/src/main/AndroidManifest.xml')] + [os.path.join(d, 'src/main/AndroidManifest.xml') for d in dirs]
acts = {}  # class -> merged attribute dict (the app's own declaration wins, like the manifest merger)
for i, m in enumerate(mans):
    if not os.path.exists(m): continue
    root = ET.parse(m).getroot()
    for a in root.iter('activity'):
        n = a.get(A + 'name')
        if n.startswith('.'): n = 'com.diegonmarcos.cloudwebserver' + n
        cur = acts.setdefault(n, {'attrs': {}, 'filters': [], 'src': m})
        for k, v in a.attrib.items(): cur['attrs'].setdefault(k, v)
        for f in a.findall('intent-filter'):
            cur['filters'].append([c.get(A + 'name') for c in f.iter('category')])
e = []
# HIDDEN_OK: activities that hide themselves and are fine: the reason each can never be the root of the app's task
HIDDEN_OK = {'com.diegonmarcos.superapp.crash.CrashActivity': 'its own process (:crash), shown only after the app crashed, so the app task already exists'}
def at(n, k): return acts[n]['attrs'].get(A + k)
HIDE = ('excludeFromRecents', 'noHistory', 'finishOnTaskLaunch', 'documentLaunchMode')
launchers = [n for n, a in acts.items() if any('android.intent.category.LAUNCHER' in f for f in a['filters'])]
MAIN = 'com.diegonmarcos.cloudwebserver.MainActivity'
if launchers != [MAIN]: e.append('F1 the only LAUNCHER activity must be MainActivity, got %s' % launchers)
if MAIN in acts:
    for k in ('excludeFromRecents', 'noHistory', 'finishOnTaskLaunch', 'allowTaskReparenting'):
        if at(MAIN, k) not in (None, 'false'): e.append('F1 MainActivity has %s=%s' % (k, at(MAIN, k)))
    if at(MAIN, 'documentLaunchMode') not in (None, 'none'): e.append('F1 MainActivity has documentLaunchMode')
    if at(MAIN, 'launchMode') not in (None, 'standard', 'singleTask', 'singleTop'): e.append('F1 MainActivity launchMode %s' % at(MAIN, 'launchMode'))
    if at(MAIN, 'taskAffinity') not in (None,): e.append('F1 MainActivity has its own taskAffinity %s' % at(MAIN, 'taskAffinity'))
for n in acts:
    if n == MAIN: continue
    hides = [k for k in HIDE if at(n, k) not in (None, 'false', 'none')]
    if hides and at(n, 'taskAffinity') != '' and n not in HIDDEN_OK:
        e.append('F2 %s hides itself (%s) on the app\'s own affinity: it can become the root of the app task and hide it from Recents' % (n, ','.join(hides)))
ARM = 'com.diegonmarcos.superapp.shellbroker.ShellArmActivity'
if owner:
    if ARM not in acts: e.append('F3 the owner build must declare ShellArmActivity')
    else:
        if at(ARM, 'exported') != 'true' or at(ARM, 'permission') != 'com.diegonmarcos.cloud.permission.CONSTELLATION_DATA': e.append('F3 ShellArmActivity must be exported behind the signature permission')
        if at(ARM, 'taskAffinity') != '': e.append('F3 ShellArmActivity must have taskAffinity=""')
        src = open(os.path.join(libs, 'shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellArmActivity.kt')).read()
        if 'FLAG_ACTIVITY_NEW_TASK' not in src or '.MainActivity' not in src: e.append('F4 the trampoline must start MainActivity with FLAG_ACTIVITY_NEW_TASK')
print('\n'.join(e)); sys.exit(1 if e else 0)
PY
}
LIBS="$(cd ../ab_cloud-libs-shared/libs && pwd)"
check . "$LIBS" || { echo "FAIL"; exit 1; }
echo "ok: one normal task, ADB deep link lands in it, no hidden trampoline roots"
if [ "${MUTATE:-}" = 1 ]; then
  t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
  mut() { # $1 = label, $2 = file (relative to the copy), $3 = sed expr
    rm -rf "$t/m"; mkdir -p "$t/m/ac_cloud-c3-webserver" "$t/m/1_cicd/src/data" "$t/m/ab_cloud-libs-shared/libs"
    cp -r . "$t/m/ac_cloud-c3-webserver/"; cp ../1_cicd/src/data/adb-owner.json "$t/m/1_cicd/src/data/"
    cp -r ../ab_cloud-libs-shared/libs/. "$t/m/ab_cloud-libs-shared/libs/"
    sed -i "$3" "$t/m/$2"
    check "$t/m/ac_cloud-c3-webserver" "$t/m/ab_cloud-libs-shared/libs" >/dev/null && { echo "mutation not red: $1"; exit 1; }
    echo "mutation proven red: $1"
  }
  LIB=ab_cloud-libs-shared/libs/shell-broker/src/main/AndroidManifest.xml
  APPM=ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/AndroidManifest.xml
  mut "trampoline loses its own affinity" $LIB 's/android:taskAffinity=""//'
  mut "MainActivity excluded from recents" $APPM 's/android:excludeFromRecents="false"/android:excludeFromRecents="true"/'
  mut "MainActivity noHistory" $APPM 's/android:noHistory="false"/android:noHistory="true"/'
  mut "MainActivity singleInstance" $APPM 's/android:launchMode="singleTask"/android:launchMode="singleInstance"/'
  mut "trampoline starts MainActivity without NEW_TASK" ab_cloud-libs-shared/libs/shell-broker/src/main/java/com/diegonmarcos/superapp/shellbroker/ShellArmActivity.kt 's/FLAG_ACTIVITY_NEW_TASK/FLAG_ACTIVITY_SINGLE_TOP/'
fi
