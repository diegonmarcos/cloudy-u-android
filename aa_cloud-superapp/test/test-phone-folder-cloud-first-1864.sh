#!/usr/bin/env bash
# Tester #1864, MERGED by #1936. The #1864 rule ("a folder with an icon_pkg opens with its Cloud app first") was reversed by
# the owner's decision of 2026-11-26: Phone > Apps lists third-party phone apps only, so the Cloud entry leaves the folder and
# the folder face is its first installed third-party app. What #1864 pinned that still holds is kept: the data is untouched
# (every folder keeps its icon_pkg, its icon_res and every declared phone app, rule 16) and every icon_pkg is a fleet app.
# What it pinned that no longer holds is inverted: withCloudApp is gone, nothing is prepended, the face is not icon_pkg.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for t in python3 grep; do command -v "$t" >/dev/null || { echo "FATAL: $t missing"; exit 2; }; done
for f in "$SRC" "$BJ"; do [ -s "$f" ] || { echo "FATAL: $f missing or empty"; exit 2; }; done

check() {
    local f="$1"
    grep -q 'withCloudApp' "$f" && echo "withCloudApp is back: the Cloud app is prepended to folders again"
    grep -q 'folder.iconPkg.takeIf' "$f" && echo "the Cloud app is the folder face again"
    grep -q 'FolderTile(folder.label, contents, null, folder.iconRes)' "$f" || echo "the folder face is not the Cloud page icon_res (#1956)"
    grep -q 'folder to folder.entries.map(::curated)' "$f" || echo "folders do not render their declared entries"
}
OUT="$(check "$SRC")"
[ -z "$OUT" ] && ok "no Cloud entry is prepended and the face is the first third-party app" || bad "source: $OUT"

python3 - "$BJ" <<'P' && ok "data untouched: every icon_pkg folder is still declared, its icon_pkg is a fleet package, and not a member" || bad "data: icon_pkg not a fleet package, or already a member"
import json, sys
d = json.load(open(sys.argv[1]))
ids = set()
for e in d['ui']['external_apps']:
    ids |= {e.get('hub_package'), e.get('alt_package'), e.get('install_package')} | set(e.get('forks', {}).values())
n = 0
for s in d['ui']['sections']:
    if s.get('id') != 'phone': continue
    for g in s['phone_app_groups']:
        for f in g.get('folders', []):
            ip = f.get('icon_pkg')
            if not ip: continue
            n += 1
            assert ip in ids, (f['label'], ip)
            assert ip not in [p['pkg'] for p in f['packages']], (f['label'], ip)
assert n >= 20, n
P

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mut() { cp "$SRC" "$T/m.kt"; sed -i "$2" "$T/m.kt"
    if cmp -s "$SRC" "$T/m.kt"; then bad "mutation '$1' changed nothing"; return; fi
    [ -n "$(check "$T/m.kt")" ] && ok "mutation '$1' goes red" || bad "mutation '$1' stayed green"; }
mut "prepend the Cloud app again" 's/folder to folder.entries.map(::curated)/folder to withCloudApp(folder).map(::curated)/'
mut "the face is the first installed app again" 's/FolderTile(folder.label, contents, null, folder.iconRes)/FolderTile(folder.label, contents, contents.firstOrNull { it.installed }, "")/'

echo; echo "-- test-phone-folder-cloud-first-1864 (merged by #1936): $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
