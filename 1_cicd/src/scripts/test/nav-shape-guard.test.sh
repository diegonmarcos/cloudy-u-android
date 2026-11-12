#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ nav-shape-guard.test — prove the nav-shape guard FAILS on each rule   ║
# ║ it holds, and passes on the real tree                                 ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# #868. Two parts. (1) the real tree must pass: today every app is exempt with a
# reason. (2) a synthetic fleet — one fully migrated app, one exempt app, the
# real data file and the real libs:bottomnav sources — is required to pass, then
# broken one rule at a time; each break must be proven to have landed and the
# guard must go red NAMING the rule. A comment that merely mentions a forbidden
# widget must stay green (the guard reads code, not prose).
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-nav-shape-guard.py"
DATA=1_cicd/src/data/nav-shape.json
LIB=ab_cloud-libs-shared/libs/bottomnav
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"
FIX=ac_cloud-fix
NOTES_SHELL=ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/NotesShell.kt
KT=$FIX/app/src/main/kotlin/Main.kt

# ── (1) the real tree ───────────────────────────────────────────────────────
out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"
else fail "the real tree is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

# the real tree with an unmigrated app's exemption dropped: it is held to N1-N5 and fails
rm -rf "$T"; mkdir -p "$T"
(cd "$ROOT" && git ls-files -z | grep -zE '^a[acd]_[^/]+/(build\.json|build\.gradle(\.kts)?|app/build\.gradle(\.kts)?)$' \
    | xargs -0 cp --parents -t "$T")
mkdir -p "$T/1_cicd/src/data" "$T/$LIB"; cp "$ROOT/$DATA" "$T/$DATA"; cp -r "$ROOT/$LIB/src" "$T/$LIB/src"
UIBARS=ab_cloud-libs-shared/libs/ui-nav-bars
mkdir -p "$T/$UIBARS"; cp -r "$ROOT/$UIBARS/src" "$ROOT/$UIBARS/build.gradle" "$T/$UIBARS/"
# (whichever app is still exempt AND has no ui.bottom_nav yet: every migration batch removes one, so none is named here)
dropped="$(python3 - "$T" "$DATA" <<'PY'
import json, os, sys
t, data = sys.argv[1:3]
p = os.path.join(t, data); d = json.load(open(p))
for app in d["exempt"]:
    try:
        ui = json.load(open(os.path.join(t, app, "build.json"))).get("ui") or {}
    except (OSError, ValueError):
        continue
    if not ui.get("bottom_nav"):
        del d["exempt"][app]
        with open(p, "w") as fh:
            json.dump(d, fh)
        print(app)
        break
PY
)"
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ -n "$dropped" ] && [ "$rc" -eq 1 ] && grep -qF "N1 $dropped" <<<"$out"; then ok "dropping an unmigrated app's exemption goes red (N1 $dropped)"
else fail "dropping ${dropped:-any unmigrated app}'s exemption stayed green (rc=$rc)"; printf '%s\n' "$out" | tail -3; fi

# ── (2) the synthetic fleet ─────────────────────────────────────────────────
stage() {
    rm -rf "$T"; mkdir -p "$T/$FIX/app/src/main/res/layout" "$T/$FIX/app/src/main/kotlin" "$T/ac_cloud-old" "$T/1_cicd/src/data" "$T/$LIB"
    cp -r "$ROOT/$LIB/src" "$T/$LIB/src"
    mkdir -p "$T/$UIBARS"; cp -r "$ROOT/$UIBARS/src" "$ROOT/$UIBARS/build.gradle" "$T/$UIBARS/"
    mkdir -p "$T/$(dirname "$NOTES_SHELL")"; cp "$ROOT/$NOTES_SHELL" "$T/$NOTES_SHELL"
    python3 - "$ROOT/$DATA" "$T/$DATA" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d["exempt"] = {"ac_cloud-old": "Batch Z (synthetic): this fixture app has not migrated yet."}
d["variants"] = {"_doc": "synthetic: no app is a declared exception until a test makes it one"}
d["duplicate_page_ids"] = {"_doc": "synthetic: the fixture fleet has no app with duplicate page ids (the real ledger names real apps, N12 rejects names the tree lacks)"}
d["web"] = dict(d["web"], apps={})
d["nav_ui"]["app_defaults"] = {"_doc": "synthetic: no fixture app starts in a non-classic mode until a test says so"}
d["nav_ui"]["pending"] = {"_doc": "synthetic: the fixture exposes Configs > UI"}
json.dump(d, open(sys.argv[2], "w"))
PY
    cat > "$T/$FIX/build.json" <<'J'
{"ui": {"bottom_nav": ["a", "b"], "default_section": "a", "configs_ui": ["nav_ui"],
        "sections": [{"id": "a", "label": "A", "icon": "x", "pages": [{"id": "p1", "label": "P1"}, {"id": "p2", "label": "P2", "pages": [{"id": "q1", "label": "Q1"}]}]},
                     {"id": "b", "label": "B", "icon": "y"}]}}
J
    echo '{"ui": {}}' > "$T/ac_cloud-old/build.json"
    cat > "$T/$FIX/app/build.gradle" <<'G'
dependencies { implementation project(':libs:bottomnav') }
android { defaultConfig {
    buildConfigField "String", "UI_BOTTOM_NAV", "\"[]\""
    buildConfigField "String", "UI_SECTIONS_B64", "\"\""
} }
G
    cat > "$T/$KT" <<'K'
package fix
// A comment may talk about BottomNavigationView and TabLayout and TabRow( freely.
fun go() { PageTabs(pages, null, {}); Sections.byId("a"); open("page:a/q1"); FleetChrome.apply(this); NavUiConfigCard()
    BottomNavIsland(entries, null, onSelect = { height = 4 }) }
K
    echo '<LinearLayout/>' > "$T/$FIX/app/src/main/res/layout/main.xml"
    mkdir -p "$T/$FIX/app/src/main/res/values"
    echo '<resources><!-- bottom_nav_pill in prose is fine --><color name="app_bg">#000000</color></resources>' > "$T/$FIX/app/src/main/res/values/colors.xml"
}

