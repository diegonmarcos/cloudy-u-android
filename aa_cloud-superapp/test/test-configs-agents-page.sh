#!/usr/bin/env bash
# Tester: the fleet agent registry, drawn by Configs ▸ Observability ▸ X-Ray (AGENTS group) since the Agents page was removed.
#
#   A1 Configs reads Setup, Agents, Cloudy C3 (the order of build.json groups, #1548/#1554); Cloudy C3 = C3 | Peer Control (Bluetooth is a Setup ▸ Network page);
#      Agents = Watchdog (hub page; Watchtower left); no agents page or tab is declared and Setup ▸ Watchtower - Obsv is X-Ray Device, Logcat Fleet
#      (X-Ray App, Perms, Logcat, About live in Setup ▸ Launcher)
#   A2 page:config/agents, /agents-superapp and /agents-fleet are page_aliases of xray, and each is retired to xray; page:config/watchtower and
#      /agents-watchtower are page_aliases of xray too and agents-watchtower is retired into xray + logcat-fleet (never a 404)
#   A3 X-Ray draws the registry: DevControlFragment declares the AGENTS macro over FleetAgents + AgentStatusReader
#   A4 the old deep links still land: page:config/c3 and page:config/kde are the same ids
#   A5 the registry is baked: FLEET_AGENTS_B64 from data/fleet-agents.json, and that file is
#      what the generator gives today (1_cicd/src/scripts/cloud-android-fleet-agents.py check)
#   A6 every agent declared in a build.json is in the baked file, and this app's own rows are
#      exactly its build.json fleet_agents
#   A7 live status is local only: no HTTP client in the agents package
#
# Static (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
PAGES="$KT/launcher/SectionPages.kt"
FRAG="$KT/devcontrol/DevControlFragment.kt"
READER="$KT/agents/AgentStatusReader.kt"
MODEL="$KT/agents/FleetAgents.kt"
DATA="$APP/data/fleet-agents.json"
GEN="$ROOT/1_cicd/src/scripts/cloud-android-fleet-agents.py"
for f in "$BJ" "$PAGES" "$FRAG" "$READER" "$MODEL" "$DATA" "$GEN" "$APP/app/build.gradle"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f — a check over nothing passes"; exit 2; }
done

echo "== A1: Setup, Agents, Fleet; no agents page =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
pages = sec['pages']; by = {p['id']: p for p in pages}
vis = [p for p in pages if not p.get('hidden') and not p.get('is_action')]
groups = []
for p in vis:
    g = p.get('group', '')
    if not groups or groups[-1] != g: groups.append(g)
probs = []
declared = [g['label'] for g in sec['groups']]
if groups != declared: probs.append('grid groups %r != declared groups %r' % (groups, declared))
apps = [p['label'] for p in vis if p.get('group') == 'Cloudy C3']
if apps != ['C3', 'Peer Control']: probs.append('Cloudy C3 = %r' % apps)
if any(p.get('group') == 'Fleet' for p in pages): probs.append('a page still wears the group Fleet')
for gone in ('agents', 'agents-superapp', 'agents-fleet'):
    if gone in by: probs.append('%s is still declared' % gone)
if declared != ['Setup', 'Agents', 'Cloudy C3']: probs.append('declared groups %r != Setup, Agents, Cloudy C3' % declared)
grid = [p['id'] for p in vis if p.get('group') == 'Setup' and p.get('subgroup') == 'Watchtower - Obsv']
if grid != ['xray', 'logcat-fleet']: probs.append('Setup > Watchtower - Obsv = %r' % grid)
if 'agents-watchtower' in by: probs.append('agents-watchtower is still declared')
agents = [p['id'] for p in vis if p.get('group') == 'Agents']
if agents != ['agents-watchdog']: probs.append('Agents = %r' % agents)
print('; '.join(probs) or 'OK')
PY
)" "Configs groups are build.json's groups (Setup, Agents, Cloudy C3); Cloudy C3 = C3, Peer Control; Agents = Watchdog; no agents page; Setup ▸ Watchtower - Obsv = X-Ray Device, Logcat Fleet"

echo "== A2: the old Agents ids land on X-Ray =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
probs = []
for old in ('agents', 'agents-superapp', 'agents-fleet'):
    if sec.get('page_aliases', {}).get(old) != 'xray': probs.append('page_aliases[%s] = %r' % (old, sec.get('page_aliases', {}).get(old)))
    if sec.get('retired_pages', {}).get(old) != ['xray']: probs.append('retired_pages[%s] = %r' % (old, sec.get('retired_pages', {}).get(old)))
ids = {p['id'] for p in sec['pages']}
for old in ('watchtower', 'agents-watchtower'):
    to = sec.get('page_aliases', {}).get(old)
    if to != 'xray' or to not in ids: probs.append('page_aliases[%s] = %r (must be the declared page xray)' % (old, to))
