#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #603 — the chrome is DECLARED and NATIVE: tabs, pages, sections, places, ║
# ║ and icons come from build.json::ui; the shell is Compose over the fleet  ║
# ║ island; StatusLight is the superapp's idiom; no caption, colour or tab   ║
# ║ id lives in Kotlin; the WebView page and its string-named bridge are gone ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The #579 verdict on the old app was not a bug report, it was a
# rejection of the whole surface. Gradle proves a Compose tree compiles; it cannot
# see that a declared tab has no dispatch (the island draws it, a tap shows the
# "nothing declared" state), that an icon name silently fell back to the default,
# that a caption was typed into a screen instead of the string table, or that
# the StatusLight green here is not the green superapp promises. This file can.
#
#   D1  ui.bottom_nav declared, unique, default_section among them; the ids Kotlin dispatches
#       (MainActivity `when (tabId)`) equal the declared ids in both directions.
#   D2  ui.sections[configs].pages ↔ ConfigsScreen's `when (id)`, both directions.
#   D2s #1149 ui.sections[git].pages (Me, Cloud) ↔ GitPageScreen's `when (id)` in SyncScreen.kt, both directions, and the
#       per-repository git periods (moved here from ui.configs at #609).
#   D3  every declared icon name (tabs, pages, sections, places, filters, default) is
#       a name in IconCatalog's `when`; the catalog has no dead name either.
#   D4  the declarations are BAKED (build.gradle → UI_*_B64 from buildJson.ui with
#       a hard error on a missing block) and decoded once (Declarations.kt).
#   D5  libs:bottomnav is declared, depended on, linked, and DriveShell draws
#       NavBarBottomHost (libs:ui-nav-bars: the bar, its collapse and its insets) with the declared entries.
#   D6  the WebView shell is gone: no drive.html, no tailwind.js, no FilesBridge,
#       no Material Views widget, no WebView and no JavascriptInterface at all; no
#       AppCompatActivity anywhere (2026-10-15 owner decision: all apps must be Compose,
#       so #577's pdfium reader is a ComponentActivity with Compose chrome too).
#   D7  no colour literal and no caption literal in the chrome's Kotlin; every
#       R.string the chrome names exists, and no string is dead.
#   D8  StatusLight: four states, three glyph shapes, colours and words with the
#       superapp's names — and the SAME values as the superapp's when the sibling
#       tree is present (UNVERIFIABLE when it is not, never a silent pass).
#   D9  dark is the default: Theme.Material3.Dark parent + DriveTheme darkColorScheme.
#   D10 the JVM suite and the Robolectric layout-tree test exist and name the tags.
#   D11 #603/#609 THE STRUCTURE THIS REDESIGN IS: exactly five tabs in the order
#       Files · Volumes · Home · Sync · Configs, no `backups` TAB; Sync's three
#       sub-pages in the order Git · Rclone · Mounts; Configs' three sub-pages in
#       the order Backups · General · Others; the Files navigator's two declared
#       sections with the store in its own one; the store's seed set is PUBLIC
#       repositories only, cloned shallow through libs:git-sync; the Apps grid
#       carries GitSync and RSync as in-app routes into declared Sync pages; and
#       the dark theme provides LocalContentColor so no Text() inherits
#       material3's Color.Black default.
#   D12 #604 THE VOLUMES TAB IS FOUR DECLARED CLASSES and Rclone IS Rsync: exactly
#       four ui.volumes.classes in the order Cloud-Constellation · Cloud-Containers ·
#       Cloud-Machines · S3, dispatched by VolumesScreen in both directions, with the
#       filters each class uses (machine_kinds, s3_remote_types, constellation_path)
#       declared too; the Configs sub-page keeps the id `rclone` and is LABELLED Rsync;
#       the Apps grid's RSync tile routes to {configs, rclone}, not Backups; every
#       declared folder↔folder rule maps onto a libs:rclone job and the DEFAULT one is
#       /ya_mnt_sync-a37 ⇄ a declared Google-Drive remote, manual; and no declared
#       connection falls between Cloud-Containers and Cloud-Machines.
#   M   mutation-proof: a tab dropped from the declaration and a glyph dropped from
#       the catalog both turn D1 / D3 red; a private repository marked for seeding
#       turns D11 red; a dropped volume class, an RSync tile routed back to Backups, a
#       default rule that lost its folder and a connection with an unrendered machine
#       kind each turn D12 red; the unmutated tree stays green.
#
# OWN-SOURCE ONLY except D8's cross-app comparison, which reports UNVERIFIABLE
# when aa_cloud-superapp is not beside this app. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAIN="$SRC/MainActivity.kt"
SHELL_KT="$SRC/ui/DriveShell.kt"
CONFIGS="$SRC/configs/ConfigsScreen.kt"
SYNC_KT="$SRC/sync/SyncScreen.kt"
CATALOG="$SRC/ui/IconCatalog.kt"
DECL="$SRC/Declarations.kt"
LIGHT="$SRC/ui/StatusLight.kt"
THEME="$SRC/ui/DriveTheme.kt"
COLORS="$APP/app/src/main/res/values/colors.xml"
STRINGS="$APP/app/src/main/res/values/strings.xml"
THEMES="$APP/app/src/main/res/values/themes.xml"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"
REPOS="$APP/data/drive-git-repos.json"
APPS_JSON="$APP/data/drive-apps.json"
SEED="$SRC/StoreSeed.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$GRADLE" "$MAIN" "$SHELL_KT" "$CONFIGS" "$SYNC_KT" "$CATALOG" "$DECL" "$LIGHT" "$THEME" "$COLORS" "$STRINGS" "$THEMES" "$REPOS" "$APPS_JSON" "$SEED"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks, as functions of their inputs, so the mutation block can call them on copies ──

