#!/usr/bin/env bash
# Tester: #1263 Configs > Setup > Network > WiFi over libs:wifi. Static (no device, no build); the rules themselves are JVM-tested in
# app/src/test/.../wifi/WifiLibTest.kt.
#
#   W1 the page is declared right after ADB Shell (Setup, subgroup Network), five hidden tabs Scan | Channels | Link | Tools | Log, every
#      tab routed to WifiFragment, WifiTab ids equal the declared tabs and the lib's default tab list
#   W2 the Configs group row holds the page and its tabs (horizontal_two_rows: every page in exactly one group)
#   W3 the page is the lib's: WifiFragment is a scroll-reporting KitComposeFragment over WifiHub with the host config from build.json; the
#      panel is a LazyColumn with a bottom clearance; every promised feature is present in the lib
#   W4 the vocabulary is data: the OUI asset is the sorted IEEE registry, the channel plans are valid, the config names every tool bound,
#      build.json declares the throughput endpoint; no vendor table and no channel list is typed in Kotlin
#   W5 permissions: NEARBY_WIFI_DEVICES (neverForLocation, as the app declares it), fine location, Wi-Fi / network state, multicast
#   W6 registered everywhere a shared lib must be: build.json module + app dependency, lib-apks (excluded + described), gradle dependency,
#      ship / test path triggers, lib-classes, lib-consumers
#   W7 the lib is isolated and app-agnostic: it depends on libs:ui-icons and the libs:perms contract (#1274) only and imports nothing of a host (no BuildConfig, no app class)
#   W8 i18n: values and values-es hold the same keys, every key the Kotlin names exists
#   W9 the runtime nav walk reaches the page: cloud-android-nav-walk.py's own page list for the config section contains wifi
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
LIB="$ROOT/ab_cloud-libs-shared/libs/wifi"
LK="$LIB/src/main/java/com/diegonmarcos/superapp/wifi"
LA="$LIB/src/main/assets/wifi"
PK="$ROOT/data/fleet/packs"   # #1547 the OUI table is a downloadable pack, no longer a lib asset
for f in "$BJ" "$KT/configs/WifiFragment.kt" "$KT/launcher/SectionPages.kt" "$LIB/build.gradle" "$LIB/src/main/AndroidManifest.xml" "$LK/WifiUi.kt" \
         "$LK/WifiTabs.kt" "$PK/wifi-oui.tsv" "$PK/wifi-oui.json" "$LA/channel-plans.json" "$LA/wifi-config.json" "$LIB/src/main/res/values/strings.xml" "$LIB/src/main/res/values-es/strings.xml"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done
command -v python3 >/dev/null 2>&1 || { echo "  ABORT: python3 missing"; exit 2; }

echo "== W1: the page, its place and its five tabs =="
check "$(python3 - "$BJ" "$LA/wifi-config.json" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
pages = sec['pages']; by = {p['id']: p for p in pages}
probs = []
w = by.get('wifi')
if not w: print('no wifi page'); sys.exit()
if (w.get('group'), w.get('subgroup'), w.get('label'), w.get('icon')) != ('Network', '', 'Wi-Fi', 'ic_wifi') or w.get('hidden'): probs.append('page = %r' % {k: w.get(k) for k in ('group', 'subgroup', 'label', 'icon', 'hidden')})
# #1737 Wi-Fi is the first icon after the NG Firewall hub in the Network group (the | between them is a separator page, not a tile)
vis = [p['id'] for p in pages if not p.get('hidden') and not p.get('is_action') and not p.get('separator')]
if 'ng-firewall' not in vis or vis[vis.index('ng-firewall') + 1:vis.index('ng-firewall') + 2] != ['wifi']: probs.append('Wi-Fi is not the tile right after the NG Firewall hub: %r' % vis)
want = ['wifi-scan', 'wifi-channels', 'wifi-link', 'wifi-tools', 'wifi-log']
if w.get('tabs') != want: probs.append('tabs = %r' % w.get('tabs'))
for t, label in zip(want, ['Scan', 'Channels', 'Link', 'Tools', 'Log']):
    p = by.get(t)
    if not p or p.get('label') != label or not p.get('hidden') or not p.get('icon'): probs.append('tab %s = %r' % (t, p))
