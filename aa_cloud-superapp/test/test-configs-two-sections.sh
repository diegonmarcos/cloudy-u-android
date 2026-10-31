#!/usr/bin/env bash
# Tester: #649/#723 + the Agents regroup + the 2026-10 reorg — Configs is THREE declared sections, Setup, Fleet
# (C3 · Peer Control · Agents) and Observability (About),
# and Setup has THREE subsections, Apps, Launcher and Network (#878 relabelled; was Watchdog ▸ Setup/Mesh). #723 moved
# WireGuard and KDE (now labelled Peer Control) out of Setup into Mesh and deleted
# the AI page outright. Actions is untouched.
#
# WHAT THIS FILE IS FOR. A regroup this size has one failure mode that no other
# tester in this directory can see: an ORPHANED PAGE. Every page still exists,
# every page still parses, every page still has a fragment behind it — and one of
# them is reachable from nowhere, because it slipped out of the run of entries
# its heading covers and out of every `tabs` list at the same time. The grid
# draws the other twenty, the suite stays green, and the page is gone. T5 below
# is that check, and it is the reason this file exists; the rest is the structure
# the owner asked for, pinned so a later edit has to mean it.
#
# THE SECOND FAILURE MODE is a heading that looks declared and is not. Moving the
# words "Launcher" or "Setup" into Kotlin would render identically today and make
# every assertion here unfalsifiable tomorrow, because build.json would no longer
# be what the screen is built from. T4 greps for that directly.
#
# Static tester (no device, no build): build.json is read as data; the Kotlin is
# checked for the contract that data relies on, and for the literals it must not
# contain.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
SECTIONS="$KT/launcher/Sections.kt"
NAV="$KT/launcher/LauncherNavController.kt"
GRID="$KT/launcher/TileGridFragment.kt"
PAGES="$KT/launcher/SectionPages.kt"

command -v python3 >/dev/null 2>&1 || {
  echo "  ABORT: python3 missing — every assertion below would report a verdict"
  echo "         from its absence rather than from the declaration."; exit 2; }
for f in "$BJ" "$SECTIONS" "$NAV" "$GRID" "$PAGES"; do
  [ -f "$f" ] || {
    echo "  ABORT: no such file: $f — a grep over nothing matches nothing, which"
    echo "         is indistinguishable here from a contract being kept."; exit 2; }
done

echo "== T1: the two sections and the three Setup subsections, in declaration order =="
# Both the NAMES and the ORDER, read off the file the grid reads. Groups are runs
# of consecutive entries, so this doubles as the consecutiveness check: a member
# parked elsewhere in the array shows up here as a repeated heading.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
shape, last = [], None
for p in pages:
    if p.get('hidden') or p.get('is_action'): continue
    key = (p.get('group', ''), p.get('subgroup', ''))
    if key != last: shape.append(key); last = key
want = [('Setup', 'Connect'), ('Setup', 'Apps'), ('Setup', 'Launcher'), ('Setup', 'Network'),
        ('Fleet', ''), ('Observability', '')]
print('OK' if shape == want else 'heading runs = %r' % (shape,))
PY
)" "Setup ▸ Connect, ▸ Apps, ▸ Launcher, ▸ Network, then Fleet, then Observability — three sections, each appearing ONCE"

echo "== T2: each heading holds exactly the pages the owner named =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
by = {p['id']: p for p in pages}
obs = next(g for g in sec['groups'] if g['id'] == 'observability')
def members(g, s=''):
    return [p['label'] for p in pages
            if not p.get('hidden') and not p.get('is_action')
            and p.get('group', '') == g and p.get('subgroup', '') == s]
