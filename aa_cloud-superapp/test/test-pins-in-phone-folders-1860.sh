#!/usr/bin/env bash
# Tester (#1860): web-page home shortcuts (pins) are filed into the Phone page's folders, NOT drawn as a "Pinned" strip on the Cloud page.
# Static, no network, no device. Each check runs on the real tree (must pass) and on a MUTATED copy (must fail), so a green is never vacuous.
# Usage: ./test-pins-in-phone-folders-1860.sh
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
L=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
T=aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in $L/launcher/pins/PinnedFolders.kt $L/apps/PhoneAppsUi.kt $L/launcher/GroupedTilesFragment.kt; do
    [ -f "$ROOT/$f" ] || { echo "FATAL: $f missing -- refusing to report a verdict"; exit 2; }
done

has() { grep -qE -- "$2" "$1" 2>/dev/null; }          # has <file> <ere>

# check <root> : prints one line per violated check; empty output = all hold.
check() {
    local r="$1" g="$1/$L/launcher/GroupedTilesFragment.kt" pf="$1/$L/launcher/pins/PinnedFolders.kt"
    [ -e "$r/$L/launcher/pins/PinnedStrip.kt" ] && echo "C1 the Cloud Pinned strip file still exists"
    has "$g" 'PinnedStrip|g:pinned' && echo "C2 the Cloud page still draws the Pinned strip"
    grep -rqE 'PinnedStrip\(' "$r/$L" 2>/dev/null && echo "C3 something still calls PinnedStrip"
    has "$r/$L/apps/PhoneAppsUi.kt" 'PinnedFolders\.merge\(' || echo "C4 allApps does not merge pins into the Phone folders"
    has "$r/$L/apps/PhoneAppsUi.kt" 'if \(only == null\) com' || echo "C5 the Cloud constellation view (only) is not kept pin-free"
    has "$r/$L/apps/PhoneAppsUi.kt" 'PinnedFolders\.launch\(' || echo "C6 a pin tile in a folder does not launch"
    has "$r/$L/apps/PhoneAppsUi.kt" 'PinnedFolders\.remove\(' || echo "C7 a pin tile has no Remove"
    has "$pf" 'const val PREFIX = "pin:"' || echo "C8 the synthetic key prefix is not pin:"
    has "$pf" 'PhoneAppClassifier\.classify' || echo "C9 pins are not classified with PhoneAppClassifier"
    [ "$(grep -c 'startShortcut(' "$pf")" = 1 ] || echo "C10 the pin launch is not exactly one shared function"
    has "$r/$L/apps/PhoneAppsFragment.kt" 'PinnedShortcutStore\.changes' || echo "C11 the Phone page does not re-read on PinnedShortcutStore.changes"
    has "$r/$L/apps/SuitePhoneAppsFragment.kt" 'PinnedShortcutStore\.changes' || echo "C12 the merged Phone page does not re-read on pin changes"
    has "$r/$L/apps/PhoneAppsFragment.kt" 'PinnedFolders\.apps' || echo "C13 the A-Z mode drops the pins"
    has "$r/$L/launcher/folders/FolderRepository.kt" 'pinApps\(ctx\)' || echo "C14 the Folders page does not enumerate pins in the Phone scope"
    has "$r/$L/launcher/folders/FolderRepository.kt" 'PinnedFolders\.classify' || echo "C15 the Folders page does not classify pins"
    has "$r/$L/launcher/pins/PinnedShortcutStore.kt" 'pinned-shortcuts\.json' || echo "C16 the pin store file moved (existing pins would be lost)"
    [ -f "$r/$T/launcher/PinnedFoldersTest.kt" ] || echo "C17 the JVM parity test is missing"
    has "$r/$T/launcher/PinnedFoldersTest.kt" 'PARITY every pin' || echo "C18 the parity test lost its assertion"
}

echo "== real tree: every check holds =="
out="$(check "$ROOT")"
if [ -z "$out" ]; then ok "all 18 checks hold"; else bad "violations:"; echo "$out"; fi

echo "== mutations: each must be caught =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
fresh() { rm -rf "$W/t"; mkdir -p "$W/t/$L/launcher/pins" "$W/t/$L/launcher/folders" "$W/t/$L/apps" "$W/t/$T/launcher"
    for f in launcher/pins/PinnedFolders.kt launcher/pins/PinnedShortcutStore.kt launcher/GroupedTilesFragment.kt launcher/folders/FolderRepository.kt apps/PhoneAppsUi.kt apps/PhoneAppsFragment.kt apps/SuitePhoneAppsFragment.kt; do
        cp "$ROOT/$L/$f" "$W/t/$L/$f"; done
    cp "$ROOT/$T/launcher/PinnedFoldersTest.kt" "$W/t/$T/launcher/PinnedFoldersTest.kt"; }
# mut <name> <file under $L or $T> <sed expr> <expected check id>
mut() {
    fresh; sed -i -E "$3" "$W/t/$2"
    if cmp -s "$ROOT/$2" "$W/t/$2"; then bad "mutation '$1' changed nothing (stale mutation)"; return; fi
    if check "$W/t" | grep -q "^$4 "; then ok "mutation '$1' caught by $4"; else bad "mutation '$1' NOT caught"; fi
}
mut "strip back on Cloud"   $L/launcher/GroupedTilesFragment.kt 's|^( *// #1860.*)$|\1\n if (sectionId == "cloud") item(key = "g:pinned") { PinnedStrip { } }|' C2
mut "merge removed"         $L/apps/PhoneAppsUi.kt 's/PinnedFolders\.merge\(/PinnedFolders.mergeX(/' C4
mut "cloud view gets pins"  $L/apps/PhoneAppsUi.kt 's/only == null\) com/true) com/' C5
mut "launch removed"        $L/apps/PhoneAppsUi.kt 's/PinnedFolders\.launch\(/PinnedFolders.launchX(/' C6
mut "remove removed"        $L/apps/PhoneAppsUi.kt 's/PinnedFolders\.remove\(/PinnedFolders.removeX(/' C7
mut "key prefix changed"    $L/launcher/pins/PinnedFolders.kt 's/const val PREFIX = "pin:"/const val PREFIX = "p:"/' C8
mut "own classifier"        $L/launcher/pins/PinnedFolders.kt 's/PhoneAppClassifier\.classify/OtherClassifier.classify/' C9
mut "second launch path"    $L/launcher/pins/PinnedFolders.kt 's#^(fun launchPin.*)$#\1\n // startShortcut(#' C10
mut "no live refresh"       $L/apps/PhoneAppsFragment.kt 's/PinnedShortcutStore\.changes/PinnedShortcutStore.chg/' C11
mut "suite no refresh"      $L/apps/SuitePhoneAppsFragment.kt 's/PinnedShortcutStore\.changes/PinnedShortcutStore.chg/' C12
mut "A-Z drops pins"        $L/apps/PhoneAppsFragment.kt 's/PinnedFolders\.apps/PinnedFolders.appz/' C13
mut "folders page no pins"  $L/launcher/folders/FolderRepository.kt 's/pinApps\(ctx\)/emptyList()/' C14
mut "folders page no class" $L/launcher/folders/FolderRepository.kt 's/PinnedFolders\.classify/PinnedFolders.classifz/' C15
mut "store file moved"      $L/launcher/pins/PinnedShortcutStore.kt 's/pinned-shortcuts\.json/pins2.json/' C16
mut "parity assertion gone" $T/launcher/PinnedFoldersTest.kt 's/PARITY every pin/every pin/' C18

echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