# mutate <label> <file> <python-edit-of-s> <expected-substring>
mutate() {
    ${STAGE:-stage}
    local before after out rc
    before="$(cat "$T/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$T/$2"
    after="$(cat "$T/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
J() { printf "d=json.loads(s); %s; s=json.dumps(d)" "$1"; }

# green <label> <file> <python-edit-of-s> [<file2> <edit2>]: a change the guard must ACCEPT
green() {
    stage
    local f
    for f in "$2" "${4:-}"; do [ -n "$f" ] || continue
        local e="$3"; [ "$f" = "$2" ] || e="$5"
        python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $e; open(p,'w').write(s)" "$T/$f"
    done
    local out rc
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 0 ]; then ok "$1 passes"; else fail "$1 is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi
}

stage
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the unbroken synthetic fleet passes ($(tail -1 <<<"$out"))"
else fail "the unbroken synthetic fleet is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

mutate "the lib loses PageTabsView.kt's class"  "$LIB/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabsView.kt" \
    "s=s.replace('class PageTabsView','class Gone')" "N0 $LIB: PageTabsView.kt no longer declares"
mutate "the lib loses PageTabs.kt's function (#876: it lives in commonMain)" "$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt" \
    "s=s.replace('fun PageTabs(','fun Gone(')" "N0 $LIB: PageTabs.kt no longer declares"
# N1 limits: the owner lifted the cap of 5 (2026-10); the bar holds 2..8, and above 5 the island fits (widens, scales down), never scrolls.
BAR() { printf "ids=list('%s'); d['ui']['sections'] += [{'id': i, 'label': i.upper(), 'icon': 'x'} for i in ids[2:]]; d['ui']['bottom_nav'] = ids" "$1"; }
for ids in abcdef abcdefg abcdefgh; do
    green "${#ids} ids on the bar" "$FIX/build.json" "$(J "$(BAR "$ids")")"
