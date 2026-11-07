#!/usr/bin/env bash
# #343 — Constellation tabs are DATA: libs / libs-t-ml / libs-l-ml beside apps.
#
#   T1  every fleet entry sits in a declared group, in the declared order
#   T2  the real on-device ML modules moved OUT of plain libs
#   T3  reference rows live outside `apps`, installable:false, with no package
#   T4  the updater cannot reach a reference row
#   T5  StoreCloudFragment names no group: no id or label literal, no kind
#       partition, no hardcoded tab list
#   T6  #383 Lite-ML is declared before Tiny-ML, and the keys are untouched
#   T7  #405 an ML lib names its own section (ml-{t|l}-{domain}-{name}) and
#       nothing else sits in one - asserted in BOTH directions
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
FLEET="$APP/data/constellation-fleet.json"
BUILD="$APP/build.json"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
STORE_BJ="$ROOT/ac_cloud-store/build.json"
PAGE="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
ENGINE="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
# Every input must exist up front, so no assertion below can answer from a
# missing file.
for f in "$FLEET" "$BUILD" "$PAGE" "$ENGINE"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== T1: every entry is in a declared group, in declared order =="
jq -e '[.groups[].id] as $declared | all((.apps[], .catalogue[]); .group as $g | $declared | index($g) != null)' "$FLEET" >/dev/null \
  && ok "every apps + catalogue entry names a declared group" \
  || bad "an entry names a group constellation.groups does not declare"
jq -n -e --slurpfile fleet "$FLEET" --slurpfile build "$BUILD" \
  '($fleet[0].groups | map(.id)) == ($build[0].constellation.groups | map(.id))' >/dev/null \
  && ok "fleet groups keep build.json's declared order" \
  || bad "fleet groups differ from build.json::constellation.groups — rerun data/regen.sh"
# members is emitted beside the per-entry stamp; the two must be one fact.
jq -e '. as $root | all($root.groups[]; .id as $id
         | (.members | sort) == ([($root.apps[], $root.catalogue[]) | select(.group == $id) | .id] | sort))' "$FLEET" >/dev/null \
  && ok "each group's members match the per-entry group stamps" \
  || bad "a group's members disagree with its entries' group field"
for g in libs libs-t-ml libs-l-ml; do
  jq -e --arg g "$g" '(.groups | map(.id) | index($g)) != null and any(.groups[]; .id == $g and (.members | length) > 0)' "$FLEET" >/dev/null \
    && ok "group $g is declared and has rows" \
    || bad "group $g is missing or empty"
done

