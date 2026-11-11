#!/usr/bin/env bash
# Tester: Configs ▸ LAUNCHER IS A HEADING over four DIRECT pages (Presets |
# Controls | One-Hand | Notify), and neither the #154 merge that made it one
# page with four tabs, the #574 renames (Profiles → Presets, Modes → Controls),
# #580 moving Notify in, nor #649 promoting all four back out broke its stored
# settings or the targets that still name it.
#
# #649 INVERTED T1 AND T10 ON PURPOSE. There is no `launcher` page any more:
# Launcher is the `group` those four pages declare, so each of them is its own
# visible tile again and none of them is `hidden`. What this file is FOR did not
# change, because the two things a page move silently destroys did not change.
#   1. SETTINGS — a preference store keyed off a page id would have moved when
#      the page did, wiping every toggle the user had set. This asserts the
#      launcher/one-hand stores are named literally, so the id could change
#      (`launcher` → `controls`) and the page could stop existing entirely
#      without taking the data with it.
#   2. TARGETS — `page:config/onehand` is spoken by launcher shortcuts, edge
#      gestures, the radial menus and the App-Tabs history already on the
#      device. It resolved through the owner page + tab while One-Hand was a
#      tab; it now resolves directly, because the page stayed DECLARED through
#      both moves. A tab declared inline instead of as a real page would have
#      killed all of them at once, twice.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# is checked for the routing contract that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
SECTIONS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
STRIP="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"

echo "== T1: the former Launcher pages are DIRECT pages, now split across Device ▸ UI / Notification Center / Configs (#649, #1610) =="
# #649 dissolved the Launcher tab-strip into direct pages (a `group` string per page, not a `tabs` array on a fifth page);
# #1610 split them across the Device subpage's subgroups. Consecutiveness within each subgroup is still part of the assertion:
# the grid prints a heading when the subgroup CHANGES, so a member parked elsewhere would print the heading a second time.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
if any(p['id'] == 'launcher' for p in pages):
    print('a `launcher` page is declared again — #649 made Launcher a heading, '
          'and a page of that name would tile beside its own members')
    sys.exit()
def run(g): return [p['id'] for p in pages if p.get('group') == g and not p.get('hidden')]
launcher = run('Launcher'); tools = run('Tools'); cfg = run('Configs')
if launcher != ['controls', 'panels-ui', 'app-folders', 'onehand', 'launcher-sep-1', 'notify']: print('Launcher = %r' % (launcher,)); sys.exit()
if tools != ['c3-watchdog', 'tools-sep-1', 'xray', 'logcat-fleet', 'agents-watchdog']: print('Tools = %r' % (tools,)); sys.exit()
if cfg != ['perms', 'logcat', 'xray-app', 'app-about']: print('Configs = %r' % (cfg,)); sys.exit()
ids = [p['id'] for p in pages]
for r in (launcher, tools, cfg):
    first = ids.index(r[0])
    if ids[first:first + len(r)] != r: print('not CONSECUTIVE: %r' % (ids[first:first + len(r)],)); sys.exit()
by = {p['id']: p for p in pages}
if by['controls'].get('tabs') != ['presets', 'panels'] or by['panels'].get('tabs') != ['panels-all', 'panels-battery', 'panels-tools']:
    print('Controls is not Presets | Panels(All | Battery Hungers | Tools) [#1623 UI promoted out]: %r / %r' % (by['controls'].get('tabs'), by['panels'].get('tabs'))); sys.exit()
if by['panels-ui'].get('hidden') or by['panels-ui'].get('group') != 'Launcher':
    print('UI (panels-ui) is not a visible Launcher tile: %r' % (by.get('panels-ui'),)); sys.exit()
loose = [i for i in ('presets', 'panels', 'panels-all', 'panels-battery', 'panels-tools') if i not in by or not by[i].get('hidden')]
if loose:
    print('tab page(s) missing or drawing a tile of their own: %r' % (loose,)); sys.exit()
