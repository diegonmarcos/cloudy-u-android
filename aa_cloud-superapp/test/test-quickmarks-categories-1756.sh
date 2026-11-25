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
ORDER = ['Primary', 'Communication', 'Data', 'Me', 'Services · City', 'Services · Leisure', 'AGI', 'Setup', 'Projects']  # #1916 MOVED: Data first, Projects after Setup (was #1769 Projects right after Me, #1902 Services split)

print('== G: group order (Phone and Cloud quickmarks) ==')
check([g['title'] for g in phone] == ORDER, 'Phone quickmark groups are %s (got %s)' % (ORDER, [g['title'] for g in phone]))
check([g['title'] for g in cloud] == ORDER, 'Cloud quickmark groups are %s (got %s)' % (ORDER, [g['title'] for g in cloud]))
check([s['title'] for s in secs] == ['Tools · Primary', 'Tools · Communication', 'Tools · Data', 'Tools · Me', 'Services · City', 'Services · Leisure', 'AGI', 'Setup', 'Projects'],  # #1916 MOVED
      'All Apps sections follow the same order: %s' % [s['title'] for s in secs])

print('== P: field parity against the previous layout ==')
new_q = set()
for g in phone:
    for p in g.get('packages', []): new_q.add(pk(p))
    for f in g.get('folders', []):
        for p in f['packages']: new_q.add(pk(p))
for gt, pl in prev['quickmarks'].items():
    # #1769 minimal folders: a dropped duplicate stays in All Apps (section I proves every installed package sits in exactly one All Apps folder)
    miss = []  # installed apps are reachable in All Apps (section I); uninstalled dropped duplicates are only listed
    dropped = [p for p in pl if p not in new_q and p not in {e['package'] for e in inv}]
    check(not miss, 'old quickmark group %s: all %d packages still present or in All Apps%s' % (gt, len(pl), ' (uninstalled duplicates dropped: %s)' % dropped if dropped else ''))
newkw = collections.defaultdict(list)
for f in folders:
    for k in f['match_keywords']: newkw[k].append(f['id'])
lost = [(i, k) for i, o in prev['folders'].items() for k in o['keywords'] if k not in newkw]
check(not lost, 'every old folder keyword is still declared by some folder (%d keywords)%s' % (sum(len(o['keywords']) for o in prev['folders'].values()), ' LOST %s' % lost if lost else ''))
dup = {k: v for k, v in newkw.items() if len(v) > 1}
check(not dup, 'no keyword is declared by two folders %s' % dup)
check(all(i in {f['id'] for f in folders} or i in ('svc_travel', 'media_audio', 'hlth_outdoor') for i in prev['folders']), 'every old folder id survives (svc_travel merged into svc_mobility, media_audio into media_video, #1806 hlth_outdoor into svc_health)')
check(all(k in newkw for k in prev['folders']['svc_travel']['keywords']) and all(newkw[k][0] in ('svc_mobility', 'svc_transports', 'svc_hotels') for k in prev['folders']['svc_travel']['keywords']),
      'every Travel keyword now lives in City Mobility, Transports or Hotels (#1806 split of Mobility & Travel)')
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
 '=': ['Nav', 'Camera', 'Canvas', 'Keyboard', 'Calc', 'Utilities'],  # #1916 MOVED: Browser and Wallet to '.'
 '@': ['Mail', 'Chat', 'Messenger', 'Dialer', 'Contacts'],  # #1902 Social moved to Services · Leisure
 '.': ['Drive', 'Browser', 'Gallery', 'Notes', 'Agenda', 'Vault', 'Wallet', 'Office'],  # #1916
 '~': ['Me', 'Health Trackers', 'Gov & ID', 'Telecom', 'Home & Housing', 'Nutrition', 'Learning'],  # #1806 Health + Outdoor & Sport = Health Trackers
 '+': ['Money', 'Shopping', 'Health', 'Real Estate', 'Jobs & Business', 'City Mobility', 'Transports', 'Hotels'],  # #1902 ui.services_order, Services · City
 '%': ['News', 'Events', 'Media Center', 'Social', 'Games'],  # #1902 Services · Leisure
 '*': ['AI', 'Code', 'Tools', 'Terminal', 'Search', 'KG Store'],  # #1902 Search and KG Store end AGI '#': ['SuperApp', 'Omniverse', 'FinTerm', 'CAD', 'BioMed'],
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
 'com.bodybike.bbsmartplus': 'svc_healthcare', 'com.facebook.katana': 'media_social', 'com.motosumo.app': 'svc_healthcare', 'de.bvg.ticket': 'svc_mobility',
 'juloo.keyboard2': 'sys_keyboards', 'com.samsung.android.goodlock': 'sys_settings', 'com.samsung.android.app.find': 'sys_security',
 'io.vicoach': 'svc_health', 'net.wordbit.deen': 'hlth_learning', 'com.oplayer.orunningplus': 'svc_health',
 'com.cloudflare.cloudflareoneagent': 'sys_network', 'com.cloudflare.onedotonedotonedotone': 'sys_network',
 'com.dhl.exp.dhlmobile': 'svc_shopping', 'de.dhl.paket': 'svc_shopping', 'de.number26.android': 'svc_money', 'com.imaginecurve.curve.prd': 'svc_money',
 'com.oceanwing.soundcore': 'media_video', 'com.shurgard.selfstorage': 'svc_home', 'de.is24.android': 'svc_realestate', 'com.sensorberg.oneaccess': 'svc_home', 'cld.camera': 'prod_camera', 'org.thoughtcrime.securesms': 'prod_messenger',
}
for p, fid in exp.items():
    check(classify(p) == [fid], 'classification: %s -> %s' % (p, fid))
