#!/usr/bin/env bash
# Anchors are DECLARED, exactly like pages, so a broken one is caught here
# rather than by a finger on a tile that does nothing.
#
# A tile whose target reads `anchor:<id>` scrolls the stack it is already on
# to a view registered under `<id>`. Registration used to happen in Kotlin as
# panels rendered, with ids slugged out of display labels — so build.json did
# not know what anchors a page had, and renaming a label silently renamed an
# anchor. Now every anchor id is written down: a panel's own `anchor`, plus
# the `anchors: [{id, group, subgroup?}]` it declares for the headers it draws
# inside itself.
#
# What this asserts:
#   T1  every `anchor:` target resolves to an id declared on the SAME stack
#   T2  declared ids are unique within a stack
#   T3  every declared {group, subgroup} binding exists in cloud_services.json
#   T4  no anchor id is derived from a label anywhere in Kotlin
#   T5  the panels the C3 Index leads to are still declared (no regression)
#   T6  every CROSS-page `page:<section>/<page>#<anchor>` resolves too
#   T7  every `page:` target anywhere in build.json resolves to a real page
#   T8  every HIDDEN page has a referrer (a hidden page IS its referrers)
#   T9  no (kind, title) card appears twice in the same stack — catches a
#       copy-pasted panel (e.g. a duplicated "More" row) even though it
#       carries no `anchor` for T2 to dedupe against
#   W   declared-but-unreferenced ids are reported, not failed
#
# T8 is the one that catches the failure that prompted it. Making the C3 Index
# pure anchors left c3/{stack,vms,workflows,gha,logs,reports} declared, routable
# and reachable by nothing — a hidden page has no tab, so a tile pointing at it
# is the ONLY way in, and losing that tile deletes the page without deleting it.
#
# T6 covers the second form. A link that has to travel first is an ordinary
# `page:` target carrying a fragment — the web's own grammar — so it is checked
# the same way a dangling `page:` target is: the page must exist, and the
# anchor must be declared on THAT page's stack.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== stack anchors are declared in build.json =="

REPORT="$(python3 - "$APP" <<'PY'
import json, re, sys, os
app = sys.argv[1]
build = json.load(open(os.path.join(app, "build.json")))
dash  = json.load(open(os.path.join(app, "data", "cloud_services.json")))

groups = {g.get("id"): g for g in dash.get("groups", [])}
sublabels = {gid: {s.get("label") for s in g.get("subgroups", [])}
             for gid, g in groups.items()}

def tile_targets(node, out):
    """Every `target` string anywhere under a stack, tiles nest arbitrarily."""
    if isinstance(node, dict):
        t = node.get("target")
        if isinstance(t, str):
            out.append(t)
        for v in node.values():
            tile_targets(v, out)
    elif isinstance(node, list):
        for v in node:
            tile_targets(v, out)

lines = []
def emit(kind, msg): lines.append("%s\t%s" % (kind, msg))

# "section/page" -> ids declared on that page's stack, for the cross-page form.
declared_by_page = {}
# Every page id a section declares, so a `page:` half can be checked too.
pages_by_section = {s.get("id"): {p.get("id") for p in s.get("pages", [])}
                    for s in build.get("ui", {}).get("sections", [])}
cross = []   # (where, target, section, page, anchor)

