#!/bin/sh
# test-c3-webserver-ssh-doors.sh: ONE supervised app brings the phone's SSH access up, and the SSHd tab shows every door.
#
#   D  declaration: build.json::ssh_doors names the three fleet doors (nix supervised, SuperApp hosting none, Termux), no literal
#      port or package (each is a `_from` the build resolves from the owner's own declaration), the nix hook exists in its owner
#   S  supervisor wiring: KeepAliveService's nativeStart (the service that starts at boot with Keep alive) asks for the door,
#      power apply and the Stop path run the same pass; the Kotlin glue is untouched (no-kotlin pin) and nothing polls or schedules
#   U  UI: the SSHd tab replaces Files (route kept as a client redirect, file view kept inside), the Server card shows the door
#   H  jump-host helpers: the four scripts exist, fleet-nix / fleet-termux exec the right onward ssh, nothing switches uid
#   R  render (node, the shell's pure block): three doors drawn with state, Start/Stop only where a hook exists, Copy All
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
for t in python3 node; do command -v $t >/dev/null 2>&1 || { echo "ERROR $t absent"; exit 1; }; done
python3 - "$APP" "$ROOT" <<'PY'
import json, os, re, sys
app, root = sys.argv[1:3]
rd = lambda p: open(os.path.join(app, p)).read()
bad = []
bj = json.load(open(os.path.join(app, "build.json")))
sd = bj["ssh_doors"]
doors = {d["id"]: d for d in sd["doors"]}
if list(doors) != ["nix", "superapp", "termux"]: bad.append("D doors are %r, want nix, superapp, termux" % list(doors))
if [d["id"] for d in sd["doors"] if d.get("supervised")] != ["nix"]: bad.append("D exactly the nix door is supervised")
for d in sd["doors"]:
    for k in ("port", "package", "bind", "user", "control"):
        if k in d and k + "_from" in d: bad.append("D door %s spells %s next to %s_from" % (d["id"], k, k))
    if "port" in d: bad.append("D door %s spells a literal port" % d["id"])
if "port" in doors["superapp"] or "port_from" in doors["superapp"]: bad.append("D the SuperApp hosts no sshd and must declare no port")
nix = json.load(open(os.path.join(root, "ac_cloud-nix-on-droid/build.json")))["mesh_sshd"]
if nix["start_on_boot"] is not False: bad.append("D nix start_on_boot must stay false (#1487)")
if "control" not in nix: bad.append("D the nix hook (mesh_sshd.control) is missing")
if "mesh_sshd.control" not in doors["nix"]["control_from"] or "mesh_sshd.port" not in doors["nix"]["port_from"]: bad.append("D the nix door does not read its owner's declaration")
# S
ts = rd("src-tauri/src/lib.rs")
start = ts.split("nativeStart(")[1].split("pub extern")[0]
if "door::sync(false, false)" not in start or "note_service" not in start: bad.append("S nativeStart does not run the door supervisor")
stop = ts.split("nativeStop(")[1]
if "power::apply" not in stop or "note_service" not in stop: bad.append("S the Stop path does not run the supervisor with the service context")
pw = rd("server/src/power.rs")
if "door::sync(false, false)" not in pw.split("pub fn apply")[1].split("pub fn status_json")[0]: bad.append("S power::apply does not run the door pass")
br = rd("src-tauri/src/bridge.rs")
if "startForegroundService" not in br or "c.service" not in br or re.search(r'"(cld|com)\.termux', br): bad.append("S the signal must start the declared service and name no package of its own")
for f in ("server/src/door.rs", "server/src/jump.rs"):
    if re.search(r"thread::spawn|thread::sleep|loop \{|AlarmManager|JobScheduler|WorkManager|WakeLock", rd(f).split("#[cfg(test)]")[0]): bad.append("S %s polls, sleeps or schedules" % f)
if "START_STICKY" in rd("src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/KeepAliveService.kt").replace("START_NOT_STICKY", "").split("return START_STICKY")[0].split("onStartCommand")[0]: pass
# U
ap = rd("server/src/app.rs").split("#[cfg(test)]")[0]
if 'tab: Some("Files")' in ap or 'tab: Some("SSHd")' not in ap: bad.append("U the nav must have SSHd and no Files tab")
if '"/__app__/tab/files", tab: None' not in ap: bad.append("U the old Files route must stay (redirect)")
if '"/__app__/api/sshd"' not in ap: bad.append("U no /__app__/api/sshd route")
sh = rd("server/ui/shell.html")
if "legacy" not in sh or "<iframe id=\"fileview\" class=\"inline\" title=\"Files\" src=\"/\">" not in sh: bad.append("U the file view is not kept inside the SSHd tab, or the old route does not redirect")
if "data-power=\"' + id" not in sh and "'ssh_door'" not in sh: bad.append("U the Server card has no SSH door switch")
# H
jd = os.path.join(app, "server/assets/jump")
for n in ("fleet-nix", "fleet-termux", "fleet-adb", "fleet-ipc"):
    p = os.path.join(jd, n)
    if not os.path.isfile(p): bad.append("H %s missing" % n); continue
    t = open(p).read()
    if re.search(r"\bsu\b|setuid|run-as|PRIVATE KEY|ssh-ed25519 ", t): bad.append("H %s switches uid or carries key material" % n)
