#!/usr/bin/env bash
# Tester (#1928): the VPN page (ui.mesh_page) is a strip of subpages per tab, and the reorganisation CUTS NOTHING (rule 16).
#   P1  every control of previous_controls is listed by exactly one subpage; the Profile tab has Profiles | Import-Export | Tunnel | Connection |
#       DNS & Transport | Cloudflare with the ticket's control lists; DNS & Transport carries the link control to Configs > Network > DNS.
#   P2  every row of pages[status].previous_rows is in exactly one status section, and each section is drawn by a Status subpage
#       (Overview | Interface | Peers | Routes | Probes); the Logs tab keeps level, copy, clear and gains export.
#   P3  every former page id resolves (aliases) to a subpage whose sections name it; subpage sections name declared pages.
#   P4  the additions are wired: section Copy / Export (engine ids dispatched, a `report` per subpage covering the dump's headings), the
#       DNS servers in use (MeshPort.dnsServers, AndroidMeshPort), the endpoint in use per peer, a `stamp` per subpage.
# Each is then mutated and must turn red. Static, no device.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FATAL: python3 missing"; exit 2; }
exec python3 -I - "$ROOT" <<'PY'
import json, sys, copy
A = sys.argv[1] + '/aa_cloud-superapp/'
MESH = A + 'app/src/main/java/com/diegonmarcos/superapp/network/mesh/'
KT = {n: open(MESH + n).read() for n in ('MeshStore.kt', 'MeshPort.kt', 'AndroidMeshPort.kt', 'MeshProbeRows.kt', 'MeshReport.kt', 'MeshScreen.kt')}
bj0 = json.load(open(A + 'build.json'))
mp = lambda bj: bj['ui']['mesh_page']
subs = lambda m: [s for t in m['tabs'] for s in t.get('subpages', [])]
HEAD = ['Tunnel', 'Interface', 'DNS', 'Transport', 'Peers', 'Traffic', 'Routes', 'Meshes', 'Reachability', 'Prober', 'Log']
PROFILE = {'profiles': ['provider', 'profile_select', 'profile_diff'],
 'import_export': ['profile_import_clipboard', 'profile_import_vault', 'conf_import', 'profile_export_text', 'profile_export_qr', 'profile_export_folder', 'conf_export'],
 'tunnel': ['tunnel_name', 'address', 'listen_port', 'private_key', 'generate_key', 'mtu', 'keepalive'],
 'connection': ['connect', 'reconnect', 'auto_boot', 'auto_wifi', 'auto_mobile', 'kill_switch', 'vpn_settings'],
 'dns_transport': ['dns_preset', 'dns_fleet_link', 'transport_mode', 'relay_key', 'test_fallbacks'],
 'cloudflare': ['cf_warp_register', 'cf_warp_forget', 'cf_one_enrol']}

def check(n, bj, kt=KT):
    f = []; m = mp(bj); S = subs(m)
    if n == 'P1':
        listed = [c for s in S for c in s.get('controls', [])]
        for c in m['previous_controls']:
            k = listed.count(c)
            if k != 1: f.append('control %s is on %d subpages' % (c, k))
        pt = [t for t in m['tabs'] if t['id'] == 'profile'][0]['subpages']
        if [s['id'] for s in pt] != list(PROFILE): f.append('Profile subpages %s' % [s['id'] for s in pt])
        for s in pt:
            if s.get('controls') != PROFILE.get(s['id']): f.append('%s controls %s' % (s['id'], s.get('controls')))
        if [s['label'] for s in pt] != ['Profiles', 'Import-Export', 'Tunnel', 'Connection', 'DNS & Transport', 'Cloudflare']: f.append('Profile labels')
    elif n == 'P2':
        st = [p for p in m['pages'] if p['id'] == 'status'][0]
        sec = {s['id']: s['rows'] for s in m['status_sections']}
        drawn = [s['status_section'] for s in [t for t in m['tabs'] if t['id'] == 'status'][0]['subpages'] if s.get('status_section')]
        rows = [r for i in drawn for r in sec.get(i, [])]
        for r in st['previous_rows']:
            if rows.count(r) != 1: f.append('row %s drawn %d times' % (r, rows.count(r)))
        if sorted(rows) != sorted(st['rows']): f.append('drawn rows differ from the declared rows')
        if [s['id'] for s in [t for t in m['tabs'] if t['id'] == 'status'][0]['subpages']] != ['overview', 'interface', 'peers', 'routes', 'probes']: f.append('Status subpages')
        lg = [c['id'] for c in [p for p in m['pages'] if p['id'] == 'log'][0]['controls']]
        if lg != ['log_level', 'log_copy', 'log_clear', 'log_export']: f.append('Log controls %s' % lg)
    elif n == 'P3':
        ids = {p['id'] for p in m['pages']}
        for p in m['pages']:
            a = m.get('aliases', {}).get(p['id'])
            hit = [s for t in m['tabs'] for s in t['subpages'] if a and a == t['id'] + '/' + s['id']]
            if not hit or p['id'] not in hit[0]['sections']: f.append('page %s has no alias to a subpage naming it (%r)' % (p['id'], a))
        for s in S:
            for x in s['sections']:
                if x not in ids: f.append('subpage %s names undeclared page %s' % (s['id'], x))
    elif n == 'P4':
        claimed = [h for s in S for h in s.get('report', [])]
        for h in HEAD:
            if claimed.count(h) != 1: f.append('dump heading %s is claimed %d times' % (h, claimed.count(h)))
        ctl = {c['id']: c for p in m['pages'] for c in p['controls']}
        for cid, eng in (('section_copy', 'section.copy'), ('section_export', 'section.export'), ('log_export', 'log.export'), ('dns_fleet_link', 'nav.configsDns')):
            if ctl.get(cid, {}).get('engine') != eng: f.append('control %s lacks engine %s' % (cid, eng))
            if '"%s" to' % eng not in kt['MeshStore.kt']: f.append('MeshStore does not dispatch %s' % eng)
        if 'fun dnsServers' not in kt['MeshPort.kt'] or 'override fun dnsServers' not in kt['AndroidMeshPort.kt']: f.append('dnsServers is not read by AndroidMeshPort')
        if 'endpoint_in_use' not in kt['MeshProbeRows.kt']: f.append('peerFields has no endpoint_in_use')
        if 'servers in use' not in kt['MeshReport.kt']: f.append('the dump has no DNS servers in use')
        for s in S:
            if s['id'] != 'log' and s['id'] not in ('profiles',) and not s.get('stamp') and s.get('status_section'): f.append('subpage %s has no stamp' % s['id'])
    return f

