#!/usr/bin/env bash
# Tester (#1545): Configs ▸ Setup ▸ Network ▸ Watchdog is the FIRST Network entry and an IN-APP hub page.
# Static (no device, no build).
#   W1 Watchdog is the first visible Network page, has no action (a page, not the C3-Watchdog APK) and declares hub_of_subgroup Network
#   W2 the hub's grid is DERIVED (Sections.hubTiles over the subgroup), routed by data (SectionPages hub_of_subgroup branch),
#      and drawn with the Cloud ▸ Apps tile component (TileStrip), not a copy
#   W3 Cloud ▸ Apps ▸ Configs holds Watchdog (page:config/watchdog) where Firewall was, and no Firewall tile
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
net = [p for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')]
w = net[0] if net else {}
print('FIRST', w.get('id'), w.get('label'), repr(w.get('action', '')), w.get('hub_of_subgroup'))
rows = [g for s in secs for g in s.get('tile_groups', []) if g.get('title') == 'Configs']
tiles = rows[0]['tiles'] if rows else []
print('ROW', '|'.join('%s=%s' % (t.get('label'), t.get('target', '')) for t in tiles if not t.get('separator')))
PY
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -qx "FIRST watchdog Watchdog '' Network" && ok "Watchdog is the first Network page, an in-app page, hub over Network" \
  || bad "the first Network page is not the in-app Watchdog hub (hub_of_subgroup Network, no action)"
echo "$out" | grep -q '|Watchdog=page:config/watchdog|' && ok "Cloud ▸ Apps ▸ Configs opens page:config/watchdog" \
  || bad "Cloud ▸ Apps ▸ Configs has no Watchdog tile on page:config/watchdog"
echo "$out" | grep -q 'Firewall=' && bad "Cloud ▸ Apps ▸ Configs still has a Firewall tile" || ok "Cloud ▸ Apps ▸ Configs no longer carries Firewall"

echo "== W2: derived, routed by data, drawn by the shared component =="
grep -qE 'hubOfSubgroup\.isNotBlank\(\) -> SubgroupHubFragment\.newInstance\(sectionId, pageId\)' "$KT/SectionPages.kt" \
  && ok "SectionPages routes any hub_of_subgroup page to SubgroupHubFragment" || bad "no data-driven hub branch in SectionPages"
grep -q 'pageId == "watchdog"' "$KT/SectionPages.kt" && bad "SectionPages names the watchdog page by id" || ok "no per-id branch for watchdog"
grep -q 'fun hubTiles(' "$KT/Sections.kt" && ok "Sections.hubTiles derives the tiles" || bad "Sections.hubTiles missing"
grep -q 'TileStrip(tiles' "$KT/SubgroupHubFragment.kt" && ok "the hub draws TileStrip (Cloud ▸ Apps' tile component)" || bad "the hub does not reuse TileStrip"
grep -qE 'Firewall|"dns"|Webserver' "$KT/SubgroupHubFragment.kt" && bad "the hub hard-codes a Network entry" || ok "the hub names no Network entry"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