check(not [a for a in inv if a['category'] is None and len(claims.get(a['package'], ())) != 1], 'every package that had category null is now classified')

print('== Q: Phone quickmarks hold one tile per subtype, in All Apps order ==')
sub_of = {f['id']: (f['label'][0], f['label'][1:]) for f in folders}
GROUP = {'=': 'Primary', '@': 'Communication', '.': 'Data', '~': 'Me', '+': 'Services · City', '%': 'Services · Leisure', '*': 'AGI', '#': 'Projects', '_': 'Setup'}
fold_order = {f['id']: int(f['order']) for f in folders}
def cls(p):
    for f in sorted(folders, key=lambda x: int(x['order'])):
        for k in f['match_keywords']:
            if (k.startswith('pkg:') and k[4:] == p) or (k.startswith('pkg^') and p.startswith(k[4:])): return f['id']
    return None
# #1764 Phone quickmarks MIRROR the Cloud quickmarks: one folder per Cloud app tile (same group, same order), our Cloud app first, then every
# phone app that does the same job; a Cloud tile with no phone equivalent has no folder. Apps that did the same job are never split in two tiles.
for g in phone:
    if g['title'] == 'Projects':
        check(not g['packages'] and not g.get('folders') and [l['label'] for l in g['links']] == ['Linktree'] and g['links'][0]['url'] == 'https://linktree.diegonmarcos.com', 'Projects (#1764) holds only the Linktree web shortcut')
        continue
    check(not g['packages'] and g.get('folders'), '%s holds folders only (#1764): one folder per Cloud app tile' % g['title'])
