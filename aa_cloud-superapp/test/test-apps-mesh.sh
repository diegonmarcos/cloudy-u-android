#!/usr/bin/env bash
# #733 Configs ▸ Setup ▸ Network and the Apps Mesh page.
#
#   C1  captions: the wg page reads Cloud Mesh (id unchanged), the Store's mesh
#       entry and the Configs apps-mesh page both read Apps Mesh — the SAME
#       words, from their two declarations — and Mesh holds them in order
#   C2  ONE page, two entry points: AppsMeshFragment and the Store's renderMesh
#       both call AppsMesh.page, config/apps-mesh routes to AppsMeshFragment, and
#       nothing else draws the mesh
#   C3  the four member actions (API · Start · Stop · Open · Details) are declared
#       and each id has its own branch doing the thing it names
#   C4  the missing-membership detector: every GapKind has words + fix declared
#       and is emitted by gaps(); Export shares the report
#   C5  the declaration-only gap is FIXED: the devtools manifest makes every
#       mesh member visible to every other (queries + exported, guarded marker)
#   C6  Peer Control: a peer selector at the top, fed by the declarations, and no
#       action targets "whichever link is open first" any more
#   C9  #809 a member's Details is a sub-page (no dialog) with its own Copy, and
#       /api/fleet/controls serves the typed controls the page draws; 6 mutants
#   C7  #793 the Store's OWN controls: every button on the page (tools, each
#       member's row, All endpoints) is StoreBar.button and every filter
#       chip StoreBar.chip — the same two builders the Store's bar, rows and
#       filter use; the declared filters and tools each have their handler; the
#       page draws before it probes and probes member by member; the endpoints
#       API takes the same filter
#
# What the detector DECIDES (each gap planted on one app, each with its healthy
# control) is asserted by app/src/test/.../AppsMeshTest.kt.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
STORE="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
SMESH="$STORE/StoreMesh.kt"
CTRLS="$STORE/StoreControls.kt"
BAR="$STORE/StoreBar.kt"
API="$STORE/StoreDebugApi.kt"
CONTROLS="$LIBS/appstore/src/main/assets/appstore-controls.json"
MESH="$STORE/AppsMesh.kt"
FRAG="$STORE/AppsMeshFragment.kt"
PAGE="$STORE/StoreCloudFragment.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
DEVMAN="$LIBS/devtools/src/main/AndroidManifest.xml"
RECV="$LIBS/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetMemberReceiver.kt"
KDE="$LIBS/kde-connect/src/main/java/com/diegonmarcos/superapp/kdeconnect"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$CONTROLS" "$MESH" "$SMESH" "$CTRLS" "$BAR" "$API" "$FRAG" "$PAGE" "$PAGES" "$DEVMAN" "$RECV" "$KDE/KdePeers.kt" "$KDE/KdeConnectFragment.kt"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — a check over nothing passes" >&2; exit 2; }
done
# Comment-stripped Kotlin, so a KDoc that NAMES a call cannot satisfy a check for the call.
code() { python3 - "$1" <<'EOF'
import re, sys
s = open(sys.argv[1]).read()
s = re.sub(r'/\*.*?\*/', '', s, flags=re.S); s = re.sub(r'(?m)^\s*//.*$', '', s)
print(s)
EOF
}
# The body of one Kotlin function, by name (to the first line that closes it at its indent).
fn() { code "$1" | awk -v n="fun $2(" 'index($0, n){f=1; match($0,/^ */); ind=RLENGTH} f{print} f && $0 ~ "^ {" ind "}}$" {exit}'; }

