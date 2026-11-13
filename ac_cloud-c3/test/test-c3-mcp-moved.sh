#!/usr/bin/env bash
# #1725 Cloud C3 > Infra > APIs > C3 MCP: the MCP servers of the C3 environment, their tools and the c3-api routes the tools map
# to, MOVED here from Cloud C3 Webserver > API (its MCP page and the remote nodes of its Topology; the webserver shows only the
# phone now and keeps the tools that act on the device). Nothing lost:
#   P1 PARITY, MCP page: every row the webserver's MCP page drew is recomputed from the generated catalogue with the page's own
#      rules (apis.rs::mcp_page, checked to still be those rules): per server its head (name, auth, tool count, aliases), endpoint
#      and API; per tool its name, API route, tree and old name; a server whose tools are not enumerated keeps its count row.
#      Each is in data/api_providers.json::mcp_catalogue, OR is a device tool, in which case it is still on the webserver
#      (data/fleet/device-access.json::mcp.tools). No row is in neither place, none is in both.
#   P2 PARITY, Topology: every remote node the webserver's Topology drew (topology.rs: one `api:c3:<node>` per catalogue node
#      whose route is an API route, labelled with the node, summarised by the route, and the tool -> node edge) is derivable
#      here: the tool row carries that node and that route.
#   P3 the block is GENERATED (data/gen_c3_mcp.py check).
#   P4 the page is declared and drawn: the block's group answers a declared page of the APIs row, InfraPages draws it through
#      C3McpScreen with every field (C3Mcp.lines for a server and for a tool), and Kotlin spells no server, tool or page id.
#   P5 the webserver no longer shows them: its MCP page is the device page (device_mcp_page) and its topology is built from the
#      device catalogue (device_catalogue), which keeps only the device access model's tools.
#   M  a mutation of each turns the check red.
# OWN-SOURCE ONLY. python3.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent - unrun, not passing"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/check.py" <<'PY'
import json, re, subprocess, sys
root = sys.argv[1]
c3, web = root + "/ac_cloud-c3", root + "/ac_cloud-c3-webserver"
bad = []
ap = json.load(open(c3 + "/data/api_providers.json", encoding="utf-8"))
blk = ap.get("mcp_catalogue") or {}
here = {s["name"]: s for s in blk.get("servers") or []}
cat = json.load(open(web + "/data/mcp-catalogue.json", encoding="utf-8"))["servers"]
device = [t["tool"] for t in json.load(open(root + "/data/fleet/device-access.json", encoding="utf-8"))["mcp"]["tools"]]
rs = open(web + "/server/src/apis.rs", encoding="utf-8").read()
tp = open(web + "/server/src/topology.rs", encoding="utf-8").read()
# the rules the moved rows were drawn with, as written in the webserver
for needle, what in (
    ('let mut lines = vec![format!("API route {}", s(&t["route"]))];', "mcp_page tool route line"),
    ('lines.push(format!("tree {}", s(&t["tree"])));', "mcp_page tree line"),
    ('lines.push(format!("old name {}", l));', "mcp_page old name line"),
    ('let head = format!("{} · {} · {} tools{}", name, auth, sv["tools_count"].as_u64().unwrap_or(0)', "mcp_page server head"),
    ('g["endpoint"] = sv["endpoint"].clone();', "mcp_page endpoint"),
):
    if needle not in rs: bad.append("P1 apis.rs::mcp_page changed (%s): re-derive the parity rule" % what)
for needle, what in (
    ('let aid = format!("api:c3:{}", s(&t["node"]));', "remote node id"),
    ('"meta": {"surface": "c3", "kind": "api", "api": sv["api"], "method": null, "auth": sv["auth"], "summary": route}', "remote node meta"),
    ('route.contains("no API route")', "mcp_only rule"),
):
    if needle not in tp: bad.append("P2 topology.rs changed (%s): re-derive the parity rule" % what)
