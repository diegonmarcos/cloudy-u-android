#!/usr/bin/env bash
# Tester: the notification centre's badges + the ntfy page (Configs ▸ Launcher ▸
# Notify since #580; it was Configs ▸ Home's two tabs, Push | Notify, and Home
# is deleted) — plus the device switch board (was Panel ▸ Control, now Configs ▸
# Launcher ▸ Controls, hosted by LauncherConfigFragment) and the rules each
# exists to keep.
#
# WHY THIS EXISTS: this page has several ways to be wrong, and none of them
# shows up as a crash or a failed build.
#
#   1. A SWITCH THAT LIES. Android forbids a normal app from flipping Wi-Fi,
#      mobile data, Bluetooth or the airplane radios. Drawing a Switch for those
#      gives the owner a control that moves under the thumb, persists, and
#      changes nothing on the device — the single worst outcome this page can
#      have, and one no compiler will ever mention. So: those four must not have
#      a `set`, every write must decide its verdict by RE-READING the device,
#      and the UI must never show the state that was asked for.
#   2. A COPY OF THE NTFY PAGE. The Notify tab was told to reuse the existing
#      page, not clone it. It is a `mirror_page` facet, so it renders the very
#      fragment `page:communication/my-rss` opens; a second stack_ declaration
#      here would be the copy that drifts.
#   3. A DECORATIVE BADGE PANE (#497). The badge panes (Push until #580 merged
#      it into Notify) render build.json::ui.notification_center — every
#      notification-center / badge producer this app ships — and they have to
#      actually be DRIVEN by that array, not by a hardcoded list of three
#      BadgePanes invented on its own. A pane that renders the same
#      whatever the declaration says would be decorative, so T28 below proves
#      the render follows an edit to the declaration, the same way T22 proves
#      it for Battery Hungers.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin is
# checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SECTIONS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
TABS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
CONTROLS="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/DeviceControls.kt"
FRAGMENT="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/ControlFragment.kt"
PUSH="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/BadgePanes.kt"   # #580: was PushFragment.kt
AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
AGGPANELS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorPanels.kt"   # the card bodies (#773)
KT_LCF="$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherConfigFragment.kt"
# #515 moved the READING of the declaration out of the pane and into one
# resolver both the pane and the restart receiver share, so T28/T29 follow
# the indirection rather than asserting the pane still parses JSON itself.
NCDIR="$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"
BADGEDECL="$NCDIR/BadgeDeclaration.kt"
BADGESVC="$NCDIR/BadgeServices.kt"
STATUS="$APP/../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/ui/StatusLight.kt"
# #867 the status-light colours and words moved to libs:account with StatusLight; the rest stayed here.
ACCRES="$APP/../ab_cloud-libs-shared/libs/account/src/main/res"
COLORS="$(mktemp)"; trap 'rm -f "$COLORS"' EXIT; cat "$APP/app/src/main/res/values/colors.xml" "$ACCRES/values/colors.xml" > "$COLORS"
STRINGS="$APP/app/src/main/res/values/strings.xml"
THEME_BG="$APP/app/src/main/res/drawable/bg_gradient_black_purple.xml"
LIBS="$(cd "$APP/../ab_cloud-libs-shared" && pwd)"

# ── preflight: a missing tool or a moved file must be LOUD, never a verdict ──
# Four testers in this repository once passed only because ripgrep was absent
# and their call failed open, and a path written against the wrong directory
# matches nothing exactly as quietly as a contract being kept.
for tool in python3 jq; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "  ABORT: $tool is not on PATH — every assertion below would report a"
    echo "         verdict from its ABSENCE rather than from the code."; exit 2; }
done
for f in "$BJ" "$GRADLE" "$SECTIONS" "$PAGES" "$NAV" "$TABS" "$CONTROLS" \
         "$FRAGMENT" "$STATUS" "$COLORS" "$THEME_BG" "$PUSH"; do
  [ -f "$f" ] || {
    echo "  ABORT: no such file: $f — a grep over nothing matches nothing, which"
    echo "         is indistinguishable here from a contract being kept."; exit 2; }
done

echo "== T1: Configs ▸ Home/Push/Panel/Control stay DELETED; Launcher ends One-Hand, Folders, Notify (#580, #649, #1553) =="
# #580 put Notify immediately after One-Hand in Launcher's tab strip. #649 turned
# that strip into the `Launcher` group of four direct tiles — so the ORDER this
# check owns survived verbatim and only its carrier changed, from
# `pages[launcher].tabs` to the run of pages declaring `group: Launcher`. The
# deleted ids are still deleted, which is the other half and the reason the four
# retired names are listed rather than assumed.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
ids  = [p['id'] for p in pages]
gone = [i for i in ('home', 'push', 'panel', 'control', 'launcher') if i in ids]
def run(g): return [p['id'] for p in pages if p.get('group') == g and not p.get('hidden')]
launcher = run('Launcher')
if gone:                     print('retired page(s) still declared: %r' % gone)
elif launcher != ['controls', 'panels-ui', 'app-folders', 'onehand', 'launcher-sep-1', 'notify']:
                             # #1623 the Launcher group holds Quick Actions, UI, Folders, One-Hand | Notify as direct tiles
                             print('Launcher is not Quick Actions, UI, Folders, One-Hand | Notify: %r' % (launcher,))
elif 'launcher-sep-1' not in ids: print('the | separator page is gone')
elif 'app-about' not in ids: print('no `app-about` page')
else:                        print('OK')
PY
)" "no home/push/panel/control/launcher page; Launcher = Quick Actions, UI, Folders, One-Hand | Notify; the relocated Perms, Logcat, X-Ray App, About live in the Configs group (#1623)"

echo "== T2: Notify is a REAL, VISIBLE page of the config section, never declared inline =="
# The contract established in 07964787e was: a tab is a declared page, so
# page:config/<tab> stays a live target. #649 spent that contract — every one of
# the four became its own tile, which is only possible because none of them was
# ever an inline label. So the assertion inverts (`hidden` must now be ABSENT)
# while what it protects does not: `page:config/notify` still resolves, and it
# resolves to a page carrying the mirror rather than to a copy of it.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
for pid in ('presets', 'controls', 'panels', 'panels-all', 'panels-ui', 'panels-battery', 'panels-tools', 'onehand', 'notify'):
    p = by_id.get(pid)
    tab = pid in ('presets', 'panels', 'panels-all', 'panels-battery', 'panels-tools')  # tabs of Controls: declared, hidden, reached through the strip (#1623 UI left the strip)
    if p is None:                       problems.append('page %r is GONE — its target dead-ends' % pid)
    elif tab:
        if not p.get('hidden'):         problems.append('tab %r draws a tile of its own' % pid)
    elif p.get('hidden'):               problems.append('page %r is hidden — #649/#1623 gave it its own tile' % pid)
    else:
        want = {'controls': ('Launcher', ''), 'panels-ui': ('Launcher', ''), 'onehand': ('Launcher', ''), 'notify': ('Launcher', '')}[pid]  # #1623 placement
        if (p.get('group'), p.get('subgroup')) != want:
            problems.append('page %r is under %r, not %r' % (pid, (p.get('group'), p.get('subgroup')), want))
if by_id.get('notify', {}).get('mirror_page') != 'communication/my-rss':
    problems.append('notify stopped mirroring communication/my-rss — a second '
                    'notification centre is exactly what #497/#515 deleted')
print('; '.join(problems) or 'OK')
PY
)" "presets/controls/onehand/notify are declared, VISIBLE Launcher pages; Notify still mirrors the one centre"

echo "== T3: Notify MIRRORS the ntfy page — it does not carry a copy of it =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
sections = {s['id']: s for s in ui['sections']}
notify = next(p for p in sections['config']['pages'] if p['id'] == 'notify')
problems = []
if not notify.get('facet'):
    problems.append('mirror_page only fires on a facet page')
target = notify.get('mirror_page', '')
if '/' not in target:
    problems.append('mirror_page = %r is not <section>/<page>' % target)
else:
    sec, pid = target.split('/', 1)
    if sec not in sections:
        problems.append('mirror_page names an unknown section %r' % sec)
    elif not any(p['id'] == pid for p in sections[sec]['pages']):
        problems.append('mirror_page names an unknown page %r in %r' % (pid, sec))
    if (sec, pid) == ('config', 'notify'):
        problems.append('mirror_page points at itself')
# THE COPY CHECK: the mirrored page's data must stay owned by ITS section.
for key in sections['config']:
    if key.startswith(('stack_notify', 'tiles_notify')):
        problems.append('config declares %s — that is a copy, not a mirror' % key)
print('; '.join(problems) or 'OK')
PY
)" "notify is a facet mirroring communication/my-rss, with no data of its own"

echo "== T4: mirror_page is parsed AND routed, self-reference guarded =="
mp_fail=""
grep -q 'mirror_page' "$SECTIONS"                  || mp_fail="$mp_fail Sections:not-parsed"
grep -q 'val mirrorPage' "$SECTIONS"               || mp_fail="$mp_fail Sections:no-field"
grep -q 'page.mirrorPage' "$NAV"                   || mp_fail="$mp_fail Nav:not-routed"
grep -q 'mirroredPage != null -> pageFragment' "$NAV" || mp_fail="$mp_fail Nav:no-branch"
# A page mirroring itself would re-enter aggregatorPage forever.
grep -q 'sec != section.id || pid != page.id' "$NAV"  || mp_fail="$mp_fail Nav:no-self-guard"
# The mirror branch must be decided BEFORE the section-wide fallbacks, or a
# facet with both would render the section's stack instead of the named page.
python3 - "$NAV" <<'PY' || mp_fail="$mp_fail Nav:branch-order"
import sys
s = open(sys.argv[1]).read()
sys.exit(0 if s.index('mirroredPage != null ->') < s.index('mirrored != null &&') else 1)
PY
[ -z "$mp_fail" ] \
  && ok "mirror_page: declared field, routed branch, self-reference refused, checked first" \
  || bad "mirror_page plumbing incomplete:$mp_fail"