# d1 <build.json> <MainActivity.kt> : declared tab ids == dispatched tab ids
d1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); main = open(sys.argv[2], encoding="utf-8").read()
secs = {s.get("id"): s for s in bj.get("ui", {}).get("sections", [])}
ids = bj.get("ui", {}).get("bottom_nav", [])
tabs = [secs.get(i, {}) for i in ids]
if not ids or len(set(ids)) != len(ids) or any(not i for i in ids) or any(i not in secs for i in ids): print("    ui.bottom_nav missing, empty, not unique or naming a non-section"); sys.exit(1)
if bj["ui"].get("default_section") not in ids: print("    ui.default_section is not a declared tab"); sys.exit(1)
if any(not t.get("label") or not t.get("icon") for t in tabs): print("    a tab lacks label or icon"); sys.exit(1)
m = re.search(r"when \(tabId\) \{(.*?)\n\s*\}", main, re.S)
if not m: print("    MainActivity has no `when (tabId)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z_]+)" ->', m.group(1), re.M)
# #1149 the island holds the bottom_nav sections; the top bar opens the other two (AI, Configs). Every section is dispatched, none twice.
extra = sorted(set(secs) - set(ids))
if extra != ["ai", "configs"]:
    print("    sections outside ui.bottom_nav are %s, not exactly the two the top bar opens (ai, configs)" % extra); sys.exit(1)
if sorted(dispatched) != sorted(secs):
    print("    declared %s vs dispatched %s" % (sorted(secs), sorted(dispatched))); sys.exit(1)
PYTHON
}

# d2 <build.json> <ConfigsScreen.kt>
d2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); src = open(sys.argv[2], encoding="utf-8").read()
ids = [p.get("id") for p in next(s for s in bj["ui"]["sections"] if s["id"] == "configs")["pages"]]
m = re.search(r"when \(id\) \{(.*?)\n\s*\}", src, re.S)
if not m: print("    ConfigsScreen has no `when (id)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z_]+)" ->', m.group(1), re.M)
if sorted(dispatched) != sorted(ids): print("    declared %s vs dispatched %s" % (ids, dispatched)); sys.exit(1)
PYTHON
}

# d2s <build.json> <SyncScreen.kt> : #1149 the Git strip (Me, Cloud), the mirror of d2
d2s() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); src = open(sys.argv[2], encoding="utf-8").read()
ids = [p.get("id") for p in next(s for s in bj["ui"]["sections"] if s["id"] == "git")["pages"]]
m = re.search(r"when \(id\) \{(.*?)\n\s*\}", src, re.S)
if not m: print("    GitPageScreen has no `when (id)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z_]+)" ->', m.group(1), re.M)
if sorted(dispatched) != sorted(ids): print("    declared %s vs dispatched %s" % (ids, dispatched)); sys.exit(1)
periods = bj["ui"]["sync"].get("git_periods_minutes", [])
if not periods or any(p < 15 for p in periods): print("    git_periods_minutes missing or below WorkManager's 15-minute floor"); sys.exit(1)
PYTHON
}

# d3 <build.json> <IconCatalog.kt> : every declared icon is a catalog name; no dead catalog name
d3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); cat = open(sys.argv[2], encoding="utf-8").read()
ui = bj["ui"]
declared = set(s["icon"] for s in ui["sections"]) | set(p["icon"] for s in ui["sections"] for p in s.get("pages", [])) | set(q["icon"] for s in ui["sections"] for p in s.get("pages", []) for q in p.get("pages", [])) | set(x["icon"] for x in ui["files"]["sections"]) | set(p["icon"] for p in ui["files"]["places"]) | set(f["icon"] for f in ui["files"]["filters"]) | {ui["icons"]["_default"]}
block = re.search(r"fun vector\(name: String\): ImageVector\? = when \(name\) \{(.*?)\n    \}", cat, re.S)
if not block: print("    IconCatalog.vector has no `when (name)`"); sys.exit(1)
known = set(re.findall(r'^\s*"([a-z_]+)" -> Icons\.', block.group(1), re.M))
missing = sorted(declared - known)
if missing: print("    declared icons unknown to IconCatalog: %s" % missing); sys.exit(1)
if len(known) < len(declared): print("    catalog smaller than the declaration"); sys.exit(1)
PYTHON
}

echo "── D1 tabs: declared ↔ dispatched ──"
d1 "$BJ" "$MAIN" && pass "ui.bottom_nav sections are unique, labelled, iconed; MainActivity dispatches exactly them" || fail "tabs: declaration and dispatch disagree"
if grep -qE 'BuildConfig\.UI_DEFAULT_SECTION' "$DECL" && grep -qE 'Declarations\.defaultTab' "$SHELL_KT"; then pass "the default tab is the declared one"; else fail "default tab not read from the declaration"; fi
# #1300 the owner's rule: the app opens on the Git page, declared (ui.default_section), never a restored last page
if [ "$(jq -r '.ui.default_section' "$BJ")" = "git" ]; then pass "ui.default_section is git (the app opens on the Git page)"; else fail "ui.default_section is not git (#1300)"; fi
if grep -qE 'SharedPreferences|prefs\.(get|put)[A-Za-z]*\(.*(tab|section|last)' "$SHELL_KT"; then fail "DriveShell persists a last page (#1300: cold start is always Git)"; else pass "DriveShell persists no last page"; fi

echo "── D2 configs pages: declared ↔ dispatched ──"
d2 "$BJ" "$CONFIGS" && pass "ui.sections[configs].pages dispatch matches" || fail "configs pages: declaration and dispatch disagree"

echo "── D2s git pages: declared ↔ dispatched (#1149) ──"
d2s "$BJ" "$SYNC_KT" && pass "ui.sections[git].pages dispatch matches; periods at or above the floor" || fail "git pages: declaration and dispatch disagree"

echo "── D3 icons from declarations ──"
d3 "$BJ" "$CATALOG" && pass "every declared icon name is in IconCatalog's vocabulary" || fail "an icon is declared that the catalog does not know"
if grep -qE 'Declarations\.nav\.islandEntries\(\)' "$SHELL_KT" && grep -qE 'NavBarTopSecondRow\(' "$CONFIGS" && grep -qE 'NavBarTopSecondRow\(' "$SYNC_KT"; then pass "the shell draws the declared nav icons from the shared libs:ui-icons catalog and the strips are libs:ui-nav-bars's NavBarTopSecondRow over the declared pages"; else fail "the island does not draw its icons from the shared catalog (islandEntries()), or a strip is not NavBarTopSecondRow"; fi
if grep -rqE '"ic_[a-z_]+"' "$SRC"; then fail "a drawable name is typed in Kotlin"; else pass "no drawable-name literal in Kotlin"; fi

echo "── D4 baked once, decoded once ──"
for f in UI_SECTIONS_B64 UI_BOTTOM_NAV UI_DEFAULT_SECTION UI_SYNC_B64 UI_FILES_B64 UI_ICON_DEFAULT; do
    if grep -qE "buildConfigField \"String\", *\"$f\"" "$GRADLE" && grep -qE "BuildConfig\.$f" "$DECL"; then pass "$f baked and decoded"; else fail "$f not baked in build.gradle or not read in Declarations.kt"; fi
