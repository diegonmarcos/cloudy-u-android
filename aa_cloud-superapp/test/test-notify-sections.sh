#!/usr/bin/env bash
# Tester: the notify redesign (2026-11-08) - section order, Alerts removed, Cloudy/Phone split, title, media last session.
# Static: build.json read as data, the Kotlin checked for the contract the data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"

check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
gl = {g['id']: g for g in nc['groups']}
apps = {a['id']: a for a in nc['app_sections']}
order = nc['page_order']
labels = [gl[i]['label'] if i in gl else apps[i]['label'] for i in order]
want = ['Me Infos', 'Media', 'Device Infos', 'Apps Push Notifications', 'Apps Keep Alive', 'Actions']
prod = {p['id']: p for p in nc['producers']}
if labels != want: print('page order %s' % labels)
elif any(g.get('alerts') or g['label'] == 'Alerts' for g in gl.values()): print('Alerts group is back')
elif 'fleet_alerts' in prod and prod['fleet_alerts'].get('badge'): print('fleet_alerts is a badge again')
elif [prod[m]['label'] for m in gl['live']['members']] != ['Weather', 'Health', 'Fin Terminal']: print('Me Infos rows wrong')
elif any([s['label'] for s in a['subgroups']] != ['Cloudy', 'Phone'] for a in apps.values()): print('Cloudy/Phone split missing')
else:
    placed = {m for g in gl.values() for m in g['members']}
    badges = {p['id'] for p in nc['producers'] if p.get('badge')}
    print('OK' if placed == badges else 'badge(s) in no section: %s / unplaced %s' % (sorted(badges - placed), sorted(placed - badges)))
PY
)" "page_order, labels, Alerts removed, Cloudy/Phone split, every badge placed"

grep -q 'EEE dd.MM.yyyy - HH:mm' "$SRC/notificationcenter/NotifyTitle.kt" && grep -q 'Locale.US' "$SRC/notificationcenter/NotifyTitle.kt" \
  && ok "title format is EEE dd.MM.yyyy - HH:mm (24h, Locale.US)" || bad "title format"
grep -q 'NotifyTitle.cityOf' "$SRC/launcher/BadgeCards.kt" && grep -q 'loadForecast' "$SRC/notificationcenter/NotifyTitle.kt" \
  && ok "title city comes from the Weather badge's resolved place" || bad "title city source"
grep -q 'GeocodeListener' "$SRC/notificationcenter/PlaceName.kt" && grep -q 'PlaceName.resolve' "$SRC/notificationcenter/WeatherBadgeService.kt" \
  && ok "Weather resolves its place through PlaceName (API 33 async geocoder, cached)" || bad "weather place resolution"
grep -q 'LastMediaSession.resume' "$SRC/media/MusicControlsPopup.kt" && grep -q 'LastMediaSession.save' "$SRC/floatingnav/MediaProxy.kt" \
  && ok "media: last session saved from the live controller, Play resumes it" || bad "media last session wiring"
grep -q 'AppSwitches.pushAllowed' "$SRC/notificationcenter/PhoneNotificationListenerService.kt" \
  && ok "muted push apps are skipped by the listener" || bad "push switch not honoured"
grep -q 'AppSectionBlock' "$SRC/configs/BadgePanes.kt" && grep -q 'NotifySections.layout' "$SRC/configs/BadgePanes.kt" \
  && ok "Config pane draws the app sections in the same layout as the shade" || bad "Config pane lacks the app sections"
echo "notify-sections: $PASS passed, $FAIL failed"; [ "$FAIL" = 0 ]