echo "== T5: every DECLARED control has a real capability behind it =="
# An id declared with nothing implementing it is silently DROPPED by the
# fragment, so a typo here costs a control with no error anywhere.
check "$(python3 - "$BJ" "$CONTROLS" <<'PY'
import json, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
declared = [c['id'] for g in cp['groups'] for c in g.get('controls', [])]
src = open(sys.argv[2]).read()
implemented = set(re.findall(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src))
missing = [i for i in declared if i not in implemented]
orphan = [i for i in sorted(implemented) if i not in declared]
out = []
if missing: out.append('declared with no capability: %s' % missing)
if orphan:  out.append('implemented but never declared: %s' % orphan)
if not declared: out.append('no controls declared at all')
print('; '.join(out) or 'OK')
PY
)" "declaration and capability registry name exactly the same controls"

echo "== T6: THE RULE — the four Android will not let an app flip are NOT switches =="
check "$(python3 - "$CONTROLS" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
# Slice the catalog into one block per control id.
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
for i, (pos, cid) in enumerate(ids):
    end = ids[i + 1][0] if i + 1 < len(ids) else len(src)
    blocks[cid] = src[pos:end]
problems = []
# These CANNOT be toggled by a normal app on any supported Android. A `set` on
# one of them is the lying switch this whole page is written to avoid.
for cid in ('wifi', 'mobile_data', 'bluetooth', 'airplane_mode'):
    b = blocks.get(cid)
    if b is None:                 problems.append('%s: not implemented' % cid)
    elif 'set =' in b:            problems.append('%s: has a set — it would lie' % cid)
    elif 'open =' not in b:       problems.append('%s: no way through to Settings' % cid)
    elif 'read =' not in b:       problems.append('%s: no read-only state' % cid)
# Every control reads its state; a control with no read has nothing honest to show.
for cid, b in blocks.items():
    if 'read =' not in b and 'launcherToggle' not in b:
        problems.append('%s: no read' % cid)
print('; '.join(problems) or 'OK')
PY
)" "wifi / mobile_data / bluetooth / airplane_mode are Settings rows, not switches"

echo "== T7: no write is EVER reported successful without re-reading the device =="
# Verdict(true, …) as a literal means "trust the call" — exactly the bug that
# makes a switch report a change it did not achieve.
if grep -n 'Verdict(true' "$CONTROLS" >/dev/null; then
  bad "DeviceControls hardcodes a successful Verdict: $(grep -n 'Verdict(true' "$CONTROLS" | head -3)"
else
  ok "every Verdict is computed from a fresh read, never asserted"
fi

echo "== T8: the UI never displays the state that was ASKED for =="
ui_fail=""
# The tile's fill may only be set from the value just read BACK.
grep -q 'val on = row?.state == true' "$FRAGMENT" \
  || ui_fail="$ui_fail no-read-back-assignment"
grep -qE '\bon = (want|!?currentlyOn)\b' "$FRAGMENT" \
  && ui_fail="$ui_fail paints-the-request"
# NOT A COMPOUND BUTTON ANY MORE, and that is stronger than the detached
# listener the list version needed. Assigning Switch.isChecked FIRES
# OnCheckedChangeListener, so every refresh re-issued the last write unless the
# listener was pulled first — tearing down the mesh or the API server for a
# look at the page. A tile's fill is a plain field: a repaint cannot call
# anything back, so there is no listener to forget to detach. A Switch or a
# CompoundButton reappearing here brings that whole class of bug with it.
#
# Comments are stripped first — the file EXPLAINS which widget it stopped using
# and why, and a grep that cannot tell prose from code would forbid saying so.
# Exit 0 is the CLEAN answer on purpose: a python that never got as far as
# looking must fail this check, not pass it.
python3 - "$FRAGMENT" <<'PY' || ui_fail="$ui_fail compound-button-is-back"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
sys.exit(1 if re.search(r'\b(Switch|CompoundButton)\b', code) else 0)
PY
# A control with no `set` must never switch under a tap.
grep -q 'if (control.set != null) write(' "$FRAGMENT" \
  || ui_fail="$ui_fail tap-not-gated-on-set"
# A blocked tile must be disabled AND say why.
grep -q 'enabled = blocked.isEmpty()' "$FRAGMENT" \
  || ui_fail="$ui_fail blocked-tile-still-enabled"
grep -q 'if (blocked.isNotEmpty()) Text(blocked,' "$FRAGMENT" \
  || ui_fail="$ui_fail blocked-reason-not-shown"
[ -z "$ui_fail" ] \
  && ok "tiles are filled from the device read; no CompoundButton to re-fire" \
  || bad "the switch board can show a state it did not verify:$ui_fail"

echo "== T9: ui.control_panel reaches the APK — emitter, constant, reader =="
cp_fail=""
grep -q 'buildJson.ui.control_panel' "$GRADLE"          || cp_fail="$cp_fail gradle:no-json-read"
grep -q 'uiControlPanelB64' "$GRADLE"                   || cp_fail="$cp_fail gradle:no-b64"
grep -q 'UI_CONTROL_PANEL_B64' "$GRADLE"                || cp_fail="$cp_fail gradle:no-buildconfig"
grep -q 'BuildConfig.UI_CONTROL_PANEL_B64' "$CONTROLS"  || cp_fail="$cp_fail kotlin:not-read"
[ -z "$cp_fail" ] \
  && ok "build.json → uiControlPanelB64 → BuildConfig.UI_CONTROL_PANEL_B64 → DeviceControls" \
  || bad "the declaration cannot reach the UI:$cp_fail"

echo "== T10: four functional groups + derived views; nothing orphaned, nothing twice =="
# WHAT CHANGED AND WHY IT IS ASSERTED THIS WAY. The groups used to be
# Phone / Cloud / System — a BLAST-RADIUS taxonomy — and this assertion used to
# pin those three ids and forbid any control appearing twice. Both halves had
# to go, and neither went quietly.
#
# The three groups split the radios across two of themselves (Wi-Fi, mobile
# data, Bluetooth, airplane under Phone; the mesh tunnel and the firewall under
# Cloud), so "turn the network off" meant visiting two groups and knowing in
# advance which half lived where. Device / Network / Tools / UI groups by what
# the owner came looking for instead.
#
# And a control now DOES appear twice on purpose: Battery Hungers gathers the
# battery-hungry ones a second time so they can all be switched off in one
# place. What must still be impossible is a control appearing twice among the
# FUNCTIONAL groups — that would be two homes, which is the ambiguity the old
# assertion was really guarding — and a derived group inventing an id, or
# carrying a hand-written list of ids that can fall out of step with the flags.
check "$(python3 - "$BJ" <<'PY'
import json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
groups = cp['groups']
functional = [g for g in groups if not g.get('derive')]
derived    = [g for g in groups if g.get('derive')]
problems = []

if not functional: problems.append('no functional group declares any control')
if not derived:    problems.append('no derived group — Battery Hungers is gone')

# A control has exactly ONE functional home.
seen, dupes = set(), []
for g in functional:
    if not g.get('controls'): problems.append('group %s is empty' % g['id'])
    for c in g.get('controls', []):
        if c['id'] in seen: dupes.append(c['id'])
        seen.add(c['id'])
        for field in ('label', 'subtitle', 'icon'):
            if not c.get(field): problems.append('%s has no %s' % (c['id'], field))
if dupes: problems.append('control in two FUNCTIONAL groups: %s' % sorted(set(dupes)))

for g in derived:
    # THE ONE THAT MATTERS. A derived group with its own `controls` is a second
    # list of ids beside the flags, and the two drift — which is exactly the
    # "on in one group, off in another" defect this design is shaped to avoid.
    if g.get('controls'):
        problems.append('derived group %s carries its own controls list' % g['id'])
    flag = g['derive']
    gathered = [c['id'] for gg in functional for c in gg.get('controls', [])
                if c.get(flag) is True]
    if not gathered:
        problems.append('derived group %s gathers nothing — no control carries %r'
                        % (g['id'], flag))
    # Nothing invented: everything a derived view shows has a functional home.
    for cid in gathered:
        if cid not in seen: problems.append('%s gathered but has no home' % cid)

# Every group is named through the STRING TABLE, or its heading can never be
# translated — the owner reads this app in Spanish.
for g in groups:
    for key in ('label_res', 'subtitle_res'):
        if not g.get(key): problems.append('group %s declares no %s' % (g['id'], key))
    if not g.get('label'): problems.append('group %s has no fallback label' % g['id'])
print('; '.join(problems) or 'OK')
PY
)" "one functional home per control; the derived view gathers by flag, invents nothing"

echo "== T11: the switch board is routed by the Launcher's Controls tab; Push has NO route and NO fragment; Notify is NOT routed (facet) =="
r_fail=""
# #574: the switch board has ONE producer. SectionPages no longer routes a
# `control` page at all; `controls` is LauncherConfigFragment, which hosts
# ControlFragment as an embedded child (so its ticker/lights/tap/hold are the
# same class, asserted by every test below).
grep -q 'pageId == "control" ' "$PAGES"          && r_fail="$r_fail control:old-route-still-there"
grep -q 'configs.ControlFragment' "$PAGES"     && r_fail="$r_fail control:second-producer-in-SectionPages"
grep -q 'pageId == "panels-ui" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI)' "$PAGES" \
                                                 || r_fail="$r_fail panels-ui:not-routed"
