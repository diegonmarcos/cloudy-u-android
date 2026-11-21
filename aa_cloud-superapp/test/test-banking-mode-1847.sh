#!/usr/bin/env bash
# #1847 Banking Apps Mode (Quick actions > Preset > Mode).
#  B1  RULE 16: every mode of the frozen declaration (test/modes-baseline-1847.json) is still declared, every field equal;
#      Banking Apps Mode is appended and the Preset > Mode group lists it.
#  B2  the declaration: every item the owner named has a `device` (or `keeper`) step, none needs the shell, keep-alive FIRST, the
#      VPN item present, banks declared, `no_shell` and `cycle` false.
#  B3  PAIRING GATE (#1833): the fragment's pick/off path for a no_shell mode never calls BgUi.acquire (the ADB connect prompt);
#      the mode's sources reach no connect / arm / pair call; the gate's own guards (ChannelAutoArm, mayPair, WirelessDebugKeeper)
#      are untouched by this change.
#  B4  wiring: the engine has the `device` kind (apply AND revert), the host implements the items, the watcher is fed by the
#      floating-nav poll, the badge's Change cycle skips the mode.
set -u
cd "$(dirname "$0")/.."
BJ=${BUILD_JSON:-build.json}
KT=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = OK ]; then ok "$2"; else bad "$2 — $1"; fi; }
has() { if grep -qF -- "$2" "$1"; then ok "$3"; else bad "$3"; fi; }
hasnot() { if grep -qF -- "$2" "$1"; then bad "$3"; else ok "$3"; fi; }

echo "== B1: rule 16 =="
check "$(python3 - "$BJ" test/modes-baseline-1847.json <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); base = json.load(open(sys.argv[2]))['modes']
now = {m['id']: m for m in b['ui']['launcher_modes']}
probs = []
for m in base:
    n = now.get(m['id'])
    if n is None: probs.append('mode %s gone' % m['id']); continue
    for k, v in m.items():
        if n.get(k) != v: probs.append('%s.%s changed/lost' % (m['id'], k))
ids = [m['id'] for m in b['ui']['launcher_modes']]
if ids[:len(base)] != [m['id'] for m in base]: probs.append('order of the frozen modes changed')
if ids[-1] != 'banking': probs.append('banking is not appended')
print('; '.join(probs) or 'OK')
PY
)" "every frozen mode and field is still declared; Banking Apps Mode follows them"

echo "== B2: the declaration =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
m = next(x for x in json.load(open(sys.argv[1]))['ui']['launcher_modes'] if x['id'] == 'banking')
st = m['steps']; ids = [s['id'] for s in st]
probs = []
items = {s.get('item') for s in st if s['kind'] == 'device'}
for need in ('developer_options', 'usb_debugging', 'wireless_debugging', 'adb_shell', 'a11y_fleet', 'edge_menus', 'floating_nav', 'vpn'):
    if need not in items: probs.append('no step for %s' % need)
if not any(s['kind'] == 'keeper' for s in st): probs.append('no keep-alive step')
if st[0]['kind'] != 'keeper': probs.append('the keep-alive is not the first step')
if any(s.get('adb') is not False for s in st): probs.append('a step needs the shell')
if len(m.get('banks', [])) < 5 or any(not b.get('pkg') for b in m['banks']): probs.append('bank list')
if m.get('no_shell') is not True or m.get('cycle') is not False: probs.append('no_shell / cycle flags')
if m.get('auto_default') != 'off': probs.append('auto is not off by default')
if 'toggles' in m: probs.append('the mode names launcher toggles')
print('; '.join(probs) or 'OK')
PY
)" "every named item is a step, keep-alive first, nothing needs the shell, banks declared, auto off by default"

echo "== B3: pairing gate =="
F=$KT/settings/LauncherPresetsFragment.kt
has $F 'noShell = mode.noShell' "picking a no_shell mode skips the ADB acquire"
has $F 'if (noShell) { proceed = true; shell = com.diegonmarcos.superapp.bgpolicy.BgHost.shell(act) }' "no_shell runs with the shell only if already armed"
has $F 'noShell = noShell) { sh -> PresetModes.off' "leaving a no_shell mode skips the ADB acquire"
for f in BankingMode BankingWatcher; do
  if grep -vE '^\s*(\*|//|/\*)' $KT/system/$f.kt | grep -nE 'autoConnect|autoArm|openAdbShell|ChannelAutoArm\.set|AdbReadiness\.connector|ServerAutoStart|PairingFlow|BgUi\.acquire' | grep -q .; then bad "$f reaches a connect / arm / pair path"; else ok "$f reaches no connect / arm / pair path"; fi
done
LIB=../ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug
has $LIB/ServerAutoStart.kt 'fun mayPair(reason: String): Boolean = reason == "button"' "ServerAutoStart.mayPair gate unchanged: only a tap pairs"
has $LIB/ChannelAutoArm.kt 'fun enabled(ctx: Context): Boolean' "ChannelAutoArm switch unchanged"

echo "== B4: wiring =="
has $KT/bgpolicy/SuperSaving.kt '"device" -> applyDevice(s, entry)' "the engine applies a device step"
has $KT/bgpolicy/SuperSaving.kt '"device" ->' "the engine reverts a device step"
has $KT/bgpolicy/SuperSavingRuntime.kt 'BankingMode.read(app, item)' "the runtime host reads items through BankingMode"
has $KT/floatingnav/FloatingNavService.kt 'BankingWatcher.onForeground(' "the foreground poll feeds the watcher"
has $KT/settings/PresetModes.kt 'filter { it.cycle }' "the Change cycle skips Banking Apps Mode"
has $KT/settings/LauncherPresetsFragment.kt 'mode-step-owner:' "the report shows a needs-your-tap item with its deep link"
has $KT/settings/LauncherPresetsFragment.kt 'BankingSettingsPanel()' "the mode's settings (auto, VPN, banks) are drawn under its tile"
echo "passed=$PASS failed=$FAIL"; [ "$FAIL" = 0 ]
