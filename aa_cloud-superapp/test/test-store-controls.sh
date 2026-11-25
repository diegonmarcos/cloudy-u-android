#!/usr/bin/env bash
# #732 Store controls — three KINDS of control, three LOOKS, all declared.
#
#   C1  appstore-controls.json declares exactly three styles - tab, page,
#       action - and no two share a SHAPE (fill/outline/chevron/stretch/rounding)
#   C2  every line-2 entry (each declared feed + the page's own mesh)
#       wears the `page` style, with an icon; captions have ONE declaration each
#   C3  the page style is a page button: outlined, chevron, wraps; line 1 wears
#       `tab` and btn() wears `action`
#   C4  the Kotlin draws what is declared: tabBar dresses line 2 from the page
#       declarations, tabButton draws icon + chevron, paintTabs and btn build
#       their backgrounds from the style, and no style token is written in Kotlin
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
STORE="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main"
DECL="$ROOT/data/fleet/appstore-controls.json"   # #1562 a served dataset, not an APK asset
FEEDS="${FLEET_PRIVATE_DIR:-$ROOT/fleet-private-data}/appstore-feeds.json"   # #1566 fleet-private: CI materialises it
PAGE="$STORE/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
CTRL="$STORE/java/com/diegonmarcos/superapp/appstore/StoreControls.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$DECL" "$FEEDS" "$PAGE" "$CTRL"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done
jq -e . "$DECL" >/dev/null || { echo "ERROR: $DECL is not JSON" >&2; exit 2; }

echo "== C1: three styles, three different shapes =="
styles="$(jq -r '.styles | keys[] | select(startswith("_") | not)' "$DECL" | sort | tr '\n' ' ')"
[ "$styles" = "action page tab " ] && ok "the declared styles are exactly: $styles" \
  || bad "expected styles 'action page tab', declared: '$styles'"
# A shape is what the eye reads before the colour: filled or not, outlined or
# not, chevron or not, stretched or not, square or rounded. Two styles with one
# shape are one look. (tab vs action differ only in the last: a square
# segmented bar against rounded verb blocks; dropping the rounding from either
# makes them one look, and this goes red.)
shapes="$(jq -r '.styles | to_entries[] | select(.key | startswith("_") | not) | .value
  | [(.fill != null), (.stroke != null), (.chevron != ""), .stretch, (.radius_dp > 0)] | tostring' "$DECL")"
n="$(printf '%s\n' "$shapes" | grep -c .)"; u="$(printf '%s\n' "$shapes" | sort -u | grep -c .)"
[ "$n" -ge 3 ] && [ "$n" = "$u" ] && ok "all $n styles have a distinct shape" \
  || bad "$n styles but only $u distinct shapes - two kinds of control look alike: $(printf '%s ' $shapes)"

echo "== C2: every line-2 entry is a page button =="
# The line-2 ids are DERIVED: every declared feed, plus the ids the page owns
# (its const vals). A pinned list here would not notice a fifth entry.
# #896.3 the Cloud page has no line 2 any more (the feeds, Perms and Apps Mesh left it); what the asset still
# declares as a `page` is Apps Mesh's caption, which SuperApp's apps-mesh page must equal.
ids="$(jq -r '.pages | keys[]' "$DECL")"
count=0
for id in $ids; do
  count=$((count+1))
  st="$(jq -r --arg id "$id" '.pages[$id].style // "MISSING"' "$DECL")"
  [ "$st" = "page" ] && ok "'$id' wears the page style" || bad "'$id' wears '$st', not the page style"
  icon="$(jq -r --arg id "$id" '.pages[$id].icon // ""' "$DECL")"
  [ -n "$icon" ] && ok "'$id' declares an icon ($icon)" || bad "'$id' has no icon - a page button reads as text again"
  if jq -e --arg id "$id" '.feeds[] | select(.id == $id)' "$FEEDS" >/dev/null; then
    jq -e --arg id "$id" '.pages[$id] | has("label")' "$DECL" >/dev/null \
      && bad "feed '$id' carries a second caption here; its caption is appstore-feeds.json's" \
      || ok "feed '$id' keeps its one caption in appstore-feeds.json"
  else
    lbl="$(jq -r --arg id "$id" '.pages[$id].label // ""' "$DECL")"
    [ -n "$lbl" ] && ok "page '$id' declares its caption ($lbl)" || bad "page '$id' declares no caption"
  fi