grep -q 'pageId == "panels-tools" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS)' "$PAGES" \
                                                 || r_fail="$r_fail panels-tools:not-routed"
grep -q 'ControlFragment.newInstance(embedded = true)' "$KT_LCF" \
                                                 || r_fail="$r_fail controls:does-not-host-the-grid"
# #580: Push stopped being a page. A route or a fragment class for it is a
# second producer of the badge view — the panes are stack_my-rss panels now.
grep -q 'pageId == "push"' "$PAGES"              && r_fail="$r_fail push:route-still-there"
grep -rq 'PushFragment' "$APP/app/src/main/java" && r_fail="$r_fail push:fragment-still-there"
# A SectionPages branch for `notify` would bypass the mirror and hand back a
# generic placeholder, which is how the tab would quietly stop being the ntfy
# page while still opening something.
grep -q 'pageId == "notify"' "$PAGES"            && r_fail="$r_fail notify:shadowed-by-factory"
[ -z "$r_fail" ] \
  && ok "config/controls → LauncherConfigFragment ⊃ ControlFragment; no config/push route or PushFragment; config/notify left to the mirror" \
  || bad "page routing wrong:$r_fail"

echo "== T12: the tab a strip OPENS ON is the first declared tab, and nothing else =="
# "Presets is first" and "Launcher opens on Presets" are only the same statement
# while startIndex's fallback stays "the first tab with a fragment". If someone
# adds a default_tab flag, the array and the flag become two answers to one
# question and the reorder above silently stops deciding anything.
land_fail=""
grep -q 'pages.indexOfFirst { it.action.isBlank() }.coerceAtLeast(0)' "$TABS" \
  || land_fail="$land_fail no-first-tab-fallback"
# only the Launcher strip is asked: the Mesh strip (section wg) declares its own default_tab, and test-mesh-page.sh requires it
python3 - "$BJ" <<'PY' || land_fail="$land_fail second-source-of-truth-in-json"
import json, sys
secs = json.load(open(sys.argv[1]))['ui']['sections']
cfg = next(s for s in secs if s['id'] == 'config')
bad = [k for p in cfg['pages'] if p.get('subgroup') == 'Launcher' for k in p if k in ('default_tab', 'initial_tab', 'selected_tab')]
sys.exit(1 if bad else 0)
PY
grep -qE 'defaultTab|initialTab' "$SECTIONS" \
  && land_fail="$land_fail second-source-of-truth-in-kotlin"
# WHICH design this app has, pinned so changing it has to be deliberate: the
# strip is restored to the tab the user left, from an IN-MEMORY map on the
# controller — so the first-tab rule decides a cold open, and the rest of the
# process gets the last tab looked at. A move to SharedPreferences would make
# a phone that once opened Notify open Notify forever, which is a different
# product decision and must not arrive as a refactor.
grep -q 'activeTabFor(tabKey)' "$TABS" \
  || land_fail="$land_fail strip-does-not-restore"
grep -q 'private val activeTabBySection = mutableMapOf<String, String>()' "$NAV" \
  || land_fail="$land_fail tab-memory-is-no-longer-in-memory"
[ -z "$land_fail" ] \
  && ok "first declared tab is the landing tab; last-viewed tab is remembered per process only" \
  || bad "the declared tab order does not decide what opens:$land_fail"

echo "== T13: every DECLARED control carries an icon that RESOLVES, and a state source =="
# Two ways a row ships blank, neither of which the compiler or the JSON parser
# will mention:
#   - no `icon` at all, or one naming a drawable that does not exist. Sections
#     .iconResFor falls back to the generic ic_link_tile for any name it cannot
#     resolve, so a typo becomes a row that looks deliberate and means nothing.
#   - no `read`, which is the ONLY thing a light is painted from. A row without
#     one could not have a light that was driven by anything.
# Adding a control without either has to fail HERE, because nothing downstream
# fails at all.
check "$(python3 - "$BJ" "$CONTROLS" "$APP/app/src/main/res/drawable" <<'PY'
import json, os, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
src = open(sys.argv[2]).read()
drawables = sys.argv[3]

# One block per control id, so `read` is attributed to the right entry.
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
for i, (pos, cid) in enumerate(ids):
    blocks[cid] = src[pos:(ids[i + 1][0] if i + 1 < len(ids) else len(src))]

problems = []
for g in cp['groups']:
    # A derived group re-shows controls declared above; it owns no icon.
    for c in g.get('controls', []):
        cid = c['id']
        icon = c.get('icon', '')
        if not icon:
            problems.append('%s: no icon declared' % cid)
        elif not any(os.path.exists(os.path.join(drawables, icon + ext))
                     for ext in ('.xml', '.png', '.webp')):
            problems.append('%s: icon %r is not a drawable - it would render '
                            'as the ic_link_tile fallback' % (cid, icon))
        b = blocks.get(cid)
        if b is None:
            problems.append('%s: no capability, so no state source' % cid)
        elif 'read =' not in b and 'launcherToggle' not in b:
            problems.append('%s: no read - its light could not be driven' % cid)
print('; '.join(problems) or 'OK')
PY
)" "every control: an icon that resolves to a real drawable, and a read to light it"

echo "== T14: NO light colour is a literal - every one is a themeable resource =="
# Two different defects, and the second is the one that ships looking fine.
#   1. A hex written at the row is a colour chosen at BUILD time. It survives
#      the state source being removed, which is exactly how a light stops
#      meaning anything while still looking right.
#   2. A hex written ANYWHERE, including in StatusLight, cannot follow a theme.
#      This app draws over a black->purple gradient by default and over pure
#      black under the two oled_black launcher themes; a status light is the
#      last thing on screen allowed to become unreadable, so its colours live
#      in colors.xml where a theme can override them.
light_fail=""
grep -qE '0x[fF][fF][0-9A-Fa-f]{6}' "$FRAGMENT" \
  && light_fail="$light_fail fragment-hardcodes-a-colour"
grep -qE '0x[fF][fF][0-9A-Fa-f]{6}' "$STATUS" \
  && light_fail="$light_fail StatusLight-hardcodes-a-colour"
grep -q 'StatusLight.colour(' "$FRAGMENT" || light_fail="$light_fail fragment-does-not-use-the-token"
# setTextColor on the light must take StatusLight's answer and nothing else.
grep -qE 'Color\(StatusLight\.colour\(' "$FRAGMENT" \
  || light_fail="$light_fail light-painted-from-something-else"
# StatusLight must resolve through resources, and those resources must exist.
grep -q 'ContextCompat.getColor' "$STATUS" || light_fail="$light_fail StatusLight-does-not-resolve-a-resource"
for c in status_light_on status_light_off status_light_unknown; do
  grep -q "R.color.$c" "$STATUS"          || light_fail="$light_fail StatusLight-misses:$c"
  grep -q "name=\"$c\"" "$COLORS"        || light_fail="$light_fail colors.xml-misses:$c"
done
# ONE definition of healthy / failed / cannot-say. A SECOND copy of one of
# these exact values in Kotlin is a page about to drift into its own idea of
# what healthy looks like, and it would not follow the theme either.
# The values are read out of colors.xml rather than written here, so this
# assertion cannot go stale against the palette it is protecting. Scoped to
# THESE values on purpose: another page's own unrelated green is not a second
# status light, and banning it would make this a lie about what it protects.
#
# Matching the values alone caught only half the disease, and not the half that
# happened. ContainerSheet grew a private palette by INVENTING three colours
# rather than by copying these, so every value below missed it while the sheet
# painted the same fleet a different green from every other page for as long as
# it took somebody to notice by eye. The second pass therefore matches the
# SHAPE instead -- a three-state reading painted straight from literals -- which
# names no colour at all and so cannot be escaped by choosing different ones.
dupes="$(python3 - "$COLORS" "$APP/app/src/main/java" <<'PYX'
import os, re, sys
xml = open(sys.argv[1]).read()
hexes = [re.search(r'name="status_light_%s">#(\w{8})<' % k, xml).group(1).lower()
         for k in ('on', 'off', 'unknown')]
shape = re.compile(r'true\s*->\s*0x[0-9a-f]{8}.{0,200}?false\s*->\s*0x[0-9a-f]{8}'
                   r'.{0,200}?null\s*->\s*0x[0-9a-f]{8}', re.S)
hits = []
for root, _, files in os.walk(sys.argv[2]):
    for f in files:
        if not f.endswith('.kt') or f == 'StatusLight.kt': continue
        body = open(os.path.join(root, f), encoding='utf-8').read().lower()
        for h in hexes:
            if '0x' + h in body: hits.append('%s:%s' % (f, h))
        if shape.search(body):
            hits.append('%s:paints-a-three-state-reading-from-literals' % f)
print(','.join(hits))
PYX
)"
[ -z "$dupes" ] || light_fail="$light_fail status-colour-copied-into:$dupes"
[ -z "$light_fail" ] \
  && ok "every light colour resolves from StatusLight, which resolves from colors.xml" \
  || bad "a light colour is a literal, duplicated, or missing:$light_fail"

