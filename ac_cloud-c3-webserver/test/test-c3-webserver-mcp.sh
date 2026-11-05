#!/usr/bin/env bash
# #1495 cloud-c3-webserver API > MCP: the first sub-page is the MCP tool catalogue, generated from the declarations.
#
#   MC1 build.json::subpages lists MCP FIRST in the API tab, its source is api.sources.mcp_catalogue, and app.rs answers it.
#   MC2 data/mcp-catalogue.json is the generator's output shape: every server has name, auth, tools_count; cloudy-c3-mcp lists every tool
#       with the API route (tree path) of its page, a tree method and a node that exists in ac_cloud-c3/data/c3_hierarchy.json.
#   MC3 the merged phone tools are the peers-control group of cloudy-c3-mcp, and cloudy-android-mcp / cloud-superapp-mcp are its aliases.
#   MC4 when CONTAINERS_DIR (cloudy-u-containers) and LINUX_REPO (the cloudy-u-linux da_my-ai/data/claude dir) are set, the committed file
#       equals a fresh run of data/gen_mcp_catalogue.py (the page is generated, never typed).
#   M   a mutation of each fails the check.
# OWN-SOURCE ONLY. python3.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/check.py" <<'PY'
import json, sys
app = sys.argv[1]
b = json.load(open(app + "/build.json"))
cat = json.load(open(app + "/data/mcp-catalogue.json"))
hier = json.load(open(app + "/../ac_cloud-c3/data/c3_hierarchy.json"))
bad = []
api = [p for p in b["subpages"] if p["tab"] == "api"]
if not api or api[0]["id"] != "mcp" or api[0]["label"] != "MCP":
    bad.append("MC1 the first API sub-page is %r, not MCP" % (api[0]["id"] if api else None))
if not b["api"]["sources"].get("mcp_catalogue", "").startswith("ac_cloud-c3-webserver/data/mcp-catalogue.json#"):
    bad.append("MC1 api.sources.mcp_catalogue does not point at the generated catalogue")
if 'path: "/__app__/api/sub/api-mcp"' not in open(app + "/server/src/app.rs").read():
    bad.append("MC1 app.rs has no route for the MCP sub-page")
nodes = set()
def walk(ns, pre=""):
    for n in ns:
        p = pre + n["id"]
        if n.get("children"): walk(n["children"], p + "/")
        else: nodes.add(p)
walk(hier["sections"])
srv = {s["name"]: s for s in cat["servers"]}
for s in cat["servers"]:
    for k in ("name", "auth", "tools_count", "aliases", "tools"):
        if k not in s: bad.append("MC2 server %r lacks %s" % (s.get("name"), k))
c3 = srv.get("cloudy-c3-mcp")
if not c3 or len(c3["tools"]) < 150 or c3["tools_count"] != len(c3["tools"]):
    bad.append("MC2 cloudy-c3-mcp does not list its tools")
else:
    for t in c3["tools"]:
        if t["node"] not in nodes: bad.append("MC2 tool %s names node %s which the hierarchy does not have" % (t["tool"], t["node"]))
        if not t["route"].startswith("/" + t["node"]) or not t["tree"].startswith("c3_"): bad.append("MC2 tool %s has no route or tree method" % t["tool"])
    peers = {t["tool"] for t in c3["tools"] if t["tool"].startswith("peers.")}
    want = {"peers." + x for x in "apps logcat crashes docs wake call shell logcat_full grant force_stop input screencap adb_pair adb_autoconnect adb_status".split()}
    if peers != want: bad.append("MC3 the peers-control tools are %s, want %s" % (sorted(peers), sorted(want)))
    if not {"cloudy-android-mcp", "cloud-superapp-mcp"} <= set(c3["aliases"]): bad.append("MC3 cloudy-android-mcp and cloud-superapp-mcp are not aliases of cloudy-c3-mcp")
if "cloudy-android-mcp" in srv: bad.append("MC3 cloudy-android-mcp is still a server of its own")
import re
if re.search(r"(?i)bearer [a-z0-9._-]{16,}|-----BEGIN", json.dumps(cat)): bad.append("MC2 a secret-shaped value in the catalogue")
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
python3 -I "$WORK/check.py" "$APP" && pass "MC1-MC3 the MCP sub-page is first, declared, answered; the catalogue lists every cloudy-c3-mcp tool with route, the peers-control group and the aliases" || fail "MC1-MC3 the MCP sub-page or its catalogue"

if [ -n "${CONTAINERS_DIR:-}" ] && [ -n "${LINUX_REPO:-}" ]; then
  python3 -I "$APP/data/gen_mcp_catalogue.py" --containers "$CONTAINERS_DIR" --linux "$LINUX_REPO" --check && pass "MC4 the committed catalogue equals a fresh generator run" || fail "MC4 the committed catalogue is stale"
else
  echo "  SKIP  MC4 needs CONTAINERS_DIR and LINUX_REPO (the generator's inputs); the shape checks above still ran"
fi

mutate() { # title file from to
  local d="$WORK/m$RANDOM"; mkdir -p "$d/server/src" "$d/data" "$d/../ac_cloud-c3/data"
  cp "$APP/build.json" "$d/"; cp "$APP/server/src/app.rs" "$d/server/src/"; cp "$APP/data/mcp-catalogue.json" "$d/data/"; cp "$APP/../ac_cloud-c3/data/c3_hierarchy.json" "$d/../ac_cloud-c3/data/"
  python3 - "$d/$2" "$3" "$4" <<'PY' || { fail "M: '$1' did not apply"; return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p).read(); n = s.replace(a, b, 1); assert n != s; open(p, "w").write(n)
PY
  if python3 -I "$WORK/check.py" "$d" >/dev/null 2>&1; then fail "M: '$1' stayed GREEN"; else pass "M: '$1' goes red"; fi
}
mutate "MCP no longer first"            build.json '{ "tab": "api", "id": "mcp", "label": "MCP", "route": "/__app__/api/sub/api-mcp" },' ''
mutate "a tool on a node that is gone"  data/mcp-catalogue.json '"node": "infra/personal/control"' '"node": "infra/personal/gone"'
mutate "an alias dropped"               data/mcp-catalogue.json '"cloudy-android-mcp"' '"cloudy-android-x"'
mutate "the route row lost"             server/src/app.rs 'path: "/__app__/api/sub/api-mcp"' 'path: "/__app__/api/sub/api-gone"'

[ "$FAILURES" -eq 0 ] && { echo "test-c3-webserver-mcp: all green"; exit 0; } || { echo "test-c3-webserver-mcp: $FAILURES FAILED"; exit 1; }
