#!/usr/bin/env bash
# #1356 THE GIT TABLE'S SIZE COLUMNS. Main (depth 1) <= Total (history) always; Total is the forge's KiB * 1024; On disk is
# the clone folder's walked bytes, the dash when not cloned. RepoSizeColumnsTest (JVM) proves the values; this guard pins
# the source those values come from and proves itself with mutations (each turns it red).
#   Z1 RepoTable.rowOf reads Size main through mainBytes() = minOf(tree blobs, total) and Size total through totalBytes() = KiB * 1024.
#   Z2 the columns are labelled by what they measure: Main (depth 1), Total (history), On disk.
#   Z3 On disk is only shown for a clone (sizeDiskBytes = if (cloned) diskBytes else null).
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"; T="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/RepoTable.kt"; S="$APP/../ab_cloud-libs-shared/libs/git-ui/src/main/res/values/strings.xml"
TEST="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/RepoSizeColumnsTest.kt"
FAILURES=0; pass() { echo "  PASS  $*"; }; fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
check() { python3 - "$1" "$2" "$3" <<'PY'
import sys
t, s, test = (open(p, encoding="utf-8").read() for p in sys.argv[1:4]); bad = []
if "fun totalBytes(totalKb: Long): Long = totalKb * 1024L" not in t: bad.append("Z1 Size total is not KiB * 1024")
if "fun mainBytes(treeBlobBytes: Long, totalKb: Long): Long = minOf(treeBlobBytes, totalBytes(totalKb))" not in t: bad.append("Z1 Size main is not bounded by Size total")
if "sizeMainBytes = remote?.let { mainBytes(it.sizeMainBytes, it.sizeTotalKb) }" not in t or "sizeTotalBytes = remote?.let { totalBytes(it.sizeTotalKb) }" not in t: bad.append("Z1 the row does not read the sizes through mainBytes/totalBytes")
for k, v in (("repo_col_size_main", "Main (depth 1)"), ("repo_col_size_total", "Total (history)"), ("repo_col_size_disk", "On disk")):
    if '<string name="%s">%s</string>' % (k, v) not in s: bad.append("Z2 %s is not labelled %s" % (k, v))
if "sizeDiskBytes = if (cloned) diskBytes else null" not in t: bad.append("Z3 On disk is shown for a repo that is not cloned")
if "r.sizeMainBytes!! <= r.sizeTotalBytes!!" not in test or "5_000_000" not in test: bad.append("the JVM test lost its main <= total case")
print("\n".join("    " + b for b in bad)); sys.exit(1 if bad else 0)
PY
}
echo "== Z1-Z3 the size columns =="
check "$T" "$S" "$TEST" && pass "Main (depth 1) <= Total (history) = KiB * 1024; On disk only for a clone" || fail "the size columns are wrong"
echo "== M mutations =="
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mut() { local label=$1 file=$2 old=$3 new=$4 t="$T" s="$S"
    python3 -c 'import sys; x=open(sys.argv[1]).read(); assert sys.argv[2] in x; open(sys.argv[4],"w").write(x.replace(sys.argv[2],sys.argv[3]))' "$file" "$old" "$new" "$tmp/m" || { fail "mutation '$label' has no anchor"; return; }
    [ "$file" = "$T" ] && t="$tmp/m" || s="$tmp/m"
    check "$t" "$s" "$TEST" >/dev/null && fail "M $label stayed green (vacuous)" || pass "M $label → RED"; }
mut "main = the raw tree (the shipped bug)" "$T" "minOf(treeBlobBytes, totalBytes(totalKb))" "treeBlobBytes"
mut "total in decimal KB" "$T" "totalKb * 1024L" "totalKb * 1000L"
mut "main and total swapped" "$T" "sizeMainBytes = remote?.let { mainBytes(it.sizeMainBytes, it.sizeTotalKb) }" "sizeMainBytes = remote?.let { totalBytes(it.sizeTotalKb) }"
mut "the old ambiguous label" "$S" '<string name="repo_col_size_main">Main (depth 1)</string>' '<string name="repo_col_size_main">Size main</string>'
mut "disk size for a repo not cloned" "$T" "sizeDiskBytes = if (cloned) diskBytes else null" "sizeDiskBytes = diskBytes"
check "$T" "$S" "$TEST" >/dev/null && pass "the unmutated tree is still green" || fail "the unmutated tree is red"
[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-size-columns: all green"; exit 0; } || { echo "test-drive-git-size-columns: $FAILURES failure(s)"; exit 1; }