echo "== T15: a state source that will not answer renders UNKNOWN, never green =="
# The third state. Red and green are two; the check that has not run, threw,
# timed out or aged out is the third, and drawing it as either of the other two
# is a false statement the owner cannot see is false.
unknown_fail=""
# null MUST map to UNKNOWN in the one place that decides.
python3 - "$STATUS" <<'PY' || unknown_fail="$unknown_fail null-is-not-unknown"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'fun of\(reading: Boolean\?, observed: Boolean.*?\n        \}', s, re.S)
sys.exit(0 if m and re.search(r'null\s*->\s*State\.UNKNOWN', m.group(0)) else 1)
PY
# ...and UNKNOWN must not be painted with the ON colour.
python3 - "$STATUS" <<'PY' || unknown_fail="$unknown_fail unknown-is-green"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'fun colourRes\(state: State\).*?\n    \}', s, re.S)
sys.exit(0 if m and re.search(r'State\.UNKNOWN\s*->\s*R\.color\.status_light_unknown', m.group(0)) else 1)
PY
# A read that throws must become null, not a default.
grep -q 'runCatching { control.read(ctx) }.getOrNull()' "$FRAGMENT" \
  || unknown_fail="$unknown_fail throwing-read-not-nulled"
# A row never read, or read too long ago, has no answer to show.
grep -q 'row.readAt != 0L' "$FRAGMENT" || unknown_fail="$unknown_fail never-read-not-unknown"
grep -q 'SystemClock.elapsedRealtime() - row.readAt <= STALE_MS' "$FRAGMENT" \
  || unknown_fail="$unknown_fail stale-reading-still-shown"
# The light is painted from reading(), which is the function that ages out -
# painting from row.reading directly would skip the staleness rule entirely.
grep -q 'val reading = reading(row)' "$FRAGMENT" && grep -q 'StatusLight.of(reading, control.observed)' "$FRAGMENT" \
  || unknown_fail="$unknown_fail light-bypasses-the-stale-check"
# Nothing may coerce a null reading into a boolean on the way to a light.
grep -qE 'reading\(row\)\s*(\?:|== true)' "$FRAGMENT" \
  && unknown_fail="$unknown_fail null-coerced-before-the-light"
[ -z "$unknown_fail" ] \
  && ok "unread, throwing and aged-out readings all render Unknown, not a colour" \
  || bad "the third state is drawn as one of the other two:$unknown_fail"

echo "== T16: the lights poll only while the page is VISIBLE, and age out slower than they poll =="
# This is a phone. A control panel that keeps binding the WireGuard engine and
# probing shell channels while the owner is in another app is a battery cost
# with no reader.
life_fail=""
grep -q 'repeatOnLifecycle(Lifecycle.State.RESUMED)' "$FRAGMENT" || life_fail="$life_fail no-start-on-resume"
# repeatOnLifecycle(RESUMED) cancels its block the moment the page drops below RESUMED (onPause), so the stop is structural.
grep -q 'while (true) {' "$FRAGMENT" || life_fail="$life_fail no-loop"
# The loop must live INSIDE the lifecycle block, not outside it - outside, a covered page would keep polling.
python3 - "$FRAGMENT" <<'PY' || life_fail="$life_fail loop-not-inside-repeatOnLifecycle"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'repeatOnLifecycle\(Lifecycle\.State\.RESUMED\)\s*\{(.*?)\n            \}', s, re.S)
sys.exit(0 if m and 'while (true)' in m.group(1) and 'refresh(scope)' in m.group(1) else 1)
PY
grep -q 'delay(REFRESH_MS)' "$FRAGMENT" || life_fail="$life_fail ticker-does-not-repeat"
# A reading must outlive the interval that refreshes it, or every row blinks to
# Unknown between ticks and the light becomes noise the owner learns to ignore.
python3 - "$FRAGMENT" <<'PY' || life_fail="$life_fail stale-window-shorter-than-poll"
import re, sys
s = open(sys.argv[1]).read()
def const(n):
    m = re.search(r'%s = ([0-9_]+)L' % n, s)
    return int(m.group(1).replace('_', '')) if m else None
r, st = const('REFRESH_MS'), const('STALE_MS')
sys.exit(0 if r and st and st > r else 1)
PY
# ONE state path. A second poller alongside the existing read is how two
# surfaces start disagreeing about the same device.
for banned in 'java.util.Timer' 'ScheduledExecutorService' 'lifecycleScope.launch'; do
  grep -q "$banned" "$FRAGMENT" && life_fail="$life_fail second-poller:$banned"
done
[ -z "$life_fail" ] \
  && ok "one ticker, started on resume, cancelled on pause, ageing slower than it polls" \
  || bad "the panel polls when nobody is looking, or blinks when they are:$life_fail"

echo "== T17: every control ANSWERS whether its read observes anything =="
# The light's second input. A control added without deciding this would be lit
# from whatever its read happens to return - including a preference, which is
# the SETTING again and never the state. There is deliberately no default in
# the Kotlin, so this check is about the ones that go missing anyway: a new
# entry copied from an old one, a merge that drops a line, a default put back.
check "$(python3 - "$BJ" "$CONTROLS" <<'PYX'
import json, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
declared = [c['id'] for g in cp['groups'] for c in g.get('controls', [])]
src = open(sys.argv[2]).read()
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
for i, (pos, cid) in enumerate(ids):
    blocks[cid] = src[pos:(ids[i + 1][0] if i + 1 < len(ids) else len(src))]
# The two launcher toggles are built by one factory, so their flag is declared
# there; resolve to it rather than demanding a copy at each row.
m = re.search(r'private fun launcherToggle\(.*?\n    \)', src, re.S)
factory = m.group(0) if m else ''
problems = []
for cid in declared:
    b = blocks.get(cid, '')
    if 'launcherToggle(' in b and 'read =' not in b:
        b = factory
    if not re.search(r'observed = (true|false)', b):
        problems.append('%s: does not declare observed' % cid)
if 'observed = ' not in factory:
    problems.append('launcherToggle: the factory declares no observed')
if re.search(r'val observed: Boolean\s*=', src):
    problems.append('Control.observed has a default - a new control could stay silent')
print('; '.join(problems) or 'OK')
PYX
)" "every declared control says whether its read observes the device or a preference"

echo "== T18: THE LIGHT IS THE STATE, NOT THE SETTING =="
# The one assertion this feature exists for, and the one a future refactor
# would break in silence. Both halves run against the SHIPPED mapping, parsed
# out of StatusLight.kt and executed - not a copy of it restated here, which
# would pass forever while the app did something else.
#
#   A. THE CASE. The preference says on and the device says off: an accept loop
#      that died, a tunnel the engine dropped. The panel must go RED. It does
#      only while `read` asks the mechanism instead of the store `set` writes,
#      so every observing control is checked for exactly that.
#   B. THE OTHER CASE. The preference says on and NOTHING can be observed.
#      Green there would be the switch position recoloured, which is the whole
#      failure being designed against.
check "$(python3 - "$STATUS" "$CONTROLS" "$FRAGMENT" "$LIBS" <<'PYX'
import re, sys
st = open(sys.argv[1]).read()

# ── the shipped decision, executed ───────────────────────────────────────
of = re.search(r'fun of\(reading: Boolean\?, observed: Boolean.*?\n        \}', st, re.S)
if not of:
    print('StatusLight.of no longer has the shape this asserts about'); sys.exit()
guard = re.search(r'if \(!observed\) State\.(\w+)', of.group(0))
arms = dict(re.findall(r'(true|false|null) -> State\.(\w+)', of.group(0)))
colour = dict(re.findall(r'State\.(\w+) -> R\.color\.(\w+)', st))
glyph = dict(re.findall(r'State\.(\w+) -> "([^"]+)"', st))
if not guard:
    print('of() no longer refuses to answer for a read that observed nothing'); sys.exit()

def state(reading, observed):
    if not observed:
        return guard.group(1)
    return arms[{True: 'true', False: 'false', None: 'null'}[reading]]

problems = []
# A. preference on, device off  =>  red
if state(False, True) != 'OFF':
    problems.append('an observed false reads %s, not OFF' % state(False, True))
elif colour.get('OFF') == colour.get('ON') or 'off' not in (colour.get('OFF') or ''):
    problems.append('OFF is not painted the failed colour (%s)' % colour.get('OFF'))
# B. preference on, nothing observable  =>  not green
if state(True, False) == 'ON':
    problems.append('an unobservable "on" is drawn ON - the switch recoloured')
if colour.get(state(True, False)) == colour.get('ON'):
    problems.append('%s wears the ON colour' % state(True, False))
# Shape carries the state too, or the light is one hue away from useless.
if glyph.get('ON') == glyph.get('OFF'):
    problems.append('ON and OFF share a glyph - colour is the only channel')

# ── and the panel must ask for it that way ───────────────────────────────
if 'StatusLight.of(reading, control.observed)' not in open(sys.argv[3]).read():
    problems.append('the fragment does not light its rows from (reading, observed)')

# ── no observing read may consult the store its own write touches ────────
src = open(sys.argv[2]).read()
# The map closes on a 4-space `)`; entries close on an 8-space one. Without
# this bound the last control absorbs every helper below it and is accused of
# reading stores it never touches.
start = src.index('val byId: Map<String, Control> = mapOf(')
close = re.search(r'\n    \)\n', src[start:])
catalog = src[start:start + close.start()] if close else src[start:]
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', catalog)]
for i, (pos, cid) in enumerate(ids):
    blocks[cid] = catalog[pos:(ids[i + 1][0] if i + 1 < len(ids) else len(catalog))]