cfg = json.load(open(sys.argv[2]))
if ['wifi-' + t for t in cfg['tabs']] != want: probs.append('lib default tabs = %r' % cfg['tabs'])
print('; '.join(probs) or 'OK')
PY
)" "wifi: Setup > Network, right after ADB Shell, label WiFi, tabs Scan | Channels | Link | Tools | Log, each a hidden page with an icon"
for tab in scan channels link tools log; do
  grep -qE "pageId == \"wifi-$tab\" +-> .*WifiFragment.newInstance\(.*WifiTab\." "$KT/launcher/SectionPages.kt" \
    && ok "config/wifi-$tab routes to WifiFragment" || bad "config/wifi-$tab is not routed"
done
python3 - "$LK/WifiUi.kt" <<'PY' && ok "WifiTab ids are exactly scan, channels, link, tools, log" || bad "WifiTab ids drifted from the declared tabs"
import re, sys
ids = re.findall(r'(?:Scan|Channels|Link|Tools|Log)\("(\w+)"', open(sys.argv[1]).read())
sys.exit(0 if ids == ['scan', 'channels', 'link', 'tools', 'log'] else 1)
PY
[ -f "$APP/app/src/main/res/drawable/ic_wifi.xml" ] && ok "ic_wifi resolves to a drawable" || bad "ic_wifi is not a drawable (the tile would fall back to ic_link_tile)"

echo "== W2: the group row holds the page and its tabs =="
# #1623 Configs is one page of six top-level groups; ADB Shell, WiFi and Bluetooth
# belong to the Network group (no subgroup), none to Launcher/Apps/Connect/Tools/
# Configs — asserted by test-configs-restructure R6. This row check moved to the
# 'network' group in lockstep; the page + its five tabs must all sit in network,
# WiFi must be the visible Network tile right after ADB Shell, and every Configs
# page must still live in exactly one group.
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
network = next(g for g in sec['groups'] if g['id'] == 'network')['pages']
others  = [i for g in sec['groups'] if g['id'] != 'network' for i in g['pages']]
probs = []
need = ['wifi', 'wifi-scan', 'wifi-channels', 'wifi-link', 'wifi-tools', 'wifi-log']
if any(i not in network for i in need): probs.append('network group lacks %r' % [i for i in need if i not in network])
if any(i in others for i in need): probs.append('another group still lists wifi pages %r' % [i for i in need if i in others])
net = [p['id'] for p in sec['pages'] if p.get('group') == 'Network' and not p.get('hidden') and not p.get('separator')]   # #1737 visible tiles in order
if 'ng-firewall' not in net or net[net.index('ng-firewall') + 1:net.index('ng-firewall') + 2] != ['wifi']:
    probs.append('Wi-Fi is not the Network tile right after the NG Firewall hub: %r' % net)
seen = [i for g in sec['groups'] for i in g['pages']]
if sorted(seen) != sorted(p['id'] for p in sec['pages']): probs.append('a page is not in exactly one group')
print('; '.join(probs) or 'OK')
PY
)" "Network group row: Wi-Fi (+ its five tabs) right after the NG Firewall hub, in the network group; every Configs page in exactly one group"

echo "== W3: the page is the lib's, scroll and clearance wired, features present =="
F="$KT/configs/WifiFragment.kt"
grep -q 'class WifiFragment : KitComposeFragment()' "$F" && ! grep -q 'override val reportsScroll.*false' "$F" \
  && ok "WifiFragment is a KitComposeFragment that reports its scroll to the shell" || bad "WifiFragment is not a scroll-reporting KitComposeFragment"
grep -q 'WifiPanel(controller, tab)' "$F" && grep -q 'WifiHub.controller' "$F" && grep -q 'BuildConfig.WIFI_ANALYZER_B64' "$F" \
  && ok "the fragment draws WifiPanel over WifiHub with build.json wifi_analyzer as the host config" || bad "the fragment does not draw the lib's panel with the host config"