print('OK')
PY
)" "Launcher = Quick Actions (#1623 tabs Presets | Panels ▸ All | Battery Hungers | Tools), UI, Folders, One-Hand | Notify — all consecutive direct pages; Tools = Watchdog | X-Ray Device, Logcat Fleet, Agents; Configs = Perms, Logcat, X-Ray App, About (#1623)"

echo "== T2: no Configs page is a tab strip any more =="
# #865 deleted the generic tab-owner rule: its last subject, Store, became a
# tile that opens Cloud Store (extapp:cloud-store), so the rule had nothing left
# to check - exactly what it said should happen to it. What remains true, and
# is asserted here, is that Configs declares no strip at all; a new one must
# bring its own check.
OWNERS=$(python3 -c "
import json
pages = next(s for s in json.load(open('$BJ'))['ui']['sections']
             if s['id'] == 'config')['pages']
print(','.join(p['id'] for p in pages if p.get('tabs')))
")
# Agents (folded into Configs ▸ Observability ▸ X-Ray) and Apps Mesh (Apps | IPC |
# Identity) have checks of their own: test-configs-agents-page.sh A2, test-ipc-mesh-page.sh R1 and
# test-fleet-shared-uid.sh U4.
[ "$OWNERS" = "controls,adb-shell,wifi,bluetooth,panels" ] \
  && ok "the Configs tab strips are Controls (#1621 Presets | UI | Panels), ADB Shell (Status | Shell | Perms | All; check T2b and test-adb-shell-cockpit.sh), Panels (All | Battery Hungers | Tools), Bluetooth (Find | Connect | Interact | Debug | Dev; test-bluetooth-page.sh B1), WiFi (Scan | Channels | Link | Tools | Log; test-wifi-page.sh W1); Agents was folded into X-Ray (no strip of its own) (Apps Mesh, IPC and Identity are Cloud Store > Access now: no strip here) (Store opens Cloud Store since #865)" \
  || bad "Configs tab strips are [$OWNERS], expected controls,adb-shell,wifi,bluetooth,panels - a new one must bring a check of its own"

echo "== T2b: ADB Shell is a strip over Status | Shell | Perms | All, each tab a hidden, routed page =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
tabs = ['adb-status', 'adb-term', 'adb-perms', 'adb-all']
if by['adb-shell'].get('tabs') != tabs: print('adb-shell tabs = %r' % (by['adb-shell'].get('tabs'),)); sys.exit()
if [by[t]['label'] for t in tabs] != ['Status', 'Shell', 'Perms', 'All']: print('tab labels = %r' % [by[t]['label'] for t in tabs]); sys.exit()
loose = [t for t in tabs if not by[t].get('hidden')]
print('tab(s) drawing a tile of their own: %r' % (loose,) if loose else 'OK')
PY
)" "adb-shell tabs Status | Shell | Perms | All are hidden pages"
for t in adb-status adb-term adb-perms adb-all; do
  grep -qF "pageId == \"$t\"" "$PAGES" && ok "$t routes to its fragment" || bad "$t has no route"
done

echo "== T3: the One-Hand id SURVIVES (page:config/onehand must still resolve) =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
oh = next((p for p in pages if p['id'] == 'onehand'), None)
if oh is None:            print('the onehand page was DELETED — every stored target now dead-ends')
elif oh['label'] != 'One-Hand': print('label = %r' % oh['label'])
else:                     print('OK')
PY
)" "onehand still declared, so its target resolves through Section.allPages"

