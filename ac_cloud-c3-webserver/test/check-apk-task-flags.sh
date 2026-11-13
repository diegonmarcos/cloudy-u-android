#!/usr/bin/env bash
# #1712 the task flags of the BUILT APK's merged manifest (what Android really installs), the companion of test-c3-webserver-task-flags.sh.
# Usage: check-apk-task-flags.sh path/to/app.apk   (needs aapt2 from the Android build-tools; run by the ship workflow after the build)
set -eu
apk="${1:?apk}"
aapt2="$(command -v aapt2 || ls -1 "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -x "$aapt2" ] || { echo "::error::aapt2 not found"; exit 2; }
"$aapt2" dump xmltree --file AndroidManifest.xml "$apk" > "${TMPDIR:-/tmp}/apk-manifest-tree.txt"
python3 - "${TMPDIR:-/tmp}/apk-manifest-tree.txt" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
acts, cur, stack = [], None, []
for ln in lines:
    m = re.match(r'^(\s*)E: (\S+)', ln)
    if m:
        ind = len(m.group(1)); tag = m.group(2)
        while stack and stack[-1][0] >= ind: stack.pop()
        stack.append((ind, tag))
        if tag == 'activity': cur = {'attrs': {}, 'cats': []}; acts.append(cur)
        elif tag == 'category' and cur is not None: cur['_cat'] = True
        elif tag in ('activity-alias', 'service', 'receiver', 'provider'): cur = None
        continue
    a = re.match(r'^\s*A: (?:http://schemas.android.com/apk/res/android:)?([\w:-]+)(?:\(0x[0-9a-f]+\))?=(.*)$', ln)
    if a and cur is not None and stack:
        k, v = a.group(1), a.group(2)
        v = re.sub(r'\s*\(Raw: .*\)$', '', v).strip()
        if stack[-1][1] == 'activity': cur['attrs'][k] = v
        elif stack[-1][1] == 'category' and k == 'name': cur['cats'].append(v.strip('"'))
def val(a, k): return a['attrs'].get(k)
def is_true(v): return v is not None and (v == 'true' or (v.startswith('(type 0x12)') and v.endswith('0xffffffff')))
e = []
HIDDEN_OK = {'com.diegonmarcos.superapp.crash.CrashActivity'}
MAIN = 'com.diegonmarcos.cloudwebserver.MainActivity'
launch = [a for a in acts if 'android.intent.category.LAUNCHER' in a['cats']]
if len(launch) != 1 or val(launch[0], 'name').strip('"') != MAIN: e.append('launcher activities: %s' % [val(a, 'name') for a in launch])
for a in acts:
    n = val(a, 'name').strip('"')
    hides = [k for k in ('excludeFromRecents', 'noHistory', 'finishOnTaskLaunch') if is_true(val(a, k))]
    if val(a, 'documentLaunchMode') not in (None, '0', '(type 0x10)0x0'): hides.append('documentLaunchMode')
    aff = val(a, 'taskAffinity')
    if n == MAIN:
        if hides: e.append('MainActivity hides itself: %s' % hides)
        if aff is not None: e.append('MainActivity has its own affinity %s' % aff)
    elif hides and aff != '""' and n not in HIDDEN_OK:
        e.append('%s hides itself (%s) on the default affinity' % (n, hides))
    print('activity %s hides=%s affinity=%s launchMode=%s' % (n, hides, aff, val(a, 'launchMode')))
ARM = 'com.diegonmarcos.superapp.shellbroker.ShellArmActivity'
arm = [a for a in acts if val(a, 'name').strip('"') == ARM]
if not arm: e.append('ShellArmActivity (the ADB deep link entry) missing from the merged manifest')
elif val(arm[0], 'taskAffinity') != '""': e.append('ShellArmActivity affinity is %s, must be empty' % val(arm[0], 'taskAffinity'))
if e: print('FAIL\n' + '\n'.join(e)); sys.exit(1)
print('ok: merged manifest task flags')
PY