worked = []
for cid, b in sorted(blocks.items()):
    obs = re.search(r'observed = (true|false)', b)
    if not obs or obs.group(1) != 'true':
        continue
    m = re.search(r'read = (.*?)\n            (?:set|blocked|open) =', b, re.S)
    read = m.group(1) if m else b
    in_read = set(re.findall(r'\b(\w+Prefs)\b', read))
    in_write = set(re.findall(r'\b(\w+Prefs)\b', b)) - in_read
    if in_read:
        problems.append('%s: claims to observe but its read asks %s'
                        % (cid, ', '.join(sorted(in_read))))
    elif in_write:
        worked.append('%s set writes %s, read does not' % (cid, '+'.join(sorted(in_write))))

# The firewall is the sharpest case and it is one hop away, so follow the hop:
# FirewallController.isEnabled delegating to a preference store means the row
# CANNOT be observed, whatever its flag says.
import os
target = None
for root, _, files in os.walk(sys.argv[4]):
    if 'FirewallController.kt' in files:
        target = os.path.join(root, 'FirewallController.kt'); break
if target:
    hop = re.search(r'fun isEnabled\(ctx: Context\): Boolean = (\w+)', open(target).read())
    if hop and hop.group(1).endswith('Prefs') \
       and 'observed = false' not in blocks.get('firewall', ''):
        problems.append('firewall reads %s - a store - but claims to observe'
                        % hop.group(1))
if worked:
    sys.stderr.write('    evidence: ' + '; '.join(worked) + '\n')
print('; '.join(problems) or 'OK')
PYX
)" "preference on + device off => red; preference on + nothing observable => never green"

echo "== T19: the lights stay READABLE on the themes this app actually ships =="
# A green picked against white is a green nobody can find on the Samsung-black
# terminal theme, and a status light that cannot be seen is worse than
# none: its absence reads as nothing being wrong. The surfaces come from the
# app's own window background, so a theme that darkens or lightens the page
# re-runs this arithmetic instead of invalidating it.
check "$(python3 - "$COLORS" "$THEME_BG" <<'PYX'
import re, sys
lights = dict(re.findall(r'name="(status_light_\w+)">#\w\w(\w{6})<', open(sys.argv[1]).read()))
# Every colour the window background paints, plus pure black: the two
# oled_black launcher themes drop the gradient entirely.
surfaces = set(re.findall(r'Color="#\w\w(\w{6})"', open(sys.argv[2]).read())) | {'000000'}

def lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
def lum(h):
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
def ratio(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)

problems, worst = [], []
if len(lights) != 3:
    problems.append('expected three status colours, found %d' % len(lights))
for name, hexv in sorted(lights.items()):
    low, on = min((ratio(hexv, s), s) for s in surfaces)
    worst.append('%s %.2f:1 on #%s' % (name.replace('status_light_', ''), low, on))
    if low < 4.5:
        problems.append('%s is %.2f:1 on #%s - below the 4.5:1 AA floor for the '
                        '12sp text it is drawn as' % (name, low, on))
sys.stderr.write('    worst case: ' + '; '.join(worst) + '\n')
print('; '.join(problems) or 'OK')
PYX
)" "every status colour clears WCAG AA against pure black and against the gradient"

echo "== T20: the light is readable WITHOUT colour, and translatable =="
# Red and green are the pair colour-blind users most often cannot separate,
# and neither colour reaches a screen reader at all. And the owner reads this
# app in Spanish: a state word compiled into Kotlin can never be translated,
# which on THIS page means the one screen that says whether the fleet is
# working is the one screen stuck in English.
a11y_fail=""
python3 - "$STATUS" <<'PYX' || a11y_fail="$a11y_fail states-share-a-glyph"
import re, sys
g = dict(re.findall(r'State\.(\w+) -> "([^"]+)"', open(sys.argv[1]).read()))
sys.exit(0 if g.get('ON') and g.get('OFF') and g['ON'] != g['OFF'] else 1)
PYX
# The TILE carries it now, not the light: a grid of icons is one node to a
# screen reader, and four separately-announced fragments of one row is worse
# than the row was. It is still StatusLight's own sentence — the shared one,
# not a second phrasing assembled in the fragment.
grep -q 'StatusLight.description(ctx, decl.label, state)' "$FRAGMENT" && grep -q 'contentDescription = description' "$FRAGMENT" \
  || a11y_fail="$a11y_fail tile-has-no-content-description"
grep -qE 'State\.(ON|OFF|UNKNOWN|UNVERIFIABLE) -> "(On|Off|Unknown|Not verifiable)"' "$STATUS" \
  && a11y_fail="$a11y_fail state-word-hardcoded-in-kotlin"
grep -q 'stringResource(R.string.control_title)' "$FRAGMENT"   || a11y_fail="$a11y_fail title-not-a-resource"
grep -q 'stringResource(R.string.control_caption' "$FRAGMENT"  || a11y_fail="$a11y_fail caption-not-a-resource"
grep -q 'getString(R.string.control_write_refused' "$FRAGMENT" \
  || a11y_fail="$a11y_fail snackbar-not-a-resource"
# EVERY locale carries them, not only the default: the day a values-es lands,
# a state word missing from it must fail HERE rather than ship as one English
# word in the middle of a Spanish sentence.
for f in "$APP"/app/src/main/res/values*/strings.xml; do
  [ -e "$f" ] || continue
  for k in status_light_on status_light_off status_light_unknown status_light_unverifiable \
           status_light_description control_title control_caption control_write_refused \
           control_no_menu control_tile_open_action \
           control_group_device control_group_device_sub \
           control_group_network control_group_network_sub \
           control_group_tools control_group_tools_sub \
           control_group_ui control_group_ui_sub \
           control_group_battery control_group_battery_sub; do
    grep -q "name=\"$k\"" "$f" "$ACCRES/$(basename "$(dirname "$f")")/strings.xml" 2>/dev/null || a11y_fail="$a11y_fail $(basename "$(dirname "$f")")-misses:$k"
  done
done
[ -z "$a11y_fail" ] \
  && ok "differing glyphs, a spoken description, and every word in the string table" \
  || bad "the light is colour-only or English-only:$a11y_fail"

echo "== T21: a control drawn in TWO groups is ONE piece of state =="
# THE DEFECT THIS FEATURE INVITES. Battery Hungers shows the same controls a
# second time. If each tile kept its own copy of the state, flipping the mesh
# under Network would leave the mesh tile under Battery Hungers still lit, and
# the page would be reporting two different answers about one tunnel. Nothing
# would crash and no build would complain.
#
# The invariant is that a tile holds NOTHING about the state except the id it
# was declared with, and that a landed reading reaches EVERY tile bearing that
# id. `firstOrNull` is the precise shape of the bug: it updates one of the two.
dup_fail=""
# The DATA has to actually duplicate something, or every check below guards air.
python3 - "$BJ" <<'PY' || dup_fail="$dup_fail nothing-is-duplicated"
import json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
functional = [g for g in cp['groups'] if not g.get('derive')]
drawn = [c['id'] for g in functional for c in g.get('controls', [])]
for g in cp['groups']:
    if g.get('derive'):
        drawn += [c['id'] for gg in functional for c in gg.get('controls', [])
                  if c.get(g['derive']) is True]
sys.exit(0 if len(drawn) > len(set(drawn)) else 1)
PY
# A read that lands fans out to every tile of that control...
grep -q 'readings\[id\] = Reading(' "$FRAGMENT" \
  || dup_fail="$dup_fail landed-updates-one-tile"
# ...and so does the disable during a write.
grep -q 'writing\[decl.id\] = true' "$FRAGMENT" \
  || dup_fail="$dup_fail write-disables-one-tile"
# NOTHING may route a reading through firstOrNull. currentlyOn() is allowed to
# ask any one tile what the last reading was precisely BECAUSE they are all
# painted together — it reads, it never writes — so the ban is on the writers.
python3 - "$FRAGMENT" <<'PY' || dup_fail="$dup_fail reading-routed-through-firstOrNull"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
bad = []
for fn in ('private fun landed(', 'private fun write('):
    i = code.index(fn)
    j = code.index('\n    private fun ', i + 1)
    if 'firstOrNull' in code[i:j]:
        bad.append(fn)
sys.exit(1 if bad else 0)
PY
# And the derived rows must be the SAME Row values the functional groups made,
# not a second parse of the same JSON — two parses are two objects, and two
# objects are what a "shared" state quietly stops being.
grep -q '.flatMap { it.rows }.filter { it.flags\[flag\] == true }' "$CONTROLS" \
  || dup_fail="$dup_fail derived-rows-are-a-second-parse"
[ -z "$dup_fail" ] \
  && ok "one id, one Control, one read — fanned out to every tile that shows it" \
  || bad "the duplicate tiles can disagree about one control:$dup_fail"

echo "== T22: the battery classification is DATA — adding one needs no Kotlin =="
# The fleet rule, asserted rather than asserted-about. If `battery_hungry`
# appeared in the Kotlin — a `when`, a set of ids, a named field — then adding
# a control to Battery Hungers would be a code change and a rebuild, and the
# declaration would no longer be the source of truth it claims to be.
data_fail=""
python3 - "$CONTROLS" "$FRAGMENT" <<'PY' || data_fail="$data_fail flag-name-is-in-the-kotlin"
import re, sys
for path in sys.argv[1:]:
    code = open(path, encoding='utf-8').read()
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    code = re.sub(r'//[^\n]*', '', code)
    if 'battery_hungry' in code or 'batteryHungry' in code:
        sys.exit(1)
