#!/usr/bin/env bash
# Tester (#1902): FIELD PARITY for the four launcher surfaces after the Services split (Services · City, Services · Leisure,
# Search and KG Store ending AGI, Social moving to Leisure, Cloud Smart Folders regrouped into the same nine sections).
# Rule 16: a reorg DROPS NOTHING. test/data/launcher-surfaces-before-1902.json is the snapshot taken from main before the
# change; every surface is checked against it on its own, then mutated, and each mutant must turn that surface red.
#   S1  Phone All Apps (ui.phone_folders + ui.phone_sections + data/fleet/launcher-folders/phone.json): every folder id is still
#       declared with its name (the label minus its section prefix), every match keyword and metadata rule is still declared on it,
#       and the Configs folders dataset still carries every folder.
#   S2  Cloud Quickmarks (ui.sections[cloud].tile_groups): every tile id is still a tile (separators are layout, not fields);
#       outside the Me row (renamed by another ticket) its caption, icon and target are unchanged.
#   S3  Phone Quickmarks (ui.sections[phone].phone_app_groups): every folder label is still a folder (Me excepted, as above), every
#       package is still a quickmark, every link is still a link.
#   S4  Cloud Smart Folders (data/fleet/launcher-folders/cloud.json): every folder id is still declared with its label and icon.
#   N   the new entries exist: Services · City / Services · Leisure sections with their own prefixes, Social in Leisure on the
#       Cloud and Phone quickmarks and on the cloud smart folders.
# #1916: the nine sections are now ordered Primary, Communication, Data, Me (#1927 MOVED: Primary and Data swapped), City, Leisure, AGI, Setup, Projects (a MOVE; the N checks pin it).
# Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy, io, contextlib
root = sys.argv[1]
A = root + '/aa_cloud-superapp/'
BJ = json.load(open(A + 'build.json'))
PH = json.load(open(root + '/data/fleet/launcher-folders/phone.json'))
CL = json.load(open(root + '/data/fleet/launcher-folders/cloud.json'))
SNAP = json.load(open(A + 'test/data/launcher-surfaces-before-1902.json'))
pk = lambda x: x if isinstance(x, str) else x['pkg']
# #1903 the Me row renames (old -> new), never removals: the three page tiles keep id and icon (label and target are the new Cloud Me pages), the Profile tile and
# its rule moved to Setup (configs-profile), the three phone Me folders carry the same new names.
ME_TILE = {'myhealth': ('Me-Pers', 'extapp:cloud-me#page:pers/pers-profiles'), 'myexperiences': ('Me-Prof', 'extapp:cloud-me#page:prof/prof-web'), 'myroadmap': ('Me-Prj', 'extapp:cloud-me#page:proj/pj-home')}
ICON_SWAP = {'me': 'ic_tree', 'myroadmap': 'ic_vcard'}   # #1931 the Lnktree and Me-Prj tiles swapped icons (a move)
ME_MOVED = {'tools-account': 'configs-profile'}
ME_FOLDER = {'Me-Health': 'Me-Pers', 'Experiences': 'Me-Prof', 'Socials': 'Me-Prj'}
CITY, LEIS = 'Services · City', 'Services · Leisure'