want = {
    ('Setup', 'Launcher'):             ['Quick Actions', 'One-Hand', 'Notify'],  # Controls = tabs Presets | Panels (UI | Tools)
    ('Fleet', ''):                     ['Bluetooth', 'C3 Fleet Control', 'Peer Control'],  # #1221 Bluetooth first; Agents moved to Observability
    ('Setup', 'Connect'):          ['Account'],
    ('Setup', 'Apps'):             ['Store'],  # Permissions moved to Observability
    ('Setup', 'Network'):              ['Firewall', 'DNS', 'Webserver', 'Cloud Mesh', 'Apps Access', 'ADB Shell', 'WiFi'],  # #1263 WiFi right after ADB Shell; IPC is a tab of Apps Mesh, not a tile
    # Observability is read from the registry: the labels of build.json groups[observability], visible pages, in order.
    ('Observability', ''):          [by[i]['label'] for i in obs['pages'] if i in by and not by[i].get('hidden')],
}
problems = ['%s%s = %r' % (g, ' ▸ ' + s if s else '', members(g, s))
            for (g, s), exp in want.items() if members(g, s) != exp]
print('; '.join(problems) or 'OK')
PY
)" "Setup ▸ Connect = Account; Setup ▸ Launcher = Controls/One-Hand/Notify; Fleet = Bluetooth/C3 Fleet Control/Peer Control; Observability = its declared group (X-Ray/LogCat/Permissions/About); Setup ▸ Apps = Store; Network = Firewall/DNS/Webserver/Cloud Mesh/Apps Mesh/ADB Shell (#733, #740; IPC and Identity are Apps Mesh tabs); About is the last page"

echo "== T3: Store is the former Constellation, and Cloud Constellation is INSIDE it =="
# #563 renamed the Constellation page Store; the word survives as the label of
# the fleet tab inside it. Both halves are asserted because either one alone
# reads as a finished rename: a Store page with no Cloud Constellation tab means
# the fleet store was dropped, and a second page called Constellation means the
# rename was undone beside it.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
store = by_id.get('store')
if store is None:                             problems.append('no `store` page')
else:
    if store.get('group') != 'Setup' or store.get('subgroup') != 'Apps':
        problems.append('store is under %r/%r, not Setup ▸ Apps'
                        % (store.get('group'), store.get('subgroup')))
    # #865 the Store is its own app: the tile opens it and owns no tabs.
    if store.get('action') != 'extapp:cloud-store' or store.get('tabs'):
        problems.append('store action = %r, tabs = %r' % (store.get('action'), store.get('tabs')))
if by_id.get('store-cloud', {}).get('label') != 'Cloud Constellation':
    problems.append('the Cloud Constellation tab lost its label: %r'
                    % (by_id.get('store-cloud', {}).get('label'),))
if any(p['label'] == 'Constellation' for p in pages):
    problems.append('a page is labelled Constellation again — #563 renamed it Store')
print('; '.join(problems) or 'OK')
PY
)" "Store sits in Setup ▸ Apps and opens Cloud Store; the Cloud Constellation page keeps its label; no page named Constellation"

echo "== T4: no heading, subheading or page name is a Kotlin literal on the grid path =="
# The whole point of the declaration: a literal would make the build.json edit
# inert while the screen kept looking correct.
#
# THE WORD LIST IS DERIVED, never typed here — it is every group, subgroup and
# visible label the config section declares. A hand list is the version of this
# check that goes quiet the moment a heading is renamed, which is precisely when
# it is needed.
#
# THE SCOPE IS THE GRID PATH — the three files that turn the declaration into
# tiles. Deliberately not the whole app, and this is the honest boundary rather
# than a convenient one: OTHER surfaces legitimately own the same words, and two
# do today. configs/OneHandFragment.kt prints "One-Hand" as the title INSIDE the
# One-Hand page, and profile/ProfileFragment.kt names a tab "Setup" inside Account
# (#626) — neither is the Configs grid deciding what a heading says, and widening
# this grep to catch them would make it fail on a coincidence of vocabulary
# instead of on a broken contract. What it must catch is any of these words
# reaching the grid from Kotlin, which is what these three files do and nothing
# else does.
#
# COMMENTS ARE STRIPPED. This file's own rationale quotes the headings it pins,
# and a grep that counts prose is a check that fails the moment it is explained.
check "$(python3 - "$BJ" "$SECTIONS" "$NAV" "$GRID" <<'PY'
import json, re, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
words = set()
for p in pages:
    for k in ('group', 'subgroup'):
        if p.get(k): words.add(p[k])
    if not p.get('hidden') and not p.get('is_action'): words.add(p['label'])