echo "== T3b: Notify keeps its id and mirrors the ntfy page; Home and Push are GONE (#580) =="
# Configs ▸ Home had two tabs, Push and Notify. Push was merged INTO Notify and
# Notify moved to Launcher, which left Home with nothing — so it is deleted, not
# hidden. A page kept "just in case" is a tile that opens an empty strip. Every
# declaration that could still point at either id is checked here: a page, a tab
# list, and a `page:config/...` target anywhere in build.json.
check "$(python3 - "$BJ" <<'PY'
import json, re, sys
raw = open(sys.argv[1]).read()
ui = json.loads(raw)['ui']
pages = next(s for s in ui['sections'] if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
for gone in ('home', 'push'):
    if gone in by_id: problems.append('page %r is still declared' % gone)
n = by_id.get('notify')
if n is None:                                   problems.append('notify page was deleted — its target dead-ends')
elif n.get('mirror_page') != 'communication/my-rss':
                                                problems.append('notify no longer mirrors communication/my-rss: %r' % n.get('mirror_page'))
for p in pages:
    for t in p.get('tabs', []):
        if t in ('home', 'push'): problems.append('%s lists retired tab %r' % (p['id'], t))
for m in re.findall(r'page:config/(home|push)\b', raw):
    problems.append('a target still names page:config/%s' % m)
print('; '.join(problems) or 'OK')
PY
)" "notify declared + mirroring; no home/push page, tab or page: target survives"

echo "== T4: Sections parses the page tabs list and can answer who owns a tab =="
grep -qF 'tabs     = po.optJSONArray("tabs")' "$SECTIONS" \
  && ok "Page.tabs is parsed from build.json" \
  || bad "Page.tabs is not parsed — the declaration would be ignored"
grep -qF 'fun tabOwnerOf(sectionId: String, pageId: String): Page?' "$SECTIONS" \
  && ok "tabOwnerOf exists (tab id → owning page)" \
  || bad "tabOwnerOf missing — a tab id has no way back to its page"
grep -qF 'owner.tabs.filter { it != owner.id }' "$SECTIONS" \
  && ok "a page listed among its own tabs is dropped (no render loop)" \
  || bad "self-referencing tab guard missing"

echo "== T5: a page that declares tabs renders the SAME strip a section does =="
grep -qF 'tabs.isNotEmpty() -> SectionTabsFragment.forPage(sectionId, pageId)' "$PAGES" \
  && ok "SectionPages routes a tabbed page to SectionTabsFragment (no second tab widget)" \
  || bad "tabbed-page branch missing or routed elsewhere"
# The tabs branch must be tested BEFORE the id branches, or a page id listed
# below would win and the strip would never be built.
tabs_ln=$(grep -n 'tabs.isNotEmpty() ->' "$PAGES" | head -1 | cut -d: -f1)
url_ln=$(grep -n 'url.isNotBlank() ->' "$PAGES" | head -1 | cut -d: -f1)
if [ -n "$tabs_ln" ] && [ -n "$url_ln" ] && [ "$tabs_ln" -lt "$url_ln" ]; then
  ok "tabs branch (line $tabs_ln) precedes every id/url branch (line $url_ln)"
else
  bad "tabs branch does not come first in factoryFor"
fi
grep -qF 'forPage(sectionId: String, pageId: String): SectionTabsFragment' "$STRIP" \
  && ok "SectionTabsFragment.forPage is the page-strip entry point" \
  || bad "SectionTabsFragment.forPage missing"

echo "== T6: every tab routes to its fragment =="
grep -qF 'pageId == "panels-ui" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI)' "$PAGES" \
  && grep -qF 'pageId == "panels-tools" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS)' "$PAGES" \
  && ok "Panels ▸ UI and Tools → LauncherConfigFragment (the ONE producer, two scopes)" || bad "Panels UI/Tools lost their fragment"
grep -qF 'pageId == "onehand" ->' "$PAGES" \
  && ok "One-Hand tab → OneHandFragment" || bad "One-Hand tab lost its fragment"

echo "== T7: a target naming a tab is resolved to its page BEFORE anything else =="
own_ln=$(grep -n 'Sections.tabOwnerOf(sectionId, pageId)?.let' "$NAV" | head -1 | cut -d: -f1)
base_ln=$(grep -n 'if (host.currentSection != sectionId) {' "$NAV" | head -1 | cut -d: -f1)
if [ -n "$own_ln" ] && [ -n "$base_ln" ]; then
  [ "$own_ln" -lt "$base_ln" ] \
    && ok "tab redirect (line $own_ln) runs before the section base (line $base_ln)" \
    || bad "tab redirect (line $own_ln) runs AFTER the section base (line $base_ln) — the base is built for a page that is then replaced"
