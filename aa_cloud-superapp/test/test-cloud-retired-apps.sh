#!/usr/bin/env bash
# #1736 Retired apps (the Store's appstore-retired-apps.json) never appear in the SuperApp's Cloud list.
#
#   T1 no Cloud tile (any group, any folder child) opens a retired package: via extapp:<id> -> ui.external_apps
#      install_package/hub_package/alt_package, via app://<package>, or via an explicit fleet_app
#   T2 no ui.external_apps entry and no fleet row (constellation-fleet.json apps) IS a retired package
#   T3 the Cloud list has no tile labelled/ided Writer or Cloud Writer (#1707 retired it into the keyboard) and
#      Writer is still reachable as Cloud Keyboard's declared action (rule 16)
#   T4 the runtime filter exists: GroupedTilesPage drops retired tiles (CloudAppMenu.dropRetired)
#   T5 MUTATION: re-adding a retired app's tile / external_apps entry makes T1/T2 fail
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
BJ="$APP/build.json"; FLEET="$APP/data/constellation-fleet.json"
RET="$APP/../ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-retired-apps.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$FLEET" "$RET"; do [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }; done
command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }
[ "$(jq '.apps | length' "$RET")" -gt 0 ] || { echo "ERROR: retired list empty" >&2; exit 2; }

# stdin: build.json. Prints one offending line per Cloud tile that opens a retired package.
tiles_bad() {
  jq -r --slurpfile r "$RET" '
    ([$r[0].apps[].package]) as $ret
    | ([.ui.external_apps[] | {key: .id, value: [.install_package, .hub_package, .alt_package] }] | from_entries) as $ext
    | def kids: ., (.tiles[]? | kids);
    [.ui.sections[] | select(.id == "cloud") | .tile_groups[].tiles[] | kids | select(.separator != true)]
    | .[] | . as $t
    | ( ($t.target // "") as $g
        | (if ($g | startswith("extapp:")) then ($ext[$g[7:] | sub("#.*$"; "") | sub("/.*$"; "")] // [])
           elif ($g | startswith("app://")) then [$g[6:] | sub("#.*$"; "")] else [] end)
        + [$t.fleet_app // empty] ) as $pk
    | select(($pk | map(select(. != null)) | any(. as $p | $ret | index($p))) )
    | "\($t.id) -> \($t.target)"' 
}
echo "== T1 no Cloud tile opens a retired app =="
OUT="$(tiles_bad < "$BJ")"
[ -z "$OUT" ] && ok "no Cloud tile resolves to a retired package" || bad "Cloud tiles open retired apps: $OUT"

echo "== T2 no external_apps entry / fleet row is retired =="
N="$(jq -r --slurpfile r "$RET" '[$r[0].apps[].package] as $ret | [.ui.external_apps[] | select([.install_package,.hub_package,.alt_package] | any(. as $p | $ret | index($p)))] | length' "$BJ")"
[ "$N" = 0 ] && ok "external_apps has no retired package" || bad "$N external_apps entries are retired apps"
N="$(jq -r --slurpfile r "$RET" '[$r[0].apps[].package] as $ret | [.apps[] | select(.package as $p | $ret | index($p))] | length' "$FLEET")"
[ "$N" = 0 ] && ok "the fleet manifest has no retired app" || bad "$N fleet rows are retired apps"

echo "== T3 Writer tile gone, Writer reachable through the keyboard =="
N="$(jq -r '[.ui.sections[] | select(.id=="cloud") | .tile_groups[].tiles[] | select((.id=="writer") or (.label=="Writer") or (.label=="Cloud Writer"))] | length' "$BJ")"
[ "$N" = 0 ] && ok "no Writer tile on the Cloud list" || bad "a Writer tile is still on the Cloud list"
jq -e '.ui.external_apps[] | select(.id=="cloud-keyboard") | .actions[] | select(.label=="Writer" and .target=="extapp:cloud-keyboard#page:writer")' "$BJ" >/dev/null 2>&1 \
  && ok "Writer is a declared action of cloud-keyboard" || bad "cloud-keyboard lost its Writer action"
grep -rn -i 'cloud-writer\|cloudwriter' "$BJ" | grep -v '"_doc' | grep -v '_doc_' >/dev/null && bad "build.json still names cloud-writer outside a _doc" || ok "build.json names no cloud-writer outside docs"

echo "== T4 the runtime filter is wired =="
GT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt"
grep -q 'CloudAppMenu.dropRetired' "$GT" && grep -q 'CloudAppMenu.retiredPackages' "$GT" && ok "GroupedTilesPage filters through dropRetired(retiredPackages)" || bad "GroupedTilesPage does not filter retired apps"

echo "== T5 mutation =="
MUT="$(mktemp)"
jq '(.ui.sections[] | select(.id=="cloud") | .tile_groups[0].tiles) += [{"id":"w","label":"W","icon":"x","target":"extapp:cloud-writer-mut"}]
    | .ui.external_apps += [{"id":"cloud-writer-mut","install_package":"com.diegonmarcos.cloudwriter"}]' "$BJ" > "$MUT"
[ -n "$(tiles_bad < "$MUT")" ] && ok "a re-added retired tile is caught" || bad "mutation (retired tile re-added) not caught"
rm -f "$MUT"
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
