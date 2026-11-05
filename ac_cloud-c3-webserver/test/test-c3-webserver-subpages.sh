#!/usr/bin/env bash
# #1490 cloud-c3-webserver: the API and Pages sub-pages, the mesh log route and the Repos serving.
#
#   S1  build.json::subpages is exactly API (Public Endpoints, Mesh Endpoints, Apps Endpoints, Apps IPC) and Pages (Drive, Terminals,
#       Repos), and every declared route is a row of app.rs::ROUTES (cargo test's check_table also refuses the reverse).
#   S2  the shell draws the sub-nav and every sub-page from the declaration (pure block under node), escaping what it draws.
#   S3  the mesh listener can never be public: the declared bind comes from a WireGuard profile, the subnets are RFC 1918 ranges, the
#       Rust source holds the bind guard and the bearer check before routing, and no wildcard address is bound.
#   S4  the Repos exposures are loopback and mesh only; the store is a JSON file; ports are outside every other declared port.
#   S5  the IPC contract the server calls is the one the apps export: authority suffix and method list equal LogExportContract.kt's.
#   M   a mutation of each fails the check (a check that cannot go red proves nothing).
#
# OWN-SOURCE ONLY. node, python3.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in node python3; do command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent"; exit 1; }; done
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/check.py" <<'PY'
import json, re, sys
app, shell, build, repo = sys.argv[1:5]
b = json.load(open(build))
bad = []
want = [("api", "public", "Public Endpoints"), ("api", "mesh", "Mesh Endpoints"), ("api", "apps", "Apps Endpoints"), ("api", "ipc", "Apps IPC"),
        ("pages", "drive", "Drive"), ("pages", "terminals", "Terminals"), ("pages", "repos", "Repos")]
got = [(p["tab"], p["id"], p["label"]) for p in b.get("subpages", [])]
if got != want:
    bad.append("S1 subpages are %r, want %r" % (got, want))
rs = open(app + "/server/src/app.rs").read()
for p in b.get("subpages", []):
    if 'path: "%s"' % p["route"] not in rs:
        bad.append("S1 %s is declared but app.rs::ROUTES has no row for it" % p["route"])
html = open(shell).read()
m = re.search(r'<script id="pure">([\s\S]*?)</script>', html)
pure = m.group(1) if m else ""
for fn in ("renderSubNav", "renderSubRow", "renderSubPage", "renderForm", "subsOf"):
    if "function %s(" % fn not in pure:
        bad.append("S2 the pure block lacks %s" % fn)
if "j('/__app__/api/subpages')" not in html:
    bad.append("S2 the shell does not fetch the sub-page declaration")
if re.search(r"font:\s*[0-9.]+sp|[0-9]sp\b", html):
    bad.append("S2 an sp literal in the shell")
ml = open(app + "/server/src/meshlogs.rs").read()
if re.search(r"UNSPECIFIED|0\.0\.0\.0\"|Ipv4Addr::new\(0, 0, 0, 0\)", ml.split("#[cfg(test)]")[0]):
    bad.append("S3 meshlogs.rs binds or names a wildcard address")
if "check_bind()" not in ml.split("pub fn start")[1].split("#[cfg(test)]")[0]:
    bad.append("S3 meshlogs::start does not run the bind guard first")
route_src = ml.split("pub fn route")[1].split("pub fn start")[0]
if route_src.index("authorize(") > route_src.index("strip_prefix"):
    bad.append("S3 the bearer check no longer runs before any routing")
for c in b["mesh"]["cidrs"]:
    a, n = c.split("/")
    o = [int(x) for x in a.split(".")]
    private = o[0] == 10 or (o[0] == 172 and 16 <= o[1] <= 31) or (o[0] == 192 and o[1] == 168)
    if not private or int(n) < 8:
        bad.append("S3 mesh cidr %s is not a private range of at least /8" % c)
if b["mesh"]["profile_from"]["owner"] != "aa_cloud-superapp":
    bad.append("S3 the mesh bind is not read from the SuperApp's WireGuard profile")
if not b["runtime"].get("mesh", {}).get("port"):
    bad.append("S3 runtime.mesh.port is not declared")
if set(b["repos"]["exposures"]) - {"loopback", "mesh"} or not b["repos"]["exposures"]:
    bad.append("S4 repos.exposures must be a non-empty subset of loopback, mesh")
if not b["repos"]["store"].endswith(".json"):
    bad.append("S4 the repos store is not a JSON file")
if not (b["repos"]["port_first"] > b["runtime"]["mesh"]["port"] and b["repos"]["port_first"] < b["repos"]["port_last"]):
    bad.append("S4 the repo port range overlaps the app's own ports")
if "0.0.0.0" in open(app + "/server/src/repos.rs").read().split("#[cfg(test)]")[0]:
    bad.append("S4 repos.rs names a wildcard address")
k = open(repo + "/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/logexport/LogExportContract.kt").read()
suffix = re.search(r'AUTHORITY_SUFFIX = "([^"]+)"', k).group(1)
methods = re.search(r"METHODS = listOf\(([^)]*)\)", k).group(1)
consts = dict(re.findall(r'const val (META|LOGS|CRASHES|EXPORT) = "([^"]+)"', k))
kt_methods = [consts[x.strip()] for x in methods.split(",")]
if b["mesh"]["ipc"]["authority_suffix"] != suffix:
    bad.append("S5 mesh.ipc.authority_suffix %r != the provider's %r" % (b["mesh"]["ipc"]["authority_suffix"], suffix))