done
mutate "nine ids on the bar"                     "$FIX/build.json" "$(J "$(BAR abcdefghi)")" "N1 $FIX: ui.bottom_nav has 9 ids, the island holds at most 8"
mutate "one id on the bar"                       "$FIX/build.json" "$(J "d['ui']['bottom_nav']=['a']")" "N1 $FIX: ui.bottom_nav has 1 ids, the island needs at least 2"
mutate "a bar id that is no section"             "$FIX/build.json" "$(J "d['ui']['bottom_nav']=['a','zzz']")" "N1 $FIX: ui.bottom_nav id 'zzz'"
mutate "a default outside the bar"               "$FIX/build.json" "$(J "d['ui']['default_section']='nope'")" "N1 $FIX: ui.default_section 'nope'"
mutate "no bottomnav dependency"                 "$FIX/app/build.gradle" "s=s.replace(\"implementation project(':libs:bottomnav')\",'')" "N2 $FIX: does not compile libs:bottomnav"
mutate "a local TabLayout"                       "$KT" "s+='\nval t = TabLayout(ctx)\n'" "N3 $FIX: $KT:6"
mutate "a Material TabRow"                       "$KT" "s+='\nfun f() { TabRow(0) {} }\n'" "(TabRow)"
mutate "a local @Composable BottomNav"           "$KT" "s+='\n@Composable fun BottomNav() {}\n'" "(local BottomNav)"
mutate "a NavigationBar"                         "$KT" "s+='\nfun f() { NavigationBar { } }\n'" "(NavigationBar)"
mutate "a BottomNavigationView in a layout"      "$FIX/app/src/main/res/layout/main.xml" "s='<LinearLayout><com.google.android.material.bottomnavigation.BottomNavigationView/></LinearLayout>'" "(BottomNavigationView)"
mutate "the sections blob is not baked"          "$FIX/app/build.gradle" "s=s.replace('UI_SECTIONS_B64','OTHER')" "N4 $FIX: build.gradle does not bake UI_SECTIONS_B64"
mutate "a section literal build.json lacks"      "$KT" "s=s.replace('byId(\"a\")','byId(\"zzz\")')" "names section 'zzz'"
mutate "a page deep link build.json lacks"       "$KT" "s=s.replace('page:a/q1','page:a/zzz')" "names page:a/zzz"
mutate "multi-page sections drawn without strips" "$KT" "s=s.replace('PageTabs(pages, null, {});','')" "N5 $FIX:"
# N7: the look is the lib's alone (the unbroken fixture above passes with a nested `height = 4` inside the onSelect lambda: only top-level arguments count)
mutate "a colour scheme handed to the island"    "$KT" "s+='\nfun f() { BottomNavIsland(entries, null, {}, colorScheme = scheme) }\n'" "N7 $FIX: $KT:6 passes \`colorScheme\` to BottomNavIsland"
mutate "an inset handed to the host"             "$KT" "s+='\nfun f() { BottomNavHost(entries, null, {}, insets = WindowInsets(0)) {} }\n'" "passes \`insets\` to BottomNavHost"
mutate "a height handed to the strip"            "$KT" "s+='\nfun f() { PageTabs(pages, null, {}, height = 9) }\n'" "passes \`height\` to PageTabs"
mutate "a scheme assigned on the island view"    "$KT" "s+='\nfun g(v: BottomNavIslandView) { v.colorScheme = x }\n'" "assigns \`colorScheme\` on an island/strip view"
mutate "an inset set inside a strip view's apply" "$KT" "s+='\nval t = PageTabsView(c).apply {\n    insets = z\n}\n'" "assigns \`insets\` on an island/strip view"
mutate "an island colour of the app's own"       "$FIX/app/src/main/res/values/colors.xml" "s=s.replace('app_bg','bottom_nav_pill')" "declares \`bottom_nav_pill\`"
mutate "an island dimen of the app's own"        "$FIX/app/src/main/res/values/colors.xml" "s=s.replace('<color name=\"app_bg\">#000000</color>','<dimen name=\"page_tabs_pill_radius\">4dp</dimen>')" "declares \`page_tabs_pill_radius\`"
mutate "the window chrome is not called"         "$KT" "s=s.replace('FleetChrome.apply(this)','')" "N7 $FIX: no source calls FleetChrome.apply("
# N9: a page's declared background picks the strip's lib-owned surface; code never names one
mutate "an unknown section background"           "$FIX/build.json" "$(J "d['ui']['sections'][0]['background']='neon'")" "N9 $FIX: ui.sections a declares background 'neon'"
mutate "an unknown page background"              "$FIX/build.json" "$(J "d['ui']['sections'][0]['pages'][1]['background']='grey'")" "N9 $FIX: ui.sections a/p2 declares background 'grey'"
mutate "a surface chosen in code"                "$KT" "s+='\nval x = TabSurface.Light\n'" "N9 $FIX: $KT:6 names TabSurface.Light"
mutate "a theme background no strip reads"       "$FIX/build.json" "$(J "d['ui']['sections'][0]['background']='theme'")" "N9 $FIX: declares a light/theme page background but no source calls stripSurface("
# N10: the second row's mode and page groups
mutate "an unknown nav_mode"                     "$FIX/build.json" "$(J "d['ui']['sections'][0]['nav_mode']='three_rows'")" "N10 $FIX: ui.sections a declares nav_mode 'three_rows'"
mutate "groups without a grouped mode"              "$FIX/build.json" "$(J "d['ui']['sections'][0]['groups']=[{'id':'g','label':'G','icon':'i','pages':['p1']}]")" "N10 $FIX: ui.sections a declares groups but nav_mode is not horizontal_two_rows or vertical_side_menu"
mutate "a page in two groups"                    "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='horizontal_two_rows', groups=[{'id':'g','label':'G','icon':'i','pages':['p1','p2']},{'id':'h','label':'H','icon':'i','pages':['p2']}])")" "N10 $FIX: ui.sections a page 'p2' is in two groups"
mutate "a page in no group"                      "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='horizontal_two_rows', groups=[{'id':'g','label':'G','icon':'i','pages':['p1']}])")" "N10 $FIX: ui.sections a horizontal_two_rows: page 'p2' is in no group"
# the three modes and the old contract names
green  "the old name many_rows still reads (alias of horizontal_two_rows)" "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='many_rows', groups=[{'id':'g','label':'G','icon':'i','pages':['p1','p2']}])")"
green  "the old name one_row still reads (alias of horizontal_one_row)" "$FIX/build.json" "$(J "d['ui']['sections'][0]['nav_mode']='one_row'")"
green  "horizontal_one_row, the default, declared"  "$FIX/build.json" "$(J "d['ui']['sections'][0]['nav_mode']='horizontal_one_row'")"
green  "vertical_side_menu with groups and an ungrouped page" "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='vertical_side_menu', groups=[{'id':'g','label':'G','icon':'i','pages':['p1']}])")"
green  "vertical_side_menu with dynamic children from a known source" "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='vertical_side_menu', dynamic_children='mail_folders_labels')")"
mutate "dynamic children from an unknown source"  "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='vertical_side_menu', dynamic_children='bogus')")" "N10 $FIX: ui.sections a declares dynamic_children 'bogus'"
mutate "dynamic children without the side menu"   "$FIX/build.json" "$(J "d['ui']['sections'][0].update(dynamic_children='mail_folders_labels')")" "N10 $FIX: ui.sections a declares dynamic_children but nav_mode is not vertical_side_menu"
mutate "a side-menu group naming a page the section lacks" "$FIX/build.json" "$(J "d['ui']['sections'][0].update(nav_mode='vertical_side_menu', groups=[{'id':'g','label':'G','icon':'i','pages':['zzz']}])")" "N10 $FIX: ui.sections a group 'g' names page 'zzz'"
# N5: the section-layout call (the three modes) draws the strips as well as a bare strip does
green  "multi-page sections drawn by NavSectionLayout instead of PageTabs" "$KT" "s=s.replace('PageTabs(pages, null, {});','NavSectionLayout(section, null, {}) { };')"
mutate "a colour handed to the section layout"    "$KT" "s+='\nfun f() { NavSectionLayout(section, null, {}, colors = c) { } }\n'" "passes \`colors\` to NavSectionLayout"
# N11: ONE shared nav-bars lib; the retired per-bar libs stay gone
mutate "the shared nav-bars lib loses the side menu layout"   "$UIBARS/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavSideMenu.kt" "s=s.replace('fun NavSectionLayout(','fun Gone(')" "N11 $UIBARS: NavSideMenu.kt no longer declares \`fun NavSectionLayout(\`"
mutate "the shared nav-bars lib loses the Configs card"       "$UIBARS/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavModeConfigCard.kt" "s=s.replace('fun NavModeConfigCard(','fun Gone(')" "N11 $UIBARS: NavModeConfigCard.kt no longer declares"
mutate "the scroll machine loses a state"                      "$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/BottomNavBar.kt" "s=s.replace('enum class ChromeScrollState','enum class Gone')" "N0 ab_cloud-libs-shared/libs/bottomnav: BottomNavBar.kt no longer declares \`enum class ChromeScrollState\`"
mutate "an app names a retired per-bar lib"                    "$FIX/build.json" "$(J "d['modules']={'libs:nav-bar-bottom':{'dir':'x'}}")" "N11 $FIX: build.json modules name the retired libs:nav-bar-bottom"
green  "a theme background read through stripSurface" "$FIX/build.json" "$(J "d['ui']['sections'][0]['background']='theme'")" \
    "$KT" "s=s.replace('PageTabs(pages, null, {});','PageTabs(pages, null, {}, surface = nav.stripSurface(\"a\", dark));')"