done
if grep -qE 'throw new GradleException\("build\.json::ui\.bottom_nav / ui\.sections must declare' "$GRADLE"; then pass "a missing ui.bottom_nav / ui.sections fails the build"; else fail "build.gradle does not refuse a missing ui.bottom_nav / ui.sections"; fi
if grep -qE 'throw new GradleException\("build\.json::ui\.sections\[git\]\.pages must declare' "$GRADLE"; then pass "a missing ui.sections[git].pages fails the build"; else fail "build.gradle does not refuse a missing ui.sections[git].pages"; fi
OTHER_BLOBS="$(grep -rlE 'BuildConfig\.[A-Z_]+_B64' "$SRC" | grep -v 'Declarations.kt' | grep -v 'EngineActivity.kt' || true)"
if [ -z "$OTHER_BLOBS" ]; then pass "no screen reads a BuildConfig blob directly"; else fail "BuildConfig blobs read outside Declarations/EngineActivity:"; printf '%s\n' "$OTHER_BLOBS" | sed 's/^/        /'; fi

echo "── D5 the fleet island ──"
if python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); m=b["modules"]; sys.exit(0 if "libs:bottomnav" in m and "libs:bottomnav" in m["app"]["depends_on"] and m["libs:bottomnav"].get("dir","").endswith("libs/bottomnav") else 1)' "$BJ"; then pass "libs:bottomnav declared and depended on"; else fail "libs:bottomnav missing from build.json modules / app.depends_on"; fi
if grep -qE "implementation project\(':libs:bottomnav'\)" "$GRADLE"; then pass "libs:bottomnav linked"; else fail "libs:bottomnav not linked in app/build.gradle"; fi
if grep -qE 'NavBarBottomHost\(' "$SHELL_KT" && grep -qE 'entries = Declarations\.nav\.islandEntries\(\)' "$SHELL_KT"; then pass "DriveShell draws the island from the declared tabs, on the fleet's host (which owns the scroll-collapse)"; else fail "DriveShell does not draw NavBarBottomHost from Declarations.tabs"; fi
if grep -qE 'setContent \{ (CompositionLocalProvider\([A-Za-z.]+ provides [A-Za-z.]+\) \{ )?DriveTheme \{ Root\(\)(; [A-Za-z.]+\(\))* \} \}' "$MAIN" && grep -qE 'class MainActivity : ComponentActivity\(\), DriveActions' "$MAIN"; then pass "MainActivity is a Compose host implementing DriveActions"; else fail "MainActivity is not the Compose host"; fi

echo "── D6 the WebView shell is gone ──"
for gone in "$APP/app/src/main/assets/drive.html" "$APP/app/src/main/assets/vendor/tailwind.js" "$SRC/FilesBridge.kt" "$APP/app/src/main/res/layout/activity_main.xml"; do
    if [ -e "$gone" ]; then fail "still present: ${gone#$APP/}"; else pass "gone: ${gone#$APP/}"; fi
done
JSI="$(grep -rl '@JavascriptInterface' "$SRC" || true)"
if [ -z "$JSI" ]; then pass "no JavascriptInterface anywhere: nothing calls Kotlin by string name any more"; else fail "a JavascriptInterface is back: $JSI"; fi
APPCOMPAT="$(grep -rlE 'AppCompatActivity' "$SRC" --include='*.kt' || true)"
if [ -z "$APPCOMPAT" ]; then pass "no AppCompatActivity anywhere: the chrome and #577's PDF reader are ComponentActivities"; else fail "an AppCompatActivity is back: $APPCOMPAT"; fi
if grep -qE 'class PdfReaderActivity : ComponentActivity\(\)' "$SRC/PdfReaderActivity.kt" && grep -qE 'setContent \{ DriveTheme \{ ReaderScreen\(\) \} \}' "$SRC/PdfReaderActivity.kt"; then pass "the PDF reader is a Compose host under DriveTheme"; else fail "the PDF reader is not a ComponentActivity hosting ReaderScreen under DriveTheme"; fi
if grep -qE 'AlertDialog\.Builder|PopupMenu|\bEditText\(|\bTextView\(|\bsetContentView\(' "$SRC/PdfReaderActivity.kt"; then fail "the PDF reader builds a View widget again (its pdfium surface PdfReaderView is the one View, hosted by AndroidView)"; else pass "the PDF reader builds no View widget: dialogs, menu, fields and bars are Compose"; fi
if grep -qE "^import com\.google\.android\.material\." -r "$SRC"; then fail "a Material Views widget in the chrome"; else pass "no Material Views widget in Kotlin (the material dependency only supplies the AppCompat-descended dark theme the reader needs)"; fi
if grep -rqE 'android\.webkit\.WebView' "$SRC" --include='*.kt'; then fail "a WebView is back in the chrome (the PDF reader is native pdfium, #577)"; else pass "no WebView anywhere: the chrome is Compose, the PDF reader is native (#577)"; fi
for gone in "$APP/app/src/main/assets/reader.html" "$APP/app/src/main/assets/vendor"; do [ -e "$gone" ] && fail "still present: ${gone#$APP/}" || pass "gone: ${gone#$APP/}"; done

