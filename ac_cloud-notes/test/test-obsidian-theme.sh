#!/usr/bin/env bash
# ac_cloud-notes tester (#1369): Cloud Notes wears Obsidian's UI. No build, no device. Three claims, each assertion
# proven red by its own mutation (a copy of the file under test is mutated in a temp dir; the real tree is never touched):
#   T  token mapping  the generated sheet and native tokens equal data/obsidian-theme.json, carry Obsidian's reference values
#                     (docs.obsidian.md CSS variables), and every AFFiNE variable the app paints with points at its Obsidian name
#   A  actions        build.json declares nav_ui.actions = drawer, the lib draws the drawer's AppActions row from every launch
#                     page and filters launch pages out of the strips; the bottom nav holds sections only
#   S  theme switch   light and dark blocks both exist; the web half's resolved theme reaches the native shell, which picks
#                     Obsidian light or dark (no fixed dark)
#   V  vault          the vault stylesheet has no literal colour (all Obsidian variables); narrow screens keep the ribbon in the drawer
#   W H K G           URL.canParse polyfilled first (WebView 113); Clean's declared history bar; the Obsidian keyboard toolbar;
#                     the vault scene grants all-files access by uid
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
exec python3 - "$ROOT" "$REPO" <<'PY'
import json, os, re, shutil, subprocess, sys, tempfile

