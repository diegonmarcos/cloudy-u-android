#!/usr/bin/env bash
# Tester (#1806, #1902): ONE Services order (sections Services · City, Services · Leisure and the Search | KG Store tail of AGI), declared once in build.json::ui.services_order, on every surface that lists Services:
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
CITY, LEIS = 'Services · City', 'Services · Leisure'
# #1902 the Services order is declared in sections: City and Leisure (Services), and the tail of AGI (Search | KG Store, they left Services)
WANT = {CITY: [['Money'], ['Shopping', 'Health', 'Real Estate', 'Jobs & Business'], ['City Mobility', 'Transports', 'Hotels']],
        LEIS: [['News'], ['Events', 'Media Center', 'Social', 'Games']],
        'AGI': [['Search', 'KG Store']]}
PREFIX = {CITY: '+', LEIS: '%', 'AGI': '*'}

def sp_by_prefix(sp, pre): return next(t for t, p in sp.items() if p == pre)

def run(bj, ds, fav):
    fails = []
    def check(ok, msg, quiet=False):
        if not ok: fails.append(msg)
        if not quiet: print('  %s: %s' % ('PASS' if ok else 'FAIL', msg))
    ui = bj['ui']
    S = {s['id']: s for s in ui['sections']}
    so = ui.get('services_order') or {}
    secs, cats = so.get('sections', []), so.get('categories', {})
    keys = [k for s in secs for g in s['groups'] for k in g]
    print('== O: the order is declared once ==')
    check([s['title'] for s in secs] == list(WANT), 'ui.services_order sections read %s' % list(WANT))
    check({s['title']: [[cats[k]['label'] for k in g] for g in s['groups']] for s in secs} == WANT, 'ui.services_order reads %s' % ' ; '.join('%s: %s' % (t, ' | '.join(', '.join(g) for g in gs)) for t, gs in WANT.items()))
    sp = {s['title']: s['prefix'] for s in ui['phone_sections']}
    check({s['title']: s['prefix'] for s in secs} == PREFIX and all(sp.get(t) == p for t, p in PREFIX.items()), 'each section names its All Apps label prefix (+ City, % Leisure, * AGI) and it is the one ui.phone_sections declares')
    check(len(keys) == len(set(keys)) and all(k in cats for k in keys) and set(cats) == set(keys), 'every key is declared once and has exactly one category entry')
    labels = [cats[k]['label'] for k in keys]
    cg = {g['title']: g['tiles'] for g in S['cloud']['tile_groups']}
    pg = {g['title']: g.get('folders', []) for g in S['phone']['phone_app_groups']}
    folders = ui['phone_folders']
    print('== C: Cloud Services rows ==')
    for sec in secs:
        s = sec['title']
        names = [cats[k]['cloud_tile'] for g in sec['groups'] for k in g]
        tiles = cg[s]
        if sec.get('tail'):
            tiles = tiles[len(tiles) - (len(names) + len(sec['groups'])):]  # the tail follows the AGI tiles already there
        row = [t for t in tiles if not t.get('separator')]
        check([t['id'] for t in row] == names, '%s tiles follow the declared order (%s)' % (s, [t['label'] for t in row]))
        check([t['label'] for t in row] == [cats[k]['label'] for g in sec['groups'] for k in g], '%s tile captions are the category labels' % s)
        want_row = ' | '.join(' '.join(cats[k]['label'] for k in g) for g in sec['groups'])
        got = ' '.join('|' if t.get('separator') else t['label'] for t in tiles)
        check(got == ('| ' if sec.get('tail') else '') + want_row, "a '|' divider between groups of %s: %s" % (s, want_row))
    check([t.get('target') for t in cg[LEIS] if t['id'] == 'social'] == ['https://diegonmarcos.github.io/mySocials/'], 'the Cloud Social tile opens the mySocials web page (#1904)')
    check([t['label'] for t in cg['AGI']][:8] == ['SI', 'AI', '|', 'Code', 'Tools', '|', 'MyTerm.', 'Terminal'], 'the AGI tiles already there keep their place before the tail')
    print('== P: Phone Services quickmarks ==')
    for sec in secs:
        s = sec['title']
        want = [cats[k]['quickmark'] for g in sec['groups'] for k in g if cats[k]['quickmark']]
        got = [f['label'] for f in pg[s]]
        check(got == want if not sec.get('tail') else (got[len(got) - len(want):] == want and got[:len(got) - len(want)] == ['AI', 'Code', 'Terminal']), 'quickmark folders of %s follow the declared order (%s)' % (s, got))
    soc = [f for f in pg[LEIS] if f['label'] == 'Social'][0]
    check(soc.get('opens_folder') == 'media_social' and not soc['packages'] and cats['social']['folder'] == 'media_social' and any(f['id'] == 'media_social' for f in folders), 'the Phone Social quickmark opens the media_social All Apps folder (opens_folder), holding no copy of its apps')
    print('== A: All Apps + folders and the Configs folders dataset ==')
    byid = {f['id']: f for f in folders}
    for sec in secs:
        fid = [cats[k]['folder'] for g in sec['groups'] for k in g]
        want_l = [cats[k]['label'] for g in sec['groups'] for k in g]
        here = [byid[i] for i in fid]
        check([f['label'] for f in here] == [sec['prefix'] + l for l in want_l], 'All Apps folders of %s carry the %s prefix and the category labels' % (sec['title'], sec['prefix']))
        check([int(f['order']) for f in here] == sorted(int(f['order']) for f in here), 'All Apps folders of %s follow the declared order by their order values' % sec['title'])
        if not sec.get('tail'):
            by_prefix = [f['id'] for f in sorted(folders, key=lambda f: int(f['order'])) if f['label'].startswith(sec['prefix'])]
            check(by_prefix == fid, '%s holds exactly its declared folders (nothing else carries %s)' % (sec['title'], sec['prefix']))
    allf = [byid[cats[k]['folder']] for k in keys]
    check([f['id'] for f in sorted(allf, key=lambda f: int(f['order']))] == [byid[cats[k]['folder']]['id'] for k in keys], 'the declared order is the order-value order of all Services folders (City, Leisure, AGI tail)')
    check([f['id'] for f in folders if f['id'] in {a['id'] for a in allf}] == [byid[cats[k]['folder']]['id'] for k in keys], 'build.json lists them in that order too')
    dsm = {f['id']: (f['label'], f['order'], f['section']) for f in ds['folders']}
    check(all(dsm.get(f['id']) == (f['label'], int(f['order']), sp_by_prefix(sp, f['label'][0])) for f in allf), 'data/fleet/launcher-folders/phone.json carries the same folders, labels, orders and sections')
    dsmm = {f['id']: (f['label'], f['order']) for f in ds['folders'] if f['label'][:1] == '~'}
    check(dsmm == {f['id']: (f['label'], int(f['order'])) for f in folders if f['label'][:1] == '~'}, 'and the same ~ (Me) folders')
    print('== W: browser Services favourites ==')
    seen = []
    for i in fav['items']:
        g = i['group']
        if g.startswith('Services/') and g[9:] not in seen: seen.append(g[9:])
    check(seen == [l for l in labels if l in seen], 'Services favourite groups appear in the declared order (%s)' % seen)
    check('Search' in seen, 'the Services chip still lists Search (the AGI tail folders are Services entries)')
    print('== R: every consumer reads ui.services_order ==')
    check('ext.applyServicesOrder' in GRADLE and 'ui?.services_order' in GRADLE and 'so.sections' in GRADLE, 'services-order.gradle reads ui.services_order sections')
    check("libs/appstore/services-order.gradle'" in APPG and 'applyServicesOrder(buildJson.ui)' in APPG and APPG.index('applyServicesOrder(buildJson.ui)') < APPG.index('def uiSectionsJson'), 'app/build.gradle applies it before baking ui.sections')
    check('services-order.gradle' in STOREG and 'applyServicesOrder(taxonomyUi)' in STOREG and STOREG.index('applyServicesOrder(taxonomyUi)') < STOREG.index('UI_PHONE_FOLDERS_B64'), 'libs:appstore applies it before baking ui.phone_folders')
    check('services_order' in GEN and '"sections"' in GEN, 'gen-favourites-catalog.py orders the Services favourites by it')

    print('== S: superset of the previous layout (rule 16) ==')
    allc = {t['id']: t for g in S['cloud']['tile_groups'] for t in g['tiles']}
    for gt, tl in PREV['cloud_tile_groups'].items():
        # #1903 renames, never removals: the Profile tile moved to Setup (configs-profile) and its rule (a layout item) went with it; the three Cloud Me page tiles keep id and icon, their targets are the new pages
        ME_MOVED = {'tools-account': 'configs-profile', 'me-sep-3': None}
        ME_RETARGET = {'myhealth': 'extapp:cloud-me#page:pers/pers-profiles', 'myexperiences': 'extapp:cloud-me#page:prof/prof-profiles', 'myroadmap': 'extapp:cloud-me#page:proj/lnktree-site'}
        gone = [t['id'] for t in tl if t['id'] not in allc and not (t['id'] in ME_MOVED and (ME_MOVED[t['id']] is None or ME_MOVED[t['id']] in allc))]
        check(not gone, 'every Cloud %s tile is still there (%d)%s' % (gt, len(tl), ' GONE %s' % gone if gone else ''))
        moved = [t['id'] for t in tl if t['id'] in allc and (allc[t['id']].get('icon') != t['icon'] or allc[t['id']].get('target') != ME_RETARGET.get(t['id'], t['target']))]
        check(not moved, 'Cloud %s tiles keep their icon and target%s' % (gt, ' CHANGED %s' % moved if moved else ''))
    nowq = {g['title']: [p['pkg'] for f in g['folders'] for p in f['packages']] for g in S['phone']['phone_app_groups'] if g['title'] in PREV['phone_app_groups']}
    nowq['Services'] = [p for t_ in (CITY, LEIS, 'AGI') for p in [p_['pkg'] for f in pg[t_] for p_ in f['packages']]]  # #1902 the old Services group is City + Leisure + the AGI tail
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
    hq = [f for f in pg[CITY] if f['label'] == 'Health']
    check(hq and {p['pkg'] for p in hq[0]['packages']} >= {'com.urbansportsclub', 'com.medantapatient.app', 'com.downdogapp', 'com.motosumo.app'}, 'the Phone Services · City > Health quickmark holds the gym, medical care, yoga and indoor-cycling apps')
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
cloudrow = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'cloud' for g in s['tile_groups'] if g['title'] == CITY][0]['tiles']
phoneq = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'phone' for g in s['phone_app_groups'] if g['title'] == CITY][0]['folders']
mut('Cloud row swapped', lambda bj, ds, fav: cloudrow(bj).insert(0, cloudrow(bj).pop(2)))
mut('Phone quickmark swapped', lambda bj, ds, fav: phoneq(bj).append(phoneq(bj).pop(0)))
mut('All Apps order swapped', lambda bj, ds, fav: [f.__setitem__('order', '699') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_search'])
mut('a keyword dropped', lambda bj, ds, fav: [f['match_keywords'].pop() for f in bj['ui']['phone_folders'] if f['id'] == 'svc_hotels'])
mut('a tile dropped', lambda bj, ds, fav: cloudrow(bj).pop())
mut('Down Dog back in Me', lambda bj, ds, fav: [f['match_keywords'].remove('pkg:com.downdogapp') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_healthcare'] and None or [f['match_keywords'].append('pkg:com.downdogapp') for f in bj['ui']['phone_folders'] if f['id'] == 'svc_health'])
mut('order declared twice differently', lambda bj, ds, fav: bj['ui']['services_order']['sections'][0]['groups'].reverse())
mut('favourite dropped', lambda bj, ds, fav: fav['items'].pop([i for i, x in enumerate(fav['items']) if x['group'].startswith('Services/') and [y['url'] for y in fav['items']].count(x['url']) == 1][0]))
leisrow = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'cloud' for g in s['tile_groups'] if g['title'] == LEIS][0]['tiles']
agirow = lambda bj: [g for s in bj['ui']['sections'] if s['id'] == 'cloud' for g in s['tile_groups'] if g['title'] == 'AGI'][0]['tiles']
mut('Social tile retargeted', lambda bj, ds, fav: [t_.__setitem__('target', 'page:phone/apps') for t_ in leisrow(bj) if t_['id'] == 'social'])
mut('Social quickmark stops opening the folder', lambda bj, ds, fav: [f_.pop('opens_folder') for s_ in bj['ui']['sections'] if s_['id'] == 'phone' for g_ in s_['phone_app_groups'] if g_['title'] == LEIS for f_ in g_['folders'] if f_['label'] == 'Social'])
mut('Search tile back in a Services row', lambda bj, ds, fav: leisrow(bj).append(agirow(bj).pop(-2)))
mut('KG Store label loses the AGI prefix', lambda bj, ds, fav: [f_.__setitem__('label', '+KG Store') for f_ in bj['ui']['phone_folders'] if f_['id'] == 'svc_kgstore'])
mut('Social folder keeps the Communication prefix', lambda bj, ds, fav: [f_.__setitem__('label', '@Social') for f_ in bj['ui']['phone_folders'] if f_['id'] == 'media_social'])
mut('the dataset forgets a section move', lambda bj, ds, fav: [f_.__setitem__('section', 'Tools \u00b7 Communication') for f_ in ds['folders'] if f_['id'] == 'media_social'])
print('== RESULT: %d failed ==' % len(fails))
sys.exit(1 if fails else 0)
PY
