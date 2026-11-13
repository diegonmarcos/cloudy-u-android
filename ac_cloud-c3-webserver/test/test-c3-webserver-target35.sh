#!/usr/bin/env bash
# #1708 cloud-c3-webserver targets SDK 35 (silent Cloud Store updates on Android 17) and keeps every feature.
#   T1 build.json target_sdk >= 35 and the Gradle file reads it from there
#   T2 the served root is read through All files access: all_files is in perms.needs and MANAGE_EXTERNAL_STORAGE is in the manifest
#   T3 KeepAliveService declares foregroundServiceType specialUse (required from targetSdk 34) and the code starts it with the typed call
#   T4 edge-to-edge is opted out for v35 in both day and night themes (layout unchanged)
#   T5 nothing here execs from app-private storage: the shell is /system/bin/sh, so W^X (targetSdk 29+) never applies;
#      the jump helpers stay mode 755 scripts that sh runs
#   T6 the guard policy no longer exempts this app
# Each assertion is proven red by a mutation (run with MUTATE=1).
set -u
cd "$(dirname "$0")/.." || exit 2
A=src-tauri/gen/android/app/src/main
check() { # $1 = dir holding the files to read
  local d="$1" bad=0
  python3 - "$d" <<'PY' || bad=1
import json,sys,re,os
d=sys.argv[1]; b=json.load(open(os.path.join(d,'build.json')))
e=[]
if b['android']['target_sdk']<35: e.append('T1 target_sdk below 35')
if 'all_files' not in b['perms']['needs']: e.append('T2 all_files not in perms.needs')
m=open(os.path.join(d,'src-tauri/gen/android/app/src/main/AndroidManifest.xml')).read()
if 'android.permission.MANAGE_EXTERNAL_STORAGE' not in m: e.append('T2 manifest lacks MANAGE_EXTERNAL_STORAGE')
if not re.search(r'KeepAliveService"[^>]*foregroundServiceType="specialUse"',m,re.S): e.append('T3 service type missing')
k=open(os.path.join(d,'src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/KeepAliveService.kt')).read()
if re.search(r'startKeepAlive\([^\n]*, 0\)',k): e.append('T3 untyped startForeground')
for v in ('values-v35','values-night-v35'):
    p=os.path.join(d,'src-tauri/gen/android/app/src/main/res',v,'themes.xml')
    if not os.path.exists(p) or 'windowOptOutEdgeToEdgeEnforcement' not in open(p).read(): e.append('T4 '+v)
s=open(os.path.join(d,'server/src/sshd.rs')).read()
if '"/system/bin/sh"' not in s: e.append('T5 shell is not /system/bin/sh')
for f in ('fleet-nix','fleet-termux','fleet-adb','fleet-ipc'):
    if not os.path.exists(os.path.join(d,'server/assets/jump',f)): e.append('T5 helper '+f)
if 'from_mode(0o755)' not in open(os.path.join(d,'server/src/jump.rs')).read(): e.append('T5 helper mode')
root=os.path.abspath(os.path.join(d,'..'))
g=json.load(open(os.path.join(root,'1_cicd/src/data/target-sdk-guard.json')))
if 'ac_cloud-c3-webserver' in g['exempt']: e.append('T6 still exempt')
print('\n'.join(e)); sys.exit(1 if e else 0)
PY
  return $bad
}
check . || { echo "FAIL"; exit 1; }
echo "ok: target 35 + all features declared"
if [ "${MUTATE:-}" = 1 ]; then
  t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
  mkdir -p "$t/ac_cloud-c3-webserver" "$t/1_cicd/src/data"; cp -r . "$t/ac_cloud-c3-webserver/"; cp ../1_cicd/src/data/target-sdk-guard.json "$t/1_cicd/src/data/"
  sed -i 's/"target_sdk": 35/"target_sdk": 28/' "$t/ac_cloud-c3-webserver/build.json"
  check "$t/ac_cloud-c3-webserver" >/dev/null && { echo "mutation not red"; exit 1; }
  echo "mutation proven red"
fi
