#!/usr/bin/env bash
# ac_cloud-notes tester: the fleet's shared nav scaffold (#1164), and ONE source for the insets.
#
# Cloud Notes is a Capacitor/WebView app. Its NotesShell is the activity's content: libs:ui-nav-bars's
# scaffold (Profile top-right launches Cloud Account), libs:ui-nav-bars, libs:ui-nav-bars and
# libs:theme-background, with the editor WebView as the content and its scroll fed to the ONE chrome collapse
# (ChromeWebScroll). Three halves must agree, none of which a compiler can see across:
#
#   declaration  build.json::ui (+ modules) <-> app/build.gradle bakes UI_SECTIONS_B64 / UI_BOTTOM_NAV /
#                AI_GATEWAY_URL and links the shared nav libs and :libs:ai
#   wiring       MainActivity makes NotesShell the content and registers the CloudNotesHost bridge;
#                NotesShell draws the shared scaffold with the Account launcher, the AI panel and a
#                Configs handler that asks the web half to open Cloud Notes's settings
#   bridge       the Kotlin event names / host object == the TypeScript ones
#   insets       the shell owns the status bar and the navigation bar: no Kotlin writes a margin to the
#                WebView, and the web half zeroes --safe-area-inset-top/bottom when native owns them
#
# python3 only; paths below are the only constants.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()

APP = 'packages/frontend/apps/android/App/app'
KT = APP + '/src/main/java/app/cloudnotes/pro'
WEB = 'packages/frontend/core/src/mobile/components'
build = json.loads(rd('build.json'))
ui = build.get('ui') or {}
gradle = rd(APP, 'build.gradle')
main = rd(KT, 'MainActivity.kt')
shell = rd(KT, 'NotesShell.kt')
nav = rd(KT, 'NativeNav.kt')
routes = rd(WEB, 'native-nav-routes.ts')
bridge = rd(WEB, 'native-nav-bridge.tsx')
setup = rd('packages/frontend/apps/android/src/setup.ts')

# declaration
check('top_bar' not in ui, 'ui declares no top_bar of its own (the shared first row is fixed)')
mods = build.get('modules') or {}
check(all(m in mods for m in ('libs:bottomnav', 'libs:ui-nav-bars', 'libs:ui-nav-bars', 'libs:ui-nav-bars', 'libs:theme-background', 'libs:ai', 'libs:model-catalogue')),
      'build.json::modules links the shared nav libs, libs:ai and its api dependency libs:model-catalogue')
check('"UI_SECTIONS_B64"' in gradle and '"UI_BOTTOM_NAV"' in gradle, 'app/build.gradle bakes the nav declaration')
check('"AI_GATEWAY_URL"' in gradle and 'ai-gateway.gradle' in gradle, 'app/build.gradle bakes AI_GATEWAY_URL via the shared ai-gateway.gradle')
check(all(("project(':%s')" % m) in gradle for m in ('libs:ai', 'libs:ui-nav-bars', 'libs:ui-nav-bars', 'libs:ui-nav-bars', 'libs:theme-background')),
      'app/build.gradle implements :libs:ai and the shared nav libs')
check(re.search(r"ext\.aiFleetRoot\s*=", gradle) is not None, 'the nested gradle root names the repository root for ai-gateway.gradle')

# wiring
check('setContentView(' in main and 'NotesShell(' in main, 'MainActivity makes NotesShell the activity content')
check('addJavascriptInterface(' in main and '"CloudNotesHost"' in main, 'MainActivity registers the CloudNotesHost bridge object')
check('NavBarTopFirstRowScaffold(' in shell and 'rememberAccountLauncher()' in shell, 'NotesShell draws the shared first row with the Account launcher (Profile -> Cloud Account)')
check('NavBarTopSecondRow(' in shell and 'NavBarBottomHost(' in shell, 'NotesShell draws the shared second row and bottom nav')
check('ChromeWebScroll.attach(' in shell and 'ChromeWebScroll.inject(' in rd(KT, 'CloudNotesWebViewClient.kt'), 'the editor WebView feeds the chrome scroll-collapse')
check('AiFleetChatPanel(' in shell, "the AI panel is libs:ai's shared chat")
check('onConfigs = { fleetConfigsOpen = true }' in shell and 'FleetConfigsScreen(' in shell and 'onAppConfigs = { configsOpen = true }' in shell and 'NavUiConfigCard(' in shell and 'run(NativeNavScript.openSettings())' in shell,
      'Configs opens the fleet strip (#1697); its General > App opens Configs > UI (the shared nav card) with a way on to the web half\'s own Cloud Notes settings')
