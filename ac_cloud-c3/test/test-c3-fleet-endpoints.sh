#!/usr/bin/env bash
# #1718 Cloud C3 > Infra > APIs > C3 Endpoints: the fleet services' public and mesh URLs, MOVED here from Cloud C3 Webserver > API (its
# Public Endpoints page and the fleet group of its Mesh Endpoints page; the webserver shows only the phone now). Nothing lost:
#   F1 PARITY: every row the webserver listed exists here. The webserver's rows are recomputed from the declaration with ITS OWN
#      rules, read out of server/src/apis.rs (public_url / mesh_url: scheme, domain, port, path) and checked to still be those
#      rules: for each service with a public URL the row (id, kind, policy, health, URL) and for each with a mesh URL the row
#      (id, policy, URL) is in data/api_providers.json::fleet_endpoints, and no service of the declaration is missing.
#   F2 the block is GENERATED (data/gen_fleet_endpoints.py check) from ac_cloud-account/build.json::fleet_endpoints.services.
#   F3 the page is declared and drawn: group `c3_endpoints` answers page api_endpoints, the APIs row lists it, InfraPages draws the group
#      through FleetEndpointsScreen with every field (FleetEndpoints.lines / links), and Kotlin spells no service and no page id.
#   F4 the webserver no longer shows them: no Public Endpoints sub-page or page in its nav, no api-public route, and its own Mesh
#      page is the device page (device_mesh_page); the off-device /endpoints JSON still carries both for cloudy-c3-mcp.
#   F5 no secret-shaped text in the block.
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
c3, web, acc = root + "/ac_cloud-c3", root + "/ac_cloud-c3-webserver", root + "/ac_cloud-account/build.json"
bad = []
ap = json.load(open(c3 + "/data/api_providers.json", encoding="utf-8"))
fe = ap.get("fleet_endpoints") or {}
rows = {s["id"]: s for s in fe.get("services") or []}
decl = json.load(open(acc, encoding="utf-8"))["fleet_endpoints"]["services"]
rs = open(web + "/server/src/apis.rs", encoding="utf-8").read()
# F1 the webserver's own URL rules, as written in apis.rs
if 'format!("{}://{}{}", svc["public_scheme"].as_str().unwrap_or("https"), d, svc["public_path"].as_str().unwrap_or(""))' not in rs:
    bad.append("F1 apis.rs::public_url changed: re-derive the parity rule")
if 'format!("{}://{}{}{}", svc["mesh_scheme"].as_str().unwrap_or("http"), d, port, svc["mesh_path"].as_str().unwrap_or(""))' not in rs:
    bad.append("F1 apis.rs::mesh_url changed: re-derive the parity rule")
def pub(s):
    d = s.get("public_domain")
    return "%s://%s%s" % (s.get("public_scheme") or "https", d, s.get("public_path") or "") if d else None
def mesh(s):
    d = s.get("mesh_dns")
    if not d: return None
    return "%s://%s%s%s" % (s.get("mesh_scheme") or "http", d, ":%d" % s["mesh_port"] if isinstance(s.get("mesh_port"), int) else "", s.get("mesh_path") or "")
npub = nmesh = 0
for s in decl:
    r = rows.get(s["id"])
    if r is None:
        bad.append("F1 service %s of the declaration is not in Cloud C3" % s["id"]); continue
    if pub(s):   # a row of the webserver's Public Endpoints page: head id, badges kind + policy, line health, link URL
        npub += 1
        if (r["public_url"], r["kind"], r["policy"], r["health"]) != (pub(s), s.get("kind", ""), s.get("policy", ""), s.get("health", "")):
            bad.append("F1 the Public Endpoints row of %s differs here" % s["id"])
    elif r["public_url"] is not None:
        bad.append("F1 %s has a public URL here that the declaration lacks" % s["id"])
    if mesh(s):  # a row of the webserver's fleet mesh group: head id, badge policy, link URL
        nmesh += 1
        if (r["mesh_url"], r["policy"]) != (mesh(s), s.get("policy", "")):
            bad.append("F1 the mesh row of %s differs here" % s["id"])
    elif r["mesh_url"] is not None:
        bad.append("F1 %s has a mesh URL here that the declaration lacks" % s["id"])