sys.exit(0)
PY
# The generic mechanism that makes that possible: a group names the flag it
# gathers, and a control carries EVERY boolean it declared.
grep -q 'val flag = g.optString("derive")' "$CONTROLS" \
  || data_fail="$data_fail derive-is-not-read-from-the-declaration"
grep -q 'filter { o.opt(it) is Boolean }' "$CONTROLS" \
  || data_fail="$data_fail flags-are-a-fixed-list-not-whatever-was-declared"
# Nor may the fragment know which group is the derived one.
python3 - "$FRAGMENT" <<'PY' || data_fail="$data_fail fragment-special-cases-a-group"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
sys.exit(1 if re.search(r'group\.(id|derived)\s*==|"battery"', code) else 0)
PY
# PROOF IT IS ONLY DATA: adding the flag to a control that does not carry it
# must change what the declaration yields, with the Kotlin untouched.
python3 - "$BJ" <<'PY' || data_fail="$data_fail adding-the-flag-changes-nothing"
import copy, json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
def gather(cp):
    functional = [g for g in cp['groups'] if not g.get('derive')]
    out = []
    for g in cp['groups']:
        if g.get('derive'):
            out += [c['id'] for gg in functional for c in gg.get('controls', [])
                    if c.get(g['derive']) is True]
    return out
before = gather(cp)
after = copy.deepcopy(cp)
flag = next(g['derive'] for g in cp['groups'] if g.get('derive'))
for g in after['groups']:
    for c in g.get('controls', []):
        if not c.get(flag):
            c[flag] = True
            sys.exit(0 if gather(after) != before else 1)
sys.exit(1)   # every control already flagged — the check would prove nothing
PY
[ -z "$data_fail" ] \
  && ok "the flag lives only in build.json; a group gathers whatever flag it names" \
  || bad "the battery classification has leaked into Kotlin:$data_fail"

echo "== T23: a hold opens the menu and does NOT also switch the control =="
# THE DEFECT THAT REACHES THE OWNER. View.onTouchEvent runs performClick() on
# ACTION_UP only when mHasPerformedLongPress is false, and that flag is set
# ONLY by a performLongClick() that RETURNED TRUE. A long-press listener that
# returns false — or a tile with no long-press listener at all — therefore
# toggles the control on the way to opening its settings screen.
#
# NOT FIXED WITH A DEBOUNCE, and this keeps it that way. This app has already
# shipped a tile that fired twice (149 duplicates in one trace) and the fix was
# removing the second route, not swallowing the second event: a timestamp guard
# would hide the symptom and break a genuine fast double tap with it.
gest_fail=""
# Compose has no listener whose return value lets a tap through behind the hold: combinedClickable runs onClick only for a
# press that was NOT a long press. What can still go wrong is a SECOND route - a plain clickable()/pointerInput on the tile
# alongside it, which would fire the tap on the way out of the hold - so the tile has exactly one gesture modifier, and it is
# the combined one carrying both handlers.
python3 - "$FRAGMENT" <<'PY' || gest_fail="$gest_fail tile-gesture-is-not-one-combinedClickable"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
m = re.search(r'private fun Tile\(.*?\n    \}\n', code, re.S)
if not m: sys.exit(1)
body = m.group(0)
one = body.count('combinedClickable(') == 1 and 'onClick = {' in body and 'onLongClick = {' in body
second = re.search(r'(?<![A-Za-z])clickable\(|pointerInput\(|detectTapGestures', body)
sys.exit(0 if one and not second else 1)
PY
# Exactly ONE gesture path reaches a write. A second one is the double-dispatch
# shape, whatever it is spelled.
writes="$(grep -cE '^\s+(if \(control\.set != null\) )?write\(' "$FRAGMENT" || true)"
[ "$writes" = "1" ] || gest_fail="$gest_fail write-called-from-$writes-places"
# No debounce smuggled back in.
grep -qE 'lastClick|SystemClock.uptimeMillis\(\) - last|DEBOUNCE|CLICK_GAP' "$FRAGMENT" \
  && gest_fail="$gest_fail debounce-instead-of-one-route"
[ -z "$gest_fail" ] \
  && ok "the hold returns true, so the tap behind it never fires; one route to a write" \
  || bad "tap and hold are fighting:$gest_fail"

echo "== T24: no hold ever silently does nothing =="
# A long-press that produces no response is indistinguishable from a broken
# tile, an unregistered gesture and a frozen page — the exact failure shape
# this repository already carries a CI guard against elsewhere (the enhance
# silence guard). Six of the seventeen controls have no settings screen, so
# this is not hypothetical.
silent_fail=""
python3 - "$FRAGMENT" <<'PY' || silent_fail="$silent_fail a-hold-can-return-silently"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
m = re.search(r'private fun openOrSayThereIsNowhere\(.*?\n    \}', code, re.S)
if not m:
    sys.exit(1)
body = m.group(0)
# Both arms accounted for: one opens, the other says there is nowhere to open.
sys.exit(0 if 'if (open != null) open(' in body and 'snack(' in body else 1)
PY
grep -q 'onLongClick = {' "$FRAGMENT" || silent_fail="$silent_fail no-long-press-at-all"
python3 - "$FRAGMENT" <<'PY' || silent_fail="$silent_fail hold-bypasses-the-fallback"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'onLongClick = \{(.*?)\n                    \},', code, re.S)
sys.exit(0 if m and 'openOrSayThereIsNowhere(' in m.group(1) else 1)
PY
# DATA: at least one declared control really has nowhere to go, or the fallback
# is guarding a case that cannot happen.
python3 - "$BJ" "$CONTROLS" <<'PY' || silent_fail="$silent_fail every-control-has-a-menu-fallback-guards-nothing"
import json, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
src = open(sys.argv[2], encoding='utf-8').read()
ids = [(m.start(), m.group(1)) for m in
       re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
blocks = {cid: src[pos:(ids[i+1][0] if i+1 < len(ids) else len(src))]
          for i, (pos, cid) in enumerate(ids)}
declared = {c['id'] for g in cp['groups'] for c in g.get('controls', [])}
sys.exit(0 if any('open =' not in blocks.get(c, '') for c in declared) else 1)
PY
grep -q 'getString(R.string.control_no_menu' "$FRAGMENT" \
  || silent_fail="$silent_fail fallback-message-not-a-resource"
[ -z "$silent_fail" ] \
  && ok "a hold either opens the screen or says there is not one" \
  || bad "a hold can do nothing at all:$silent_fail"

echo "== T25: black-off / white-on is an INVERSION through theme tokens =="
# The owner asked for "icons black when off and white when on". Taken as two
# literals that is a Samsung LIGHT-theme description, and this app ships a
# Samsung-black Power Saving theme and a Minimalistic Black one — #000 on #000
# is an invisible grid. So it is implemented as the two theme roles SWAPPED:
# off is `surface` filled with `text_primary`, on is `text_primary` filled with
# `tile_ink`. Contrast is symmetric, so proving the off tile legible proves the
# on tile legible, on every theme, without a device.
check "$(python3 - "$BJ" "$COLORS" "$THEME_BG" "$FRAGMENT" <<'PY'
import json, re, sys
bj, colours_path, bg_path, fragment = sys.argv[1:5]
d = json.load(open(bj))['ui']
colours = dict(re.findall(r'<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>',
                          open(colours_path).read()))
window_stops = re.findall(r'olor="#([0-9A-Fa-f]{6,8})"', open(bg_path).read())

def argb(h):
    h = h[-8:] if len(h) == 8 else 'FF' + h
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)

def over(top, bottom):
    """Composite an alpha colour onto an opaque one — a 13%-white surface is
       not a colour until you say what is behind it."""
    a, r, g, b = argb(top); _, br, bg_, bb = argb(bottom)
    f = a / 255.0
    return 'FF%02X%02X%02X' % (round(r*f + br*(1-f)), round(g*f + bg_*(1-f)),
                               round(b*f + bb*(1-f)))

def lum(h):
    _, r, g, b = argb(h)
    def c(v):
        v /= 255.0
        return v/12.92 if v <= 0.03928 else ((v+0.055)/1.055) ** 2.4
    return 0.2126*c(r) + 0.7152*c(g) + 0.0722*c(b)

def ratio(x, y):
    a, b = sorted((lum(x), lum(y)), reverse=True)
    return (a + 0.05) / (b + 0.05)

problems = []
for theme in d['launcher_themes']:
    p = theme.get('palette') or {}
    missing = [r for r in ('tile_ink', 'text_primary', 'surface') if not p.get(r)]
    if missing:
        problems.append('%s declares no %s' % (theme['id'], ', '.join(missing)))
        continue
    if p['tile_ink'] not in colours or p['text_primary'] not in colours \
            or p['surface'] not in colours:
        problems.append('%s names a token that is not in colors.xml' % theme['id'])
        continue
    ink, fg, raw = colours[p['tile_ink']], colours[p['text_primary']], colours[p['surface']]
    # ON: text_primary fill, tile_ink icon. Both opaque, nothing behind them.
    on = ratio(fg, ink)
    if on < 4.5:
        problems.append('%s ON tile: %s icon on %s fill is %.2f:1'
                        % (theme['id'], p['tile_ink'], p['text_primary'], on))
    # OFF: text_primary icon on the surface fill, which may be translucent —
    # so composite it over whatever the theme's window actually paints.
    win = colours.get(p.get('window', ''), '')
    backs = [win] if win else (window_stops or ['000000'])
    for back in backs:
        off = ratio(fg, over(raw, back))
        if off < 4.5:
            problems.append('%s OFF tile: %s icon on %s over #%s is %.2f:1'
                            % (theme['id'], p['text_primary'], p['surface'], back, off))