echo "── D7 no colour or caption literal; strings alive ──"
COLOUR="$(grep -rnE 'Color\(0x[0-9A-Fa-f]{6,8}\)|parseColor\(|#[0-9A-Fa-f]{6}' "$SRC" --include='*.kt' | grep -vE '^\S+:\s*(//|\*|/\*)' || true)"
# The ONE place a hex colour is data rather than chrome: the CSS of the standalone .html the Markdown
# converter WRITES for the owner's document (files/convert/Markdown.kt STYLE). Exempted only as a CSS
# declaration in that file; a Color(0x..) or parseColor( there, or any hex elsewhere, still fails.
COLOUR="$(printf '%s\n' "$COLOUR" | grep -vE 'convert/Markdown\.kt:[0-9]+:.*(color|background|border[a-z-]*):[^;}"]*#[0-9A-Fa-f]{3,6}' | grep -v '^$' || true)"
if [ -z "$COLOUR" ]; then pass "no colour literal in the chrome's Kotlin (colors.xml → DriveTheme)"; else fail "colour literal in Kotlin:"; printf '%s\n' "$COLOUR" | sed 's/^/        /'; fi
CAPTION="$(grep -rnE '(^|[^A-Za-z])(Text|CapsuleBadge|Pill|SectionHeader|DriveCard|EmptyState)\(\s*"[A-Za-z][^"]*"' "$SRC" --include='*.kt' || true)"
if [ -z "$CAPTION" ]; then pass "no caption literal in a screen (every caption is a string resource or a declaration)"; else fail "caption typed into Kotlin:"; printf '%s\n' "$CAPTION" | sed 's/^/        /'; fi
python3 - "$SRC" "$STRINGS" "$APP/app/src/main/AndroidManifest.xml" <<'PYTHON' && pass "every R.string named in Kotlin exists; no string is dead" || fail "string table and Kotlin disagree (see above)"
import os, re, sys
src, strings, manifest = sys.argv[1], sys.argv[2], sys.argv[3]
used = set()
for d, _, fs in os.walk(src):
    for f in fs:
        if f.endswith(".kt"): used |= set(re.findall(r"R\.string\.([a-z0-9_]+)", open(os.path.join(d, f), encoding="utf-8").read()))
used |= set(re.findall(r"@string/([a-z0-9_]+)", open(manifest, encoding="utf-8").read()))
import glob
declared = set()
for f in glob.glob(os.path.join(os.path.dirname(strings), "strings*.xml")):   # #1173: strings.xml plus strings_storage.xml
    declared |= set(re.findall(r'<string name="([a-z0-9_]+)"', open(f, encoding="utf-8").read()))
# app_name is build.gradle's resValue (the launcher label = build.json::display_name), never in strings.xml.
missing = sorted(used - declared - {"app_name"}); dead = sorted(declared - used)
bad = 0
if missing: print("    named in Kotlin but not declared: %s" % missing); bad = 1
if dead: print("    declared but never used: %s" % dead); bad = 1
sys.exit(bad)
PYTHON

echo "── D8 the StatusLight idiom ──"
if grep -qE 'enum class State \{ ON, OFF, UNKNOWN, UNVERIFIABLE \}' "$LIGHT" && grep -qE 'State\.ON -> "●"' "$LIGHT" && grep -qE 'State\.OFF -> "○"' "$LIGHT" && grep -qE 'State\.UNKNOWN -> "\?"' "$LIGHT" && grep -qE 'State\.UNVERIFIABLE -> "\?"' "$LIGHT"; then pass "four states, three glyph shapes"; else fail "StatusLight states or glyphs differ from the idiom"; fi
if grep -qE 'fun of\(reading: Boolean\?, observed: Boolean = true\): State' "$LIGHT" && grep -qE 'null -> State\.UNKNOWN' "$LIGHT"; then pass "null never collapses to OFF; unobserved is UNVERIFIABLE"; else fail "StatusLight.of does not implement the honesty rules"; fi
for c in status_light_on status_light_off status_light_unknown; do grep -qE "<color name=\"$c\">" "$COLORS" && pass "colors.xml declares $c" || fail "colors.xml lacks $c"; done
for s in status_light_on status_light_off status_light_unknown status_light_unverifiable status_light_description; do grep -qE "<string name=\"$s\">" "$STRINGS" && pass "strings.xml declares $s" || fail "strings.xml lacks $s"; done
# the status-light tokens moved out of the superapp tree into libs:account (the superapp consumes them from there)
SUPER="$ROOT/ab_cloud-libs-shared/libs/account/src/main/res/values/colors.xml"
if [ -f "$SUPER" ]; then
    for c in status_light_on status_light_off status_light_unknown; do
        ours="$(grep -oE "<color name=\"$c\">#[0-9A-Fa-f]+</color>" "$COLORS" | grep -oE '#[0-9A-Fa-f]+' | tr 'a-f' 'A-F')"
        theirs="$(grep -oE "<color name=\"$c\">#[0-9A-Fa-f]+</color>" "$SUPER" | grep -oE '#[0-9A-Fa-f]+' | tr 'a-f' 'A-F')"
        if [ -n "$ours" ] && [ "$ours" = "$theirs" ]; then pass "$c = $ours, the superapp's value"; else fail "$c is '$ours' here and '$theirs' in the superapp — two greens, one of them lying"; fi
    done
else
    echo "  UNVERIFIABLE  aa_cloud-superapp is not beside this app; the colour comparison did not run"
fi
USES="$(grep -rlE 'StatusLightRow\(|StatusLight\.State' "$SRC" | wc -l | tr -d ' ')"
if [ "$USES" -ge 6 ]; then pass "the light is used across the chrome ($USES files)"; else fail "StatusLight is used in only $USES files"; fi

echo "── D9 dark is the default ──"
if grep -qE 'parent="Theme\.Material3\.Dark\.NoActionBar"' "$THEMES" && grep -qE 'darkColorScheme\(' "$THEME" && grep -qE 'colorResource\(R\.color\.drive_background\)' "$THEME"; then pass "forced dark in both halves, colours from resources"; else fail "the theme is not the forced-dark, resource-driven one"; fi

echo "── D10 the JVM suite and the layout-tree test exist and name the tags ──"
for t in "$TESTS/files/FilesStateTest.kt" "$TESTS/files/FileOpsTest.kt" "$TESTS/sync/SyncHistoryTest.kt" "$TESTS/DeclarationsTest.kt" "$TESTS/ui/DriveShellTest.kt" "$TESTS/PdfConvertTest.kt" "$TESTS/PdfLayoutTest.kt"; do
    [ -f "$t" ] && pass "exists: ${t#$APP/}" || fail "missing: ${t#$APP/}"
done
if grep -qE 'RobolectricTestRunner' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'BottomNavTags\.item\(' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'DriveTags\.FILES_TAB_STRIP' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'DriveTags\.FILES_SELECTION_BAR' "$TESTS/ui/DriveShellTest.kt"; then pass "DriveShellTest renders the island and the Files tree under Robolectric"; else fail "DriveShellTest does not measure the declared tree"; fi
if grep -qE 'includeAndroidResources = true' "$GRADLE" && grep -qE "testImplementation 'org\.robolectric:robolectric" "$GRADLE" && grep -qE "testImplementation 'junit:junit" "$GRADLE"; then pass "the test stack is linked"; else fail "app/build.gradle lacks junit / robolectric / includeAndroidResources"; fi
if grep -qE 'IconCatalog\.knows\(' "$TESTS/DeclarationsTest.kt"; then pass "DeclarationsTest holds every declared icon to the catalog"; else fail "DeclarationsTest does not check the icons"; fi