moved = kept = nodes = 0
for s in cat:
    h = here.get(s["name"])
    if h is None:
        bad.append("P1 server %s of the catalogue is not in Cloud C3" % s["name"]); continue
    for k in ("endpoint", "auth", "aliases", "api", "tools_count"):   # the server head, endpoint and API
        if h.get(k) != s.get(k, [] if k == "aliases" else ""): bad.append("P1 server %s: %s differs here" % (s["name"], k))
    rows = {t["tool"]: t for t in h.get("tools") or []}
    for t in s.get("tools", []):
        r = rows.get(t["tool"])
        if t["tool"] in device:
            kept += 1
            if r is not None: bad.append("P1 device tool %s is listed in both apps" % t["tool"])
            continue
        if r is None:
            bad.append("P1 tool %s of %s is in neither app" % (t["tool"], s["name"])); continue
        moved += 1
        if (r["route"], r["tree"], r.get("legacy")) != (t.get("route", ""), t.get("tree", ""), t.get("legacy")):
            bad.append("P1 the MCP row of %s differs here (route, tree or old name)" % t["tool"])
        if "no API route" not in t.get("route", ""):   # P2 the Topology's remote node and its edge
            nodes += 1
            if r.get("node") != t.get("node") or not r.get("node"): bad.append("P2 the remote node of %s is lost" % t["tool"])
    extra = set(rows) - {t["tool"] for t in s.get("tools", [])}
    if extra: bad.append("P1 %s lists tools the catalogue does not: %s" % (s["name"], sorted(extra)[:3]))
    if h.get("device_tools") != sum(1 for t in s.get("tools", []) if t["tool"] in device): bad.append("P1 %s: the device tool count differs" % s["name"])
if set(here) - {s["name"] for s in cat}: bad.append("P1 Cloud C3 lists servers the catalogue does not")
if kept != len(device): bad.append("P1 %d of the %d device tools are catalogue tools: a device tool has no catalogue row" % (kept, len(device)))
if len(cat) < 5 or moved < 100 or nodes < 50: bad.append("P1 the catalogue lists %d servers, %d moved tools, %d remote nodes: too few to be the real one" % (len(cat), moved, nodes))
# P3
if subprocess.run([sys.executable, "-I", c3 + "/data/gen_c3_mcp.py", "check", root], capture_output=True).returncode != 0:
    bad.append("P3 data/api_providers.json::mcp_catalogue is stale or hand-edited: run python3 ac_cloud-c3/data/gen_c3_mcp.py write")
# P4
bj = json.load(open(c3 + "/build.json", encoding="utf-8"))
infra = next(s for s in bj["ui"]["sections"] if s["id"] == "infra")
grp = next((g for g in ap["groups"] if g["id"] == blk.get("group")), None)
if grp is None: bad.append("P4 mcp_catalogue names group %r, which is not declared" % blk.get("group"))
else:
    if grp["page"] not in [p["id"] for p in infra["pages"]]: bad.append("P4 build.json declares no page %s" % grp["page"])
    if grp["page"] not in next(g for g in infra["groups"] if g["id"] == "apis")["pages"]: bad.append("P4 the APIs row does not list %s" % grp["page"])
kdir = c3 + "/app/src/main/java/com/diegonmarcos/cloudc3/"
ip = open(kdir + "pages/InfraPages.kt", encoding="utf-8").read()
mk = open(kdir + "pages/C3Mcp.kt", encoding="utf-8").read()
if "else if (it.id == mcp.group) C3McpScreen(section, it, mcp)" not in ip or "Declarations.c3Mcp" not in ip: bad.append("P4 InfraPages.kt does not draw the group through C3McpScreen")
if "C3Mcp.lines(s))" not in ip or "C3Mcp.lines(t))" not in ip or "s.tools.forEach { t ->" not in ip: bad.append("P4 the page does not draw every server and every tool")
srv = mk.split("fun lines(s: Server)")[1].split("fun lines(t: Tool)")[0]; tool = mk.split("fun lines(t: Tool)")[1]
for f in ("s.endpoint.", "s.auth.", "s.api.", "s.aliases.", "s.toolsCount}", "s.deviceTools"):
    if f not in srv: bad.append("P4 C3Mcp.lines(server) drops %s" % f.split(".")[1].rstrip("}"))
for f in ("t.route", "t.tree", "t.node", "t.legacy"):
    if f not in tool: bad.append("P4 C3Mcp.lines(tool) drops %s" % f.split(".")[1])
code = re.sub(r"/\*.*?\*/|//[^\n]*", "", mk + ip, flags=re.S)
for s in cat:
    if '"%s"' % s["name"] in code: bad.append("P4 Kotlin spells the server %s" % s["name"])
if grp and '"%s"' % grp["page"] in code: bad.append("P4 Kotlin spells the page id %s" % grp["page"])
# P5
sub = open(web + "/server/src/sub.rs", encoding="utf-8").read()
if "apis::device_mcp_page(apis::data())" not in sub or "apis::mcp_page(apis::data())" in sub: bad.append("P5 the webserver's MCP page still lists the whole catalogue")
if "build(&apis::device_catalogue(apis::data()), map(), apis::build(), apis::apps(), app::ROUTES)" not in tp: bad.append("P5 the webserver's topology is still built from the whole catalogue")
if 'd["device_access"]["mcp"]["tools"]' not in rs.split("pub fn device_catalogue")[1].split("pub fn device_mcp_page")[0]: bad.append("P5 the device catalogue is not the device access model's tool list")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY

if python3 -I "$WORK/check.py" "$ROOT"; then
  pass "P1-P5 every MCP server, tool and remote topology node the webserver listed is in Cloud C3 > Infra > APIs > C3 MCP or still on the webserver as a device tool, generated, declared, drawn whole"
else
  fail "P1-P5 the MCP catalogue moved to Cloud C3 does not hold"
fi

# ── M: mutation-proof ──────────────────────────────────────────────────────
K=ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages
FILES="ac_cloud-c3/data/api_providers.json ac_cloud-c3/data/gen_c3_mcp.py ac_cloud-c3/build.json $K/InfraPages.kt $K/C3Mcp.kt ac_cloud-c3-webserver/data/mcp-catalogue.json ac_cloud-c3-webserver/server/src/apis.rs ac_cloud-c3-webserver/server/src/topology.rs ac_cloud-c3-webserver/server/src/sub.rs data/fleet/device-access.json"
mutate() { # title file from to
  local title="$1" file="$2" from="$3" to="$4" d="$WORK/m$RANDOM" f
  for f in $FILES; do mkdir -p "$d/$(dirname "$f")"; cp "$ROOT/$f" "$d/$f"; done
  python3 -I - "$d/$file" "$from" "$to" <<'PY' || { fail "M: '$title' did not apply"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding="utf-8").read(); n = s.replace(a, b, 1); assert n != s; open(p, "w", encoding="utf-8").write(n)
PY
  if python3 -I "$WORK/check.py" "$d" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN"; else pass "M: '$title' goes red"; fi
}
mutate "a tool lost in the move"                 ac_cloud-c3/data/api_providers.json '{"tool": "obs.debug.cf_api"' '{"tool": "obs.debug.cf_apix"'
mutate "a server lost in the move"               ac_cloud-c3/data/api_providers.json '{"name": "google-workspace-mcp"' '{"name": "google-workspace-mcp-x"'
mutate "a server endpoint changed in the move"   ac_cloud-c3/data/api_providers.json '"endpoint": "https://mcp.diegonmarcos.com/g-personal/mcp"' '"endpoint": "https://mcp.diegonmarcos.com/g-personal/mcp2"'
mutate "a remote topology node lost"             ac_cloud-c3/data/api_providers.json '"node": "ops/' '"node": "'
mutate "a device tool listed in both apps"       data/fleet/device-access.json '"tool": "peers.shell"' '"tool": "peers.shell2"'
mutate "a new catalogue tool not regenerated"    ac_cloud-c3-webserver/data/mcp-catalogue.json '"tool": "devops.cf.api_write"' '"tool": "devops.cf.api_write2"'
mutate "the group no longer drawn"               $K/InfraPages.kt 'else if (it.id == mcp.group) C3McpScreen(section, it, mcp) ' ''
mutate "the tools no longer drawn"               $K/InfraPages.kt 'C3Mcp.lines(t))' 'emptyList())'
mutate "a tool field dropped from the row"       $K/C3Mcp.kt '        "tree ${t.tree.ifBlank { "not declared" }}",' ''
mutate "a server field dropped from the row"     $K/C3Mcp.kt '        "API: ${s.api.ifBlank { "no API of its own" }}",' ''
mutate "the page left the APIs row"              ac_cloud-c3/build.json '              "api_endpoints",
              "api_mcp"' '              "api_endpoints"'
mutate "Kotlin spells a server"                  $K/C3Mcp.kt 'object C3Mcp {' 'object C3Mcp {
    val first = "cloudy-c3-mcp"'
mutate "the webserver MCP page lists the whole catalogue again" ac_cloud-c3-webserver/server/src/sub.rs 'apis::device_mcp_page(apis::data())' 'apis::mcp_page(apis::data())'
mutate "the webserver topology draws the remote nodes again"   ac_cloud-c3-webserver/server/src/topology.rs 'build(&apis::device_catalogue(apis::data()), map()' 'build(&apis::data()["mcp_catalogue"], map()'

[ "$FAILURES" -eq 0 ] && { echo "test-c3-mcp-moved: all green"; exit 0; } || { echo "test-c3-mcp-moved: $FAILURES FAILED"; exit 1; }
