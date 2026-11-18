#!/usr/bin/env bash
# Tester (#1806): ONE Services order, declared once in build.json::ui.services_order, on every surface that lists Services:
# the Cloud Services row, the Phone Services quickmarks, the All Apps + folders, the Configs > Launcher > Folders dataset
# (data/fleet/launcher-folders/phone.json) and the browser's Services favourites (ac_cloud-browser favourites_catalog.json).
# Rule 16 parity against the layout before the reorg (test/data/services-me-before-1806.json): every tile, quickmark app,
# folder keyword, metadata rule and favourite is still there (a superset). Me: ~Health and ~Outdoor & Sport are one
# ~Health Trackers; the service-type entries moved to +Health. Each mutant at the end must turn the checks red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
root = sys.argv[1]
A = root + '/aa_cloud-superapp/'
BJ = json.load(open(A + 'build.json'))
DS = json.load(open(root + '/data/fleet/launcher-folders/phone.json'))
FAV = json.load(open(root + '/ac_cloud-browser/app/src/main/assets/favourites_catalog.json'))
PREV = json.load(open(A + 'test/data/services-me-before-1806.json'))
GRADLE = open(root + '/ab_cloud-libs-shared/libs/appstore/services-order.gradle').read()
APPG = open(A + 'app/build.gradle').read()
STOREG = open(root + '/ab_cloud-libs-shared/libs/appstore/build.gradle').read()
GEN = open(root + '/ac_cloud-browser/tools/gen-favourites-catalog.py').read()
WANT = [['Search'], ['News', 'Events', 'Media Center', 'Games'], ['Shopping', 'Jobs & Business', 'Real Estate', 'Health'], ['Money'], ['Hotels', 'Transports', 'City Mobility'], ['KG Store']]