echo "── D11 the #603/#609 structure ──"
d11_structure() {
    python3 - "$1" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
bad = 0
tabs = ui["bottom_nav"]
if tabs != ["explorer", "manage", "agents", "home", "git", "fsync", "mounts"]:
    print("    ui.bottom_nav is %s, not the owner's order explorer/manage/agents/home/git/fsync/mounts (#1149, #1173)" % tabs); bad = 1
if ui.get("top_bar", {}).get("drawer") is not True or ui["top_bar"].get("inline_actions") is not True:
    print("    ui.top_bar is not the hamburger plus inline AI / Profile / Configs (#1149)"); bad = 1
if "backups" in ui:
    print("    ui still carries a top-level backups block: it moved into ui.configs at #603"); bad = 1
cfg_pages = next(s for s in ui["sections"] if s["id"] == "configs")["pages"]
pages = [p["id"] for p in cfg_pages]
if pages != ["general", "perms", "about", "drive", "git", "fsync", "mounts", "webserver"]:
    print("    ui.configs.pages is %s, not the owner's order general/git/fsync/mounts/webserver (#1216)" % pages); bad = 1
if any(not p.get("label") or not p.get("icon") for p in cfg_pages):
    print("    a Configs sub-page lacks a label or an icon"); bad = 1
if any(s["id"] == "sync" for s in ui["sections"]):
    print("    a `sync` section is declared again: Git, Rsync and Mounts are bottom-nav sections of their own (#1149)"); bad = 1
sync_sec = next(s for s in ui["sections"] if s["id"] == "git").get("pages", [])
sync_pages = [p["id"] for p in sync_sec]
if sync_pages != ["me", "cloud"]:
    print("    ui.sections[git].pages is %s, not me/cloud (#1149)" % sync_pages); bad = 1
for sid, want in (("fsync", []), ("mounts", ["rclone", "fuse"])):
    got = [p["id"] for p in next(s for s in ui["sections"] if s["id"] == sid).get("pages", [])]
    if got != want:
        print("    ui.sections[%s].pages is %s, not %s (#1173)" % (sid, got, "/".join(want))); bad = 1
if any(not p.get("label") or not p.get("icon") for p in sync_sec):
    print("    a Git sub-page lacks a label or an icon"); bad = 1
sections = [x["id"] for x in ui["files"]["sections"]]
if len(sections) != 3 or "emulated" not in sections or "terminal" not in sections:
    print("    ui.files.sections is %s: #603 declares Emulated, the store's own section and the Terminal root" % sections); bad = 1
store = [p for p in ui["files"]["places"] if p["kind"] == "shared_root"]
if len(store) != 1 or store[0].get("section") == "emulated":
    print("    the shared store is not a section of its own"); bad = 1
unknown = [p["id"] for p in ui["files"]["places"] if p.get("section") not in sections]
if unknown:
    print("    places naming an undeclared section: %s" % unknown); bad = 1
sys.exit(bad)
PYTHON
}
# d11_seed <drive-git-repos.json> : the seed set is PUBLIC repositories only
d11_seed() {
    python3 - "$1" <<'PYTHON'
import json, sys
repos = json.load(open(sys.argv[1]))["repos"]
seeded = [r for r in repos if r.get("seed")]
if not seeded: print("    no repository is marked seed: the store would start empty"); sys.exit(1)
private = [r["name"] for r in seeded if r.get("private")]
if private: print("    PRIVATE repositories marked for seeding: %s (an anonymous clone of one fails every time)" % private); sys.exit(1)
if any(not r.get("public") for r in seeded): print("    a seeded repository does not state public: true"); sys.exit(1)
sys.exit(0)
PYTHON
}
d11_structure "$BJ" && pass "seven tabs in the #1149 order; Configs sub-pages in order; Git has Me and Cloud; two Files sections with the store in its own" || fail "the #603/#609 structure is not what build.json declares"
d11_seed "$REPOS" && pass "the store's seed set is public repositories only" || fail "the seed set is not public-only"
if grep -qE 'buildConfigField "boolean", "SEED_ENABLED"' "$GRADLE" && grep -qE 'buildConfigField "int", +"SEED_DEPTH"' "$GRADLE" && grep -qE 'GitEngine\.clone\(applicationContext, url, dir, auth = auth, depth = BuildConfig\.SEED_DEPTH\)' "$SEED"; then pass "the seed is declared (storage.seed -> SEED_ENABLED/SEED_DEPTH) and cloned SHALLOW through the engine"; else fail "the first-run seed is not wired to the declaration"; fi
if grep -qE 'GitEngine\.isRepository\(dir\)' "$SEED" && grep -qE 'RepoRegistry\(File\(applicationContext\.filesDir, GitSyncWorker\.REGISTRY_FILE\)\)' "$SEED"; then pass "seeding skips a clone that exists and registers into the SAME registry the Git page reads"; else fail "the seed does not reuse the one registry"; fi
if grep -qE 'org\.eclipse\.jgit' "$SEED"; then fail "the seed talks to JGit directly - libs:git-sync is the engine"; else pass "the seed goes through libs:git-sync, never a second git"; fi
python3 - "$APPS_JSON" "$BJ" <<'PYTHON' && pass "the Apps grid carries GitSync and Fsync as in-app routes into the Git and Fsync pages" || fail "the Apps grid does not route GitSync / Fsync into the Git / Fsync pages"
import json, sys
apps = json.load(open(sys.argv[1]))["apps"]
ui = json.load(open(sys.argv[2]))["ui"]
tabs = ui["bottom_nav"]; secs = {s["id"]: [p["id"] for p in s.get("pages", [])] for s in ui["sections"]}
bad = 0
for want in ("GitSync", "Fsync"):
    tile = next((a for a in apps if a.get("label") == want), None)
    if tile is None: print("    no %s tile" % want); bad = 1; continue
    route = tile.get("route") or {}
    if route.get("tab") not in ("git", "fsync", "mounts") or route.get("tab") not in tabs: print("    %s routes to tab %r, not a former Sync page of the bottom nav (#1149)" % (want, route.get("tab"))); bad = 1
    elif route.get("page") is not None and route.get("page") not in secs[route["tab"]]: print("    %s routes to page %r, which ui.sections[%s].pages does not declare" % (want, route.get("page"), route["tab"])); bad = 1
    if tile.get("package"): print("    %s declares a package: it is an in-app route, not an installed app" % want); bad = 1
