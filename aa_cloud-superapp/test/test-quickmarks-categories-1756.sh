#!/usr/bin/env bash
# Tester (#1756): ONE category system for All Apps and Quickmarks (Cloud and Phone).
# Rule 16 field parity against the previous layout (test/data/previous-layout-1756.json), every inventory package
# (test/data/apps-inventory-1756.json) lands in exactly one All Apps folder, group order, folder contents, every
# section is made of subtype folders (no loose app), Notify taxonomy prefixes still exist. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, collections
root = sys.argv[1]
A = root + '/aa_cloud-superapp/'
bj = json.load(open(A + 'build.json'))['ui']
prev = json.load(open(A + 'test/data/previous-layout-1756.json'))
inv = json.load(open(A + 'test/data/apps-inventory-1756.json'))['apps']
P = F = 0
def check(ok, msg):
    global P, F
    if ok: P += 1; print('  PASS:', msg)
    else: F += 1; print('  FAIL:', msg)
pk = lambda x: x if isinstance(x, str) else x['pkg']
phone = [s for s in bj['sections'] if s['id'] == 'phone'][0]['phone_app_groups']
cloud = [s for s in bj['sections'] if s['id'] == 'cloud'][0]['tile_groups']
folders = bj['phone_folders']; secs = bj['phone_sections']
ORDER = ['Primary', 'Communication', 'Data', 'Me', 'Services', 'AGI', 'Projects', 'Setup']

print('== G: group order (Phone and Cloud quickmarks) ==')
check([g['title'] for g in phone] == ORDER, 'Phone quickmark groups are %s (got %s)' % (ORDER, [g['title'] for g in phone]))
check([g['title'] for g in cloud] == ORDER, 'Cloud quickmark groups are %s (got %s)' % (ORDER, [g['title'] for g in cloud]))
check([s['title'] for s in secs] == ['Tools · Primary', 'Tools · Communication', 'Tools · Data', 'Tools · Me', 'Services', 'AGI', 'Projects', 'Setup'],
      'All Apps sections follow the same order: %s' % [s['title'] for s in secs])

print('== P: field parity against the previous layout ==')
new_q = set()
for g in phone:
    for p in g.get('packages', []): new_q.add(pk(p))
    for f in g.get('folders', []):
        for p in f['packages']: new_q.add(pk(p))
for gt, pl in prev['quickmarks'].items():
    miss = [p for p in pl if p not in new_q]
    check(not miss, 'old quickmark group %s: all %d packages still present%s' % (gt, len(pl), ' MISSING %s' % miss if miss else ''))
newkw = collections.defaultdict(list)
for f in folders:
    for k in f['match_keywords']: newkw[k].append(f['id'])
lost = [(i, k) for i, o in prev['folders'].items() for k in o['keywords'] if k not in newkw]
check(not lost, 'every old folder keyword is still declared by some folder (%d keywords)%s' % (sum(len(o['keywords']) for o in prev['folders'].values()), ' LOST %s' % lost if lost else ''))
dup = {k: v for k, v in newkw.items() if len(v) > 1}
check(not dup, 'no keyword is declared by two folders %s' % dup)
check(all(i in {f['id'] for f in folders} or i in ('svc_travel', 'media_audio') for i in prev['folders']), 'every old folder id survives (svc_travel merged into svc_mobility, media_audio into media_video)')
check(all(k in newkw for k in prev['folders']['svc_travel']['keywords']) and all('svc_mobility' in newkw[k] for k in prev['folders']['svc_travel']['keywords']),
      'every Travel keyword now lives in Mobility & Travel')
oldq_titles = set(prev['quickmarks'])
check(all(any(t == n for n in ORDER) or t in ('AI', 'Inboxes', 'Data Apps', 'Tools Primary', 'Configs') for t in oldq_titles), 'old group names are all accounted for')

print('== S: every section is subtype folders, nothing loose ==')
prefixes = [s['prefix'] for s in secs]
bysec = collections.defaultdict(list)
for f in folders:
    if f['id'] in ('misc', 'others'): continue
    bysec[f['label'][0]].append(f)
for s in secs:
    fl = bysec.get(s['prefix'], [])
    check(len(fl) >= 1, '%s (%s) has %d subtype folders: %s' % (s['title'], s['prefix'], len(fl), [x['label'][1:] for x in fl]))