echo "== C1: captions =="
out="$(python3 - "$BJ" "$CONTROLS" <<'EOF'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
store_caption = json.load(open(sys.argv[2]))['pages']['mesh']['label']
mesh = [p['id'] for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')]
print('WG', by.get('wg', {}).get('label'), by.get('wg', {}).get('action'))
print('APPS', by.get('apps-access', {}).get('label'), by.get('apps-access', {}).get('action'), '|', store_caption, '|', ','.join(i for i in ('apps-mesh', 'ipc', 'identity', 'apps-mesh-members') if i in by))
print('ORDER', ','.join(mesh))
EOF
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -qx 'WG Cloud Mesh section:wg' && ok "the wg page reads Cloud Mesh and still opens section:wg" \
  || bad "the wg page is not 'Cloud Mesh' opening section:wg"
echo "$out" | grep -qx 'APPS Apps Access action:open_apps_access | Apps Mesh | ' && ok "Configs holds ONE Apps Access tile (action:open_apps_access) and declares no Apps Mesh / IPC / Identity page; the Store's Access keeps Apps Mesh" \
  || bad "Configs must declare exactly the Apps Access tile and no apps-mesh, ipc, identity or apps-mesh-members page"
echo "$out" | grep -qx 'ORDER firewall,dns,webserver,wg,apps-access,adb-shell,wifi' && ok "Setup ▸ Network = Firewall · DNS (#740) · Webserver · Cloud Mesh · Apps Access · ADB Shell · WiFi (#1263)" \
  || bad "Setup ▸ Network is not firewall, dns, webserver, wg, apps-access, adb-shell, wifi in that order"

echo "== C2: one page, two entry points =="
code "$FRAG" | grep -qF 'AppsMesh.Page(this@AppsMeshFragment' && ok "AppsMeshFragment hosts AppsMesh.Page" \
  || bad "AppsMeshFragment does not draw AppsMesh.Page"
# #896.3 the Store's entry point is Cloud Store's Access page, which hosts the SAME AppsMeshFragment (no copy).
ACCESS="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt"
grep -qF 'AndroidFragment<AppsMeshFragment>' "$ACCESS" && ok "Store's Access page hosts the same AppsMeshFragment (AppsMesh.page)" \
  || bad "Store's Access page does not host AppsMeshFragment — the two entry points show different pages"
{ grep -qF 'StoreMesh.Mesh' "$PAGE" || grep -qF 'AppsMesh.Page(' "$PAGE"; } && bad "the Cloud page still draws its own mesh" \
  || ok "the Cloud page draws no mesh (it moved to Access)"
# #865 directly, or through the Cloud Store hand-off that embeds it until Cloud Store is installed.
HANDOFF="$(dirname "$PAGES")/../apps/CloudStoreHandoff.kt"
# The Configs tile hands over to Cloud Store's Access section (tab=access), or its install offer.
grep -qE 'actionType == "open_apps_access" +->' "$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt" \
  && grep -qE 'CloudStoreHandoff\.open\(this, "access"\)' "$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt" \
  && grep -qE 'launchExternalApp\("cloud-store"\)' "$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt" \
  && ok "open_apps_access opens Cloud Store on tab=access, else the Store install offer" || bad "open_apps_access does not open Cloud Store's Access / the install offer"
grep -qE 'sectionId == "config" && pageId == "(apps-mesh|ipc|identity|apps-mesh-members)"' "$PAGES" \
  && bad "SectionPages still routes an Apps Mesh / IPC / Identity page of its own" || ok "SectionPages hosts no Apps Mesh / IPC / Identity page (Cloud Store > Access owns them)"
callers="$(grep -rlF 'StoreMesh.Mesh(' "$LIBS" "$APP/app/src/main" --include=*.kt | xargs -r -n1 sh -c 'python3 - "$0" <<EOF
import re,sys
s=open(sys.argv[1]).read(); s=re.sub(r"/\*.*?\*/","",s,flags=re.S); s=re.sub(r"(?m)^\s*//.*$","",s)
print(sys.argv[1] if "StoreMesh.Mesh(" in s else "")
EOF' | grep -v '^$' | xargs -r -n1 basename | sort -u | tr '\n' ' ')"
[ "$callers" = "AppsMesh.kt " ] && ok "only AppsMesh draws the mesh (callers: $callers)" \
  || bad "the mesh is drawn outside AppsMesh: $callers"

echo "== C3: the member actions (#793: a row of buttons on each member's card) =="
ids="$(jq -r '.apps_mesh.controls[] | select(.scope=="member") | .id' "$CONTROLS" | tr '\n' ' ')"
act="$(fn "$MESH" act)"
[ -n "$act" ] || bad "could not isolate AppsMesh.act — the checks below would verify nothing"
for a in api start stop open details; do
  case " $ids " in *" $a "*) ok "action $a is declared" ;; *) bad "action $a is not declared in apps_mesh.controls (scope member)" ;; esac
  printf '%s' "$act" | grep -qE "^\s+\"$a\" ->" && ok "act() handles $a" || bad "act() has no branch for $a"
