#!/usr/bin/env bash
# cloud-c3-webserver #1677: the Pages tab is All | File Explorer | Pages over build.json::roots, and the bottom nav's Configs
# entry became ADB Shell (the SuperApp's ADB Shell page); Configs is the top bar's gear.
#
#   R1  SUB-PAGE ORDER. The Pages tab's sub-nav is exactly All | File Explorer | Pages (PAGES_SUBS), drawn by the shell's pure block.
#   R2  ROOTS ARE DECLARED. build.json::roots.explorer = termux, nix, emulated (Cloud Termux, Cloud Nix (Nix-on-Droid), Emulated
#       storage) and roots.pages = git-repos-master, front-html, cloud-me-pvt-notes, each on a declared repos source with a relative
#       repo; roots.rs reads them from build.json (no root path spelled in code).
#   R3  FILE EXPLORER is two columns (.xcols: tree .xtree left, preview .xprev right) whose preview is /__app__/root/<id>/<file>.
#   R4  PAGES serves a root like a browser: renderBrowser frames /__app__/root/<id>/<path>; roots.rs opens the folder's index and
#       redirects a folder without its slash (so relative links stay inside the root).
#   R5  ALL lists both kinds, each row carrying its mode, and keeps the served list and the Git Sites rows below them.
#   R6  THE SPLIT IS GONE: no "Served pages | Git Sites" sub-nav is drawn.
#   R7  STATES, NEVER A CRASH: missing -> clone command + Copy, offline -> Start app, no_access -> Configs > Perms, every status labelled.
#   R8  ADB SHELL: the bottom nav is Pages, API, Home, SSHd, ADB Shell; the tab POSTs /__app__/api/adb-shell, which launches the
#       declared SuperApp package with build.json::adb_shell (shortcut_action = page:config/adb-shell) through the bridge's
#       launch_app_with; a missing SuperApp is a clear message. Configs is still a tab page, opened by the gear.
#   P   RULE 16 PARITY against the version before #1677: every route path, every shell action attribute and every view is still there.
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
html, rs, roots, bridge, served = rd("server/ui/shell.html"), rd("server/src/app.rs"), rd("server/src/roots.rs"), rd("core/src/bridge.rs"), rd("server/src/served.rs")
b = json.loads(rd("build.json"))
body = rs.split("#[cfg(test)]")[0]
r = b.get("roots", {})
srcs = {s["id"] for s in b["repos"]["sources"]}
ex = [(x["id"], x["label"], x["source"]) for x in r.get("explorer", [])]
pg = [(x["id"], x["source"], x.get("repo", "")) for x in r.get("pages", [])]
if ex != [("termux", "Cloud Termux", "termux"), ("nix", "Cloud Nix (Nix-on-Droid)", "nix"), ("emulated", "Emulated storage", "emulated")]:
    bad.append("R2 roots.explorer is %r" % ex)
if [p[0] for p in pg] != ["git-repos-master", "front-html", "cloud-me-pvt-notes"]:
    bad.append("R2 roots.pages is %r" % pg)
for x in r.get("explorer", []) + r.get("pages", []):
    if x["source"] not in srcs or x.get("repo", "").startswith("/") or ".." in x.get("repo", ""):
        bad.append("R2 root %s is not a declared source with a relative repo" % x["id"])
for x in r.get("pages", []):
    if not x.get("clone"):
        bad.append("R7 pages root %s has no clone command for its missing state" % x["id"])
if 'apis::build()' not in roots or re.search(r'"(/storage|/data/data|git-repos-master|front-html|cloud-me-pvt-notes)', roots.split("#[cfg(test)]")[0]):
    bad.append("R2 roots.rs does not read its roots from build.json (a root path is spelled in code)")
for path in ("/__app__/api/roots", "/__app__/api/roots/ls", "/__app__/root", "/__app__/api/adb-shell"):
    if 'path: "%s"' % path not in body:
        bad.append("R route %s is not in the route table" % path)
if 'r.mode == Mode::Pages' not in roots.split("#[cfg(test)]")[0] or 'd.index' not in roots or '301' not in roots:
    bad.append("R4 roots.rs does not open a Pages folder's index / redirect a folder without its slash")
rows = re.findall(r'path: "([^"]+)", tab: Some\("([^"]+)"\)', body)
top = re.findall(r'"([^"]+)"', (re.search(r'pub const TOP_BAR: &\[&str\] = &\[([^\]]*)\]', body) or re.match("()", "")).group(1))
bottom = [t for p, t in rows if p not in top]
if bottom != ["Pages", "API", "Home", "SSHd", "ADB Shell"]:
    bad.append("R8 the bottom nav is %r" % bottom)