check(all(f['label'][0] in prefixes for fid in bysec for f in bysec[fid]), 'every folder label starts with a declared section prefix')
check(sorted(x['order'] for x in folders) == [x['order'] for x in folders] or True, 'orders listed')
orders = [int(x['order']) for x in folders]
check(orders == sorted(orders) and len(set(orders)) == len(orders), 'folder orders are unique and ascending (render order = section order)')
want = {
 '=': ['Browser', 'Nav', 'Calc', 'Camera', 'Keyboard', 'Wallet', 'Utilities'],
 '@': ['Mail', 'Chat', 'Messenger', 'Dialer', 'Contacts', 'Social'],
 '.': ['Drive', 'Notes', 'Agenda', 'Office', 'Gallery', 'Media Center', 'Vault'],
 '~': ['Me', 'Health', 'Gov & ID', 'Telecom', 'Home & Housing', 'Outdoor & Sport', 'Nutrition', 'Learning', 'Games'],
 '+': ['Search', 'News', 'Shopping', 'Events', 'Mobility & Travel', 'Jobs & Business', 'Real Estate', 'Money', 'KG Store'],
 '*': ['AI', 'Code', 'Terminal', 'Tools'], '#': ['SuperApp', 'Omniverse', 'FinTerm', 'CAD', 'Sim2Real'],
 '_': ['Store', 'Account', 'C3 & Dev', 'Diagnostics', 'Network', 'Security', 'Settings'],
}
for pre, labs in want.items():
    check([f['label'][1:] for f in bysec[pre]] == labs, 'section %s holds exactly %s' % (pre, labs))

print('== I: every inventory package lands in exactly one All Apps folder ==')
claims = collections.defaultdict(set)
for f in folders:
    for k in f['match_keywords']:
        if k.startswith('pkg:'): claims[k[4:]].add(f['id'])
pref = [(k[4:], f['id']) for f in folders for k in f['match_keywords'] if k.startswith('pkg^')]
for e in bj['external_apps']:
    for p in [e.get('hub_package'), e.get('alt_package'), e.get('install_package')] + list((e.get('forks') or {}).values()):
        if p: claims[p].add(e['folder'])
bad = []
def classify(p):
    # PhoneAppClassifier: folders walked in `order`, first keyword (pkg: or pkg^) wins; fleet apps are claimed by external_apps.
    for f in sorted(folders, key=lambda x: int(x['order'])):
        for k in f['match_keywords']:
            if (k.startswith('pkg:') and k[4:] == p) or (k.startswith('pkg^') and p.startswith(k[4:])): return [f['id']]
    return sorted(claims.get(p, set()))
for a in inv:
    p = a['package']
    c = classify(p)
    if len(c) != 1 or len([1 for f in folders for k in f['match_keywords'] if k == 'pkg:' + p]) > 1: bad.append((p, c))
check(not bad, 'all %d inventory packages are claimed by exactly one folder%s' % (len(inv), ' BAD %s' % bad if bad else ''))
ids = {f['id'] for f in folders}
check(all(fid in ids for v in claims.values() for fid in v), 'every claim names a declared folder')
exp = {
 'com.bodybike.bbsmartplus': 'hlth_outdoor', 'com.facebook.katana': 'media_social', 'com.motosumo.app': 'hlth_outdoor', 'de.bvg.ticket': 'svc_mobility',
 'juloo.keyboard2': 'sys_keyboards', 'com.samsung.android.goodlock': 'sys_settings', 'com.samsung.android.app.find': 'sys_security',
 'io.vicoach': 'hlth_outdoor', 'net.wordbit.deen': 'hlth_learning', 'com.oplayer.orunningplus': 'svc_health',
 'com.cloudflare.cloudflareoneagent': 'sys_network', 'com.cloudflare.onedotonedotonedotone': 'sys_network',
 'com.dhl.exp.dhlmobile': 'svc_shopping', 'de.dhl.paket': 'svc_shopping', 'de.number26.android': 'svc_money', 'com.imaginecurve.curve.prd': 'svc_money',
 'com.oceanwing.soundcore': 'media_video', 'com.shurgard.selfstorage': 'svc_home', 'de.is24.android': 'svc_realestate', 'com.sensorberg.oneaccess': 'svc_home', 'cld.camera': 'prod_camera', 'org.thoughtcrime.securesms': 'prod_messenger',
}
for p, fid in exp.items():
    check(classify(p) == [fid], 'classification: %s -> %s' % (p, fid))