else
  bad "could not locate the tab redirect / section-base markers in openSectionPage"
fi
grep -qF 'recordActiveTab(SectionTabsFragment.pageTabKey(sectionId, owner.id), pageId)' "$NAV" \
  && ok "the wanted tab is recorded before the owner page opens" \
  || bad "the deep link would open the owner page on tab 0, not the tab asked for"

echo "== T8: a page strip cannot overwrite its SECTION's remembered tab =="
grep -qF 'if (ownerPageId.isBlank()) sectionId else pageTabKey(sectionId, ownerPageId)' "$STRIP" \
  && ok "page strips remember their tab under their own key" \
  || bad "page and section strips share one key — Configs would inherit Launcher's tab"

echo "== T9: NO preference store is keyed off a page id (settings must not move) =="
# This is the invariant that let `launcher` become `controls` for free. If a store
# name ever gets built from a page id, moving a control between pages silently
# resets it — which is exactly what this merge must never do.
sp_fail=""
for f in "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherThemePrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherProfilePrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherModes.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherSettingsPrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/HomeSwipePrefs.kt"; do
  [ -f "$f" ] || { sp_fail="$sp_fail $(basename "$f"):missing"; continue; }
  # Every getSharedPreferences argument must be a literal or a constant, never
  # an interpolated page id.
  grep -n 'getSharedPreferences(' "$f" | grep -q '\$' \
    && sp_fail="$sp_fail $(basename "$f"):interpolated"
done
[ -z "$sp_fail" ] \
  && ok "launcher + one-hand stores are named literally, so no key moved with the page" \
  || bad "preference store name built from a variable:$sp_fail"

echo "== T10: the Presets page is 'presets', never 'profile' (#339, #574, #649) =="
# `profile` is the owner's identity page (name, email, WireGuard export), which
# #626 relabelled Account and #649 filed under Setup ▸ Apps. `presets` is
# what you PICK on the launcher. One section cannot hold two pages under one id,
# so the Launcher member had to take a second name. Naming them apart is the
# whole reason the split works, which is why it is asserted rather than trusted —
# and #649 made BOTH of them visible tiles, so the id is now the only thing
# keeping them apart.
check "$(python3 - "$BJ" <<'PY'
import json, sys
section = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
pages   = {p['id']: p for p in section['pages']}
if 'profile' not in pages:
    print("the owner's identity page 'profile' is GONE — Configs lost its own tile")
elif 'controls' not in pages:
    print("no 'controls' page — the Launcher group has nothing to render")
elif pages['profile'].get('hidden'):
    print("'profile' went hidden — the owner's identity tile vanished from Configs")
elif pages['controls'].get('hidden'):
    print("'controls' went hidden — it is the Launcher group's direct tile")
elif (pages['profile'].get('group'), pages['profile'].get('subgroup')) == (pages['controls'].get('group'), pages['controls'].get('subgroup')):
    print("'profile' and 'presets' landed under one heading (%r) — the owner's "
          "identity is Watchdog setup, the launcher preset is not"
          % (pages['profile'].get('group'),))
else:
    print('OK')
PY
)" "profile (Setup ▸ Apps) and controls (Setup ▸ Launcher) are two visible pages under two headings"

grep -qF 'pageId == "presets" -> LauncherPresetsFragment.newInstance()' "$PAGES" \
  && ok "the presets tab routes to its own fragment" \
  || bad "presets has no route — the tab would fall through to the generic page"

# #867 through the Cloud Account hand-off, which embeds ProfileFragment until the app is installed.
grep -qF 'pageId == "profile"   -> com.diegonmarcos.superapp.apps.AccountHandoff.page()' "$PAGES" \
  && ok "the identity page still routes to ProfileFragment (via the Cloud Account hand-off)" \
  || bad "profile lost its route — the split stole the owner's identity page"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