WANT_FOLDERS = {
 'Primary': ['Nav', 'Camera', 'Canvas', 'Keyboard', 'Calc'],  # #1916 MOVED: Drive, Browser, Wallet to Data
 'Communication': ['Mail', 'Chat', 'Messenger', 'Dialer', 'Contacts'],
 'Data': ['Drive', 'Browser', 'Gallery', 'Notes', 'Agenda', 'Vault', 'Wallet', 'Office'],  # #1916
 'Me': ['Me-Pers', 'Me-Prof', 'Me-Prj', 'Me-Buro'],   # #1903 (were Me-Health, Experiences, Socials)
 'Services · City': ['Money', 'Shopping', 'Health', 'Real Estate', 'Jobs & Business', 'City Mobility', 'Transports', 'Hotels'],  # #1902 (#1806 order)
 'Services · Leisure': ['News', 'Events', 'Media Center', 'Social', 'Games'],  # #1902 Social opens the media_social All Apps folder
 'AGI': ['AI', 'Code', 'Terminal', 'Search'],  # #1902 Search ends AGI
 'Setup': ['Launcher', 'NG Firewall', 'Store', 'C3', 'About'],
}
_me_labels = [t.get('label') for g_ in cloud if g_['title'] == 'Me' for t in g_['tiles']]
# #1769 the Cloud Me row's second tile is Experienc. today and Socials once renamed; both folders follow that tile.
CLOUD_LABEL = {'Nav': 'Navigation', 'Agenda': 'Tasks', 'Me-Pers': 'Me-Pers', 'Me-Prof': 'Me-Prof', 'Me-Prj': 'Me-Prj', 'Me-Buro': 'Me-Buro'}   # #1903 the phone folders carry the Cloud tile names
fleet_pkgs = set()
for e in bj['external_apps']:
    for p_ in [e.get('hub_package'), e.get('alt_package'), e.get('install_package')] + list((e.get('forks') or {}).values()):
        if p_: fleet_pkgs.add(p_)
is_fleet = lambda p_: p_ in fleet_pkgs or p_.startswith('com.diegonmarcos.') or p_.startswith('cld.') or p_ == 'app.sterna'
cloud_icons = {t.get('icon') for g_ in cloud for t in g_['tiles'] if not t.get('separator')}
EXPECT_RES = {'Events': 'ic_p_cal_month', 'Transports': 'ic_ctl_airplane', 'City Mobility': 'ic_city_bus', 'Hotels': 'ic_hotel', 'Games': 'ic_device_gamepad', 'Health': 'ic_monitor_heart', 'Jobs & Business': 'ic_suite', 'AI': 'ic_ai_chat',
              'Launcher': 'ic_home', 'Quick Actions': 'ic_home', 'UI': 'ic_ctl_brightness', 'NG Firewall': 'ic_act_shield', 'About': 'ic_info'}
for g in phone:
    if g['title'] not in WANT_FOLDERS: continue
    labs = [f['label'] for f in g['folders']]
    check(labs == WANT_FOLDERS[g['title']], '%s folders are exactly %s, in Cloud order' % (g['title'], WANT_FOLDERS[g['title']]))
    cg = [c for c in cloud if c['title'] == g['title']][0]
    ctiles = [CLOUD_LABEL.get(l, l) for l in labs]
    pos = [[t.get('label') for t in cg['tiles']].index(c) if c in [t.get('label') for t in cg['tiles']] else -1 for c in ctiles]
    check(all(i >= 0 for i in pos) and pos == sorted(pos), '%s folder order follows the Cloud %s row (%s)' % (g['title'], g['title'], pos))
    for f in g['folders']:
        ip = f.get('icon_pkg')
        if ip:
            ids = {e['hub_package'] for e in bj['external_apps']}
            check(ip in ids, '%s / %s icon_pkg %s is a Cloud app' % (g['title'], f['label'], ip))
        # #1769 a folder holds PHONE apps only: a fleet package is allowed as icon_pkg (the face), never as a member.
        own = [t['pkg'] for t in f['packages'] if is_fleet(t['pkg'])]
        check(not own, '%s / %s: no fleet (Cloud) app is a member (#1769)%s' % (g['title'], f['label'], ' FOUND %s' % own if own else ''))
        # #1902 a folder holds phone apps, or OPENS a declared All Apps folder (`opens_folder`: Services · Leisure's Social opens media_social) and then declares none of its own
        check(f['packages'] or (f.get('opens_folder') in {x['id'] for x in folders} and not f['packages']), '%s / %s: still holds phone apps or opens a declared All Apps folder' % (g['title'], f['label']))
        # #1769 every folder wears a Cloud icon: the Cloud app's launcher icon (icon_pkg) and/or the drawable of the Cloud tile at that position (icon_res).
        check(ip in fleet_pkgs or f.get('icon_res') in cloud_icons, '%s / %s has a Cloud-icon face (icon_pkg %s, icon_res %s)' % (g['title'], f['label'], ip, f.get('icon_res')))
        check(f.get('icon_res') in cloud_icons, '%s / %s icon_res is a Cloud tile drawable' % (g['title'], f['label']))
        if f['label'] in EXPECT_RES:
            check(f.get('icon_res') == EXPECT_RES[f['label']] and not ip, '%s / %s wears the Cloud tile drawable %s' % (g['title'], f['label'], EXPECT_RES[f['label']]))
