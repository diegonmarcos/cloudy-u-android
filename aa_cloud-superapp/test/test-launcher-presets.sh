#!/usr/bin/env bash
# #574 — Configs ▸ Launcher ▸ Presets (was Profiles) / Controls (was Modes,
# merged with Panel ▸ Control) and Panel → Home.
#
# WHY THIS EXISTS: the change is mostly NAMES, and a wrong name fails silently —
# the app builds, the tab draws, it just says the old word. So every name the
# owner asked for is pinned here, read from the ONE declaration (build.json),
# and the Kotlin is checked to draw from it rather than carry a copy:
#
#   T1  the Launcher strip wears Presets · Quick Actions · Folders · One-Hand · Notify, by label (#1550: Folders before One-Hand, Notify right after One-Hand per #580/#649)
#   T2  Presets declares exactly Sandboxes · Themes · Modes, in that order
#   T3  each group's rows are the owner's, in the owner's order, and no row's
#       caption is its own id (#380: an identity string is not a caption)
#   T4  a mode only names switches that exist and that a mode may write
#   T5  Panel is Home everywhere user-visible; nothing is still called Panel,
#       Profiles or Modes at PAGE level, and no page `control` survives
#   T6  the Presets fragment names nothing itself and owns no engine: themes go
#       through LauncherThemes.apply, modes through LauncherThemes.applyToggles
#   T7  the declarations reach the APK (gradle → BuildConfig → reader)
#   T8  Controls has ONE producer: LauncherConfigFragment, hosting ControlFragment
#
# Static tester (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

command -v python3 >/dev/null 2>&1 || { echo "  ABORT: python3 missing"; exit 2; }
for f in "$BJ" "$KT/settings/LauncherPresetsFragment.kt" "$KT/settings/LauncherModes.kt" \
         "$KT/settings/LauncherConfigFragment.kt" "$KT/launcher/SectionPages.kt" \
         "$APP/app/build.gradle"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f"; exit 2; }
done

echo "== T1: the Launcher group is Controls (Presets | Panels ▸ UI | Tools) | One-Hand | Notify (#580, #649) =="
# Asserted by LABEL, which is what the owner reads on the screen, and the four
# labels are unchanged across #649 — it moved them from a tab strip to four
# direct tiles under a `Launcher` heading, which is the same four words in the
# same order in a different shape. The heading itself is asserted too: "Launcher"
# is a declared string, so a Kotlin literal quietly supplying it would leave this
# group unnamed in the file and still look right on the phone.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages  = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
              if s['id'] == 'config')['pages']
by     = {p['id']: p for p in pages}
labels = [p['label'] for p in pages if p.get('subgroup') == 'Launcher']
tabs   = [by[i]['label'] for i in by['controls'].get('tabs', [])]
sub    = [by[i]['label'] for i in by['panels'].get('tabs', [])]
print('OK' if (labels, tabs, sub) == (['Quick Actions', 'Folders', 'One-Hand', 'Notify'], ['Presets', 'Panels'], ['UI', 'Tools'])
      else 'Launcher labels = %r, Controls tabs = %r, Panels tabs = %r' % (labels, tabs, sub))
PY
)" "the Launcher group, by label, is Quick Actions (Presets | Panels ▸ UI | Tools) · Folders · One-Hand · Notify"

echo "== T2: Presets declares Sandboxes · Themes · Modes, in order =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))['ui']['launcher_presets']['groups']
got = [(x['label'], x['kind']) for x in g]
want = [('Sandboxes', 'profile'), ('Themes', 'theme'), ('Modes', 'mode')]
print('OK' if got == want else 'groups = %r' % (got,))
PY
)" "ui.launcher_presets.groups = Sandboxes(profile) · Themes(theme) · Modes(mode)"

echo "== T3: the rows of each group, in the owner's order, none captioned by id =="
check "$(python3 - "$BJ" <<'PY'
import json, re, sys
ui = json.load(open(sys.argv[1]))['ui']
feeds = {'profile': ('launcher_profiles', ['Work', 'Personal', 'Guest']),
         'theme':   ('launcher_themes',   ['Cloud Purple', 'Cloud Minimalistic']),
         'mode':    ('launcher_modes',    ['Power Mode', 'Focus Mode', 'Saving Energy Mode', 'Super Saving Battery Mode'])}
problems = []
for g in ui['launcher_presets']['groups']:
    key, want = feeds[g['kind']]
    rows = ui[key]
    got = [r['label'] for r in rows]
    if got != want: problems.append('%s labels = %r' % (key, got))
    for r in rows:
        if r['label'] == r['id'] or re.fullmatch(r'[a-z0-9_]+', r['label']):
            problems.append('%s/%s: caption is an identity string' % (key, r['id']))