green  "an explicit dark background with no stripSurface" "$FIX/build.json" "$(J "d['ui']['sections'][0]['background']='dark'")"
mutate "an exemption without a reason"           "$DATA" "$(J "d['exempt']['ac_cloud-old']='tbd'")" "N6 ac_cloud-old: exemption needs a reason"
mutate "an exemption for an app that is gone"    "$DATA" "$(J "d['exempt']['ac_cloud-gone']='Batch Z (synthetic): there is no such app any more.'")" "N6 ac_cloud-gone: exempt"
mutate "a migrated app still exempt"             "$DATA" "$(J "d['exempt']['$FIX']='Batch Z (synthetic): migrated but nobody deleted this.'")" "N6 $FIX: passes N1-N5 but is still exempt"

# N8: the fleet island has ONE declared exception (nav-shape.json::variants); everyone else stays on it
VARIANT='{"style": "search-html", "call": "SearchHtmlIsland(", "reason": "Synthetic owner request: this fixture app keeps the lib variant for the test."}'
variant_fleet() {   # the fixture as a declared variant: data, ui.style and the call all agree
    stage
    python3 - "$T" "$DATA" "$FIX" "$KT" "$VARIANT" <<'PY'
import json, os, sys
t, data, fix, kt, variant = sys.argv[1:6]
p = os.path.join(t, data); d = json.load(open(p)); d["variants"][fix] = json.loads(variant); json.dump(d, open(p, "w"))
b = os.path.join(t, fix, "build.json"); j = json.load(open(b)); j["ui"]["style"] = "search-html"; json.dump(j, open(b, "w"))
k = os.path.join(t, kt); s = open(k).read()
open(k, "w").write(s.replace("BottomNavIsland(entries, null, onSelect = { height = 4 })", "SearchHtmlIsland(entries, null, onSelect = { height = 4 }, dark = true)"))
PY
}
n8() {   # n8 <label> <expected-substring or ""> <python edit of the staged tree: t, data, fix, kt in scope>
    variant_fleet
    python3 - "$T" "$DATA" "$FIX" "$KT" "$3" <<'PY'
import json, os, sys
t, data, fix, kt, edit = sys.argv[1:6]
exec(edit)
PY
    local out rc
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ -z "$2" ]; then
        if [ "$rc" -eq 0 ]; then ok "$1 passes"; else fail "$1 is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi
    elif [ "$rc" -eq 1 ] && grep -qF -- "$2" <<<"$out"; then ok "$1 goes red ($2)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