sys.exit(bad)
PYTHON
if grep -qE 'CompositionLocalProvider\( *LocalContentColor provides text,' <<<"$(tr '\n' ' ' <"$THEME")"; then pass "the dark theme provides LocalContentColor: no Text() inherits material3's Color.Black on the dark page"; else fail "DriveTheme does not provide LocalContentColor - every Text() without an explicit colour prints BLACK on the dark page"; fi
for a in android:textColorPrimary android:textColorSecondary colorOnSurface; do
    grep -qE "<item name=\"$a\">@color/drive_text" "$THEMES" && pass "Theme.CloudDrive names $a" || fail "Theme.CloudDrive does not override $a - an inflated View falls back to a framework ink"
done

echo "── D12 the #604 structure: four volume classes, and Rsync ──"
# d12_classes <build.json> <VolumesScreen.kt> : the four declared classes, in order, dispatched
d12_classes() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
ui = json.load(open(sys.argv[1]))["ui"]; src = open(sys.argv[2], encoding="utf-8").read()
bad = 0
vol = ui.get("volumes") or {}
classes = next(p for p in next(s for s in ui["sections"] if s["id"] == "explorer")["pages"] if p["id"] == "cloud").get("pages") or []
ids = [c.get("id") for c in classes]
if ids != ["constellation", "containers", "machines", "s3"]:
    print("    ui.sections[explorer].pages[cloud].pages is %s, not the #604 order constellation/containers/machines/s3" % ids); bad = 1
if any(not c.get("label") or not c.get("icon") for c in classes):
    print("    a volume class lacks a label or an icon"); bad = 1
m = re.search(r"when \(id\) \{(.*?)\n\s*\}", src, re.S)
if not m:
    print("    VolumesScreen has no `when (id)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z0-9_]+)" ->', m.group(1), re.M)
if sorted(dispatched) != sorted(i for i in ids if i):
    print("    declared %s vs dispatched %s" % (ids, dispatched)); bad = 1
# the rules the classes are filtered by are declared too, never typed into Kotlin
if not vol.get("machine_kinds") or "container" in vol["machine_kinds"]:
    print("    ui.volumes.machine_kinds missing, or carries `container` (which is the default, not a host kind)"); bad = 1
if not vol.get("s3_remote_types"):
    print("    ui.volumes.s3_remote_types is empty: the S3 class would list nothing"); bad = 1
if "<package>" not in (vol.get("constellation_path") or ""):
    print("    ui.volumes.constellation_path does not carry the <package> placeholder"); bad = 1
sys.exit(bad)
PYTHON
}
# d12_fsync <build.json> <drive-apps.json> : the Rsync page is now Fsync (#1173) with the folder sync rules as its one page (#1216); the tile routes there
d12_fsync() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]; apps = json.load(open(sys.argv[2]))["apps"]
bad = 0
if any(s["id"] == "rsync" for s in ui["sections"]):
    print("    ui.sections still has `rsync`: the page was renamed to Fsync, not given a second name (#1173)"); bad = 1
page = next((s for s in ui["sections"] if s["id"] == "fsync"), None)
if page is None:
    print("    ui.sections has no `fsync` section: the id is the dispatch (#1173)"); sys.exit(1)
if page.get("label") != "Fsync":
    print("    the fsync section is labelled %r, not 'Fsync' (#1173)" % page.get("label")); bad = 1
sub = [(p.get("id"), p.get("label"), p.get("icon")) for p in page.get("pages", [])]
if sub:
    print("    fsync declares sub-pages %s: its main page is the folder sync rules and nothing else (#1216)" % sub); bad = 1
tile = next((a for a in apps if a.get("label") == "Fsync"), None)
if tile is None:
    print("    no Fsync tile in the Apps grid"); sys.exit(1)
route = tile.get("route") or {}
if (route.get("tab"), route.get("page")) != ("fsync", None):
    print("    Apps > Fsync routes to %r, not the Fsync page itself, {fsync} (#1216)" % route); bad = 1
sys.exit(bad)
PYTHON
}
# d12_rules <drive-sync-rules.json> : #1173 no mock: the APK declares no folder rule and no Unison pair, only Unison's policy
d12_rules() {
    python3 - "$1" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1]))
bad = 0
if d.get("rules"):
    print("    %d sync rule(s) declared: the Fsync page draws real state only, a declared rule is sample data (#1173)" % len(d["rules"])); bad = 1
u = d.get("unison") or {}
if u.get("pairs"):
    print("    %d Unison pair(s) declared: a pair belongs to the user (storage/UnisonPairs.kt), not to the APK (#1173)" % len(u["pairs"])); bad = 1
if not u.get("defaults") or not u.get("ignore"):
    print("    the Unison policy (defaults, ignore) is missing: user pairs inherit it"); bad = 1
sys.exit(bad)
PYTHON
}
# d12_machines <build.json> <drive-connections.json> : every connection's machine is a declared kind
d12_machines() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
vol = json.load(open(sys.argv[1]))["ui"]["volumes"]
conns = json.load(open(sys.argv[2]))
allowed = set(vol.get("machine_kinds") or []) | {"container"}
strays = sorted({c.get("machine", "container") for c in conns} - allowed)
if strays:
    print("    connections declare machine kinds no class renders: %s" % strays); sys.exit(1)
if not any(c.get("machine", "container") == "container" for c in conns):
    print("    no connection belongs to Cloud-Containers: the class that already existed would be empty"); sys.exit(1)
sys.exit(0)
PYTHON
}
VOLUMES_KT="$SRC/volumes/VolumesScreen.kt"
RULES_JSON="$APP/data/drive-sync-rules.json"
REMOTES_JSON="$APP/data/drive-remotes.json"
CONN_JSON="$APP/data/drive-connections.json"
for required in "$VOLUMES_KT" "$RULES_JSON" "$REMOTES_JSON" "$CONN_JSON"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
d12_classes "$BJ" "$VOLUMES_KT" && pass "four volume classes in the #604 order, dispatched by VolumesScreen; their filters are declared too" || fail "the #604 volume classes are not what build.json declares"
d12_fsync "$BJ" "$APPS_JSON" && pass "Fsync is a bottom-nav section (folder sync rules, no sub-pages) and the Apps tile routes there; no rsync id remains" || fail "the Rsync→Fsync rename or its route is not done"
d12_rules "$RULES_JSON" && pass "no sync rule and no Unison pair is declared in the APK: the Fsync page is real state, Unison policy only" || fail "sample rules or pairs are declared again (#1173)"
d12_machines "$BJ" "$CONN_JSON" && pass "every connection's machine kind is a class that renders it" || fail "a connection would vanish between Cloud-Containers and Cloud-Machines"
for f in UI_VOLUMES_B64 UI_CONSTELLATION_B64 SYNC_RULES_B64; do
    if grep -qE "buildConfigField \"String\", *\"$f\"" "$GRADLE" && grep -qE "BuildConfig\.$f" "$DECL"; then pass "$f baked and decoded"; else fail "$f not baked in build.gradle or not read in Declarations.kt"; fi
