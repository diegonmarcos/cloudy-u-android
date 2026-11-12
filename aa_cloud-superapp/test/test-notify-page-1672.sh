#!/usr/bin/env bash
# Tester: #1672 the Notify page - sub-pages All | Persistent Badges | Cloudy Apps | Phone Apps | Configs; the feeds
# are real notifications (All per app like Android, app pages By Categories / By Time), the C3 Ntfy table and the
# Channels block removed (only those two), Configs one level, no polling (listener callback), no crash without access.
# Static: build.json read as data, the Kotlin checked for the contract the data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 - $1"; fi; }
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
for f in launcher/AggregatorNotify.kt launcher/AggregatorPanels.kt launcher/AggregatorState.kt launcher/BadgeCards.kt \
         notificationcenter/PhoneNotificationStore.kt notificationcenter/NotifySections.kt; do
  [ -f "$SRC/$f" ] || { echo "ABORT: $SRC/$f missing - a check over nothing passes"; exit 1; }
done

check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
nc = ui['notification_center']
gl = {g['id']: g['label'] for g in nc['groups']}
labels = [gl.get(i, '?' + i) for i in nc['page_order']]
comm = next(s for s in ui['sections'] if s['id'] == 'communication')
pages = [(p['id'], p['label']) for p in comm['pages'] if not p.get('hidden')]
want = [('my-rss', 'All'), ('notify-badges', 'Persistent Badges'), ('notify-cloudy', 'Cloudy Apps'),
        ('notify-phone', 'Phone Apps'), ('notify-configs', 'Configs')]
if pages != want: print('sub-pages %s' % pages)
elif labels != ['Media', 'Me Infos', 'Device Infos', 'Actions']: print('badge sections %s' % labels)
elif any(comm.get('state_pages', {}).get(p) != 'my-rss' for p, _ in want): print('sub-pages do not share one state')
else: print('OK')
PY
)" "T1 sub-pages All | Persistent Badges | Cloudy Apps | Phone Apps | Configs; badge sections Media, Me Infos, Device Infos, Actions; one shared state"

check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
comm = next(s for s in ui['sections'] if s['id'] == 'communication')
bad = []
stacks = {k: v for k, v in comm.items() if k.startswith('stack_') and isinstance(v, list)}
for k, v in stacks.items():
    nc = [p for p in v if p.get('kind') == 'notification_center']
    if any(p.get('stream') == 'channels' or p.get('title') == 'C3 Ntfy' for p in v): bad.append(k + ': C3 Ntfy table is back')
    if any(p.get('scopes') or not p.get('split') for p in nc): bad.append(k + ': a notification panel can poll ntfy channels again')
want = {'stack_my-rss': ['all'], 'stack_notify-badges': [], 'stack_notify-cloudy': ['cloudy'],
        'stack_notify-phone': ['phone'], 'stack_notify-configs': []}
for k, w in want.items():
    got = [p.get('split') for p in stacks.get(k, []) if p.get('kind') == 'notification_center']
    if got != w: bad.append('%s splits %s, want %s' % (k, got, w))
for k, v in comm.items():
    if k.startswith('filters_'):
        for f in v:
            if any(o.get('id') == 'c3ntfy' for o in f.get('options', [])): bad.append(k + ': Source still offers C3 Ntfy')
c3 = next((s for s in ui['sections'] if s['id'] == 'c3'), {})
if not any(p.get('stream') == 'channels' for p in c3.get('stack_observability', [])): bad.append('C3 Observability lost its channels')
print('OK' if not bad else '; '.join(bad))
PY
)" "T2 C3 Ntfy table and Channels block removed from every Notify page (C3 Observability keeps its channels); each sub-page shows its side"