print('; '.join(problems) or 'OK')
PY
)" "Work·Personal·Guest / Cloud Purple·Cloud Minimalistic / Power·Focus·Saving Energy·Super Saving Battery Mode"

echo "== T4: a mode names only real switches it may write =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
toggles = {t['id']: t for t in ui['launcher_settings']['toggles']}
problems = []
for m in ui['launcher_modes']:
    if not m.get('toggles') and not m.get('engine'): problems.append('%s names no switch' % m['id'])
    for k in m.get('toggles', {}):
        if k not in toggles:            problems.append('%s: unknown switch %s' % (m['id'], k))
        elif toggles[k].get('user_owned'):
                                        problems.append('%s: writes user-owned %s' % (m['id'], k))
print('; '.join(problems) or 'OK')
PY
)" "every mode toggle id is a declared, non-user-owned launcher switch"

echo "== T5: Panel was Home (#574) and Home is now deleted (#580); no retired name survives at page level =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
# #580: Home's two tabs (Push, Notify) merged into Launcher ▸ Notify, so the
# page is gone rather than renamed again.
for retired in ('panel', 'control', 'profiles', 'theme', 'home', 'push'):
    if retired in by_id: problems.append('page %r is still declared' % retired)
for p in pages:
    if p['label'] in ('Panel', 'Profiles', 'Modes'):
        problems.append('page %s is still labelled %s' % (p['id'], p['label']))
print('; '.join(problems) or 'OK')
PY
)" "panel, control, profiles, theme, home, push are gone; no page label is Panel/Profiles/Modes"

echo "== T6: Presets draws the declaration and owns no engine =="
PRE="$KT/settings/LauncherPresetsFragment.kt"
lit="$(grep -n -E '"(Work|Personal|Guest|Cloud Purple|Cloud Minimalistic|Power Mode|Focus Mode|Saving Energy Mode|Sandboxes|Themes|Modes)"' "$PRE" "$KT/settings/LauncherModes.kt" | grep -v -E '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' || true)"
[ -z "$lit" ] && ok "no preset or group name is a Kotlin string literal" \
  || bad "a name is written in Kotlin instead of build.json: $lit"
grep -q 'LauncherPresets.groups()' "$PRE" && grep -q 'LauncherModes.load()' "$PRE" \
  && grep -q 'LauncherThemes.loadFromBuildConfig()' "$PRE" && grep -q 'LauncherProfiles.loadFromBuildConfig()' "$PRE" \
  && ok "groups and rows come from the four declaration readers" \
  || bad "Presets does not read one of the declarations"
grep -q 'LauncherThemes.apply(ctx' "$PRE" \
  && ok "a theme pick goes through the existing LauncherThemes.apply" \
  || bad "the theme pick bypasses LauncherThemes.apply (a second engine)"
grep -q 'LauncherThemes.applyToggles(ctx, mode.toggles)' "$KT/settings/LauncherModes.kt" \
  && ok "a mode pick goes through the theme engine's applyToggles" \
  || bad "modes carry their own switch-writing engine"
grep -q 'LauncherSettingsPrefs' "$PRE" \
  && bad "the Presets fragment writes switches itself" \
  || ok "the Presets fragment never touches LauncherSettingsPrefs directly"

echo "== T7: the declarations reach the APK =="
G="$APP/app/build.gradle"
miss=""
for k in UI_LAUNCHER_MODES_B64:launcher_modes UI_LAUNCHER_PRESETS_B64:launcher_presets; do
  c="${k%%:*}"; j="${k##*:}"
  grep -q "buildJson.ui.$j" "$G" || miss="$miss $j:gradle-read"
  grep -q "buildConfigField \"String\", \"$c\"" "$G" || miss="$miss $c:no-field"
  grep -rq "BuildConfig.$c" "$KT/settings" || miss="$miss $c:not-read"
done
[ -z "$miss" ] && ok "build.json → gradle → BuildConfig → reader, for modes and presets" \
  || bad "declaration does not reach the app:$miss"

echo "== T8: Controls has ONE producer =="
grep -q 'pageId == "panels-ui" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI)' "$KT/launcher/SectionPages.kt" \
  && grep -q 'pageId == "panels-tools" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS)' "$KT/launcher/SectionPages.kt" \
  && ok "Panels ▸ UI and Tools → LauncherConfigFragment (two scopes, one producer)" || bad "Panels UI/Tools are not routed to LauncherConfigFragment"
grep -q 'ControlFragment.newInstance(embedded = true)' "$KT/settings/LauncherConfigFragment.kt" \
  && ok "…which hosts the Samsung-style grid (ControlFragment) as a child" \
  || bad "the switch board is not hosted by the Controls tab"
n="$(grep -rl 'configs.ControlFragment.newInstance' "$KT" | wc -l)"
[ "$n" = "1" ] && ok "exactly one call site builds the grid" || bad "$n call sites build the grid (want 1)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
