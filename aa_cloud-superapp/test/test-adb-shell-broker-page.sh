#!/usr/bin/env bash
# #1522 The SuperApp's ADB Shell page: declarative sections, dense KitStoreRow rows, the ui-kit type ramp, no sp literals.
#  1. AdbShellModel declares the seven sections in order: status, actions, keeper, callers, audit, keeper_log, console (#1550)
#  2. only audit and keeper_log are collapsed by default
#  3. the page draws KitStoreRow parts and no `.sp` literal, no Material Button, no minimum height
#  4. the page is drawn from AdbShellModel.ORDER (no section named twice in the drawing loop)
#  5. the page loads on open and on resume, with no polling loop
#  6. the JVM tests exist (model, page, one dot)
# Usage: ./test-adb-shell-broker-page.sh   (static only)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
D="$ROOT/app/src/main/java/com/diegonmarcos/superapp/configs"; T="$ROOT/app/src/test/java/com/diegonmarcos/superapp"
PASS=0; FAIL=0; ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }; bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
code(){ python3 -c 'import re,sys;print(re.sub(r"/\*.*?\*/|//[^\n]*","",open(sys.argv[1],encoding="utf-8").read(),flags=re.S))' "$1"; }
# #1682 P3 step 4a: the model (and its JVM test) live in the owner-side lib libs:adb-shell-core, same package.
LD="$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/configs"; LT="$ROOT/../ab_cloud-libs-shared/libs/adb-shell-core/src/test/java/com/diegonmarcos/superapp"
M="$(code "$LD/AdbShellModel.kt")"; P="$(code "$D/AdbShellBrokerPage.kt")"
python3 - "$LD/AdbShellModel.kt" <<'PY' && ok "seven sections in order" || bad "sections missing or out of order"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
ids = re.findall(r'^\s+[A-Z_]+\("(\w+)", "[^"]+", (?:true|false),', s, re.M)
sys.exit(0 if ids == ['status', 'actions', 'keeper', 'callers', 'audit', 'keeper_log', 'console'] else 1)
PY
echo "$M" | grep -E 'AUDIT\("audit", "[^"]+", true' >/dev/null && echo "$M" | grep -E 'KEEPER_LOG\("keeper_log", "[^"]+", true' >/dev/null \
  && ! echo "$M" | grep -E '(STATUS|ACTIONS|KEEPER|CALLERS)\("[a-z_]+", "[^"]+", true' >/dev/null && ok "only the two logs start collapsed" || bad "collapsed defaults wrong"
echo "$P" | grep -q 'KitStoreRowLine' && echo "$P" | grep -q 'KitStoreRow\.body' && ok "drawn from KitStoreRow parts and the type ramp" || bad "not drawn from KitStoreRow"
echo "$P" | grep -E '[0-9]\.sp\b|[0-9]sp\b' >/dev/null && bad "an sp literal in the page" || ok "no sp literal"
echo "$P" | grep -E 'Button\(|minHeight|defaultMinSize|heightIn' >/dev/null && bad "Material Button or minimum height" || ok "no Material Button, no minimum height"
echo "$P" | grep -q 'console = { AdbShellPage() }' && ok "#1550 the console hosts the full lib page" || bad "#1550 the full channel console is not hosted"
echo "$P" | grep -q 'for (sec in AdbShellModel.ORDER)' && ok "drawn from AdbShellModel.ORDER" || bad "not drawn from the declared order"
echo "$P" | grep -q 'ON_RESUME' && ! echo "$P" | grep -Eq 'while \(true\)|delay\(' && ok "loads on open and resume, no polling" || bad "polls, or does not reload on resume"
for f in configs/AdbShellModelTest configs/AdbShellBrokerPageTest launcher/AdbPeerDotsTest launcher/StatusBarDeclTest; do { [ -f "$T/$f.kt" ] || [ -f "$LT/$f.kt" ]; } && ok "tests: $f" || bad "missing tests: $f"; done
echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