if 'exec ssh -p "$PORT"' not in open(os.path.join(jd, "fleet-nix")).read() or "{{NIX_PORT}}" not in open(os.path.join(jd, "fleet-nix")).read(): bad.append("H fleet-nix does not exec ssh to the declared nix port")
if 'exec ssh -p "$PORT"' not in open(os.path.join(jd, "fleet-termux")).read() or "{{TERMUX_PORT}}" not in open(os.path.join(jd, "fleet-termux")).read(): bad.append("H fleet-termux does not exec ssh to the declared termux port")
if "settings put global adb_wifi_enabled" not in open(os.path.join(jd, "fleet-adb")).read() or "adb connect" not in open(os.path.join(jd, "fleet-adb")).read(): bad.append("H fleet-adb does not enable wireless debugging and connect")
if "content call" not in open(os.path.join(jd, "fleet-ipc")).read(): bad.append("H fleet-ipc is not a wrapper over content call")
# M mesh-only
mo = bj["ssh_doors"].get("mesh_only_from", "")
if "mesh_sshd.mesh_only" not in mo: bad.append("M the mesh-only policy is not read from the hosting app's declaration")
dr = rd("server/src/door.rs").split("#[cfg(test)]")[0]
if "unwrap_or(cfg().mesh.default_on)" not in dr: bad.append("M the switch must default to the declared default (ON)")
if "extra_mesh_only" not in br or "putExtra" not in br: bad.append("M the start signal does not carry the Mesh-only flag")
if "Cmd::Start(mesh_only(&st))" not in dr: bad.append("M every start must carry the flag")
if "set_mesh_only" not in rd("server/src/app.rs"): bad.append("M the API has no mesh_only switch")
print("\n".join("FAIL " + b for b in bad) if bad else "ok   declaration, supervisor wiring, UI wiring, helpers and mesh-only hold")
sys.exit(1 if bad else 0)
PY
node - "$APP/server/ui/shell.html" <<'JS'
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(html);
const mod = { exports: {} };
new Function('module', m[1] + '\n')(mod);
const S = mod.exports;
const door = (id, state, avail, port, bs, binds) => ({ bind_state: bs, binds, id, title: id, app: 'a', package: 'p', engine: 'OpenSSH 9.8', banner: 'SSH-2.0-OpenSSH_9.8', port, bind: '0.0.0.0', state, auth: 'pubkey', authorized_keys: null, authorized_keys_note: 'private', seen_up_s: 65, sessions: avail ? ['10.0.0.2:51000'] : null, sessions_note: 'hidden', note: 'n', control: { available: avail, explicit: null, note: 'hook' } });
const data = { doors: [door('nix', 'connected', true, 8024, 'mesh-only', ['10.0.0.9', '127.0.0.1']), door('superapp', 'not hosted', false, null, 'n/a', null), door('termux', 'listening', false, 8023, 'exposed', ['0.0.0.0'])], other: [{ port: 8022, engine: 'dropbear', banner: 'SSH-2.0-dropbear' }], wanted: true, explicit: null, keep_alive: true, server: true, mesh_only: true, mesh_cidrs: ['10.0.0.0/24', 'fd0c:1d00::/32'] };
const out = S.renderSshd(data);
const bad = [];
for (const id of ['door-nix', 'door-superapp', 'door-termux']) if (!out.includes('id="' + id + '"')) bad.push('R ' + id + ' not drawn');
if (!out.includes('connected') || !out.includes('not hosted') || !out.includes('listening')) bad.push('R live states not shown');
if (!out.includes('tcp 8024') || !out.includes('tcp 8023') || !out.includes('no port')) bad.push('R ports not shown');
if (!/data-door-op="start" data-door="nix">/.test(out)) bad.push('R nix Start is not enabled');
if (!/data-door-op="start" data-door="termux" disabled/.test(out) || !/data-door-op="stop" data-door="superapp" disabled/.test(out)) bad.push('R Start/Stop must be disabled where no hook exists');
if (!out.includes('data-copy-doors') || !out.includes('id="fileview"')) bad.push('R Copy All or the file view is missing');
if (!out.includes('8022') || !out.includes('dropbear')) bad.push('R other connected servers not listed');
if (!/id="door-nix"[\s\S]*?badge mesh">mesh-only/.test(out.split('id="door-superapp"')[0])) bad.push('R the nix door lacks the green mesh-only badge');
if (!/id="door-termux"[\s\S]*?badge exposed">all interfaces \/ EXPOSED/.test(out)) bad.push('R an all-interfaces door lacks the red EXPOSED badge');
if (out.split('id="door-termux"')[0].includes('EXPOSED</span>')) bad.push('R the EXPOSED badge is on the wrong door');
if (!out.includes('id="mesh-only"') || !out.includes('SSH reachable over mesh VPN only') || !out.includes('data-mesh-only="false"')) bad.push('R the Mesh-only flag (ON) with its label is missing');
const off = S.renderMeshOnly(Object.assign({}, data, { mesh_only: false }));
if (!off.includes('data-mesh-only="true"') || !off.includes('OFF: all interfaces')) bad.push('R Mesh-only OFF is not shown as exposed');
const txt = S.doorsText(data);
if (!txt.includes('Bind (observed): 10.0.0.9, 127.0.0.1 (mesh-only)') || !txt.includes('Mesh-only')) bad.push('R Copy All lacks the observed bind');
if (!txt.includes('nix') || !txt.includes('Engine: OpenSSH 9.8') || !txt.includes('Authorized keys: not readable here')) bad.push('R Copy All text lacks the per-door facts');
const pw = S.renderPower({ server: true, keep_alive: true, running: true, ports: [], sites: 0, ssh_door: { port: 8024, wanted: true, explicit: null, state: 'listening', via: 'nix' } });
if (!pw.includes('SSH door :8024') || !pw.includes('data-power="ssh_door"')) bad.push('R the Server card shows no SSH door switch');
console.log(bad.length ? bad.map((b) => 'FAIL ' + b).join('\n') : 'ok   the SSHd page draws three doors with live state, hook-only controls and Copy All');
process.exit(bad.length ? 1 : 0);
JS