echo "== T2: real ML modules moved out of plain libs =="
# Derived from the modules themselves: a library whose build.gradle pulls in an
# on-device ML runtime is an ML module, whatever group data says. The floor of 2
# (ml-l-text-mlkit, ml-l-voice-vosk today) stops this passing on an empty match.
ML_MODULES="$(grep -lE "^[[:space:]]*implementation[[:space:]]+['\"](com\.google\.mlkit|com\.alphacephei|org\.tensorflow|com\.microsoft\.onnxruntime|com\.google\.mediapipe|com\.google\.ai\.edge)" "$LIBS"/*/build.gradle | xargs -n1 dirname | xargs -n1 basename)"
ML_COUNT="$(printf '%s\n' "$ML_MODULES" | grep -c .)"
[ "$ML_COUNT" -ge 2 ] \
  && ok "found $ML_COUNT library module(s) with an ML runtime dependency" \
  || bad "expected at least 2 ML runtime modules under libs/, found $ML_COUNT"
for mod in $ML_MODULES; do
  # #870: only engines ship a Cloud-Lib row; a static ML module (ml-l-text-mlkit, ml-l-voice-vosk)
  # ships inside the keyboard companion and has no row to file.
  jq -e --arg id "lib-$mod" 'any(.apps[]; .id == $id)' "$FLEET" >/dev/null || { ok "lib-$mod ships no Cloud-Lib row (static, #870)"; continue; }
  jq -e --arg id "lib-$mod" 'any(.apps[]; .id == $id and (.group == "libs-t-ml" or .group == "libs-l-ml"))' "$FLEET" >/dev/null \
    && ok "lib-$mod is in an ML group" \
    || bad "lib-$mod carries an ML runtime but is not in libs-t-ml or libs-l-ml"
done
jq -e 'all(.apps[] | select(.id == "lib-ml-l-text-mlkit" or .id == "lib-ml-l-voice-vosk"); .package != "" and .asset != "") and ([.apps[] | select(.id == "lib-ml-l-text-mlkit" or .id == "lib-ml-l-voice-vosk")] | length <= 2)' "$FLEET" >/dev/null \
  && ok "moved modules keep their real installable entries" \
  || bad "a moved ML module lost its package or asset"

echo "== T3: reference rows are outside apps and not installable =="
jq -n -e --slurpfile fleet "$FLEET" --slurpfile build "$BUILD" \
  '($fleet[0].catalogue | length) > 0 and ($fleet[0].catalogue | length) == ($build[0].constellation.catalogue | length)' >/dev/null \
  && ok "every build.json catalogue row is emitted" \
  || bad "catalogue rows missing from the fleet — rerun data/regen.sh"
jq -e 'all(.catalogue[]; .installable == false and (has("package") | not))' "$FLEET" >/dev/null \
  && ok "every catalogue row is installable:false with no package" \
  || bad "a catalogue row claims to be installable or carries a package"
jq -e '[.catalogue[].id] as $references | all(.apps[]; .id as $id | $references | index($id) | not)' "$FLEET" >/dev/null \
  && ok "no reference row is inside apps" \
  || bad "a reference row leaked into apps, where Fleet.parse hands it to the updater"
jq -e 'all(.apps[]; (.package // "") != "")' "$FLEET" >/dev/null \
  && ok "every apps entry has a real package" \
  || bad "an apps entry has no package"

echo "== T4: the updater cannot reach a reference row =="
grep -qF 'optJSONArray("apps")' "$ENGINE" \
  && ok "Fleet.parse reads the apps array" \
  || bad "Fleet.parse no longer reads apps — re-check where reference rows can reach"
grep -qF '"catalogue"' "$ENGINE" \
  && bad "Fleet.kt reads the catalogue, so reference rows can reach the updater" \
  || ok "Fleet.kt never reads the catalogue"
grep -qF 'if (app.blocked) return@filter false' "$ENGINE" \
  && ok "installAllLocked drops blocked entries before checking status" \
  || bad "installAllLocked no longer skips blocked entries"
grep -qF 'blocked = !entry.optBoolean("installable", false)' "$PAGE" \
  && ok "the page builds reference rows blocked unless the data says installable" \
  || bad "reference rows are not built blocked"
grep -qF 'updateAll(ctx, "the whole fleet", fleet)' "$PAGE" \
  && ok "Update all runs over Fleet.parse's list, which holds no reference row" \
  || bad "Update all is no longer built from the parsed fleet"

echo "== T5: StoreCloudFragment names no group =="
STRIP="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreStrip.kt"
[ -f "$STRIP" ] || { echo "ERROR: missing $STRIP" >&2; exit 2; }
for literal in $(jq -r '.groups[] | .id, .label' "$FLEET"); do
  grep -nF "\"$literal\"" "$PAGE" \
    && bad "StoreCloudFragment hardcodes group literal \"$literal\"" \
    || ok "no \"$literal\" literal"
done
# The strip's order is ONE pure declaration (StoreStrip): the only group it names is Fleet Data's anchor, and that group is a declared one.
strip_groups="$(grep -oE '"libs-[a-z-]+"|"apps"' "$STRIP" | tr -d '"' | sort -u)"
[ "$strip_groups" = "libs-t-ml" ] && jq -e '.groups[] | select(.id == "libs-t-ml")' "$FLEET" >/dev/null \
  && ok "StoreStrip names exactly one group (Fleet Data's Tiny-ML anchor) and the fleet declares it" \
  || bad "StoreStrip names group literals beyond its declared anchor, or the anchor is not a declared group: $strip_groups"
grep -nF 'listOf("Apps"' "$PAGE" && bad "hardcoded tab list is back" || ok "no hardcoded tab list"
grep -nF 'filter { it.kind' "$PAGE" && bad "a kind partition is back" || ok "no kind partition"
grep -qF 'fleetObjects("groups")' "$PAGE" \
  && ok "tabs are built from the fleet's declared groups" \
  || bad "tabs are not built from the declared groups"

echo "== T6: #383 the tab row the owner asked for =="
# T1 only proves the two files AGREE. Reverting the swap in build.json and
# re-running regen.sh leaves them agreeing perfectly on the wrong order, so T1
# stays green through exactly the regression #383 fixed. This pins the fact
# itself.
#
# RELATIVE, not a full expected list: "Lite-ML sits before Tiny-ML" is what the
# owner asked for, and it stays true when a sixth group is added next to them.
# A pinned `apps libs libs-l-ml libs-t-ml` would fail on that unrelated addition
# and teach the next agent to edit the expectation instead of reading it.
lite_before_tiny() { # lite_before_tiny <file> <jq path to the groups array>
  jq -e "$2"' | map(.id)
     | (index("libs-l-ml")) as $lite | (index("libs-t-ml")) as $tiny
     | $lite != null and $tiny != null and $lite < $tiny' "$1" >/dev/null
}
lite_before_tiny "$BUILD" '.constellation.groups' \
  && ok "build.json declares Lite-ML before Tiny-ML" \
  || bad "build.json has Tiny-ML in front of Lite-ML again (#383 reverted at the declaration)"
lite_before_tiny "$FLEET" '.groups' \
  && ok "the generated fleet renders Lite-ML before Tiny-ML" \
  || bad "the fleet has Tiny-ML in front of Lite-ML again (#383 reverted, or regen.sh not re-run)"
# The ids are IDENTITY (#343) and the labels are the only thing #383 moved.
# If a later edit ever "tidies" libs-l-ml into libs-lite-ml, every stamped
# entry, every members list and both auto-update workers follow a renamed key
# for a cosmetic reason — so the keys are asserted by name, here, on purpose.
jq -e 'any(.groups[]; .id == "libs-t-ml" and .label == "Tiny-ML")
   and any(.groups[]; .id == "libs-l-ml" and .label == "Lite-ML")' "$FLEET" >/dev/null \
  && ok "the ML group keys are untouched and carry the Tiny-ML / Lite-ML labels" \
  || bad "an ML group key or label changed — keys are identity, only labels may move"
# A tab named Lite-ML whose own caption opens "Lightweight ML" is the #343
# rename half-done: the blurb IS the heading drawn inside the tab.
jq -e 'all(.groups[]; (.blurb | test("Lightweight"; "i")) | not)' "$FLEET" >/dev/null \
  && ok "no tab caption still says the dead name Lightweight" \
  || bad "a tab caption still says Lightweight while its tab says Lite-ML"

echo "== T7: #405 an ML lib names its own section, and nothing else sits in one =="
# The scheme is ml-{t|l}-{domain}-{name}, worn behind the store's lib- row
# prefix. THAT NAME IS THE WHOLE CLASSIFICATION: regen.sh derives the tab from
# its weight-class segment and StoreCloudFragment derives the application
# heading from its domain segment, so there is no second list to disagree with
# it - and a name that does not parse is a row in no tab and under no heading.
#
# Asserted in BOTH directions on purpose. "every ML lib is in an ML tab" alone
# is satisfied by naming nothing ML; "every row in an ML tab is on-scheme" alone
# is satisfied by an empty tab. Neither is worth anything without the other, and
# (f) closes the last hole: a lib that wears the ML name without carrying an ML
# runtime.
SCHEME='^lib-ml-[tl]-[a-z0-9]+-[a-z0-9-]+$'

# (a) A module that CARRIES an ML runtime must be NAMED for it. $ML_MODULES is
#     derived from build.gradle above, so renaming a module without renaming
#     what it is fails right here.
for mod in $ML_MODULES; do
  printf '%s\n' "lib-$mod" | command grep -qE "$SCHEME" \
    && ok "module $mod carries an ML runtime and is named on-scheme" \
    || bad "module $mod carries an ML runtime but is not named ml-{t|l}-{domain}-{name}"
done

# (b) An ML tab may hold nothing off-scheme.
off_scheme="$(jq -r --arg re "$SCHEME" '[.apps[], .catalogue[]] | .[]
      | select(.group == "libs-t-ml" or .group == "libs-l-ml")
      | select(.id | test($re) | not) | .id' "$FLEET")"
[ -z "$off_scheme" ] \
  && ok "every row in an ML tab is named on-scheme" \
  || bad "off-scheme row(s) sitting in an ML tab: $(printf '%s' "$off_scheme" | tr '\n' ' ')"

# (c) An on-scheme row must sit in the tab its OWN NAME declares. This is the
#     direction that catches an ML lib parked outside its ML section, including
#     by a reintroduced hand-written override.
misfiled="$(jq -r --arg re "$SCHEME" '[.apps[], .catalogue[]] | .[]
      | select(.id | test($re))
      | . as $row
      | ($row.id | capture("^lib-ml-(?<weight>[tl])-") | "libs-\(.weight)-ml") as $named
      | select($row.group != $named)
      | "\($row.id) is drawn in \($row.group) but its name says \($named)"' "$FLEET")"
[ -z "$misfiled" ] \
  && ok "every ML-named row is in the tab its own name declares" \
  || bad "misfiled ML row(s): $(printf '%s' "$misfiled" | tr '\n' '; ')"

# (d) The hand-written {fleet id -> tab} map must not come back. Two statements
#     of one fact is the defect this replaced, not a safety net for it.
jq -e '.constellation | has("group_members")' "$BUILD" >/dev/null 2>&1 \
  && bad "constellation.group_members is back - the tab is derived from the name now, and a map that can disagree with it is the bug" \
  || ok "no group_members map: an ML row's name is the only statement of its tab"

# (e) The page must derive the application heading from the row, not list the
#     applications. A hardcoded "voice"/"text"/"image" here is the second list
#     wearing a different hat.
command grep -qF 'applicationOf(app.id)' "$PAGE" \
  && ok "the page reads each row's application off its own id" \
  || bad "StoreCloudFragment no longer derives the application from the row id"
for application in $(jq -r '[.apps[], .catalogue[]] | .[].id
        | capture("^lib-ml-[tl]-(?<domain>[a-z0-9]+)-") | .domain' "$FLEET" | sort -u); do
  command grep -nF "\"$application\"" "$PAGE" \
    && bad "StoreCloudFragment hardcodes the application literal \"$application\"" \
    || ok "no \"$application\" literal on the page"
done

# (f) ... and a lib may not wear the ML name without carrying an ML runtime.
for claimed in $(jq -r --arg re "$SCHEME" '.apps[]
        | select(.kind == "lib" and (.id | test($re))) | .id | sub("^lib-"; "")' "$FLEET"); do
  printf '%s\n' "$ML_MODULES" | command grep -qx "$claimed" \
    && ok "$claimed wears the ML name and its build.gradle backs it" \
    || bad "$claimed wears the ML name but its build.gradle declares no ML runtime"
done

echo "== T8: #642 the Libs tab is TABLES, from the one declaration, through the Apps mechanism =="
# A group is a TAB; a category is a table inside one. Libs rendered flat not
# because they used a different mechanism but because the SAME one had no data:
# AppStoreHost.classify files a package the launcher knows, and it knows no
# library package, so all 46 rows came back unshelved and sat in one unnamed
# run. T8 asserts the three things that fix has to be, and each on the side a
# hollow version would not survive.
LIBCAT_BJ="$ROOT/ab_cloud-libs-shared/lib-apks/build.json"
[ -f "$LIBCAT_BJ" ] || { echo "ERROR: missing $LIBCAT_BJ" >&2; exit 2; }

# (a) The declaration and the generated fleet are ONE list, in one order. Checked
#     on the whole array rather than a count: two lists of equal length in
#     different orders would pass a count and render the tables in the wrong
#     order, which is the #383 regression one level down.
jq -n -e --slurpfile fleet "$FLEET" --slurpfile bj "$LIBCAT_BJ" \
  '($fleet[0].lib_categories // []) as $f | ($bj[0].lib_apks.categories // []) as $d
   | ($f | length) > 0 and ($f | map({id, label, members})) == ($d | map({id, label, members}))' >/dev/null \
  && ok "the fleet's lib_categories are lib_apks.categories, in declared order" \
  || bad "lib_categories differ from lib_apks.categories — rerun data/regen.sh"

# (b) Every row of the Libs TAB is filed on exactly one table. Derived from the
#     tab's own members, so a lib added to the fleet is caught here rather than
#     discovered as an untitled row on the device. Both directions: an unfiled
#     lib AND a category naming a row that is not in the tab.
unfiled="$(jq -r '[.lib_categories[].members[]] as $filed
    | (.groups[] | select(.id == "libs") | .members[]) | select(IN($filed[]) | not)' "$FLEET")"
[ -z "$unfiled" ] \
  && ok "every row in the Libs tab is filed under a category" \
  || bad "Libs row(s) under no category, so they draw in one unnamed run again: $(printf '%s' "$unfiled" | tr '\n' ' ')"
stray="$(jq -r '[.groups[] | select(.id == "libs") | .members[]] as $tab
    | .lib_categories[] as $c | $c.members[] | select(IN($tab[]) | not)
    | "\($c.id) names \(.)"' "$FLEET")"
[ -z "$stray" ] \
  && ok "no category names a row outside the Libs tab" \
  || bad "category member(s) that are not Libs rows: $(printf '%s' "$stray" | tr '\n' '; ')"
dupe="$(jq -r '[.lib_categories[].members[]] | group_by(.)[] | select(length > 1) | .[0]' "$FLEET")"
[ -z "$dupe" ] \
  && ok "no lib is filed under two categories" \
  || bad "lib(s) in more than one category: $(printf '%s' "$dupe" | tr '\n' ' ')"

# (c) ZERO KOTLIN. Adding a lib or a category must be the data edit above and
#     nothing else, so the page may not write a single category id or label —
#     the same rule T5 holds for groups and (e) holds for ML applications. This
#     is the assertion that fails if someone "helpfully" hardcodes the tables.
for literal in $(jq -r '.lib_categories[] | .id, .label' "$FLEET" | tr ' ' '\036'); do
  literal="$(printf '%s' "$literal" | tr '\036' ' ')"
  command grep -nF "\"$literal\"" "$PAGE" \
    && bad "StoreCloudFragment hardcodes lib category literal \"$literal\"" \
    || ok "no \"$literal\" literal on the page"
done

# (d) ... and it must group libs through the mechanism it already had, not a
#     second one beside it. The category IS an AppStoreHost.Shelf — the same
#     (heading, order) pair the host's classifier returns — and ONE lookup
#     feeds both the heading and the sort key, so the two cannot disagree.
command grep -qF 'fleetObjects("lib_categories")' "$PAGE" \
  && ok "the tables are built from the fleet's declared lib_categories" \
  || bad "the page does not read lib_categories — the Libs tab is flat again"
command grep -qF 'AppStoreHost.Shelf(' "$PAGE" \
  && ok "a lib category IS a Shelf: the Apps grouping type, not a new one" \
  || bad "the page builds its own lib-heading type instead of AppStoreHost.Shelf"
# The heading and the ordering key must both go through shelfOf. A direct
# shelves[...] read left on either side is exactly how the two drift apart.
for needle in 'shelfOf(app)?.heading' 'shelfOf(it)?.order'; do
  command grep -qF "$needle" "$PAGE" \
    && ok "$needle — one shelf lookup feeds it" \
    || bad "missing $needle: the heading and the sort key can disagree"
done
command grep -nE 'shelves\[[a-z]+\.pkg\]\?\.(heading|order)' "$PAGE" \
  && bad "a direct shelves[...] heading/order read is back beside shelfOf — two grouping paths" \
  || ok "shelfOf is the only heading/order source"

echo "== T9: #660 a tab that is not a TYPE OF APK is not on the type line =="
# A declared group is a type of apk and picking one narrows the fleet. Commits,
# CI-CD and Perms narrow nothing: the feeds' declared endpoints carry no type,
# and the Perms tab walks the whole fleet. So they get their own line, and WHICH
# line a tab sits on is which declaration it came from - never a list here.
FEEDS_ASSET="$LIBS/appstore/src/main/assets/appstore-feeds.json"
[ -f "$FEEDS_ASSET" ] || { echo "ERROR: missing $FEEDS_ASSET" >&2; exit 2; }

# (a) The two lines must not be concatenated back into one. This is the exact
#     regression direction: one list of labels is what #660 was filed about.
merged="$(command grep -nE '\{ it\.label \}[[:space:]]*\+[[:space:]]*FeedViewer\.labels' "$PAGE")"
[ -z "$merged" ] \
  && ok "the group labels and the feed labels are not concatenated into one strip" \
  || bad "groups + feeds are back in ONE tab line (#660 reverted): $merged"

# (b) The per-type line is the declared groups, and the other line is the
#     declared feeds plus the page's own Perms. Asserted as the LINE LIST that
#     the builder iterates, so a tab cannot be moved between lines by accident.
# #671 paired each line with its control language, so the line list is now
# (labels to style) rather than a bare list. What is asserted is unchanged: line
# 1 IS the declared groups and line 2 IS the declared feeds plus Perms.
# #732 each line's entries are now Controls carrying their declared look, so
# the line list is two lists of Controls; the membership asserted is the same.
# The strip is now StoreStrip.slots(declared group labels, ...): the declared groups, then Fleet Data, the "|" separator and Ventoy, in that order.
command grep -qF 'StoreStrip.slots(tabs.map { it.label }' "$PAGE" \
  && command grep -qF 'StoreControls.Control(it, "", groupTab)' "$PAGE" \
  && ! command grep -qF 'rememberStoreControls().page' "$PAGE" \
  && ok "the Cloud page is ONE line, the declared groups through StoreStrip; Mesh, the feeds and Perms are Access/Feed pages since #896/#896.3" \
  || bad "the tab lines are not built from the two declarations"
slots_body="$(awk '/fun slots\(/{f=1} f{print} f && /^    }$/{exit}' "$STRIP")"
fd_at="$(printf '%s' "$slots_body" | grep -n 'KIND_FLEETDATA' | head -1 | cut -d: -f1)"
sep_at="$(printf '%s' "$slots_body" | grep -n 'out.add(at, Slot(SEPARATOR, KIND_SEPARATOR))' | head -1 | cut -d: -f1)"
ven_at="$(printf '%s' "$slots_body" | grep -n 'KIND_VENTOY' | head -1 | cut -d: -f1)"
[ -n "$fd_at" ] && [ -n "$sep_at" ] && [ -n "$ven_at" ] && [ "$fd_at" -lt "$sep_at" ] && printf '%s' "$slots_body" | grep -F 'out.add(at, Slot(SEPARATOR, KIND_SEPARATOR)); out.add(at + 1, Slot(ventoyLabel, KIND_VENTOY))' >/dev/null \
  && ok "StoreStrip orders the Cloud strip: groups, Fleet Data, \"|\" separator, Ventoy" \
  || bad "StoreStrip no longer orders Fleet Data, then the \"|\" separator, then Ventoy"
[ -f "$LIBS/appstore/src/test/java/com/diegonmarcos/superapp/appstore/StoreStripTest.kt" ] \
  && ok "StoreStripTest pins that order" || bad "StoreStripTest is missing"

# (c) TWO strips inside one column, from ONE button builder - not a second
#     differently-styled control set beside the first.
#
#     SCOPED TO tabBar's OWN BODY. Grepping the whole file for
#     "orientation = LinearLayout.VERTICAL" passes on a dozen unrelated cards and
#     columns, so it stayed green with tabBar flipped back to HORIZONTAL - it was
#     asserting that the file contains a vertical layout SOMEWHERE, which it
#     always will. Caught by mutation, not by reading it.
# #896 the strip builder is StoreTabs.bar, shared with Phone; tabBar is its Cloud call.
TABS="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreBar.kt"  # StoreTabs lives beside the bar
[ -f "$TABS" ] || { echo "ERROR: missing $TABS" >&2; exit 2; }
command grep -qF 'StoreTabs.Bar(' "$PAGE" && ok "TabBar draws through the shared StoreTabs.Bar" || bad "TabBar does not use the shared strip builder"
tabbar_body="$(awk '/fun Bar\(lines: List/{f=1} f{print} f && /^    }$/{exit}' "$TABS")"
[ -n "$tabbar_body" ] || bad "could not isolate tabBar's body - the assertion below would verify nothing"
printf '%s' "$tabbar_body" | command grep -qF 'Column(modifier' \
  && ok "tabBar itself is a column that can hold more than one strip" \
  || bad "tabBar is still a single horizontal row"
printf '%s' "$tabbar_body" | command grep -qF 'Row {' \
  && ok "and the strips inside it are horizontal" \
  || bad "tabBar builds no horizontal strip - the tabs would stack one per line"
builders="$(command grep -c 'fun RowScope.Tab(' "$TABS")"
[ "$builders" = "1" ] \
  && ok "exactly one tab-button builder feeds both lines" \
  || bad "expected 1 tab-button builder, found $builders - the two lines can drift apart"

# (d) An EMPTY line draws NO strip. A row reserving height for controls that are
#     not there is the affordance-that-cannot-act shape (#233).
command grep -qE 'if \((labels|line)\.isEmpty\(\)\) continue' "$TABS" \
  && ok "a line with no tabs draws no strip at all" \
  || bad "an empty tab line would still draw a strip"

# (e) ZERO KOTLIN for a new tab. Every tab label comes off a declaration, so no
#     label may be written here. #732 moved the last two (Mesh, Perms) into
#     assets/appstore-controls.json, so there is no exemption left. Derived from
#     ALL THREE declarations, so the assertion count grows when any gains an
#     entry: a pinned list would not move.
CONTROLS_ASSET="$LIBS/appstore/src/main/assets/appstore-controls.json"
[ -f "$CONTROLS_ASSET" ] || { echo "ERROR: missing $CONTROLS_ASSET" >&2; exit 2; }
for label in $(jq -r '.groups[].label' "$FLEET" | tr ' ' '\036') \
             $(jq -r '.feeds[].label' "$FEEDS_ASSET" | tr ' ' '\036') \
             $(jq -r '.pages[] | .label // empty' "$CONTROLS_ASSET" | tr ' ' '\036'); do
  label="$(printf '%s' "$label" | tr '\036' ' ')"
  command grep -nF "\"$label\"" "$PAGE" \
    && bad "StoreCloudFragment hardcodes tab label \"$label\" instead of reading its declaration" \
    || ok "tab label \"$label\" is not written on the page"
done
# ... and the page's own entry is named by the IDS their declaration is
# keyed on, so the caption can change in data with no Kotlin edit.
for id in $(jq -r '.pages | keys[]' "$CONTROLS_ASSET"); do
  # #896.3 a declared page is Access's (its label is the Apps Mesh caption SuperApp must equal), never a Cloud chip.
  jq -e --arg id "$id" '.ui.sections[] | select(.id == "access") | .pages[] | select(.id == $id)' "$STORE_BJ" >/dev/null \
    && ok "declared page '$id' is a child page of Access" \
    || bad "appstore-controls.json declares page '$id' that is no child page of Access"
done

echo "== T10: #671 the two lines are two CONTROL LANGUAGES, not two rows of the same pill =="
# A line-1 tab SELECTS A SUBSET; a line-2 entry filters nothing (both feeds are
# repo-wide, and Perms walks the whole fleet). Drawing them identically claims
# they are the same control. Every assertion below is SCOPED to the body of the
# function it is about - a whole-file grep for a layout property asserts nothing,
# which is how the #660 horizontal check stayed green while tabBar was wrong.
body_of() { # body_of <fun signature fragment>
  awk -v pat="$1" 'index($0, pat){f=1} f{print} f && /^    }$/{exit}' "$PAGE"
}
tabs_body_of() { awk -v pat="$1" 'index($0, pat){f=1} f{print} f && /^    }$/{exit}' "$TABS"; }
TABBAR="$(body_of 'private fun TabBar(')"
TABBTN="$(tabs_body_of 'fun RowScope.Tab(')"
PAINT="$TABBTN"   # selection is painted by the same Tab composable now (no separate paint pass)
BARBODY="$(tabs_body_of 'fun Bar(lines')"
for pair in "TabBar:$TABBAR" "Tab:$TABBTN"; do
  [ -n "${pair#*:}" ] || bad "could not isolate ${pair%%:*}'s body - every assertion about it would verify nothing"
done

# (a) The two lines carry DIFFERENT styles. #732 made the look data: line 1
#     wears the declared group_tab_style and line 2 wears each entry's own
#     declared style (controls.page). That the declared styles really differ is
#     test-store-controls.sh's job; here, that tabBar sources the two lines'
#     looks from those two DIFFERENT declarations rather than one.
printf '%s' "$TABBAR" | command grep -qF 'rememberStoreControls().groupTab' \
  && printf '%s' "$TABBAR" | command grep -qF 'groupTab' \
  && ! printf '%s' "$TABBAR" | command grep -qF '.page' \
  && ok "tabBar dresses its one line from the group-tab declaration (no page chips left on Cloud)" \
  || bad "tabBar draws page chips again"

# (b) ONE builder still, and it is the thing that VARIES by style. A builder
#     that ignores its style argument is the same defect wearing a parameter.
builders="$(command grep -c 'fun RowScope.Tab(' "$TABS")"
[ "$builders" = "1" ] \
  && ok "exactly one tab-button builder feeds both lines" \
  || bad "expected 1 tab-button builder, found $builders - the two languages can drift apart"
printf '%s' "$TABBTN" | command grep -qF 'if (stretch)' && printf '%s' "$BARBODY" | command grep -qF 'it.style.stretch' \
  && ok "the builder branches on the style it is handed" \
  || bad "tabButton takes a style and ignores it - both lines would draw the same"
# The two branches must differ in the thing that makes a segmented control
# segmented: one stretches (weight 1f), the other wraps.
printf '%s' "$TABBTN" | command grep -qF 'Modifier.weight(1f)' \
  && printf '%s' "$TABBTN" | command grep -qF 'Modifier.padding(end' \
  && ok "one style stretches to fill the bar and the other wraps its content" \
  || bad "both styles size the same way, so line 2 still reads as a partition"

# (c) SELECTION reads differently too. Filling a destination chip the way a
#     segmented pill fills puts the two languages back into one.
printf '%s' "$PAINT" | command grep -qF 'storeSurface(style, on)' \
  && printf '%s' "$PAINT" | command grep -qF 'style.textActive' \
  && ok "paintTabs paints a destination differently from a segmented pill" \
  || bad "paintTabs paints every tab alike - the active state re-merges the two languages"

# (d) REAL SEPARATION, and only BETWEEN lines. A page with one line must not
#     draw a stray rule above it.
printf '%s' "$BARBODY" | command grep -qF 'Divider()' \
  && ok "a divider separates the two lines" \
  || bad "nothing separates the two lines - they read as one block"
printf '%s' "$BARBODY" | command grep -qF 'if (drawn > 0)' \
  && ok "the divider is drawn only BETWEEN lines, never above the first" \
  || bad "the divider is not conditional - a single-line bar would draw a stray rule"
DIV="$(tabs_body_of 'fun Divider(')"
printf '%s' "$DIV" | command grep -qE 'padding\(top = sdp\(StoreDensity\.S[0-9]+\), bottom = sdp\(StoreDensity\.S[0-9]+\)\)' \
  && ok "the divider carries real vertical space, not just a hairline" \
  || bad "the divider has no margins - a 1px rule with no space is still one block"

# (e) Everything #660 proved must survive: membership still derived, empty line
#     still draws nothing.
printf '%s' "$TABBAR" | command grep -qF 'StoreStrip.slots(tabs.map { it.label }' \
  && ok "line membership is still which declaration the tab came from" \
  || bad "the tab lines are no longer built from the two declarations"
printf '%s' "$BARBODY" | command grep -qF 'if (line.isEmpty()) continue' \
  && ok "a line with no tabs still draws no strip at all" \
  || bad "an empty tab line would draw a strip (or its divider)"


echo "== T11: #1441 the Machine-learning libs table is on Lite-ML only, the shared libs table on Libs only =="
# Declared beside the group (build.json::constellation.groups[].build_time_libs), carried into the
# fleet by regen.sh, read by the page; exactly one group per table, so no page draws a duplicate.
for pair in "shared:libs" "ml:libs-l-ml"; do
  kind="${pair%%:*}"; want="${pair#*:}"
  got="$(jq -r --arg k "$kind" '[.groups[] | select(.build_time_libs == $k) | .id] | join(",")' "$FLEET")"
  [ "$got" = "$want" ] \
    && ok "build-time $kind libs table is declared on $want alone" \
    || bad "build-time $kind libs table is on [$got], want [$want] (rerun data/regen.sh after build.json)"
done
jq -e '[.constellation.groups[] | .build_time_libs // empty] == ["shared","ml"]' "$BUILD" >/dev/null \
  && ok "build.json declares the two tables, in page order" \
  || bad "build.json::constellation.groups does not declare build_time_libs shared (Libs) and ml (Lite-ML)"
command grep -qF 'group.optString("build_time_libs")' "$PAGE" \
  && command grep -qF 'if (buildTimeLibs.isNotEmpty()) BuildTimeLibs(buildTimeLibs == "ml")' "$PAGE" \
  && ! command grep -qF 'if (hasLibSections) BuildTimeLibs(' "$PAGE" \
  && ok "the page draws each lib table where the tab declares it, not on the Libs tab by default" \
  || bad "the page no longer reads build_time_libs off the tab (the ML table would be back on Libs)"

echo
echo "== RESULT(#405 groups+ml-naming, #642 lib tables, #660/#671 tab lines, #1441 ML libs on Lite-ML): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
