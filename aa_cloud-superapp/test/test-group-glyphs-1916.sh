#!/usr/bin/env bash
# Tester (#1916): the Cloud quickmark group glyphs and the Data / Primary membership, per surface, as MOVES (rule 16).
#   G1  Cloud quickmarks (ui.sections[cloud].tile_groups): group order, glyph per group declared as data (`glyph`), Data and Primary
#       membership; Drive, Browser and Wallet moved Primary -> Data (same tiles, ids, targets).
#   G2  the renderer draws the declared glyph before the title (Sections.TileGroup.glyph, GroupedTilesFragment.GroupTitle), nothing hardcoded.
#   G3  Phone quickmarks (phone_app_groups): same order and membership, folders moved with their packages.
#   G4  Phone All Apps (phone_folders + phone_sections + launcher-folders/phone.json): Browser and Wallet carry the Data prefix, Primary and Data
#       folder order, section order, orders blocked by section position.
#   G5  Cloud Smart Folders (launcher-folders/cloud.json): Drive, Browser, Wallet in Data, sections in the same order.
# Each surface is then mutated and must turn red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
root = sys.argv[1]; A = root + '/aa_cloud-superapp/'
BJ = json.load(open(A + 'build.json'))
PH = json.load(open(root + '/data/fleet/launcher-folders/phone.json'))
CL = json.load(open(root + '/data/fleet/launcher-folders/cloud.json'))
KT = open(A + 'app/src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt').read()
SC = open(A + 'app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt').read()
CITY, LEIS = 'Services · City', 'Services · Leisure'
ORDER = ['Data', 'Communication', 'Primary', 'Me', CITY, LEIS, 'AGI', 'Setup', 'Projects']
GLYPH = {'Data': '△', 'Communication': '○', 'Primary': '□', CITY: '○', LEIS: '○', 'AGI': '⬡'}
CLOUD_DATA = ['Drive', 'Browser', 'Gallery', 'Notes', '|', 'Me', 'Tasks', 'Vault', 'Wallet', '|', 'Office']
CLOUD_PRIMARY = ['Navigation', 'Camera', 'Canvas', 'Keyboard', 'Calc']
PHONE_DATA = ['Drive', 'Browser', 'Gallery', 'Notes', 'Agenda', 'Vault', 'Wallet', 'Office']
PHONE_PRIMARY = ['Nav', 'Camera', 'Canvas', 'Keyboard', 'Calc']
ALL_DATA = ['prod_storage', 'prod_web', 'data_gallery', 'prod_docs', 'prod_plan', 'data_vault', 'prod_wallet', 'prod_office']
ALL_PRIMARY = ['prod_maps', 'prod_camera', 'prod_canvas', 'sys_keyboards', 'prod_calc', 'prod_utils']
SEC_ORDER = ['Tools · Data', 'Tools · Communication', 'Tools · Primary', 'Tools · Me', CITY, LEIS, 'AGI', 'Setup', 'Projects']
sec = lambda bj, i: [s for s in bj['ui']['sections'] if s['id'] == i][0]

