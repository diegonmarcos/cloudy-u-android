#!/usr/bin/env bash
# Configs > Network > ADB Shell: the privileged channel's ONE page. Static (no build, no device).
#  1. build.json: the page is declared once, in Setup > Network, RIGHT AFTER Apps Mesh, labelled "ADB Shell".
#  2. The page declares FOUR TABS (Status, Shell, Perms, All; see test-adb-shell-cockpit.sh); SectionPages routes them to AdbShellFragment, whose All tab hosts AdbShellBrokerPage (#1522: six sections over the broker, see test-adb-shell-broker-page.sh).
#  3. The page lives in libs:shizuku-adb-debug-tools with its seven sections in order (status table, connect,
#     active connection, logs, declared needs, setup, keeper), the four modes, the five connection buttons and the
#     log's Copy / Clear; every rule behind it is a pure, tested model.
#  4. The SuperApp's default mode is the local server (it declares its own local_server port) and it declares
#     what it needs the channel for.
# The "no second copy" half (Store, Account, terminals, chips) is test-adb-shell-one-place.sh.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0; ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }; bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
L="$ROOT/ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src"
LM="$L/main/java/com/diegonmarcos/superapp/adbdebug"; LT="$L/test/java/com/diegonmarcos/superapp/adbdebug"
out="$(python3 - "$ROOT/aa_cloud-superapp/build.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
pages = next(s for s in b['ui']['sections'] if s['id'] == 'config')['pages']
net = [p['id'] for p in pages if p.get('group') == 'Network' and p['id'] in ('ng-firewall','wifi','bluetooth','dns','wg','firewall','os-perms','webserver','proxy','apps-access','adb-shell')]   # #1737 declared order: the individual icons, the hub, OS Perms, Proxy; Apps Access and ADB Shell hidden
by = {p['id']: p for p in pages}
# Apps Access (Cloud Store > Access: Apps Mesh, IPC, Identity) is the one mesh tile; ADB Shell follows it
mesh = [i for i in ('apps-access',) if i in net]
print('AFTER', net[net.index(mesh[-1]) + 1] if mesh and net.index(mesh[-1]) + 1 < len(net) else 'none')
print('LABEL', by.get('adb-shell', {}).get('label'))
print('ONCE', sum(1 for p in pages if p['id'] == 'adb-shell'))
print('OWN', 'local_server' in b.get('shizuku_diagnostics', {}))
print('NEEDS', len((b.get('privileged_channel') or {}).get('needs') or []))
PY
)"
echo "$out" | grep -qx 'AFTER adb-shell' && ok "ADB Shell comes right after Apps Access" || bad "ADB Shell is not right after Apps Access"
echo "$out" | grep -qx 'LABEL ADB Shell' && ok "the label is ADB Shell (build.json, one place)" || bad "the label is not ADB Shell"
echo "$out" | grep -qx 'ONCE 1' && ok "declared once" || bad "declared 0 or 2+ times"
# #1682 the local server belongs to the declared ADB owner: the SuperApp declares none once it is a client, the owner declares it
OWNER_APP="$(python3 -c "import json;print(json.load(open('$ROOT/1_cicd/src/data/adb-owner.json'))['adb_owner'])")"
OWNER_HAS="$(python3 -c "import json;print('local_server' in (json.load(open('$ROOT/$OWNER_APP/build.json')).get('shizuku_diagnostics') or {}))")"
if [ "$OWNER_APP" = aa_cloud-superapp ]; then want=True; else want=False; fi
{ [ "$OWNER_HAS" = True ] && echo "$out" | grep -qx "OWN $want"; } && ok "the declared ADB owner ($OWNER_APP) declares the local server; the SuperApp declares it only while it is the owner" || bad "local_server declaration does not match the ADB owner ($OWNER_APP has: $OWNER_HAS, SuperApp OWN want $want)"
echo "$out" | grep -qE '^NEEDS [1-9]' && ok "the SuperApp declares what it needs the channel for" || bad "no privileged_channel.needs in the SuperApp's build.json"
grep -q 'pageId == "adb-shell".*AdbSide.page("adb-shell")' "$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt" && ok "routed to AdbShellFragment" || bad "adb-shell is not routed"
grep -q 'AdbShellBrokerPage(' "$ROOT/aa_cloud-superapp/app/src/owner/java/com/diegonmarcos/superapp/configs/AdbShellFragment.kt" && ok "the fragment hosts the SuperApp broker page" || bad "the fragment does not host AdbShellBrokerPage"
# the six sections, in the order the owner asked for
python3 - "$LM/AdbShellPage.kt" <<'PY' && ok "the page draws its seven sections in order" || bad "the page's sections are missing or out of order"
import sys
s = open(sys.argv[1], encoding='utf-8').read()
want = ['"1  STATUS"', '"2  CONNECT"', '"3  ACTIVE CONNECTION"', '"4  LOGS"', '"5  DECLARED NEEDS"', '"6  SETUP"', '"7  KEEP WIRELESS DEBUGGING ON"']
at = [s.find(w) for w in want]
sys.exit(0 if all(a >= 0 for a in at) and at == sorted(at) else 1)
PY
for a in DISCONNECT RECONNECT RESTART_SERVER STOP_SERVER TEST; do grep -q "ChannelAction.$a" "$LM/AdbShellPage.kt" && ok "button $a on the page" || bad "button $a missing"; done
for m in LOCAL_SERVER EMBEDDED_ONLY SHIZUKU AUTO; do grep -q "ChannelMode.$m" "$LM/ChannelModeBar.kt" && ok "mode $m in the selector" || bad "mode $m missing"; done
for w in '"Connect"' '"Copy"' '"Clear"' '"Refresh"'; do grep -qF "$w" "$LM/AdbShellPage.kt" && ok "$w on the page" || bad "$w missing"; done
for t in ChannelStateTest ConnectPlanTest ChannelActionsTest ChannelLogTest DeclaredNeedsTest SetupChecklistTest; do [ -f "$LT/$t.kt" ] && ok "unit tests: $t" || bad "missing unit tests: $t"; done
echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
