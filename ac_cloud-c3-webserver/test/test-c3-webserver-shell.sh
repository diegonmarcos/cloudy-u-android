#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the shell's tabs and the link chooser.       ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. A link the web server serves did one fixed thing, and the choice between
# the system-side browser (cloud-browser) and the app's own WebView was nowhere declared. The choice is
# build.json::links; this runs server/ui/shell.html's PURE block under node (no browser object is touched
# there) and holds:
#
#   L1  CLASSIFY. Every link is the first declared type whose schemes and hosts match: served pages and
#       the four private names are `served`, other http(s) is `web`, everything else is `other`.
#   L2  THE CHOICES ARE THE DECLARATION'S. The chooser sheet offers exactly the labels build.json::links
#       declares for that type ("Open in browser", "Open embedded"); a single-choice type does not ask.
#   L3  REMEMBER. A remembered choice runs without asking when the type still offers it; a stale one
#       asks again; a declaration with remember=false never skips the sheet.
#   L4  EMBEDDED WORKS IN THE WEBVIEW. A private name is rewritten to 127.0.0.1:<port> (the names resolve
#       only through this app's private DNS, which the WebView does not use); other URLs are untouched.
#   L5  THE WIRING. The shell hooks clicks of its own document and of the file view (and frames inside
#       it) in the capture phase, opens embedded by navigating the WebView (so the back gesture follows
#       the WebView history), hands browser opens to POST /__app__/api/open, and keeps the remembered
#       choice per type.
#   N1  THE TABS ARE THE ROUTE TABLE. tabsFrom/renderNav draw one button per tab row, in order; a planted
#       row appears, a removed row disappears.
#   M   mutation-proof: a classifier that ignores hosts, a sheet that hard-codes its labels and a shell
#       that stops hooking the file view each go red.
#
# OWN-SOURCE ONLY: server/ui/shell.html and build.json. node, python3.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in node python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent — a verdict from a missing tool is not a verdict"; exit 1; }
done
[ -f "$APP/server/ui/shell.html" ] && [ -f "$APP/build.json" ] || { echo "ERROR missing shell.html or build.json — unrun, not passing"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# check <shell.html> <build.json> : prints one line per failure; exit 1 when any.
cat > "$WORK/check.js" <<'JS'
const fs = require('fs');
const [shellPath, buildPath] = process.argv.slice(2);
const html = fs.readFileSync(shellPath, 'utf8');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(html);
if (!m) { console.log('shell.html has no <script id="pure"> block'); process.exit(1); }
const mod = { exports: {} };
new Function('module', m[1] + '\n')(mod);
const S = mod.exports;
const links = JSON.parse(fs.readFileSync(buildPath, 'utf8')).links;
const names = ['termux.app', 'nix.app', 'emulated.app', 'cloud.app'];
const eps = [{ name: 'emulated.app', port: 8002 }, { name: 'cloud.app', port: 8003 }];
const base = 'http://127.0.0.1:8002/';
const bad = [];
const eq = (what, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) bad.push(what + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); };
const typ = (href, b) => { const c = S.classifyLink(href, b || base, links, names); return c ? c.type.id : null; };
// L1
eq('L1 relative file link', typ('/docs/a.md'), 'served');
eq('L1 absolute loopback', typ('http://127.0.0.1:8003/'), 'served');
eq('L1 localhost', typ('http://localhost:8000/x'), 'served');
eq('L1 private name', typ('http://emulated.app/x'), 'served');
eq('L1 external https', typ('https://example.org/a'), 'web');
eq('L1 lookalike host', typ('http://emulated.app.evil.example/'), 'web');
eq('L1 mailto', typ('mailto:a@b.c'), 'other');
eq('L1 tel', typ('tel:+123'), 'other');
eq('L1 unparsable', S.classifyLink('http://', base, links, names), null);
eq('L1 link targets', ['#x', '', 'javascript:void(0)', '/a'].map(S.isLinkTarget), [false, false, false, true]);
// L2
const labelOf = id => links.choices.filter(c => c.id === id)[0].label;
const sheet = href => S.renderSheet(S.classifyLink(href, base, links, names), links);
for (const [href, id] of [['/a.md', 'served'], ['https://example.org/', 'web']]) {
  const t = links.types.filter(x => x.id === id)[0];
  const h = sheet(href);
  for (const c of links.choices) {
    const has = h.indexOf('data-choice="' + c.id + '"') >= 0;
    if (has !== (t.choices.indexOf(c.id) >= 0)) bad.push('L2 ' + id + ' sheet ' + (has ? 'offers' : 'lacks') + ' ' + c.id);
    if (has && h.indexOf('>' + c.label + '<') < 0) bad.push('L2 ' + id + ' sheet does not carry the declared label ' + c.label);
  }
}
eq('L2 labels', [labelOf('browser'), labelOf('embedded')], ['Open in browser', 'Open embedded']);
const other = S.classifyLink('mailto:a@b.c', base, links, names);
eq('L2 single-choice type does not ask', S.decide(other, null, links), other.choices[0]);
// L3
const served = S.classifyLink('/a.md', base, links, names);
eq('L3 nothing remembered asks', S.decide(served, null, links), 'ask');
eq('L3 remembered embedded', S.decide(served, 'embedded', links), 'embedded');
eq('L3 remembered browser', S.decide(served, 'browser', links), 'browser');
eq('L3 stale remembered asks', S.decide(served, 'teleport', links), 'ask');
eq('L3 remember=false never skips', S.decide(served, 'embedded', Object.assign({}, links, { remember: false })), 'ask');
if (links.remember && sheet('/a.md').indexOf('id="remember"') < 0) bad.push('L3 the sheet has no remember tick');
if (!links.remember && sheet('/a.md').indexOf('id="remember"') >= 0) bad.push('L3 the sheet offers remember although the declaration does not');
// L4
eq('L4 private name -> loopback + port', S.embedUrl('http://emulated.app/dir/a.md?x=1', eps), 'http://127.0.0.1:8002/dir/a.md?x=1');
eq('L4 explicit port kept', S.embedUrl('http://emulated.app:9000/a', eps), 'http://127.0.0.1:9000/a');
eq('L4 loopback untouched', S.embedUrl('http://127.0.0.1:8003/', eps), 'http://127.0.0.1:8003/');
eq('L4 external untouched', S.embedUrl('https://example.org/a', eps), 'https://example.org/a');
// L5
const glue = html.slice(html.indexOf('<script>', m.index + m[0].length));
const wires = [
  ['clicks hooked in the capture phase', /addEventListener\('click', linkClick, true\)/],
  ['file view and its frames are watched', /watchFrame\(fv\)/],
  ['frames are rescanned, not hooked on load alone', /setInterval\(function \(\) \{ if \(!document\.body\.contains\(f\)\)/],
  ['frames inside frames are watched', /querySelectorAll\('iframe'\)/],
  ['embedded navigates the WebView', /window\.location\.assign\(embedUrl\(/],
  ['browser opens go to the guarded endpoint', /post\('\/__app__\/api\/open', \{ url: cls\.url \}\)/],
  ['the declaration is fetched, not spelled', /j\('\/__app__\/api\/links'\)/],
  ['remembered choice per type', /KEY \+ cls\.type\.id/],
  ['remembered choice is only stored when ticked', /box && box\.checked/],
  ['storage access is guarded', /try \{ if \(op === 'get'\)/]
];
for (const [what, re] of wires) if (!re.test(glue)) bad.push('L5 ' + what + ' (pattern ' + re + ' not found)');
// N1
const routes = [
  { kind: 'page', path: '/__app__/tab/pages', tab: 'Pages' }, { kind: 'page', path: '/__app__/tab/home', tab: 'Home' },
  { kind: 'page', path: '/__app__/tab/files', tab: 'Files' }, { kind: 'api', path: '/__app__/api/x', tab: null }];
eq('N1 tabs', S.tabsFrom(routes).map(t => t.id), ['pages', 'home', 'files']);
eq('N1 planted row appears', S.tabsFrom(routes.concat([{ kind: 'page', path: '/__app__/tab/extra', tab: 'Extra' }])).map(t => t.id).pop(), 'extra');
eq('N1 removed row disappears', S.tabsFrom(routes.filter(r => r.tab !== 'Home')).map(t => t.id), ['pages', 'files']);
eq('N1 default tab is home', S.defaultTab(S.tabsFrom(routes)), 'home');
eq('N1 nav buttons', (S.renderNav(S.tabsFrom(routes), 'home').match(/<button /g) || []).length, 3);
eq('N1 tab from path', [S.tabFromPath('/__app__/tab/files'), S.tabFromPath('/__app__/'), S.tabFromPath('/files')], ['files', null, null]);
// N2 the nav keeps its hooks (data-tab, .on, label text only) and carries the NavBarBottom icon-over-label cells
const hnv = S.renderNav(S.tabsFrom(routes), 'home');
eq('N2 on class only on the current tab', (hnv.match(/class="on"/g) || []).length, 1);
eq('N2 every cell has an svg icon and a label span', [(hnv.match(/<svg /g) || []).length, (hnv.match(/<span>/g) || []).length], [3, 3]);
eq('N2 data-tab ids', (hnv.match(/data-tab="[a-z]+"/g) || []), ['data-tab="pages"', 'data-tab="home"', 'data-tab="files"']);
// H1 Home is the status cockpit, built from the data the server already serves
const hpw = { server: true, keep_alive: false, running: true, content: true, sites: 1, ports: [], ssh_door: { port: 8024, wanted: false, explicit: null, state: 'listening', via: 'nix' } };
const hsv = { app: 'C3', version_name: '1.2', endpoints: [{ name: 'emulated.app', port: 8002, role: 'drive', up: true }, { name: 'cloud.app', port: 8003, role: 'diag', up: false }], dns: { host: '127.0.0.1', port: 5354, answer: 'A 127.0.0.1' } };
const hsshd = { keep_alive: true, doors: [{ id: 'nix', title: 'Nix', port: 8024, state: 'listening', control: { explicit: null } }, { id: 'termux', title: 'Termux', port: 8023, state: 'closed', control: { explicit: true } }] };
const hserved = { rows: [{ id: 'emulated', name: 'Local', port: 8002, role: 'drive', serving: true }, { id: 'termux', name: 'Termux', port: 8100, role: 'external', serving: false, paused: true, app: 'not open' }], undeclared: { items: [] } };
const habout = [{ macro: 'x', sections: [{ title: 'Memory', rows: [['Total RAM', '8 GB'], ['Available', '3 GB']] }, { title: 'Battery & Usage', rows: [['Level', '80%'], ['Status', 'Charging']] }, { title: 'App', rows: [['Server uptime', '5 min']] }] }];
const hhome = S.renderHome({ power: hpw, health: { ok: true, pid: 7, uptime_s: 90 }, served: hserved, sshd: hsshd, serving: hsv, about: habout });
for (const id of ['home-status', 'home-pages', 'home-sshd', 'home-ports', 'home-device', 'home-topology', 'id="power"']) if (!hhome.includes(id)) bad.push('H1 Home lacks ' + id);
eq('H1 power switches still on Home', (hhome.match(/data-power="server"/g) || []).length, 1);
eq('H1 a row per served page', (hhome.match(/data-home-page=/g) || []).length, 2);
eq('H1 a row per sshd door', (hhome.match(/data-home-door=/g) || []).length, 2);
eq('H1 device facts', S.deviceFacts(habout).map(f => f[1]), ['5 min', '3 GB free of 8 GB', null, '80% Charging', null]);
if (!S.renderHealth({ ...hpw, server: false, running: false }, null, hsv).includes('admin page stays up')) bad.push('H1 OFF banner must say the admin page stays up');
// T1 the API topology view: /api/topology rendered as 3 layers + a detail panel + search/group
const T = { nodes: [
  { layer: 'mcp', id: 'mcp:srv', label: 'srv', meta: { kind: 'server', auth: 'tok', endpoint: 'https://x/mcp', tools_count: 3, tools_listed: 3 } },
  { layer: 'mcp', id: 'mcp:srv/remote_tool', label: 'remote_tool', meta: { kind: 'tool', server: 'srv' } },
  { layer: 'mcp', id: 'mcp:srv/only_tool', label: 'only_tool', meta: { kind: 'tool', server: 'srv' } },
  { layer: 'mcp', id: 'mcp:srv/local_tool', label: 'local_tool', meta: { kind: 'tool', server: 'srv' } },
  { layer: 'api', id: 'api:c3:a.b', label: 'a.b', meta: { surface: 'c3', kind: 'api', method: null, auth: 'tok', summary: '/a/b' } },
  { layer: 'api', id: 'api:app:/__app__/api/power', label: '/__app__/api/power', meta: { surface: 'app', kind: 'api', method: 'GET', auth: 'loopback', summary: 'Power state' } },
  { layer: 'api', id: 'api:app:/__app__/api/open', label: '/__app__/api/open', meta: { surface: 'app', kind: 'api', method: 'POST', auth: 'loopback', summary: 'Open' } },
  { layer: 'ipc', id: 'ipc:power.get', label: 'power.get', meta: { kind: 'query', authority: 'app', what: 'read power' } } ],
  edges: [ { from: 'mcp:srv', to: 'mcp:srv/remote_tool', kind: 'exposes' }, { from: 'mcp:srv/remote_tool', to: 'api:c3:a.b', kind: 'maps_to' },
    { from: 'mcp:srv/local_tool', to: 'api:app:/__app__/api/power', kind: 'served_by' }, { from: 'api:app:/__app__/api/power', to: 'ipc:power.get', kind: 'dispatches' } ],
  routes: [ { mcp_tool: 'remote_tool', mcp_server: 'srv', api_route: 'a.b', ipc_route: null, method: null, auth: 'tok', summary: '/a/b', status: 'unknown', ipc_state: 'n/a' },
    { mcp_tool: 'only_tool', mcp_server: 'srv', api_route: null, ipc_route: null, method: null, auth: 'tok', summary: 'x', status: 'mcp_only', ipc_state: 'n/a' },
    { mcp_tool: 'local_tool', mcp_server: 'srv', api_route: '/__app__/api/power', ipc_route: 'ipc:power.get', method: 'GET', auth: 'loopback', summary: 'Power state', status: 'unknown', ipc_state: 'mapped' } ] };
const tt = S.renderTopology(T, { q: '', group: 'layer', sel: null });
eq('T1 three layer groups', ['mcp', 'api', 'ipc'].map(l => tt.includes('<h2>' + l + '<')), [true, true, true]);
eq('T1 a row per node', (tt.match(/data-topo="/g) || []).length, T.nodes.length);
eq('T1 search box and group toggles', [tt.includes('data-topo-q'), (tt.match(/data-topo-group=/g) || []).length], [true, 3]);
eq('T1 mcp_only badge', (tt.match(/mcp_only<\/span>/g) || []).length >= 1, true);
eq('T1 remote c3-api badge', tt.includes('remote c3-api, no local IPC'), true);
eq('T1 local route badge', tt.includes('local route'), true);
const dl = S.topoDetail(T, 'mcp:srv/local_tool');
eq('T1 tool detail follows edges to api+ipc+state', [dl.includes('/__app__/api/power'), dl.includes('power.get'), dl.includes('mapped'), dl.includes('unknown')], [true, true, true, true]);
eq('T1 remote tool detail', S.topoDetail(T, 'mcp:srv/remote_tool').includes('remote c3-api, no local IPC'), true);
eq('T1 mcp_only tool detail', S.topoDetail(T, 'mcp:srv/only_tool').includes('none (mcp_only)'), true);
const da = S.topoDetail(T, 'api:app:/__app__/api/power');
eq('T1 api detail lists tools, ipc, method, auth, Try it', [da.includes('local_tool'), da.includes('power.get'), da.includes('<b>GET</b>'), da.includes('loopback'), da.includes('data-topo-try')], [true, true, true, true, true]);
eq('T1 POST route has no Try it', S.topoDetail(T, 'api:app:/__app__/api/open').includes('data-topo-try'), false);
eq('T1 ipc detail lists its api route', S.topoDetail(T, 'ipc:power.get').includes('/__app__/api/power'), true);
eq('T1 selection marks related nodes', (S.renderTopology(T, { q: '', group: 'layer', sel: 'mcp:srv/local_tool' }).match(/class="tn l-\w+ rel"/g) || []).length, 2);
eq('T1 search by tool / ipc id / server', ['only_tool', 'power.get', 'srv'].map(q => (S.topoTree(T, { q, group: 'layer', sel: null }).match(/data-topo="/g) || []).length), [1, 1, 4]);
eq('T1 group by surface', ['app', 'c3'].map(g => S.topoTree(T, { q: '', group: 'surface', sel: null }).includes('<h2>' + g + '<')), [true, true]);
eq('T1 group by server', S.topoTree(T, { q: '', group: 'server', sel: null }).includes('<h2>srv<'), true);
eq('T1 the API tab fetches /__app__/api/topology and draws it', [html.includes("j('/__app__/api/topology')"), /api: function[\s\S]{0,400}renderTopology\(t, topoSt\)/.test(html)], [true, true]);
eq('T1 nothing selected', S.topoDetail(T, null).includes('Select'), true);
bad.forEach(b => console.log('    ' + b));
process.exit(bad.length ? 1 : 0);
JS

run() { node "$WORK/check.js" "$1" "$2"; }

if run "$APP/server/ui/shell.html" "$APP/build.json"; then
  pass "T1 topology view (3 layers, detail, search, groups); L1-L5 N1 links are classified by build.json::links, the sheet offers the declared choices, remembered choices skip it, embedded works in the WebView, the tabs are the route table"
else
  fail "L1-L5 N1 the shell's link chooser or tabs disagree with their declaration"
fi

# ── M: mutation-proof ──────────────────────────────────────────────────────
mutate() {
  local title="$1" file="$2" from="$3" to="$4" d="$WORK/m$RANDOM"
  mkdir -p "$d"; cp "$APP/server/ui/shell.html" "$d/shell.html"; cp "$APP/build.json" "$d/build.json"
  python3 - "$d/$file" "$from" "$to" <<'PY' || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p).read()
n = s.replace(a, b, 1)
assert n != s
open(p, "w").write(n)
PY
  if run "$d/shell.html" "$d/build.json" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
mutate "a classifier that ignores hosts"          shell.html "var h = t.hosts === 'any' || (t.hosts === 'local' ? local : !local);" "var h = true;"
mutate "a sheet with a hard-coded label"          shell.html "esc(c ? c.label : id)" "'Open'"
mutate "a chooser that never asks"                shell.html "return 'ask';
}
function renderSheet" "return cls.choices[0];
}
function renderSheet"
mutate "topology view no longer fetches /api/topology" shell.html "j('/__app__/api/topology')" "j('/__app__/api/routes')"
mutate "private names not rewritten for the WebView" shell.html "u.hostname = '127.0.0.1';" "/* gone */"
mutate "the file view no longer hooked"           shell.html "if (fv) watchFrame(fv);" ""
mutate "embedded stops navigating the WebView"    shell.html "window.location.assign(embedUrl(" "window.open(embedUrl("
# build.json: the `served` type loses its embedded choice (the sheet must follow the declaration)
d="$WORK/mjson"; mkdir -p "$d"; cp "$APP/server/ui/shell.html" "$d/shell.html"
python3 - "$APP/build.json" "$d/build.json" <<'PY' || fail "M: the declaration mutation did not apply"
import json, sys
j = json.load(open(sys.argv[1]))
j["links"]["types"][0]["choices"] = ["browser"]
j["links"]["types"][1]["choices"] = ["embedded"]
json.dump(j, open(sys.argv[2], "w"))
PY
# the check itself reads the live declaration, so it must still pass on a CONSISTENT edit and the sheet must change with it
if node -e '
const fs=require("fs");const h=fs.readFileSync(process.argv[1],"utf8");const m=/<script id="pure">([\s\S]*?)<\/script>/.exec(h);
const mod={exports:{}};new Function("module",m[1])(mod);const S=mod.exports;const l=JSON.parse(fs.readFileSync(process.argv[2],"utf8")).links;
const c=S.classifyLink("/a","http://127.0.0.1:8002/",l,[]);const s=S.renderSheet(c,l);
process.exit(s.indexOf("data-choice=\"embedded\"")<0 && S.decide(c,null,l)==="browser" ? 0 : 1)' "$d/shell.html" "$d/build.json"; then
  pass "M: the sheet follows the declaration (served reduced to browser: no embedded button, no question)"
else
  fail "M: the sheet did not follow an edited declaration"
fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-shell: all green ──"; else echo "── test-c3-webserver-shell: $FAILURES FAILED ──"; exit 1; fi
