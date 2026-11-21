#!/usr/bin/env bash
# #1844 the Mesh badge is in the shade whatever the tunnel does; every badge keeps its group.
#  M1  NotifyGate has postKept (forced when the card is not in the shade) and the Mesh service posts through it.
#  M2  every group summary is posted through postKept (a swiped summary comes back).
#  M3  the Mesh service never stops itself and nothing stops it on a VPN-down path; START_STICKY; swipe re-posts.
#  M4  RULE 16 / grouping: every declared badge is a member of exactly one declared group, no member is unresolvable.
set -u
cd "$(dirname "$0")/.."
BJ=build.json
KT=app/src/main/java/com/diegonmarcos/superapp/notificationcenter
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { if grep -qF -- "$2" "$1"; then ok "$3"; else bad "$3"; fi; }

echo "== M1 =="
has $KT/NotifyGate.kt 'fun postKept(' "NotifyGate.postKept exists"
has $KT/NotifyGate.kt 'force = !inShade(nm, tag, id))' "postKept forces when the card is not in the shade"
has $KT/NetworkBadgeService.kt 'NotifyGate.postKept(' "Mesh badge posts through postKept"
echo "== M2 =="
has $KT/NotifyGroups.kt 'NotifyGate.postKept(nm, g.id, SUMMARY_ID' "group summaries post through postKept"
echo "== M3 =="
has $KT/NetworkBadgeService.kt 'return START_STICKY' "Mesh service is START_STICKY"
has $KT/NetworkBadgeService.kt 'setDeleteIntent(BadgeServices.repostOnDismiss' "swipe re-posts through the delete intent"
if grep -n 'stopSelf\|stopForeground' $KT/NetworkBadgeService.kt | grep -q .; then bad "Mesh service stops itself"; else ok "Mesh service never stops itself"; fi
if grep -rn 'NetworkBadgeService' app/src/main/java --include=*.kt | grep -i 'stopService' | grep -q .; then bad "something stops the Mesh service"; else ok "nothing stops the Mesh service by name"; fi
echo "== M4 =="
r=$(python3 - "$BJ" <<'PY'
import json, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
members = [m for g in nc['groups'] for m in g['members']]
ids = {p['id'] for p in nc['producers'] if p.get('badge')}
probs = [i for i in ids if members.count(i) != 1]
probs += [m for m in members if m not in {p['id'] for p in nc['producers']}]
print('OK' if not probs else 'ungrouped/dup/unknown: %r' % probs)
PY
)
if [ "$r" = OK ]; then ok "every badge producer is in exactly one group"; else bad "$r"; fi
echo "passed=$PASS failed=$FAIL"; [ "$FAIL" = 0 ]