if sec.get('retired_pages', {}).get('agents-watchtower') != ['xray', 'logcat-fleet']: probs.append('retired_pages[agents-watchtower] = %r' % sec.get('retired_pages', {}).get('agents-watchtower'))
print('; '.join(probs) or 'OK')
PY
)" "page:config/agents, /agents-superapp, /agents-fleet alias and retire to xray"

echo "== A3: X-Ray draws the registry =="
grep -qF 'macro("🤖  AGENTS")' "$FRAG" && grep -qF 'FleetAgents.forScope(FleetAgents.all(), scope)' "$FRAG" \
  && grep -qF 'AgentStatusReader.read(appCtx, it)' "$FRAG" \
  && ok "DevControlFragment (X-Ray) declares AGENTS over FleetAgents + AgentStatusReader" || bad "X-Ray does not draw the agent registry"
grep -qE 'pageId == "agents' "$PAGES" && bad "SectionPages still routes an agents page" || ok "no agents route is left in SectionPages"

echo "== A4: old deep links still land =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
probs = []
if by.get('c3', {}).get('action') != 'extapp:cloud-c3': probs.append('c3 = %r' % by.get('c3'))
if by.get('kde', {}).get('label') != 'Peer Control': probs.append('kde = %r' % by.get('kde'))
print('; '.join(probs) or 'OK')
PY
)" "page:config/c3 and page:config/kde keep their ids, so every old link resolves to the moved pages"

echo "== A5: the registry is baked and current =="
grep -qF 'def fleetAgentsFile = file("../data/fleet-agents.json")' "$APP/app/build.gradle" \
  && grep -qF 'buildConfigField "String", "FLEET_AGENTS_B64"' "$APP/app/build.gradle" \
  && ok "app/build.gradle bakes data/fleet-agents.json as FLEET_AGENTS_B64" || bad "the registry is not baked"
grep -qF 'cloud-android-fleet-agents.py" write' "$APP/data/regen.sh" \
  && ok "data/regen.sh regenerates fleet-agents.json on every build" || bad "regen.sh does not regenerate the registry"
if python3 "$GEN" check "$ROOT" >/dev/null 2>&1; then ok "data/fleet-agents.json equals the generator's output"
else bad "data/fleet-agents.json is stale or a declaration is invalid: $(python3 "$GEN" check "$ROOT" | head -3)"; fi

echo "== A6: every declared agent is in the baked file =="
check "$(python3 - "$ROOT" "$DATA" <<'PY'
import glob, json, os, sys
root, data = sys.argv[1], json.load(open(sys.argv[2]))
ids = {a['id'] for a in data['agents']}
probs = []
for p in sorted(glob.glob(root + '/*/build.json') + glob.glob(root + '/*/*/build.json')):
    try: bj = json.load(open(p))
    except Exception: continue
    for r in bj.get('fleet_agents', []):
        if 'from' in r: continue
        want = '%s/%s' % (bj.get('name'), r.get('id'))
        if want not in ids: probs.append('%s missing' % want)
self_bj = json.load(open(root + '/aa_cloud-superapp/build.json'))
mine = ['cloud-superapp/' + r['id'] for r in self_bj['fleet_agents']]
got = [a['id'] for a in data['agents'] if a['app'] == 'cloud-superapp']
if mine != got: probs.append('SuperApp rows %r != declared %r' % (got, mine))
if not any(a['status']['source'] == 'provider' for a in data['agents']):
    probs.append('no agent reads live status through a provider (Cloud Drive Screenshot agent)')
print('; '.join(probs) or 'OK')
PY
)" "every build.json fleet_agents row (and every expanded list) is a row of fleet-agents.json; SuperApp's tab is exactly its own declaration"

echo "== A7: live status is local only =="
if grep -nE 'HttpURLConnection|OkHttp|okhttp3|URL\(|openConnection|Retrofit|ktor' "$FRAG" "$READER" "$MODEL" >/dev/null; then
  bad "the agents package opens a network connection: $(grep -nE 'HttpURLConnection|OkHttp|okhttp3|URL\(|openConnection|Retrofit|ktor' "$FRAG" "$READER" "$MODEL" | head -2)"
else ok "no HTTP client in the agents package — WorkManager, services, PackageManager and providers only"; fi
grep -qF 'com.diegonmarcos.clouddrive.permission.READ_AGENT_RESULTS' "$APP/app/src/main/AndroidManifest.xml" \
  && ok "the manifest asks for Cloud Drive's agent-results permission (its provider's guard)" \
  || bad "no uses-permission for Cloud Drive's READ_AGENT_RESULTS — the Screenshot agent's state would answer SecurityException"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
