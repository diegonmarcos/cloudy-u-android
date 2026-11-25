#!/usr/bin/env bash
# #1848 Notify = two rows (Cards | Boards), one Notify page, Actions in the bottom nav, Badges -> Cards.
#  N1  RULE 16 against test/notify-boards-baseline-1848.json: every page, stack, filter and field of the frozen communication
#      and boards sections is still declared; only the Persistent Badges label (-> Persistents, with a | before it) changed.
#      The Boards section is untouched (its pages and state are kept; Notify's Boards row mirrors them).
#  N2  the two rows: Cards = All | Persistents, Cloudy Apps, Phone Apps, Configs; Boards = Inboxes, Me, Tasks | Resources, X-Ray
#      Device; every visible page is in exactly one row; each Boards page mirrors the real Boards page (one implementation).
#  N3  ONE Notify page: Configs > Launcher > Notify redirects to Notify; every deep link that named it resolves (canonicalPage).
#  N4  the bottom nav: communication, actions, home, cloud, phone; Actions is a single-page section that routes to Quick Actions.
#  N5  Badges -> Cards in every visible string the Notify page and its settings draw (strings.xml in every locale, build.json).
#  N6  the strip draws one PageTabs per row (code wiring).
set -u
cd "$(dirname "$0")/.."
BJ=${BUILD_JSON:-build.json}
KT=app/src/main/java/com/diegonmarcos/superapp
BASE=${BASELINE:-test/notify-boards-baseline-1848.json}
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = OK ]; then ok "$2"; else bad "$2 — $1"; fi; }
has() { if grep -qF -- "$2" "$1"; then ok "$3"; else bad "$3"; fi; }

echo "== N1: rule 16 =="
check "$(python3 - "$BJ" "$BASE" <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); base = json.load(open(sys.argv[2]))
secs = {s['id']: s for s in b['ui']['sections']}
probs = []
ALLOWED = {('communication', 'notify-badges', 'label'), ('communication', 'notify-badges', 'separator_before')}
# communication: every frozen page and key
for sid in ('communication', 'boards'):
    old, new = base[sid], secs[sid]
    for k, v in old.items():
        if k == 'pages':
            np_ = {p['id']: p for p in new['pages']}
            for p in v:
                n = np_.get(p['id'])
                if n is None: probs.append('%s page %s gone' % (sid, p['id'])); continue
                for f, fv in p.items():
                    if n.get(f) != fv and (sid, p['id'], f) not in ALLOWED: probs.append('%s/%s.%s changed' % (sid, p['id'], f))
            continue
        if k.startswith('_doc_pages_1672') or k == 'state_pages': continue  # documentation of the old row
        # #1849 the bottom-nav glyph of Notify moved off the Inboxes tray to the catalog bell (the one named change)
        if (sid, k) == ('communication', 'icon') and v == 'ic_p_inbox' and new.get(k) == 'notifications': continue
        # #1902 the Services filter option is the SET of the two Services section prefixes ('+' City and '%' Leisure): id and default '+' read '+%' now (the one named change)
        if new.get(k) != v and not (k.startswith('stack_') and json.dumps(new.get(k)).replace('Persistents', 'Persistent badges') == json.dumps(v)) and json.dumps(new.get(k)).replace('"+%"', '"+"') != json.dumps(v):
            probs.append('%s.%s changed or lost' % (sid, k))
# the Boards section keeps its five pages in order
if [p['id'] for p in secs['boards']['pages']] != [p['id'] for p in base['boards']['pages']]: probs.append('boards pages changed')
cfg = {p['id']: p for p in next(s for s in b['ui']['sections'] if s['id'] == 'config')['pages']}['notify']
for f, fv in base['config_notify'].items():
    if cfg.get(f) != fv: probs.append('config notify.%s changed or lost' % f)
for e in base['swipe_walk']:
    n = [x for x in b['ui']['swipe_walk'] if x['id'] == e['id']]
    if not n: probs.append('swipe stop %s gone' % e['id'])
print('; '.join(probs) or 'OK')
PY
)" "frozen communication and boards sections still declared field for field (Persistents label, | before it, only)"

echo "== N2: the two rows =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
sec = next(s for s in b['ui']['sections'] if s['id'] == 'communication')
pages = {p['id']: p for p in sec['pages']}
rg = sec['row_groups']
probs = []
if [(g['id'], g['label']) for g in rg] != [('cards', 'Cards'), ('boards', 'Boards')]: probs.append('rows %r' % [(g['id'], g['label']) for g in rg])
lab = lambda g: [pages[i]['label'] for i in g['pages']]
if lab(rg[0]) != ['All', 'Persistents', 'Cloudy Apps', 'Phone Apps', 'Configs']: probs.append('Cards row %r' % lab(rg[0]))
if lab(rg[1]) != ['Inboxes', 'Me', 'Tasks', 'Resources', 'X-Ray Device']: probs.append('Boards row %r' % lab(rg[1]))
seps = [i for g in rg for i in g['pages'] if pages[i].get('separator_before')]
if seps != ['notify-badges', 'boards-resources']: probs.append('separators %r' % seps)
visible = [p['id'] for p in sec['pages'] if not p.get('hidden')]
named = [i for g in rg for i in g['pages']]
if sorted(visible) != sorted(named) or len(named) != len(set(named)): probs.append('visible pages and row pages differ: %r vs %r' % (visible, named))
want = {'boards-msgs': 'boards/msgs', 'boards-me': 'boards/my-pm', 'boards-tasks': 'boards/tasks', 'boards-resources': 'boards/resources', 'boards-xray': 'config/xray'}
for i, m in want.items():
    if pages[i].get('mirror_page') != m: probs.append('%s does not mirror %s' % (i, m))
    sid, pid = m.split('/')
    tgt = next(s for s in b['ui']['sections'] if s['id'] == sid)
    if pid not in [p['id'] for p in tgt['pages']]: probs.append('mirror target %s missing' % m)