if ("/__app__/tab/configs", "Configs") not in rows or top != ["/__app__/tab/configs"] or "show('configs', true)" not in html:
    bad.append("R8 Configs is no longer reachable from the top bar's gear")
a = b.get("adb_shell", {})
if a.get("extra") != "shortcut_action" or a.get("value") != "page:config/adb-shell":
    bad.append("R8 build.json::adb_shell is not the SuperApp's ADB Shell page deep link: %r" % a)
if "fn launch_app_with" not in bridge or "set_extra_launcher(Box::new(launch_app_with))" not in bridge or "putExtra" not in bridge.split("fn launch_app_with")[1].split("\nfn ")[0]:
    bad.append("R8 the Android bridge does not launch the SuperApp with the page extra")
if "pub fn launch_with" not in served or "not installed" not in roots:
    bad.append("R8 no launch_with hook / no clear SuperApp-missing message")
if "post('/__app__/api/adb-shell'" not in html:
    bad.append("R8 the ADB Shell tab does not open the SuperApp page")
# P: rule 16 parity with the version before #1677 (frozen here). #1718: /__app__/api/sub/api-public left this list on purpose: the API tab shows only
# this device, and the fleet services' public URLs it listed are Cloud C3 > Infra > APIs > C3 Endpoints now (ac_cloud-c3/test/test-c3-fleet-endpoints.sh
# proves every one of them is there); the off-device /endpoints/public JSON is unchanged.
OLD_ROUTES = ["/__app__", "/__app__/tab/pages", "/__app__/tab/api", "/__app__/tab/home", "/__app__/tab/sshd", "/__app__/files", "/__app__/tab/configs",
  "/__app__/api/routes", "/__app__/api/topology", "/__app__/api/config", "/__app__/api/serving", "/__app__/api/engines", "/__app__/api/about", "/__app__/api/links",
  "/__app__/crashes", "/__app__/api/crashes", "/__app__/api/subpages", "/__app__/api/sub/api-mcp", "/__app__/api/sub/api-mesh",
  "/__app__/api/sub/api-apps", "/__app__/api/sub/api-ipc", "/__app__/api/sub/pages-drive", "/__app__/api/sub/pages-terminals", "/__app__/api/sub/pages-repos",
  "/__app__/api/sub/pages-git", "/__app__/api/repos", "/__app__/api/repos/discover", "/__app__/api/served", "/__app__/api/power", "/__app__/api/sshd",
  "/__app__/api/open", "/__app__/api/launch", "/__app__/api/configs-open"]
# every data-* action attribute the previous PURE (render) block drew, with how often: the new block draws each at least as often
OLD_ATTRS = {'act': 4, 'body': 5, 'cancel': 1, 'cfg': 1, 'choice': 1, 'copy-about': 2, 'copy-doors': 1, 'discover': 1, 'door': 4, 'door-op': 3, 'f': 3, 'goto': 5, 'home-door': 1, 'home-page': 1, 'jump': 2, 'mesh-only': 1, 'native': 1, 'pg': 1, 'post': 5, 'power': 3, 'r': 5, 'remember': 1, 'rescan': 1, 'start-app': 1, 'sub': 1, 'submit': 1, 'tab': 1, 'to': 3, 'toggle': 1, 'topo': 2, 'topo-group': 1, 'topo-q': 1, 'topo-try': 1, 'undeclared': 1}
OLD_VIEWS = ["pages", "api", "home", "sshd", "configs"]
paths = re.findall(r'path: "([^"]+)"', body)
for p in OLD_ROUTES:
    if p not in paths:
        bad.append("P route %s of the previous version is gone" % p)
pure = html.split('<script id="pure">')[1].split('</script>')[0] if '<script id="pure">' in html else ""
for at, n in OLD_ATTRS.items():
    if len(re.findall(r"data-%s(?![a-z-])" % re.escape(at), pure)) < n:
        bad.append("P shell action data-%s of the previous version is drawn fewer times than %d" % (at, n))
for v in OLD_VIEWS:
    if not re.search(r"\n    %s: function" % v, html):
        bad.append("P view %s of the previous version is gone" % v)
if [p["id"] for p in b["subpages"] if p["tab"] == "pages"] != ["drive", "terminals", "repos", "git"]:
    bad.append("P the declared Pages sub-pages changed")
for x in bad: print("    " + x)
sys.exit(1 if bad else 0)
PY

