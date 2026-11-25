#!/usr/bin/env bash
# Store > Search (its own island item after Phone): the landing (our list, filed under SuperApp > Phone's taxonomy) and the
# merged multi-source search.
#
#  1. Behaviour (kotlinc, plain JVM): libs:appstore StoreSuggest + apps/PhoneTaxonomyLayout -
#     one shelf per NON-EMPTY sub-category, sections in ui.phone_sections order, folders in their
#     order, the unclaimed group last, to-install before installed; merge = one result per package
#     with every source's badge (a GitHub repository joined by its sourceCode), undeclared sources
#     dropped; groups = first source that found it, installed first. test/kt/StoreSearchLandingCheck.kt.
#  2. Same order as SuperApp > Phone, on the REAL taxonomy: the landing's layout of SuperApp's own
#     build.json (ui.phone_sections / ui.phone_folders) is compared with SuperApp's order computed
#     here independently (its renderAllApps rule: sections in declared order, folders by prefix,
#     prefix-less ones under Other, last). And both pages lay out through ONE function,
#     PhoneTaxonomyLayout.bucket: SuperApp's PhoneAppsFragment calls it and keeps no loop of its own.
#  3. Suggestions come from OUR list only: the landing reads the fleet manifest and the declared
#     list (resolver.apps + DeclaredEdits), never a search source; on the real data that list fills
#     sub-categories (the keyword rules of ui.phone_folders, the same ones PhoneTaxonomy applies).
#  4. Each shelf is a title line then a LazyRow (scrolls sideways); the page is drawn with the
#     shared parts (ui-kit's KitSearchBar / KitStatusBanner / KitEmptyState / KitStatePill, StoreRow
#     for every result row); per-source state; no call to a sibling fleet app.
#  5. The sources: search.sources in the install-source map name only searchers that exist, izzy is
#     a declared, pinned, https F-Droid-format repo, and the Phone page's nav stays within 2..8.
# Then each planted defect must turn it red.
#
# Usage: ./test-store-search-landing.sh   (static + kotlinc when on PATH; CLOUD_REQUIRE_KOTLINC=1 makes it required)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIBREL="ab_cloud-libs-shared/libs/appstore/src/main"
APPSREL="$LIBREL/java/com/diegonmarcos/superapp/apps"
STOREREL="$LIBREL/java/com/diegonmarcos/superapp/appstore"
SAREL="aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsFragment.kt"
KC="$(command -v kotlinc || true)"
# Reads ALL its input (never has on a pipe: an early exit SIGPIPEs the writer).
has() { grep "$@" >/dev/null; }

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'(?<![:"])//[^\n]*', '', s))
PY
}

# check <repo-root> [skip-kotlin]  -> PASS/FAIL lines, return = #fails
check() {
  local R="$1" skipk="${2:-}" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local LAY="$R/$APPSREL/PhoneTaxonomyLayout.kt" SUG="$R/$STOREREL/StoreSuggest.kt" PAGE="$R/$STOREREL/StoreSearchPage.kt"
  local SA="$R/$SAREL" ASSET="$R/fleet-private-data/appstore-install-sources.json" BJ="$R/aa_cloud-superapp/build.json"
  for f in "$LAY" "$SUG" "$PAGE" "$SA" "$ASSET" "$BJ" "$R/ac_cloud-store/build.json"; do [ -f "$f" ] || { bad "missing ${f#$R/}"; return 99; }; done
  local work; work="$(mktemp -d)"

  # The real taxonomy as the landing reads it, and SuperApp's order for it computed independently.
  python3 - "$BJ" "$work/tax.tsv" "$work/want.txt" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1], encoding='utf-8'))["ui"]
secs = [s for s in ui["phone_sections"] if isinstance(s, dict) and s.get("title") and s.get("prefix")]
fs = sorted((f for f in ui["phone_folders"] if isinstance(f, dict)), key=lambda f: (f.get("order", "99"), f["id"]))
with open(sys.argv[2], "w", encoding="utf-8") as o:
    for s in secs: o.write("S\t%s\t%s\n" % (s["title"], s["prefix"]))
    for f in fs: o.write("F\t%s\t%s\n" % (f["id"], f["label"]))
