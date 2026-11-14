#!/usr/bin/env bash
# Tester: the firewall engine's CONTROLS live in Configs ▸ Network ▸ Firewall; About
# keeps a READ-ONLY summary and a link. Static (no device, no build).
#   F1 Network page order is exactly Firewall (first again: #1548 moved the Watchdog hub to Agents), DNS, Webserver, Cloud Mesh, Apps Access (Cloud Store > Access), ADB Shell, WiFi (#1263)
#   F2 config/firewall embeds FirewallDialog (the controls) as a page
#   F3 About's Firewall block opens nothing of the engine's: no FirewallDialog / FirewallController /
#      FirewallRules write; only FirewallInfo reads + a link to config/firewall
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$KT/launcher/SectionPages.kt" "$KT/devcontrol/DevControlFragment.kt"; do
  [ -f "$f" ] || { echo "  ABORT: missing $f"; exit 2; }; done

echo "== F1: Network order =="
got="$(python3 - "$APP/build.json" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
# #1737 the visible Network group: the NG Firewall hub, then the individual icons with | between the sub-groups.
print(','.join(('|' if p.get('separator') else p['label']) for p in pages if p.get('group') == 'Network' and not p.get('hidden')))
PY
)"
[ "$got" = "NG Firewall,|,Wi-Fi,Bluetooth,DNS,VPN,|,Firewall,OS Perms,|,Webserver,Proxy" ] && ok "Network = $got" || bad "Network order is: $got"

echo "== F2: Firewall page hosts the controls =="
grep -qE 'pageId == "firewall" +-> .*NgFirewallFragment\.newInstance\(\)' "$KT/launcher/SectionPages.kt" \
  && ok "config/firewall opens NgFirewallFragment (#1719)" || bad "config/firewall does not open NgFirewallFragment"
grep -q 'FirewallDialog.newInstance(tab).apply { showsDialog = false }' "$KT/network/NgFirewallFragment.kt" \
  && ok "the Firewall page hosts FirewallDialog as its pages (master switch, consent, per-app rules)" \
  || bad "NgFirewallFragment does not embed FirewallDialog (showsDialog = false)"

echo "== F3: About is read-only for the firewall =="
blk="$(awk '/infoSection\("Firewall"\) \{/{f=1} f{print} f&&/^        }$/{exit}' "$KT/devcontrol/DevControlFragment.kt")"
[ -n "$blk" ] || bad "About's Firewall block not found"
echo "$blk" | grep -qE 'FirewallDialog|FirewallController|FirewallRules|FirewallPrefs' \
  && bad "About's Firewall block touches the engine's controls" || ok "About's Firewall block uses no engine control"
echo "$blk" | grep -q 'FirewallInfo.read' && ok "About still shows the status summary (FirewallInfo)" || bad "About lost its status summary"
echo "$blk" | grep -q 'openSectionPage("config", "firewall")' && ok "About links to Network ▸ Firewall" || bad "About has no link to config/firewall"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
