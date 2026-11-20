#!/usr/bin/env bash
# #1833 Quick actions ▸ Panels ▸ Security.
#
#  P1  the Panels strip is All | Battery Hungers | Security | Tools: Security comes right after Battery Hungers.
#  P2  RULE 16 PARITY: every Tools-tab group of the control panel, and every field of every control in it, is what it was
#      before Security existed (test/panels-tools-baseline-1833.json). A field may be ADDED (the two share flags), none
#      removed or changed, and no Tools group moved to the Security tab.
#  P3  the Security tab draws, in order: Developer & debugging, Accessibility apps, Features using accessibility, VPN &
#      location, and each gathers exactly the controls the owner listed (Wireless debugging and the Floating menu are the
#      SAME tiles as on Tools, by flag, not copies).
#  P4  every Security control has a capability that reads the device (observed = true, a read, a re-read verdict).
#  P5  routed: SectionPages -> SCOPE_SECURITY; the fragment draws the security grid; the grid filters by tab.
#  P2b RULE 16 PARITY for the Security tab itself (redo after the on-phone check): every group and every control of the frozen
#      pre-redo declaration (test/panels-security-baseline-1833.json) is still declared with its id, label and icon; a control
#      that left Accessibility apps is still drawn there by the dynamic group (its a11y_package is not adopted by that group).
#  P6  no hardcoded component list: the apps group is dynamic (AccessibilityManager), the declared app tiles name a package whose
#      manifest declares an accessibility service (so Keyboard, SuperApp and Vault appear when installed).
#  P7  lib builds: LIB_PREFIX is lib-apks' application_id_prefix; no current lib module declares an accessibility service (a lib
#      that offers one is an old build, offered Uninstall); no label anywhere is the anonymous "Cloud Library" / "Cloud Lib".
#  P8  pairing prompts: the ADB owner declares its keep-alive OFF, the owner wires ONE keep-alive switch, an implicit auto-start
#      never posts the pairing notification, and every pairing start is an explicit path.
#  P9  the emulator proof covers every Security tile (MATCH/MISMATCH table) and has a run without WRITE_SECURE_SETTINGS.
set -u
cd "$(dirname "$0")/.."
ROOT="$(cd .. && pwd)"
BJ=build.json
KT=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = OK ]; then ok "$2"; else bad "$2 — $1"; fi; }

echo "== P1: Security sits right after Battery Hungers =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in sec['pages']}
tabs = by['panels']['tabs']
labels = [by[t]['label'] for t in tabs]
p = by.get('panels-security', {})
ok = tabs == ['panels-all', 'panels-battery', 'panels-security', 'panels-tools'] and labels == ['All', 'Battery Hungers', 'Security', 'Tools'] \
     and p.get('hidden') is True and p.get('group') == 'Launcher'
launcher = next(g for g in sec['groups'] if g['id'] == 'launcher')['pages']
ok = ok and 'panels-security' in launcher and launcher.index('panels-security') == launcher.index('panels-battery') + 1
print('OK' if ok else 'tabs=%r labels=%r page=%r' % (tabs, labels, p))
PY
)" "Panels = All | Battery Hungers | Security | Tools; panels-security declared hidden under Launcher"

echo "== P2: rule 16 — the Tools tab lost nothing =="
check "$(python3 - "$BJ" test/panels-tools-baseline-1833.json <<'PY'
import json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
base = json.load(open(sys.argv[2]))
now = {g['id']: g for g in cp['groups']}
probs = []
tools_now = [g['id'] for g in cp['groups'] if g.get('tab', 'tools') == 'tools' and g.get('draw', True)]
if tools_now != [g['id'] for g in base['groups']]: probs.append('Tools groups %r != %r' % (tools_now, [g['id'] for g in base['groups']]))
for g in base['groups']:
    n = now.get(g['id'])
    if n is None: probs.append('group %s gone' % g['id']); continue
    for k, v in g.items():
        if k == 'controls': continue
        if n.get(k) != v: probs.append('group %s field %s changed' % (g['id'], k))
    nc = {c['id']: c for c in n.get('controls', [])}
    if [c['id'] for c in n.get('controls', [])] != [c['id'] for c in g.get('controls', [])]:
        probs.append('group %s controls %r' % (g['id'], [c['id'] for c in n.get('controls', [])]))
    for c in g.get('controls', []):
        m = nc.get(c['id'], {})
        for k, v in c.items():
            if m.get(k) != v: probs.append('%s.%s changed/lost' % (c['id'], k))
