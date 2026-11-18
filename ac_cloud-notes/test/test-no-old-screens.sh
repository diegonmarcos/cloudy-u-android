#!/usr/bin/env bash
# ac_cloud-notes tester (#1717): the old Cloud Notes screens are gone and stay gone. Fails if any old screen id, route
# or source folder returns. Source-only check: no build, no device.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
M = 'packages/frontend/core/src/mobile'
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()

# 1. source folders and files of the old screens must not exist
gone = [
  M + '/pages/workspace/' + n for n in
  ('home.tsx', 'all.tsx', 'boards.tsx', 'journals.tsx', 'journals.css.ts', 'search.tsx', 'graph.tsx', 'trash.tsx',
   'collection', 'tag', 'detail')
] + [M + '/views', M + '/components/app-tabs', M + '/components/navigation/virtual-scroller', M + '/dialogs/setting']
for g in gone:
    check(not os.path.exists(os.path.join(root, g)), 'removed: ' + g)

# 1b. the old file-import explorer is gone (its helper, its component, its event); open-with stays
app = rd('packages/frontend/apps/android/src/app.tsx')
for gone_name in ('ExternalFileOpener', 'importVaultFolder', 'importExternalFile', 'openExternalFile', 'cloud-notes:open-file'):
    check(gone_name not in app, 'app.tsx has no ' + gone_name)
check(not os.path.exists(os.path.join(root, 'packages/frontend/apps/android/src/vault-import.ts')), 'removed: apps/android/src/vault-import.ts')
check('OpenWithListener' in app and 'cloud-notes:view-intent' in app, 'open-with is still wired (OpenWithListener, cloud-notes:view-intent)')

# 2. the workbench declares only the vault and the 404 fallback
router = rd(M, 'workbench-router.ts')
# the page routes are exactly /vault (the screen) and * (the 404 fallback); every removed path redirects to the vault
check("path: '/vault'" in router and "path: '*'" in router, 'the vault route and the 404 fallback are declared')
legacy = re.findall(r"^  '(/[^']*)',?$", router, re.M)
check('/' in legacy and '/home' in legacy and '/all' in legacy and '/:pageId' in legacy, 'the index and the removed pages redirect: %s' % legacy)
check("redirect('/vault')" in router and 'loader: toVault' in router, 'the redirect goes to /vault')
check('Component:' not in router.replace('Component: Vault', ''), 'no other page component is mounted')

def resolve(pattern, path):
    pp = [x for x in pattern.split('/') if x]; xx = [x for x in path.split('/') if x]
    if pattern == '/' : return path == '/'
    return len(pp) == len(xx) and all(a.startswith(':') or a == b for a, b in zip(pp, xx))

def route_for(path):
    # first match wins, in declaration order: /vault, the redirects, then *
    if resolve('/vault', path): return '/vault'
    for l in legacy:
        if resolve(l, path): return 'redirect:' + l
    return '*'

for p in ('/', '/home', '/all', '/boards', '/journals', '/trash', '/search', '/graph', '/graph/local', '/collection', '/collection/c1', '/tag', '/tag/t1', '/someDocId', '/vault'):
    check(route_for(p) != '*', 'launch / legacy path %s does not fall to the 404 (%s)' % (p, route_for(p)))
check(route_for('/a/b/c') == '*', 'a path that never was a route still reaches the 404 fallback')
# every native-nav target resolves to the vault
nav = rd(M, 'components/native-nav-routes.ts')
for t in re.findall(r"to:\s*'([^']*)'", nav):
    check(route_for(t) == '/vault', 'native nav target %s resolves to the vault' % t)

# 3. nothing routes to an old path any more
nav = rd(M, 'components/native-nav-routes.ts')
targets = re.findall(r"to:\s*'([^']*)'", nav[nav.index('NATIVE_NAV_TARGETS'):nav.index('NATIVE_NAV_FIRST_PAGE')])
check(targets and all(t == '/vault' for t in targets), 'every native nav route lands in /vault')

# 4. no source imports a removed folder
bad = []
for d, _, fs in os.walk(os.path.join(root, 'packages/frontend')):
    if 'node_modules' in d: continue
    for f in fs:
        if not f.endswith(('.ts', '.tsx', '.mts')): continue
        t = open(os.path.join(d, f), encoding='utf-8', errors='ignore').read()
        if re.search(r"mobile/(views|dialogs/setting|pages/workspace/(detail|collection|tag))|mobile/components/app-tabs", t):
            bad.append(os.path.relpath(os.path.join(d, f), root))
check(not bad, 'no import of a removed screen folder ' + ', '.join(bad))
print('FAIL' if fails else 'OK')
sys.exit(1 if fails else 0)
PY