if not words:
    print('the config section declares no group, subgroup or label — this check '
          'derived an empty word list and would pass over anything'); sys.exit()
hits = []
for path in sys.argv[2:]:
    src = open(path).read()
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)          # block comments
    src = re.sub(r'(?m)//.*$', '', src)                       # line comments
    for w in sorted(words):
        if '"%s"' % w in src:
            hits.append('%s hardcodes "%s"' % (path.rsplit('/', 1)[-1], w))
print('; '.join(hits) or 'OK')
PY
)" "none of the declared headings or page names appears as a literal in Sections/LauncherNavController/TileGridFragment"
# How many words it actually checked, asserted separately. "No literals found" is
# also what an empty word list says, and the two must not read alike.
WORDS=$(python3 -c "
import json
pages = next(s for s in json.load(open('$BJ'))['ui']['sections']
             if s['id'] == 'config')['pages']
w = {p[k] for p in pages for k in ('group', 'subgroup') if p.get(k)}
w |= {p['label'] for p in pages if not p.get('hidden') and not p.get('is_action')}
print(len(w))
")
[ "$WORDS" = 26 ] \
  && ok "T4 derived 26 words from the declaration (X-Ray regroup: About became X-Ray, a new About, Agents and Diagnostics folded into X-Ray, Logcat relabelled LogCat; Setup, Apps, Launcher, Network, Fleet, Observability + 21 page labels (#1263: WiFi added; #1221: Bluetooth added, C3 became C3 Fleet Control), Diagnostics and Logcat the last two: the Agents regroup added Agents and dropped the | separator; #ipc added IPC; IPC became an Apps Mesh tab and left the labels)" \
  || bad "T4 derived $WORDS words, expected 26 — it is checking a different set than it claims"

# The ONE heading Kotlin may still name is the fallback for a section that
# declares none, which is every section but this one. Pinned so it stays the only
# one: an unreviewed second constant here is how a declared heading gets quietly
# replaced by a literal.
# #697 moved the pair from LauncherNavController into Sections so the grid and
# the menus share them; the controller may only alias, never spell, a heading.
KTGROUPS=$(grep -cE '^\s*const val GROUP_[A-Z]+ = "' "$SECTIONS" || true)
NAVLIT=$(grep -cE 'val GROUP_[A-Z]+ = "' "$NAV" || true)
[ "$KTGROUPS" = 2 ] && [ "$NAVLIT" = 0 ] \
  && ok "Sections names exactly 2 headings in Kotlin (the Pages fallback + Actions), the nav controller none" \
  || bad "$KTGROUPS hardcoded GROUP_ constants in Sections (expected 2), $NAVLIT in LauncherNavController (expected 0)"

echo "== T5: NO ORPHANED PAGE — every declared page is reachable =="
# THE check this file exists for. A page is reachable when it draws a tile (not
# `hidden`), OR it is a tab of a page that does, OR something names it as a
# target. Anything else exists, parses, has a fragment, and cannot be opened.
check "$(python3 - "$BJ" <<'PY'
import json, re, sys
raw   = open(sys.argv[1]).read()
pages = next(s for s in json.load(raw)['ui']['sections']
             if s['id'] == 'config')['pages'] if False else \
        next(s for s in json.loads(raw)['ui']['sections']
             if s['id'] == 'config')['pages']
by_id   = {p['id']: p for p in pages}
tiled   = {p['id'] for p in pages if not p.get('hidden')}
as_tab  = {}
for _ in range(4):  # tabs of tabs (Controls > Panels > UI | Tools): walk until nothing new is reachable
    as_tab.update({t: p['id'] for p in pages if p['id'] in tiled or p['id'] in as_tab for t in p.get('tabs', [])})
# A `page:config/<id>` anywhere in this file is a live door of its own.
targeted = set(re.findall(r'page:config/([A-Za-z0-9_-]+)', raw))
orphans = []
for p in pages:
    pid = p['id']
    if pid in tiled or pid in as_tab or pid in targeted: continue
    orphans.append(pid)
if orphans:
    print('unreachable: %r — declared, renders, and nothing opens it' % (orphans,))
    sys.exit()
# And the reverse: a tab naming a page that does not exist is a dead strip entry.
dangling = [(p['id'], t) for p in pages for t in p.get('tabs', []) if t not in by_id]
print('tabs with no page behind them: %r' % (dangling,) if dangling else 'OK')
PY
)" "every config page draws a tile, is a tab of one that does, or is named as a page: target"