done
branch() { printf '%s' "$act" | awk -v a="\"$1\" ->" 'index($0,a){f=1} f{print} f && /^            "[a-z]+" ->/ && !index($0,a){exit}'; }
# #ipc docs ride the fleet IPC layer (StoreMesh.ask -> FleetIpc.call); only a pre-IPC build is asked over
# its loopback port, with the fleet bearer.
branch api     | grep -qF 'StoreMesh.docs(' && fn "$SMESH" docs | grep -qF 'ask(ctx, pkg, port, "docs")' \
  && fn "$SMESH" ask | grep -qF 'FleetIpc.call(' && fn "$SMESH" ask | grep -qF 'FleetToken.get' \
  && ok "Docs fetches docs over IPC, a pre-IPC build over HTTP with the fleet bearer" || bad "Docs does not fetch docs over the fleet IPC layer (HTTP + bearer only as the fallback)"
branch api     | grep -qF 'row.docs[app.id]?.let { body ->' && branch api | grep -qF 'row.panel' \
  && ok "Docs unfolds in the card and reuses the fetched copy" || bad "Docs does not unfold in the card from a cached copy"
branch start   | grep -qF 'FleetPeers.wake('  && branch start | grep -qF 'row.reprobe(app)' \
  && ok "Wake wakes through the fleet provider, then re-probes that member" || bad "Wake does not use FleetPeers.wake and re-probe"
branch stop    | grep -qF 'PhoneAppActions.forceStop('  && branch stop | grep -qF 'row.noChannel(app)' \
  && code "$MESH" | grep -qF 'store_fleet_stop_no_channel' \
  && ok "Stop force-stops via the privileged channel and says so when none is armed" \
  || bad "Stop does not use the shell channel, or is silent when it is not armed"
branch open    | grep -qF 'getLaunchIntentForPackage'   && ok "Open launches the app" || bad "Open does not launch the app"
DPAGE="$(code "$MESH" | awk '/fun DetailsPage\(/{f=1} f{print} f && /^        }$/{exit}')"
printf '%s' "$DPAGE" | grep -qF 'details(appCtx, app' && ok "Details builds the full detail text (on its sub-page)" || bad "the Details page does not build details()"
fn "$MESH" details | grep -qF 'installedDetails' && fn "$MESH" details | grep -qF 'permissions(' \
  && ok "details() reads the installed APK identity and its permissions" || bad "details() lacks installed identity or permissions"
printf '%s' "$DPAGE" | grep -qF '"copy" to { copy(ctx, body) }' && ok "the Details page offers Copy all" || bad "no Copy all on the Details page"
fn "$MESH" ColumnScope.MemberButtons | grep -qF 'val mine = actionsFor(decl, onStore != null)' && fn "$MESH" ColumnScope.MemberButtons | grep -qF 'for (a in cs)' \
  && ok "each member's card draws the declared actions" || bad "member cards do not draw the declared actions"
code "$MESH" | grep -qF 'fun showActions(' && bad "a member's actions still hide behind a dialog" || ok "no action dialog: the buttons are on the card"

echo "== C4: missing membership + export =="
kinds="$(code "$MESH" | sed -n 's/.*enum class GapKind { \(.*\) }.*/\1/p' | tr -d ' ' | tr ',' '\n')"
[ -n "$kinds" ] || bad "no GapKind enum found — the checks below would verify nothing"
gaps_fn="$(fn "$MESH" gaps)"
for k in $kinds; do
  jq -e --arg k "$k" '.apps_mesh.gaps[$k] | (.label|length>0) and (.fix|length>0)' "$CONTROLS" >/dev/null \
    && ok "gap $k has its words and fix declared" || bad "gap $k has no label/fix in apps_mesh.gaps"
  printf '%s' "$gaps_fn" | grep -qF "GapKind.$k" && ok "gaps() can report $k" || bad "gaps() never reports $k"
done
fn "$MESH" share | grep -qF 'Intent.ACTION_SEND' && fn "$MESH" GapsPage | grep -qF 'report(decl' \
  && fn "$MESH" GapsPage | grep -qF '"export" to { share(host, "Apps Mesh", "text/plain", report()) }' \
  && ok "Export shares the page's report" || bad "Export does not share report()"