if b["mesh"]["ipc"]["methods"] != kt_methods:
    bad.append("S5 mesh.ipc.methods %r != the provider's %r" % (b["mesh"]["ipc"]["methods"], kt_methods))
if not any(t["id"] == "logexport" and t["authority"] == "{package}." + suffix for t in b["api"]["ipc"]):
    bad.append("S5 the Apps IPC templates do not list the log export authority")
for x in bad:
    print("    " + x)
sys.exit(1 if bad else 0)
PY

cat > "$WORK/pure.js" <<'JS'
const fs = require('fs');
const m = /<script id="pure">([\s\S]*?)<\/script>/.exec(fs.readFileSync(process.argv[2], 'utf8'));
const mod = { exports: {} }; new Function('module', m[1] + '\n')(mod); const S = mod.exports;
const bad = [];
const subs = [{ tab: 'api', id: 'a', label: 'A<', route: '/r' }, { tab: 'api', id: 'b', label: 'B', route: '/r' }, { tab: 'pages', id: 'c', label: 'C', route: '/r' }];
if (S.subsOf(subs, 'api').length !== 2) bad.push('subsOf does not filter by tab');
const nav = S.renderSubNav(S.subsOf(subs, 'api'), 'b');
if ((nav.match(/<button /g) || []).length !== 2 || !/data-sub="b" class="on"/.test(nav) || nav.indexOf('A<') >= 0) bad.push('the sub-nav is not one button per sub-page, current marked, escaped');
const page = { title: 't', note: 'n', groups: [{ title: 'g', rows: [{ head: '<h>', badges: ['x'], lines: ['l'], links: [{ label: 'L', href: 'http://q' }], actions: [{ label: 'Stop', post: '/p', body: { op: 'stop' } }] }] }, { title: 'e', rows: [] }], form: { title: 'F', post: '/p', fields: [{ id: 'f', label: 'F', kind: 'text' }], submit: { label: 'Add', body: { op: 'add' } } } };
const out = S.renderSubPage(page);
for (const needle of ['&lt;h&gt;', 'class="badge">x', 'href="http://q"', 'data-act="0"', 'data-submit', 'class="note">none']) if (out.indexOf(needle) < 0) bad.push('sub-page lacks ' + needle);
if (out.indexOf('<h>') >= 0) bad.push('a head is not escaped');
bad.forEach(b => console.log('    ' + b)); process.exit(bad.length ? 1 : 0);
JS

run() { python3 "$WORK/check.py" "$APP" "$1" "$2" "$REPO" && node "$WORK/pure.js" "$1"; }
if run "$APP/server/ui/shell.html" "$APP/build.json"; then
  pass "S1-S5 the sub-pages are declared and routed, drawn from the declaration, the mesh listener is bearer-first and mesh-only, repos are loopback or mesh, the IPC contract matches"
else
  fail "S1-S5 the sub-pages, mesh log route or repos serving disagree with their declaration"
fi

# ── M: mutation-proof ──────────────────────────────────────────────────────
mutate() { # title file-in-copy from to
  local title="$1" file="$2" from="$3" to="$4" d="$WORK/m$RANDOM"
  mkdir -p "$d/server/src" "$d/server/ui"; cp "$APP/build.json" "$d/"; cp "$APP/server/ui/shell.html" "$d/server/ui/"; cp "$APP/server/src/app.rs" "$APP/server/src/meshlogs.rs" "$APP/server/src/repos.rs" "$d/server/src/"
  python3 - "$d/$file" "$from" "$to" <<'PY' || { fail "M: '$title' did not apply"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p).read(); n = s.replace(a, b, 1); assert n != s; open(p, "w").write(n)
PY
  if python3 "$WORK/check.py" "$d" "$d/server/ui/shell.html" "$d/build.json" "$REPO" >/dev/null 2>&1 && node "$WORK/pure.js" "$d/server/ui/shell.html" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN"; else pass "M: '$title' goes red"; fi
}
mutate "a sub-page without its route"        server/src/app.rs '"/__app__/api/sub/api-ipc"' '"/__app__/api/sub/api-gone"'
mutate "a mesh cidr that is public"          build.json '"10.1.0.0/24"' '"203.0.113.0/24"'
mutate "a mesh bind on the wildcard"         server/src/meshlogs.rs 'pub fn start(m: Mesh) -> Result<(), String> {
    m.check_bind()?;' 'pub fn start(m: Mesh) -> Result<(), String> {
    let _w = std::net::Ipv4Addr::UNSPECIFIED;'
mutate "a public repo exposure"              build.json '"exposures": [ "loopback", "mesh" ]' '"exposures": [ "loopback", "mesh", "public" ]'
mutate "a method list that drifted"          build.json '"crashes",' '"crashes", "shell",'
mutate "an unescaped sub-nav"                server/ui/shell.html "'>' + esc(p.label) + '</button>'" "'>' + p.label + '</button>'"
mutate "the sub-pages no longer fetched"     server/ui/shell.html "j('/__app__/api/subpages')" "j('/__app__/api/routes')"

[ "$FAILURES" -eq 0 ] && { echo "test-c3-webserver-subpages: all green"; exit 0; } || { echo "test-c3-webserver-subpages: $FAILURES FAILED"; exit 1; }