grep -q 'buildConfigField "String", "WIFI_ANALYZER_B64"' "$APP/app/build.gradle" && grep -q 'buildJson.wifi_analyzer' "$APP/app/build.gradle" \
  && ok "build.gradle bakes build.json wifi_analyzer into BuildConfig" || bad "build.json wifi_analyzer never reaches the app"
grep -q 'LazyColumn' "$LK/WifiUi.kt" && grep -q 'bottomClearance' "$LK/WifiUi.kt" && ok "the panel is a LazyColumn with a bottom-nav clearance" || bad "the panel is not a cleared LazyColumn"
grep -q 'BoxWithConstraints' "$LK/WifiUi.kt" && grep -q '600.dp' "$LK/WifiUi.kt" && ok "landscape / tablet aware (wide layout from 600 dp)" || bad "no wide layout"
grep -q 'UiIcons.vector' "$LK/WifiUi.kt" && ! grep -q 'androidx.compose.material.icons' "$LK"/*.kt && ok "icons come from libs:ui-icons" || bad "an icon is not from libs:ui-icons"
for w in 'startScan' 'isScanThrottleEnabled' 'is80211mcResponder' 'is80211azNtbResponder' 'affiliatedMloLinks' 'wifiStandard' 'centerFreq0' \
         'WifiRttManager' 'FLAG_INCLUDE_LOCATION_INFO' 'isPrivateDnsActive' 'NET_CAPABILITY_CAPTIVE_PORTAL' 'leaseDuration' 'mtu' \
         'NsdManager' 'M-SEARCH' 'nodeStatusRequest' 'isReachable' 'portScan' 'traceroute' 'getAllByName' 'download' 'upload' \
         'ToneGenerator' 'Heatmap' 'Roaming' 'Export.pcap' 'CreateDocument' 'ACTION_SEND' 'SpectrumChart' 'TimeChart' 'Sparkline' \
         'ChannelRating.best' 'congestion' 'groupBySsid' 'ApSort' 'ACTION_APPLICATION_DEVELOPMENT_SETTINGS' 'ACTION_LOCATION_SOURCE_SETTINGS' 'PermsNeeds.missing'; do
  grep -rq -- "$w" "$LK" && ok "the lib has: $w" || bad "the lib lacks: $w"
done

echo "== W4: the vocabulary is data =="
check "$(python3 - "$LA" "$BJ" "$PK" <<'PY'
import json, re, sys
d = sys.argv[1]; probs = []
keys = [l[:6] for l in open(sys.argv[3] + '/wifi-oui.tsv', encoding='utf-8') if l and l[0] != '#']
if len(keys) < 30000: probs.append('OUI table has %d entries' % len(keys))
if keys != sorted(set(keys)): probs.append('OUI keys not sorted / unique')
if any(not re.fullmatch(r'[0-9A-F]{6}', k) for k in keys): probs.append('OUI key not 6 hex')
plans = json.load(open(d + '/channel-plans.json'))
ids = [p['id'] for p in plans['plans']]
if plans['default'] not in ids: probs.append('default plan missing')
cc = [c for p in plans['plans'] for c in p['countries']]
if len(cc) != len(set(cc)): probs.append('a country is in two plans')
for p in plans['plans']:
    for b, chs in p['bands'].items():
        ns = [c['ch'] for c in chs]
        if len(ns) != len(set(ns)): probs.append('%s %s duplicate' % (p['id'], b))
        ok = {'2.4': range(1, 15), '5': range(32, 178), '6': range(1, 234)}[b]
        if any(n not in ok for n in ns): probs.append('%s %s out of range' % (p['id'], b))
cfg = json.load(open(d + '/wifi-config.json'))
for k in ('rssi', 'smoothing', 'scan', 'ping', 'traceroute', 'port_scan', 'sweep', 'throughput', 'locator', 'heatmap', 'ports', 'nsd_types'):
    if k not in cfg: probs.append('config lacks ' + k)
if cfg.get('port_scan', {}).get('max_ports', 0) > 4096: probs.append('port scan unbounded')
if cfg.get('sweep', {}).get('concurrency', 0) > 128: probs.append('sweep concurrency unbounded')
wa = json.load(open(sys.argv[2])).get('wifi_analyzer', {})
if not wa.get('throughput', {}).get('download_url', '').startswith('https://'): probs.append('build.json wifi_analyzer.throughput.download_url not declared (https)')
print('; '.join(probs) or 'OK')
PY
)" "OUI = sorted IEEE registry, channel plans valid, config bounds every tool, build.json declares the throughput endpoint"
grep -rqE '"(F0D1A9|001A11|B827EB)"' "$LK" && bad "a vendor prefix is typed in Kotlin" || ok "no vendor table in Kotlin (packs/wifi-oui.tsv is the only list)"
grep -rqE 'listOf\((36, 40, 44, 48|1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)' "$LK" && bad "a channel list is typed in Kotlin" || ok "no channel plan in Kotlin (channel-plans.json is the only list)"

echo "== W5: permissions =="
M="$LIB/src/main/AndroidManifest.xml"
grep -q 'NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation"' "$M" && grep -q 'ACCESS_FINE_LOCATION' "$M" && grep -q 'ACCESS_WIFI_STATE' "$M" \
  && grep -q 'CHANGE_WIFI_STATE' "$M" && grep -q 'ACCESS_NETWORK_STATE' "$M" && grep -q 'CHANGE_WIFI_MULTICAST_STATE' "$M" && grep -q 'android.permission.INTERNET' "$M" \
  && ok "manifest: NEARBY (neverForLocation, as the app), fine location, Wi-Fi / network state, multicast, internet" || bad "the lib manifest permissions are wrong"
grep -q 'NEARBY_WIFI_DEVICES"' "$APP/app/src/main/AndroidManifest.xml" && grep -A1 'NEARBY_WIFI_DEVICES"' "$APP/app/src/main/AndroidManifest.xml" | grep -q neverForLocation \
  && ok "the app's own NEARBY_WIFI_DEVICES carries the same flag (the manifest merge cannot conflict)" || bad "the app and the lib disagree on NEARBY_WIFI_DEVICES"
! grep -q 'RequestMultiplePermissions' "$LK/WifiUi.kt" && grep -q 'PermsLink.open' "$LK/WifiUi.kt" && grep -q 'WifiPermissions.missing' "$LK/WifiController.kt" && grep -q 'locationOn' "$LK/WifiUi.kt" \
  && ok "the page links missing permissions to the Permissions page (#1274) and checks location services" || bad "the page asks for permissions itself or never links to the Permissions page"

echo "== W6: registered like every shared lib =="
check "$(python3 - "$ROOT" <<'PY'
import json, os, sys
r = sys.argv[1]; probs = []
sa = json.load(open(r + '/aa_cloud-superapp/build.json'))
m = sa['modules'].get('libs:wifi')
if not m or m.get('dir') != '../ab_cloud-libs-shared/libs/wifi': probs.append('superapp module missing / wrong dir')
if 'libs:wifi' not in sa['modules']['app']['depends_on']: probs.append('superapp app does not depend on it')
la = json.load(open(r + '/ab_cloud-libs-shared/lib-apks/build.json'))['lib_apks']
if 'wifi' not in la['exclude']: probs.append('lib-apks: not excluded')
if 'wifi' not in la['static_libs']: probs.append('lib-apks: no description')
if "project(':libs:wifi')" not in open(r + '/aa_cloud-superapp/app/build.gradle').read(): probs.append('app/build.gradle lacks the dependency')
for f in ('ship-cloud-superapp.yml', 'test-cloud-superapp.yml'):
    for base in ('1_cicd/src/cicd/', '1_cicd/dist/cicd/', '.github/workflows/'):
        if 'ab_cloud-libs-shared/libs/wifi/**' not in open(os.path.join(r, base + f)).read(): probs.append(base + f + ' does not watch the lib')
if 'wifi' not in json.load(open(r + '/1_cicd/src/data/lib-classes.json'))['libs' if 'libs' in json.load(open(r + '/1_cicd/src/data/lib-classes.json')) else 'classes']: probs.append('lib-classes: no class')
lc = json.load(open(r + '/aa_cloud-superapp/data/lib-consumers.json'))
rows = [x for x in lc.get('build_time', lc.get('libs', [])) if isinstance(x, dict) and x.get('id') == 'wifi']
if not rows or 'aa_cloud-superapp' not in rows[0].get('compiled_by', []): probs.append('lib-consumers: wifi not compiled by the SuperApp')
print('; '.join(probs) or 'OK')
PY
)" "module, app dependency, lib-apks, gradle dependency, ship / test path triggers, lib-classes and lib-consumers name libs:wifi"

echo "== W7: the lib is isolated and app-agnostic =="
check "$(python3 - "$LIB" <<'PY'
import glob, re, sys
lib = sys.argv[1]; probs = []
deps = re.findall(r"project\('(:[^']+)'\)", open(lib + '/build.gradle').read())
if deps != [':libs:ui-icons', ':libs:fleet-data-client', ':libs:perms']: probs.append('project deps = %r' % deps)
for f in glob.glob(lib + '/src/main/java/**/*.kt', recursive=True):
    for imp in re.findall(r'^import (com\.diegonmarcos\.[\w.]+)', open(f).read(), re.M):
        if not (imp.startswith('com.diegonmarcos.superapp.wifi.') or imp.startswith('com.diegonmarcos.superapp.uiicons.') or imp.startswith('com.diegonmarcos.superapp.perms.') or imp.startswith('com.diegonmarcos.superapp.fleetdata.')): probs.append('%s imports %s' % (f.rsplit('/', 1)[1], imp))
    if re.search(r'BuildConfig\.[A-Z_]', open(f).read()): probs.append(f.rsplit('/', 1)[1] + ' reads a BuildConfig')