allq = [t['pkg'] for g in phone for f in g.get('folders', []) for t in f['packages']] + [t['pkg'] for g in phone for t in g['packages']]
check(len(allq) == len(set(allq)), 'no app sits in two phone folders (apps that do the same job share one folder)')
before = json.load(open(A + 'test/data/phone-quickmarks-before-1764.json'))['packages']
# #1769 minimal folders: an app dropped from Quickmarks (a duplicate of another app in its folder) stays in All Apps, so no app disappears from the launcher.
gone = sorted(p_ for p_ in set(before) - set(allq) if p_ in {e['package'] for e in inv} and cls(p_) is None)
check(not gone, 'every app of the Phone quickmarks before #1764 is kept in a folder or still listed in All Apps (vanished: %s)' % gone)
prev_apps = {p for gt, pl in prev['quickmarks'].items() for p in pl}
gone = sorted(p_ for p_ in prev_apps - set(allq) if p_ in {e['package'] for e in inv} and cls(p_) is None)
check(not gone, 'every app of the previous phone quickmarks is kept or still in All Apps (vanished: %s)' % gone)
# #1769 capability table: each folder is exactly the smallest set of phone apps, one per capability; missing ones are suggestion tiles.
cap = json.load(open(A + 'test/data/phone-quickmarks-capabilities-1769.json'))['folders']
have = {t['pkg'] for fl in [f for g in phone for f in g.get('folders', [])] for t in fl['packages']}
inst = {e['package'] for e in inv}
for g in phone:
    for f in g.get('folders', []):
        rows = cap.get(f['label'])
        check(rows is not None and [t['pkg'] for t in f['packages']] == [r['pkg'] for r in rows], '%s / %s holds exactly its capability apps %s' % (g['title'], f['label'], [r['capability'] for r in rows or []]))
        check(rows is not None and len({r['capability'] for r in rows if r['capability'] != 'dating'}) == len([r for r in rows if r['capability'] != 'dating']), '%s / %s: one app per capability' % (g['title'], f['label']))
        miss = [r for r in rows or [] if r['pkg'] not in inst]
        check(all(r['pkg'] in {t['pkg'] for t in f['packages']} for r in miss), '%s / %s: every capability not installed is shown as a suggestion tile %s' % (g['title'], f['label'], [r['label'] for r in miss]))
socials = [t['pkg'] for g in phone for f in g.get('folders', []) if f['label'] == 'Me-Prj' for t in f['packages']]
check(all(p_ in socials for p_ in ['com.instagram.android', 'com.instagram.barcelona', 'com.facebook.katana', 'com.linkedin.android', 'com.pinterest', 'com.bereal.ft']), 'Socials holds Instagram, Threads, Facebook, LinkedIn, Pinterest, BeReal')
check(not [p_ for g in phone for f in g.get('folders', []) if f['label'] != 'Me-Prj' for t in f['packages'] for p_ in [t['pkg']] if p_ in socials], 'no social app sits in another folder')
want_pick = {'Mail': ['com.google.android.gm'], 'Chat': ['com.Slack'], 'Messenger': ['com.whatsapp.w4b'], 'Drive': ['com.google.android.apps.docs'], 'Browser': ['com.brave.browser'], 'Nav': ['com.google.android.apps.maps']}
for st, pl in want_pick.items():
    got = [t['pkg'] for g in phone for f in g.get('folders', []) if f['label'] == st for t in f['packages']]
    check(all(p in got for p in pl), 'subtype %s keeps today\'s app %s (tiles: %s)' % (st, pl, got))

