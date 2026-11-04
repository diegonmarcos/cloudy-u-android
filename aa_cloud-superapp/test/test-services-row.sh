#!/usr/bin/env bash
# Tester: Cloud > Apps: the Services row (#1473) sits right after Me and before AGI, holds News | Shopping, News left Data Apps, and both
# tiles resolve to a fleet app (ui.external_apps) and a constellation member. Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; FLEET="$APP/data/constellation-fleet.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
check() {
  local bj="$1" fleet="$2" G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
  eq "group order Me, Services, AGI" "$(jq -r "[$G | .title] | join(\",\")" "$bj" | grep -o 'Me,Services,AGI')" "Me,Services,AGI"
  eq "Services row reads News Shopping" "$(jq -r "$G | select(.title == \"Services\") | [.tiles[].label] | join(\" \")" "$bj")" "News Shopping"
  eq "Services tiles open cloud-news and cloud-shopping" "$(jq -r "$G | select(.title == \"Services\") | [.tiles[].target] | join(\" \")" "$bj")" "extapp:cloud-news extapp:cloud-shopping"
  eq "News left Data Apps" "$(jq -r "$G | select(.title == \"Data Apps\") | [.tiles[] | select(.label == \"News\")] | length" "$bj")" "0"
  eq "the Shopping app resolves in ui.external_apps" "$(jq -r '[.ui.external_apps[] | select(.id == "cloud-shopping") | .install_package] | join(",")' "$bj")" "com.diegonmarcos.cloudshopping"
  eq "Store > Cloud > Apps groups News and Shopping under Services" "$(jq -r '.app_categories[] | select(.label == "Services") | .members | join(",")' "$fleet")" "news,shopping"
  eq "Data Apps no longer lists news in the Store" "$(jq -r '.app_categories[] | select(.label == "Data Apps") | .members | index("news")' "$fleet")" "null"
}
echo "[services-row] the real tree"; check "$BJ" "$FLEET"
echo "[services-row] mutations (each must turn the tester red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <jq filter on build.json>
  jq "$2" "$BJ" > "$W/bj.json"; before=$FAIL; ( check "$W/bj.json" "$FLEET" ) >"$W/out" 2>&1; if grep -q FAIL "$W/out"; then ok "mutant '$1' is red"; else bad "mutant '$1' stayed green"; fi
}
mut "Services dropped"      '(.ui.sections[] | select(.id == "cloud") | .tile_groups) |= map(select(.title != "Services"))'
mut "News back in Data Apps" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Data Apps") | .tiles) += [{"id":"news","label":"News","target":"extapp:cloud-news"}]'
mut "Shopping tile retargeted" '(.ui.sections[] | select(.id == "cloud") | .tile_groups[] | select(.title == "Services") | .tiles[1].target) = "extapp:cloud-news"'
mut "Shopping app unresolved"  '(.ui.external_apps) |= map(select(.id != "cloud-shopping"))'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
