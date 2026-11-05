#!/usr/bin/env bash
# #876 Cloud Account, web edition: the Kotlin/Wasm page that web/ builds.
#
# What this holds about the built page (build/dist/wasmJs/productionExecutable):
#  1. it is a page: index.html loads the app's script, and beside that script sit
#     (at least) two real WebAssembly modules - the app's and skiko's canvas renderer;
#     the bundler names both by content hash, so they are recognised by the \0asm magic;
#  2. it fits the budget declared in build.json::web.size_budget_mb (an accidental
#     dependency is a megabyte count before it is a complaint);
#  3. it carries the fleet's nav (#876): composeResources/ holds account-nav.json, the build.json::ui the Android shell
#     bakes (island ids, every section's child page ids), and the app's wasm module holds the strings of the shared
#     island and tab strip (bottomnav_island, bottomnav_item_, pagetabs_strip) and the mock page ("sample data");
#  4. it names no Android: the app's own JS glue carries no `android.` platform name and no
#     androidx.(activity|fragment|core|lifecycle|navigation|appcompat) (a shared lib that
#     leaked an Android type into commonMain shows up here even if the purity guard missed
#     it). Compose's own androidx.compose.* package names are the library, not Android.
#
# Then it plants each defect below in a scratch copy of a (synthetic) dist and requires
# the checks to go red, so a check that cannot fail does not count as a check. That part
# needs no build and always runs.
#
# The REAL dist exists only after `./gradlew wasmJsBrowserDistribution` in web/. Locally,
# without one, the real-dist half SKIPS with its reason. ship-cloud-account-wasm.yml builds
# first and sets CLOUD_WEB_REQUIRE_DIST=1: there a missing dist is a FAILURE, because a
# green run that checked nothing is the lie this tester exists to refuse.
#
# Usage: ./test-account-web.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APPD="$(cd "$HERE/.." && pwd)"
DIST="$APPD/web/build/dist/wasmJs/productionExecutable"
BUDGET_MB="$(jq -r '.web.size_budget_mb // empty' "$APPD/build.json")"
[ -n "$BUDGET_MB" ] || { echo "FAIL build.json::web.size_budget_mb is not declared"; exit 1; }

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }

# check <dist-dir> <budget-mb>  → prints one PASS/FAIL line per property, returns #fails
check() {
  local dist="$1" budget="$2" n=0 f js w nw=0
  [ -f "$dist/index.html" ] || { echo "FAIL  index.html is missing"; n=$((n + 1)); }
  grep -q 'cloud-account\.js' "$dist/index.html" 2>/dev/null \
    || { echo "FAIL  index.html does not load cloud-account.js"; n=$((n + 1)); }
  [ -s "$dist/cloud-account.js" ] || { echo "FAIL  cloud-account.js is missing or empty"; n=$((n + 1)); }
  for w in "$dist"/*.wasm; do
    [ -s "$w" ] || continue
    if [ "$(head -c 4 "$w" | od -An -tx1 | tr -d ' \n')" = "0061736d" ]; then nw=$((nw + 1))
    else echo "FAIL  $(basename "$w") is not a WebAssembly module (no \\0asm magic)"; n=$((n + 1)); fi
  done
  [ "$nw" -ge 2 ] || { echo "FAIL  $nw WebAssembly module(s); the page needs its own and skiko's"; n=$((n + 1)); }
  find "$dist/composeResources" -name account-nav.json 2>/dev/null | head -1 | xargs grep -q '"bottom_nav"' 2>/dev/null \
    || { echo "FAIL  composeResources/ carries no account-nav.json with a bottom_nav"; n=$((n + 1)); }
  # #896 the baked nav IS build.json::ui: the island's ids and every section's child page ids.
  local navf want have
  navf="$(find "$dist/composeResources" -name account-nav.json 2>/dev/null | head -1)"
  if [ -n "$navf" ] && grep -q '"bottom_nav"' "$navf" 2>/dev/null; then
    want="$(jq -c '[.ui.bottom_nav, [.ui.sections[] | [.id, [.pages[]?.id]]], .ui.default_section]' "$APPD/build.json")"
    have="$(jq -c '[.bottom_nav, [.sections[] | [.id, [.pages[]?.id]]], .default_section]' "$navf" 2>/dev/null)"
    [ "$want" = "$have" ] || { echo "FAIL  account-nav.json is not build.json::ui (island, sections or child pages differ)"; n=$((n + 1)); }
  fi
  # Kotlin/Wasm keeps string literals in the module's data, not in the JS glue: look in both.
  for t in bottomnav_island bottomnav_item_ pagetabs_strip sample data; do
    grep -qa -e "$t" "$dist"/*.wasm "$dist"/cloud-account.js 2>/dev/null \
      || { echo "FAIL  the island string '$t' is in neither the wasm modules nor the glue"; n=$((n + 1)); }
  done
  local kb limit
  kb="$(du -sk "$dist" | cut -f1)"; limit=$((budget * 1024))
  [ "$kb" -le "$limit" ] || { echo "FAIL  dist is ${kb} KB, over the ${budget} MB budget"; n=$((n + 1)); }
  for js in "$dist"/cloud-account*.js "$dist"/cloud-account*.mjs; do
    [ -f "$js" ] || continue
    if grep -qE '(^|[^A-Za-z0-9_])android\.[A-Za-z]|androidx\.(activity|fragment|core|lifecycle|navigation|appcompat)' "$js"; then
      echo "FAIL  $(basename "$js") names android: $(grep -oE '.{0,20}(android\.[A-Za-z]|androidx\.(activity|fragment|core|lifecycle|navigation|appcompat)).{0,20}' "$js" | head -1)"; n=$((n + 1))
    fi
  done
  [ "$n" -eq 0 ] && echo "PASS  dist is a page, carries the fleet nav, holds the island, fits ${budget} MB, names no android"
  return "$n"
}

# ── the real dist ────────────────────────────────────────────────────────────
if [ -d "$DIST" ]; then
  out="$(check "$DIST" "$BUDGET_MB")"; rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  [ "$rc" -eq 0 ] && ok "the built dist holds (web/build/dist/wasmJs/productionExecutable)" || bad "the built dist is red"
elif [ "${CLOUD_WEB_REQUIRE_DIST:-}" = "1" ]; then
  bad "CLOUD_WEB_REQUIRE_DIST=1 but $DIST does not exist — the wasm build produced nothing"
else
  echo "SKIP  built dist: $DIST is absent (run ./gradlew wasmJsBrowserDistribution in web/; CI sets CLOUD_WEB_REQUIRE_DIST=1 and fails instead)"
fi

# ── the checks can fail: plant each defect in a synthetic dist ───────────────
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
stage() {
  rm -rf "$W/d"; mkdir -p "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files"
  printf '<html><script src="cloud-account.js"></script></html>' > "$W/d/index.html"
  printf 'var app = "androidx.compose.ui.window";' > "$W/d/cloud-account.js"
  printf '\0asm\1\0\0\0' > "$W/d/aaaa1111.wasm"; printf '\0asm\1\0\0\0bottomnav_island bottomnav_item_ pagetabs_strip sample data' > "$W/d/bbbb2222.wasm"
  jq -c '{sections: .ui.sections, bottom_nav: .ui.bottom_nav, default_section: .ui.default_section}' "$APPD/build.json" > "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json"
}
mutant() {  # mutant <label> <shell-edit-of-$W/d> <expected-substring>
  stage
  eval "$2"
  local out; out="$(check "$W/d" "$BUDGET_MB")"; local rc=$?
  if [ "$rc" -ne 0 ] && grep -qF -- "$3" <<<"$out"; then ok "$1 goes red ($3)"
  else bad "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | sed 's/^/    /'; fi
}
stage
out="$(check "$W/d" "$BUDGET_MB")"; rc=$?
[ "$rc" -eq 0 ] && ok "the unbroken synthetic dist is green (Compose's androidx.compose.* names included)" || { bad "the unbroken synthetic dist is red"; printf '%s\n' "$out" | sed 's/^/    /'; }
mutant "no index.html"                  'rm "$W/d/index.html"'                                            "index.html is missing"
mutant "index.html forgets the script"  'printf "<html></html>" > "$W/d/index.html"'                      "does not load cloud-account.js"
mutant "no script"                      'rm "$W/d/cloud-account.js"'                                        "cloud-account.js is missing"
mutant "only one wasm module"           'rm "$W/d/bbbb2222.wasm"'                                         "1 WebAssembly module(s)"
mutant "a wasm that is not wasm"        'printf "<html>404</html>" > "$W/d/bbbb2222.wasm"'                "not a WebAssembly module"
mutant "no nav declaration"             'rm "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json"' "no account-nav.json with a bottom_nav"
mutant "a nav declaration with no bar"  'printf "{}" > "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json"' "no account-nav.json with a bottom_nav"
mutant "a stale nav declaration (an island that is not build.json::ui)" 'jq -c ".bottom_nav = [\"stale\"]" "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json" > "$W/n" && mv "$W/n" "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json"' "account-nav.json is not build.json::ui"
mutant "a nav declaration without Vault's child pages" 'jq -c "(.sections[] | select(.id == \"vault\") | .pages) = []" "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json" > "$W/n" && mv "$W/n" "$W/d/composeResources/com.diegonmarcos.cloudaccount.web.generated/files/account-nav.json"' "account-nav.json is not build.json::ui"
mutant "no island in the page"          'printf "\0asm\1\0\0\0" > "$W/d/bbbb2222.wasm"'                  "the island string 'bottomnav_island'"
mutant "no page-tab strip in the page"  'printf "\0asm\1\0\0\0bottomnav_island bottomnav_item_" > "$W/d/bbbb2222.wasm"' "the island string 'pagetabs_strip'"
mutant "over the size budget"           'head -c $(( (BUDGET_MB + 1) * 1048576 )) /dev/zero > "$W/d/cloud-account.js.map"' "over the ${BUDGET_MB} MB budget"
mutant "android.* in the glue"          'printf "var c = \"android.content.Context\";" > "$W/d/cloud-account.js"' "cloud-account.js names android"
mutant "androidx.fragment in the glue"  'printf "var c = \"androidx.fragment.app.Fragment\";" > "$W/d/cloud-account.js"' "cloud-account.js names android"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
