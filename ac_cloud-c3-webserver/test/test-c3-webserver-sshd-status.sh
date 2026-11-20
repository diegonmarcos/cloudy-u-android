#!/usr/bin/env bash
# #1746 the SSHd page shows the REAL state of each sshd, never a flag or "a port accepted a connection".
#   S1 the verdict, the banner parser and the check runner are in server/src/sshd_status.rs and door.rs derives every door's state from it
#   S2 the shell draws a dot + word per server from status.verdict (green / amber / red / grey), a row per server, the checks, and Restart / Re-check / Copy ssh command
#   S3 a door whose server sent no status is GREY, even when its old `state` says "listening" (the bug: the page trusted the state word)
#   S4 rule 16 parity: every row of the previous door view (doorFacts) is still drawn
#   S5 the native page reads the same keys (SshdStatus.kt) and the Home strip uses the verdict
#   S6 the shared vectors file drives the Rust test, the webserver Kotlin test and the SuperApp strip test
# Each rule is proven red by a mutation.
set -u
cd "$(dirname "$0")/.." || exit 2
SHELL_HTML=server/ui/shell.html
check() { # $1 = shell.html, $2 = app dir
  python3 - "$2" <<'PY' || return 1
import re, sys
app = sys.argv[1]
e = []
st = open(app + '/server/src/sshd_status.rs').read()
dr = open(app + '/server/src/door.rs').read()
for needle in ('pub fn verdict(', 'pub fn parse_ident(', 'pub fn probe_addr(', 'pub fn mesh_addr(', 'sshd-verdict-vectors.json'):
    if needle not in st: e.append('S1 sshd_status.rs lacks ' + needle)
if 'sshd_status::status_json' not in dr or 'state_real(verdict' not in dr: e.append('S1 door.rs must derive the state from the real verdict')
if 'state_of(d.port.is_some(), up, n' in dr: e.append('S1 door.rs still derives the state from a bare connect')
kt = open(app + '/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/SshdStatus.kt').read()
page = open(app + '/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/SshdPage.kt').read()
home = open(app + '/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/HomePage.kt').read()
for needle in ('"verdict"', 'fun sshRow(', 'fun sshChecks(', 'fun sshExpanded('):
    if needle not in kt: e.append('S5 SshdStatus.kt lacks ' + needle)
for needle in ('c3_door_restart', 'c3_door_recheck', 'c3_door_copy_ssh', 'sshRow(d)'):
    if needle not in page: e.append('S5 SshdPage.kt lacks ' + needle)
if 'sshState(x)' not in home + open(app + '/src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/StatusPage.kt').read(): e.append('S5 the Home strip must read the verdict')
print('\n'.join(e)); sys.exit(1 if e else 0)
PY
  node - "$1" <<'JS' || return 1
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(html);
const mod = { exports: {} };
new Function('module', m[1] + '\n')(mod);
const S = mod.exports;
const chk = (id, req, ok, d) => ({ id, required: req, ok, detail: d, rtt_ms: 2, at_ms: 1700000000000 });
const mk = (id, v, label, failed, mesh, checks, state) => ({ id, title: id, app: 'a', package: 'p', engine: 'OpenSSH', banner: '', port: 8025, bind: 'x', bind_state: 'mesh-only', state, auth: 'pubkey', authorized_keys: null, authorized_keys_note: 'n', seen_up_s: 65, sessions: [], sessions_note: '', note: 'n', mesh_only: true, user: 'u', pid: null, pid_note: 'other', host_key: null, host_key_note: 'x', auth_methods: ['publickey'], keys_count: null, config_path: '/x', log_tail: [], log_note: '', control: { available: true, note: 'c', explicit: null }, status: v ? { verdict: v, label, failed, reason: 'r', mesh_reachable: mesh, mesh_ip: '10.0.0.7', lan_ip: null, ssh_command: 'ssh -p 8025 u@10.0.0.7', checks } : undefined });
const data = { doors: [
  mk('webserver', 'green', 'UP', [], true, [chk('port', true, true, 'a'), chk('loopback', true, true, 'b'), chk('mesh', true, true, 'c')], 'listening'),
  mk('nix', 'amber', 'PARTIAL', ['mesh'], false, [chk('loopback', true, true, 'b'), chk('mesh', true, false, 'refused')], 'partial'),
  mk('termux', 'red', 'OFF', ['port'], null, [chk('port', true, false, 'refused')], 'down'),
  mk('stale', null, '', [], null, [], 'listening')], other: [], wanted: true, keep_alive: true, mesh_cidrs: ['10.0.0.0/24'] };
const out = S.renderSshd(data), bad = [];
for (const s of ['data-verdict="green"', 'data-verdict="amber"', 'data-verdict="red"', 'data-verdict="grey"', 'PARTIAL: mesh failed', 'mesh yes', 'mesh no', 'Banner on mesh (wg0)', 'Copy ssh command', '>Restart<', '>Re-check<', 'id="door-nix"', 'data-srow="nix"'])
  if (!out.includes(s)) bad.push('S2 ' + s);
if (S.sshVerdict(data.doors[3]) !== 'grey') bad.push('S3 a door without status must be grey, not green');
if (S.sshWord(data.doors[3]) !== 'NOT CHECKED') bad.push('S3 word of an unchecked door');
for (const f of S.doorFacts(data.doors[0])) if (!out.includes(f[0])) bad.push('S4 lost row ' + f[0]);
console.log(bad.join('\n')); process.exit(bad.length ? 1 : 0);
JS
}
check "$SHELL_HTML" . || { echo FAIL; exit 1; }
for f in data/sshd-verdict-vectors.json; do python3 -c "import json,sys;d=json.load(open('$f'));assert len(d['cases'])>=10 and len(d['banners'])>=6" || { echo "FAIL S6 vectors"; exit 1; }; done
grep -q 'sshd-verdict-vectors' ../aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/launcher/SshdVerdictTest.kt 2>/dev/null || [ ! -d ../aa_cloud-superapp ] || { echo "FAIL S6 the SuperApp strip test must read the shared vectors"; exit 1; }
echo "ok: the SSHd page draws the real verdict per server"
if [ "${NO_MUTATE:-}" != 1 ]; then
  T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
  mut() { # name, sed expr on shell.html
    cp "$SHELL_HTML" "$T/s.html"; sed -i "$2" "$T/s.html"
    if cmp -s "$SHELL_HTML" "$T/s.html"; then echo "FAIL mutation $1 did not change the file"; exit 1; fi
    if check "$T/s.html" . >/dev/null 2>&1; then echo "FAIL mutation $1 stayed green"; exit 1; else echo "PASS mutation turns red: $1"; fi
  }
  mut "no status trusted as green" "s/return v === 'green' || v === 'amber' || v === 'red' ? v : 'grey'/return d.state === 'listening' ? 'green' : 'grey'/"
  mut "failed checks not named" "s/sshVerdict(d) === 'amber' \&\& d.status.failed/false \&\& d.status.failed/"
  mut "mesh column dropped" "s/mesh ' + sshMesh(d) + '<\/span>/<\/span>/"
  mut "Restart dropped" "s/>Restart</></"
fi