echo "== C5: every mesh member can see every other (the manifest-only gap, fixed) =="
python3 - "$DEVMAN" <<'EOF' && ok "devtools queries MESH_MEMBER and exports a CONSTELLATION_DATA-guarded receiver answering it" || bad "the mesh visibility marker is missing or unguarded"
import sys, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
m = ET.parse(sys.argv[1]).getroot()
q = {a.get(A+'name') for a in m.findall('./queries/intent/action')}
r = [x for x in m.findall('./application/receiver')
     if x.get(A+'exported') == 'true'
     and x.get(A+'permission') == 'com.diegonmarcos.cloud.permission.CONSTELLATION_DATA'
     and {a.get(A+'name') for a in x.findall('./intent-filter/action')} & q]
sys.exit(0 if q and r else 1)
EOF
grep -qF 'class FleetMemberReceiver' "$RECV" && grep -qF 'FleetMemberReceiver' "$DEVMAN" \
  && ok "the receiver the manifest names exists" || bad "FleetMemberReceiver is declared but not implemented (or vice versa)"

echo "== C6: Peer Control targets the selected peer =="
code "$KDE/KdePeers.kt" | grep -qF 'KdeConnectConfig.get().devices' && code "$KDE/KdePeers.kt" | grep -qF 'KdeMesh.nodes()' \
  && ok "the peer list is the declared devices + the mesh nodes" || bad "KdePeers does not read both declarations"
KF="$(code "$KDE/KdeConnectFragment.kt")"
printf '%s' "$KF" | grep -qF 'connectedIds().firstOrNull()' \
  && bad "an action still targets the first open link instead of the selected peer" \
  || ok "no action targets 'the first open link'"
n="$(printf '%s' "$KF" | grep -cF 'selectedLiveId()')"
[ "$n" -ge 6 ] && ok "$n call sites target the selected peer" || bad "only $n call sites use selectedLiveId()"
cv="$(fn "$KDE/KdeConnectFragment.kt" Page)"
sel="$(printf '%s\n' "$cv" | grep -nF 'PeerSelector(' | cut -d: -f1)"
clip="$(printf '%s\n' "$cv" | grep -nF 'ClipboardCard(' | cut -d: -f1)"
[ -n "$sel" ] && [ -n "$clip" ] && [ "$sel" -lt "$clip" ] && ok "the selector sits at the top, above every action card" \
  || bad "the peer selector is not drawn above the action cards (selector@$sel clipboard@$clip)"

echo "== C7: the Store's own controls, filters, lazy probe (#793) =="
MROOT="$(fn "$MESH" Root)"
[ -n "$MROOT" ] || bad "could not isolate AppsMesh's Root page — the checks below would verify nothing"
fn "$BAR" Button | grep -qF 'storeControl = BUTTON' && fn "$BAR" Chip | grep -qF 'storeControl = CHIP' \
  && ok "StoreBar owns the one marked button and the one marked chip" || bad "StoreBar's button/chip carry no mark"
fn "$BAR" Bar | grep -qF 'Button(ctx.getString(R.string.store_bar_check_all)' && ok "the Store bar (Check all …) draws StoreBar.Button" \
  || bad "the Store bar draws its own buttons"
fn "$BAR" Actions | grep -qF 'StoreBar.Button(' && fn "$PAGE" FleetDetail | grep -qF 'StoreRow.Actions(' \
  && ok "the Store's app rows draw StoreBar.Button (through StoreRow.Actions)" \
  || bad "the Store's app rows draw their own buttons"
# The Store's filter is the shared chip: the page asks StoreRow.Filters, which draws every chip with StoreBar.Chip.
# (c7filter <Cloud page> <StoreBar.kt>: prints one FAIL line per defect, so a mutant can prove it goes red.)
c7filter() {
  fn "$1" FleetPage | grep -qF 'StoreRow.Filters(' || echo "FAIL the Store's filter is not StoreRow.Filters"
  fn "$2" Filters | grep -qF 'StoreBar.Chip(' || echo "FAIL StoreRow.Filters draws its own chips"
  fn "$1" FleetPage | grep -qE '\bText\(.*(All|Updates|Missing|Installed)' && echo "FAIL the Store's filter labels are drawn as plain text"
  return 0
}
[ -z "$(c7filter "$PAGE" "$BAR")" ] && ok "the Store's filter draws StoreBar.Chip (StoreRow.Filters)" \
  || bad "the Store's filter draws its own chips"