done
if grep -qE 'throw new GradleException\("build\.json::ui\.sections\[explorer\]\.pages\[cloud\]\.pages must declare' "$GRADLE"; then pass "a missing ui.sections[explorer].pages[cloud].pages fails the build"; else fail "build.gradle does not refuse a missing ui.sections[explorer].pages[cloud].pages"; fi
if grep -qE 'connections: List<Declarations\.ConnectionDecl> = Declarations\.connections' "$SRC/sync/RcloneMountsScreens.kt"; then pass "Cloud-Containers is the SAME MountsSyncScreen, scoped — not a second mounts screen"; else fail "MountsSyncScreen is not scopeable: Volumes must reuse it, never copy it"; fi
if grep -qE 'fun job\(rule: Declarations\.SyncRuleDecl\): RcloneJob' "$SRC/sync/SyncRules.kt" && ! grep -qE 'ProcessBuilder|exec\(' "$SRC/sync/SyncRules.kt"; then pass "a sync rule runs as ONE RcloneJob on the existing engine, never its own process"; else fail "SyncRules does not map a rule onto libs:rclone's job model"; fi

echo "── D13 the #613 regroup: two sections, three Personal subgroups, one auth deep-link ──"
# d13_sections <build.json> : the two sections regroup the four classes, nothing lost, Personal carries the auth
d13_sections() {
    python3 - "$1" <<'PYTHON'
import json, sys
_ui = json.load(open(sys.argv[1]))["ui"]
vol = _ui["volumes"]
bad = 0
classes = [c.get("id") for c in (next(p for p in next(s for s in _ui["sections"] if s["id"] == "explorer")["pages"] if p["id"] == "cloud").get("pages") or [])]
sections = vol.get("sections") or []
ids = [s.get("id") for s in sections]
if ids != ["constellation", "personal"]:
    print("    ui.volumes.sections is %s, not the #613 order constellation/personal" % ids); bad = 1
if any(not s.get("label") for s in sections):
    print("    a section lacks a label"); bad = 1
by = {s.get("id"): s for s in sections}
if (by.get("constellation") or {}).get("classes") != ["constellation"]:
    print("    Cloud Constellation must own exactly the constellation class"); bad = 1
if (by.get("personal") or {}).get("classes") != ["machines", "containers", "s3"]:
    print("    Personal Mounts must own the three subgroups machines/containers/s3, in order"); bad = 1
if not (by.get("personal") or {}).get("auth"):
    print("    Personal Mounts must carry auth: true (its sign-in affordance)"); bad = 1
if (by.get("constellation") or {}).get("auth"):
    print("    Cloud Constellation is the mesh side and must carry no auth affordance"); bad = 1
# NOTHING LOST: every declared class is claimed by exactly one section.
claimed = [c for s in sections for c in (s.get("classes") or [])]
if sorted(claimed) != sorted(classes):
    print("    the sections' classes %s do not cover ui.sections[explorer].pages[cloud].pages %s (a class would be lost or double-listed)" % (sorted(claimed), sorted(classes))); bad = 1
# the auth deep-link is declared: a fleet id (resolved to a package at build time) or a package, plus a target and the extra it travels as.
pa = vol.get("personal_auth") or {}
if not (pa.get("fleet") or pa.get("package")):
    print("    ui.volumes.personal_auth names neither a fleet id nor a package: the sign-in has nothing to launch"); bad = 1
if not pa.get("target") or not pa.get("extra"):
    print("    ui.volumes.personal_auth lacks target or extra: the deep-link cannot say where to land"); bad = 1
sys.exit(bad)
PYTHON
}
d13_sections "$BJ" && pass "two sections in the #613 order; Personal owns machines/containers/s3 and the auth, Constellation owns constellation and none; nothing lost" || fail "the #613 two-section regroup is not what build.json declares"
if grep -qE 'Declarations\.volumes\.sections\.forEach' "$VOLUMES_KT" && grep -qE 'Declarations\.volumes\.unsectioned\(\)' "$VOLUMES_KT"; then pass "VolumesScreen renders the declared sections and buckets an unclaimed class under Others"; else fail "VolumesScreen does not render ui.volumes.sections (the regroup is declared but not drawn)"; fi
if grep -qE 'actions\.openAuthProfile\(auth\.pkg, auth\.target, auth\.extra\)' "$VOLUMES_KT" \
    && grep -qE 'fun openAuthProfile\(pkg: String, target: String, extra: String\): Boolean' "$SRC/DriveActions.kt" \
    && grep -qE 'override fun openAuthProfile\(' "$MAIN"; then pass "the Personal Mounts sign-in deep-links through DriveActions.openAuthProfile, implemented on the Activity — libs:auth is not reimplemented here"; else fail "the auth affordance is not wired to a cross-app deep-link (DriveActions.openAuthProfile / MainActivity)"; fi

