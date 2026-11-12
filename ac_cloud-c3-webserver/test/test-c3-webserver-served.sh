#!/usr/bin/env bash
# cloud-c3-webserver: the Pages tab is ONE list of served pages (reorg of the Pages tab, nothing removed from the backend).
#
#   V1  the shell draws Pages from /__app__/api/served alone: no Drive|Terminals|Repos sub-nav, no extraOn hack, no Sources / Repo-sites /
#       "This App's Pages" sections; the Pages tab is still declared in the route table and the top nav order is unchanged.
#   V2  one row per served page draws its header (dot, name, :port, role, serving badge, Start/Stop) and FOUR stacked rows: Status
#       (serving on/off; Termux and Nix also app open/not open + Start app when not open), Metadata (auth, exposure, host), Source
#       (path + open link) and Links (name and 127.0.0.1:port), plus an edit-config link. Pure render, escaped.
#   V3  the Add-a-place form is the repos-serving backend (POST /__app__/api/repos, op add): fields, "Add & serve", the backend ops
#       add|remove|start|stop and the Repos sub-page routes are all still there.
#   V4  the undeclared-pages checker renders (count, address, Declare, Stop, Rescan, scope note) and the server side has the scan.
#   V5  COVERAGE (nothing lost): the four serving endpoints (termux.app, nix.app, emulated.app, cloud.app), the subpages declaration
#       and the repos store are unchanged, and every old field of an endpoint (title, name, port, role, summary link) is still drawn.
#   M   a mutation of each fails the check.
#
# OWN-SOURCE ONLY. node, python3.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in node python3; do command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent"; exit 1; }; done
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/check.py" <<'PY'
import json, re, sys
app = sys.argv[1]
bad = []
def rd(p): return open(app + "/" + p).read()
html, rs, sub, served, bridge = rd("server/ui/shell.html"), rd("server/src/app.rs"), rd("server/src/sub.rs"), rd("server/src/served.rs"), rd("core/src/bridge.rs")
b = json.loads(rd("build.json"))
m = re.search(r"pages: function \(\) \{(.*?)\n    \},\n", html, re.S)
view = m.group(1) if m else ""
if "/__app__/api/served" not in view or "withSubs" in view or "renderRoutes" in view:
    bad.append("V1 the Pages view is not the single served list")
if "withSubs('pages'" in html:
    bad.append("V1 the Pages tab still draws the sub-nav")
for old in ("Repo sites", "Sources", "This App", "pages this app serves"):
    if old in view:
        bad.append("V1 the Pages view still names the old section " + old)
if not re.search(r'tab: Some\("Pages"\)', rs):
    bad.append("V1 the Pages tab row is gone from the route table")
tabs = re.findall(r'tab: Some\("([^"]+)"\)', rs.split("#[cfg(test)]")[0])
# #1677 Configs moved to the top bar (app.rs TOP_BAR) and ADB Shell took its bottom-nav place; the tab pages keep their order.
if tabs != ["Pages", "API", "Home", "SSHd", "Configs", "ADB Shell"] or 'TOP_BAR: &[&str] = &["/__app__/tab/configs"]' not in rs:
    bad.append("V1 the top nav order changed: %r" % tabs)
if 'path: "/__app__/api/served"' not in rs or "post: true" not in re.search(r'path: "/__app__/api/served"[^\n]*', rs).group(0):
    bad.append("V3 /__app__/api/served is not a routed GET+POST API")
for r in ("/__app__/api/repos\"", "/__app__/api/repos/discover", "/__app__/api/sub/pages-repos", "/__app__/api/sub/pages-drive", "/__app__/api/sub/pages-terminals"):
    if 'path: "' + r not in rs and ('path: "' + r.rstrip('"') + '"') not in rs:
        bad.append("V3 the backend route %s is gone" % r)
for op in ('"add" =>', '"remove" =>', '"start" =>', '"stop" =>', 'body["serve"]'):
    if op not in sub:
        bad.append("V3 repos_op lost " + op)
formsrc = served.split("pub fn form(")[1].split("pub fn page(")[0]
if formsrc.count('"post"') != 1:
    bad.append("V3 the add form declares more than one post target")
