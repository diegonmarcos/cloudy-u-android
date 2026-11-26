#!/usr/bin/env bash
# #1934 the generated result table (test/results/git-control-sheet-results.md): every declared control has a row, every referenced test exists, the committed file is current.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
HERE="$ROOT/ac_cloud-drive/test"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
python3 -I "$HERE/gen-git-control-sheet-results.py" "$ROOT" && pass "every control has a row, every test exists, the table is current" || fail "result table"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mkdir -p "$TMP/ac_cloud-drive/test/fixtures" "$TMP/ac_cloud-drive/test/results"
python3 -I -c "
import json,sys
b=json.load(open(sys.argv[1])); json.dump(b,open(sys.argv[2],'w'))
m=json.load(open(sys.argv[3])); m['groups'].pop('sy_direction'); json.dump(m,open(sys.argv[4],'w'))" "$ROOT/ac_cloud-drive/build.json" "$TMP/ac_cloud-drive/build.json" "$HERE/fixtures/git-control-test-map.json" "$TMP/ac_cloud-drive/test/fixtures/git-control-test-map.json"
cp "$HERE/results/git-control-sheet-results.md" "$TMP/ac_cloud-drive/test/results/"
for d in ac_cloud-drive/app/src/test ab_cloud-libs-shared/libs/git-core/src/test ab_cloud-libs-shared/libs/git-ui/src/test ab_cloud-libs-shared/libs/git-repos/src/test; do mkdir -p "$TMP/$d"; cp -r "$ROOT/$d/." "$TMP/$d/"; done
python3 -I "$HERE/gen-git-control-sheet-results.py" "$TMP" >/dev/null 2>&1 && fail "a declared control without a row passed (vacuous)" || pass "a control without a row → RED"
echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-control-sheet-results: all checks passed"; else echo "test-drive-git-control-sheet-results: $FAILURES check(s) FAILED"; exit 1; fi