def surface(name, bj, ph, cl):
    """the failures of one surface; the mutants below call it with a damaged copy"""
    ui = bj['ui']
    S = {s['id']: s for s in ui['sections']}
    f = []
    if name == 'S1':
        fo = {x['id']: x for x in ui['phone_folders']}
        dsf = {x['id']: x for x in ph['folders']}
        for old in SNAP['phone_all_apps']['folders']:
            n = fo.get(old['id'])
            if n is None: f.append('All Apps folder %s is gone' % old['id']); continue
            if n['label'][1:] != old['label'][1:] and old['label'][0] in '=@.~+*#_': f.append('All Apps folder %s is renamed %r -> %r' % (old['id'], old['label'], n['label']))
            lost = [k for k in old['keywords'] if k not in n['match_keywords']]
            if lost: f.append('All Apps folder %s lost keywords %s' % (old['id'], lost))
            lostm = [k for k in old['metadata'] if k not in n.get('match_metadata', [])]
            if lostm: f.append('All Apps folder %s lost metadata rules %s' % (old['id'], lostm))
        for old in SNAP['phone_dataset']:
            if old['id'] not in dsf and old['id'] != 'others': f.append('the Configs phone dataset lost folder %s' % old['id'])
        prefixes = [s['prefix'] for s in ui['phone_sections']]
        if len(prefixes) != len(set(prefixes)): f.append('two phone sections share a prefix %s' % prefixes)
        for x in ui['phone_folders']:
            if x['id'] not in ('misc', 'others') and x['label'][0] not in prefixes: f.append('folder %s has no section prefix' % x['id'])
    elif name == 'S2':
        now = {t['id']: t for g in S['cloud']['tile_groups'] for t in g['tiles']}
        where = {t['id']: g['title'] for g in S['cloud']['tile_groups'] for t in g['tiles']}
        for gt, tl in SNAP['cloud_quickmarks'].items():
            for old in tl:
                n = now.get(old['id'])
                if n is None and old['id'] in ME_MOVED:
                    if ME_MOVED[old['id']] not in now or where.get(ME_MOVED[old['id']]) != 'Setup': f.append('Cloud Me tile %s left the Me row but is not in Setup as %s' % (old['id'], ME_MOVED[old['id']]))
                    continue
                if n is None:
                    # #1929 a tile merged into another keeps its id as an alias of the merged tile, whose target opens the group that holds the old target's page
                    h = [t for t in now.values() if old['id'] in t.get('aliases', [])]
                    if h:
                        launcher = {'page:config/' + p['id'] for p in S['config']['pages'] if p.get('group') == 'Launcher'}
                        if h[0].get('target') != 'page:config/launcher-hub' or old['target'] not in launcher: f.append('Cloud tile %s merged into %s but its page %s is not reachable from the Launcher group' % (old['id'], h[0]['id'], old['target']))
                        continue
                    f.append('Cloud %s tile %s is gone' % (gt, old['id'])); continue
                if gt == 'Me':   # #1903 renames: the id and the icon stay, the label and target are the new ones
                    if old['id'] in ME_TILE:
                        if n.get('icon') != ICON_SWAP.get(old['id'], old['icon']) or (n.get('label'), n.get('target')) != ME_TILE[old['id']]: f.append('Cloud Me tile %s is not renamed to %s with icon %s' % (old['id'], ME_TILE[old['id']], old['icon']))
                    else:
                        for k in ('label', 'icon', 'target'):
                            if n.get(k) != (ICON_SWAP.get(old['id'], old[k]) if k == 'icon' else old[k]): f.append('Cloud Me tile %s changed %s: %r -> %r' % (old['id'], k, old[k], n.get(k)))
                    continue
                for k in ('label', 'icon', 'target', 'coming_soon'):
                    if n.get(k) != old[k]: f.append('Cloud tile %s changed %s: %r -> %r' % (old['id'], k, old[k], n.get(k)))
    elif name == 'S3':
        pg = {g['title']: g for g in S['phone']['phone_app_groups']}
        allpk = {pk(p) for g in pg.values() for p in g.get('packages', [])} | {pk(p) for g in pg.values() for fo in g.get('folders', []) for p in fo['packages']}
        labels = {g['title']: [fo['label'] for fo in g.get('folders', [])] for g in pg.values()}
        everylabel = {l for ls in labels.values() for l in ls}
        links = {l['label'] for g in pg.values() for l in g.get('links', [])}
        aliased = {a for g in pg.values() for fo in g.get('folders', []) for a in fo.get('aliases', [])}   # #1929 Quick Actions and UI merged into Launcher
        for gt, old in SNAP['phone_quickmarks'].items():
            for fl, pl in old['folders'].items():
                if fl not in everylabel and ME_FOLDER.get(fl) not in everylabel and fl not in aliased: f.append('Phone quickmark folder %s (was in %s) is gone' % (fl, gt))
                lost = [p for p in pl if p not in allpk and p != 'com.amazon.avod.thirdpartyclient']   # #1956 the only removal: Prime Video leaves the Media Center folder (named by the owner)
                if lost: f.append('Phone quickmark folder %s lost packages %s' % (fl, lost))
            lost = [p for p in old['packages'] if p not in allpk]
            if lost: f.append('Phone %s lost loose packages %s' % (gt, lost))
            lost = [l for l in old['links'] if l not in links]
            if lost: f.append('Phone %s lost links %s' % (gt, lost))
    elif name == 'S4':
        now = {x['id']: x for x in cl['folders']}
        for old in SNAP['cloud_smart_folders']:
            n = now.get(old['id'])
            if n is None: f.append('Cloud smart folder %s is gone' % old['id']); continue
            if n['label'] != old['label'] or n['icon'] != old['icon']: f.append('Cloud smart folder %s changed (%r/%r -> %r/%r)' % (old['id'], old['label'], old['icon'], n['label'], n['icon']))
    elif name == 'N':
        mg = [g for g in S['cloud']['tile_groups'] if g['title'] == 'Me'][0]['tiles']
        if [t['label'] for t in mg] != ['Lnktree', '|', 'Me-Pers', 'Me-Prof', 'Me-Prj', '|', 'Me-Invent.', 'Me-Buro', 'Me-LLC']: f.append('the Me row does not read Lnktree | Me-Pers Me-Prof Me-Prj | Me-Invent. Me-Buro Me-LLC: %s' % [t['label'] for t in mg])
        if [fo['label'] for g in S['phone']['phone_app_groups'] if g['title'] == 'Me' for fo in g['folders']] != ['Lnktree', 'Me-Pers', 'Me-Prof', 'Me-Prj', 'Me-Buro']: f.append('the Phone quickmarks Me group is not Lnktree, Me-Pers, Me-Prof, Me-Prj, Me-Buro')
        su = [g for g in S['cloud']['tile_groups'] if g['title'] == 'Setup'][0]['tiles']
        if [t['label'] for t in su[-2:]] != ['Profile', 'About']: f.append('Setup does not end Profile | About (the Profile tile left the Me row)')
        sp = {s['title']: s['prefix'] for s in ui['phone_sections']}
        if not (sp.get(CITY) and sp.get(LEIS) and sp[CITY] != sp[LEIS] and len(set(sp.values())) == len(sp)): f.append('Services · City / Leisure have no unique prefixes: %s' % sp)
        if 'Services' in sp: f.append('the old Services section is still declared')
        fo = {x['id']: x for x in ui['phone_folders']}
        if fo['media_social']['label'] != sp.get(LEIS, '?') + 'Social': f.append('media_social is not a Leisure folder')
        for x in ('svc_search', 'svc_kgstore'):
            if not fo[x]['label'].startswith(sp['AGI']): f.append('%s is not an AGI folder' % x)
        if [g['title'] for g in S['cloud']['tile_groups']] != ['Primary', 'Communication', 'Data', 'Me', CITY, LEIS, 'AGI', 'Setup', 'Projects']: f.append('Cloud quickmark groups are not the nine sections')
        if [g['title'] for g in S['phone']['phone_app_groups']] != ['Primary', 'Communication', 'Data', 'Me', CITY, LEIS, 'AGI', 'Setup', 'Projects']: f.append('Phone quickmark groups are not the nine sections')
        cs = [t for g in S['cloud']['tile_groups'] if g['title'] == LEIS for t in g['tiles'] if t['id'] == 'social']
        if not cs or cs[0]['target'] != 'https://diegonmarcos.github.io/mySocials/': f.append('Cloud Leisure has no Social tile opening the mySocials URL (#1904)')
        ps = [x for g in S['phone']['phone_app_groups'] if g['title'] == LEIS for x in g['folders'] if x['label'] == 'Social']
        if not ps or ps[0].get('opens_folder') != 'media_social': f.append('Phone Leisure has no Social quickmark opening media_social')
        sections = []
        for x in cl['folders']:
            if x['section'] not in sections: sections.append(x['section'])
        if sections != ['Primary', 'Communication', 'Data', 'Me', CITY, LEIS, 'AGI', 'Setup', 'Projects']: f.append('Cloud smart folder sections are %s' % sections)
        if [x['section'] for x in cl['folders'] if x['id'] == 'social'] != [LEIS]: f.append('Cloud smart folder Social is not in Leisure')
    return f