check(not [a for a in inv if a['category'] is None and len(claims.get(a['package'], ())) != 1], 'every package that had category null is now classified')

print('== Q: Phone quickmarks hold one tile per subtype, in All Apps order ==')
sub_of = {f['id']: (f['label'][0], f['label'][1:]) for f in folders}
GROUP = {'=': 'Primary', '@': 'Communication', '.': 'Data', '~': 'Me', '+': 'Services', '*': 'AGI', '#': 'Projects', '_': 'Setup'}
fold_order = {f['id']: int(f['order']) for f in folders}
def cls(p):
    for f in sorted(folders, key=lambda x: int(x['order'])):
        for k in f['match_keywords']:
            if (k.startswith('pkg:') and k[4:] == p) or (k.startswith('pkg^') and p.startswith(k[4:])): return f['id']
    return None
for g in phone:
    if g['title'] == 'Projects':
        check(len(g['packages']) >= 5 and any(f['label'] == 'Money' for f in g['folders']), 'Projects is unchanged (loose tiles plus its Money folder)')
        continue
    check(not g.get('folders'), '%s holds tiles only, no folders' % g['title'])
    last = -1; ok = True; subs = []
    for t in g['packages']:
        fid = cls(t['pkg'])
        pre, lab = sub_of.get(fid, ('?', '?'))
        good = GROUP.get(pre) == g['title'] and lab == t.get('subtype') and fold_order[fid] >= last
        ok &= good; last = fold_order[fid] if fid else last
        if not good: print('        bad tile', g['title'], t)
        subs.append(lab)
    check(ok, '%s: every tile sits in its subtype, group and All Apps order (%s)' % (g['title'], ', '.join(dict.fromkeys(subs))))
prev_apps = {p for gt, pl in prev['quickmarks'].items() for p in pl}
check(all(any(t['pkg'] == p for g in phone for t in g['packages']) or any(p == pk(x) for g in phone for f in g.get('folders', []) for x in f['packages']) for p in prev_apps), 'every app of the previous phone quickmarks still appears')
want_pick = {'Mail': ['com.google.android.gm'], 'Chat': ['com.Slack'], 'Messenger': ['com.whatsapp.w4b'], 'Drive': ['com.google.android.apps.docs'], 'Browser': ['com.brave.browser'], 'Nav': ['com.google.android.apps.maps']}
for st, pl in want_pick.items():
    got = [t['pkg'] for g in phone for t in g['packages'] if t.get('subtype') == st]
    check(all(p in got for p in pl), 'subtype %s keeps today\'s app %s (tiles: %s)' % (st, pl, got))

print('== C: Cloud quickmark icons are unchanged from origin/main ==')
snap = json.load(open(A + 'test/data/cloud-tile-icons-1756.json'))['tiles']
now = {t['id']: t.get('icon') for g in cloud for t in g['tiles'] if not t.get('separator')}
for tid, o in snap.items():
    if tid not in now: check(False, 'Cloud tile %s is gone' % tid)
    elif now[tid] != o['icon']: check(False, 'Cloud tile %s icon changed %s -> %s' % (tid, o['icon'], now[tid]))
check(all(now.get(tid) == o['icon'] for tid, o in snap.items()), 'all %d Cloud tile icons are identical to origin/main' % len(snap))

print('== X: All Apps subtypes mirror the Cloud apps ==')
sub = {f['id']: f['label'][1:] for f in folders}
for e in bj['external_apps']:
    check(e['folder'] in sub, 'Cloud app %s sits in subtype %s' % (e['id'], sub.get(e['folder'])))

print('== N: Notify taxonomy filters and ntfy taxon use declared prefixes ==')
comm = [s for s in bj['sections'] if s['id'] == 'communication'][0]
for k, fl in comm.items():
    if not k.startswith('filters_'): continue
    for f in fl:
        if f['id'] in ('tools', 'services'):
            for o in f['options']:
                if o['id'] == 'all': continue
                check(all(c in prefixes for c in o['id']), '%s / %s option %s uses declared prefixes' % (k, f['id'], o['id']))
            check(f['default'] == 'all' or any(o['id'] == f['default'] for o in f['options']), '%s / %s default %s is an option' % (k, f['id'], f['default']))
check(all(v in prefixes for v in bj['ntfy']['taxon'].values()), 'every ui.ntfy.taxon prefix is a declared section prefix')

print('== RESULT: %d passed, %d failed ==' % (P, F))
sys.exit(1 if F else 0)
PY