# T5 is only as strong as the page set it walks. Pin the COUNT: a page deleted
# outright cannot be reported as an orphan by a check that iterates what is left.
COUNT=$(python3 -c "
import json
print(len(next(s for s in json.load(open('$BJ'))['ui']['sections']
               if s['id'] == 'config')['pages']))
")
[ "$COUNT" = 41 ] \
  && ok "41 config pages declared — the set T5 walks is the whole set (X-Ray regroup: minus about, diagnostics, agents and its two tabs, plus xray and app-about; #1263: plus wifi and its five hidden tabs; #1221: plus bluetooth and its five hidden tabs; #1264: plus its Analyze and Locate tabs; #649: 22 minus launcher; #723: minus ai and its 5 tabs; #733: plus apps-mesh; #740: plus dns; plus firewall; plus the hidden About ▸ Battery; Agents regroup: minus the Network separator, plus agents and its two tabs; #ipc: plus ipc; Controls merge: plus panels, panels-ui and panels-tools; Apps Mesh strip: plus apps-mesh-members and identity)" \
  || bad "$COUNT config pages, expected 41 — a page was added or DELETED, and T5 cannot report a page that is gone"

echo "== T6: ACTIONS IS UNTOUCHED =="
# The owner said to keep it as it is. Three things make that true: the same three
# entries, the same flag, and the same rendering path. Their POSITION in the array
# is deliberately not asserted — the grid appends the Actions group last whatever
# the order, which is what lets this section be regrouped without moving them.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
acts = [(p['id'], p['label'], p.get('action')) for p in pages if p.get('is_action')]
want = [('update',      'Update All',  'action:update_all'),
        ('kde_connect', 'KDE Connect', 'action:kde_connect_now'),
        ('animations',  'Animations',  'action:toggle_animations')]
if acts != want: print('actions = %r' % (acts,)); sys.exit()
grouped = [p['id'] for p in pages if p.get('is_action') and (p.get('group') or p.get('subgroup'))]
print('an action entry carries a group: %r — Actions is its own heading, off the '
      'is_action flag' % (grouped,) if grouped else 'OK')
PY
)" "Update All, KDE Connect, Animations — same ids, labels and targets, and none of them grouped"

grep -qF 'group = if (p.isAction) GROUP_ACTIONS else p.group.ifBlank { GROUP_PAGES }' "$NAV" \
  && ok "the Actions heading still comes from is_action, not from the new group field" \
  || bad "the is_action → Actions path changed — Actions was to be left alone"
grep -qF 'own.filterNot { it.group == GROUP_ACTIONS }' "$NAV" \
  && ok "the pages half is everything that is NOT an action (not: everything named Pages)" \
  || bad "the grid still selects pages by the fallback heading name, so a declared heading drops them off the grid"

echo "== T7: the declaration actually reaches the screen =="
grep -qF 'group    = po.optString("group", "")' "$SECTIONS" \
  && ok "Page.group is parsed from build.json" \
  || bad "Page.group is not parsed — the declared heading would be ignored"
grep -qF 'subgroup = po.optString("subgroup", "")' "$SECTIONS" \
  && ok "Page.subgroup is parsed from build.json" \
  || bad "Page.subgroup is not parsed — the second level would be ignored"
grep -qF 'subgroup = if (p.isAction) "" else p.subgroup' "$NAV" \
  && ok "the nav controller carries subgroup through to the tile" \
  || bad "subgroup never reaches TileGridFragment — Setup/Observability would not draw"
grep -qF 'items += GridItem.SubHeader(s)' "$GRID" \
  && ok "TileGridFragment draws a subgroup header" \
  || bad "no subgroup header is drawn — the Setup subsections would run together"