fails = 0
def report(name, title, bj, ph, cl):
    global fails
    r = surface(name, bj, ph, cl)
    print('  %s: %s%s' % ('PASS' if not r else 'FAIL', title, '' if not r else ' -> ' + '; '.join(r[:6])))
    fails += bool(r)

print('== parity against test/data/launcher-surfaces-before-1902.json ==')
report('S1', 'S1 Phone All Apps: all %d folders, keywords and metadata rules kept, dataset complete' % len(SNAP['phone_all_apps']['folders']), BJ, PH, CL)
report('S2', 'S2 Cloud Quickmarks: all %d tiles kept (caption, icon, target outside Me)' % sum(len(v) for v in SNAP['cloud_quickmarks'].values()), BJ, PH, CL)
report('S3', 'S3 Phone Quickmarks: every folder, package and link kept', BJ, PH, CL)
report('S4', 'S4 Cloud Smart Folders: all %d folders kept with label and icon' % len(SNAP['cloud_smart_folders']), BJ, PH, CL)
report('N', 'N the new sections, Social entries and regrouped Smart Folders exist', BJ, PH, CL)

print('== mutants: each must turn its surface red ==')
def mut(name, surf, fn):
    global fails
    bj, ph, cl = copy.deepcopy(BJ), copy.deepcopy(PH), copy.deepcopy(CL)
    fn(bj, ph, cl)
    red = bool(surface(surf, bj, ph, cl))
    print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, surf))
    fails += not red
