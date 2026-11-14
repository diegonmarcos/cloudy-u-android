#!/usr/bin/env bash
# #ipc SuperApp > Configs > Network > Apps Mesh > IPC (a0_docs/eng-specs/fleet-ipc.md): the fleet's IPC Mesh,
# the SAME shared composable Cloud Store > Access > IPC draws (libs:appstore IpcMeshPage).
#   R1 build.json: Configs declares NO ipc / apps-mesh page; Network holds the one Apps Access tile (2..8 tiles)
#   R2 SectionPages builds no ipc page; libs:appstore IpcMeshFragment (Cloud Store > Access hosts it) hosts IpcMeshPage() and nothing else
#   R3 the reachability and "every declared endpoint is listed" tests exist (IpcMeshPageTest P1-P3)
# Then each planted defect must turn it red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
SP="app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
PG="ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/IpcMeshPage.kt"
UT="app/src/test/java/com/diegonmarcos/superapp/apps/IpcMeshPageTest.kt"
check() {  # check <app dir> <page file>
  local A="$1" P="$2" f=0
  python3 - "$A/build.json" <<'PY' || { echo "  FAIL: R1 Configs must hold one Apps Access tile and no ipc / apps-mesh page of its own (2..8 Network tiles)"; f=$((f+1)); }
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
net = [p['id'] for p in pages if p.get('group') == 'Network' and p['id'] in ('ng-firewall','wifi','bluetooth','dns','wg','firewall','os-perms','webserver','proxy','apps-access','adb-shell')]  # #1737 the Network group's declared pages (Apps Access hidden, reached from OS Perms)  # #1623 Network is a top-level group (no subgroup)
ids = {p['id'] for p in pages}
ok = 'apps-access' in net and not ({'ipc', 'apps-mesh', 'apps-mesh-members'} & ids) and 2 <= len([i for i in net if not next(p for p in pages if p['id'] == i).get('hidden')]) <= 9
sys.exit(0 if ok else 1)
PY
  ! grep -q 'pageId == "ipc"' "$A/$SP" \
    || { echo "  FAIL: R2 SectionPages still builds an ipc page"; f=$((f+1)); }
  grep -q 'ComposeView(requireContext()).apply { setContent { IpcMeshPage() } }' "$P" \
    || { echo "  FAIL: R2b IpcMeshFragment does not host IpcMeshPage"; f=$((f+1)); }
  for t in 'P1 the baked registry carries every declared endpoint' 'P2 the page lists every declared endpoint' \
           'P3 IPC is Cloud Store Access only, Configs keeps one Apps Access tile and hosts the shared page'; do
    grep -qF "fun \`$t\`()" "$A/$UT" || { echo "  FAIL: R3 IpcMeshPageTest lost: $t"; f=$((f+1)); }
  done
  return $f
}
FAIL=0
if check "$APP" "$ROOT/$PG"; then echo "  PASS: R1-R3 Configs > Network > Apps Access replaces the IPC page; the shared IPC Mesh page stays Cloud Store's"; else FAIL=$((FAIL+1)); fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
while IFS='|' read -r file expr; do
  [ -n "$file" ] || continue
  rm -rf "$TMP/a"; mkdir -p "$TMP/a/$(dirname "$SP")" "$TMP/a/$(dirname "$UT")"
  cp "$APP/build.json" "$TMP/a/"; cp "$APP/$SP" "$TMP/a/$SP"; cp "$APP/$UT" "$TMP/a/$UT"; cp "$ROOT/$PG" "$TMP/page.kt"
  tgt="$TMP/a/$file"; [ "$file" = page ] && tgt="$TMP/page.kt"
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$expr; open(p,'w').write(t); sys.exit(0 if t!=s else 3)" "$tgt" \
    || { echo "  FAIL: mutation did not apply: $file"; FAIL=$((FAIL+1)); continue; }
  if check "$TMP/a" "$TMP/page.kt" >/dev/null 2>&1; then echo "  FAIL: NOT caught: $file ${expr:0:60}"; FAIL=$((FAIL+1))
  else echo "  PASS: caught: $file ${expr:0:60}"; fi
done <<MUT
build.json|s.replace('"id": "apps-access"', '"id": "ipc"')
build.json|s.replace('"id": "adb-shell"', '"id": "apps-mesh"')
$SP|s.replace('// Configs > Network > ADB Shell:', 'sectionId == "config" && pageId == "ipc" -> x\n        // Configs > Network > ADB Shell:')
page|s.replace('setContent { IpcMeshPage() }', 'setContent { }')
$UT|s.replace('fun \`P2 the page lists every declared endpoint\`()', 'fun p2()')
MUT
echo "== RESULT(#ipc SuperApp IPC page): $FAIL failed =="
[ "$FAIL" -eq 0 ]