n8 "a declared variant that agrees everywhere" "" "pass"
mutate "an app declares the variant without being named" "$FIX/build.json" "$(J "d['ui']['style']='search-html'")" "N8 $FIX: ui.style is 'search-html' but $DATA only lets"
mutate "an unknown style"                        "$FIX/build.json" "$(J "d['ui']['style']='glass'")" "N8 $FIX: ui.style 'glass' is not one of"
mutate "another app reaches for the variant"     "$KT" "s+='\nfun f() { SearchHtmlIsland(entries, null, {}, dark = true) }\n'" "N8 $FIX: $KT:6 uses \`SearchHtmlIsland\`"
mutate "another app reads the variant's tokens"  "$KT" "s+='\nval k = SearchHtmlTokens.height\n'" "uses \`SearchHtmlTokens\`"
n8 "a named variant app that does not declare the style" "N8 $FIX: ui.style is 'fleet' but $DATA declares 'search-html' for it" \
    "b=os.path.join(t,fix,'build.json'); j=json.load(open(b)); del j['ui']['style']; json.dump(j,open(b,'w'))"
n8 "a named variant app whose style differs" "declares 'search-html' for it" \
    "b=os.path.join(t,fix,'build.json'); j=json.load(open(b)); j['ui']['style']='fleet'; json.dump(j,open(b,'w'))"
n8 "a variant that is no longer drawn (stale exception)" "a variant that is not drawn is a stale exception" \
    "k=os.path.join(t,kt); s=open(k).read(); open(k,'w').write(s.replace('SearchHtmlIsland(entries, null, onSelect = { height = 4 }, dark = true)','BottomNavIsland(entries, null, {})'))"
n8 "a variant that also draws the fleet island" "draws BottomNavIsland as well as its variant" \
    "k=os.path.join(t,kt); s=open(k).read(); open(k,'w').write(s+'\nfun h() { BottomNavIsland(entries, null, {}) }\n')"
n8 "a variant that restyles the bar through the call" "passes \`colorScheme\` to SearchHtmlIsland" \
    "k=os.path.join(t,kt); s=open(k).read(); open(k,'w').write(s+'\nfun h() { SearchHtmlIsland(entries, null, {}, dark = true, colorScheme = x) }\n')"
n8 "a variant without a reason" "N8 $FIX: the variant needs a reason" \
    "p=os.path.join(t,data); d=json.load(open(p)); d['variants'][fix]['reason']='ok'; json.dump(d,open(p,'w'))"
n8 "a variant that is also exempt" "a variant but also exempt" \
    "p=os.path.join(t,data); d=json.load(open(p)); d['exempt'][fix]='Batch Z (synthetic): exempt and a variant at once.'; json.dump(d,open(p,'w'))"
n8 "a variant for an app that is gone" "N8 ac_cloud-gone: a variant" \
    "p=os.path.join(t,data); d=json.load(open(p)); d['variants']['ac_cloud-gone']=d['variants'][fix]; json.dump(d,open(p,'w'))"
