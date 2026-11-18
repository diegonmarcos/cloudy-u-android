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
] + [M + '/views', M + '/components/app-tabs', M + '/components/navigation/virtual-scroller']
for g in gone:
    check(not os.path.exists(os.path.join(root, g)), 'removed: ' + g)

# 2. the workbench declares only the vault and the 404 fallback
router = rd(M, 'workbench-router.ts')
paths = re.findall(r"path:\s*'([^']*)'", router)
check(paths == ['/vault', '*'], 'workbench routes are exactly /vault and * (found %s)' % paths)
for old in ('home', 'all', 'boards', 'journals', 'collection', 'tag', 'trash', 'search', 'graph', ':pageId'):
    check(("'/" + old) not in router and ("'/:" + old) not in router, 'no route /' + old)

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
        if re.search(r"mobile/(views|pages/workspace/(detail|collection|tag))|mobile/components/app-tabs", t):
            bad.append(os.path.relpath(os.path.join(d, f), root))
check(not bad, 'no import of a removed screen folder ' + ', '.join(bad))
print('FAIL' if fails else 'OK')
sys.exit(1 if fails else 0)
PY