# SuperApp > Phone (PhoneAppsFragment.renderAllApps, before it called the shared function):
# sections in declared order, each with the visible folders whose label starts with its prefix,
# then every folder no prefix claims under "Other". Every third folder is empty, as in the check.
shown = [f for i, f in enumerate(fs) if i % 3 != 1]
out = []
for s in secs:
    mine = [f for f in shown if next((t for t in secs if f["label"].startswith(t["prefix"])), None) is s]
    if mine: out += ["SECTION " + s["title"]] + ["SHELF " + f["id"] for f in mine]
other = [f for f in shown if not any(f["label"].startswith(t["prefix"]) for t in secs)]
if other: out += ["SECTION <other>"] + ["SHELF " + f["id"] for f in other]
open(sys.argv[3], "w", encoding="utf-8").write("".join(l + "\n" for l in out))
PY

  # 1 + 2. behaviour, and the real taxonomy's order
  if [ -z "$skipk" ]; then
    if [ -n "$KC" ]; then
      if "$KC" -nowarn "$LAY" "$SUG" "$HERE/kt/StoreSearchLandingCheck.kt" -d "$work/c" >"$work/log" 2>&1; then
        local res; res="$(java -cp "$work/c:$(dirname "$(dirname "$(readlink -f "$KC")")")/lib/kotlin-stdlib.jar" StoreSearchLandingCheckKt "$work/tax.tsv" "$work/got.txt" 2>&1)"
        printf '%s\n' "$res"
        fails=$((fails + $(printf '%s\n' "$res" | grep -c '  FAIL:')))
        printf '%s' "$res" | has '  PASS:' || bad "the StoreSuggest check printed nothing"
        if [ -s "$work/got.txt" ] && cmp -s "$work/got.txt" "$work/want.txt"; then
          ok "on SuperApp's real taxonomy the landing lists sections and sub-categories in SuperApp > Phone's order ($(grep -c '^SHELF' "$work/want.txt") shelves)"
        else
          bad "the landing's order differs from SuperApp > Phone's on the real taxonomy"; diff "$work/want.txt" "$work/got.txt" | head -8 | sed 's/^/    /'
        fi
      else
        sed 's/^/    /' "$work/log" | head -20; bad "StoreSuggest.kt / PhoneTaxonomyLayout.kt do not compile with the check"
      fi
    elif [ "${CLOUD_REQUIRE_KOTLINC:-0}" = 1 ]; then bad "kotlinc not on PATH (CLOUD_REQUIRE_KOTLINC=1)"
    else echo "  NOT COVERED: kotlinc not on PATH - StoreSuggest behaviour not run here (static checks below still are)"; fi
  fi

  local lay sug page sa
  lay="$(strip "$LAY")"; sug="$(strip "$SUG")"; page="$(strip "$PAGE")"; sa="$(strip "$SA")"

  # 2. one layout function for both pages
  printf '%s' "$sa" | has -F 'PhoneTaxonomyLayout.bucket(sections, visible)' && ok "SuperApp > Phone lays its sections out through PhoneTaxonomyLayout.bucket" || bad "SuperApp > Phone does not call PhoneTaxonomyLayout.bucket"
  ! printf '%s' "$sa" | has -F 'folder.label.startsWith(it.prefix)' && ok "SuperApp keeps no bucketing loop of its own" || bad "SuperApp buckets folders by prefix itself (a second layout)"
  printf '%s' "$sug" | has -F 'PhoneTaxonomyLayout.bucket(sections, shown)' && ok "the landing lays its shelves out through the same PhoneTaxonomyLayout.bucket" || bad "the landing does not lay out through PhoneTaxonomyLayout.bucket"
  printf '%s' "$lay" | has 'label(f).startsWith(it.second)' && ok "a folder joins its section by its label prefix" || bad "the shared layout does not join folders by prefix"

  # 3. our list, and only our list
  local land; land="$(printf '%s\n' "$page" | sed -n '/^    fun landing(ctx: Context, cfg: SourceResolver.Config, scope: Scope = Scope.BOTH): Landing {/,/^    }/p')"
  [ -n "$land" ] && ok "StoreSearchPage.landing exists" || bad "no StoreSearchPage.landing"
  printf '%s' "$land" | has -F 'fleetApps.filter { it.kind == FLEET_KIND_APP && !it.blocked }' && ok "the landing lists the fleet's own apps (the baked manifest)" || bad "the landing does not list the fleet's apps"
  printf '%s' "$land" | has -F 'else declaredList(ctx, cfg)) {' && ok "the landing lists the declared apps (resolver.apps + DeclaredEdits)" || bad "the landing does not list the declared apps"
  printf '%s' "$land" | has -F 'PhoneTaxonomy.folderIdOf(it.pkg, it.label, ctx)' && printf '%s' "$land" | has -F 'PhoneSections.loadFromBuildConfig()' && printf '%s' "$land" | has -F 'PhoneTaxonomy.folders' \
    && ok "each app is filed by the shared PhoneTaxonomy, sections by PhoneSections" || bad "the landing files apps by something other than the shared taxonomy"
  ! printf '%s' "$land" | has -E 'FDroidIndex|PlayAnonFetcher|searchOne|getBody' && ok "no search source feeds the landing" || bad "the landing reads a search source: suggestions must come from our list"
  printf '%s' "$land" | has -F 'ext.unresolved != null) continue' && ok "an app with no public source is not suggested" || bad "unresolved apps are suggested"

  python3 - "$BJ" "$ASSET" <<'PY' && ok "our list fills the taxonomy on the real data (keyword rules of ui.phone_folders)" || bad "our declared list fills fewer than 10 sub-categories: the landing would be near empty"