if len(rows) != len(decl): bad.append("F1 Cloud C3 lists %d services, the declaration %d" % (len(rows), len(decl)))
if npub < 3 or nmesh < 10: bad.append("F1 the declaration lists %d public and %d mesh URLs: too few to be the real one" % (npub, nmesh))
# F2
if subprocess.run([sys.executable, "-I", c3 + "/data/gen_fleet_endpoints.py", "check", root], capture_output=True).returncode != 0:
    bad.append("F2 data/api_providers.json::fleet_endpoints is stale or hand-edited: run python3 ac_cloud-c3/data/gen_fleet_endpoints.py write")
# F3
bj = json.load(open(c3 + "/build.json", encoding="utf-8"))
infra = next(s for s in bj["ui"]["sections"] if s["id"] == "infra")
grp = next((g for g in ap["groups"] if g["id"] == fe.get("group")), None)
if grp is None: bad.append("F3 fleet_endpoints names group %r, which is not declared" % fe.get("group"))
else:
    if grp["page"] not in [p["id"] for p in infra["pages"]]: bad.append("F3 build.json declares no page %s" % grp["page"])
    if grp["page"] not in next(g for g in infra["groups"] if g["id"] == "apis")["pages"]: bad.append("F3 the APIs row does not list %s" % grp["page"])
kdir = c3 + "/app/src/main/java/com/diegonmarcos/cloudc3/"
ip = open(kdir + "pages/InfraPages.kt", encoding="utf-8").read()
fk = open(kdir + "pages/FleetEndpoints.kt", encoding="utf-8").read()
if "if (it.id == fleet.group) FleetEndpointsScreen(section, it, fleet)" not in ip or "Declarations.fleetEndpoints" not in ip:
    bad.append("F3 InfraPages.kt does not draw the fleet group through FleetEndpointsScreen")
if "FleetEndpoints.lines(s), links = FleetEndpoints.links(s)" not in ip or "listOf(s.kind, s.policy)" not in ip:
    bad.append("F3 the row does not draw every field (kind, policy, lines, links)")
for field in ("s.publicUrl", "s.meshUrl", "s.health", "s.auth", "s.aliases", "s.routePaths", "s.source"):
    if field not in fk.split("fun lines(")[1]: bad.append("F3 FleetEndpoints.lines drops %s" % field.split(".")[1])
code = re.sub(r"/\*.*?\*/|//[^\n]*", "", fk + ip, flags=re.S)
for s in decl:
    if '"%s"' % s["id"] in code: bad.append("F3 Kotlin spells the service %s" % s["id"])
if grp and '"%s"' % grp["page"] in code: bad.append("F3 Kotlin spells the page id %s" % grp["page"])
# F4
wb = json.load(open(web + "/build.json", encoding="utf-8"))
if any(p["id"] == "public" or "Public" in p["label"] for p in wb["subpages"]): bad.append("F4 the webserver still declares a Public Endpoints sub-page")
api = next(s for s in wb["ui"]["sections"] if s["id"] == "api")
if any(p["id"] == "public" for p in api["pages"]): bad.append("F4 the webserver's API section still has a Public Endpoints page")
if "api-public" in open(web + "/server/src/app.rs", encoding="utf-8").read(): bad.append("F4 the webserver still routes /__app__/api/sub/api-public")
sub = open(web + "/server/src/sub.rs", encoding="utf-8").read()
if "apis::device_mesh_page(&m, apis::apps())" not in sub or "apis::public_page" in sub: bad.append("F4 the webserver's own Mesh page still lists the fleet services, or a page still draws the public URLs")
ml = open(web + "/server/src/meshlogs.rs", encoding="utf-8").read()
if "apis::public_page(apis::data())" not in ml or "apis::mesh_page(apis::data(), m, apps)" not in ml: bad.append("F4 the off-device /endpoints JSON lost the public or mesh page (cloudy-c3-mcp reads it)")
# F5
SECRET = re.compile(r"sk-or-v1-[A-Za-z0-9]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{20,}|(?i:bearer) +[A-Za-z0-9._~+/=-]{24,}|://[^/\s]*:[^/\s]*@")
if SECRET.search(json.dumps(fe)): bad.append("F5 the fleet endpoints block holds a secret-shaped value")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY

if python3 -I "$WORK/check.py" "$ROOT"; then
  pass "F1-F5 every fleet service row the webserver listed is in Cloud C3 > Infra > APIs > C3 Endpoints, generated, declared, drawn whole, and gone from the webserver's own pages"
else
  fail "F1-F5 the fleet endpoints moved to Cloud C3 do not hold"
fi

# ── M: mutation-proof ──────────────────────────────────────────────────────
FILES="ac_cloud-c3/data/api_providers.json ac_cloud-c3/data/gen_fleet_endpoints.py ac_cloud-c3/build.json ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages/FleetEndpoints.kt ac_cloud-account/build.json ac_cloud-c3-webserver/build.json ac_cloud-c3-webserver/server/src/apis.rs ac_cloud-c3-webserver/server/src/app.rs ac_cloud-c3-webserver/server/src/sub.rs ac_cloud-c3-webserver/server/src/meshlogs.rs"
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
mutate "a public URL lost in the move"             ac_cloud-c3/data/api_providers.json '"public_url": "https://auth.diegonmarcos.com"' '"public_url": null'
mutate "a mesh URL changed in the move"            ac_cloud-c3/data/api_providers.json '"mesh_url": "http://gitea.mesh.diegonmarcos.com:3002"' '"mesh_url": "http://gitea.mesh.diegonmarcos.com:3003"'
mutate "a service dropped in the move"             ac_cloud-c3/data/api_providers.json '{"id": "languagetool", ' '{"id": "languagetool-x", '
mutate "a new fleet service not regenerated"       ac_cloud-account/build.json '"id": "gitea"' '"id": "gitea2"'
mutate "the health path lost"                      ac_cloud-c3/data/api_providers.json '"health": "/api/healthz"' '"health": ""'
mutate "the group no longer drawn"                 ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt 'if (it.id == fleet.group) FleetEndpointsScreen(section, it, fleet) else ' ''
mutate "a field dropped from the row"              ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages/FleetEndpoints.kt '        "Health: ${s.health.ifBlank { "not declared" }}",' ''
mutate "the page left the APIs row"                ac_cloud-c3/build.json '              "api_scraper",
              "api_endpoints"' '              "api_scraper"'
mutate "Kotlin spells a service"                   ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/pages/FleetEndpoints.kt 'object FleetEndpoints {' 'object FleetEndpoints {
    val first = "gitea"'
mutate "the webserver shows Public Endpoints again" ac_cloud-c3-webserver/build.json '{ "tab": "api", "id": "mesh", "label": "Mesh Endpoints"' '{ "tab": "api", "id": "public", "label": "Public Endpoints", "route": "/x" }, { "tab": "api", "id": "mesh", "label": "Mesh Endpoints"'
mutate "the webserver Mesh page lists the fleet again" ac_cloud-c3-webserver/server/src/sub.rs 'apis::device_mesh_page(&m, apis::apps())' 'apis::mesh_page(apis::data(), &m, apis::apps())'
mutate "the off-device JSON lost the public URLs"  ac_cloud-c3-webserver/server/src/meshlogs.rs '"public" => Resp::json(200, apis::public_page(apis::data()).to_string()),' ''
mutate "a credential in a URL"                     ac_cloud-c3/data/api_providers.json '"public_url": "https://git.diegonmarcos.com"' '"public_url": "https://user:pw@git.diegonmarcos.com"'

[ "$FAILURES" -eq 0 ] && { echo "test-c3-fleet-endpoints: all green"; exit 0; } || { echo "test-c3-fleet-endpoints: $FAILURES FAILED"; exit 1; }
