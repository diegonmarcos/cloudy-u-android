#!/usr/bin/env bash
# Tester (#1931): every Me-row tile of Cloud > Apps (ui.sections[cloud].tile_groups[Me]) opens a page Cloud Me really declares, in the section its tile names.
#   R1  each `extapp:cloud-me#page:<section>/<page>` target resolves against ac_cloud-me/build.json ui.sections (read only): the section exists and DECLARES the
#       page (any depth) - a target that only resolves through ui.renamed.pages is STALE (the #1931 root cause: Me-Prj kept proj/lnktree-site, which Cloud Me
#       aliases to lnktree/lt-lnktree-site, so it opened Lnktree instead of Proj).
#   R2  Me-Pers lands in pers, Me-Prof in prof, Me-Prj in proj (its Home subpage pj-home), Me-Invent. in tasks, Me-Buro in buro, Me-LLC in llc; the bare Lnktree tile
#       opens Cloud Me's default section lnktree.
#   R3  the icons of the Lnktree tile (id me) and the Me-Prj tile (id myroadmap) are swapped; tile ids unchanged.
# Each is then mutated and must turn red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy, re
root = sys.argv[1]
bj0 = json.load(open(root + '/aa_cloud-superapp/build.json'))
me0 = json.load(open(root + '/ac_cloud-me/build.json'))
WANT = {'myhealth': 'pers', 'myexperiences': 'prof', 'myroadmap': 'proj', 'myduties': 'tasks', 'myburo': 'buro', 'myllc': 'llc'}

def ids(node):
    out = set()
    for p in node.get('pages', []) or []:
        out.add(p['id']); out |= ids(p)
    return out

def check(n, bj, me):
    f = []
    g = [x for s in bj['ui']['sections'] if s['id'] == 'cloud' for x in s['tile_groups'] if x['title'] == 'Me'][0]['tiles']
    tiles = {t['id']: t for t in g if not t.get('separator')}
    secs = {s['id']: s for s in me['ui']['sections']}
    alias = me['ui']['renamed']['pages']
    if n in ('R1', 'R2'):
        for tid, t in tiles.items():
            tg = t.get('target', '')
            m = re.fullmatch(r'extapp:cloud-me#page:([\w-]+)/([\w-]+)', tg)
            if tid == 'me':
                if tg != 'extapp:cloud-me' or me['ui'].get('default_section') != 'lnktree' and n == 'R2': f.append('Lnktree tile target %r / default section %r' % (tg, me['ui'].get('default_section')))
                continue
            if not m: f.append('%s target %r is not a Cloud Me page link' % (tid, tg)); continue
            sec, page = m.groups()
            if n == 'R1':
                if sec not in secs: f.append('%s: Cloud Me has no section %s' % (tid, sec))
                elif page not in ids(secs[sec]):
                    via = alias.get('%s/%s' % (sec, page))
                    f.append('%s: %s/%s is not declared by Cloud Me%s' % (tid, sec, page, ' (STALE: it only resolves through the alias to %s)' % via if via else ''))
            elif WANT.get(tid) != sec: f.append('%s lands in section %s, want %s' % (tid, sec, WANT.get(tid)))
        if n == 'R2' and tiles['myroadmap']['target'] != 'extapp:cloud-me#page:proj/pj-home': f.append('Me-Prj is not on Proj Home: %s' % tiles['myroadmap']['target'])
    elif n == 'R3':
        if tiles['me']['icon'] != 'ic_tree' or tiles['myroadmap']['icon'] != 'ic_vcard': f.append('icons me=%s myroadmap=%s' % (tiles['me']['icon'], tiles['myroadmap']['icon']))
        if list(tiles) != ['me', 'myhealth', 'myexperiences', 'myroadmap', 'myduties', 'myburo', 'myllc']: f.append('tile ids %s' % list(tiles))
    return f

fails = 0
for n, t in (('R1', 'every Me-row target is declared by Cloud Me (none only through an alias)'), ('R2', 'each Me tile lands in its own Cloud Me section; Me-Prj on Proj Home'), ('R3', 'Lnktree and Me-Prj icons swapped, ids unchanged')):
    r = check(n, bj0, me0); print('  %s: %s %s' % ('PASS' if not r else 'FAIL', n, t + ('' if not r else ' -> ' + '; '.join(r)))); fails += bool(r)
print('== mutants: each must turn its check red ==')
tile = lambda bj, i: [t for s in bj['ui']['sections'] if s['id'] == 'cloud' for g in s['tile_groups'] if g['title'] == 'Me' for t in g['tiles'] if t.get('id') == i][0]
def mut(name, s, fn):
    global fails
    bj, me = copy.deepcopy(bj0), copy.deepcopy(me0); fn(bj, me)
    red = bool(check(s, bj, me)); print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
mut('Me-Prj back on the stale proj/lnktree-site', 'R1', lambda b, m: tile(b, 'myroadmap').__setitem__('target', 'extapp:cloud-me#page:proj/lnktree-site'))
mut('a target names a page that does not exist', 'R1', lambda b, m: tile(b, 'myburo').__setitem__('target', 'extapp:cloud-me#page:buro/nope'))
mut('Cloud Me drops the page a tile opens', 'R1', lambda b, m: [s['pages'].remove(p) for s in m['ui']['sections'] if s['id'] == 'llc' for p in list(s['pages']) if p['id'] == 'employed'])
mut('Me-Prj points at a page of another section', 'R2', lambda b, m: tile(b, 'myroadmap').__setitem__('target', 'extapp:cloud-me#page:lnktree/lt-lnktree-site'))
mut('Me-Pers points at Prof', 'R2', lambda b, m: tile(b, 'myhealth').__setitem__('target', 'extapp:cloud-me#page:prof/prof-web'))
mut('the Lnktree tile loses the default-section link', 'R2', lambda b, m: tile(b, 'me').__setitem__('target', 'extapp:cloud-wiki'))
mut('the icons are swapped back', 'R3', lambda b, m: (tile(b, 'me').__setitem__('icon', 'ic_vcard'), tile(b, 'myroadmap').__setitem__('icon', 'ic_tree')))
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