import json, sys
ui = json.load(open(sys.argv[1], encoding='utf-8'))["ui"]
apps = json.load(open(sys.argv[2], encoding='utf-8'))["resolver"]["apps"]
folders = sorted((f for f in ui["phone_folders"] if isinstance(f, dict)), key=lambda f: (f.get("order", "99"), f["id"]))
extra = {}
for e in ui.get("external_apps", []):
    for k in ("hub_package", "alt_package", "install_package"):
        if e.get("folder") and e.get(k): extra.setdefault(e["folder"], []).append("pkg:" + e[k].lower())
def m(k, p, l):
    if k.startswith("pkg:"): return p == k[4:]
    if k.startswith("pkg^"): return p.startswith(k[4:])
    if k.startswith("lbl:"): return l == k[4:]
    if k.startswith("lbl~"): return len(k) > 6 and k[4:] in l
    return len(k) >= 4 and k in p + " " + l
filled = set()
for pkg, a in apps.items():
    if a.get("unresolved"): continue
    p, l = pkg.lower(), a.get("label", pkg).lower()
    for f in folders:
        if any(m(k.lower(), p, l) for k in f.get("match_keywords", []) + extra.get(f["id"], [])): filled.add(f["id"]); break
print("    (our declared list fills %d sub-categories)" % len(filled))
sys.exit(0 if len(filled) >= 10 else 1)
PY

  # 4. the view: title line, then a sideways row; shared parts; per-source state
  local lv; lv="$(printf '%s\n' "$page" | sed -n '/private fun LandingView(/,/^    }/p')"
  printf '%s' "$lv" | tr -d '\n' | has -E 'testTag\(TAG_SHELF \+ shelf\.folderId\)\).*LazyRow\(Modifier\.fillMaxWidth\(\)\.testTag\(TAG_SHELF_ROW \+ shelf\.folderId\)' \
    && ok "each shelf is a title line, then a LazyRow (scrolls sideways)" || bad "a shelf is not a title line followed by a horizontally scrolling LazyRow"
  printf '%s' "$lv" | has -F 'section.shelves.forEach' && ok "one shelf per sub-category the landing returned (empty ones never reach the view)" || bad "the view does not draw the landing's shelves"
  for part in 'KitSearchBar(' 'KitStatusBanner(' 'KitEmptyState(' 'KitStatePill(' 'KitPalette.fromArgb(' 'StoreRow.Card(' 'StoreRow.Head(' 'StoreRow.Actions(' 'StoreSuggest.merge(' 'StoreSuggest.groups('; do
    printf '%s' "$page" | has -F "$part" && ok "Search uses $part" || bad "Search does not use $part"
  done
  printf '%s' "$page" | has -F 'SrcState.Failed(' && printf '%s' "$page" | has -F 'store_search_retry' && ok "a failing source shows its own error with Retry, the others keep theirs" || bad "no per-source error state"
  printf '%s' "$page" | has -F 'thread(name = "store-search-${s.id}")' && ok "each source searches on its own thread" || bad "sources do not search independently"
  ! printf '%s' "$page" | has -E 'openConnection|HttpURLConnection|URL\(|127\.0\.0\.1|localhost|AppDebugServer' && ok "Search opens no connection of its own and calls no sibling fleet app" || bad "Search opens its own connection or calls a sibling app"
  local bg; bg="$(cat "$R/$LIBREL/../../build.gradle")"
  printf '%s' "$bg" | has -F "api project(':libs:ui-kit')" && ok "libs:appstore links libs:ui-kit" || bad "libs:appstore does not link libs:ui-kit"

  # 5. sources and the nav rule
  python3 - "$ASSET" "$R/ac_cloud-store/build.json" "$PAGE" <<'PY' || fails=$((fails+1))