def run(bj, ds, fav):
    fails = []
    def check(ok, msg, quiet=False):
        if not ok: fails.append(msg)
        if not quiet: print('  %s: %s' % ('PASS' if ok else 'FAIL', msg))
    ui = bj['ui']
    S = {s['id']: s for s in ui['sections']}
    so = ui.get('services_order') or {}
    groups, cats = so.get('groups', []), so.get('categories', {})
    keys = [k for g in groups for k in g]
    print('== O: the order is declared once ==')
    check([[cats[k]['label'] for k in g] for g in groups] == WANT, 'ui.services_order reads %s' % ' | '.join(', '.join(g) for g in WANT))
    check(len(keys) == len(set(keys)) and all(k in cats for k in keys) and set(cats) == set(keys), 'every key is declared once and has exactly one category entry')
    labels = [cats[k]['label'] for k in keys]
    cloud = [g for g in S['cloud']['tile_groups'] if g['title'] == 'Services'][0]['tiles']
    phone = [g for g in S['phone']['phone_app_groups'] if g['title'] == 'Services'][0]['folders']
    folders = ui['phone_folders']
    print('== C: Cloud Services row ==')
    row = [t for t in cloud if not t.get('separator')]
    by_tile = {cats[k]['cloud_tile']: k for k in keys}
    check([t['id'] for t in row] == [cats[k]['cloud_tile'] for k in keys], 'tiles follow the declared order (%s)' % [t['label'] for t in row])
    check([t['label'] for t in row] == labels, 'tile captions are the category labels')
    want_row = ' | '.join(' '.join(cats[k]['label'] for k in g) for g in groups)
    check(' '.join('|' if t.get('separator') else t['label'] for t in cloud) == want_row, "a '|' divider between groups: %s" % want_row)
    print('== P: Phone Services quickmarks ==')
    check([f['label'] for f in phone] == [cats[k]['quickmark'] for k in keys if cats[k]['quickmark']], 'quickmark folders follow the declared order (%s)' % [f['label'] for f in phone])
    print('== A: All Apps + folders and the Configs folders dataset ==')
    fid = [cats[k]['folder'] for k in keys]
    plus = [f for f in sorted(folders, key=lambda f: int(f['order'])) if f['label'].startswith('+')]
    check([f['id'] for f in plus] == fid, 'All Apps + folders, by order, follow the declared order')
    check([f['label'][1:] for f in plus] == labels, 'All Apps + folder labels are the category labels')
    check([int(f['order']) for f in plus] == sorted(int(f['order']) for f in plus) and [f['id'] for f in folders if f['label'].startswith('+')] == fid, 'build.json lists them in that order too')
    dsp = [f for f in sorted(ds['folders'], key=lambda f: f['order']) if f['label'].startswith('+')]
    check([(f['id'], f['label'], f['order']) for f in dsp] == [(f['id'], f['label'], int(f['order'])) for f in plus], 'data/fleet/launcher-folders/phone.json carries the same + folders, labels and orders')
    dsm = {f['id']: (f['label'], f['order']) for f in ds['folders'] if f['label'][:1] == '~'}
    check(dsm == {f['id']: (f['label'], int(f['order'])) for f in folders if f['label'][:1] == '~'}, 'and the same ~ (Me) folders')
    print('== W: browser Services favourites ==')
    seen = []
    for i in fav['items']:
        g = i['group']
        if g.startswith('Services/') and g[9:] not in seen: seen.append(g[9:])
    check(seen == [l for l in labels if l in seen], 'Services favourite groups appear in the declared order (%s)' % seen)
    print('== R: every consumer reads ui.services_order ==')
    check('ext.applyServicesOrder' in GRADLE and 'ui?.services_order' in GRADLE, 'services-order.gradle reads ui.services_order')
    check("libs/appstore/services-order.gradle'" in APPG and 'applyServicesOrder(buildJson.ui)' in APPG and APPG.index('applyServicesOrder(buildJson.ui)') < APPG.index('def uiSectionsJson'), 'app/build.gradle applies it before baking ui.sections')
    check('services-order.gradle' in STOREG and 'applyServicesOrder(taxonomyUi)' in STOREG and STOREG.index('applyServicesOrder(taxonomyUi)') < STOREG.index('UI_PHONE_FOLDERS_B64'), 'libs:appstore applies it before baking ui.phone_folders')
    check('services_order' in GEN, 'gen-favourites-catalog.py orders the Services favourites by it')

    print('== S: superset of the previous layout (rule 16) ==')
    allc = {t['id']: t for g in S['cloud']['tile_groups'] for t in g['tiles']}
    for gt, tl in PREV['cloud_tile_groups'].items():
        gone = [t['id'] for t in tl if t['id'] not in allc]
        check(not gone, 'every Cloud %s tile is still there (%d)%s' % (gt, len(tl), ' GONE %s' % gone if gone else ''))
        moved = [t['id'] for t in tl if t['id'] in allc and (allc[t['id']].get('icon') != t['icon'] or allc[t['id']].get('target') != t['target'])]
        check(not moved, 'Cloud %s tiles keep their icon and target%s' % (gt, ' CHANGED %s' % moved if moved else ''))
    nowq = {g['title']: [p['pkg'] for f in g['folders'] for p in f['packages']] for g in S['phone']['phone_app_groups'] if g['title'] in PREV['phone_app_groups']}
    for gt, fl in PREV['phone_app_groups'].items():
        was = [p for f in fl for p in f['packages']]
        gone = [p for p in was if p not in nowq.get(gt, [])]
        check(not gone, 'every Phone %s quickmark app is still a %s quickmark (%d)%s' % (gt, gt, len(was), ' GONE %s' % gone if gone else ''))
    nowkw = {}
    for f in folders:
        for k in f['match_keywords']: nowkw.setdefault(k, []).append(f['id'])
    nowmeta = {(m, f['id']) for f in folders for m in f.get('match_metadata', [])}
    was = [(k, f['id']) for f in PREV['phone_folders'] for k in f['match_keywords']]
    gone = [k for k, _ in was if k not in nowkw]
    check(not gone, 'every All Apps + and ~ keyword is still declared (%d)%s' % (len(was), ' GONE %s' % gone if gone else ''))
    check(all(len(v) == 1 for v in nowkw.values()), 'no keyword is declared by two folders')
    ids = {f['id'] for f in folders}
    gone = [f['id'] for f in PREV['phone_folders'] if f['id'] not in ids and f['id'] != 'hlth_outdoor']
    check(not gone and 'hlth_outdoor' not in ids, 'every previous folder id survives (hlth_outdoor merged into svc_health)%s' % (' GONE %s' % gone if gone else ''))
    meta = [m for f in PREV['phone_folders'] for m in f['match_metadata'] if not any(mm == m for mm, _ in nowmeta)]
    check(not meta, 'every metadata rule is still declared%s' % (' GONE %s' % meta if meta else ''))
    dsid = {f['id'] for f in ds['folders']}
    check(all(f['id'] in dsid or f['id'] == 'hlth_outdoor' for f in PREV['launcher_folders_dataset']), 'every previous Configs dataset folder survives (hlth_outdoor merged)')
    furl = {i['url'] for i in fav['items']}
    gone = [i['url'] for i in PREV['favourites'] if i['url'] not in furl]
    check(not gone, 'every previous Services and Health / Outdoor & Sport favourite is still listed (%d)%s' % (len(PREV['favourites']), ' GONE %s' % gone[:5] if gone else ''))
    mt = {k for f in PREV['phone_folders'] if f['id'] == 'svc_mobility' for k in f['match_keywords']}
    home = {k: nowkw[k][0] for k in mt if k in nowkw}
    check(set(home.values()) == {'svc_hotels', 'svc_transports', 'svc_mobility'} and len(home) == len(mt), 'every Mobility & Travel entry is re-homed to Hotels, Transports or City Mobility')
    for p, f in {'pkg:com.ubercab': 'svc_mobility', 'pkg:com.donkeyrepublic.bike.android': 'svc_mobility', 'pkg:com.ridedott.rider': 'svc_mobility', 'pkg:de.bvg.ticket': 'svc_mobility',
                 'pkg:de.flixbus.app': 'svc_transports', 'pkg:com.thetrainline': 'svc_transports', 'pkg:com.ryanair.cheapflights': 'svc_transports', 'pkg:de.hafas.android.db': 'svc_transports',
                 'pkg:com.booking': 'svc_hotels', 'pkg:com.airbnb.android': 'svc_hotels', 'pkg:com.hostelworld.app': 'svc_hotels'}.items():
        check(nowkw.get(p) == [f], '%s -> %s' % (p[4:], f), quiet=True)
    print('== M: Me section: Health Trackers, service-type entries under Services > Health ==')
    me = [f['label'][1:] for f in sorted(folders, key=lambda f: int(f['order'])) if f['label'].startswith('~')]
    check(me == ['Me', 'Health Trackers', 'Gov & ID', 'Telecom', 'Home & Housing', 'Nutrition', 'Learning'], 'Me folders read %s' % me)
    for p in ['com.medantapatient.app', 'com.urbansportsclub', 'com.downdogapp', 'com.motosumo.app', 'com.bodybike.bbsmartplus']:
        check(nowkw.get('pkg:' + p) == ['svc_healthcare'], '%s is a Services > Health entry' % p)
    for p in ['com.strava', 'com.garmin.android.apps.connectmobile', 'com.urbandroid.sleep', 'com.fitbit.FitbitMobile', 'com.sec.android.app.shealth', 'com.google.android.apps.fitness', 'com.oplayer.orunningplus', 'com.alltrails.alltrails', 'de.komoot.android', 'io.vicoach']:
        check(nowkw.get('pkg:' + p) == ['svc_health'], '%s is a Me > Health Trackers entry' % p, quiet=True)
    ht = [f for f in folders if f['id'] == 'svc_health'][0]
    check(set(ht.get('match_metadata', [])) >= {'perm:android.permission.ACTIVITY_RECOGNITION', 'perm:android.permission.BODY_SENSORS'}, 'Health Trackers keeps the tracker permission rules')
    hq = [f for f in phone if f['label'] == 'Health']
    check(hq and {p['pkg'] for p in hq[0]['packages']} >= {'com.urbansportsclub', 'com.medantapatient.app', 'com.downdogapp', 'com.motosumo.app'}, 'the Phone Services > Health quickmark holds the gym, medical care, yoga and indoor-cycling apps')
    return fails

