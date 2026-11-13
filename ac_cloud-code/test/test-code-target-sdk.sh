#!/usr/bin/env bash
# #1708 cloud-code targets SDK 36 (silent Cloud Store updates) and keeps the terminal.
#   S1 build.json target_sdk >= 35; the guard policy no longer exempts the app
#   S2 the engine tells post-process.js to pin 28 only for a declared target <= 28, and fetches the native tools for >= 29
#   S3 tools/native-tools.json pins sha256 for proot, both loaders, talloc and axs on every ABI the terminal supported
#      (arm64-v8a, armeabi-v7a, x86_64); nothing executable is committed
#   S4 ProcessManager reads FDROID from the bundled libproot-xed.so, init-sandbox.sh runs from NATIVE_DIR when FDROID is false
#   S5 Terminal.js (non-F-Droid path) downloads only the root filesystem and links axs from NATIVE_DIR: nothing it downloads is executed
#   S6 jniLibs are extracted (post-process.js enableLegacyJni) so nativeLibraryDir holds real files
#   S7 the emulator job launches every tool (ship-cloud-code.yml -> test-native-tools.yml)
# Run MUTATE=1 to prove each assertion red.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$ROOT/.." && pwd)"
check() { python3 - "$1" "$2" <<'PY'
import json,os,re,sys
r,repo=sys.argv[1],sys.argv[2]; e=[]
rd=lambda *p: open(os.path.join(r,*p)).read()
b=json.loads(rd('build.json'))
if b['android']['target_sdk']<35: e.append('S1 target_sdk')
g=os.path.join(repo,'1_cicd/src/data/target-sdk-guard.json')
if os.path.exists(g) and 'ac_cloud-code' in json.load(open(g))['exempt']: e.append('S1 still exempt')
eng=rd('build.sh')
if '-le 28' not in eng or 'fetch-native-tools.py' not in eng or '-ge 29' not in eng: e.append('S2 engine')
n=json.loads(rd('tools','native-tools.json'))
need={'arm64-v8a':{'libproot-xed.so','libproot.so','libproot32.so','libtalloc.so','libaxs.so'},'armeabi-v7a':{'libproot-xed.so','libproot.so','libtalloc.so','libaxs.so'},'x86_64':{'libproot-xed.so','libproot.so','libproot32.so','libtalloc.so','libaxs.so'}}
for abi,names in need.items():
    f=n['abis'].get(abi,{}).get('files',{})
    if not names<=set(f) or not all(re.fullmatch(r'[0-9a-f]{64}',v) for v in f.values()): e.append('S3 '+abi)
for d,_,fs in os.walk(r):
    if any(x in d for x in ('node_modules','platforms','plugins/')): continue
    for x in fs:
        if x.endswith(('.so','.rootfs')): e.append('S3 committed binary '+x)
pm=rd('src/plugins/terminal/src/android/ProcessManager.java')
if 'libproot-xed.so' not in pm: e.append('S4 ProcessManager')
sh=rd('src/plugins/terminal/scripts/init-sandbox.sh')
if '$NATIVE_DIR/libproot-xed.so' not in sh or 'libaxs' not in rd('src/plugins/terminal/www/Terminal.js'): e.append('S4 init-sandbox')
t=rd('src/plugins/terminal/www/Terminal.js')
m=re.search(r'\}else\{(.*?)ln -s \$NATIVE_DIR/libaxs\.so',t,re.S)
if not m or 'alpine.tar.gz' not in m.group(1) or 'libproot' in m.group(1) or '"axs"' in m.group(1): e.append('S5 Terminal.js')
if 'useLegacyPackaging' not in rd('hooks','post-process.js'): e.append('S6 legacy jni')
wf=os.path.join(repo,'.github/workflows/ship-cloud-code.yml')
if os.path.exists(wf):
    w=open(wf).read()
    if 'test-native-tools.yml' not in w or 'libproot-xed.so:prootrun' not in w or 'libaxs.so:run' not in w: e.append('S7 emulator job')
print('\n'.join(e)); sys.exit(1 if e else 0)
PY
}
check "$ROOT" "$REPO" || { echo FAIL; exit 1; }
echo "ok: cloud-code targets >= 35 with its native tools in nativeLibraryDir"
if [ "${MUTATE:-}" = 1 ]; then
  t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
  for m in 'build.json|s/"target_sdk": 36/"target_sdk": 28/' 'tools/native-tools.json|s/libaxs/libaxz/' 'src/plugins/terminal/src/android/ProcessManager.java|s/libproot-xed.so/libproot-xedz.so/'; do
    rm -rf "$t/a"; mkdir -p "$t/a"; cp -r "$ROOT/." "$t/a/"
    sed -i "${m#*|}" "$t/a/${m%%|*}"; check "$t/a" "$t/none" >/dev/null && { echo "mutation $m not red"; exit 1; }
  done
  echo "mutations proven red"
fi