print('; '.join(probs) or 'OK')
PY
)" "every Tools group and every field of every control matches the frozen baseline"

echo "== P2b: rule 16 - the Security tab lost nothing in the redo =="
check "$(python3 - "$BJ" test/panels-security-baseline-1833.json <<'PY'
import json, sys
groups = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
base = json.load(open(sys.argv[2]))['groups']
now = {g['id']: g for g in groups}
ctl = {c['id']: c for g in groups for c in g.get('controls', [])}
functional = [g for g in groups if not g.get('derive')]
probs = []
for g in base:
    n = now.get(g['id'])
    if n is None: probs.append('group %s gone' % g['id']); continue
    for k in ('label', 'label_res', 'subtitle_res', 'tab', 'derive', 'draw'):
        if k in g and n.get(k) != g[k]: probs.append('group %s.%s changed' % (g['id'], k))
    for c in g.get('controls', []):
        m = ctl.get(c['id'])
        if m is None: probs.append('control %s gone' % c['id']); continue
        for k in ('label', 'icon'):
            if m.get(k) != c.get(k): probs.append('%s.%s changed' % (c['id'], k))
# every (derived group, control) pair of the baseline is still drawn there: by flag, or through the dynamic group
home = next(g for g in base if g['id'] == 'security_home')
for g in base:
    if not g.get('derive'): continue
    was = [c['id'] for c in home['controls'] if c.get(g['derive']) is True]
    n = now[g['id']]
    gathered = [c['id'] for f in functional for c in f.get('controls', []) if c.get(n['derive']) is True]
    adopted = {ctl[i].get('a11y_package') for i in gathered if ctl.get(i, {}).get('a11y_package')}
    for cid in was:
        if cid in gathered: continue
        pkg = ctl.get(cid, {}).get('a11y_package')
        if n.get('dynamic') == 'fleet_accessibility_services' and pkg and pkg not in adopted: continue
        probs.append('%s no longer drawn in %s' % (cid, g['id']))
print('; '.join(probs) or 'OK')
PY
)" "every pre-redo Security group and control is still declared and still drawn where it was"

echo "== P3: what the Security tab draws =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
functional = [g for g in groups if not g.get('derive')]
sec = [g for g in groups if g.get('tab') == 'security' and g.get('draw', True)]
want = {
  'security_debug': ['developer_options', 'adb_shell', 'usb_debugging', 'unverified_developers', 'wireless_debugging'],
  'security_access_apps': ['access_keyboard', 'access_superapp', 'access_others'],
  'security_access_features': ['access_keyboard', 'access_vault', 'edge_menus', 'floating_nav'],
  'security_net': ['vpn', 'location'],
}
probs = []
if [g['id'] for g in sec] != list(want): probs.append('security groups %r' % [g['id'] for g in sec])
for g in sec:
    if not g.get('derive'): probs.append('%s is not a derived view' % g['id']); continue
    got = [c['id'] for f in functional for c in f.get('controls', []) if c.get(g['derive']) is True]
    if got != want.get(g['id']): probs.append('%s gathers %r' % (g['id'], got))
home = next((g for g in groups if g['id'] == 'security_home'), None)
if not home or home.get('draw') is not False or home.get('tab') != 'security': probs.append('security_home is not a drawn-nowhere home')
print('; '.join(probs) or 'OK')
PY
)" "Developer & debugging | Accessibility apps | Features using accessibility | VPN & location, each with the owner's controls"