# And the fragment must express it as the SWAP, not as two colours it chose.
code = re.sub(r'//[^\n]*', '', re.sub(r'/\*.*?\*/', '', open(fragment).read(), flags=re.S))
if 'if (on) p.textPrimary else p.surface' not in code:
    problems.append('the fill is not the text_primary/surface swap')
if 'if (on) p.tileInk else p.textPrimary' not in code:
    problems.append('the icon is not the tile_ink/text_primary swap')
print('; '.join(problems) or 'OK')
PY
)" "every theme inverts legibly: on and off are the same two tokens swapped"

echo "== T26: the tiles stay big enough to hit =="
# The counter-complaint to a denser grid, pinned before it arrives. Android's
# accessibility minimum is 48dp in both directions; a quick-settings grid below
# it is a grid that gets mis-tapped, and the column count is data precisely so
# the density can be argued with — which means the floor has to be asserted or
# one edit to build.json takes it away.
size_fail=""
python3 - "$FRAGMENT" "$BJ" <<'PY' || size_fail="$size_fail tile-below-the-touch-minimum"
import json, re, sys
code = open(sys.argv[1], encoding='utf-8').read()
def const(name):
    m = re.search(r'private const val %s = (\d+)' % name, code)
    return int(m.group(1)) if m else 0
# The TILE (badge + label column) is the 48dp touch target; the badge is only the icon's
# plate, at most 8dp wider than the icon (the owner's "white box too big", 2026-10-17).
ok = (const('TOUCH_TARGET_DP') >= 48
      and 0 < const('ICON_DP') < const('BADGE_DP') <= const('ICON_DP') + 8)
columns = json.load(open(sys.argv[2]))['ui']['control_panel'].get('columns', 0)
# 360dp is the narrowest phone this app targets; below ~64dp a column stops
# leaving room for a tile's 48dp touch floor plus its gutters.
sys.exit(0 if ok and 2 <= columns <= 5 and 360 / columns >= 64 else 1)
PY
grep -q 'heightIn(min = TOUCH_TARGET_DP.dp)' "$FRAGMENT" \
  || size_fail="$size_fail no-minimum-height-on-the-tile"
grep -q 'widthIn(min = TOUCH_TARGET_DP.dp)' "$FRAGMENT" \
  || size_fail="$size_fail no-minimum-width-on-the-tile"
[ -z "$size_fail" ] \
  && ok "tile floor and column count clear the 48dp target; badge hugs the icon" \
  || bad "the grid is denser than it is tappable:$size_fail"

echo "== T27: Battery Hungers hands off to a MEASURED per-app list, and says its unit =="
# THE LIE THIS PREVENTS. "Battery Hungers" over the tiles alone is a group of
# CONTROLS chosen by a human writing `battery_hungry: true` — an opinion about
# mechanism, not a measurement, and not an answer to "which apps are eating my
# battery". The owner asked that second question. It is answered by a hand-off
# to libs:battery's EnergyUsageDialog, and the whole risk is that the LABEL and
# the NUMBERS BEHIND IT drift apart: a link promising mAh over a screen-time
# list is precisely the failure that would be believed and acted on.
#
# So the label's unit and the producing code's unit are asserted TOGETHER. Each
# half alone is worthless — a label naming mAh proves nothing about the data,
# and a function returning mAh proves nothing about what the owner was told.
DIALOG="$LIBS/libs/battery/src/main/java/com/diegonmarcos/superapp/battery/EnergyUsageDialog.kt"
WATCHDOG="$LIBS/libs/battery/src/main/java/com/diegonmarcos/superapp/battery/EnergyWatchdog.kt"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"
for f in "$DIALOG" "$WATCHDOG" "$STRINGS_ES"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f — the hand-off cannot be"
                   echo "         checked, and absence must not read as a pass."; exit 2; }
done

details_fail=""

# (a) the derived group DECLARES the hand-off — data, not a Kotlin special case
#     keyed off the group id. Found by `derive`, never by index.
decl="$(python3 - "$BJ" <<'PY2'
import json, sys
groups = json.load(open(sys.argv[1]))['ui']['control_panel']['groups']
d = [g for g in groups if g.get('derive')]
if len(d) != 1: print('expected exactly one derived group, got %d' % len(d)); raise SystemExit
g = d[0]
if not g.get('details'):     print('the derived group declares no `details` surface')
elif not g.get('details_res'): print('`details` with no `details_res` label key')
else: print('OK %s %s' % (g['details'], g['details_res']))
PY2
)"
case "$decl" in
  "OK "*) surface="$(echo "$decl" | awk '{print $2}')"
          res="$(echo "$decl" | awk '{print $3}')" ;;
  *) details_fail="$details_fail declaration:[$decl]"; surface=""; res="" ;;
esac

# (b) the declared surface is one the fragment actually implements. A name
#     nobody maps draws NOTHING, so a typo here is a silently missing feature.
if [ -n "$surface" ]; then
  grep -q "DETAILS_ENERGY_USAGE = \"$surface\"" "$FRAGMENT" \
    || details_fail="$details_fail fragment-implements-no-surface-named-$surface"
fi

# (c)+(d) THE ANTI-LIE PAIR. The label exists in BOTH locales and BOTH name the
#     unit; a Spanish reader must not be shown a unit-less promise.
if [ -n "$res" ]; then
  for loc in "$STRINGS" "$STRINGS_ES"; do
    line="$(awk -v k="name=\"$res\"" 'index($0,k)' "$loc")"
    [ -n "$line" ] || { details_fail="$details_fail no-$res-in-$(basename "$(dirname "$loc")")"; continue; }
    case "$line" in
      *mAh*) : ;;
      *) details_fail="$details_fail $(basename "$(dirname "$loc")")-label-names-no-unit" ;;
    esac
  done
fi

# (e) ...and the DATA really is mAh, ranked worst-first. This is the half that
#     makes the label a fact rather than a claim.
grep -q 'mAh = avgMa \* ms / 3_600_000.0' "$WATCHDOG" \
  || details_fail="$details_fail watchdog-no-longer-computes-mAh"
grep -q 'sortedByDescending { it.mAh }' "$WATCHDOG" \
  || details_fail="$details_fail per-app-list-not-ranked-descending-by-mAh"

# (f) A LINK, NOT A SECOND COPY. The fragment must reach the dialog and must
#     not grow per-app energy arithmetic of its own — a duplicate ranking here
#     is the drift this hand-off exists to avoid.
grep -q 'EnergyUsageDialog().show(parentFragmentManager, EnergyUsageDialog.TAG)' "$FRAGMENT" \
  || details_fail="$details_fail fragment-does-not-open-the-dialog"
# ...and the check reads CODE, not prose: this file's own comments discuss mAh
# at length, and a grep that cannot tell a sentence from a call would fail on
# the documentation explaining why the arithmetic is elsewhere.
frag_code="$(awk '{ t=$0; sub(/^[ \t]+/,"",t)
                    if (t !~ /^\*/ && t !~ /^\/\// && t !~ /^\/\*/) print }' "$FRAGMENT")"
case "$frag_code" in
  *UsageStatsManager*|*perAppEstimate*|*mAh*)
    details_fail="$details_fail fragment-computes-its-own-per-app-energy" ;;
esac

# (g) THE #228 RULE, AGAIN. The hand-off is a link to a page; it has no state,
#     so it must not be given a light. A light here would be a status indicator
#     driven by nothing — the exact shape of the private copies #228 removed.
det_body="$(awk '/private fun DetailsLink/,/^    companion object/' "$FRAGMENT")"
[ -n "$det_body" ] || details_fail="$details_fail DetailsLink-not-found"
case "$det_body" in
  *StatusLight*) details_fail="$details_fail details-link-carries-a-status-light" ;;
esac

[ -z "$details_fail" ] \
  && ok "the hand-off is declared, implemented, labelled with its unit in both locales, and the unit is what the code computes" \
  || bad "Battery Hungers' hand-off is wrong:$details_fail"

echo "== T28: ui.notification_center reaches the APK — emitter, constant, reader =="
np_fail=""
grep -q 'buildJson.ui.notification_center' "$GRADLE"          || np_fail="$np_fail gradle:no-json-read"
grep -q 'uiNotificationCenterB64' "$GRADLE"                   || np_fail="$np_fail gradle:no-b64"
grep -q 'UI_NOTIFICATION_CENTER_B64' "$GRADLE"                || np_fail="$np_fail gradle:no-buildconfig"
grep -q 'BuildConfig.UI_NOTIFICATION_CENTER_B64' "$BADGESVC"  || np_fail="$np_fail kotlin:not-read"
grep -q 'BadgeDeclaration.parse' "$BADGESVC"                  || np_fail="$np_fail kotlin:not-parsed"
grep -q 'BadgeServices.declared' "$PUSH"                      || np_fail="$np_fail kotlin:pane-not-fed"
[ -z "$np_fail" ] \
  && ok "build.json → uiNotificationCenterB64 → BuildConfig.UI_NOTIFICATION_CENTER_B64 → BadgeServices → BadgePanes" \
  || bad "the declaration cannot reach the UI:$np_fail"