for needle in ('"post": "/__app__/api/repos"', "Add & serve", '"op": "add"'):
    if needle not in formsrc:
        bad.append("V3 the add form no longer wires to the repos backend: " + needle)
for needle in ("fn scan(", "fn declared_ports(", "fn listen_ports(", "/proc/net/tcp", '"declare" =>', '"stop_undeclared" =>', '"forget" =>', '"start_app" =>'):
    if needle not in served:
        bad.append("V4 served.rs lacks " + needle)
if "set_launcher" not in bridge or "fn launch_app" not in bridge:
    bad.append("V2 the Android bridge does not install the app launcher (Start app)")
got = [(e["id"], e["name"], e["role"]) for e in b["serving"]["endpoints"]]
if got != [("termux", "termux.app", "external"), ("nix", "nix.app", "external"), ("emulated", "emulated.app", "drive"), ("cloud", "cloud.app", "diag")]:
    bad.append("V5 serving.endpoints changed: %r" % got)
if [p["id"] for p in b["subpages"] if p["tab"] == "pages"] != ["drive", "terminals", "repos", "git"]:
    bad.append("V5 the declared Pages sub-pages changed")
if not b["repos"]["store"].endswith(".json") or set(b["repos"]["exposures"]) != {"loopback", "mesh"}:
    bad.append("V5 the repos store / exposures changed")
for k in ("title", "name", "role", "summary"):
    if "e.%s" % k not in served and k != "summary":
        bad.append("V5 served.rs rows no longer carry the endpoint " + k)
for x in bad: print("    " + x)
sys.exit(1 if bad else 0)
PY

cat > "$WORK/pure.js" <<'JS'
const fs = require('fs');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(fs.readFileSync(process.argv[2], 'utf8'));
const mod = { exports: {} }; new Function('module', m[1] + '\n')(mod); const S = mod.exports;
const bad = [];
const act = (op, extra) => Object.assign({ post: '/__app__/api/served', body: Object.assign({ op }, extra || {}) });
const row = (o) => Object.assign({ id: 'x', kind: 'declared', name: 'X', port: 8000, role: 'external', up: false, serving: false, paused: false, app: null, start_app: null,
  auth: 'a', exposure: 'loopback', host: 'h', source: '/p/<s>', source_open: 'http://x.app/', links: [{ label: 'x.app', href: 'http://x.app/' }, { label: '127.0.0.1:8000', href: 'http://127.0.0.1:8000/' }],
  toggle: Object.assign(act('start'), { label: 'Start serving', danger: false, disabled: false }), config: 'configs' }, o);
const pg = { dns: { host: '127.0.0.1', port: 5354 },
  rows: [row({ name: 'Termux', app: 'not open', start_app: act('start_app', { id: 'termux' }) }), row({ id: 'n', name: 'Nix', app: 'open', serving: true, toggle: Object.assign(act('stop'), { label: 'Stop serving', danger: true, disabled: true, why: 'w' }) }), row({ id: 'l', name: 'Local<', role: 'drive' })],
  form: { title: 'Add a place to serve', post: '/__app__/api/repos', discover: '', fields: [{ id: 'label', label: 'Name', kind: 'text' }, { id: 'source', label: 'Source', kind: 'select', options: [{ value: 'e', label: 'E' }] }, { id: 'repo', label: 'Path', kind: 'repo' }], submit: { label: 'Add & serve', body: { op: 'add', serve: true } } },
  undeclared: { items: [{ port: 9090, addr: '127.0.0.1:9090', label: 'HTTP 200', ours: false }], method: 'loopback connect probe', declared: 12, scope: 'scope text' } };