fails = 0
for n, t in (('P1', 'every previous control on exactly one subpage, the Profile lists as ordered'), ('P2', 'every previous status row in exactly one section, drawn by a Status subpage'),
             ('P3', 'former page ids alias to a subpage that names them'), ('P4', 'the additions are wired')):
    r = check(n, bj0); print('  %s: %s %s' % ('PASS' if not r else 'FAIL', n, t + ('' if not r else ' -> ' + '; '.join(r[:5])))); fails += bool(r)
print('== mutants: each must turn its check red ==')
def mut(name, s, fn, kt=None):
    global fails
    bj = copy.deepcopy(bj0); k = dict(KT)
    r = fn(bj)
    if isinstance(r, dict): k.update(r)
    red = bool(check(s, bj, k)); print('  %s: mutant %s turns %s red' % ('PASS' if red else 'FAIL', name, s)); fails += not red
sp = lambda bj, i: [s for s in subs(mp(bj)) if s['id'] == i][0]
mut('a control dropped from Tunnel', 'P1', lambda b: sp(b, 'tunnel')['controls'].remove('mtu'))
mut('a control listed twice', 'P1', lambda b: sp(b, 'connection')['controls'].append('mtu'))
mut('Cloudflare subpage removed', 'P1', lambda b: [t['subpages'].remove(sp(b, 'cloudflare')) for t in mp(b)['tabs'] if t['id'] == 'profile'])
mut('Probes subpage removed', 'P2', lambda b: [t['subpages'].remove(sp(b, 'probes')) for t in mp(b)['tabs'] if t['id'] == 'status'])
mut('a row drawn twice', 'P2', lambda b: [s['rows'].append('dns') for s in mp(b)['status_sections'] if s['id'] == 'probes'])
mut('a row left out of the sections', 'P2', lambda b: [s['rows'].remove('latency') for s in mp(b)['status_sections'] if s['id'] == 'peers'])
mut('Log export dropped', 'P2', lambda b: [p['controls'].pop() for p in mp(b)['pages'] if p['id'] == 'log'])
mut('an alias dropped', 'P3', lambda b: mp(b)['aliases'].pop('controls'))
mut('an alias points at the wrong subpage', 'P3', lambda b: mp(b)['aliases'].__setitem__('controls', 'profile/profiles'))
mut('a heading no subpage reports', 'P4', lambda b: sp(b, 'probes')['report'].remove('Prober'))
mut('the section export engine is not dispatched', 'P4', lambda b: {'MeshStore.kt': KT['MeshStore.kt'].replace('"section.export" to', '"section.exportx" to')})
mut('the endpoint in use leaves the peer fields', 'P4', lambda b: {'MeshProbeRows.kt': KT['MeshProbeRows.kt'].replace('endpoint_in_use', 'endpoint_x')})
print('== RESULT: %d failed ==' % fails)
sys.exit(1 if fails else 0)
PY
