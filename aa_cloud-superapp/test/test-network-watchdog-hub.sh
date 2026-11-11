#!/usr/bin/env bash
# Tester (#1545, #1548): Configs ▸ Agents ▸ Watchdog is an IN-APP hub page (the Watchtower hub page was removed: the concept is Setup ▸ Watchtower - Obsv).
# Static (no device, no build).
#   W1 Agents = Watchdog only; no action (a page, not the C3 APK); Watchdog's hub_sections = Network, Watchtower - Obsv;
#      Watchdog is no Network item; no agents-watchtower page is declared
#   W2 the hub's sections are DERIVED (Sections.hubSections over the subgroups), routed by data (SectionPages hub_sections
#      branch), and drawn with the Cloud ▸ Apps heading and tile components (GroupTitle, TileStrip), not a copy
#   W3 Cloud ▸ Apps ▸ Configs holds Watchdog as the C3-Watchdog app (extapp:c3-watchdog) and a Firewall tile
#      (page:config/firewall) — #1628 redefined this apps-Configs row (Quick Actions, UI | Firewall, WiFi | Store |
#      Account, C3 | Watchdog | About); the config-section Agents hub page (W1) is a separate surface and unchanged.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$KT/SectionPages.kt" "$KT/Sections.kt" "$KT/SubgroupHubFragment.kt"; do
  [ -f "$f" ] || { echo "  ABORT: missing $f"; exit 2; }; done

echo "== W1 + W3: the declaration =="
out="$(python3 - "$APP/build.json" <<'PY'
import json, sys
secs = json.load(open(sys.argv[1]))['ui']['sections']
pages = next(s for s in secs if s['id'] == 'config')['pages']
ag = [p for p in pages if p['id'] == 'agents-watchdog']  # #1610 the hub is relabelled Agents and lives under Device ▸ Configs
print('AGENTS', ' '.join('%s:%s:%r:%s' % (p['id'], p['label'], p.get('action', ''), ','.join(p.get('hub_sections', []))) for p in ag))
print('NETWORK', ','.join(p['id'] for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')))
rows = [g for s in secs for g in s.get('tile_groups', []) if g.get('title') == 'Configs']
tiles = rows[0]['tiles'] if rows else []
print('ROW', '|'.join('%s=%s' % (t.get('label'), t.get('target', '')) for t in tiles if not t.get('separator')))
PY
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -qx "AGENTS agents-watchdog:Agents:'':Network" \
  && ok "the Agents hub (agents-watchdog, relabelled Agents) has hub_sections [Network] and no action (an in-app hub page, #1610)" \
  || bad "agents-watchdog is not exactly the Agents hub (hub_sections [Network]) without an action"
echo "$out" | grep -q '^NETWORK.*watchdog' && bad "Watchdog is still a Network item" || ok "Watchdog left Network"
echo "$out" | grep -q 'Watchdog=extapp:c3-watchdog' && ok "Cloud ▸ Apps ▸ Configs Watchdog opens the C3-Watchdog app (extapp:c3-watchdog, #1628)" \
  || bad "Cloud ▸ Apps ▸ Configs has no Watchdog tile on extapp:c3-watchdog (#1628)"
echo "$out" | grep -q 'Firewall=page:config/firewall' && ok "Cloud ▸ Apps ▸ Configs carries a Firewall tile on page:config/firewall (#1628)" \
  || bad "Cloud ▸ Apps ▸ Configs has no Firewall tile on page:config/firewall (#1628)"

echo "== W2: derived, routed by data, drawn by the shared component =="
grep -qE 'hubSections\.isNotEmpty\(\) -> SubgroupHubFragment\.newInstance\(sectionId, pageId\)' "$KT/SectionPages.kt" \
  && ok "SectionPages routes any hub_sections page to SubgroupHubFragment" || bad "no data-driven hub branch in SectionPages"
grep -qE 'pageId == "(agents-watchdog|agents-watchtower)"' "$KT/SectionPages.kt" && bad "SectionPages names a hub page by id" || ok "no per-id branch for the hub pages"
grep -q 'fun hubSections(' "$KT/Sections.kt" && ok "Sections.hubSections derives the sections" || bad "Sections.hubSections missing"
grep -q 'GroupTitle(sec.title)' "$KT/SubgroupHubFragment.kt" && ok "each section is titled with GroupTitle (Cloud ▸ Apps' heading)" || bad "the hub does not title its sections with GroupTitle"
grep -q 'TileStrip(sec.tiles' "$KT/SubgroupHubFragment.kt" && ok "the hub draws TileStrip (Cloud ▸ Apps' tile component)" || bad "the hub does not reuse TileStrip"
grep -qE 'Firewall|"dns"|Webserver|X-Ray|"xray"|"Network"|"Observability"' "$KT/SubgroupHubFragment.kt" && bad "the hub hard-codes an entry or a section" || ok "the hub names no entry and no section"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