def surface(name, bj, ph, cl, kt=KT, sc=SC):
    f = []
    if name == 'G1':
        tg = sec(bj, 'cloud')['tile_groups']; by = {g['title']: g for g in tg}
        if [g['title'] for g in tg] != ORDER: f.append('Cloud group order %s' % [g['title'] for g in tg])
        for t, g in GLYPH.items():
            if by[t].get('glyph') != g: f.append('Cloud group %s glyph %r, want %r' % (t, by[t].get('glyph'), g))
        for t in ('Me', 'Projects', 'Setup'):
            if by[t].get('glyph'): f.append('Cloud group %s must carry no glyph' % t)
        if [t['label'] for t in by['Data']['tiles']] != CLOUD_DATA: f.append('Cloud Data row %s' % [t['label'] for t in by['Data']['tiles']])
        if [t['label'] for t in by['Primary']['tiles']] != CLOUD_PRIMARY: f.append('Cloud Primary row %s' % [t['label'] for t in by['Primary']['tiles']])
        tg_ = {t['label']: t.get('target') for t in by['Data']['tiles'] if not t.get('separator')}
        for lab, tgt in (('Drive', 'extapp:cloud-drive'), ('Browser', 'extapp:cloud-browser'), ('Wallet', 'extapp:cloud-wallet')):
            if tg_.get(lab) != tgt: f.append('moved tile %s lost its target %s' % (lab, tgt))
    elif name == 'G2':
        if 'val glyph: String = ""' not in sc or 'optString("glyph"' not in sc: f.append('Sections.TileGroup does not parse the declared glyph')
        if 'GroupTitle(g.title, g.glyph)' not in kt: f.append('GroupedTilesFragment does not pass the group glyph to GroupTitle')
        if '"$glyph $text"' not in kt: f.append('GroupTitle does not draw the glyph before the title')
        if any(g in kt for g in set(GLYPH.values())): f.append('a glyph is hardcoded in the renderer (it must be declared data)')
    elif name == 'G3':
        pg = sec(bj, 'phone')['phone_app_groups']; by = {g['title']: g for g in pg}
        if [g['title'] for g in pg] != ORDER: f.append('Phone group order %s' % [g['title'] for g in pg])
        if [x['label'] for x in by['Data']['folders']] != PHONE_DATA: f.append('Phone Data folders %s' % [x['label'] for x in by['Data']['folders']])
        if [x['label'] for x in by['Primary']['folders']] != PHONE_PRIMARY: f.append('Phone Primary folders %s' % [x['label'] for x in by['Primary']['folders']])
        for lab in ('Drive', 'Browser', 'Wallet'):
            fo = [x for x in by['Data']['folders'] if x['label'] == lab]
            if not fo or not fo[0]['packages']: f.append('Phone quickmark %s moved without its packages' % lab)
    elif name == 'G4':
        ui = bj['ui']; fo = {x['id']: x for x in ui['phone_folders']}
        if [s['title'] for s in ui['phone_sections']] != SEC_ORDER: f.append('phone_sections order %s' % [s['title'] for s in ui['phone_sections']])
        pre = {s['title']: s['prefix'] for s in ui['phone_sections']}
        for ids, title in ((ALL_DATA, 'Tools · Data'), (ALL_PRIMARY, 'Tools · Primary')):
            for i in ids:
                if fo[i]['label'][0] != pre[title]: f.append('All Apps folder %s is not in %s: %s' % (i, title, fo[i]['label']))
            o = [int(fo[i]['order']) for i in ids]
            if o != sorted(o): f.append('%s folders not in the declared order: %s' % (title, o))
        pos = {s['prefix']: n + 1 for n, s in enumerate(ui['phone_sections'])}
        for x in ui['phone_folders']:
            if x['label'][0] in pos and int(x['order']) // 100 != pos[x['label'][0]]: f.append('%s order %s not in block %d' % (x['id'], x['order'], pos[x['label'][0]]))
        sp = {x['id']: x for x in ph['folders']}
        for i, x in fo.items():
            if i in sp and (sp[i]['label'] != x['label'] or int(sp[i]['order']) != int(x['order'])): f.append('Configs phone dataset disagrees on %s' % i)
        for i in ('prod_web', 'prod_wallet'):
            if sp[i]['section'] != 'Tools · Data': f.append('Configs phone dataset keeps %s out of Data' % i)
    elif name == 'G5':
        secs = []
        for x in cl['folders']:
            if x['section'] not in secs: secs.append(x['section'])
        if secs != ORDER: f.append('Cloud smart folder sections %s' % secs)
        now = {x['id']: x['section'] for x in cl['folders']}
        for i in ('drive', 'web', 'wallet'):
            if now.get(i) != 'Data': f.append('Cloud smart folder %s is not in Data' % i)
    return f

fails = 0
def report(n, title):
    global fails
    r = surface(n, BJ, PH, CL)
    print('  %s: %s%s' % ('PASS' if not r else 'FAIL', title, '' if not r else ' -> ' + '; '.join(r[:5]))); fails += bool(r)