grep -qF 'subs.getOrNull(i + span).orEmpty() == s' "$GRID" \
  && ok "a grid row never straddles a subgroup boundary" \
  || bad "the row span ignores subgroup — Observability's tile would share Setup's last row"
grep -qF 'shownSub = null' "$GRID" \
  && ok "a new group resets the remembered subgroup (a repeated subheading under a later group still draws)" \
  || bad "shownSub is never reset — the same subheading under two groups would be drawn once"

echo "== T8: each of Launcher's four pages opens on its OWN, not via a tab owner =="
# They were tabs until #649. A tile that still resolves to an owner page would
# land the owner on a tab strip that no longer exists.
for pid in presets panels-ui panels-tools onehand notify; do
  case "$pid" in
    notify) grep -qF '"mirror_page": "communication/my-rss"' "$BJ" \
              && ok "notify renders through its declared mirror_page, no factory branch needed" \
              || bad "notify has neither a mirror_page nor a fragment — the tile would open nothing" ;;
    *)      grep -qE "pageId == \"$pid\"" "$PAGES" \
              && ok "page:config/$pid resolves to its own fragment in SectionPages" \
              || bad "no SectionPages branch for $pid — its new tile opens the fallback placeholder" ;;
  esac
done

echo "== T9: NO PAGE WAS LOST — the id set before the regroup == the id set now, minus declared retirements (#697) =="
# T5 walks what is left and the COUNT pin above only sees a net change: delete one
# page and add another and both stay green. This is a SET check against the ids
# as they stood at 171d9e048~1, the last declaration before #649 regrouped the
# section — the pages the owner had working. A page may leave the set only by
# being named in build.json `retired_pages` with the pages its content lives on
# now, and those must be real, visible pages. A NEW page fails here too, on
# purpose: adding one to Configs is a decision, and it is recorded by adding its
# id to BEFORE below.
check "$(python3 - "$BJ" <<'PY'
import json, sys
BEFORE = {'bluetooth-analyze', 'bluetooth-locate', 'presets', 'controls', 'onehand', 'notify', 'launcher',
          'profile', 'wg', 'kde', 'ai', 'perms', 'store', 'about',
          'websearch', 'localsearch', 'textenhance', 'library', 'tokens',
          'store-cloud', 'store-phone',
          'panels', 'panels-ui', 'panels-tools',  # added on purpose: Configs ▸ Setup ▸ Launcher ▸ Controls ▸ Panels (UI | Tools), hidden tabs of the merged Controls
          'update', 'kde_connect', 'animations',
          'webserver',  # added on purpose: Configs ▸ Network ▸ Webserver (opens the Cloud C3 Webserver app, else localhost in Cloud Browser)
          'apps-mesh', 'ipc', 'apps-mesh-members', 'identity',  # were Apps Mesh and its tabs; now removed_pages (Cloud Store > Access)
          'apps-access',  # added on purpose: Configs ▸ Network ▸ Apps Access (opens Cloud Store > Access, else the Store install offer)
          'adb-shell',  # added on purpose: Configs ▸ Network ▸ ADB Shell (the privileged channel's page)
          'c3', 'network-sep-1',   # added on purpose: Network's C3 entry and its separator
          'firewall',   # added on purpose: Configs ▸ Network ▸ Firewall hosts the firewall engine's controls (About keeps a summary)
          'dns',        # #740 added on purpose: Configs ▸ Mesh ▸ DNS (the fleet resolver)
          'bluetooth', 'bluetooth-find', 'bluetooth-connect', 'bluetooth-interact', 'bluetooth-debug', 'bluetooth-dev',  # #1221 added on purpose: Configs ▸ Fleet ▸ Bluetooth and its five hidden tabs
          'wifi', 'wifi-scan', 'wifi-channels', 'wifi-link', 'wifi-tools', 'wifi-log',  # #1263 added on purpose: Configs ▸ Setup ▸ Network ▸ WiFi and its five hidden tabs
          'agents', 'agents-superapp', 'agents-fleet',  # added on purpose: Configs ▸ Fleet ▸ Agents and its two tabs
          'diagnostics',  # added on purpose: Configs ▸ Observability ▸ Diagnostics (device numbers + remote read)
          'xray', 'app-about',  # added on purpose: Configs ▸ Observability ▸ X-Ray (the former About) and the new About
          'logcat',     # added on purpose: Configs ▸ Observability ▸ Logcat, moved out of About into its own tab before it
          'battery'}    # added on purpose: Configs ▸ About ▸ Battery, the battery SoT's stats page (hidden, page:config/battery)