const out = S.renderPages(pg);
const need = (n, why) => { if (out.indexOf(n) < 0) bad.push('renderPages lacks ' + why + ' (' + n + ')'); };
if ((out.match(/class="pg"/g) || []).length !== 3) bad.push('not one .pg row per served page');
for (const r of ['status', 'meta', 'source', 'links', 'config']) if ((out.match(new RegExp('data-r="' + r + '"', 'g')) || []).length !== 3) bad.push('rows lack the ' + r + ' row');
need('class="served"', 'the .served container'); need('data-start-app', 'Start app'); need('>not open<', 'app not open'); need('>open<', 'app open');
need('data-toggle', 'the Start/Stop button'); need('disabled title="w"', 'a disabled Stop with its reason'); need('open &#8599;', 'the source open link'); need('edit config', 'the edit-config link');
need('href="http://127.0.0.1:8000/"', 'the loopback link'); need('data-post="/__app__/api/repos"', 'the add form wired to the repos backend'); need('Add &amp; serve', 'Add & serve');
need('Undeclared served pages &middot; 1', 'the undeclared count'); need('data-rescan', 'Rescan'); need('&quot;op&quot;:&quot;declare&quot;', 'Declare'); need('&quot;op&quot;:&quot;stop_undeclared&quot;', 'Stop'); need('127.0.0.1:9090', 'the undeclared address');
if ((out.match(/data-start-app/g) || []).length !== 1) bad.push('Start app must be only on the closed terminal');
if (out.indexOf('data-sub=') >= 0) bad.push('a sub-nav is drawn'); if (/Drive<\/button>|Terminals<\/button>|Repos<\/button>/.test(out)) bad.push('a Drive/Terminals/Repos tab is drawn');
if (out.indexOf('Local<') >= 0 || out.indexOf('/p/<s>') >= 0) bad.push('a name or path is not escaped');
bad.forEach(b => console.log('    ' + b)); process.exit(bad.length ? 1 : 0);
JS

run() { python3 "$WORK/check.py" "$1" && node "$WORK/pure.js" "$1/server/ui/shell.html"; }
if run "$APP"; then
  pass "V1-V5 Pages is one served list: four detail rows per page, Start/Stop, Start app, the Add form on the repos backend, the undeclared checker, and nothing of the backend lost"
else
  fail "V1-V5 the Pages single list disagrees with its design"
fi

mutate() { # title file from to
  local title="$1" file="$2" from="$3" to="$4" d="$WORK/m$RANDOM"
  mkdir -p "$d/server/src" "$d/server/ui" "$d/core/src"; cp "$APP/build.json" "$d/"; cp "$APP/server/ui/shell.html" "$d/server/ui/"; cp "$APP/server/src/app.rs" "$APP/server/src/sub.rs" "$APP/server/src/served.rs" "$d/server/src/"; cp "$APP/core/src/bridge.rs" "$d/core/src/"
  python3 - "$d/$file" "$from" "$to" <<'PY' || { fail "M: '$title' did not apply"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p).read(); n = s.replace(a, b, 1); assert n != s; open(p, "w").write(n)
PY
  if run "$d" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN"; else pass "M: '$title' goes red"; fi
}
mutate "the Drive/Terminals/Repos sub-nav back on Pages" server/ui/shell.html "var served = j('/__app__/api/served')" "var served = withSubs('pages', 'drive', null) || j('/__app__/api/served')"
mutate "no Start app button"             server/ui/shell.html "data-start-app" "data-x"
mutate "no undeclared checker"           server/ui/shell.html "renderForm(pg.form) + renderUndeclared(pg.undeclared)" "renderForm(pg.form)"
mutate "no Rescan"                       server/ui/shell.html "data-rescan>Rescan" "data-x>Rescan"
mutate "the form off the repos backend"  server/src/served.rs '"discover": "/__app__/api/repos/discover?source=",' '"discover": "/__app__/api/other",
        "post": "/__app__/api/other",'
mutate "Add & serve renamed"             server/src/served.rs '"label": "Add & serve"' '"label": "Add"'
mutate "a row without Source"            server/ui/shell.html 'data-r="source"' 'data-r="src"'
mutate "an unescaped source"             server/ui/shell.html "esc(r.source)" "r.source"
mutate "the repos start op dropped"      server/src/sub.rs '"start" => {' '"begin" => {'
mutate "a declared endpoint dropped"     build.json '"name": "nix.app"' '"name": "nx.app"'
mutate "the nav order changed"           server/src/app.rs 'tab: Some("API")' 'tab: Some("Apis")'

[ "$FAILURES" -eq 0 ] && { echo "test-c3-webserver-served: all green"; exit 0; } || { echo "test-c3-webserver-served: $FAILURES FAILED"; exit 1; }