C7W="$(mktemp -d)"
cp "$PAGE" "$C7W/page.kt"; cp "$BAR" "$C7W/bar.kt"
python3 - "$C7W/page.kt" <<'EOF' || bad "C7 mutation 'the Store filter is plain text' did not apply"
import sys; p = sys.argv[1]; s = open(p).read()
old = "StoreRow.Filters(listOf(\"All\""; assert old in s, old
open(p, "w").write(s.replace(old, "Text(listOf(\"All\"", 1))
EOF
[ -n "$(c7filter "$C7W/page.kt" "$BAR")" ] && ok "C7 mutant 'the Store filter is not the shared chip line' goes red" || bad "C7 mutant 'the Store filter is not the shared chip line' stayed GREEN"
python3 - "$C7W/bar.kt" <<'EOF' || bad "C7 mutation 'Filters draws its own chip' did not apply"
import sys; p = sys.argv[1]; s = open(p).read()
i = s.index("fun Filters("); j = s.index("StoreBar.Chip(", i)
open(p, "w").write(s[:j] + "Text(" + s[j + len("StoreBar.Chip("):])
EOF
[ -n "$(c7filter "$PAGE" "$C7W/bar.kt")" ] && ok "C7 mutant 'StoreRow.Filters draws its own chip' goes red" || bad "C7 mutant 'StoreRow.Filters draws its own chip' stayed GREEN"
rm -rf "$C7W"
n="$(code "$MESH" | grep -cF 'StoreBar.Button(')"
[ "$n" -ge 2 ] && ok "Apps Mesh draws its buttons with StoreBar.Button ($n call sites: member rows, Controls())" \
  || bad "Apps Mesh has only $n StoreBar.Button call sites"
printf '%s' "$MROOT" | grep -qF 'StoreBar.Chip(' && ok "Apps Mesh filters with StoreBar.Chip" \
  || bad "Apps Mesh draws its own filter chips"
code "$MESH" | grep -qE 'setBackgroundColor\(|GradientDrawable|private fun tool\(' \
  && bad "Apps Mesh paints a control of its own" || ok "Apps Mesh paints no control of its own"
kfilters="$(code "$MESH" | sed -n 's/.*val FILTERS = listOf(\(.*\))/\1/p' | tr -d ' "' | tr ',' ' ')"
[ -n "$kfilters" ] || bad "no FILTERS list in AppsMesh"
FSEL='[.apps_mesh.controls[] | select(.type=="filter")]'
for f in $(jq -r "$FSEL[].id" "$CONTROLS"); do
  case " $kfilters " in *" $f "*) ok "filter '$f' is one AppsMesh implements" ;; *) bad "filter '$f' is declared but AppsMesh.FILTERS lacks it" ;; esac
done
for f in $kfilters; do
  [ "$f" = all ] && continue
  fn "$MESH" matches | grep -qE "^\s+\"$f\" ->" && ok "matches() decides '$f'" || bad "matches() has no branch for '$f'"
  jq -e --arg f "$f" "$FSEL | map(.id) | index(\$f)" "$CONTROLS" >/dev/null \
    && ok "filter '$f' has a declared chip" || bad "filter '$f' has no filter control in apps_mesh.controls"
done
for t in $(jq -r '.apps_mesh.controls[] | select(.scope=="root" and .type!="filter") | .id' "$CONTROLS"); do
  printf '%s' "$MROOT" | grep -qE "^\s+\"$t\" to " && ok "root control '$t' has a handler" || bad "root control '$t' is declared but has no handler"
done
for t in wake endpoints; do
  jq -e --arg t "$t" '[.apps_mesh.controls[] | select(.scope=="root")] | map(.id) | index($t)' "$CONTROLS" >/dev/null \
    && ok "the '$t' control is on the page" || bad "the '$t' control is not declared on root"