echo "== T29: the badge panes render the DECLARATION, not a hardcoded list of three =="
# THE DECORATIVE-TAB CHECK. Diego named three badges and trailed off — "…." —
# on purpose, which is only honoured if BadgePanes iterates whatever
# build.json::ui.notification_center carries rather than switching on three
# known ids. A tab that renders the same regardless of what the declaration
# says is decorative, which is exactly the #341/#344 empty-bake shape.
decorative_fail=""
grep -q 'optJSONArray("producers")' "$BADGEDECL" \
  || decorative_fail="$decorative_fail no-array-iteration"
# #515/#518: the pane draws BOTH of its sections by walking the resolved badge
# list. A pane that stopped doing that would be back to naming badges itself.
grep -q 'BadgeDeclaration.badges' "$PUSH" \
  || decorative_fail="$decorative_fail pane-does-not-derive"
# No per-producer id may be switched on in the renderer — every id this file
# names may appear only in a *comment*, never as a live `==` branch, or a
# fourth producer added to the JSON would render nothing until Kotlin catches
# up, exactly the drift #497 exists to forbid.
python3 - "$BJ" "$PUSH" <<'PY' || decorative_fail="$decorative_fail id-switched-on-in-kotlin"
import json, re, sys
producers = json.load(open(sys.argv[1]))['ui']['notification_center']['producers']
code = open(sys.argv[2], encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
bad = [p['id'] for p in producers
       if re.search(r'"%s"\s*(==|->)' % re.escape(p['id']), code)
       or re.search(r'(==|\bif\s*\()\s*"%s"' % re.escape(p['id']), code)]
sys.exit(1 if bad else 0)
PY
# PROOF, not assertion: dropping one producer from the JSON must shrink the
# list BadgePanes would build from BadgeDeclaration.badges() — computed by walking the
# SAME optJSONArray/optString/optBoolean shape the Kotlin uses, not restated
# by hand, so a rewrite of the parser is what this catches, not just word
# choice.
python3 - "$BJ" <<'PY' || decorative_fail="$decorative_fail removing-a-producer-does-not-shrink-the-render"
import json, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
def rendered(producers):
    # Mirrors BadgeDeclaration.parse + .badges: a producer with no id or no
    # label is a parse error, and only badge=true reaches the pane. #515 —
    # the other producers are still DECLARED, they are just not badges.
    out = []
    for o in producers:
        pid, label = o.get('id', ''), o.get('label', '')
        if not pid or not label: continue
        if not o.get('badge'): continue
        out.append(pid)
    return out
badges = [p for p in nc['producers'] if p.get('badge')]
before = rendered(nc['producers'])
after = rendered([p for p in nc['producers'] if p is not badges[0]])
sys.exit(0 if len(before) > 0 and len(after) == len(before) - 1 else 1)
PY
[ -z "$decorative_fail" ] \
  && ok "BadgePanes iterates the declared array; no producer id is a live branch; removing one shrinks the render" \
  || bad "the badge panes are decorative, not declaration-driven:$decorative_fail"

echo "== T30: every declared producer names a REAL owner file and a resolvable icon =="
# An id pointing at a file that does not exist is the DeviceControls failure
# mode restated for Push: a row that draws and means nothing, because the
# capability it claims to summarise is not where it says it is.
owner_fail=""
check "$(python3 - "$BJ" "$APP" "$APP/app/src/main/res/drawable" <<'PY'
import json, os, sys
nc = json.load(open(sys.argv[1]))['ui']['notification_center']
app_src = os.path.join(sys.argv[2], 'app/src/main/java/com/diegonmarcos/superapp')
lib_src = os.path.join(sys.argv[2], '..', 'ab_cloud-libs-shared')
drawables = sys.argv[3]
problems = []
for p in nc['producers']:
    pid = p.get('id', '<no id>')
    owner = p.get('owner', '')
    if not owner:
        problems.append('%s: no owner declared' % pid); continue
    if not (os.path.exists(os.path.join(app_src, owner)) or
            os.path.exists(os.path.join(lib_src, owner))):
        problems.append('%s: owner %r does not exist' % (pid, owner))
    icon = p.get('icon', '')
    if not icon:
        problems.append('%s: no icon declared' % pid)
    elif not any(os.path.exists(os.path.join(drawables, icon + ext))
                 for ext in ('.xml', '.png', '.webp')):
        problems.append('%s: icon %r is not a drawable' % (pid, icon))
    if not p.get('subtitle'):
        problems.append('%s: no subtitle' % pid)
print('; '.join(problems) or 'OK')
PY
)" "every producer names an owner file that exists and an icon that resolves"

echo "== T31: Notify leads with the badge cards; every control (Filters + Configs) is ONE folded Settings section at the very bottom (#580, #1068) =="
# The merge is declared, not coded: two stack_my-rss panels, one flagged
# above_filters and one collapsed. The Kotlin only has to honour the flags, in
# the order the page reads: above-filter panels, the Filters, the rest.
nb_fail=""
python3 - "$BJ" <<'PY' || nb_fail="$nb_fail declaration"
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
comm = next(s for s in ui['sections'] if s['id'] == 'communication')
# #1672 (owner 2026-11-12): Notify is sub-pages. The full badge cards are the Persistent Badges page
# (stack_notify-badges, above_filters), All shows them as a one-line summary (stack_my-rss, summary), and the
# badge Configs are the Configs page (stack_notify-configs, bottom) - on no other page.
stacks = {k: v for k, v in comm.items() if k.startswith('stack_') and isinstance(v, list)}
def first(k, kind): return next((p for p in stacks.get(k, []) if p['kind'] == kind), None)
badges  = first('stack_notify-badges', 'notification_badges')
summary = first('stack_my-rss', 'notification_badges')
configs = first('stack_notify-configs', 'notification_badge_configs')
bad = []
if badges is None:  bad.append('no notification_badges panel in stack_notify-badges')
elif not badges.get('above_filters'): bad.append('the badges panel is not above_filters, so it would sit under the Filters')
if summary is None or not summary.get('summary'): bad.append('All (stack_my-rss) does not summarise the badges on top')
if configs is None: bad.append('no notification_badge_configs panel in stack_notify-configs')
elif not configs.get('bottom'):        bad.append('the Configs panel is not flagged bottom')
elif configs.get('above_filters'):     bad.append('the Configs panel is above the cards')
for k, v in stacks.items():
    if k not in ('stack_my-rss', 'stack_notify-badges') and any(p.get('kind') == 'notification_badges' for p in v):
        bad.append('%s also declares a badge panel' % k)
    if k != 'stack_notify-configs' and any(p.get('kind') == 'notification_badge_configs' for p in v):
        bad.append('%s also declares the badge Configs' % k)
order = [p['id'] for p in comm['pages'] if not p.get('hidden')]
if order != ['my-rss', 'notify-badges', 'notify-cloudy', 'notify-phone', 'notify-configs']:
    bad.append('Notify sub-pages are %s' % order)
for p in (badges, configs):
    if p and not p.get('title_res'): bad.append('%s has no title_res' % p['kind'])
for msg in bad: print('  ' + msg)
sys.exit(1 if bad else 0)
PY
grep -q 'aboveFilters *= p.optBoolean("above_filters"' "$SECTIONS" || nb_fail="$nb_fail Sections:above_filters-not-parsed"
grep -q 'bottom *= p.optBoolean("bottom"' "$SECTIONS" || nb_fail="$nb_fail Sections:bottom-not-parsed"
python3 - "$AGG" "$AGGPANELS" <<'PY' || nb_fail="$nb_fail AggregatorStackFragment:order"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
panels = open(sys.argv[2], encoding='utf-8').read()
try:
    a = s.index('for ((i, panel) in above)')
    f = s.index('ConfigSection(st')
    b = s.index('for ((i, panel) in below)')
except ValueError as e:
    print('  missing marker: %s' % e); sys.exit(1)
if not (a < b < f):
    print('  render order is not above-panels, other panels, then the Settings section last'); sys.exit(1)
if 'FilterRow(st)' in s:
    print('  the Filters are drawn on the page again; they belong inside ConfigSection'); sys.exit(1)
if 'it.value.aboveFilters' not in s:
    print('  the page no longer partitions on aboveFilters'); sys.exit(1)
for kind, r in (('notification_badges', r'BadgeCards\(\)'), ('notification_badge_configs', r'BadgePane')):
    if not re.search(r'"%s"\s*->\s*%s' % (kind, r), panels):
        print('  panel kind %s has no renderer' % kind); sys.exit(1)
PY
[ -z "$nb_fail" ] \
  && ok "#1672 Notify sub-pages: badge cards on Persistent Badges, a summary on All, Configs only on the Configs page" \
  || bad "Notify's badge/Configs layout is not what #580 declared:$nb_fail"

echo "== T32: the badge panes are VIEWS — they post, clear and store nothing (#497/#515 single centre) =="
# BadgePanes may LAUNCH a badge's own owner (that is a button on a producer that
# already exists) but must not be a producer itself: no NotificationManager
# notify/cancel, no NotificationStore write, no channel of its own.
vw_fail=""
code="$(grep -v -E '^[[:space:]]*(//|\*|/\*)' "$PUSH")"
printf '%s\n' "$code" | grep -q -E 'NotificationManager|\.notify\(|cancelAll|NotificationStore|createNotificationChannel' \
  && vw_fail="$vw_fail posts-or-clears-notifications"
printf '%s\n' "$code" | grep -q 'BadgeServices.view(' || vw_fail="$vw_fail no-BadgeServices.view"
[ -z "$vw_fail" ] \
  && ok "BadgePanes draws through BadgeServices and never notifies, cancels or stores" \
  || bad "the badge panes became a producer:$vw_fail"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