sec     = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
               if s['id'] == 'config')
pages   = sec['pages']
now     = {p['id'] for p in pages}
retired = sec.get('retired_pages', {})
removed = sec.get('removed_pages', {})
tiled   = {p['id'] for p in pages if not p.get('hidden') and not p.get('is_action')}
problems = []
lost = sorted(BEFORE - now - set(retired) - set(removed))
if lost:  problems.append('LOST %r — gone from pages and declared in neither retired_pages nor removed_pages' % lost)
# #723: a DELETION is declared too, with its reason — never inferred from absence.
for rid, why in sorted(removed.items()):
    if rid not in BEFORE: problems.append('removed %r never existed' % rid)
    if rid in now:        problems.append('removed %r is still declared' % rid)
    if rid in retired:    problems.append('%r is both retired and removed' % rid)
    if not str(why).strip(): problems.append('removed %r gives no reason' % rid)
new = sorted(now - BEFORE)
if new:   problems.append('NEW %r — add the id to BEFORE in this tester if it is meant' % new)
for rid, into in sorted(retired.items()):
    if rid not in BEFORE: problems.append('retired %r never existed' % rid)
    if rid in now:        problems.append('retired %r is still declared' % rid)
    if not into:          problems.append('retired %r names no successor' % rid)
    for s in into:
        if s not in tiled: problems.append('retired %r -> %r, which is not a visible page' % (rid, s))
print('; '.join(problems) or 'OK')
PY
)" "every pre-regroup config page id is still declared, retired into visible successors (launcher → Controls, One-Hand, Notify; Presets is a tab of Controls), or removed with a reason (ai + its five tabs)"