done
printf '%s' "$MROOT" | grep -qF 'putString(PREF_FILTER' && ok "the chosen filter is remembered" || bad "the filter is not persisted"
# draws everything static first (the model starts from the cached probe, the composition reads it), THEN probes
# (the effect that starts the probe runs after the first composition has been committed)
code "$MESH" | grep -qF 'mutableStateOf<StoreMesh.Live?>(cached?.live)' && fn "$MESH" Page | grep -qF 'DisposableEffect(m)' \
  && fn "$MESH" Page | grep -qF 'm.start()' && fn "$MESH" start | grep -qF 'probe(wake = false)' \
  && ok "the page draws everything static first, then probes" \
  || bad "the page does not draw before probing"
code "$MESH" | grep -qF 'readCache(ctx)' && code "$MESH" | grep -qF 'StoreMesh.probeEach(' \
  && ok "the page opens on the cached probe and fills member by member" || bad "the page does not use the cache or the per-member probe"
fn "$SMESH" probeEach | grep -qF 'Executors.newFixedThreadPool(POOL)' \
  && fn "$SMESH" probeEach | grep -qF 'if (port != null) { found(id, port); emit(listOf(id)) }' \
  && ok "probeEach runs a bounded pool and emits each member as it lands" || bad "probeEach is not bounded or not per member"
code "$API" | grep -qF 'q["filter"]' && fn "$MESH" catalogue | grep -qF 'matches(filter, it, links, live)' \
  && ok "/api/fleet/endpoints?filter= keeps what the page's chip keeps" || bad "the endpoints API does not filter like the page"

echo "== C8: three kinds of control, declared as data, drawn in three groups (#809) =="
types="$(code "$MESH" | sed -n 's/.*val TYPES = listOf(\(.*\))/\1/p' | tr -d ' "' | tr ',' ' ')"
[ "$types" = "page action filter " ] || [ "$types" = "page action filter" ] && ok "AppsMesh knows exactly three kinds: $types" \
  || bad "AppsMesh.TYPES is not page/action/filter: '$types'"
jq -e '.apps_mesh.controls | length > 0 and all(.type == "page" or .type == "action" or .type == "filter")' "$CONTROLS" >/dev/null \
  && ok "every declared control has a type of the three" || bad "a declared control has no (or an unknown) type"
jq -e '.apps_mesh | has("member_actions") or has("tool_rows") or has("tools") or has("filters") | not' "$CONTROLS" >/dev/null \
  && ok "no second, untyped declaration of controls remains" || bad "an untyped control list (member_actions/tools/tool_rows/filters) is still declared"
for x in json markdown export copy; do
  jq -e --arg x "$x" '[.apps_mesh.controls[] | select(.scope=="root") | .id] | index($x) | not' "$CONTROLS" >/dev/null \
    && ok "'$x' is not on the Apps Mesh root (it acts on a sub-page)" || bad "'$x' is declared on root, away from the page it acts on"
done
for p in "endpoints json" "endpoints markdown" "endpoints copy" "gaps export" "gaps copy"; do
  set -- $p
  jq -e --arg s "$1" --arg i "$2" 'any(.apps_mesh.controls[]; .scope==$s and .id==$i and .type=="action")' "$CONTROLS" >/dev/null \
    && ok "action '$2' lives on the $1 page" || bad "action '$2' is not declared on the $1 page"
done
jq -e '[.apps_mesh.controls[] | select(.scope=="root" and .type=="page") | .id] == ["endpoints","gaps"]' "$CONTROLS" >/dev/null \
  && ok "root's page buttons: App API Endpoints, Missing membership" || bad "root's page buttons are not endpoints + gaps"
grp="$(fn "$MESH" Controls)"
printf '%s' "$grp" | grep -qF 'StoreBar.PageButton(' && printf '%s' "$grp" | grep -qF 'StoreBar.Button(' \
  && printf '%s' "$grp" | grep -qF 'Group(scope, type)' \
  && ok "Controls() draws each type with its own Store builder, in a group per type" || bad "Controls() does not draw a group per type with the Store builders"
fn "$BAR" PageButton | grep -qF 'storeControl = PAGE' && ok "StoreBar owns the one marked page button" || bad "StoreBar.PageButton carries no mark"
[ "$(jq -r '.page_style' "$CONTROLS")" = "page" ] && ok "page buttons wear 'page'" || bad "page_style is not 'page' - a fourth look"
fn "$MESH" EndpointsPage | grep -qF 'AlertDialog' && bad "App API Endpoints is still a dialog, not a page" || ok "App API Endpoints is a page"

