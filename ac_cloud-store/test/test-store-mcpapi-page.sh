#!/usr/bin/env bash
# #1718 Cloud Store > Access > MCP (device) and APIs (they replace #1211's Apps MCP-API page; nothing it listed is lost).
#   M1 build.json access.pages: mcp ("MCP (device)") first, apis ("APIs") right after it
#   M2 AccessPage routes PAGE_MCP to DeviceMcpPage and PAGE_APIS to DeviceApisPage (one definition each); the superseded
#      AppsMcpApiPage is gone, so there is one list of endpoints, not two
#   M3 assets/apps-mcp-api.json declares the three kinds (mcp, debug, app), the apps group exists in the baked
#      fleet source, every `apps` entry names a member of that group, and the shared debug default has GET + POST
#   M4 the APIs page draws every row through StoreRow.Item with the route count from the model (DeviceAccess apiCount),
#      and reports MISSING for a declared kind an app lacks
#   M5 the asset is GENERATED from each app's build.json::api_endpoints (cloud-android-apps-mcp-api.py check)
#   M6 the pages read ONE model, libs:fleet-data-client DeviceAccess, over the runtime dataset data/fleet/device-access.json,
#      which is GENERATED (cloud-android-device-access.py check), listed in data/fleet/manifest.json, and in which no app that
#      exposes the fleet door counts zero API routes; every endpoint of the asset is in the dataset (field parity)
# Then each planted defect must turn it red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="ab_cloud-libs-shared/libs/appstore/src/main"
CS="ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore"
MODEL="ab_cloud-libs-shared/libs/fleet-data-client/src/main/java/com/diegonmarcos/superapp/fleetdata/DeviceAccess.kt"
DS="data/fleet/device-access.json"
GEN="1_cicd/src/scripts/cloud-android-device-access.py"
INPUTS="aa_cloud-superapp/data/constellation-fleet.json aa_cloud-superapp/data/ipc-mesh.json aa_cloud-superapp/data/fleet-identity.json 1_cicd/src/data/debug-ports.json 1_cicd/src/data/adb-owner.json ac_cloud-c3-webserver/data/api-ipc-map.json ac_cloud-c3-webserver/data/mcp-catalogue.json data/fleet/manifest.json"

check() {
  local R="$1" f=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; f=$((f+1)); }
  local BJ="$R/ac_cloud-store/build.json" AP="$R/$CS/AccessPage.kt" PG="$R/$CS/DeviceAccessPages.kt"
  local AS="$R/$LIB/assets/apps-mcp-api.json" FL="$R/aa_cloud-superapp/data/constellation-fleet.json"
  for x in "$BJ" "$AP" "$PG" "$AS" "$FL" "$R/$MODEL" "$R/$DS" "$R/$GEN"; do [ -f "$x" ] || { bad "missing ${x#$R/}"; return 1; }; done
  python3 -I - "$BJ" <<'PY' && ok "M1 mcp first, apis right after it, labelled MCP (device) and APIs" || bad "M1 Access pages do not start with MCP (device), APIs"
import json, sys
s = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'access')
ids = [p['id'] for p in s['pages']]; lab = {p['id']: p['label'] for p in s['pages']}
sys.exit(0 if ids[:2] == ['mcp', 'apis'] and lab['mcp'] == 'MCP (device)' and lab['apis'] == 'APIs' and 'mcpapi' not in ids and 'mesh' not in ids and 'cloud' not in ids else 1)
PY
  grep -q 'PAGE_MCP -> DeviceMcpPage()' "$AP" && grep -q 'PAGE_APIS -> DeviceApisPage()' "$AP" && grep -q 'const val PAGE_MCP = "mcp"' "$AP" && grep -q 'const val PAGE_APIS = "apis"' "$AP" \
    && ok "M2 AccessPage routes mcp and apis to the device access pages" || bad "M2 AccessPage does not route mcp / apis"
  [ "$(grep -rl --include=*.kt 'fun DeviceApisPage(' "$R"/a[abc]_* 2>/dev/null | wc -l)" = 1 ] && [ "$(grep -rl --include=*.kt 'fun DeviceMcpPage(' "$R"/a[abc]_* 2>/dev/null | wc -l)" = 1 ] \
    && [ -z "$(grep -rl --include=*.kt 'fun AppsMcpApiPage(' "$R"/a[abc]_* 2>/dev/null)" ] && ok "M2 one DeviceApisPage, one DeviceMcpPage, no second endpoint page" || bad "M2 a device access page is missing or duplicated, or AppsMcpApiPage is back"
  python3 -I - "$AS" "$FL" <<'PY' && ok "M3 asset: kinds, apps group members, shared GET+POST debug default" || bad "M3 asset is inconsistent with the fleet or lacks the shared debug default"