fails = run(BJ, DS, FAV)
print('== X: mutants (each must be red) ==')
def mut(name, f):
    bj, ds, fav = copy.deepcopy(BJ), copy.deepcopy(DS), copy.deepcopy(FAV)
    f(bj, ds, fav)
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()): r = run(bj, ds, fav)
    ok = bool(r); print('  %s: mutant %s is red' % ('PASS' if ok else 'FAIL', name))
    if not ok: fails.append('mutant ' + name)
cloudrow = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'cloud' for g in s['tile_groups'] if g['title'] == 'Services'][0]['tiles']
phoneq = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'phone' for g in s['phone_app_groups'] if g['title'] == 'Services'][0]['folders']
mut('Cloud row swapped', lambda bj, ds, fav: cloudrow(bj).insert(0, cloudrow(bj).pop(2)))
mut('Phone quickmark swapped', lambda bj, ds, fav: phoneq(bj).append(phoneq(bj).pop(0)))
mut('All Apps order swapped', lambda bj, ds, fav: [f.__setitem__('order', '699') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_search'])
mut('a keyword dropped', lambda bj, ds, fav: [f['match_keywords'].pop() for f in bj['ui']['phone_folders'] if f['id'] == 'svc_hotels'])
mut('a tile dropped', lambda bj, ds, fav: cloudrow(bj).pop())
mut('Down Dog back in Me', lambda bj, ds, fav: [f['match_keywords'].remove('pkg:com.downdogapp') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_healthcare'] and None or [f['match_keywords'].append('pkg:com.downdogapp') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_health'])
mut('order declared twice differently', lambda bj, ds, fav: bj['ui']['services_order']['groups'].reverse())
mut('favourite dropped', lambda bj, ds, fav: fav['items'].pop([i for i, x in enumerate(fav['items']) if x['group'].startswith('Services/') and [y['url'] for y in fav['items']].count(x['url']) == 1][0]))
print('== RESULT: %d failed ==' % len(fails))
sys.exit(1 if fails else 0)
PY