mutate "the lib loses the variant" "$LIB/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/BottomNavSearchHtml.kt" \
    "s=s.replace('fun SearchHtmlIsland(','fun Gone(')" "N0 $LIB: BottomNavSearchHtml.kt no longer declares"

# ── W1-W4 (#1158): a WebView app draws the same bars from a declaration the guard holds to the libs ──
web_stage() {   # the synthetic fleet plus the REAL Cloud Code declaration, its web sources and the lib files the parity reads
    stage
    python3 - "$ROOT" "$T" "$DATA" <<'PY'
import json, os, shutil, sys
root, t, data = sys.argv[1:4]
real = json.load(open(os.path.join(root, data)))
p = os.path.join(t, data); d = json.load(open(p)); d["web"] = real["web"]; d["nav_ui"]["pending"]["ac_cloud-code"] = real["nav_ui"]["pending"]["ac_cloud-code"]; json.dump(d, open(p, "w"))
w = real["web"]
files = [w["apps"]["ac_cloud-code"]["nav"], "ac_cloud-code/build.json", *w["sources"], w["theme_file"], w["bottom_colors_file"], *[e["file"] for e in w["parity"].values()]]
for f in files:
    dst = os.path.join(t, f); os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy(os.path.join(root, f), dst)
PY
}
STAGE=web_stage
web_stage
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ "$rc" -eq 0 ] && grep -q "held to" <<<"$out"; then ok "the real Cloud Code web declaration passes W1-W4 in the synthetic fleet ($(tail -1 <<<"$out"))"
else fail "the real Cloud Code web declaration is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi
NAVJ=ac_cloud-code/src/cloud/nav.json
LIBSRC=ab_cloud-libs-shared/libs
mutate "a web app's second-row tab height drifts from the lib" "$NAVJ" "$(J "d['bars']['second_row']['tab_height']=40")" "W3 ac_cloud-code: $NAVJ bars.second_row.tab_height is 40 but SecondRowSpec.TAB_HEIGHT is 36"
mutate "a web app's bottom cell drifts from the lib"           "$NAVJ" "$(J "d['bars']['bottom']['cell']=52")" "bars.bottom.cell is 52 but BottomSpec.CELL is 56"
mutate "a web app's first-row icon drifts from the lib"        "$NAVJ" "$(J "d['bars']['first_row']['icon']=20")" "bars.first_row.icon is 20 but TopFirstRowSpec.ICON is 24"
mutate "a web app's selected-tab colour drifts from the lib"   "$NAVJ" "$(J "d['bars']['theme']['dark']['tabSelected']='0xFF000000'")" "bars.theme.dark.tabSelected is '0xFF000000'"
mutate "a web app's page gradient drifts from the lib"         "$NAVJ" "$(J "d['bars']['theme']['light']['top']='0xFFFFFFFF'")" "bars.theme.light.top is '0xFFFFFFFF'"
mutate "a web app's bottom accent drifts from the lib"         "$NAVJ" "$(J "d['bars']['bottom_colors']['dark']['accent']='0xFF123456'")" "bars.bottom_colors.dark.accent is '0xFF123456'"
mutate "the lib changes the second row; the web app did not follow" "$LIBSRC/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt" \
    "s=s.replace('val TAB_HEIGHT: Dp = 36.dp','val TAB_HEIGHT: Dp = 30.dp')" "SecondRowSpec.TAB_HEIGHT is 30"
mutate "the lib changes the bar's gutter (through SearchHtmlTokens); the web app did not follow" "$LIB/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/BottomNavSearchHtml.kt" \
    "s=s.replace('val sideGutter: Dp = 16.dp','val sideGutter: Dp = 12.dp')" "BottomSpec.SIDE_GUTTER is 12"
mutate "the lib loses a spec object the web app is held to"     "$LIBSRC/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbarbottom/NavBarBottom.kt" \
    "s=s.replace('object BottomSpec','object Gone')" "no longer declares \`object BottomSpec\`"