REMOVED=$(python3 -c "
import json
sec = next(s for s in json.load(open('$BJ'))['ui']['sections'] if s['id'] == 'config')
print(','.join(sorted(sec.get('removed_pages', {}))))
")
[ "$REMOVED" = "ai,apps-mesh,apps-mesh-members,identity,ipc,library,localsearch,network-sep-1,textenhance,tokens,websearch" ] \
  && ok "removed_pages is exactly the AI page, its five tabs, the Network separator and Apps Mesh with its IPC / Identity tabs (now Cloud Store > Access) — nothing else left Configs by deletion" \
  || bad "removed_pages = [$REMOVED] — the deletion escape hatch now covers a different set than #723 declared"

echo "== T10: the rail and the drawer list Configs under the SAME declared headings as the grid (#697) =="
# #649 reached the phone grid only. On a two-pane screen Configs renders through
# SectionMenuFragment (the rail), and the drawer expands `section:config` in
# HomeDrawerFragment; both walked section.pages flat, so an unfolded screen
# showed fourteen rows with no Launcher, Setup, Apps or Observability.
MENU="$KT/launcher/SectionMenuFragment.kt"
DRAWER="$KT/launcher/HomeDrawerFragment.kt"
for f in "$MENU" "$DRAWER"; do
  n=${f##*/}
  grep -qF 'val (g, s) = Sections.headingOf(section, page)' "$f" \
    && ok "$n asks Sections.headingOf for each page's heading" \
    || bad "$n does not read the declared heading — it lists Configs flat"
  grep -qE 'DrawerRow\(([a-z]+ \+ )?g\.uppercase\(\), DrawerRow\.Kind\.HEADING\)' "$f" \
    && ok "$n prints the group heading as a disabled (HEADING) row" \
    || bad "$n never prints the group heading (Launcher / Setup)"
  grep -qE 'DrawerRow\("[^"]*\$s", DrawerRow\.Kind\.HEADING\)' "$f" \
    && ok "$n prints the subgroup heading as a disabled (HEADING) row" \
    || bad "$n never prints the subgroup heading (Apps / Observability)"
done
grep -qF 'section.pages.none { it.group.isNotBlank() || it.subgroup.isNotBlank() } -> "" to ""' "$SECTIONS" \
  && ok "a section that declares no heading gets none in its menus (every section but Configs is unchanged)" \
  || bad "headingOf no longer returns blank for an undeclared section — every menu would grow a PAGES banner"
grep -qF 'page.isAction -> GROUP_ACTIONS to ""' "$SECTIONS" \
  && ok "menus put is_action pages under Actions, as the grid does" \
  || bad "headingOf lost the is_action → Actions rule — the three actions would list under Observability"

echo "== T11: THE RENDERED GRID — exact headings, sub-headings and tile order (#723) =="
# T1/T2 check the declaration piecewise. This replays what the phone grid DRAWS,
# end to end, using the same rules the Kotlin applies and that T6/T7/T10 pin:
#   Sections drops `hidden` pages; sectionGrid maps each page to a tile whose
#   group is Actions for is_action and else the declared group (fallback Pages),
#   and puts every non-Actions tile first, Actions after; TileGridFragment prints
#   a group header when the group changes (resetting the sub-header) and a
#   sub-header when the subgroup changes, both uppercased.
# The expected sequence below IS the owner's spec, written out once: the screen
# path, the titles and the icon order the architect checks on the phone. The
# Configs star's extra actions (starActionsOf) append after Animations from a
# different declaration and are deliberately outside this assertion.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
vis = [p for p in pages if not p.get('hidden')]
tiles = [(('Actions' if p.get('is_action') else (p.get('group') or 'Pages')),
          ('' if p.get('is_action') else p.get('subgroup', '')),
          p['label'], p.get('icon')) for p in vis]
tiles = [t for t in tiles if t[0] != 'Actions'] + [t for t in tiles if t[0] == 'Actions']
out, g0, s0 = [], None, None
for g, s, label, icon in tiles:
    if g and g != g0: out.append('H:' + g.upper()); g0, s0 = g, None
    if s and s != s0: out.append('S:' + s.upper()); s0 = s
    out.append('%s[%s]' % (label, icon))
want = ['H:SETUP',
        'S:CONNECT',      'Account[ic_person]',
        'S:APPS',         'Store[ic_storefront]',
        'S:LAUNCHER',     'Quick Actions[ic_home]', 'One-Hand[ic_onehand]', 'Notify[ic_rss]',
        'S:NETWORK',          'Firewall[ic_lock]', 'DNS[ic_world]', 'Webserver[ic_web]', 'Cloud Mesh[ic_wg]', 'Apps Access[ic_apps]', 'ADB Shell[ic_terminal]', 'WiFi[ic_wifi]',
        'H:FLEET',        'Bluetooth[ic_bluetooth]', 'C3 Fleet Control[ic_p_watchdog]', 'Peer Control[ic_hub]',
        'H:OBSERVABILITY', 'X-Ray[ic_monitor_heart]', 'LogCat[ic_subject]', 'Permissions[ic_key]', 'About[ic_info]',
        'H:ACTIONS',
        'Update All[ic_system_update]', 'KDE Connect[ic_devices]', 'Animations[ic_animation]']
if out == want: print('OK')
else:
    import difflib
    print('rendered != spec: ' + ' | '.join(l for l in difflib.unified_diff(want, out, lineterm='', n=0)
                                           if l[:1] in '+-' and l[:3] not in ('+++', '---')))
PY
)" "grid = SETUP ▸ CONNECT[Account] ▸ APPS[Store] ▸ LAUNCHER[Quick Actions,One-Hand,Notify] ▸ NETWORK[Firewall,DNS,Webserver,Cloud Mesh,Apps Access,ADB Shell,WiFi] FLEET[Bluetooth,C3 Fleet Control,Peer Control] OBSERVABILITY[X-Ray,LogCat,Permissions,About] ACTIONS[Update All,KDE Connect,Animations]"

echo "== T12: THE AI PAGE IS GONE, not hidden — no route, target or tile names it (#723) =="
# Deleting a declaration and leaving its door behind is the dead-tile failure:
# a Kotlin branch, a page:config/<id> target or a fragment for a page that no
# longer exists. The id list is READ from removed_pages, so a later removal is
# covered without editing this file.
check "$(python3 - "$BJ" "$KT" <<'PY'
import json, os, re, sys
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
gone = sorted(sec.get('removed_pages', {}))
if not gone: print('removed_pages is empty — this check has no subject'); sys.exit()
raw = open(sys.argv[1]).read()
hits = []
for g in gone:
    if re.search(r'page:config/%s\b' % re.escape(g), raw): hits.append('build.json targets page:config/%s' % g)
    if any(g in p.get('tabs', []) for p in sec['pages']): hits.append('a page still lists %s as a tab' % g)
for root, _, files in os.walk(sys.argv[2]):
    for f in files:
        if not f.endswith('.kt'): continue
        src = open(os.path.join(root, f)).read()
        src = re.sub(r'/\*.*?\*/', '', src, flags=re.S); src = re.sub(r'(?m)//.*$', '', src)
        for g in gone:
            if re.search(r'page:config/%s\b' % re.escape(g), src): hits.append('%s targets page:config/%s' % (f, g))
            if re.search(r'pageId == "%s"' % re.escape(g), src):   hits.append('%s still routes pageId == "%s"' % (f, g))
print('; '.join(hits) or 'OK')
PY
)" "no build.json target, tab list or Kotlin route names ai/websearch/localsearch/textenhance/library/tokens"
[ ! -d "$KT/ai" ] \
  && ok "the AI page's fragments (superapp/ai/) are deleted, not left compiled behind no route" \
  || bad "superapp/ai/ still exists — the AI page's fragments outlived the page"