import json, sys
a = json.load(open(sys.argv[1]))
groups = json.load(open(sys.argv[2]))['groups']
g = next((x for x in groups if x['id'] == a['apps_group']), None)
ok = g is not None and [k['id'] for k in a['kinds']] == ['mcp', 'debug', 'app']
ok = ok and all(i in g['members'] for i in a['apps'])
ok = ok and all(k in ('mcp', 'debug', 'app') for e in a['apps'].values() for k in e)
ok = ok and {e['method'] for e in a['shared_debug']['endpoints']} >= {'GET', 'POST'} and a['shared_debug']['base'].endswith('/c3-infra-api')
sys.exit(0 if ok else 1)
PY
  grep -q 'StoreRow.Item(' "$PG" && grep -q 'val n = a.apiCount' "$PG" && grep -q 'Mono("MISSING"' "$PG" && grep -q 'doc.zeroApis()' "$PG" \
    && grep -q 'val apiCount: Int get() = ops.size + routes.size + declared.values.sumOf { it.size }' "$R/$MODEL" \
    && ok "M4 rows are StoreRow.Item, counted from the model, and show what is missing" || bad "M4 the APIs page does not draw StoreRow.Item with the model's route count and the missing report"
  python3 -I "$R/1_cicd/src/scripts/cloud-android-apps-mcp-api.py" check "$R" >/dev/null 2>&1 \
    && ok "M5 the asset is generated from the apps' build.json::api_endpoints (single source, not stale)" || bad "M5 apps-mcp-api.json is stale or a declaration is invalid"
  grep -q 'GENERATED by 1_cicd/src/scripts/cloud-android-apps-mcp-api.py' "$AS" && ok "M5 the asset carries its generated header" || bad "M5 the asset was edited by hand"
  grep -q 'FleetData.observe(app, DeviceAccess.DATASET) { DeviceAccess.parse(it) }' "$PG" && ! grep -q 'assets.open' "$PG" && grep -q 'FleetData.get(ctx, DATASET) { parse(it) }' "$R/$MODEL" \
    && ok "M6 the pages read the one model through the fleet data client (no baked copy)" || bad "M6 the device access pages do not read DeviceAccess through FleetData"
  python3 -I "$R/$GEN" check "$R" >/dev/null 2>&1 \
    && ok "M6 the dataset is generated from the declarations (not stale)" || bad "M6 $DS is stale or a declaration is invalid: run python3 $GEN write ."
  python3 -I - "$R/$DS" "$AS" "$R/data/fleet/manifest.json" <<'PY' && ok "M6 dataset: served, no door app counts zero API routes, every asset endpoint present" || bad "M6 the dataset is not served, an app with a door counts zero routes, or an endpoint of the old page is missing"
