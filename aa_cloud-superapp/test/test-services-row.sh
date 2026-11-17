#!/usr/bin/env bash
# Tester: Cloud > Apps: the Services row (#1473, #1476) sits right after Me and before AGI and reads Search | News Events Media Center | Shopping Mobility & Travel Jobs & Business Real Estate Money | KG Store (Events, Mobility & Travel, Jobs & Business, Real Estate, Money are coming_soon placeholders; '|' = declared separator tiles), News left Data Apps, and both
# tiles resolve to a fleet app (ui.external_apps) and a constellation member. Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; FLEET="$APP/data/constellation-fleet.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
check() {
  local bj="$1" fleet="$2" G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
  local W="$G | select(.title == \"Services\")"
  eq "group order Me, Projects, Services, AGI (#1769)" "$(jq -r "[$G | .title] | join(\",\")" "$bj" | grep -o 'Me,Projects,Services,AGI')" "Me,Projects,Services,AGI"
  eq "no group keeps the old title Weby Services (no Markets)" "$(jq -r "[$G | select(.title == \"Weby Services\")] | length" "$bj")" "0"
  eq "no group keeps the old title Weby Services & Markets (now Services, #1756)" "$(jq -r "[$G | select(.title == \"Weby Services & Markets\")] | length" "$bj")" "0"
  eq "Services row reads Search | News Events Media Center | Shopping Mobility & Travel Jobs & Business Real Estate Money | KG Store (exact order, dividers declared)" "$(jq -r "$W | [.tiles[] | if .separator then \"|\" else .label end] | join(\" \")" "$bj")" "Search | News Events Media Center | Shopping Mobility & Travel Jobs & Business Real Estate Money | KG Store"
  eq "dividers are separator tiles at positions 1, 5 and 11" "$(jq -r "$W | [.tiles | to_entries[] | select(.value.separator == true) | .key] | join(\",\")" "$bj")" "1,5,11"
  eq "the real apps open search, news, media-center, shopping, kg-store in order" "$(jq -r "$W | [.tiles[] | select((.separator | not) and (.coming_soon | not)) | .target] | join(\" \")" "$bj")" "extapp:cloud-search extapp:cloud-news extapp:cloud-media-center extapp:cloud-shopping extapp:cloud-kg-store"
  eq "exactly Events, Mobility & Travel, Jobs & Business, Real Estate, Money are coming_soon placeholders" "$(jq -r "$W | [.tiles[] | select(.coming_soon == true) | .label] | join(\",\")" "$bj")" "Events,Mobility & Travel,Jobs & Business,Real Estate,Money"
  eq "placeholders have no target and no icon-less entry" "$(jq -r "$W | [.tiles[] | select(.coming_soon == true) | (.target // \"\") + (.icon // \"?\" | if . == \"?\" then \"?\" else \"\" end)] | join(\"\")" "$bj")" ""
  eq "no fleet app or external app was invented for the placeholders" "$(jq -r '[.ui.external_apps[] | select(.id | test("events|mobility|jobs"))] | length' "$bj")$(jq -r '[.apps[]? | select(.id | test("^(events|mobility|jobs)$"))] | length' "$FLEET")" "00"
  eq "Search and KG Store left AGI" "$(jq -r "$G | select(.title == \"AGI\") | [.tiles[] | select(.label == \"Search\" or .label == \"KG Store\")] | length" "$bj")" "0"
  eq "News left Data (renamed from 'Data Apps', #1627)" "$(jq -r "$G | select(.title == \"Data\") | [.tiles[] | select(.label == \"News\")] | length" "$bj")" "0"
  eq "the Shopping app resolves in ui.external_apps" "$(jq -r '[.ui.external_apps[] | select(.id == "cloud-shopping") | .install_package] | join(",")' "$bj")" "com.diegonmarcos.cloudshopping"
  eq "Store > Cloud > Apps groups Search, News, Media Center, Shopping, KG Store under Services" "$(jq -r '.app_categories[] | select(.label == "Services") | .members | join(",")' "$fleet")" "search,news,media-center,shopping,kg-store"
  eq "Data (renamed from 'Data Apps', #1627) no longer lists news in the Store" "$(jq -r '.app_categories[] | select(.label == "Data") | .members | index("news")' "$fleet")" "null"
}
echo "[services-row] the real tree"; check "$BJ" "$FLEET"
echo "[services-row] mutations (each must turn the tester red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <jq filter on build.json>
  jq "$2" "$BJ" > "$W/bj.json"; before=$FAIL; ( check "$W/bj.json" "$FLEET" ) >"$W/out" 2>&1; if grep -q FAIL "$W/out"; then ok "mutant '$1' is red"; else bad "mutant '$1' stayed green"; fi
}
mut "Services dropped" '(.ui.sections[] | select(.id == "cloud") | .tile_groups) |= map(select(.title != "Services"))'
mut "old title restored"    '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services") | .title) = "Weby Services"'
mut "divider removed"       '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services") | .tiles) |= map(select(.separator | not))'
mut "News back in Data" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Data") | .tiles) += [{"id":"news","label":"News","target":"extapp:cloud-news"}]'
mut "Shopping tile retargeted" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services") | .tiles[6].target) = "extapp:cloud-news"'
mut "Jobs placeholder made live" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services") | .tiles[] | select(.label == "Jobs & Business") | .coming_soon) = false'
mut "Shopping app unresolved"  '(.ui.external_apps) |= map(select(.id != "cloud-shopping"))'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