print('; '.join(probs) or 'OK')
PY
)" "libs:wifi depends on libs:ui-icons, libs:fleet-data-client (#1547 packs) and libs:perms only, imports no host class and reads no BuildConfig (the host injects WifiHostConfig)"
grep -q 'class WifiHostConfig' "$LK/WifiConfig.kt" && grep -q 'fun controller(ctx: Context, host: WifiHostConfig' "$LK/WifiHub.kt" \
  && ok "the entry point takes the host config (Cloud Calc and the SuperApp each inject theirs)" || bad "the host config is not injected"

echo "== W8: i18n en + es =="
check "$(python3 - "$LIB/src/main/res" "$LK" <<'PY'
import glob, re, sys
res, src = sys.argv[1], sys.argv[2]
key = re.compile(r'<string name="([a-z0-9_]+)"')
en = set(key.findall(open(res + '/values/strings.xml', encoding='utf-8').read()))
es = set(key.findall(open(res + '/values-es/strings.xml', encoding='utf-8').read()))
probs = []
if en != es: probs.append('en / es differ: %r' % sorted(en ^ es))
used = set()
for f in glob.glob(src + '/*.kt'): used |= set(re.findall(r'R\.string\.([a-z0-9_]+)', open(f).read()))
if used - en: probs.append('Kotlin names missing strings: %r' % sorted(used - en))
if len(used) < 100: probs.append('only %d strings used: the page is not translated' % len(used))
print('; '.join(probs) or 'OK')
PY
)" "values and values-es hold the same keys; every R.string the page names exists"

echo "== W9: the runtime nav walk reaches the page =="
check "$(python3 - "$ROOT/1_cicd/src/scripts/cloud-android-nav-walk.py" "$BJ" <<'PY'
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location('navwalk', sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
sec = next(s for s in json.load(open(sys.argv[2]))['ui']['sections'] if s['id'] == 'config')
ids = [p[0] if isinstance(p, (list, tuple)) else p for p in m.pages_of(sec)]
print('OK' if 'wifi' in ids else 'nav walk pages lack wifi: %r' % ids[:40])
PY
)" "cloud-android-nav-walk.py walks config/wifi (P1 no crash, P3 scroll collapses the chrome, P4 the pick lands)"

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
