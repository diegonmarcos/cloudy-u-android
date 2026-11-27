#!/usr/bin/env bash
# test-wiki-1984: Home's view tabs read 3D | List | Table | Graph and a cold start opens Home on 3D (#1984).
#   V1 build.json::ui.home declares view_modes = planet, list, table, graph (3D first; all four views stay)
#   V2 ui.home.default_view = planet and ui.home.start_page = home (the library's Home)
#   V3 Kotlin hard-codes neither: HomePage reads Decl.homeDefault / Decl.homeModes, WikiShell starts on Decl.homeStart, Decl reads the baked UI_HOME_*
#   V4 app/build.gradle bakes UI_HOME_START / UI_HOME_MODES / UI_HOME_DEFAULT from build.json::ui.home
# then plants a mutation per rule in a scratch copy and requires RED for each (green only when every mutant fails).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
# MUTATION is read from the environment and only an allow-listed name is ever used.
ALLOW=" order-list-first default-list start-other drop-view hardcode-default hardcode-order hardcode-start unbaked "

check() {   # check <app dir> -> FAIL lines, exit 1 on any
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
rd = lambda p: open(os.path.join(app, p), encoding="utf-8").read()
home = json.loads(rd("build.json"))["ui"].get("home", {})
if home.get("view_modes") != ["planet", "list", "table", "graph"]: bad.append("V1 view_modes is %r, not 3D | List | Table | Graph" % home.get("view_modes"))
if home.get("default_view") != "planet": bad.append("V2 default_view is %r, not planet (3D)" % home.get("default_view"))
if home.get("start_page") != "home": bad.append("V2 start_page is %r, not home" % home.get("start_page"))
kt = "app/src/main/java/com/diegonmarcos/cloudwiki/"
hp, sh, de = rd(kt + "ui/HomePage.kt"), rd(kt + "ui/WikiShell.kt"), rd(kt + "Decl.kt")
if "Decl.homeDefault" not in hp or re.search(r"mutableStateOf\(HomeIndex\.Mode\.\w+\.id\)", hp): bad.append("V3 HomePage does not take its default view from Decl.homeDefault")
if "Decl.homeModes" not in hp or "HomeIndex.Mode.entries.map" in hp: bad.append("V3 HomePage does not take the tab order from Decl.homeModes")
if "Decl.homeStart" not in sh or "?: wiki.pages.first().id" in sh: bad.append("V3 WikiShell does not start on Decl.homeStart")
if not all(x in de for x in ("BuildConfig.UI_HOME_START", "BuildConfig.UI_HOME_MODES", "BuildConfig.UI_HOME_DEFAULT")): bad.append("V3 Decl does not read the baked UI_HOME_*")
g = rd("app/build.gradle")
if not all(x in g for x in ('"UI_HOME_START"', '"UI_HOME_MODES"', '"UI_HOME_DEFAULT"', "ui?.home?.view_modes", "ui?.home?.default_view", "ui?.home?.start_page")): bad.append("V4 build.gradle does not bake build.json::ui.home")
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}

out="$(check "$APP")"; rc=$?
[ $rc -eq 0 ] || { echo "$out"; echo "RED: the real tree fails"; exit 1; }
echo "real tree green"

mutate() {  # mutate <name> <scratch app dir>
  case " $ALLOW " in *" $1 "*) ;; *) echo "ERROR: mutation $1 not allow-listed"; return 2;; esac
  local d="$2" k="$2/app/src/main/java/com/diegonmarcos/cloudwiki"
  case "$1" in
    order-list-first) python3 - "$d/build.json" <<'PY'
import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace('"view_modes": ["planet", "list", "table", "graph"]','"view_modes": ["list", "planet", "table", "graph"]'))
PY
    ;;
    default-list) sed -i 's/"default_view": "planet"/"default_view": "list"/' "$d/build.json" ;;
    start-other)  sed -i 's/"start_page": "home"/"start_page": "sciences"/' "$d/build.json" ;;
    drop-view)    sed -i 's/"view_modes": \["planet", "list", "table", "graph"\]/"view_modes": ["planet", "list", "table"]/' "$d/build.json" ;;
    hardcode-default) sed -i 's/mutableStateOf(Decl.homeDefault.id)/mutableStateOf(HomeIndex.Mode.Outline.id)/' "$k/ui/HomePage.kt" ;;
    hardcode-order)   sed -i 's/pages = Decl.homeModes.map {/pages = HomeIndex.Mode.entries.map {/' "$k/ui/HomePage.kt" ;;
    hardcode-start)   sed -i 's/HomeIndex.startPage(Decl.homeStart, wiki.pages.map { it.id })/wiki.pages.first().id/' "$k/ui/WikiShell.kt" ;;
    unbaked)          sed -i 's/"UI_HOME_DEFAULT"/"UI_HOME_X"/' "$d/app/build.gradle" ;;
  esac
}

fail=0
for m in ${MUTATION:-$ALLOW}; do
  s="$(mktemp -d)"; mkdir -p "$s/app/src/main/java/com/diegonmarcos/cloudwiki/ui"
  cp "$APP/build.json" "$s/"; cp "$APP/app/build.gradle" "$s/app/" 2>/dev/null
  cp "$APP/app/src/main/java/com/diegonmarcos/cloudwiki/Decl.kt" "$s/app/src/main/java/com/diegonmarcos/cloudwiki/"
  cp "$APP/app/src/main/java/com/diegonmarcos/cloudwiki/ui/HomePage.kt" "$APP/app/src/main/java/com/diegonmarcos/cloudwiki/ui/WikiShell.kt" "$s/app/src/main/java/com/diegonmarcos/cloudwiki/ui/"
  mutate "$m" "$s" || { fail=1; rm -rf "$s"; continue; }
  if check "$s" >/dev/null 2>&1; then echo "MUTANT SURVIVED (suite blind): $m"; fail=1; else echo "mutant red: $m"; fi
  rm -rf "$s"
done
[ $fail -eq 0 ] && echo "GREEN: real tree passes, every mutant red" || { echo "FAIL"; exit 1; }
