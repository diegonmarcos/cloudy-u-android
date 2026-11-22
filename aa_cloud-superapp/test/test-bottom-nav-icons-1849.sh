#!/usr/bin/env bash
# #1849 Cloud SuperApp bottom nav: every bar item has its own catalog glyph; Notify is the bell, Actions the bolt
# (Actions had reused Home's house, Notify the Inboxes tray). Labels, order and pages are held by the #1848 tests.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
BJ="$ROOT/aa_cloud-superapp/build.json"
CAT="$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"
check() { python3 -I - "$1" "$2" <<'PY'
import json, re, sys
b = json.load(open(sys.argv[1])); cat = open(sys.argv[2]).read()
ui = b['ui']; secs = {s['id']: s for s in ui['sections']}
bar = [(i, secs[i].get('icon')) for i in ui['bottom_nav']]
fails = []
icons = dict(bar)
if icons.get('communication') != 'notifications': fails.append('Notify icon is %r, want notifications (bell)' % icons.get('communication'))
if icons.get('actions') != 'bolt': fails.append('Actions icon is %r, want bolt' % icons.get('actions'))
glyphs = [g for _, g in bar]
dups = sorted({g for g in glyphs if glyphs.count(g) > 1})
if dups: fails.append('bar items share a glyph: %s' % dups)
# that each glyph is a catalog name is the nav-icons guard's I3 (1_cicd/src/scripts/cloud-android-nav-icons-guard.py)
print('\n'.join(fails) or 'OK')
sys.exit(1 if fails else 0)
PY
}
pass=0; fail=0
if out=$(check "$BJ" "$CAT"); then echo "PASS: bar glyphs (Notify bell, Actions bolt, no two bar items share one)"; pass=$((pass+1)); else echo "FAIL: $out"; fail=$((fail+1)); fi
# mutations: each must go red
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mut() { python3 -I - "$BJ" "$TMP/b.json" "$1" "$2" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
for s in b['ui']['sections']:
    if s['id'] == sys.argv[3]: s['icon'] = sys.argv[4]
json.dump(b, open(sys.argv[2], 'w'))
PY
  if check "$TMP/b.json" "$CAT" >/dev/null; then echo "FAIL: mutation $1=$2 stayed green"; fail=$((fail+1)); else echo "PASS: mutation $1=$2 goes red"; pass=$((pass+1)); fi; }
mut actions ic_home
mut communication ic_p_inbox
mut actions ic_p_dialer
echo "passed=$pass failed=$fail"; [ "$fail" -eq 0 ]