echo "== P4: every Security control reads the device =="
check "$(python3 - "$BJ" "$KT/configs/DeviceControls.kt" <<'PY'
import json, re, sys
home = next(g for g in json.load(open(sys.argv[1]))['ui']['control_panel']['groups'] if g['id'] == 'security_home')
src = open(sys.argv[2]).read()
ids = [(m.start(), m.group(1)) for m in re.finditer(r'"([a-z_]+)" to Control\(', src)]
blocks = {cid: src[pos:(ids[i + 1][0] if i + 1 < len(ids) else len(src))] for i, (pos, cid) in enumerate(ids)}
probs = []
for c in home['controls']:
    b = blocks.get(c['id'])
    if b is None: probs.append('%s: no capability' % c['id']); continue
    for need in ('observed = true', 'read =', 'probe =', 'open ='):
        if need not in b: probs.append('%s: no %s' % (c['id'], need))
    if 'set =' not in b and 'tap =' not in b: probs.append('%s: neither a switch nor a tap action' % c['id'])
    if 'Verdict(true' in b: probs.append('%s: trusts its write' % c['id'])
print('; '.join(probs) or 'OK')
PY
)" "each Security control is observed, has a read, a set judged by a re-read, and a system screen"

echo "== P4b: VPN is OUR mesh tunnel, and its hold opens OUR page =="
check "$(python3 - "$KT/configs/DeviceControls.kt" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
b = src[src.index('"vpn" to Control('):src.index('"location" to Control(')]
probs = []
if 'byId.getValue("mesh").read(ctx)' not in b: probs.append("vpn does not read the mesh tunnel (WireGuard backend)")
if 'vpnUp' in b: probs.append("vpn reads 'any VPN transport', which Always-on VPN keeps up while the tunnel is down")
if 'ACTION_VPN_SETTINGS' in b: probs.append("vpn hold opens Android's VPN settings instead of the Cloud Mesh page")
if 'byId.getValue("mesh").open' not in b: probs.append("vpn hold does not open the Cloud Mesh page")
print('; '.join(probs) or 'OK')
PY
)" "the VPN light is the mesh tunnel's state; hold opens the Cloud Mesh page"

echo "== P5: routed and drawn =="
p5=""
grep -qF 'pageId == "panels-security" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_SECURITY)' "$KT/launcher/SectionPages.kt" || p5="$p5 no-route"
grep -qF 'val showSecurity = all || scope == SCOPE_SECURITY' "$KT/settings/LauncherConfigFragment.kt" || p5="$p5 no-scope"
grep -qF 'ControlsGridHost(R.id.security_grid_host, com.diegonmarcos.superapp.configs.DeviceControls.TAB_SECURITY)' "$KT/settings/LauncherConfigFragment.kt" || p5="$p5 no-grid"
grep -qF 'for (group in DeviceControls.groupsOn(tab))' "$KT/configs/ControlFragment.kt" || p5="$p5 grid-not-filtered"
grep -qF 'val groups = DeviceControls.groupsOn(tab)' "$KT/configs/ControlFragment.kt" || p5="$p5 refresh-not-filtered"
grep -qF 'DeviceControls.dynamicTiles(ctx, g)' "$KT/configs/ControlFragment.kt" || p5="$p5 dynamic-not-read"
grep -qF 'control:detail:' "$KT/configs/ControlFragment.kt" || p5="$p5 no-detail-line"
grep -qF 'testTagsAsResourceId = true' "$KT/configs/ControlFragment.kt" || p5="$p5 no-resource-ids"
grep -qF 'if (control.slow) slowDispatcher else ioDispatcher' "$KT/configs/ControlFragment.kt" || p5="$p5 slow-reads-share-the-thread"
grep -qF 'tab = g.optString("tab", TAB_TOOLS)' "$KT/configs/DeviceControls.kt" || p5="$p5 tab-not-data"
grep -qF '<item name="security_grid_host"' app/src/main/res/values/ids.xml || p5="$p5 no-host-id"
[ -z "$p5" ] && ok "panels-security -> SCOPE_SECURITY -> the security grid, filtered by the declared tab" || bad "routing:$p5"