report('G1', 'G1 Cloud quickmarks: order, glyphs as data, Data and Primary membership')
report('G2', 'G2 renderer draws the declared glyph before the title')
report('G3', 'G3 Phone quickmarks: order and membership')
report('G4', 'G4 Phone All Apps: sections, Data / Primary folders, order blocks, dataset')
report('G5', 'G5 Cloud Smart Folders: Data holds Drive, Browser, Wallet; section order')
print('== mutants: each must turn its surface red ==')
def mut(name, s, fn, **kw):
    global fails
    bj, ph, cl = copy.deepcopy(BJ), copy.deepcopy(PH), copy.deepcopy(CL)
    r = fn(bj, ph, cl)
    red = bool(surface(s, bj, ph, cl, **(r if isinstance(r, dict) else {})))
    print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
grp = lambda bj, t: [g for g in sec(bj, 'cloud')['tile_groups'] if g['title'] == t][0]
mut('a glyph removed from Data', 'G1', lambda bj, ph, cl: grp(bj, 'Data').pop('glyph'))
mut('the AGI glyph changed', 'G1', lambda bj, ph, cl: grp(bj, 'AGI').__setitem__('glyph', '△'))
mut('Me gets a glyph', 'G1', lambda bj, ph, cl: grp(bj, 'Me').__setitem__('glyph', '○'))
mut('Wallet back in Primary', 'G1', lambda bj, ph, cl: (grp(bj, 'Primary')['tiles'].append([t for t in grp(bj, 'Data')['tiles'] if t['label'] == 'Wallet'][0]), grp(bj, 'Data')['tiles'].remove([t for t in grp(bj, 'Data')['tiles'] if t['label'] == 'Wallet'][0])))
mut('Projects back before Me', 'G1', lambda bj, ph, cl: sec(bj, 'cloud')['tile_groups'].insert(3, sec(bj, 'cloud')['tile_groups'].pop(-1)))
mut('Calc leads Primary', 'G1', lambda bj, ph, cl: grp(bj, 'Primary')['tiles'].insert(0, grp(bj, 'Primary')['tiles'].pop(-1)))
mut('the renderer ignores the glyph', 'G2', lambda bj, ph, cl: {'kt': KT.replace('GroupTitle(g.title, g.glyph)', 'GroupTitle(g.title)')})
mut('the renderer hardcodes a glyph', 'G2', lambda bj, ph, cl: {'kt': KT + '\n// △'})
mut('Phone Drive folder dropped from Data', 'G3', lambda bj, ph, cl: [g['folders'].pop(0) for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'Data'])
mut('Phone Primary gets Wallet', 'G3', lambda bj, ph, cl: [g['folders'].append({'label': 'Wallet', 'packages': []}) for g in sec(bj, 'phone')['phone_app_groups'] if g['title'] == 'Primary'])
mut('Phone Projects before Me', 'G3', lambda bj, ph, cl: sec(bj, 'phone')['phone_app_groups'].insert(3, sec(bj, 'phone')['phone_app_groups'].pop(-1)))
mut('All Apps Browser back to Primary', 'G4', lambda bj, ph, cl: [x.__setitem__('label', '=Browser') for x in bj['ui']['phone_folders'] if x['id'] == 'prod_web'])
mut('All Apps Wallet order out of line', 'G4', lambda bj, ph, cl: [x.__setitem__('order', '105') for x in bj['ui']['phone_folders'] if x['id'] == 'prod_wallet'])
mut('All Apps Projects section before Setup', 'G4', lambda bj, ph, cl: bj['ui']['phone_sections'].insert(7, bj['ui']['phone_sections'].pop(-1)))
mut('Configs dataset keeps Wallet in Primary', 'G4', lambda bj, ph, cl: [x.__setitem__('section', 'Tools · Primary') for x in ph['folders'] if x['id'] == 'prod_wallet'])
mut('smart folder Drive back in Primary', 'G5', lambda bj, ph, cl: [x.__setitem__('section', 'Primary') for x in cl['folders'] if x['id'] == 'drive'])
mut('smart folder sections Projects first', 'G5', lambda bj, ph, cl: cl['folders'].insert(0, [cl['folders'].pop(i) for i, x in enumerate(cl['folders']) if x['section'] == 'Projects'][0]))
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
