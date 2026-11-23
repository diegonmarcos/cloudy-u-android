#!/usr/bin/env bash
# Tester #1864: every Phone quickmark folder with an icon_pkg OPENS with its Cloud app first, then the declared
# phone apps in declared order. The data is untouched (rule 16: nothing removed); the prepend happens at display in
# SuitePhoneAppsFragment.withCloudApp, which the page calls for every folder.
# Each check runs against the real source, then against mutated copies that must go red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
JT="$APP/app/src/test/java/com/diegonmarcos/superapp/apps/PhoneFolderCloudAppFirstTest.kt"
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for t in jq python3 grep sed; do command -v "$t" >/dev/null || { echo "FATAL: $t missing"; exit 2; }; done
for f in "$SRC" "$JT" "$BJ"; do [ -s "$f" ] || { echo "FATAL: $f missing or empty"; exit 2; }; done

# check <file>: prints one line per failed property; empty output = all hold.
check() {
    local f="$1" body
    body="$(sed -n '/internal fun withCloudApp/,/^        }/p' "$f")"
    [ -n "$body" ] || { echo "withCloudApp is missing"; return; }
    printf '%s\n' "$body" | grep -q 'listOf(Entry(ip, ' || echo "Cloud entry is not built from icon_pkg"
    printf '%s\n' "$body" | grep -q ')) + folder.entries$' || echo "Cloud entry is not placed BEFORE the declared entries in declared order"
    printf '%s\n' "$body" | grep -q 'folder.entries.any { it.pkg == ip }' || echo "no duplicate guard"
    printf '%s\n' "$body" | grep -q 'ip.isEmpty()' || echo "folders without icon_pkg are not returned unchanged"
    printf '%s\n' "$body" | grep -q 'fleetLabel(ip) ?: return folder.entries' || echo "alias not in the fleet is not skipped"
    grep -q 'withCloudApp(folder, SuitePhoneAppsFragment::fleetLabelOf)' "$f" || echo "the page does not call withCloudApp for each folder"
}

OUT="$(check "$SRC")"
[ -z "$OUT" ] && ok "withCloudApp prepends, keeps order, dedupes, skips aliases, and the page uses it" || bad "source: $OUT"

# Data: every icon_pkg is a fleet package (so the prepend applies) and no declared package is dropped by it.
python3 - "$BJ" <<'P' && ok "every icon_pkg folder resolves to a fleet app; the Cloud entry is not already a member" || bad "data: icon_pkg not a fleet package, or already a member"
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

grep -q 'fun firstEntryIsTheCloudApp\|fun declaredOrderIsKept\|fun noDuplicateWhenAlreadyAMember\|fun folderWithoutIconPkgIsUnchanged\|fun aliasNotInTheFleetIsSkipped' "$JT" \
  && [ "$(grep -c '@Test' "$JT")" -ge 5 ] && ok "the JVM test covers first, order, no duplicate, unchanged, alias" || bad "JVM test incomplete"

# Mutations: each must turn the check red.
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mut() { # name sed-expr
    cp "$SRC" "$T/m.kt"; sed -i "$2" "$T/m.kt"
    if cmp -s "$SRC" "$T/m.kt"; then bad "mutation '$1' changed nothing"; return; fi
    [ -n "$(check "$T/m.kt")" ] && ok "mutation '$1' goes red" || bad "mutation '$1' stayed green"
}
mut "drop the prepend at the call site" 's/SuitePhoneAppsFragment.withCloudApp(folder, SuitePhoneAppsFragment::fleetLabelOf).map/folder.entries.map/'
mut "append instead of prepend" 's/)) + folder.entries/)) + folder.entries.reversed()/'
mut "drop the duplicate guard" 's/ || folder.entries.any { it.pkg == ip }//'
mut "drop the alias skip" 's/fleetLabel(ip) ?: return folder.entries/fleetLabel(ip) ?: ip/'
mut "prepend even without icon_pkg" 's/ip.isEmpty()/false/'

echo; echo "-- test-phone-folder-cloud-first-1864: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