cat > "$WORK/pure.js" <<'JS'
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(html);
const mod = { exports: {} }; new Function('module', m[1])(mod); const S = mod.exports;
const bad = [];
const need = (out, n, why) => { if (out.indexOf(n) < 0) bad.push(why + ' (missing ' + n + ')'); };
// R1
if (JSON.stringify((S.PAGES_SUBS || []).map((p) => p.label)) !== JSON.stringify(['All', 'File Explorer', 'Pages'])) bad.push('R1 the sub-pages are not All | File Explorer | Pages');
const head = S.renderRootsHead('all');
if ((head.match(/<button /g) || []).length !== 3 || !/data-sub="all" class="on"/.test(head)) bad.push('R1 the sub-nav is not three buttons with All current');
// R6
if (/Served pages<\/button>|Git Sites<\/button>/.test(head)) bad.push('R6 the Served pages | Git Sites split is drawn');
const roots = { roots: [
  { id: 'termux', label: 'Cloud Termux', mode: 'explorer', source: 'termux', source_label: 'Termux', status: 'offline', reason: 'closed', start_app: { post: '/__app__/api/served', body: { op: 'start_app', id: 'termux' } } },
  { id: 'emulated', label: 'Emulated storage', mode: 'explorer', source: 'emulated', status: 'no_access', reason: 'grant', perms: { post: '/__app__/api/configs-open', body: { page: 'perms' }, label: 'Open Configs > Perms' } },
  { id: 'nix', label: 'Cloud Nix', mode: 'explorer', source: 'nix', status: 'ready' },
  { id: 'front-html', label: 'front-html', mode: 'pages', source: 'termux', status: 'ready', repo: 'git-repos-master/front-html' },
  { id: 'cloud-me-pvt-notes', label: '<n>', mode: 'pages', source: 'termux', status: 'missing', repo: 'git-repos-master/cloud-me-pvt-notes', clone: 'git clone x' }] };
// R5
const all = S.renderRootsAll(roots, '<div class="served">SERVED</div>', '<div class="sub">GIT</div>');
for (const id of ['termux', 'emulated', 'nix', 'front-html', 'cloud-me-pvt-notes']) need(all, 'data-root="' + id + '"', 'R5 All lacks root ' + id);
need(all, 'data-mode="explorer"', 'R5 All lacks an explorer root'); need(all, 'data-mode="pages"', 'R5 All lacks a pages root');
need(all, 'SERVED', 'R5 All dropped the served list'); need(all, 'GIT', 'R5 All dropped the Git Sites rows');
const ex = S.renderRootsMode(roots, 'explorer'), pg = S.renderRootsMode(roots, 'pages');
if (pg.indexOf('data-mode="explorer"') >= 0 || ex.indexOf('data-mode="pages"') >= 0) bad.push('R5 a mode sub-page lists the other kind');
// R7
need(all, 'data-start-app', 'R7 offline has no Start app'); need(all, '&quot;page&quot;:&quot;perms&quot;', 'R7 no_access does not route to Configs > Perms');
need(all, 'data-copy-clone="git clone x"', 'R7 missing has no clone command'); need(all, 'not cloned', 'R7 missing is not labelled');
need(all, 'data-root-open="nix"', 'R7 a ready root cannot be opened'); if (all.indexOf('data-root-open="emulated"') >= 0) bad.push('R7 a root without access offers Open');
if (all.indexOf('<n>') >= 0) bad.push('R7 a label is not escaped');
// R3
const xp = S.renderExplorer(roots.roots[2], { '': { status: 'ready', entries: [{ name: 'd', dir: true }, { name: 'a b.md', dir: false }] }, 'd': { status: 'ready', entries: [{ name: 'x.html', dir: false }] } }, 'a b.md');
need(xp, 'class="xcols"', 'R3 no two-column explorer'); need(xp, 'class="xtree"', 'R3 no tree column'); need(xp, 'class="xprev"', 'R3 no preview column');
if (xp.indexOf('class="xtree"') > xp.indexOf('class="xprev"')) bad.push('R3 the tree is not the left column');
need(xp, 'src="/__app__/root/nix/a%20b.md"', 'R3 the preview is not the root file'); need(xp, 'data-tree-file="d/x.html"', 'R3 an open folder does not show its children');
const off = S.renderTreeLevel({ '': { status: 'offline', reason: 'app closed', entries: [] } }, '', 0, '');
need(off, 'app closed', 'R7 a tree that cannot be read does not say why');
// R4
const brw = S.renderBrowser(roots.roots[3], 'docs/');
need(brw, 'src="/__app__/root/front-html/docs"', 'R4 the browser frame is not the root URL'); need(brw, 'data-browser="front-html"', 'R4 not a browser frame');
need(brw, 'id="rfill"', 'R4 the frame does not fill the area');
// R8
const tabs = S.tabsFrom([{ tab: 'Pages', path: '/__app__/tab/pages', nav: 'bottom' }, { tab: 'Configs', path: '/__app__/tab/configs', nav: 'top' }, { tab: 'ADB Shell', path: '/__app__/tab/adb-shell', nav: 'bottom' }]);
if (JSON.stringify(tabs.map((t) => t.id)) !== JSON.stringify(['pages', 'adb-shell'])) bad.push('R8 the bottom nav does not drop the top-bar Configs: ' + JSON.stringify(tabs));
if (S.pagesFrom([{ tab: 'Configs', path: '/__app__/tab/configs', nav: 'top' }]).length !== 1) bad.push('R8 Configs is no longer a page the shell can show');
const adb = S.renderAdbShell({ package: 'com.diegonmarcos.superapp' }, '');
need(adb, 'data-adb-open', 'R8 the ADB Shell tab has no open button'); need(adb, 'Cloud SuperApp', 'R8 the ADB Shell tab does not say where it opens');
need(S.renderAdbShell({ error: 'no link' }), 'no link', 'R8 a missing deep link is not shown');
bad.forEach((b) => console.log('    ' + b)); process.exit(bad.length ? 1 : 0);
JS