import json, re, sys
d = json.load(open(sys.argv[1], encoding='utf-8')); r = d["resolver"]
page = open(sys.argv[3], encoding='utf-8').read()
known = set(re.findall(r'const val (?:FLEET|DECLARED|GITHUB|PLAY) = "([^"]+)"', page)) | {"fdroid", "izzy"}
bad = []
s = d.get("search") or {}
ids = [x.get("id") for x in s.get("sources", [])]
if not ids: bad.append("no search.sources")
if len(ids) != len(set(ids)): bad.append("a search source is listed twice: %s" % ids)
bad += ["search source %r has no searcher" % i for i in ids if i not in known]
bad += ["search.foss names %r, not a search source" % i for i in s.get("foss", []) if i not in ids]
gh = next((x for x in s.get("sources", []) if x.get("id") == "github"), None)
if gh and not (str(gh.get("url", "")).startswith("https://") and "{q}" in gh.get("url", "")): bad.append("github url is not https with {q}")
z = r.get("izzy") or {}
if "izzy" not in r.get("order", []) or "izzy" not in r.get("kinds", {}): bad.append("izzy is not a declared kind")
if not re.fullmatch(r"[0-9a-f]{64}", str(z.get("cert_sha256", ""))): bad.append("izzy cert_sha256 is not a pinned sha256")
if not all(str(z.get(k, "")).startswith("https://") for k in ("repo", "api")): bad.append("izzy repo/api not https")
if r.get("kinds", {}).get("izzy", {}).get("fetches") is not True: bad.append("izzy does not fetch")
ui = json.load(open(sys.argv[2], encoding='utf-8')).get("ui", {})
sec = {x.get("id"): x for x in ui.get("sections", []) if isinstance(x, dict)}
pages = (sec.get("phone") or {}).get("pages") or []
pages = [p.get("id") if isinstance(p, dict) else p for p in pages]
if "search" in pages: bad.append("Search is back under Phone: %s" % pages)
if ui.get("bottom_nav", [])[:3] != ["cloud", "phone", "search"] or "search" not in sec: bad.append("Search is not its own island item right after Phone: %s" % ui.get("bottom_nav"))
if not 2 <= len(pages) <= 8: bad.append("Phone has %d pages, outside 2..8" % len(pages))
if not 2 <= len(r["order"]) + 1 <= 8: bad.append("the Phone source strip has %d pills, outside 2..8" % (len(r["order"]) + 1))
for b in bad: print("  FAIL: " + b)
if not bad: print("  PASS: search sources %s are all searchable; izzy is pinned and https; Phone keeps %d pages and %d source pills (2..8)" % (ids, len(pages), len(r["order"]) + 1))
sys.exit(1 if bad else 0)
PY
  rm -rf "$work"
  return $fails
}

echo "== real tree =="
check "$ROOT"; F=$?