mutate "a web app's tab list differs from build.json ui"        "$NAVJ" "$(J "d['tabs'].pop()")" "W2 ac_cloud-code: $NAVJ tabs"
mutate "a web app's default differs from build.json ui"         "$NAVJ" "$(J "d['default_tab']='backlog'")" "W2 ac_cloud-code: $NAVJ default_tab 'backlog'"
mutate "a web sub-page has no icon"                             "$NAVJ" "$(J "del d['tabs'][0]['pages'][0]['icon']")" "W2 ac_cloud-code: sub-page backlog/sts declares no icon"
mutate "a web sub-page icon that differs from build.json ui"      "$NAVJ" "$(J "d['tabs'][0]['pages'][0]['icon']='folder_open'")" "W2 ac_cloud-code: sub-page backlog/sts icon 'folder_open' differs from build.json ui"
mutate "a web tab icon that differs from build.json ui"           "$NAVJ" "$(J "d['tabs'][0]['icon']='home'")" "W2 ac_cloud-code: tab backlog icon 'home' differs"
mutate "a web sub-page that build.json does not declare"        "$NAVJ" "$(J "d['tabs'][0]['pages'].pop()")" "W2 ac_cloud-code: tab backlog sub-pages"
mutate "a web app's bar ids are too few (N1)"                   "ac_cloud-code/build.json" "$(J "d['ui']['bottom_nav']=['ai']")" "N1 ac_cloud-code: ui.bottom_nav has 1 ids"
mutate "the second row is no longer drawn"                      "ac_cloud-code/src/cloud/bars-dom.js" "s=s.replace('id: \"cloud-top2\"','id: \"other\"')" "W4 ac_cloud-code: no source of ac_cloud-code/src/cloud/bars-dom.js"
mutate "the hamburger gets an edge-swipe"                       "ac_cloud-code/src/cloud/bars-dom.js" "s+='\ndocument.addEventListener(\"touchstart\", () => {});\n'" "the hamburger opens by tap only"
mutate "a web app without a reason"                             "$DATA" "$(J "d['web']['apps']['ac_cloud-code']['reason']='tbd'")" "N6 ac_cloud-code: a web app needs a reason"
mutate "a web app that is also exempt"                          "$DATA" "$(J "d['exempt']['ac_cloud-code']='Batch Z (synthetic): exempt and a web app at once.'")" "N6 ac_cloud-code: a web app but also exempt"
unset STAGE

echo

# N13: the nav UI setting (Classic / Clean, scroll-hide) is declared once, held equal to the lib, and every held app exposes it
NAVUI="$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/NavUi.kt"
TOKENS="$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/NavTokens.kt"
CARD="$UIBARS/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavUiConfigCard.kt"
mutate "an app that does not list nav_ui in configs_ui"       "$FIX/build.json" "$(J "del d['ui']['configs_ui']")" "N13 $FIX: build.json ui.configs_ui must list 'nav_ui'"
mutate "an app that lists another id only"                    "$FIX/build.json" "$(J "d['ui']['configs_ui']=['other']")" "N13 $FIX: build.json ui.configs_ui must list 'nav_ui'"
mutate "an app that draws no Configs > UI card"               "$KT" "s=s.replace('NavUiConfigCard()','')" "N13 $FIX: no source draws the Configs > UI card"
mutate "a scaffold with the card switched off and no own card" "$KT" "s=s.replace('NavUiConfigCard()','NavBarTopFirstRowScaffold(configsUi = false) { }')" "N13 $FIX: no source draws the Configs > UI card"
green  "the lib's first-row scaffold is the card's host (its Configs > UI entry)" "$KT" "s=s.replace('NavUiConfigCard()','NavBarTopFirstRowScaffold(title = t, sections = nav.sections) { }')"
# N14 (#1483): the title dropdown's section list holds every declared section (bar and off-bar)
mutate "a scaffold that passes no section list"  "$KT" "s+='\nfun h() { NavBarTopFirstRowScaffold(title = t, hierarchy = h) { NavUiConfigCard() } }\n'" "N14 $FIX: $KT:"
mutate "a scaffold that passes only the bar's sections" "$KT" "s+='\nfun h() { NavBarTopFirstRowScaffold(title = t, sections = nav.bottomSections()) { NavUiConfigCard() } }\n'" "N14 $FIX: $KT:"
green  "a scaffold that passes every declared section" "$KT" "s+='\nfun h() { NavBarTopFirstRowScaffold(title = t, sections = nav.sections) { NavUiConfigCard() } }\n'"
mutate "a nav_ui mode that is no mode"                        "$FIX/build.json" "$(J "d['ui']['nav_ui']={'mode':'neon'}")" "N13 $FIX: ui.nav_ui must be an object with mode one of"
mutate "a scroll_hide that is no boolean"                     "$FIX/build.json" "$(J "d['ui']['nav_ui']={'mode':'clean','scroll_hide':'yes'}")" "N13 $FIX: ui.nav_ui must be an object with mode one of"
mutate "a Clean default the ledger does not know"             "$FIX/build.json" "$(J "d['ui']['nav_ui']={'mode':'clean'}")" "N13 $FIX: ui.nav_ui.mode is 'clean' but"
green  "a Clean default the ledger names"                     "$FIX/build.json" "$(J "d['ui']['nav_ui']={'mode':'clean','scroll_hide':True}")" \
    "$DATA" "$(J "d['nav_ui']['app_defaults']['ac_cloud-fix']='clean'")"