echo "== P6: no hardcoded component list; the declared app tiles are real accessibility apps =="
check "$(python3 - "$ROOT" "$BJ" "$KT/configs/SecurityControls.kt" "$KT/configs/DeviceControls.kt" <<'PY'
import json, re, sys, os
root, bj, src, dc = sys.argv[1], sys.argv[2], open(sys.argv[3]).read(), open(sys.argv[4]).read()
probs = []
if re.search(r'"com\.diegonmarcos\.[a-z.]+/[A-Za-z.]+"', src): probs.append('SecurityControls names a component literal')
if 'installedAccessibilityServiceList' not in src: probs.append('not enumerated from AccessibilityManager')
if 'getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)' not in src: probs.append('bound state not from AccessibilityManager')
groups = json.load(open(bj))['ui']['control_panel']['groups']
apps = next(g for g in groups if g['id'] == 'security_access_apps')
if apps.get('dynamic') != 'fleet_accessibility_services': probs.append('Accessibility apps is not the dynamic group')
if 'const val DYNAMIC_FLEET_A11Y = "fleet_accessibility_services"' not in dc: probs.append('DeviceControls has no dynamic source')
pkgs = {c['id']: c.get('a11y_package') for g in groups for c in g.get('controls', []) if c.get('a11y_package')}
mf = {'com.diegonmarcos.cloudkeyboard': 'ac_cloud-keyboard', 'com.diegonmarcos.superapp': 'aa_cloud-superapp', 'com.diegonmarcos.cloudvault': 'ac_cloud-vault'}
for want in ('access_keyboard', 'access_superapp', 'access_vault'):
    pkg = pkgs.get(want)
    if pkg not in mf: probs.append('%s: a11y_package %r is not Keyboard/SuperApp/Vault' % (want, pkg)); continue
    text = open(os.path.join(root, mf[pkg], 'app/src/main/AndroidManifest.xml')).read()
    if 'android.accessibilityservice.AccessibilityService' not in text: probs.append('%s manifest declares no accessibility service' % mf[pkg])
print('; '.join(probs) or 'OK')
PY
)" "the apps group enumerates AccessibilityManager; Keyboard, SuperApp and Vault declare a service, so each appears when installed"

echo "== P7: library builds and names =="
check "$(python3 - "$ROOT" "$KT/configs/SecurityControls.kt" <<'PY'
import json, re, sys, os, glob
root, src = sys.argv[1], open(sys.argv[2]).read()
probs = []
la = json.load(open(os.path.join(root, 'ab_cloud-libs-shared/lib-apks/build.json')))['lib_apks']
m = re.search(r'const val LIB_PREFIX = "([^"]+)"', src)
if not m or m.group(1) != la['application_id_prefix']: probs.append('LIB_PREFIX %r != %r' % (m and m.group(1), la['application_id_prefix']))
g = open(os.path.join(root, 'ab_cloud-libs-shared/lib-apks/app/build.gradle')).read()
if 'libLabel: "cloud-lib-${name}"' not in g: probs.append('lib APK label is not cloud-lib-<module>')
for mfp in glob.glob(os.path.join(root, 'ab_cloud-libs-shared/libs/*/src/main/AndroidManifest.xml')):
    if 'android.accessibilityservice.AccessibilityService' in open(mfp, errors='replace').read():
        probs.append('lib %s declares an accessibility service: libVerdict would call it old' % mfp.split('/libs/')[1].split('/')[0])
# no app or lib is named the anonymous "Cloud Library" / "Cloud Lib"
bad = re.compile(r'^\s*Cloud Lib(rary)?\s*$', re.I)
for pat in ('*/app/src/main/AndroidManifest.xml', '*/app/src/main/res/values/strings.xml', 'ab_cloud-libs-shared/lib-apks/app/build.gradle',
            'ab_cloud-libs-shared/libs/*/src/main/AndroidManifest.xml', 'ab_cloud-libs-shared/libs/*/src/main/res/values/strings.xml'):
    for f in glob.glob(os.path.join(root, pat)):
        t = open(f, errors='replace').read()
        for v in re.findall(r'android:label="([^"]*)"', t) + re.findall(r'<string name="app_name"[^>]*>([^<]*)<', t) + re.findall(r'libLabel:\s*"([^"]*)"', t):
            if bad.match(v): probs.append('%s is labelled %r' % (os.path.relpath(f, root), v))
for f in glob.glob(os.path.join(root, '*/build.json')):
    try: n = json.load(open(f)).get('name', '')
    except Exception: continue
    if isinstance(n, str) and bad.match(n): probs.append('%s name %r' % (os.path.relpath(f, root), n))