echo "== M mutation-proof =="

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["bottom_nav"]=b["ui"]["bottom_nav"][1:]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-files.json"
d1 "$TMP/no-files.json" "$MAIN" >/dev/null && fail "D1 passed a declaration that dropped a tab (tester is vacuous)" || pass "a tab dropped from ui.bottom_nav → D1 RED"
sed 's/^\( *\)"mounts" -> MountsPageScreen/\1"mountz" -> MountsPageScreen/' "$MAIN" > "$TMP/main.kt"
cmp -s "$MAIN" "$TMP/main.kt" && fail "the dispatch mutation changed nothing (tester is stale)"
d1 "$BJ" "$TMP/main.kt" >/dev/null && fail "D1 passed a misdispatched tab (tester is vacuous)" || pass "a tab id misspelt in MainActivity → D1 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["bottom_nav"][2],b["ui"]["bottom_nav"][3]=b["ui"]["bottom_nav"][3],b["ui"]["bottom_nav"][2]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/swapped-nav.json"
d11_structure "$TMP/swapped-nav.json" >/dev/null && fail "D11 passed a reordered bottom nav (tester is vacuous)" || pass "two tabs swapped in ui.bottom_nav → D11 RED"
grep -v '"folder" -> Icons.Filled.Folder' "$CATALOG" > "$TMP/no-folder.kt"
cmp -s "$CATALOG" "$TMP/no-folder.kt" && fail "the catalog mutation changed nothing (tester is stale)"
d3 "$BJ" "$TMP/no-folder.kt" >/dev/null && fail "D3 passed a catalog without the folder glyph (tester is vacuous)" || pass "a glyph dropped from IconCatalog → D3 RED"
sed 's/"mounts" -> ConfigsMountsPage/"mountz" -> ConfigsMountsPage/' "$CONFIGS" > "$TMP/configs.kt"
cmp -s "$CONFIGS" "$TMP/configs.kt" && fail "the configs mutation changed nothing (tester is stale)"
d2 "$BJ" "$TMP/configs.kt" >/dev/null && fail "D2 passed a misdispatched page (tester is vacuous)" || pass "a page id misspelt in ConfigsScreen → D2 RED"
sed 's/"me" -> GitReposScreen/"mee" -> GitReposScreen/' "$SYNC_KT" > "$TMP/sync.kt"
cmp -s "$SYNC_KT" "$TMP/sync.kt" && fail "the sync mutation changed nothing (tester is stale)"
d2s "$BJ" "$TMP/sync.kt" >/dev/null && fail "D2s passed a misdispatched page (tester is vacuous)" || pass "a page id misspelt in GitPageScreen → D2s RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); [r.update(seed=True) for r in d["repos"] if r.get("private")]; json.dump(d,open(sys.argv[2],"w"))' "$REPOS" "$TMP/seed-private.json"
d11_seed "$TMP/seed-private.json" >/dev/null && fail "D11 passed a PRIVATE repository marked for seeding (tester is vacuous)" || pass "a private repository marked seed → D11 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); [p.update(pages=p["pages"][1:]) for s in b["ui"]["sections"] if s["id"]=="explorer" for p in s["pages"] if p["id"]=="cloud"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-constellation.json"
d12_classes "$TMP/no-constellation.json" "$VOLUMES_KT" >/dev/null && fail "D12 passed a declaration that dropped a volume class (tester is vacuous)" || pass "a class dropped from ui.sections[explorer].pages[cloud].pages → D12 RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); [a["route"].update(page="backups") for a in d["apps"] if a.get("label")=="Fsync"]; json.dump(d,open(sys.argv[2],"w"))' "$APPS_JSON" "$TMP/rsync-backups.json"
d12_fsync "$BJ" "$TMP/rsync-backups.json" >/dev/null && fail "D12 passed an Fsync tile routed to Backups (tester is vacuous)" || pass "Apps > Fsync routed to Configs Backups -> D12 RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["rules"].append({"id":"sample","local_path":"/x","remote_name":"r","remote_path":"p","direction":"upload","enabled":True}); json.dump(d,open(sys.argv[2],"w"))' "$RULES_JSON" "$TMP/rule-sample.json"
d12_rules "$TMP/rule-sample.json" >/dev/null && fail "D12 passed a sample sync rule shipped in the APK (tester is vacuous)" || pass "a sample rule declared in the APK -> D12 RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["unison"]["pairs"].append({"id":"sample"}); json.dump(d,open(sys.argv[2],"w"))' "$RULES_JSON" "$TMP/pair-sample.json"
d12_rules "$TMP/pair-sample.json" >/dev/null && fail "D12 passed a sample Unison pair shipped in the APK (tester is vacuous)" || pass "a sample Unison pair declared in the APK -> D12 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); [s.update(pages=s["pages"][:1]) for s in b["ui"]["sections"] if s["id"]=="mounts"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-fuse.json"
d11_structure "$TMP/no-fuse.json" >/dev/null && fail "D11 passed a Mounts page that lost its FUSE sub-page (tester is vacuous)" || pass "Mounts without its FUSE sub-page -> D11 RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d[0]["machine"]="laptop"; json.dump(d,open(sys.argv[2],"w"))' "$CONN_JSON" "$TMP/conn-stray.json"
d12_machines "$BJ" "$TMP/conn-stray.json" >/dev/null && fail "D12 passed a connection whose machine kind no class renders (tester is vacuous)" || pass "a connection with an undeclared machine kind → D12 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); [s["classes"].pop() for s in b["ui"]["volumes"]["sections"] if s["id"]=="personal"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/section-lost-class.json"
d13_sections "$TMP/section-lost-class.json" >/dev/null && fail "D13 passed sections that dropped a class (a volume would be lost — tester is vacuous)" || pass "a class dropped from a section's list (S3 lost) → D13 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); [s.update(auth=False) for s in b["ui"]["volumes"]["sections"] if s["id"]=="personal"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/section-no-auth.json"
d13_sections "$TMP/section-no-auth.json" >/dev/null && fail "D13 passed a Personal Mounts section with no auth (tester is vacuous)" || pass "Personal Mounts stripped of auth:true → D13 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["volumes"]["sections"].reverse(); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/section-swapped.json"
d13_sections "$TMP/section-swapped.json" >/dev/null && fail "D13 passed the sections out of order (tester is vacuous)" || pass "the two sections reordered → D13 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["volumes"]["personal_auth"].pop("target",None); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/auth-no-target.json"
d13_sections "$TMP/auth-no-target.json" >/dev/null && fail "D13 passed a personal_auth with no deep-link target (tester is vacuous)" || pass "personal_auth stripped of its target → D13 RED"
d1 "$BJ" "$MAIN" >/dev/null && d2 "$BJ" "$CONFIGS" >/dev/null && d2s "$BJ" "$SYNC_KT" >/dev/null && d3 "$BJ" "$CATALOG" >/dev/null && d11_seed "$REPOS" >/dev/null \
    && d12_classes "$BJ" "$VOLUMES_KT" >/dev/null && d12_fsync "$BJ" "$APPS_JSON" >/dev/null && d12_rules "$RULES_JSON" "$REMOTES_JSON" >/dev/null && d12_machines "$BJ" "$CONN_JSON" >/dev/null && d13_sections "$BJ" >/dev/null \
    && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-shell: all checks passed"; else echo "test-drive-shell: $FAILURES check(s) FAILED"; exit 1; fi
