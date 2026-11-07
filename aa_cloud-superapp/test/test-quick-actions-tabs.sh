#!/usr/bin/env bash
# Quick Actions (Configs > Setup > Launcher > Quick Actions) restructure: tabs All | UI | Battery Hungers | Tools.
#
#   T1  Panels is a strip over All | UI | Battery Hungers | Tools, in that order, each routed to LauncherConfigFragment
#   T2  Connectivity, Notifications, Others are declared on Tools; the battery effect group on Battery Hungers; no group on UI
#   T3  UI holds UI only: screensaver, icons per row, brightness and the three display controls; no switch box
#   T4  Battery Hungers lists the consumers (BatteryUsageBody) and hosts the Battery Hunger panel
#   T5  Tools holds the switch board, the switch boxes and the default-launcher action
#   T6  All is the union: one flag turns on UI + Battery Hungers + Tools, in that order
#   T7  nothing was lost: every toggle id and group is still declared and still lands on a tab; every previous control is still drawn
#   T8  Cloud-superapp Scale / Font Size / Zoom Size: declared (centre 411, Set 500), live status, privileged write with a needs-ADB state
#   T9  the top sub-page buttons fit their text (fitText strip for controls and panels), and no sp/dp literal in the new code
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BJ="$APP/build.json"
CFG="$KT/settings/LauncherConfigFragment.kt"
DCU="$KT/settings/DisplayControlsUi.kt"
QD="$KT/system/QuickDisplay.kt"
SD="$KT/system/SystemDisplay.kt"
PAGES="$KT/launcher/SectionPages.kt"
TABS="$KT/launcher/SectionTabsFragment.kt"
LIB="$APP/../ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$CFG" "$DCU" "$QD" "$SD" "$PAGES" "$TABS" "$LIB"; do
  [ -f "$f" ] || { echo "FAIL: $f is missing, nothing here proves anything"; exit 1; }