print('; '.join(probs) or 'OK')
PY
)" "LIB_PREFIX = lib-apks prefix; no current lib declares an accessibility service; nothing is named 'Cloud Library'"

echo "== P8: pairing prompts only from a tap, keep-alive one switch =="
LIBS="$ROOT/ab_cloud-libs-shared/libs"
check "$(python3 - "$ROOT" <<'PY'
import json, re, sys, os, glob
root = sys.argv[1]
L = os.path.join(root, 'ab_cloud-libs-shared/libs')
probs = []
own = json.load(open(os.path.join(root, '1_cicd/src/data/adb-owner.json')))['adb_owner']
pc = json.load(open(os.path.join(root, own, 'build.json'))).get('privileged_channel')
if not pc or pc.get('default_on') is not False: probs.append('ADB owner %s does not declare privileged_channel.default_on false' % own)
ro = open(os.path.join(L, 'adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/AdbReadinessOwner.kt')).read()
if 'wireKeepAlive()' not in ro.split('fun install()')[1].split('}')[0]: probs.append('AdbReadinessOwner.install does not wire the one keep-alive switch')
if 'ChannelAutoArm.hostSet' not in ro or 'ChannelAutoArm.hostSwitch' not in ro: probs.append('the owner does not install both host hooks')
sa = open(os.path.join(L, 'shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/ServerAutoStart.kt')).read()
if 'DeviceConnectExecutor(app, explicit = mayPair(reason))' not in sa: probs.append('the auto-start chain may post pairing')
if 'fun mayRun(reason: String, keepAliveOn: Boolean): Boolean = reason == "button" || keepAliveOn' not in sa: probs.append('implicit gate changed')
cd = open(os.path.join(L, 'shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/ChannelDevice.kt')).read()
if 'StepId.PAIR -> if (!explicit)' not in cd: probs.append('the PAIR step is not gated on explicit')
# every place that posts the pairing notification: AdbPairingService.start (ChannelOps.pair, the dev route) and ChannelOps.pair callers
allowed = {'ChannelDevice.kt', 'AdbDevRoutes.kt', 'AdbPairingService.kt'}
roots = [L, os.path.join(root, 'aa_cloud-superapp/app/src'), os.path.join(root, 'ac_cloud-c3-webserver/src-tauri/gen/android/app/src')]
for r in roots:
    for f in glob.glob(os.path.join(r, '**/*.kt'), recursive=True):
        if '/test/' in f: continue
        t = open(f, errors='replace').read()
        if 'AdbPairingService.start(' in t and os.path.basename(f) not in allowed: probs.append('new pairing start in %s' % os.path.relpath(f, root))
        for m in re.finditer(r'ChannelOps\.pair\(', t):
            line = t[:m.start()].split('\n')[-1]
            if not re.search(r'(run\("Pairing"\)|val said = )', line): probs.append('ChannelOps.pair outside a tap or the gated step: %s' % os.path.relpath(f, root))
print('; '.join(probs) or 'OK')
PY
)" "owner keep-alive default OFF, one switch, implicit runs never pair, pairing starts only from the known explicit paths"

echo "== P9: the emulator proof covers every Security tile =="
check "$(python3 - "$BJ" "$ROOT/.github/workflows/test-superapp-security-tab-emulator.yml" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
wf = open(sys.argv[2]).read()
sec = [g for g in groups if g.get('tab') == 'security' and g.get('draw', True)]
functional = [g for g in groups if not g.get('derive')]
ids = []
for g in sec:
    ids += [c['id'] for f in functional for c in f.get('controls', []) if c.get(g['derive']) is True]
probs = ['%s has no ground truth in the emulator proof' % i for i in dict.fromkeys(ids) if ('"%s"' % i) not in wf]
for need in ('MISMATCH', 'no-wss', 'a11y:', 'dumpsys accessibility', 'dumpsys window'):
    if need not in wf: probs.append('workflow lacks %r' % need)
print('; '.join(probs) or 'OK')
PY
)" "every Security tile has an adb ground truth; per-tile MATCH/MISMATCH; a run without WRITE_SECURE_SETTINGS"

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