import json, sys
d = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2])); m = json.load(open(sys.argv[3]))
assert d['_doc'].startswith('GENERATED by 1_cicd/src/scripts/cloud-android-device-access.py') and isinstance(d['schemaVersion'], int)
assert m['shared']['device-access']['tier'] == 3
apps = {x['id']: x for x in d['apps']}
count = lambda x: len(x['ops']) + len(x['routes']) + sum(len(v) for v in x['declared'].values())
assert all(count(x) > 0 for x in d['apps'] if x['door']), [x['id'] for x in d['apps'] if x['door'] and not count(x)]
assert d['kinds'] == a['kinds'] and d['shared_debug'] == a['shared_debug']
for i, kinds in a['apps'].items():
    for k, eps in kinds.items():
        assert apps[i]['declared'][k] == eps, (i, k)
PY
  return $f
}
check "$ROOT"; real=$?
echo "planted defects:"
M=0
mut() {  # mut <label> <file> <from> <to>
  local T; T="$(mktemp -d)"
  ( cd "$ROOT" && for f in ac_cloud-store/build.json "$CS/AccessPage.kt" "$CS/DeviceAccessPages.kt" "$LIB/assets/apps-mcp-api.json" "$MODEL" "$DS" "$GEN" \
      1_cicd/src/scripts/cloud-android-apps-mcp-api.py $INPUTS $(ls -d */build.json */*/build.json 2>/dev/null); do mkdir -p "$T/$(dirname "$f")"; cp "$f" "$T/$f"; done )
  python3 -I - "$T/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:]
s = open(p).read(); assert a in s, a; open(p, 'w').write(s.replace(a, b, 1))
PY
  if check "$T" >/dev/null 2>&1; then echo "  NOT CAUGHT: $1"; M=$((M+1)); else echo "  caught: $1"; fi
  rm -rf "$T"
}
mut "apis route dropped" "$CS/AccessPage.kt" 'PAGE_APIS -> DeviceApisPage()' 'PAGE_APIS -> AndroidPermsPage()'
mut "mcp route dropped" "$CS/AccessPage.kt" 'PAGE_MCP -> DeviceMcpPage()' 'PAGE_MCP -> AndroidPermsPage()'
mut "page order" ac_cloud-store/build.json '"id": "apis"' '"id": "apis2"'
mut "Cloud Perms page back" ac_cloud-store/build.json '"id": "android",' '"id": "cloud",'
mut "shared debug loses POST" $LIB/assets/apps-mcp-api.json '"method": "POST"' '"method": "GET"'
mut "entry for a non-member" $LIB/assets/apps-mcp-api.json '"aa_cloud-superapp": {' '"nonexistent-app": {'
mut "an endpoint declared in the app is not in the asset" aa_cloud-superapp/build.json '"path": "/api/fleet/apps"' '"path": "/api/fleet/apps2"'
mut "asset edited by hand" $LIB/assets/apps-mcp-api.json 'GENERATED by' 'written by hand, was GENERATED by'
mut "missing kinds no longer reported" "$CS/DeviceAccessPages.kt" 'Mono("MISSING"' 'Mono("-"'
mut "route count is a proxy" "$MODEL" 'val apiCount: Int get() = ops.size + routes.size + declared.values.sumOf { it.size }' 'val apiCount: Int get() = declared.values.sumOf { it.size }'
mut "a baked copy instead of the served dataset" "$CS/DeviceAccessPages.kt" 'FleetData.observe(app, DeviceAccess.DATASET) { DeviceAccess.parse(it) }' 'kotlinx.coroutines.flow.flowOf(DeviceAccess.parse(app.assets.open("device-access.json").bufferedReader().readText()))'
mut "dataset edited by hand" "$DS" '"route": "logs"' '"route": "logz"'
mut "an app loses its ops in the dataset" "$DS" '"ops": [' '"ops_gone": ['
mut "a gateway route added to the map but not regenerated" ac_cloud-c3-webserver/data/api-ipc-map.json '"route": "diag"' '"route": "diag2"'
mut "dataset not served" data/fleet/manifest.json '"device-access": {' '"device-access-x": {'
[ "$real" = 0 ] && [ "$M" = 0 ] && { echo "OK: the MCP (device) and APIs pages hold; every planted defect is caught"; exit 0; }
echo "FAIL"; exit 1