check "$(python3 - "$BJ" <<'PY'
import json, sys
by_id = {p['id']: p for p in next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
                                  if s['id'] == 'config')['pages']}
k = by_id.get('kde')
print('OK' if k and k['label'] == 'Peer Control' and not k.get('hidden') and not k.get('action')
      else 'kde = %r' % (k,))
PY
)" "Peer Control is the kde page renamed — same id, so page:config/kde still opens KdeConnectFragment"
grep -qE 'sectionId == "config" && pageId == "kde" +-> .*KdeConnectFragment' "$PAGES" \
  && ok "SectionPages still routes config/kde to KdeConnectFragment" \
  || bad "config/kde no longer resolves to KdeConnectFragment — the Peer Control tile opens a placeholder"

echo "== T13: LOGCAT IS ITS OWN OBSERVABILITY TAB, immediately before About, and About no longer carries it =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ids = [p['id'] for p in next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
                             if s['id'] == 'config')['pages'] if p.get('group') == 'Observability']
sec = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
grp = [i for i in next(g for g in sec['groups'] if g['id'] == 'observability')['pages'] if i in ids]
print('OK' if ids == grp and ids[-1] == 'app-about' and ids.index('logcat') < ids.index('app-about') else 'Observability order = %r, group = %r' % (ids, grp))
PY
)" "Observability = build.json groups[observability] in order; Logcat before About, About last"
grep -qE 'sectionId == "config" && pageId == "logcat" +-> .*LogcatFragment' "$PAGES" \
  && ok "SectionPages routes config/logcat to LogcatFragment" \
  || bad "config/logcat does not resolve to LogcatFragment — the Logcat tab opens a placeholder"
grep -q 'AboutLogcatViewer' "$KT/devcontrol/LogcatFragment.kt" \
  && ok "LogcatFragment hosts the viewer" || bad "LogcatFragment does not host AboutLogcatViewer"
if grep -nE 'AboutLogcatViewer|macro\("[^"]*LOGCAT' "$KT/devcontrol/DevControlFragment.kt" >/dev/null; then
  bad "About (DevControlFragment) still names the Logcat viewer or a LOGCAT macro — it was meant to leave About completely"
else ok "About (DevControlFragment) carries no Logcat macro or viewer"; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