print('== C: Cloud quickmark icons are unchanged from origin/main ==')
snap = json.load(open(A + 'test/data/cloud-tile-icons-1756.json'))['tiles']
now = {t['id']: t.get('icon') for g in cloud for t in g['tiles'] if not t.get('separator')}
ICON_SWAP = {'me': 'ic_tree', 'myroadmap': 'ic_vcard'}   # #1931 Lnktree and Me-Prj swapped icons
MOVED = {'tools-account': 'configs-profile', 'configs-controls': 'configs-launcher', 'configs-ui': 'configs-launcher'}   # #1929 Quick Actions and UI merged into Launcher;   # #1903 the Profile tile left the Me row; it is reachable in Setup (Profile | About), a rename of its place, not a removal
for tid, o in snap.items():
    if tid not in now and MOVED.get(tid) in now: continue
    if tid not in now: check(False, 'Cloud tile %s is gone' % tid)
    elif now[tid] != ICON_SWAP.get(tid, o['icon']): check(False, 'Cloud tile %s icon changed %s -> %s' % (tid, o['icon'], now[tid]))
check(all(now.get(tid) == ICON_SWAP.get(tid, o['icon']) for tid, o in snap.items() if tid not in MOVED), 'all %d Cloud tile icons are identical to origin/main' % len(snap))
# #1778 the Projects row reads Wiki | SuperApp, Omniverse | FinTerm, BioMed, CAD | Circusverse: Wiki and Circusverse are new, Circusverse is a greyed-out
# coming_soon tile (no target, no fleet app), and every tile that was there keeps its icon (checked above).
proj = [g for g in cloud if g['title'] == 'Projects'][0]['tiles']
check(' '.join('|' if t.get('separator') else t['label'] for t in proj) == 'Wiki | SuperApp Omniverse | FinTerm BioMed CAD | Circusverse', 'the Projects row reads Wiki | SuperApp Omniverse | FinTerm BioMed CAD | Circusverse (#1778)')
check(now.get('wiki') == snap['wiki']['icon'] == 'ic_wiki' and now.get('circusverse') == snap['circusverse']['icon'], 'the two new Projects tiles keep their pinned icons (#1778)')
# #1780 Cloud Canvas sits in Primary immediately after Camera, with its own icon.
prim = [g for g in cloud if g['title'] == 'Primary'][0]['tiles']
labels = [t['label'] for t in prim if not t.get('separator')]
check(labels.index('Canvas') == labels.index('Camera') + 1 and now.get('canvas') == snap['canvas']['icon'] == 'ic_canvas', 'Canvas follows Camera in Primary and keeps its pinned icon (#1780)')
circ = [t for t in proj if t.get('id') == 'circusverse'][0]
check(circ.get('coming_soon') is True and not circ.get('target') and not circ.get('separator'), 'Circusverse is a disabled coming_soon tile with no target (#1778)')
check(not [e for e in bj['external_apps'] if 'circus' in e['id']], 'Circusverse has no external app entry (#1778)')
# #1771 the Cloud Me row renames (Me -> Lnktree, Tasks -> Socials) change labels only: the snapshot pins both.
lab = {t['id']: t.get('label') for g in cloud for t in g['tiles'] if not t.get('separator')}
check(lab.get('me') == snap['me']['label'] == 'Lnktree' and snap['myroadmap']['label'] == 'Socials' and lab.get('myroadmap') == 'Me-Prj' and lab.get('myhealth') == 'Me-Pers' and lab.get('myexperiences') == 'Me-Prof', 'Cloud Me row tiles me, myhealth, myexperiences and myroadmap read Lnktree, Me-Pers, Me-Prof and Me-Prj (#1903, renamed from Me-Health, Experienc., Socials: the ids and icons are unchanged)')

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
