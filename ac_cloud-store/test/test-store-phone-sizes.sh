#!/usr/bin/env bash
# Store > Phone: app sizes, the size sort, and ONE row layout for Phone and Cloud.
#
#  1. Behaviour (kotlinc, plain JVM): libs:appstore StoreSizes - compact size formatting, the
#     full size (app + data + cache) and the APK fallback (base + splits, labelled APK), the dash
#     for no size, the grant-row rule, the size sort comparator (largest / smallest first,
#     unmeasured last) and its persistence. test/kt/StoreSizesCheck.kt is the check.
#  2. The no-permission state: Phone draws the one-tap grant row exactly when
#     StoreSizes.needsGrantRow says so, it opens usage access through the SAME settings action
#     Access > Android Perms uses (PermsGrant.settingsAction), the size probe falls back to
#     sourceDir + splitSourceDirs, and Cloud Store declares PACKAGE_USAGE_STATS.
#  3. Sizes are measured off the main thread, cached, and dropped on package changes.
#  4. Structure: the Phone and Cloud pages draw their rows through the SAME StoreRow component
#     (card, head, expanded actions, filter line); Phone keeps no sideways button scroller and
#     no card of its own.
# Then each planted defect must turn it red.
#
# Usage: ./test-store-phone-sizes.sh   (static + kotlinc when on PATH; CLOUD_REQUIRE_KOTLINC=1 makes it required)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIBREL="ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
KC="$(command -v kotlinc || true)"
# Reads ALL its input (never has on a pipe: an early exit SIGPIPEs the writer).
has() { grep "$@" >/dev/null; }

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <repo-root> [skip-kotlin]  -> PASS/FAIL lines, return = #fails
check() {
  local R="$1" skipk="${2:-}" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local L="$R/$LIBREL"
  local SZ="$L/StoreSizes.kt" PR="$L/StoreSizeProbe.kt" PH="$L/StorePhoneFragment.kt" CL="$L/StoreCloudFragment.kt" BAR="$L/StoreBar.kt"
  local MF="$R/ac_cloud-store/app/src/main/AndroidManifest.xml"
  for f in "$SZ" "$PR" "$PH" "$CL" "$BAR" "$MF"; do [ -f "$f" ] || { bad "missing ${f#$R/}"; return 99; }; done

  # 1. behaviour
  if [ -z "$skipk" ]; then
    if [ -n "$KC" ]; then
      local out; out="$(mktemp -d)"
      if "$KC" -nowarn "$SZ" "$HERE/kt/StoreSizesCheck.kt" -d "$out/c" >"$out/log" 2>&1; then
        local res; res="$(java -cp "$out/c:$(dirname "$(dirname "$(readlink -f "$KC")")")/lib/kotlin-stdlib.jar" StoreSizesCheckKt 2>&1)"
        printf '%s\n' "$res"
        fails=$((fails + $(printf '%s\n' "$res" | grep -c '  FAIL:')))
        printf '%s' "$res" | has '  PASS:' || bad "the StoreSizes check printed nothing"
      else
        sed 's/^/    /' "$out/log" | head -20; bad "StoreSizes.kt does not compile with the check"
      fi
      rm -rf "$out"
    elif [ "${CLOUD_REQUIRE_KOTLINC:-0}" = 1 ]; then bad "kotlinc not on PATH (CLOUD_REQUIRE_KOTLINC=1)"
    else echo "  NOT COVERED: kotlinc not on PATH - StoreSizes behaviour not run here (static checks below still are)"; fi
  fi

  local ph cl pr bar mf
  ph="$(strip "$PH")"; cl="$(strip "$CL")"; pr="$(strip "$PR")"; bar="$(strip "$BAR")"; mf="$(cat "$MF")"

  # 2. no-permission state
  printf '%s' "$ph" | has 'if (StoreSizes.needsGrantRow(sizeGranted))' && ok "Phone draws the grant row by StoreSizes.needsGrantRow" || bad "Phone's grant row is not gated by StoreSizes.needsGrantRow"
  printf '%s' "$ph" | has 'StoreSizeProbe.openPermissions(ctx)' && ok "the grant row opens the Permissions page (#1274)" || bad "the grant row does not open StoreSizeProbe.openPermissions"
  printf '%s' "$pr" | has 'PermsLink.open(ctx, PERMS_FEATURE, listOf("usage_access"))' && ok "usage access opens through libs:perms PermsLink on the catalogue entry" || bad "the probe opens a settings page of its own"
  printf '%s' "$pr" | has 'const val PERMISSION = "android.permission.PACKAGE_USAGE_STATS"' && ok "the probe's permission is PACKAGE_USAGE_STATS" || bad "the probe names another permission"
  printf '%s' "$pr" | has 'queryStatsForPackage' && ok "full sizes come from StorageStatsManager.queryStatsForPackage" || bad "no queryStatsForPackage"
  printf '%s' "$pr" | has 'ai.sourceDir' && printf '%s' "$pr" | has 'splitSourceDirs' && printf '%s' "$pr" | has 'StoreSizes.Size.apkOnly' && printf '%s' "$pr" | has '?: apk(ctx, pkg)' \
    && ok "without the grant the probe falls back to sourceDir + splitSourceDirs (apkOnly)" || bad "the APK fallback is missing"
  printf '%s' "$mf" | has 'android.permission.PACKAGE_USAGE_STATS' && ok "Cloud Store declares PACKAGE_USAGE_STATS (so Android Perms tracks it)" || bad "Cloud Store does not declare PACKAGE_USAGE_STATS"
  printf '%s' "$ph" | has 'StoreSizes.rowLabel(StoreSizeProbe.cached(r.pkg))' && ok "the row's size slot is StoreSizes.rowLabel" || bad "the row's size is not StoreSizes.rowLabel"
  printf '%s' "$ph" | has 'StoreSizes.breakdown(StoreSizeProbe.cached(r.pkg))' && ok "the row's detail shows the breakdown" || bad "no size breakdown in the row's detail"

  # 3. off the main thread, cached, refreshed on package changes
  printf '%s' "$ph" | sed -n '/private fun measureSizes/,/^    }/p' | has 'thread(name = "store-phone-sizes")' && ok "sizes are measured on a worker thread" || bad "sizes are measured on the main thread"
  printf '%s' "$pr" | has 'ConcurrentHashMap<String, StoreSizes.Size>' && ok "sizes are cached" || bad "sizes are not cached"
  printf '%s' "$ph" | has 'StoreSizeProbe.watch(ctx)' && printf '%s' "$pr" | has 'ACTION_PACKAGE_REPLACED' && ok "a package change drops its size" || bad "sizes are never refreshed on package changes"
  printf '%s' "$ph" | has 'StoreSizes.comparator(sort,' && printf '%s' "$ph" | has 'StoreSizes.saveSort(sortStore, sort)' && printf '%s' "$ph" | has 'StoreSizes.loadSort(sortStore)' \
    && ok "the list sorts by StoreSizes.comparator and persists the choice" || bad "the size sort is not wired or not persisted"

  # 4. one row layout
  for part in 'StoreRow.Item(' 'StoreRow.Summary(' 'StoreRow.Actions(' 'StoreRow.Filters('; do
    printf '%s' "$ph" | has -F "$part" && printf '%s' "$cl" | has -F "$part" && ok "Phone and Cloud both draw $part" || bad "Phone and Cloud do not share $part"
  done
  printf '%s' "$bar" | has '^object StoreRow' && ok "StoreRow is the one shared component" || bad "no shared StoreRow"
  ! printf '%s' "$ph" | has -E 'HorizontalScrollView|horizontalScroll' && ok "Phone's buttons are expanded, no sideways scroller" || bad "Phone hides buttons in a HorizontalScrollView"
  ! printf '%s' "$ph$cl" | has -E 'StoreRow\.(Card|Head)\(' && ok "neither page assembles a row from Card/Head itself: StoreRow.Item only" || bad "a page draws its own row, not StoreRow.Item"
  ! printf '%s' "$ph" | has 'Ink.CARD' && ok "Phone has no card of its own" || bad "Phone draws its own card"
  ! printf '%s' "$ph$cl$bar" | has -i 'minHeight' && ok "no minHeight in the Store rows" || bad "a minHeight in the Store rows"
  return $fails
}

