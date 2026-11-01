#!/usr/bin/env bash
# test-finterm-nav: #1399 the FinTerm navigation, statically (no build, no network, no device).
#   N1  one row: every bottom-nav section declares nav_mode horizontal_one_row, no groups, and the app's nav_ui mode draws the
#       second row (classic), so a section's sub-pages are ONE horizontally scrollable row of the fleet nav lib
#   N2  Home first: every bottom-nav section's first sub-page is `home` (label Home, icon home); the shell opens a section on it
#       (FinNavStack.initial() is the Homepage) and draws NavBarTopSecondRows
#   N3  the Homepage lists every sub-page: the pages after Home are exactly the section's ui.stacks sub-pages (a dashboard's slug,
#       or the entry's `page`), in order, labelled with the entry title, each with an icon of the libs:ui-icons catalogue and a
#       one-line description; SectionPage draws them as a grid of cards that open the sub-page
#   N4  FI Market Monitor is gone (no dashboard, stack entry, page, or feed names it) and its deep link redirects to the FI Homepage
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
ui = json.loads(read(os.path.join(app, "build.json")))["ui"]
dash = json.loads(read(os.path.join(app, "data/dashboards.json")))["dashboards"]
srcs = json.loads(read(os.path.join(app, "data/sources.json")))["sources"]
icons = set(re.findall(r'"([a-z0-9_]+)" to ', read(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"))))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/capitalfinterm")
shell, page, stack, pages_kt = (read(os.path.join(kt, f)) for f in ("ui/AppShell.kt", "ui/SectionPage.kt", "FinNavStack.kt", "Pages.kt"))
slug = lambda i: i.lower().replace("_", "-")
secs = {s["id"]: s for s in ui["sections"]}
# N1
if ui.get("nav_ui", {}).get("mode") != "classic": bad.append("N1 nav_ui.mode is %r: only classic draws the second row" % ui.get("nav_ui", {}).get("mode"))
for sid in ui["bottom_nav"]:
    s = secs[sid]
    if s.get("nav_mode") != "horizontal_one_row": bad.append("N1 section %s nav_mode is %r, not horizontal_one_row" % (sid, s.get("nav_mode")))
    if s.get("groups"): bad.append("N1 section %s declares groups (more than one row)" % sid)
# N2
for sid in ui["bottom_nav"]:
    p = secs[sid].get("pages") or [{}]
    if (p[0].get("id"), p[0].get("label"), p[0].get("icon")) != ("home", "Home", "home"): bad.append("N2 section %s does not open on Home: first page %s" % (sid, p[0]))
if not re.search(r"fun initial\(\): List<String> = LIST_ONLY", stack): bad.append("N2 FinNavStack.initial() does not open the Homepage")
if "NavBarTopSecondRows(" not in shell: bad.append("N2 AppShell does not draw the one-row sub-page nav")
# N3
for sid in ui["bottom_nav"]:
    want = []
    for e in ui["stacks"].get(sid, []):
        pid = e.get("page") or (slug(e["dashboard"]) if e.get("kind") == "dashboard" and e.get("dashboard") else "")
        if pid: want.append((pid, e.get("title"), bool(e.get("subtitle")) or e.get("kind") != "dashboard"))
    got = [(p["id"], p.get("label")) for p in secs[sid].get("pages", [])[1:]]
    if got != [(a, b) for a, b, _ in want]: bad.append("N3 section %s Homepage pages %s differ from its sub-pages %s" % (sid, got, [(a, b) for a, b, _ in want]))
    for a, _, has in want:
        if not has: bad.append("N3 sub-page %s/%s has no one-line description" % (sid, a))
    for p in secs[sid].get("pages", []):
        if p.get("icon") not in icons: bad.append("N3 page %s/%s icon %r is not in the libs:ui-icons catalogue" % (sid, p["id"], p.get("icon")))
if not re.search(r"HomeCardTile\(c, .*?\) \{ onOpen\(c\.pageId\) \}", page) or "chunked(2)" not in page: bad.append("N3 SectionPage draws no grid of cards opening the sub-page")
if "Pages.homeCards(" not in page: bad.append("N3 SectionPage does not build the Homepage from the declared pages")
# N4
if any(d["id"] == "market_monitor" for d in dash): bad.append("N4 dashboard market_monitor still declared")
if "market_monitor" in json.dumps(ui["stacks"]) or any(p["id"] == "market-monitor" for s in ui["sections"] for p in s.get("pages", [])): bad.append("N4 Market Monitor still in the nav")
if "Market Monitor" in json.dumps(srcs): bad.append("N4 a source still feeds Market Monitor")
if not any(r.get("from") == "market_monitor" and r.get("tab") == "fi" and r.get("to") == "home" for r in ui.get("redirects", [])): bad.append("N4 the market_monitor deep link does not redirect to the FI Homepage")
if "Pages.redirect(" not in shell: bad.append("N4 AppShell ignores ui.redirects")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] nav rules (#1399)"
out="$(check "$APP" "$ROOT")" && ok "N1-N4 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <python statements on the scratch copy, cwd = the copy> <needle>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" "$ROOT" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
JE="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=2)"
SUB="S=lambda f,a,b:(lambda t:open(f,'w').write(t.replace(a,b)) if a in t else (_ for _ in ()).throw(SystemExit('mutation target missing: '+a)))(open(f).read())"
KT="app/src/main/java/com/diegonmarcos/capitalfinterm"
mutant "the nav goes back to clean"         "$JE;d=L('build.json');d['ui']['nav_ui']['mode']='clean';D('build.json',d)" "N1 nav_ui.mode"
mutant "a section uses two rows"            "$JE;d=L('build.json');d['ui']['sections'][1]['nav_mode']='horizontal_two_rows';D('build.json',d)" "N1 section eq nav_mode"
mutant "a section loses its Home"           "$JE;d=L('build.json');d['ui']['sections'][2]['pages'].pop(0);D('build.json',d)" "N2 section ex does not open on Home"
mutant "a section opens on a dashboard"     "$SUB;S('$KT/FinNavStack.kt','fun initial(): List<String> = LIST_ONLY','fun initial(): List<String> = listOf(LIST, \"dash:fx\")')" "N2 FinNavStack.initial()"
mutant "the shell drops the one-row nav"    "$SUB;S('$KT/ui/AppShell.kt','NavBarTopSecondRows(','NavBarTopSecondRowz(')" "N2 AppShell does not draw"
mutant "the Homepage misses a sub-page"     "$JE;d=L('build.json');d['ui']['sections'][0]['pages'].pop();D('build.json',d)" "N3 section fi Homepage pages"
mutant "a sub-page has no description"      "$JE;d=L('build.json');d['ui']['stacks']['nw'][0]['subtitle']='';D('build.json',d)" "N3 sub-page nw/economic-calendar has no one-line description"
mutant "a page icon is not in the catalogue" "$JE;d=L('build.json');d['ui']['sections'][3]['pages'][1]['icon']='no_such_icon';D('build.json',d)" "N3 page nw/economic-calendar icon"
mutant "a card stops opening its page"      "$SUB;S('$KT/ui/SectionPage.kt','{ onOpen(c.pageId) }','{ }')" "N3 SectionPage draws no grid"
mutant "Market Monitor comes back"          "$JE;d=L('data/dashboards.json');d['dashboards'].append(dict(d['dashboards'][0],id='market_monitor'));D('data/dashboards.json',d)" "N4 dashboard market_monitor"
mutant "a source still feeds it"            "$JE;d=L('data/sources.json');d['sources'][1]['feeds'][0]['entries'].append('Market Monitor');D('data/sources.json',d)" "N4 a source still feeds"
mutant "the deep link is not redirected"    "$JE;d=L('build.json');d['ui']['redirects']=[];D('build.json',d)" "N4 the market_monitor deep link"
mutant "the shell ignores redirects"        "$SUB;S('$KT/ui/AppShell.kt','Pages.redirect(','Pages.redirekt(')" "N4 AppShell ignores"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