check(ui.get('nav_ui', {}).get('mode') == 'clean' and 'nav_ui' in (ui.get('configs_ui') or []),
      'build.json::ui.nav_ui declares Clean as the default nav mode and lists nav_ui in configs_ui')
check('"UI_NAV_UI_B64"' in gradle and 'UI_NAV_UI_B64' in shell and 'LocalNavUiDefault provides' in shell,
      'app/build.gradle bakes ui.nav_ui and NotesShell provides it as the nav default')
check('TopBarDrawer {' in shell, 'the drawer is filled through TopBarDrawer')

# bridge
for const, kt in (('NATIVE_NAV_EVENT', 'EVENT'), ('NATIVE_OPEN_SETTINGS_EVENT', 'OPEN_SETTINGS_EVENT'), ('NATIVE_OPEN_DOC_EVENT', 'OPEN_DOC_EVENT')):
    ts = re.search(const + r"\s*=\s*'([^']+)'", routes)
    k = re.search(r'const val ' + kt + r' = "([^"]+)"', nav)
    check(bool(ts and k and ts.group(1) == k.group(1)), '%s: web and Kotlin use the same event name (%s)' % (const, ts.group(1) if ts else '?'))
check('CloudNotesHost' in bridge and 'setDocs' in bridge, 'the web half pushes the page list to CloudNotesHost.setDocs')
check(re.search(r'@JavascriptInterface\s+fun setDocs\(', shell) is not None, 'Kotlin exposes setDocs as a @JavascriptInterface')

# insets: one source
margin_writers = []
for dp, _, fs in os.walk(os.path.join(root, KT)):
    for f in fs:
        if f.endswith('.kt') and re.search(r'\.topMargin\s*=', open(os.path.join(dp, f), encoding='utf-8').read()):
            margin_writers.append(f)
check(margin_writers == [], 'no Kotlin writes a top margin to the WebView (the scaffold lays it out; found: %s)' % margin_writers)
check('statusBarsPadding()' in shell, 'the shell owns the status bar inset')
check('--safe-area-inset-top:0px !important' in setup and '--safe-area-inset-bottom:0px !important' in setup, 'the web half zeroes the status-bar and navigation-bar safe areas once native owns them')
check(re.search(r'cloudNotesNativeNav\s*=\s*\n?\s*true', setup) is not None, 'the zeroing sits with the native-nav flag')

# Search > Ask is a page the shell draws itself (libs:ai), never a toast
rows = dict(re.findall(r"'([\w-]+/[\w-]+)':\s*(\{[^}]*\})", routes))
web_native = {k for k, v in rows.items() if "kind: 'native'" in v}
kt_native = set(re.findall(r'"([\w-]+/[\w-]+)"', re.search(r'NATIVE_PAGES[^=]*=\s*setOf\(([^)]*)\)', nav).group(1)))
check(web_native == kt_native and bool(kt_native), 'native pages: web rows %s == Kotlin NATIVE_PAGES %s' % (sorted(web_native), sorted(kt_native)))
check(not re.search(r"kind: 'planned'|notify\.", bridge + routes), 'the web nav bridge has no planned-toast path left')
check('AiFleetChatPanel(gatewayUrl)' in shell and 'nav.askShown' in shell, "NotesShell shows libs:ai's chat for the native Ask page")
check("'graph/global': { kind: 'route'" in routes and "'graph/local': { kind: 'route'" in routes, 'Graph global and local are routes')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