M=0
mutate() {  # <name> <file-rel> <from> <to>
  local name="$1" rel="$2" from="$3" to="$4" box; box="$(mktemp -d)"
  mkdir -p "$box/ab_cloud-libs-shared/libs" "$box/$(dirname "$SAREL")" "$box/aa_cloud-superapp" "$box/ac_cloud-store"
  cp -r "$ROOT/ab_cloud-libs-shared/libs/appstore" "$box/ab_cloud-libs-shared/libs/"
  rm -rf "$box/ab_cloud-libs-shared/libs/appstore/build"
  mkdir -p "$box/fleet-private-data"; cp "$ROOT/fleet-private-data/appstore-install-sources.json" "$box/fleet-private-data/"   # #1566 fleet-private: CI materialises it
  cp "$ROOT/$SAREL" "$box/$SAREL"; cp "$ROOT/aa_cloud-superapp/build.json" "$box/aa_cloud-superapp/"; cp "$ROOT/ac_cloud-store/build.json" "$box/ac_cloud-store/"
  python3 - "$box/$rel" "$from" "$to" <<'PY' || { echo "  MUTATION NOT PLANTED: $name"; rm -rf "$box"; return 1; }
import sys
p, a, b = sys.argv[1:]
s = open(p, encoding='utf-8').read()
if a not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(a, b, 1))
PY
  local kot=skip; case "$rel" in *StoreSuggest.kt|*PhoneTaxonomyLayout.kt) kot="";; esac
  if check "$box" $kot >/dev/null 2>&1; then echo "  MUTATION SURVIVED: $name"; rm -rf "$box"; return 1; fi
  echo "  mutation caught: $name"; rm -rf "$box"; return 0
}
echo "== mutations =="
S=$STOREREL; A=$APPSREL
mutate "SuperApp goes back to its own bucketing loop" "$SAREL" 'PhoneTaxonomyLayout.bucket(sections, visible) { it.label }' 'myBucket(sections, visible).also { folder.label.startsWith(it.prefix) }' || M=$((M+1))
mutate "the landing suggests search hits" $S/StoreSearchPage.kt 'for ((pkg, label) in declaredList(ctx, cfg)) {' 'for ((pkg, label) in FDroidIndex.search(ctx, cfg.fdroid, "a").associate { it.pkg to it.name }) {' || M=$((M+1))
mutate "a shelf's row stops scrolling sideways" $S/StoreSearchPage.kt 'LazyRow(Modifier.fillMaxWidth().testTag(TAG_SHELF_ROW + shelf.folderId),' 'Column(Modifier.fillMaxWidth().testTag(TAG_SHELF_ROW + shelf.folderId),' || M=$((M+1))
mutate "result rows stop using StoreRow" $S/StoreSearchPage.kt 'StoreRow.Card(Modifier.fillMaxWidth()' 'Column(Modifier.fillMaxWidth()' || M=$((M+1))
mutate "Search opens its own connection" $S/StoreSearchPage.kt 'val body = SourceResolver.getBody(url) ?: return emptyList()' 'val body = java.net.URL(url).openConnection().getInputStream().reader().readText()' || M=$((M+1))
mutate "a search source with no searcher" fleet-private-data/appstore-install-sources.json '"id": "github"' '"id": "gitlab"' || M=$((M+1))
mutate "izzy loses its pin" fleet-private-data/appstore-install-sources.json '"cert_sha256": "3bf0d6ab' '"cert_sha256": "3bf0' || M=$((M+1))
if [ -n "$KC" ]; then
  mutate "empty sub-categories are shown" $S/StoreSuggest.kt 'val shown = folders.filter { !byFolder[it.id].isNullOrEmpty() }' 'val shown = folders.also { f -> f.forEach { byFolder.getOrPut(it.id) { mutableListOf() } } }' || M=$((M+1))
  mutate "unclaimed folders listed first" $A/PhoneTaxonomyLayout.kt 'return if (other.isEmpty()) out else out + Group(null, "", other.toList())' 'return if (other.isEmpty()) out else listOf(Group<F>(null, "", other.toList())) + out' || M=$((M+1))
  mutate "sections in folder order, not declared order" $A/PhoneTaxonomyLayout.kt 'val out = bySection.filterValues { it.isNotEmpty() }.map' 'val out = bySection.filterValues { it.isNotEmpty() }.entries.sortedBy { e -> folders.indexOf(e.value.first()) }.associate { it.key to it.value }.map' || M=$((M+1))
  mutate "merge keys by title, not package" $S/StoreSuggest.kt 'val p = h.pkg.trim()' 'val p = h.title.trim()' || M=$((M+1))
  mutate "a repository never joins its package" $S/StoreSuggest.kt 'if (p != null) merged.getValue(p).add(h) else' 'if (false) merged.getValue(p!!).add(h) else' || M=$((M+1))
  mutate "installed apps no longer first in a group" $S/StoreSuggest.kt '.sortedByDescending { it.pkg.isNotEmpty() && installed(it.pkg) }' '.sortedBy { it.title }' || M=$((M+1))
fi
echo
[ $F -eq 0 ] && [ $M -eq 0 ] && { echo "OK: the Search landing follows SuperApp > Phone's taxonomy from our list, and merged search de-duplicates by package; every planted defect is caught"; exit 0; }
echo "FAILED: $F check(s) failed, $M mutation(s) survived"; exit 1