print('; '.join(probs) or 'OK')
PY
)" "Cards = All | Persistents, Cloudy Apps, Phone Apps, Configs; Boards = Inboxes, Me, Tasks | Resources, X-Ray Device; Boards pages are mirrors"

echo "== N3: one Notify page =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
cfg = {p['id']: p for p in next(s for s in b['ui']['sections'] if s['id'] == 'config')['pages']}
n = cfg['notify']
probs = []
if n.get('redirect') != 'communication/my-rss': probs.append('config notify is not redirected: %r' % n.get('redirect'))
comm = next(s for s in b['ui']['sections'] if s['id'] == 'communication')
if 'my-rss' not in [p['id'] for p in comm['pages']]: probs.append('redirect target missing')
if not comm.get('tabs'): probs.append('Notify has no sub-page strip')
print('; '.join(probs) or 'OK')
PY
)" "Configs > Launcher > Notify redirects to Notify (All) with its full menu"
has $KT/launcher/Sections.kt 'to.size == 2 && to.none(String::isBlank)) return (byId(to[0])?.id ?: to[0]) to to[1]' "canonicalPage applies the redirect before any page is built"

echo "== N4: the bottom nav =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
b = json.load(open(sys.argv[1])); ui = b['ui']
secs = {s['id']: s for s in ui['sections']}
probs = []
if ui['bottom_nav'] != ['communication', 'actions', 'home', 'cloud', 'phone']: probs.append('bottom_nav %r' % ui['bottom_nav'])
a = secs.get('actions')
if not a or a.get('label') != 'Actions' or not a.get('single_page') or [p['id'] for p in a['pages']] != ['quick-actions']: probs.append('actions section')
if 'boards' not in secs: probs.append('the Boards section was removed')
print('; '.join(probs) or 'OK')
PY
)" "bottom nav = Notify, Actions, Home, Cloud, Phone; Actions is a single-page section; Boards stays declared"
has $KT/launcher/SectionPages.kt 'sectionId == "actions" && pageId == "quick-actions" -> SectionTabsFragment.forPage("config", "controls")' "Actions routes to the Quick Actions strip (one implementation)"

echo "== N5: Badges -> Cards =="
for f in app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml; do
  check "$(python3 - "$f" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
names = ('notify_badges_title', 'notify_turn_all', 'notify_settings_sub', 'push_launch_all', 'push_launched', 'push_section_badges_sub', 'push_section_custom_sub', 'push_none_badges', 'badge_markets_no_instruments')
bad = []
for n in names:
    m = re.search(r'<string name="%s">(.*?)</string>' % n, s, re.S)
    if m and re.search(r'badge', m.group(1).replace('badge=true', ''), re.I): bad.append(n)
print('OK' if not bad else 'still says badge: %r' % bad)
PY
)" "$f: the Notify strings say cards"
done
check "$(python3 - "$BJ" <<'PY'
import json, sys
s = open(sys.argv[1]).read(); b = json.loads(s)
comm = next(x for x in b['ui']['sections'] if x['id'] == 'communication')
probs = []
for p in comm['pages']:
    if 'adge' in p['label']: probs.append('page label %s' % p['label'])
for k, v in comm.items():
    if k.startswith('stack_'):
        for c in v:
            if 'adge' in c.get('title', ''): probs.append('%s title %s' % (k, c['title']))
if '"label": "Show this badge"' in s: probs.append('Show this badge label')
print('; '.join(probs) or 'OK')
PY
)" "no Notify page label, card title or switch label still says badge"
has $KT/launcher/AggregatorPanels.kt 'if (it.equals("Configs", true)) "Cards" else it' "the Configs-tab heading says Cards"

echo "== N6: two strips =="
has $KT/launcher/SectionTabsFragment.kt 'val rows = if (owner == null) Sections.byId(sectionId)?.rowGroups.orEmpty() else emptyList()' "the strip reads the section's row_groups"
has $KT/launcher/SectionTabsFragment.kt 'for ((i, g) in rows.withIndex())' "one PageTabs per row"
echo "passed=$PASS failed=$FAIL"; [ "$FAIL" = 0 ]
