#!/usr/bin/env bash
# Tester (#1930): the Cloud top strip reads App | WebApps | Setup. Linktree left the strip (explicit owner order) and Configs is renamed Setup (label only).
#   S1  ui.sections[cloud].pages visible tabs: ids apps, webapps, configs; labels App, WebApps, Setup; no page lnktree.
#   S2  nothing dangles: the old id lnktree is a page alias of apps (a page that exists), no target anywhere is page:cloud/lnktree, the Home Labs
#       entries open extapp:cloud-me (a declared external app) and the Me-row Lnktree tile still opens the same destination; the swipe walk has no lnktree stop.
#   S3  untouched: the Lnktree tile row (stack_lnktree + the group), the page id configs (mirror_section config) and the Phone section's own Configs page.
# Each is then mutated and must turn red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
bj0 = json.load(open(sys.argv[1] + '/aa_cloud-superapp/build.json'))
sec = lambda bj, i: [s for s in bj['ui']['sections'] if s['id'] == i][0]

def targets(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ('target', 'id', 'action') and isinstance(v, str): yield v
            yield from targets(v)
    elif isinstance(o, list):
        for v in o: yield from targets(v)

def check(n, bj):
    f = []; c = sec(bj, 'cloud'); ui = bj['ui']
    if n == 'S1':
        tabs = [p for p in c['pages'] if not p.get('hidden') and not p.get('placeholder')]
        if [p['id'] for p in tabs] != ['apps', 'webapps', 'configs']: f.append('tab ids %s' % [p['id'] for p in tabs])
        if [p['label'] for p in tabs] != ['App', 'WebApps', 'Setup']: f.append('tab labels %s' % [p['label'] for p in tabs])
        if any(p['id'] == 'lnktree' for p in c['pages']): f.append('the lnktree page is still declared')
    elif n == 'S2':
        al = c.get('page_aliases', {})
        if al.get('lnktree') != 'apps' or not any(p['id'] == 'apps' for p in c['pages']): f.append('lnktree is not aliased to an existing page: %r' % al)
        if any(t == 'page:cloud/lnktree' for t in targets(ui)): f.append('a target still names page:cloud/lnktree')
        ext = {e['id'] for e in ui['external_apps']}
        if 'cloud-me' not in ext: f.append('extapp:cloud-me is not a declared external app')
        labs = [t for t in targets(ui['home_groups']) if t == 'extapp:cloud-me']
        if len(labs) < 1: f.append('no Home Labs entry opens extapp:cloud-me')
        me = [t for g in c['tile_groups'] if g['title'] == 'Me' for t in g['tiles'] if t['label'] == 'Lnktree']
        if not me or me[0].get('target') != 'extapp:cloud-me': f.append('the Me-row Lnktree tile does not open extapp:cloud-me')
        if any(s.get('id') == 'lnktree' or s.get('page') == 'lnktree' for s in ui['swipe_walk']): f.append('the swipe walk still stops on lnktree')
    elif n == 'S3':
        if not c.get('stack_lnktree'): f.append('stack_lnktree is gone')
        if not any(g['title'] == 'Projects' for g in c['tile_groups']): f.append('the Projects group is gone')
        cf = [p for p in c['pages'] if p['id'] == 'configs']
        if not cf or cf[0].get('mirror_section') != 'config': f.append('the configs page lost its mirror_section')
        ph = [p for p in sec(bj, 'phone')['pages'] if p['id'] == 'configs']
        if not ph or ph[0]['label'] != 'Configs': f.append('the Phone Configs page changed: %r' % [p['label'] for p in ph])
    return f

fails = 0
for n, t in (('S1', 'Cloud strip is App | WebApps | Setup'), ('S2', 'former Lnktree targets still resolve'), ('S3', 'Lnktree tile row, page id configs and Phone Configs untouched')):
    r = check(n, bj0); print('  %s: %s %s' % ('PASS' if not r else 'FAIL', n, t + ('' if not r else ' -> ' + '; '.join(r)))); fails += bool(r)
print('== mutants: each must turn its surface red ==')
def mut(name, s, fn):
    global fails
    bj = copy.deepcopy(bj0); fn(bj)
    red = bool(check(s, bj)); print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
cpage = lambda bj, i: [p for p in sec(bj, 'cloud')['pages'] if p['id'] == i][0]
mut('the Setup tab reads Configs again', 'S1', lambda b: cpage(b, 'configs').__setitem__('label', 'Configs'))
mut('the Linktree page returns', 'S1', lambda b: sec(b, 'cloud')['pages'].insert(2, {'id': 'lnktree', 'label': 'Linktree'}))
mut('the lnktree alias is dropped', 'S2', lambda b: sec(b, 'cloud').pop('page_aliases'))
mut('a Home Labs entry targets the removed page', 'S2', lambda b: [t.__setitem__('target', 'page:cloud/lnktree') for g in b['ui']['home_groups'] for t in g.get('tiles', []) if t.get('target') == 'extapp:cloud-me'] or b['ui']['home_groups'][0]['tiles'].append({'target': 'page:cloud/lnktree'}))
mut('the swipe walk keeps a lnktree stop', 'S2', lambda b: b['ui']['swipe_walk'].append({'id': 'lnktree', 'section': 'cloud', 'page': 'lnktree'}))
mut('the Lnktree tile stack is dropped', 'S3', lambda b: sec(b, 'cloud').pop('stack_lnktree'))
mut('the Phone Configs page is renamed too', 'S3', lambda b: [p.__setitem__('label', 'Setup') for p in sec(b, 'phone')['pages'] if p['id'] == 'configs'])
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