check "$(python3 - "$APP/app/src/test/resources/parity/notify-page-parity.json" <<'PY'
import json, sys
items = json.load(open(sys.argv[1]))['items']
removed = sorted(i['id'] for i in items if i['status'] == 'removed')
kept = [i for i in items if i['status'] == 'kept']
if removed != ['feed.c3ntfy', 'feed.channels']: print('removed %s' % removed)
elif len(kept) < 15: print('only %d kept items' % len(kept))
elif any(not (i.get('tag') or i.get('text')) or not i.get('page') for i in kept): print('a kept item names nothing to look for, or no page')
else: print('OK')
PY
)" "T3 parity list: exactly the two named removals, every other item kept and findable on a named sub-page"

NOTIFY="$SRC/launcher/AggregatorNotify.kt"
body="$(awk '/^private fun SplitCenter/,/^}/' "$NOTIFY")"
[ -n "$body" ] || bad "T4 SplitCenter not found"
if [ -n "$body" ]; then
  if printf '%s' "$body" | grep -q 'delay(\|while (true)'; then bad "T4 the notification feed polls (delay / while loop)"
  else ok "T4 the notification feed has no poll loop"; fi
  printf '%s' "$body" | grep -q 'NotificationAccessOff(onOpen = onOpen)' \
    && ok "T5 no access: the feed shows the prompt that opens Configs > Perms" || bad "T5 access prompt not wired"
  printf '%s' "$body" | grep -q 'GROUP_TIME -> Timeline(' && printf '%s' "$body" | grep -q 'else -> Categories(' \
    && printf '%s' "$body" | grep -q 'all -> GroupList(' \
    && ok "T6 layouts: All = one box per app; app pages By Categories / By Time" || bad "T6 a layout is missing"
fi
grep -q 'PhoneNotificationStore.addListener' "$NOTIFY" && grep -q 'PhoneNotificationStore.removeListener' "$NOTIFY" \
  && [ "$(grep -c 'changed()' "$SRC/notificationcenter/PhoneNotificationStore.kt")" -ge 4 ] \
  && ok "T7 the page re-reads on the store's change callback (push, touch, clear), registered and unregistered" \
  || bad "T7 listener callback not wired"
grep -q 'PERMS_TARGET = "page:config/perms"' "$SRC/launcher/AggregatorModel.kt" \
  && ok "T8 the prompt target is Configs > Perms" || bad "T8 prompt target"
PANELS="$SRC/launcher/AggregatorPanels.kt"
cfg="$(awk '/^internal fun ConfigSection/,/^}/' "$PANELS")"
if printf '%s' "$cfg" | grep -q 'Text("Settings"'; then bad "T9 the section still reads Settings"
elif printf '%s' "$cfg" | grep -q 'panel.kind == "notification_badge_configs"' && printf '%s' "$cfg" | grep -q 'BadgePane(state)'; then
  ok "T9 Configs is one level: the badge list is drawn straight into it, no inner fold"
else bad "T9 Configs still nests the badge list in a foldable card"; fi
printf '%s' "$cfg" | grep -q 'AppSectionBlock(sec)' \
  && ok "T10 the Cloud / Phone Publisher and keep-alive app lists are kept, inside Configs" || bad "T10 app lists lost"
if grep -q 'AppSectionBlock(app)' "$SRC/launcher/BadgeCards.kt"; then bad "T11 the page still draws app lists as sections"
else ok "T11 the page draws no app list as a section (the feeds show notifications)"; fi
grep -q 'NotifySections.pageGroupOrder(NotifySections.pageOrder' "$SRC/launcher/BadgeCards.kt" \
  && ok "T12 badge sections follow page_order, not the movable shade order" || bad "T12 badge sections ignore page_order"
grep -q 'CollapsedBadgeCard(d, accent, icon, flip)' "$SRC/launcher/BadgeCards.kt" && grep -q 'BadgeCollapse.set' "$SRC/launcher/BadgeCards.kt" \
  && ok "T13 every badge card has a remembered collapsed / expanded view" || bad "T13 collapse missing"
grep -q 'panel.split.isBlank() &&' "$SRC/launcher/AggregatorState.kt" \
  && ok "T14 a split panel never polls ntfy" || bad "T14 split panel may poll"
echo "notify-page-1672: $PASS passed, $FAIL failed"; [ "$FAIL" = 0 ]
