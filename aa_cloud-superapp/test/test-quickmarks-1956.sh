#!/usr/bin/env bash
# #1956 Phone > Quickmarks: runs the data checks of Quickmarks1956Test in plain python (no gradle) so the pins also run in the shell suite.
# Each check has a mutation of the data that must turn it red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
python3 -I - "$APP" <<'PY'
import json, sys, copy
A = sys.argv[1] + '/'
bj = json.load(open(A + 'build.json'))
before = json.load(open(A + 'test/data/phone-quickmarks-before-1956.json'))
pk = lambda p: p if isinstance(p, str) else p['pkg']
SOC = ['com.instagram.android', 'com.instagram.barcelona', 'com.facebook.katana', 'com.linkedin.android', 'com.pinterest', 'com.bereal.ft', 'com.tinder', 'com.bumble.app', 'co.hinge.app']
PV = 'com.amazon.avod.thirdpartyclient'
def view(d):
    ph = [s for s in d['ui']['sections'] if 'phone_app_groups' in s][0]['phone_app_groups']
    cl = [s for s in d['ui']['sections'] if s.get('id') == 'cloud'][0]['tile_groups']
    return ph, cl
def fo(ph, g, l): return [f for x in ph if x['title'] == g for f in x['folders'] if f['label'] == l][0]
def mem(ph, g, l): return [pk(p) for p in fo(ph, g, l)['packages']]
def lk(ph, g, l): return fo(ph, g, l).get('links', [])
def checks(d):
    ph, cl = view(d); r = {}
    allp = {pk(p) for g in ph for p in g.get('packages', [])} | {pk(p) for g in ph for f in g.get('folders', []) for p in f['packages']}
    labels = {g['title'] + '/' + f['label'] for g in ph for f in g.get('folders', [])}
    r['parity'] = all(p in allp or p == PV for k, v in before['folders'].items() for p in v) and all(k in labels for k in before['folders']) and PV not in mem(ph, 'Services · Leisure', 'Media Center')
    r['socials'] = all(p in mem(ph, 'Me', 'Me-Pers') for p in SOC) and not [p for p in mem(ph, 'Me', 'Me-Prj') if p in SOC]
    r['link'] = any(l['url'] == 'https://linktree.diegonmarcos.com' for l in lk(ph, 'Me', 'Me-Prj'))
    r['lnktree'] = mem(ph, 'Me', 'Lnktree') == mem(ph, 'Me', 'Me-Prj') and lk(ph, 'Me', 'Lnktree') == lk(ph, 'Me', 'Me-Prj') and bool(lk(ph, 'Me', 'Lnktree'))
    r['tidal'] = 'com.aspiro.tidal' in mem(ph, 'Services · Leisure', 'Media Center')
    r['gemini'] = 'com.google.android.apps.bard' in mem(ph, 'AGI', 'AI')
    r['github'] = 'com.github.android' in mem(ph, 'Data', 'Drive') and 'com.github.android' not in mem(ph, 'AGI', 'Code')
    r['jobs'] = mem(ph, 'Services · City', 'Jobs & Business') == ['com.indeed.android.jobsearch', 'com.linkedin.android'] and 'com.linkedin.android' in mem(ph, 'Me', 'Me-Pers')
    ren = {'Nav': 'Navigation', 'Agenda': 'Tasks'}; ok = True; n = 0
    for g in ph:
        cg = [c for c in cl if c['title'] == g['title']]
        for f in g.get('folders', []):
            if not cg: continue
            t = [t for t in cg[0]['tiles'] if t.get('label') == ren.get(f['label'], f['label'])]
            n += 1
            if not t or t[0].get('icon') != f.get('icon_res'): ok = False
    r['icons'] = ok and n >= 40
    return r
base = checks(bj)
fail = 0
for k, v in base.items():
    print(('  PASS: ' if v else '  FAIL: ') + k); fail += (not v)
def mut(name, key, fn):
    global fail
    d = copy.deepcopy(bj); ph, cl = view(d); fn(ph, cl)
    red = not checks(d)[key]
    print(('  PASS: mutation ' if red else '  FAIL: mutation stayed green: ') + name); fail += (not red)
def mv(ph, g, l, p, to):
    f = fo(ph, g, l); f['packages'] = [x for x in f['packages'] if pk(x) != p]
    if to: fo(ph, *to)['packages'].append({'pkg': p, 'label': p})
mut('a social back in Me-Prj', 'socials', lambda ph, cl: mv(ph, 'Me', 'Me-Pers', 'com.pinterest', ('Me', 'Me-Prj')))
mut('Me-Prj link dropped', 'link', lambda ph, cl: fo(ph, 'Me', 'Me-Prj').pop('links'))
mut('Lnktree differs from Me-Prj', 'lnktree', lambda ph, cl: fo(ph, 'Me', 'Lnktree').pop('links'))
mut('Tidal dropped', 'tidal', lambda ph, cl: mv(ph, 'Services · Leisure', 'Media Center', 'com.aspiro.tidal', None))
mut('Prime Video back in Media Center', 'parity', lambda ph, cl: fo(ph, 'Services · Leisure', 'Media Center')['packages'].append({'pkg': PV, 'label': 'Prime Video'}))
mut('a quickmark dropped', 'parity', lambda ph, cl: mv(ph, 'Me', 'Me-Pers', 'com.strava', None))
mut('Gemini dropped', 'gemini', lambda ph, cl: mv(ph, 'AGI', 'AI', 'com.google.android.apps.bard', None))
mut('GitHub back in Code', 'github', lambda ph, cl: mv(ph, 'Data', 'Drive', 'com.github.android', ('AGI', 'Code')))
mut('LinkedIn dropped from Jobs & Business (#1960)', 'jobs', lambda ph, cl: mv(ph, 'Services · City', 'Jobs & Business', 'com.linkedin.android', None))
mut('Me-Prj icon swapped', 'icons', lambda ph, cl: fo(ph, 'Me', 'Me-Prj').__setitem__('icon_res', 'ic_file_stack'))
print('== test-quickmarks-1956: %d failed ==' % fail)
sys.exit(1 if fail else 0)
PY