for sec in build.get("ui", {}).get("sections", []):
    for key, stack in sec.items():
        if not key.startswith("stack_") or not isinstance(stack, list):
            continue
        where = "%s.%s" % (sec.get("id"), key)
        declared, dupes = [], []
        card_seen, card_dupes = [], []
        for panel in stack:
            if not isinstance(panel, dict):
                continue
            # T9 — a card with no `anchor` (tile_row "More", section_title
            # headings) is invisible to the anchor-uniqueness check above, so
            # a copy-pasted panel (the class of bug that ships a duplicated
            # "More" row silently) needs its own identity: kind+title, the
            # same pair a reader uses to tell two cards apart on screen.
            # Blank titles are skipped rather than flagged — several
            # placeholder/spacer kinds legitimately carry none.
            title = panel.get("title", "")
            if title:
                sig = (panel.get("kind", ""), title)
                (card_dupes if sig in card_seen else card_seen).append(sig)
            own = panel.get("anchor", "")
            if own:
                (dupes if own in declared else declared).append(own)
            for a in panel.get("anchors", []) or []:
                aid = a.get("id", "")
                if not aid:
                    emit("T2", "%s: an anchors[] entry has no id" % where)
                    continue
                (dupes if aid in declared else declared).append(aid)
                # T3 — the binding must name something the data really draws.
                gid = a.get("group", "")
                if gid not in groups:
                    emit("T3", "%s: anchor '%s' binds to group '%s', "
                               "absent from cloud_services.json" % (where, aid, gid))
                elif a.get("subgroup") and a["subgroup"] not in sublabels[gid]:
                    emit("T3", "%s: anchor '%s' binds to subgroup '%s' under "
                               "'%s', which has no such sub-header"
                               % (where, aid, a["subgroup"], gid))
                # A card only draws group headers when it renders >1 group.
                wanted = panel.get("groups") or ([panel["group"]] if panel.get("group") else [])
                if gid in groups and not a.get("subgroup") and len(wanted) < 2:
                    emit("T3", "%s: anchor '%s' targets the group header of "
                               "'%s', but the panel renders %d group(s) so no "
                               "group header is drawn" % (where, aid, gid, len(wanted)))
                if gid and gid not in wanted:
                    emit("T3", "%s: anchor '%s' binds to group '%s', which "
                               "this panel does not render" % (where, aid, gid))
        for d in dupes:
            emit("T2", "%s: anchor id '%s' declared twice" % (where, d))
        for k, t in card_dupes:
            emit("T9", "%s: card '%s' (kind=%s) appears twice in this stack"
                       % (where, t, k))

        targets = []
        tile_targets(stack, targets)
        refs = [t[len("anchor:"):] for t in targets if t.startswith("anchor:")]
        for r in refs:
            if r not in declared:
                emit("T1", "%s: tile targets 'anchor:%s', declared by no panel"
                           % (where, r))
        declared_by_page["%s/%s" % (sec.get("id"), key[len("stack_"):])] = declared
        for t in targets:
            if t.startswith("page:") and "#" in t:
                path, _, anchor = t[len("page:"):].partition("#")
                bits = path.split("/", 1)
                if len(bits) == 2:
                    cross.append((where, t, bits[0], bits[1], anchor))
                else:
                    cross.append((where, t, sec.get("id"), bits[0], anchor))
        for d in declared:
            if d not in refs:
                emit("W", "%s: anchor '%s' is declared but nothing points at it"
                          % (where, d))
        emit("INFO", "%s: %d declared, %d referenced" % (where, len(declared), len(refs)))

# T6 — cross-page `page:<section>/<page>#<anchor>`. Both halves must resolve:
# a dangling page is the failure that cost a dead tap, a dangling fragment is
# a tap that arrives on the right page and then does nothing.
for where, target, section, page, anchor in cross:
    key = "%s/%s" % (section, page)
    if section not in pages_by_section:
        emit("T6", "%s: '%s' names section '%s', which does not exist"
                   % (where, target, section))
    elif page not in pages_by_section[section]:
        emit("T6", "%s: '%s' names page '%s/%s', which that section does not declare"
                   % (where, target, section, page))
    elif key not in declared_by_page:
        emit("T6", "%s: '%s' points at page '%s', which has no stack to anchor into"
                   % (where, target, key))
    elif anchor not in declared_by_page[key]:
        emit("T6", "%s: '%s' asks for anchor '%s', not declared on '%s'"
                   % (where, target, anchor, key))
    else:
        emit("INFO", "%s: cross-page '%s' resolves" % (where, target))

# T7/T8 — page reachability, over the WHOLE file rather than the stacks alone:
# a tile that opens a page can live in any tile list, drawer or action row.
raw = open(os.path.join(app, "build.json")).read()
refs = set(re.findall(r'"page:([a-z0-9_-]+)/([a-z0-9_-]+)(?:#[a-z0-9/_-]+)?"', raw))
for section, page in sorted(refs):
    if section not in pages_by_section:
        emit("T7", "page:%s/%s names a section that does not exist" % (section, page))
    elif page not in pages_by_section[section]:
        emit("T7", "page:%s/%s names a page that section does not declare" % (section, page))
hidden = [(s.get("id"), p.get("id"))
          for s in build.get("ui", {}).get("sections", [])
          for p in s.get("pages", []) if p.get("hidden")]
# A TAB is the second way in, and it arrived after this check was written.
# "A hidden page has no tab" was true until pages could declare `tabs`
# (Configs ▸ Launcher, then ▸ Panel): a page named in another page's tab strip
# is hidden from the grid precisely BECAUSE the strip is how it is reached, so
# demanding a `page:` tile for it fails the pages that are working as designed
# — which is what it was doing for config/theme and config/onehand. Owning
# tabs counts as pointing at them.
tabbed = {(s.get("id"), tab)
          for s in build.get("ui", {}).get("sections", [])
          for p in s.get("pages", [])
          for tab in ((p.get("tabs") or []) + (p.get("reaches") or []))}   # #1719 `reaches`: a page that hosts others behind its own nav (Configs > Network) is their referrer
