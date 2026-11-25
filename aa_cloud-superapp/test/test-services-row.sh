#!/usr/bin/env bash
# Tester: Cloud > Apps: the Services rows (#1473, #1476, #1902: Services · City = Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels, Services · Leisure = News | Events Media Center Social Games, AGI ends | Search KG Store) sit right after Projects and before AGI (#1806: Events, Games, Jobs & Business, Real Estate, Health, Money, Hotels, Transports, City Mobility are coming_soon placeholders, order from ui.services_order; '|' = declared separator tiles), News left Data Apps, and both
# tiles resolve to a fleet app (ui.external_apps) and a constellation member. Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; FLEET="$APP/data/constellation-fleet.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
check() {
  local bj="$1" fleet="$2" G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
  local C="$G | select(.title == \"Services · City\")" L="$G | select(.title == \"Services · Leisure\")" A="$G | select(.title == \"AGI\")"
  eq "group order Me, Services · City, Services · Leisure, AGI, Setup, Projects (#1916 MOVED Projects after Setup; was #1769, #1902)" "$(jq -r "[$G | .title] | join(\",\")" "$bj" | grep -o 'Me,Services · City,Services · Leisure,AGI,Setup,Projects')" "Me,Services · City,Services · Leisure,AGI,Setup,Projects"
  eq "no group keeps the old title Services (split in City and Leisure, #1902)" "$(jq -r "[$G | select(.title == \"Services\")] | length" "$bj")" "0"
  eq "no group keeps the old title Weby Services (no Markets)" "$(jq -r "[$G | select(.title == \"Weby Services\")] | length" "$bj")" "0"
  eq "no group keeps the old title Weby Services & Markets (now Services, #1756)" "$(jq -r "[$G | select(.title == \"Weby Services & Markets\")] | length" "$bj")" "0"
  eq "Services · City row reads Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels (exact order, dividers declared)" "$(jq -r "$C | [.tiles[] | if .separator then \"|\" else .label end] | join(\" \")" "$bj")" "Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels"
  eq "Services · Leisure row reads News | Events Media Center Social Games (exact order, divider declared)" "$(jq -r "$L | [.tiles[] | if .separator then \"|\" else .label end] | join(\" \")" "$bj")" "News | Events Media Center Social Games"
  eq "the AGI row ends | Search KG Store (#1902: they left Services)" "$(jq -r "$A | [.tiles[] | if .separator then \"|\" else .label end] | join(\" \")" "$bj")" "SI AI | Code Tools | MyTerm. Terminal | Search KG Store"
  eq "City dividers are separator tiles at positions 1 and 6" "$(jq -r "$C | [.tiles | to_entries[] | select(.value.separator == true) | .key] | join(\",\")" "$bj")" "1,6"
  eq "Leisure divider is a separator tile at position 1" "$(jq -r "$L | [.tiles | to_entries[] | select(.value.separator == true) | .key] | join(\",\")" "$bj")" "1"
  eq "the real apps open shopping (City), news, media-center and the mySocials web link (Leisure), search and kg-store (AGI tail)" "$(jq -r "[($C), ($L), ($A | .tiles |= map(select(.id == \"search\" or .id == \"kg-store\")))] | map(.tiles[] | select((.separator | not) and (.coming_soon | not)) | .target) | join(\" \")" "$bj")" "extapp:cloud-shopping extapp:cloud-news extapp:cloud-media-center https://diegonmarcos.github.io/mySocials/ extapp:cloud-search extapp:cloud-kg-store"
  eq "exactly Money, Health, Real Estate, Jobs & Business, City Mobility, Transports, Hotels are the City coming_soon placeholders (#1806)" "$(jq -r "$C | [.tiles[] | select(.coming_soon == true) | .label] | join(\",\")" "$bj")" "Money,Health,Real Estate,Jobs & Business,City Mobility,Transports,Hotels"
  eq "exactly Events and Games are the Leisure coming_soon placeholders (#1806)" "$(jq -r "$L | [.tiles[] | select(.coming_soon == true) | .label] | join(\",\")" "$bj")" "Events,Games"
  eq "placeholders have no target and no icon-less entry" "$(jq -r "[($C), ($L)] | map(.tiles[] | select(.coming_soon == true) | (.target // \"\") + (.icon // \"?\" | if . == \"?\" then \"?\" else \"\" end)) | join(\"\")" "$bj")" ""
  eq "Social is a tile of Leisure with an icon, opening the mySocials page (#1904)" "$(jq -r "$L | .tiles[] | select(.id == \"social\") | .target + \" \" + (.icon // \"NOICON\")" "$bj")" "https://diegonmarcos.github.io/mySocials/ ic_sparkles"
  eq "no fleet app or external app was invented for the placeholders" "$(jq -r '[.ui.external_apps[] | select(.id | test("events|mobility|jobs"))] | length' "$bj")$(jq -r '[.apps[]? | select(.id | test("^(events|mobility|jobs)$"))] | length' "$FLEET")" "00"
  eq "Search and KG Store left Services (no Services group lists them) and sit in AGI" "$(jq -r "[$G | select(.title | startswith(\"Services\")) | .tiles[] | select(.label == \"Search\" or .label == \"KG Store\")] | length" "$bj")$(jq -r "$A | [.tiles[] | select(.label == \"Search\" or .label == \"KG Store\")] | length" "$bj")" "02"
  eq "News left Data (renamed from 'Data Apps', #1627)" "$(jq -r "$G | select(.title == \"Data\") | [.tiles[] | select(.label == \"News\")] | length" "$bj")" "0"
  eq "the Shopping app resolves in ui.external_apps" "$(jq -r '[.ui.external_apps[] | select(.id == "cloud-shopping") | .install_package] | join(",")' "$bj")" "com.diegonmarcos.cloudshopping"
  eq "Store > Cloud > Apps groups Shopping under Services · City" "$(jq -r '.app_categories[] | select(.label == "Services · City") | .members | join(",")' "$fleet")" "shopping"
  eq "Store > Cloud > Apps groups News and Media Center under Services · Leisure" "$(jq -r '.app_categories[] | select(.label == "Services · Leisure") | .members | join(",")' "$fleet")" "news,media-center"
  eq "Store > Cloud > Apps groups Search and KG Store under AGI (#1902)" "$(jq -r '.app_categories[] | select(.label == "AGI") | [.members[] | select(. == "search" or . == "kg-store")] | join(",")' "$fleet")" "search,kg-store"
  eq "Data (renamed from 'Data Apps', #1627) no longer lists news in the Store" "$(jq -r '.app_categories[] | select(.label == "Data") | .members | index("news")' "$fleet")" "null"
}
echo "[services-row] the real tree"; check "$BJ" "$FLEET"
echo "[services-row] mutations (each must turn the tester red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <jq filter on build.json>
  jq "$2" "$BJ" > "$W/bj.json"; before=$FAIL; ( check "$W/bj.json" "$FLEET" ) >"$W/out" 2>&1; if grep -q FAIL "$W/out"; then ok "mutant '$1' is red"; else bad "mutant '$1' stayed green"; fi
}
mut "City dropped" '(.ui.sections[] | select(.id == "cloud") | .tile_groups) |= map(select(.title != "Services · City"))'
mut "Leisure dropped" '(.ui.sections[] | select(.id == "cloud") | .tile_groups) |= map(select(.title != "Services · Leisure"))'
mut "old title restored"    '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · City") | .title) = "Services"'
mut "divider removed"       '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · City") | .tiles) |= map(select(.separator | not))'
mut "News back in Data" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Data") | .tiles) += [{"id":"news","label":"News","target":"extapp:cloud-news"}]'
mut "Shopping tile retargeted" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · City") | .tiles[2].target) = "extapp:cloud-news"'
mut "Jobs placeholder made live" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · City") | .tiles[] | select(.label == "Jobs & Business") | .coming_soon) = false'
mut "Social retargeted" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · Leisure") | .tiles[] | select(.id == "social") | .target) = "page:phone/apps"'
mut "Search back in Services" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services · Leisure") | .tiles) += [{"id":"search","label":"Search","target":"extapp:cloud-search"}]'
mut "Shopping app unresolved"  '(.ui.external_apps) |= map(select(.id != "cloud-shopping"))'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