echo "== C9: Details is a sub-page, and the typed controls are on the debug API (#809) =="
# c9 <AppsMesh.kt> <StoreDebugApi.kt> <appstore-controls.json> — prints one FAIL line per defect
c9() {
  local act det
  act="$(fn "$1" act)"
  printf '%s' "$act" | grep -qE 'textDialog|AlertDialog[^\n]*Details|"details" -> thread' \
    && echo "FAIL the member 'details' action still opens a dialog"
  printf '%s' "$act" | grep -qF '"details" -> row.details(app)' \
    || echo "FAIL the member 'details' action does not hand over to the page's Details sub-page"
  det="$(code "$1" | awk '/fun DetailsPage\(/{f=1} f{print} f && /^        }$/{exit}')"
  code "$1" | grep -qF 'Sub("details"' || echo "FAIL DetailsPage is not opened as the 'details' sub-page"
  printf '%s' "$det" | grep -qF 'Controls("details"' \
    || echo "FAIL the Details sub-page does not draw its own declared controls"
  printf '%s' "$det" | grep -qF 'AlertDialog' && echo "FAIL the Details sub-page builds a dialog"
  jq -e 'any(.apps_mesh.controls[]; .scope=="member" and .id=="details" and .type=="page")' "$3" >/dev/null \
    || echo "FAIL member 'details' is not declared as a page"
  jq -e 'any(.apps_mesh.controls[]; .scope=="details" and .id=="copy" and .type=="action")' "$3" >/dev/null \
    || echo "FAIL the Details page's Copy is not declared on the details scope"
  code "$2" | grep -qF 'AppDebugServer.Op("controls"' || echo "FAIL /api/fleet/controls is not listed in the fleet route group"
  code "$2" | grep -qF '"controls" -> AppsMesh.controlsJson(AppsMesh.load(app))' \
    || echo "FAIL /api/fleet/controls does not answer from the page's own declaration (AppsMesh.load)"
  fn "$1" controlsJson | grep -qF 'for (c in decl.controls)' \
    || echo "FAIL controlsJson does not list the filtered decl.controls (what the page draws)"
}
out="$(c9 "$MESH" "$API" "$CONTROLS")"
[ -z "$out" ] && ok "Details opens as a sub-page with its own Copy; /api/fleet/controls serves the drawn controls" \
  || { printf '%s\n' "$out" | sed 's/^/    /'; bad "C9 Details / controls API"; }
# mutations: each planted defect must turn C9 red
MW="$(mktemp -d)"
c9mut() {  # c9mut <title> <file-key mesh|api|json> <python replace old> <new>
  cp "$MESH" "$MW/m.kt"; cp "$API" "$MW/a.kt"; cp "$CONTROLS" "$MW/c.json"
  local f; case "$2" in mesh) f="$MW/m.kt";; api) f="$MW/a.kt";; json) f="$MW/c.json";; esac
  python3 - "$f" "$3" "$4" <<'EOF' || { bad "C9 mutation '$1' did not apply"; return; }
import sys; p, old, new = sys.argv[1:4]; s = open(p).read()
assert old in s, old; open(p, "w").write(s.replace(old, new, 1))
EOF
  [ -n "$(c9 "$MW/m.kt" "$MW/a.kt" "$MW/c.json")" ] && ok "C9 mutant '$1' goes red" || bad "C9 mutant '$1' stayed GREEN"
}
c9mut "details back to a dialog"         mesh '"details" -> row.details(app)' '"details" -> AlertDialog.Builder(ctx).setTitle(app.label).show()'
c9mut "Details page drops its Copy"      mesh 'Controls("details"' 'Controls("gaps"'
c9mut "details declared as an action"    json '"id": "details",
        "type": "page"' '"id": "details",
        "type": "action"'
c9mut "controls op unregistered"         api 'AppDebugServer.Op("controls"' 'AppDebugServer.Op("controlz"'
c9mut "controls API answers a hand list" api '"controls" -> AppsMesh.controlsJson(AppsMesh.load(app))' '"controls" -> "[]"'
c9mut "controlsJson lists raw JSON"      mesh 'for (c in decl.controls)' 'for (c in emptyList<Action>())'
rm -rf "$MW"

echo
echo "== RESULT(#733 apps mesh): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