done
! grep -qF 'rememberStoreControls().page' "$PAGE" && ok "the Cloud page draws no page chips" || bad "the Cloud page draws page chips again"
[ "$count" -ge 1 ] && ok "$count declared page checked (Apps Mesh's caption)" \
  || bad "only $count line-2 entries derived - the checks above verified too little"

echo "== C3: the page style looks like a page button =="
jq -e '.styles.page | .stroke != null and .fill == null and .chevron != "" and .stretch == false' "$DECL" >/dev/null \
  && ok "page = outlined, unfilled, chevron, wraps its caption" \
  || bad "the page style is not an outlined chip with a chevron"
[ "$(jq -r '.group_tab_style' "$DECL")" = "tab" ] && ok "line-1 group tabs wear 'tab'" || bad "group_tab_style is not 'tab'"
[ "$(jq -r '.action_style' "$DECL")" = "action" ] && ok "btn() wears 'action'" || bad "action_style is not 'action'"

echo "== C4: the Kotlin draws what is declared =="
body_of() { awk -v pat="$1" 'index($0, pat){f=1} f{print} f && /^    }$/{exit}' "$PAGE"; }
TABS="$STORE/java/com/diegonmarcos/superapp/appstore/StoreBar.kt"  # #896 the strip builder (StoreTabs) Cloud and Phone share
tabs_body_of() { awk -v pat="$1" 'index($0, pat){f=1} f{print} f && /^    }$/{exit}' "$TABS"; }
TABBAR="$(body_of 'private fun TabBar(')"; TABBTN="$(tabs_body_of 'fun RowScope.Tab(')"
PAINT="$TABBTN"   # the Tab composable paints its own selection from the style it is handed
SB="$(awk '/^    fun Button\(/{f=1} f{print} f && /^    }$/{exit}' "$(dirname "$CTRL")/StoreBar.kt")"
for pair in "TabBar:$TABBAR" "Tab:$TABBTN" "StoreBar.Button:$SB"; do
  [ -n "${pair#*:}" ] || bad "could not isolate ${pair%%:*} - every assertion about it would verify nothing"
done
printf '%s' "$TABBAR" | grep -qF 'rememberStoreControls().groupTab' \
  && ok "the one line is dressed by the group-tab declaration" \
  || bad "tabBar does not dress its line from the group-tab declaration"
printf '%s' "$TABBTN" | grep -qF 'listOf(control.icon, control.label, style.chevron)' \
  && ok "tabButton draws icon, caption and chevron" || bad "tabButton drops the icon or the chevron"
printf '%s' "$PAINT" | grep -qF 'storeSurface(style, on)' \
  && ok "each Tab builds its background from that button's declared style" \
  || bad "a Tab paints without the declared style"
printf '%s' "$SB" | grep -qF 'style: StoreControls.Style = rememberStoreControls().action' \
  && grep -qF 'StoreVerb(a.label, a.color' "$PAGE" \
  && ok "a row's verb wears the declared action style in its verb's colour (#793 via the shared StoreBar.Button)" \
  || bad "a row's verb ignores the action style"
printf '%s' "$SB" | grep -qF 'storeSurface(style, false,' \
  && ok "StoreBar.Button builds its background from the style it is given" \
  || bad "StoreBar.Button ignores the declared style"
[ "$(jq -r '.filter_style' "$DECL")" = "tab" ] && ok "filter chips wear 'tab' (they select a subset)" \
  || bad "filter_style is not 'tab' - a filter chip would be a fourth look"
BG="$(awk '/^internal fun Modifier.storeSurface\(/{f=1} f{print} f && /^}$/{exit}' "$(dirname "$CTRL")/StoreBar.kt")"
printf '%s' "$BG" | grep -qF '.border(' && printf '%s' "$BG" | grep -qF 'RoundedCornerShape' \
  && ok "the background builder draws the outline and the rounding" \
  || bad "Modifier.storeSurface draws no outline or no rounding - the page chip is flat text again"
for tok in tab page action; do
  hits="$(grep -nF "\"$tok\"" "$PAGE" "$CTRL")"
  [ -z "$hits" ] && ok "style token \"$tok\" is not written in Kotlin" || bad "Kotlin hardcodes style token \"$tok\": $hits"
done

echo
echo "== RESULT(#732 store controls): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
