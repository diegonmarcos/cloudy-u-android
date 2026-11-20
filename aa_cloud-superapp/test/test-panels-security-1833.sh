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
#  P6  the accessibility components are the ones the three apps' manifests declare.
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

echo "== P3: what the Security tab draws =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
functional = [g for g in groups if not g.get('derive')]
sec = [g for g in groups if g.get('tab') == 'security' and g.get('draw', True)]
want = {
  'security_debug': ['developer_options', 'adb_shell', 'usb_debugging', 'wireless_debugging'],
  'security_access_apps': ['access_keyboard', 'access_superapp', 'access_vault', 'access_others'],
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
    for need in ('observed = true', 'read =', 'set =', 'open ='):
        if need not in b: probs.append('%s: no %s' % (c['id'], need))
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
grep -qF 'DeviceControls.groupsOn(tab).flatMap' "$KT/configs/ControlFragment.kt" || p5="$p5 refresh-not-filtered"
grep -qF 'tab = g.optString("tab", TAB_TOOLS)' "$KT/configs/DeviceControls.kt" || p5="$p5 tab-not-data"
grep -qF '<item name="security_grid_host"' app/src/main/res/values/ids.xml || p5="$p5 no-host-id"
[ -z "$p5" ] && ok "panels-security -> SCOPE_SECURITY -> the security grid, filtered by the declared tab" || bad "routing:$p5"

echo "== P6: the components are the manifests' own =="
check "$(python3 - "$ROOT" "$KT/configs/SecurityControls.kt" <<'PY'
import re, sys, os
root, src = sys.argv[1], open(sys.argv[2]).read()
comps = dict(re.findall(r'"(access_[a-z]+)" to "([^"]+)"', src))
mf = {
  'access_keyboard': ('ac_cloud-keyboard/app/src/main/AndroidManifest.xml', 'com.diegonmarcos.cloudkeyboard', '.grabber.GrabberSelectionService'),
  'access_superapp': ('aa_cloud-superapp/app/src/main/AndroidManifest.xml', 'com.diegonmarcos.superapp', '.system.LockScreenAccessibilityService'),
  'access_vault': ('ac_cloud-vault/app/src/main/AndroidManifest.xml', 'com.diegonmarcos.cloudvault', 'com.x8bit.bitwarden.Accessibility.AccessibilityService'),
}
probs = []
for cid, (path, pkg, cls) in mf.items():
    text = open(os.path.join(root, path)).read()
    if 'android:name="%s"' % cls not in text: probs.append('%s: %s not in %s' % (cid, cls, path))
    full = pkg + '/' + (pkg + cls if cls.startswith('.') else cls)
    if comps.get(cid) != full: probs.append('%s: %r != %r' % (cid, comps.get(cid), full))
print('; '.join(probs) or 'OK')
PY
)" "Keyboard, SuperApp and Vault components match their manifests"

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