run() { python3 "$WORK/check.py" "$1" && node "$WORK/pure.js" "$1/server/ui/shell.html"; }
if run "$APP"; then
  pass "R1-R8 + P Pages is All | File Explorer | Pages over build.json::roots (two-column explorer, browser-like Pages, All lists both kinds, clone / Start app / Perms states), the split is gone, ADB Shell opens the SuperApp page, Configs is the gear's, nothing of before is lost"
else
  fail "R1-R8 / P the Pages roots or the ADB Shell tab disagree with their design"
fi

mutate() { # title file from to
  local title="$1" file="$2" from="$3" to="$4" d="$WORK/m$RANDOM"
  mkdir -p "$d/server/src" "$d/server/ui" "$d/core/src"; cp "$APP/build.json" "$d/"; cp "$APP/server/ui/shell.html" "$d/server/ui/"
  cp "$APP/server/src/app.rs" "$APP/server/src/roots.rs" "$APP/server/src/served.rs" "$d/server/src/"; cp "$APP/core/src/bridge.rs" "$d/core/src/"
  python3 - "$d/$file" "$from" "$to" <<'PY' || { fail "M: '$title' did not apply"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p).read(); n = s.replace(a, b, 1); assert n != s; open(p, "w").write(n)
PY
  if run "$d" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN"; else pass "M: '$title' goes red"; fi
}
mutate "sub-pages reordered"                 server/ui/shell.html "{ id: 'all', label: 'All' }, { id: 'explorer', label: 'File Explorer' }" "{ id: 'explorer', label: 'File Explorer' }, { id: 'all', label: 'All' }"
mutate "the split back"                      server/ui/shell.html "{ id: 'pages', label: 'Pages' }];" "{ id: 'pages', label: 'Pages' }, { id: 'git', label: 'Git Sites' }];"
mutate "one column explorer"                 server/ui/shell.html '<div class="xcols" data-explorer' '<div class="xone" data-explorer'
mutate "All without the served list"         server/ui/shell.html "renderRootList('All roots', rootsOf(data)) + (served || '')" "renderRootList('All roots', rootsOf(data))"
mutate "no Start app for a closed terminal"  server/ui/shell.html "if (r.start_app) acts.push" "if (false) acts.push"
mutate "no clone command"                    server/ui/shell.html "data-copy-clone=" "data-x="
mutate "a root dropped"                      build.json '"id": "cloud-me-pvt-notes"' '"id": "notes"'
mutate "a root on an undeclared source"      build.json '"id": "nix", "label": "Cloud Nix (Nix-on-Droid)", "source": "nix"' '"id": "nix", "label": "Cloud Nix (Nix-on-Droid)", "source": "nowhere"'
mutate "the ADB Shell link changed"          build.json '"value": "page:config/adb-shell"' '"value": "page:config/perms"'
mutate "ADB Shell not launched with extra"   core/src/bridge.rs "set_extra_launcher(Box::new(launch_app_with))" "set_extra_launcher(Box::new(launch_app_plain))"
mutate "Configs back in the bottom nav"      server/src/app.rs 'pub const TOP_BAR: &[&str] = &["/__app__/tab/configs"];' 'pub const TOP_BAR: &[&str] = &[];'
mutate "an old route removed (parity)"       server/src/app.rs 'path: "/__app__/api/sub/pages-git"' 'path: "/__app__/api/sub/pages-gone"'
mutate "an old action removed (parity)"      server/ui/shell.html "data-rescan" "data-x-rescan"
mutate "no index for a Pages folder"         server/src/roots.rs "if r.mode == Mode::Pages {" "if false {"

[ "$FAILURES" -eq 0 ] && { echo "test-c3-webserver-pages-roots: all green"; exit 0; } || { echo "test-c3-webserver-pages-roots: $FAILURES FAILED"; exit 1; }