echo "== real tree =="
check "$ROOT"; F=$?

M=0
mutate() {  # <name> <file-rel> <from> <to> [all]   (all: replace every occurrence, for a use the page now has twice)
  local name="$1" rel="$2" from="$3" to="$4" all="${5:-}" box; box="$(mktemp -d)"
  mkdir -p "$box/$(dirname "$LIBREL")" "$box/ac_cloud-store/app/src/main"
  cp -r "$ROOT/$LIBREL" "$box/$LIBREL"; cp "$ROOT/ac_cloud-store/app/src/main/AndroidManifest.xml" "$box/ac_cloud-store/app/src/main/"
  python3 - "$box/$rel" "$from" "$to" "$all" <<'PY' || { echo "  MUTATION NOT PLANTED: $name"; rm -rf "$box"; return 1; }
import sys
p, a, b, every = sys.argv[1:]
s = open(p, encoding='utf-8').read()
if a not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(a, b, -1 if every else 1))
PY
  local kot=skip; case "$rel" in *StoreSizes.kt) kot="";; esac
  if check "$box" $kot >/dev/null 2>&1; then echo "  MUTATION SURVIVED: $name"; rm -rf "$box"; return 1; fi
  echo "  mutation caught: $name"; rm -rf "$box"; return 0
}
echo "== mutations =="
P=$LIBREL
mutate "grant row always hidden" $P/StorePhoneFragment.kt 'if (StoreSizes.needsGrantRow(sizeGranted))' 'if (false)' || M=$((M+1))
mutate "no APK fallback" $P/StoreSizeProbe.kt 'val s = (ss?.let { full(it, pkg) }) ?: apk(ctx, pkg) ?: continue' 'val s = (ss?.let { full(it, pkg) }) ?: continue' || M=$((M+1))
mutate "Phone back to a sideways scroller" $P/StorePhoneFragment.kt 'StoreRow.Actions(verbs)' 'Box(Modifier.horizontalScroll(rememberScrollState()))' || M=$((M+1))
mutate "Phone draws its own card" $P/StorePhoneFragment.kt 'StoreRow.Item(r.label, open,' 'StoreRow.Card(tag = "x") { StoreRow.Head(r.label, open,' || M=$((M+1))
mutate "Phone drops the shared summary line" $P/StorePhoneFragment.kt 'StoreRow.Summary(' 'StoreCaption(' || M=$((M+1))
mutate "Cloud stops using the shared actions" $P/StoreCloudFragment.kt 'StoreRow.Actions(' 'MyActions(' all || M=$((M+1))
mutate "sort not persisted" $P/StorePhoneFragment.kt 'StoreSizes.saveSort(sortStore, sort)' 'Unit' || M=$((M+1))
mutate "sizes measured on the main thread" $P/StorePhoneFragment.kt 'thread(name = "store-phone-sizes") {' 'run {' || M=$((M+1))
mutate "manifest drops usage access" ac_cloud-store/app/src/main/AndroidManifest.xml 'android:name="android.permission.PACKAGE_USAGE_STATS"' 'android:name="android.permission.NOPE"' || M=$((M+1))
if [ -n "$KC" ]; then
  mutate "size desc sorts ascending" $P/StoreSizes.kt 'sort == Sort.SIZE_DESC -> y.compareTo(x)' 'sort == Sort.SIZE_DESC -> x.compareTo(y)' || M=$((M+1))
  mutate "unmeasured sorts first" $P/StoreSizes.kt 'x == null -> 1' 'x == null -> -1' || M=$((M+1))
  mutate "APK label dropped" $P/StoreSizes.kt 'size.apkOnly -> format(size.totalBytes) + " " + APK' 'size.apkOnly -> format(size.totalBytes)' || M=$((M+1))
  mutate "no-size reads as zero" $P/StoreSizes.kt 'size == null -> NONE' 'size == null -> "0 B"' || M=$((M+1))
  mutate "cache counted twice" $P/StoreSizes.kt 'Size(appBytes, (dataBytesWithCache - cacheBytes).coerceAtLeast(0), cacheBytes, apkOnly = false)' 'Size(appBytes, dataBytesWithCache, cacheBytes, apkOnly = false)' || M=$((M+1))
  mutate "binary units" $P/StoreSizes.kt 'b < 999_500_000L -> "${(b + 500_000) / 1_000_000} MB"' 'b < 999_500_000L -> "${b / 1_048_576} MB"' || M=$((M+1))
fi
echo
[ $F -eq 0 ] && [ $M -eq 0 ] && { echo "OK: phone sizes, sort and shared row layout hold; every planted defect is caught"; exit 0; }
echo "FAILED: $F check(s) failed, $M mutation(s) survived"; exit 1