mutate "a Classic default but the ledger says Clean"          "$DATA" "$(J "d['nav_ui']['app_defaults']['ac_cloud-fix']='clean'")" "N13 $FIX: ui.nav_ui.mode is 'classic' but"
mutate "a pending entry for an app that exposes the card"     "$DATA" "$(J "d['nav_ui']['pending']['ac_cloud-fix']='the fixture says it does not expose the card yet, long enough'")" "N13 $FIX: exposes Configs > UI now"
mutate "the lib's mode names drift from the ledger"           "$NAVUI" "s=s.replace('Clean(\"clean\")','Clean(\"cleaner\")')" "NavUiMode declares ['classic', 'cleaner']"
mutate "the Clean drawer loses its icons row"                 "$NAVUI" "s=s.replace('listOf(DrawerRow.PageDropdown, DrawerRow.Actions, DrawerRow.SubPageDropdown)','listOf(DrawerRow.PageDropdown, DrawerRow.SubPageDropdown)')" "CLEAN_DRAWER_ROWS is"
mutate "#1369 the Clean drawer loses the app's actions row"   "$NAVUI" "s=s.replace('DrawerRow.Actions, DrawerRow.AppActions, DrawerRow.SubPageDropdown','DrawerRow.Actions, DrawerRow.SubPageDropdown')" "CLEAN_DRAWER_ROWS_WITH_ACTIONS is"
mutate "the Clean drawer's icons drift"                       "$NAVUI" "s=s.replace('NavQuickAction(public val declared: String) {\n    Ai(\"ai\"),','NavQuickAction(public val declared: String) {\n    Ai(\"assistant\"),')" "NavQuickAction declares"
mutate "Clean keeps the second row"                           "$NAVUI" "s=s.replace('secondRow = !clean','secondRow = true')" "plan(): Clean must have the hamburger, no second row and no bottom labels"
mutate "Clean loses the hamburger"                            "$NAVUI" "s=s.replace('hamburger = clean','hamburger = false')" "plan(): Clean must have the hamburger"
mutate "Clean keeps the bottom labels"                        "$NAVUI" "s=s.replace('bottomLabels = !clean','bottomLabels = true')" "plan(): Clean must have the hamburger"
mutate "scroll-hide loses its policy"                         "$NAVUI" "s=s.replace('ChromeScrollPolicy.Hide','ChromeScrollPolicy.Gone')" "no longer declares the scroll-hide policy"
mutate "the first row gets a grey fill"                       "$TOKENS" "s=s.replace('val topRowFill: Color = Color(0x00000000)','val topRowFill: Color = Color(0xFF303030)')" "topRowFill must be a fully transparent colour"
mutate "the Clean bottom nav loses its size token"            "$TOKENS" "s=s.replace('val cleanBarHeight: Dp = 44.dp,','')" "no longer declares the Clean size token cleanBarHeight"
mutate "the shared Configs card is gone"                      "$CARD" "s=s.replace('fun NavUiConfigCard(','fun Gone(')" "no longer declares \`fun NavUiConfigCard(\`"
mutate "the first row loses its Configs > General page (the UI card's home)" "$UIBARS/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavBarTopFirstRow.kt" "s=s.replace('FleetConfigsScreen(p, dark','NavGone(p, dark')" "no longer carries \`FleetConfigsScreen(p, dark\`"
mutate "an exempt-style pending entry without a reason"       "$DATA" "$(J "d['nav_ui']['pending']['ac_cloud-old']='tbd'")" "N13 ac_cloud-old: in"

# N13 must_hold: every behaviour the tests above describe is still in the lib's source (a mutation of each is red)
mapfile -t HOLDS < <(python3 - "$ROOT/$DATA" <<'PY'
import json, sys
for h in json.load(open(sys.argv[1]))["nav_ui"]["must_hold"]:
    print(h["file"] + "\t" + repr(h["needle"]) + "\t" + h["needle"])
PY
)
[ "${#HOLDS[@]}" -ge 10 ] || fail "nav_ui.must_hold lists only ${#HOLDS[@]} entries"
for line in "${HOLDS[@]}"; do
    IFS=$'\t' read -r hf hneedle hraw <<<"$line"
    mutate "the source loses \`$hraw\`" "$hf" "s=s.replace($hneedle,'XXGONE')" "no longer carries \`$hraw\`"
done

[ "$FAILURES" -eq 0 ] && echo "nav-shape-guard.test: OK" || { echo "nav-shape-guard.test: $FAILURES FAILED"; exit 1; }