done
py() { python3 - "$BJ" <<PY
import json, sys
b = json.load(open(sys.argv[1]))
cfg = next(s for s in b['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in cfg}
ls = b['ui']['launcher_settings']
$1
PY
}
check() { # check <desc> <python>
  local out; out="$(py "$2")"
  if [ "$out" = "OK" ]; then ok "$1"; else bad "$1: $out"; fi
}
# body of a brace block opened by a line matching $1 in $CFG (to the matching same-indent close)
block() { awk -v pat="$1" 'index($0,pat){f=1; ind=match($0,/[^ ]/); print; next} f{print; if (match($0,/[^ ]/)==ind && $0 ~ /^ *\}/) exit}' "$CFG"; }

echo "== T1: the four tabs, in order, each routed =="
check "Panels = All | UI | Battery Hungers | Tools" "
labels = [by[i]['label'] for i in by['panels']['tabs']]
print('OK' if labels == ['All', 'UI', 'Battery Hungers', 'Tools'] and by['panels']['tabs'] == ['panels-all','panels-ui','panels-battery','panels-tools'] and all(by[i].get('hidden') for i in by['panels']['tabs']) else labels)"
for pair in "panels-all:SCOPE_ALL" "panels-ui:SCOPE_UI" "panels-battery:SCOPE_BATTERY" "panels-tools:SCOPE_TOOLS"; do
  grep -qF "pageId == \"${pair%%:*}\" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.${pair##*:})" "$PAGES" \
    && ok "${pair%%:*} -> ${pair##*:}" || bad "${pair%%:*} is not routed to ${pair##*:}"
done

echo "== T2: switch groups sit on Tools / Battery Hungers, none on UI =="
check "connectivity, notifications, others -> tools; battery -> battery; nothing on ui" "
g = {x['id']: x.get('tab') for x in ls['toggle_groups']}
print('OK' if g == {'battery': 'battery', 'connectivity': 'tools', 'notifications': 'tools', 'others': 'tools'} else g)"
grep -q 'tab *= o.optString("tab", "tools")' "$KT/settings/LauncherSettingsPrefs.kt" && grep -q 'filter { it.tab == tab }' "$CFG" \
  && ok "the tab of a group is read from the declaration, not a Kotlin list" || bad "group->tab is not data driven"

echo "== T3: UI holds UI only =="
UI="$(block 'if (showUi) {')"
[ -n "$UI" ] || bad "no showUi block found"
for need in 'Screensaver' 'Icons per row' 'Screen brightness\|LauncherSettingsPrefs.Config.brightness' 'DisplayControlsSection()'; do
  grep -q "$need" <<<"$UI" && ok "UI draws: $need" || bad "UI lost: $need"
done
grep -q 'toggleGroups\|toggleGrid\|BatteryHungerPanel\|BatteryUsageBody\|ControlsGridHost' <<<"$UI" \
  && bad "UI draws a switch box, the battery panels or the switch board" || ok "UI draws no Connectivity / Notification / Others / battery / switch board"

echo "== T4: Battery Hungers =="
BAT="$(block 'if (showBattery) {')"
grep -q 'toggleGroups("battery"' <<<"$BAT" && grep -q 'BatteryUsageBody()' <<<"$BAT" && grep -q 'BatteryHungerPanel(' <<<"$BAT" \
  && ok "Battery Hungers = the battery switch group + per-app consumers (BatteryRepository) + the Battery Hunger panel" || bad "Battery Hungers is missing a part"
grep -q 'BatteryRepository.cycles' "$KT/launcher/ResourcesBatteryUsage.kt" && grep -q 'BatteryRepository.appLabel' "$KT/launcher/ResourcesBatteryUsage.kt" \
  && ok "consumers come from BatteryRepository per-uid mAh resolved to app names" || bad "the consumer source changed"

echo "== T5: Tools =="
TOOLS="$(block 'if (showTools) {')"
grep -q 'ControlsGridHost()' <<<"$TOOLS" && grep -q 'toggleGroups("tools"' <<<"$TOOLS" && grep -q 'launcher:default-cta' <<<"$TOOLS" \
  && ok "Tools = switch board + Connectivity/Notifications/Others + default launcher" || bad "Tools is missing a part"

echo "== T6: All is the union =="
grep -q 'val all = scope.isEmpty() || scope == SCOPE_ALL' "$CFG" && grep -q 'val showUi = all ||' "$CFG" \
  && grep -q 'val showBattery = all ||' "$CFG" && grep -q 'val showTools = all ||' "$CFG" \
  && ok "all turns on UI, Battery Hungers and Tools" || bad "All is not the union of the three"
U=$(grep -n 'if (showUi) {' "$CFG" | head -1 | cut -d: -f1); B=$(grep -n 'if (showBattery) {' "$CFG" | head -1 | cut -d: -f1); T=$(grep -n 'if (showTools) {' "$CFG" | head -1 | cut -d: -f1)
[ "$U" -lt "$B" ] && [ "$B" -lt "$T" ] && ok "drawn in the order UI, Battery Hungers, Tools" || bad "section order is not UI, Battery Hungers, Tools"

echo "== T7: nothing lost =="
check "every toggle still names a declared group (all 24 land on a tab)" "
gids = {x['id'] for x in ls['toggle_groups']}
t = ls['toggles']
print('OK' if len(t) >= 24 and all(x.get('group') in gids for x in t) else (len(t), [x['id'] for x in t if x.get('group') not in gids]))"
check "the previous scale / brightness / screensaver declarations remain" "
print('OK' if all(k in ls for k in ('scale','brightness','screensaver_timeout')) else list(ls))"
grep -q 'sliderRow(sc.label' "$CFG" && grep -q 'stepperRow("Cloud apps"' "$CFG" && grep -q 'GenericTileUi(saver.label' "$CFG" \
  && ok "Scale slider, icons per row steppers, screensavers still drawn" || bad "a previous control is no longer drawn"

echo "== T8: Cloud-superapp Scale / Font Size / Zoom Size =="
check "declared: scale centred on 411 with Set 500, symmetric ruler; font and zoom steps" "
d = ls['display_controls']; s = d['scale']
ok = (s['label'] == 'Cloud-superapp Scale' and s['center'] == 411 and s['center'] - s['min'] == s['max'] - s['center']
      and 500 in [p['value'] for p in s['presets']] and d['font']['label'] == 'Font Size' and d['font']['key'] == 'font_scale'
      and d['zoom']['label'] == 'Zoom Size' and d['zoom']['key'] == 'display_density_forced' and len(d['font']['steps']) >= 3 and len(d['zoom']['steps']) >= 3)
print('OK' if ok else d)"
for tag in 'display:scale:status' 'display:scale:ruler' 'display:scale:slider' 'display:scale:preset:' 'display:$id:status' 'display:$id:result'; do
  grep -qF "$tag" "$DCU" && ok "tag $tag" || bad "tag $tag missing"
done
grep -q 'font_scale' "$QD" && grep -q 'wm density' "$QD" && grep -q 'display_density_forced' "$QD" && grep -q 'NeedsAdb' "$QD" && grep -q 'canWrite' "$QD" \
  && ok "keys font_scale / display_density_forced / wm density; WRITE_SETTINGS then the shell channel; NeedsAdb carries the exact command" || bad "QuickDisplay lost a key or the fallback"
grep -q 'QuickDisplay.swOverride' "$SD" && grep -q 'densityForSw' "$SD" && ok "Cloud-superapp Scale is applied in-process by SystemDisplay.wrap (no ADB)" || bad "wrap ignores the smallest-width override"

echo "== T9: top buttons fit their text; no literals =="
grep -q 'FIT_TEXT_OWNERS = setOf("controls", "panels")' "$TABS" && grep -q 'fitText = ownerPageId in FIT_TEXT_OWNERS' "$TABS" \
  && ok "both Quick Actions strips (Presets|Panels and All|UI|Battery Hungers|Tools) ask for fit-text" || bad "the strips do not ask for fit-text"
grep -q 'fun fitTextPlan' "$LIB" && grep -q 'if (fitText) fitTextPlan(startPx)' "$LIB" && ok "fit-text pills wrap their own label, full size, no ellipsis; the strip scrolls" || bad "PageTabs has no fit-text plan"
if grep -nE '[0-9]\.dp|[0-9]\.sp|[0-9]f?\.sp|[0-9]f?\.dp' "$DCU" "$QD"; then bad "dp/sp literal in the new code"; else ok "no dp/sp literal in DisplayControlsUi / QuickDisplay (KitStoreRow tokens)"; fi

echo; echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = 0 ]