sec = lambda bj, i: [s for s in bj['ui']['sections'] if s['id'] == i][0]
mut('an All Apps folder removed', 'S1', lambda bj, ph, cl: bj['ui']['phone_folders'].remove([x for x in bj['ui']['phone_folders'] if x['id'] == 'svc_events'][0]))
mut('an All Apps keyword dropped', 'S1', lambda bj, ph, cl: [x['match_keywords'].pop() for x in bj['ui']['phone_folders'] if x['id'] == 'media_social'])
mut('an All Apps metadata rule dropped', 'S1', lambda bj, ph, cl: [x['match_metadata'].clear() for x in bj['ui']['phone_folders'] if x['id'] == 'media_social'])
mut('a folder leaves the Configs phone dataset', 'S1', lambda bj, ph, cl: ph['folders'].pop(0))
mut('a folder loses its section prefix', 'S1', lambda bj, ph, cl: [x.__setitem__('label', x['label'][1:]) for x in bj['ui']['phone_folders'] if x['id'] == 'svc_search'])
mut('the Profile tile is removed from Setup as well as from Me (#1903)', 'S2', lambda bj, ph, cl: [g['tiles'].remove(t) for g in sec(bj, 'cloud')['tile_groups'] for t in list(g['tiles']) if t['id'] == 'configs-profile'])
mut('Me-Pers goes back to Me-Health (#1903)', 'N', lambda bj, ph, cl: [t.__setitem__('label', 'Me-Health') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'myhealth'])
mut('Me-Prof retargeted away from Cloud Me Prof (#1903)', 'S2', lambda bj, ph, cl: [t.__setitem__('target', 'extapp:cloud-me#page:experiences/work') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'myexperiences'])
mut('the Profile tile returns to the Me row (#1903)', 'N', lambda bj, ph, cl: [g['tiles'].append({'id': 'tools-account', 'label': 'Profile', 'icon': 'ic_p_identities', 'target': 'extapp:cloud-me#page:profile/profile'}) for g in sec(bj, 'cloud')['tile_groups'] if g['title'] == 'Me'])
mut('a phone Me folder is dropped (#1903)', 'S3', lambda bj, ph, cl: [g['folders'].pop(1) for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'Me'])
mut('a phone Me folder keeps the old name (#1903)', 'N', lambda bj, ph, cl: [fo.__setitem__('label', 'Socials') for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'Me' for fo in g['folders'] if fo['label'] == 'Me-Prj'])
mut('the Launcher tile loses its aliases (#1929)', 'S2', lambda bj, ph, cl: [t.pop('aliases') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'configs-launcher'])
mut('the Launcher tile opens another page (#1929)', 'S2', lambda bj, ph, cl: [t.__setitem__('target', 'page:config/controls') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'configs-launcher'])
mut('the Phone Launcher folder loses its aliases (#1929)', 'S3', lambda bj, ph, cl: [fo.pop('aliases') for g in sec(bj, 'phone')['phone_app_groups'] for fo in g.get('folders', []) if fo['label'] == 'Launcher'])
mut('a Cloud tile removed', 'S2', lambda bj, ph, cl: [g['tiles'].pop(0) for g in sec(bj, 'cloud')['tile_groups'] if g['title'] == CITY])
mut('a Cloud tile retargeted', 'S2', lambda bj, ph, cl: [t.__setitem__('target', 'extapp:cloud-news') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'shopping'])
mut('the Cloud Social tile loses the mySocials URL (#1904)', 'N', lambda bj, ph, cl: [t.__setitem__('target', 'extapp:cloud-news') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'social'])
mut('Search leaves the Cloud tiles', 'S2', lambda bj, ph, cl: [g['tiles'].remove(t) for g in sec(bj, 'cloud')['tile_groups'] for t in list(g['tiles']) if t['id'] == 'search'])
mut('a Phone quickmark folder removed', 'S3', lambda bj, ph, cl: [g['folders'].remove(fo) for g in sec(bj, 'phone')['phone_app_groups'] for fo in list(g.get('folders', [])) if fo['label'] == 'Hotels'])
mut('a Phone quickmark package removed', 'S3', lambda bj, ph, cl: [fo['packages'].pop() for g in sec(bj, 'phone')['phone_app_groups'] for fo in g.get('folders', []) if fo['label'] == 'Money'])
mut('the Phone Search folder dropped on the move to AGI', 'S3', lambda bj, ph, cl: [g['folders'].pop() for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'AGI'])
mut('a Cloud smart folder removed', 'S4', lambda bj, ph, cl: cl['folders'].pop(7))
mut('a Cloud smart folder renamed', 'S4', lambda bj, ph, cl: cl['folders'][3].__setitem__('label', 'Renamed'))
mut('Leisure shares the City prefix', 'N', lambda bj, ph, cl: [s.__setitem__('prefix', '+') for s in bj['ui']['phone_sections'] if s['title'] == LEIS])
mut('the Cloud Social tile retargeted', 'N', lambda bj, ph, cl: [t.__setitem__('target', 'page:phone/apps') for g in sec(bj, 'cloud')['tile_groups'] for t in g['tiles'] if t['id'] == 'social'])
mut('the smart folders fall back to the old sections', 'N', lambda bj, ph, cl: [x.__setitem__('section', 'Media') for x in cl['folders'] if x['id'] == 'news'])
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