# #865 a THIRD way in: a `page:` route the app's own Kotlin opens - the update
# notification, Update All and the lib-row hop all land on config/store-cloud,
# which lost its tab when the Store tile started opening Cloud Store instead.
# Only string literals in main source count; a route that exists only in a
# comment reaches nothing.
code_refs = set()
src_root = os.path.join(app, "app", "src", "main", "java")
for dp, _, fs in os.walk(src_root):
    for f in fs:
        if not f.endswith(".kt"): continue
        for line in open(os.path.join(dp, f), encoding="utf-8", errors="replace"):
            code = line.split("//", 1)[0]
            code_refs |= set(re.findall(r'"page:([a-z0-9_-]+)/([a-z0-9_-]+)(?:#[a-z0-9/_-]+)?"', code))
reachable = refs | tabbed | code_refs
for section, page in sorted(hidden):
    if (section, page) not in reachable:
        emit("T8", "page %s/%s is hidden and nothing points at it — no tile "
                   "targets it and no page lists it as a tab, so it is "
                   "unreachable" % (section, page))
emit("INFO", "%d page: targets, %d tab references, %d code routes, %d hidden pages, all reachable"
             % (len(refs), len(tabbed), len(code_refs), len(hidden)))

print("\n".join(lines))
PY
)" || { echo "  FAIL: checker crashed"; exit 1; }

for t in T1 T2 T3 T6 T7 T8 T9; do
  hits="$(printf '%s\n' "$REPORT" | grep -c "^$t	" || true)"
  if [ "$hits" -eq 0 ]; then
    ok "$t: no violations"
  else
    printf '%s\n' "$REPORT" | grep "^$t	" | cut -f2- | sed 's/^/    /'
    bad "$t: $hits violation(s)"
  fi
done

printf '%s\n' "$REPORT" | grep '^INFO	' | cut -f2- | sed 's/^/  ..  /'
warns="$(printf '%s\n' "$REPORT" | grep '^W	' | cut -f2- || true)"
[ -n "$warns" ] && printf '%s\n' "$warns" | sed 's/^/  warn: /'

echo "== ids are never derived from a label =="
ANCH="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/StackAnchors.kt"
LDIR="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
# (#773: the Compose page registers its anchor targets in AggregatorPanels.kt's PageAnchors; StackAnchors stays for the
# View pages that still use it - DevControl, and the cross-page pending request.)
if grep -qE 'fun slug|registerChild' "$ANCH" "$LDIR"/Aggregator*.kt 2>/dev/null; then
  bad "T4: a label-slugging path is back — anchor ids must come from build.json"
else
  ok "T4: no slug()/registerChild — headers register only declared ids"
fi
if grep -qF 'fun target(id: String, coords: LayoutCoordinates) { if (id.isNotBlank() && declares(id)) targets[id] = coords }' "$LDIR/AggregatorPanels.kt" \
   && grep -qF 'val idx = owner[id] ?: return false' "$LDIR/AggregatorPanels.kt"; then
  ok "T4: the page registers a target only for an id its panels declared, and an undeclared id is inert"
else
  bad "T4: the Compose page registers undeclared ids, or an undeclared id is no longer inert"
fi

echo "== C3 stack anchors are still declared =="
python3 - "$APP" <<'PY' && ok "T5: every expected C3 anchor is declared" || bad "T5: an expected C3 anchor lost its declaration"
import json, sys, os
b = json.load(open(os.path.join(sys.argv[1], "build.json")))
want = {
    "stack_topology": ["pub-urls", "pvt-urls", "containers-infra", "containers-user",
                       "stack/providers", "stack/vms", "stack/dbs", "stack/apis"],
    # The Index tile_row is gone: the page IS five feed cards now (GHA,
    # Dagu, GH Repos, Gitea Repos, NTFY), each addressable by its own
    # anchor with nothing left to index into it. This just checks the
    # five anchors themselves are still declared somewhere on the stack.
    "stack_observability": ["gha", "dagu", "gh-repos", "gitea-repos", "ntfy"],
}
sec = next(s for s in b["ui"]["sections"] if s.get("id") == "c3")
missing = []
for key, ids in want.items():
    have = set()
    for p in sec.get(key, []):
        if p.get("anchor"):
            have.add(p["anchor"])
        have |= {a.get("id") for a in (p.get("anchors") or [])}
    missing += ["%s:%s" % (key, i) for i in ids if i not in have]
if missing:
    print("    undeclared: " + ", ".join(missing))
    sys.exit(1)
PY

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