ROOT, REPO = sys.argv[1], sys.argv[2]
GEN = os.path.join(ROOT, 'scripts', 'gen-obsidian-theme.py')
F = {
    'data': (ROOT, 'data/obsidian-theme.json'),
    'css': (ROOT, 'packages/frontend/component/src/theme/obsidian.gen.css'),
    'kt': (ROOT, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/theme/ObsidianTokens.kt'),
    'index': (ROOT, 'packages/frontend/component/src/theme/index.ts'),
    'build': (ROOT, 'build.json'),
    'navui': (REPO, 'ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/NavUi.kt'),
    'drawer': (REPO, 'ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavCleanDrawer.kt'),
    'scaffold': (REPO, 'ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavBarTopFirstRow.kt'),
    'strip': (REPO, 'ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt'),
    'activity': (ROOT, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/MainActivity.kt'),
    'shell': (ROOT, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/NotesShell.kt'),
    'theme': (ROOT, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/theme/Theme.kt'),
    'colors': (ROOT, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/theme/Colors.kt'),
    'webtheme': (ROOT, 'packages/frontend/apps/android/src/app.tsx'),
    'vstyle': (ROOT, 'packages/frontend/core/src/mobile/vault/style.ts'),
    'vshell': (ROOT, 'packages/frontend/core/src/mobile/vault/VaultShell.tsx'),
    'icons': (ROOT, 'packages/frontend/core/src/mobile/vault/icons.tsx'),
    'polyfill': (ROOT, 'packages/frontend/core/src/bootstrap/polyfill/browser.ts'),
    'urlpoly': (ROOT, 'packages/frontend/core/src/bootstrap/polyfill/url.ts'),
    'mcss': (ROOT, 'packages/frontend/core/src/mobile/styles/mobile.css.ts'),
    'walk': (REPO, '1_cicd/src/data/nav-walk-apps.json'),
}
path = lambda k: os.path.join(*F[k])

# Obsidian's reference values (docs.obsidian.md > CSS variables > Foundations; app.css 1.x semantic mapping). The spec, not the data.
REF = {
    'light': {'background-primary': '#ffffff', 'background-secondary': '#f6f6f6', 'text-normal': '#222222', 'text-muted': '#5c5c5c',
              'text-faint': '#ababab', 'interactive-accent': '#8a5cf5', 'background-modifier-border': '#e4e4e4', 'text-accent': '#8a5cf5',
              'color-red': '#e93147', 'color-blue': '#086ddd'},
    'dark': {'background-primary': '#1c1c1c', 'background-secondary': '#282828', 'text-normal': '#dadada', 'text-muted': '#b3b3b3',
             'text-faint': '#666666', 'interactive-accent': '#8a5cf5', 'background-modifier-border': '#333333', 'text-accent': '#a68af9',
             'color-red': '#fb464c', 'color-blue': '#027aff'},
}
# AFFiNE variables the editor, canvas and chrome paint with -> the Obsidian name they must wear.
AFFINE = {
    'affine-v2-layer-background-primary': 'background-primary', 'affine-v2-layer-background-secondary': 'background-secondary',
    'affine-v2-text-primary': 'text-normal', 'affine-v2-text-secondary': 'text-muted', 'affine-v2-text-link': 'text-accent',
    'affine-v2-icon-activated': 'interactive-accent', 'affine-v2-button-primary': 'interactive-accent',
    'affine-v2-layer-insideBorder-border': 'background-modifier-border', 'affine-background-primary-color': 'background-primary',
    'affine-text-primary-color': 'text-normal', 'affine-primary-color': 'interactive-accent', 'affine-border-color': 'background-modifier-border',
    'affine-font-family': 'font-interface', 'affine-v2-layer-background-mobile-primary': 'background-primary',
    'affine-v2-tab-divider-indicator': 'interactive-accent', 'affine-brand-color': 'interactive-accent',
}

def blocks(css):
    out = {}
    for sel, body in re.findall(r'([^{}]+)\{([^{}]*)\}', css):
        mode = 'dark' if 'data-theme=dark' in sel else 'light' if ('data-theme=light' in sel or ':root' in sel) else None
        if mode:
            out.setdefault(mode, {}).update(dict(re.findall(r'--([\w-]+):\s*([^;]+);', body)))
    return out

def resolve(vars_, name, mode_vars):
    v = mode_vars.get(name)
    for _ in range(5):
        m = re.fullmatch(r'var\(--([\w-]+)\)', (v or '').strip())
        if not m: break
        v = mode_vars.get(m.group(1))
    return (v or '').strip().lower()

def chk_T_data_matches_generated(p):
    r = subprocess.run([sys.executable, '-I', GEN, '--check', '--src', p['data']], capture_output=True, text=True,
                       env={**os.environ}, cwd=ROOT) if p['data'] != path('data') else subprocess.run([sys.executable, '-I', GEN, '--check'], capture_output=True, text=True)
    if p['data'] != path('data'):
        # mutated data: regenerate to memory and compare with the committed outputs
        sys.path.insert(0, os.path.dirname(GEN))
        import importlib.util
        spec = importlib.util.spec_from_file_location('g', GEN); g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
        t = g.Theme(json.load(open(p['data'])))
        return g.gen_css(t) == open(p['css']).read() and g.gen_kt(t) == open(p['kt']).read()
    return r.returncode == 0

def chk_T_reference_values(p):
    b = blocks(open(p['css']).read())
    for mode, want in REF.items():
        mv = {**b.get('light', {}), **b.get(mode, {})} if mode == 'dark' else b.get('light', {})
        for k, v in want.items():
            if resolve(None, k, mv) != v:
                print('    %s %s = %r, Obsidian says %s' % (mode, k, resolve(None, k, mv), v)); return False
    return True

def chk_T_affine_mapping(p):
    b = blocks(open(p['css']).read())
    for mode in ('light', 'dark'):
        mv = {**b.get('light', {}), **b.get(mode, {})}
        for a, o in AFFINE.items():
            for name in (a, 'cloud-notes-' + a[len('affine-'):]):  # the fork's components read the cloud-notes-* names
                if mv.get(name, '').strip() != 'var(--%s)' % o:
                    print('    %s --%s is %r, want var(--%s)' % (mode, name, mv.get(name), o)); return False
    return True

def chk_T_sheet_loaded_last(p):
    lines = [l.strip() for l in open(p['index']) if l.strip().startswith('import')]
    return bool(lines) and lines[-1] == "import './obsidian.gen.css';"

def chk_T_native_tokens(p):
    kt = open(p['kt']).read()
    return ('val backgroundPrimary = Color(0xFF1C1C1C)' in kt and 'val backgroundPrimary = Color(0xFFFFFFFF)' in kt
            and kt.count('val interactiveAccent = Color(0xFF8A5CF5)') == 2 and 'backgroundPrimary = ObsidianTokens.Dark.backgroundPrimary' in kt)

def chk_T_fab_accent(p):
    a = open(p['activity']).read()
    return 'ObsidianTokens.Light.interactiveAccent.toArgb()' in a and 'R.color.cloud_notes_primary' not in a

def chk_T_old_palette_gone(p):
    return 'object CloudNotesColorTokens' not in open(p['colors']).read()

def chk_A_declared(p):
    ui = json.load(open(p['build']))['ui']
    launches = [(s['id'], pg['id']) for s in ui['sections'] for pg in s.get('pages', []) if pg.get('action')]
    sections = {s['id'] for s in ui['sections']}
    return ui.get('nav_ui', {}).get('actions') == 'drawer' and bool(launches) and all(b in sections for b in ui['bottom_nav'])

def chk_A_bottom_has_no_action(p):
    ui = json.load(open(p['build']))['ui']
    launch_ids = {pg['id'] for s in ui['sections'] for pg in s.get('pages', []) if pg.get('action')}
    return not any(b in launch_ids or b == 'actions' for b in ui['bottom_nav'])

def chk_A_lib_model(p):
    kt = open(p['navui']).read()
    return ('AppActions("app_actions")' in kt and 'pages.filter { it.action.isBlank() }' in kt
            and 'if (p.action.isNotBlank()) actions += CleanDrawerEntry(appActionId(sectionId, p.id)' in kt
            and re.search(r'CLEAN_DRAWER_ROWS_WITH_ACTIONS[^=]*=\s*listOf\(DrawerRow\.PageDropdown, DrawerRow\.Actions, DrawerRow\.AppActions', kt)
            and 'actions = NavActionsPlace.of(o["actions"] as? String)' in kt)

def chk_A_drawer_draws_row(p):
    d = open(p['drawer']).read(); sc = open(p['scaffold']).read(); st = open(p['strip']).read()
    return ('DrawerRow.AppActions ->' in d and 'onAppAction(e)' in d and 'for (e in model.appActions)' in d
            and 'actionsInDrawer = ui.actionsInDrawer' in sc and 'onAppAction = {' in sc
            and 'val shown = NavUiDecl.stripPages(currentNavUi(), pages)' in st and 'shown.forEachIndexed' in st)

def chk_S_both_blocks(p):
    b = blocks(open(p['css']).read())
    d, l = b.get('dark', {}).get('background-primary'), b.get('light', {}).get('background-primary')
    return bool(d) and bool(l) and d != l and 'color-scheme' in open(p['css']).read()

def chk_S_web_reports(p):
    return re.search(r"CloudNotesTheme\.onThemeChanged\(\{\s*darkMode: resolvedTheme === 'dark'", open(p['webtheme']).read()) is not None

def chk_S_native_follows(p):
    a = open(p['activity']).read(); s = open(p['shell']).read(); t = open(p['theme']).read()
    return ('shell?.setDark(darkMode)' in a and 'this.shell = it' in a and 'val dark = darkState ?: isSystemInDarkTheme()' in s
            and 'val dark = true' not in s and 'CloudNotesTheme(if (dark) ThemeMode.Dark else ThemeMode.Light)' in s
            and 'if (dark) cloudNotesDarkScheme else cloudNotesLightScheme' in t)

def chk_V_no_literal_colour(p):
    css = open(p['vstyle']).read().split('VAULT_CSS = `', 1)[-1]
    return not re.search(r'#[0-9a-fA-F]{3,8}\b|rgba?\(\d', css) and 'var(--background-primary)' in css

def chk_V_ribbon_in_drawer(p):
    v = open(p['vshell']).read()
    return 'data-testid="vault-drawer-actions"' in v and "{!narrow && <nav className=\"cnv-ribbon\"" in v

def chk_V_lucide(p):
    return 'strokeWidth={1.75}' in open(p['icons']).read()

# ── #1369 follow-up: old WebViews, the history bar, the keyboard toolbar, the vault scene ──
read = lambda f, k: open(f[k], encoding='utf-8').read()

def chk_W_canparse(f):
    first = [l for l in read(f, 'polyfill').splitlines() if l.strip()][0]
    u = read(f, 'urlpoly')
    return first == "import './url';" and "typeof U.canParse !== 'function'" in u and "'canParse'" in u and "'parse'" in u

def chk_H_history_bar(f):
    nav, shell = read(f, 'navui'), read(f, 'shell')
    order = re.findall(r'^\s+(\w+)\("history_\w+"', nav, re.M)
    return ('"bottom": "history"' in read(f, 'build') and order == ['Back', 'Forward', 'New', 'Tabs', 'Menu']
            and 'bottom == NavBottomKind.History && mode == NavUiMode.Clean' in nav
            and 'entries = if (history) historyEntries else sectionEntries' in shell and 'HistoryBarItem.Menu -> topBar.openDrawer()' in shell)

def chk_K_keyboard_toolbar(f):
    c = read(f, 'mcss')
    return ("globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar', {\n  backgroundColor: 'var(--background-secondary)'" in c
            and "boxShadow: 'none'" in c)

def chk_G_vault_scene(f):
    w = read(f, 'walk')
    return 'appops set --uid {pkg} MANAGE_EXTERNAL_STORAGE allow' in w and '"name": "editor-toolbar"' in w

CHECKS = [
    ('T1 generated sheet and tokens equal data/obsidian-theme.json', chk_T_data_matches_generated,
     [('data', '"accent": "#8a5cf5"', '"accent": "#8a5cf6"')]),
    ('T2 Obsidian reference values, light and dark', chk_T_reference_values,
     [('css', '--background-primary: #1c1c1c;', '--background-primary: #1e1f22;'), ('css', '--text-normal: #222222;', '--text-normal: #000000;')]),
    ('T3 AFFiNE variables point at Obsidian names', chk_T_affine_mapping,
     [('css', '--affine-v2-text-primary: var(--text-normal);', '--affine-v2-text-primary: var(--text-muted);'),
      ('css', '--cloud-notes-v2-button-primary: var(--interactive-accent);', '--cloud-notes-v2-button-primary: #1e96eb;')]),
    ('T4 the Obsidian sheet loads after AFFiNE\'s', chk_T_sheet_loaded_last, [('index', "import './obsidian.gen.css';\n", '')]),
    ('T5 native tokens carry Obsidian colours', chk_T_native_tokens, [('kt', 'val interactiveAccent = Color(0xFF8A5CF5)', 'val interactiveAccent = Color(0xFF1E96FC)')]),
    ('T7 the native create button wears the Obsidian accent', chk_T_fab_accent,
     [('activity', 'ObsidianTokens.Light.interactiveAccent.toArgb()', 'ContextCompat.getColor(context, R.color.cloud_notes_primary)')]),
    ('T6 the old native palette is removed', chk_T_old_palette_gone, [('colors', 'val LocalCloudNotesColors', 'object CloudNotesColorTokens {}\nval LocalCloudNotesColors')]),
    ('A1 Cloud Notes declares its actions in the drawer', chk_A_declared, [('build', '"actions": "drawer"', '"actions": "strip"')]),
    ('A2 the bottom nav holds sections, no action', chk_A_bottom_has_no_action, [('build', '"bottom_nav": [', '"bottom_nav": ["new", ')]),
    ('A3 the lib collects launch pages into AppActions and off the strips', chk_A_lib_model,
     [('navui', 'pages.filter { it.action.isBlank() }', 'pages.filter { true }'),
      ('navui', 'DrawerRow.Actions, DrawerRow.AppActions, DrawerRow.SubPageDropdown', 'DrawerRow.Actions, DrawerRow.SubPageDropdown')]),
    ('A4 the drawer draws the Actions row; the strip filters', chk_A_drawer_draws_row,
     [('drawer', 'for (e in model.appActions)', 'for (e in emptyList<CleanDrawerEntry>())'),
      ('strip', 'val shown = NavUiDecl.stripPages(currentNavUi(), pages)', 'val shown = pages')]),
    ('S1 light and dark blocks differ', chk_S_both_blocks, [('css', '[data-theme=dark] {', '[data-theme=dim] {')]),
    ('S2 the web half reports its resolved theme', chk_S_web_reports, [('webtheme', "darkMode: resolvedTheme === 'dark'", 'darkMode: true')]),
    ('S3 the native shell follows it', chk_S_native_follows,
     [('activity', 'shell?.setDark(darkMode)', '/* ignored */'), ('shell', 'val dark = darkState ?: isSystemInDarkTheme()', 'val dark = true')]),
    ('V1 the vault stylesheet has no literal colour', chk_V_no_literal_colour, [('vstyle', 'var(--background-primary)', '#1f2023')]),
    ('V2 narrow screens keep the ribbon in the drawer', chk_V_ribbon_in_drawer, [('vshell', '{!narrow && <nav className="cnv-ribbon"', '{<nav className="cnv-ribbon"')]),
    ('V3 Lucide stroke', chk_V_lucide, [('icons', 'strokeWidth={1.75}', 'strokeWidth={1.8}')]),
    ('W1 URL.canParse is polyfilled first for WebViews before Chrome 120', chk_W_canparse,
     [('polyfill', "import './url';\n", ''), ('urlpoly', "typeof U.canParse !== 'function'", 'false')]),
    ('H1 Clean wears the declared history bar (Back, Forward, New, Tabs, Menu)', chk_H_history_bar,
     [('build', '"bottom": "history"', '"bottom": "sections"'), ('navui', 'Forward("history_forward"', 'Fwd("history_forward"'),
      ('shell', 'entries = if (history) historyEntries else sectionEntries', 'entries = sectionEntries')]),
    ('K1 the keyboard toolbar wears Obsidian\'s toolbar', chk_K_keyboard_toolbar, [('mcss', "boxShadow: 'none'", "boxShadow: '0 -4px 10px #0001'")]),
    ('G1 the vault scene grants all-files access by uid', chk_G_vault_scene,
     [('walk', 'appops set --uid {pkg}', 'appops set {pkg}')]),
]

real = {k: path(k) for k in F}
fails = 0; proven = 0; unproven = 0
for name, fn, muts in CHECKS:
    ok = bool(fn(real))
    print(('  PASS  ' if ok else '  FAIL  ') + name)
    fails += not ok
    for key, a, b in muts:
        tmp = tempfile.mkdtemp(prefix='obsidian-theme-')
        try:
            src = open(real[key], encoding='utf-8').read()
            if a not in src:
                print('  UNPROVEN  %s: mutation needle %r not in %s' % (name, a, F[key][1])); unproven += 1; continue
            mp = os.path.join(tmp, os.path.basename(real[key]))
            open(mp, 'w', encoding='utf-8').write(src.replace(a, b, 1))
            red = not fn({**real, key: mp})
            print(('    red    ' if red else '    GREEN  ') + 'mutation %s: %r -> %r' % (F[key][1].rsplit('/', 1)[-1], a[:50], b[:50]))
            if red: proven += 1
            else: unproven += 1
        finally:
            shutil.rmtree(tmp)
print('obsidian theme tester: %d assertion(s) failed, %d mutation(s) proven red, %d not proven' % (fails, proven, unproven))
sys.exit(1 if fails or unproven else 0)
PY
