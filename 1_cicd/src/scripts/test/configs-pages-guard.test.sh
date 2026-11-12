#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ configs-pages-guard.test — prove the Configs ▸ General / ▸ About      ║
# ║ guard FAILS on each rule it holds, and passes on the real tree        ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# Two parts. (1) the real tree must pass. (2) a fixture made of the real files the guard reads (every app's
# build.json and debug-api.json, the sources that reach the pages, the fleet endpoints, the web server, the licence
# map, the libs:ui-nav-bars sources and the rule file) must pass, then is broken one rule at a time; every break must
# be proven to have landed and the guard must go red NAMING the rule. Changes the rule allows (reordering sections,
# dropping the optional App section) must stay green.
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-configs-pages-guard.py"
DATA=1_cicd/src/data/configs-pages.json
UIBARS=ab_cloud-libs-shared/libs/ui-nav-bars
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$UIBARS/fleet-configs.gradle"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"; BASE="$WORK/base"

# ── (1) the real tree ───────────────────────────────────────────────────────
out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"
else fail "the real tree is red (rc=$rc)"; printf '%s\n' "$out" | tail -20; fi

# ── (2) the fixture ─────────────────────────────────────────────────────────
mkdir -p "$BASE"
(
    cd "$ROOT" || exit 1
    {
        git ls-files -- 'a[acd]_*/build.json' 'a[acd]_*/debug-api.json'
        git grep -l -E 'NavBarTopFirstRowScaffold\(|FleetConfigsActivity\.intent\(|FleetConfigsMenu\(|FleetConfigsButtons\(|FleetConfigsEntries\.(addTo|addButtons|open)\(' -- 'a[acd]_*' ':!*/test/*' ':!*/androidTest/*'
        printf '%s\n' "$DATA" ac_cloud-account/build.json ac_cloud-c3-webserver/build.json licenses/curated.json
        # the files G4 reads to see that a shortcut_reach app links libs:ui-nav-bars: its settings / build scripts or a patch of its series (Cloud Office, #1630)
        git grep -l -F ':libs:ui-nav-bars' -- 'a[acd]_*' | grep -E '(settings|build)(-extras)?\.gradle(\.kts)?$|\.patch$'
        # #1652 an app whose UI lives in a shared lib module (build.json::android.ui_module_dirs): that module's sources are the app's
        python3 - <<'PY'
import glob, json, os, subprocess
for b in sorted(glob.glob("a[acd]_*/build.json")):
    for d in (json.load(open(b)).get("android") or {}).get("ui_module_dirs") or []:
        top = os.path.normpath(os.path.join(os.path.dirname(b), d))
        print(subprocess.run(["git", "ls-files", "--", top + "/*.kt", top + "/*.java"], capture_output=True, text=True).stdout, end="")
PY
        find "$UIBARS" ab_cloud-libs-shared/libs/perms -type f -not -path "*/build/*"
    } | sort -u | while IFS= read -r f; do [ -f "$f" ] && cp --parents "$f" "$BASE"; done
)
stage() { rm -rf "$T"; cp -r "$BASE" "$T"; }

APP=ac_cloud-mail            # on the shared scaffold, talks to the jmap service
PEND=ac_c3-watchdog          # a WebView app wired through the lib's menu helper (FleetConfigsMenu)
APPKT="$(cd "$BASE" && grep -rl 'NavBarTopFirstRowScaffold(' "$APP" | head -1)"
[ -n "$APPKT" ] || { echo "ERROR: no scaffold call found under $APP — the fixture moved"; exit 1; }
DECL=$UIBARS/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigs.kt

stage
out="$(python3 "$GUARD" "$T")"; rc=$?
[ "$rc" -eq 0 ] && ok "the fixture passes" || { fail "the fixture is red (rc=$rc)"; printf '%s\n' "$out" | tail -10; }

# mutate <label> <file> <python-edit-of-s> <text the guard must print>
mutate() {
    stage
    local before after out rc
    before="$(cat "$T/$2" 2>/dev/null)"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$T/$2"
    after="$(cat "$T/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
# green <label> <file> <python-edit-of-s>: a change the guard must ACCEPT
green() {
    stage
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$T/$2"
    local out rc
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 0 ]; then ok "$1 passes"; else fail "$1 is red (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
J() { printf "d=json.loads(s); %s; s=json.dumps(d)" "$1"; }
CP="d['configs_pages']"
SEC="$CP['general']['sections']"
AB="$CP['about']"

# G1 the pages are declared
mutate "an app without configs_pages"          "$APP/build.json" "$(J "del $CP")"            "G1 $APP: build.json has no configs_pages"
mutate "an app without About"                  "$APP/build.json" "$(J "del $AB")"            "G1 $APP: build.json has no configs_pages"
mutate "an app without General"                "$APP/build.json" "$(J "del $CP['general']")" "G1 $APP: build.json has no configs_pages"
mutate "Cloud SuperApp loses its exemption"    "$DATA" "$(J "del d['exempt']['aa_cloud-superapp']")" "G1 aa_cloud-superapp: build.json has no configs_pages"
# G2 the General sections
for sid in perms battery storage notifications ui language; do
    mutate "General without its $sid section"  "$APP/build.json" "$(J "$SEC[:]=[x for x in $SEC if x['id']!='$sid']")" "G2 $APP: configs_pages.general.sections must list '$sid' exactly once"
done
mutate "a General section listed twice"        "$APP/build.json" "$(J "$SEC.append({'id':'perms'})")" "G2 $APP: configs_pages.general.sections must list 'perms' exactly once"
mutate "an unknown General section"            "$APP/build.json" "$(J "$SEC.append({'id':'wifi'})")"  "G2 $APP: configs_pages.general.sections names 'wifi'"
green  "General with its sections reordered"   "$APP/build.json" "$(J "$SEC.reverse()")"
green  "General placing the default Update section itself" "$APP/build.json" "$(J "$SEC.append({'id':'update'})")"
green  "General without the optional App section" "$APP/build.json" "$(J "$SEC[:]=[x for x in $SEC if x['id']!='app']")"
# G3 the About fields (v2: About is extensive)
for f in tagline description features screens guide privacy backends related; do
    mutate "About without $f"                  "$APP/build.json" "$(J "del $AB['$f']")" "G3 $APP: configs_pages.about.$f is missing"
done
mutate "the v1 field what is back"              "$APP/build.json" "$(J "$AB['what']='The mail app of the fleet, over JMAP.'")" "G3 $APP: configs_pages.about.what is the v1 field"
mutate "a tagline too short"                    "$APP/build.json" "$(J "$AB['tagline']='Mail'")"         "G3 $APP: configs_pages.about.tagline must be"
mutate "a tagline too long"                     "$APP/build.json" "$(J "$AB['tagline']='x'*91")"         "G3 $APP: configs_pages.about.tagline must be"
mutate "a one-line description"                 "$APP/build.json" "$(J "$AB['description']='Mail over JMAP.'")" "G3 $APP: configs_pages.about.description must say what the app does and why"
mutate "four features only"                     "$APP/build.json" "$(J "$AB['features']=['a','b','c','d']")" "G3 $APP: configs_pages.about.features must be at least 5"
mutate "an empty feature"                       "$APP/build.json" "$(J "$AB['features'].append('  ')")"   "G3 $APP: configs_pages.about.features must be at least 5"
mutate "two screens only"                       "$APP/build.json" "$(J "$AB['screens']=$AB['screens'][:2]")" "G3 $APP: configs_pages.about.screens must be at least 3"
mutate "a screen without its line"              "$APP/build.json" "$(J "$AB['screens'][0]['what']=''")"   "G3 $APP: configs_pages.about.screens must be at least 3"
mutate "a two-step guide"                       "$APP/build.json" "$(J "$AB['guide']=$AB['guide'][:2]")"  "G3 $APP: configs_pages.about.guide must be at least 3"
mutate "a one-line privacy"                     "$APP/build.json" "$(J "$AB['privacy']='Private.'")"      "G3 $APP: configs_pages.about.privacy must be at least"
mutate "no related fleet app"                   "$APP/build.json" "$(J "$AB['related']=[]")"              "G3 $APP: configs_pages.about.related must name at least 1"
mutate "a related app that does not exist"      "$APP/build.json" "$(J "$AB['related'].append({'app':'ac_cloud-ghost','why':'x'})")" "G3 $APP: configs_pages.about.related entry"
mutate "a related app without a why"            "$APP/build.json" "$(J "$AB['related'][0]['why']=''")"    "G3 $APP: configs_pages.about.related entry"
mutate "a fork without credits"                 "$APP/build.json" "$(J "$AB.pop('credits',None)")"        "G3 $APP: a upstream-fork app credits its upstream"
mutate "a grant without a why"                  "$DATA" "$(J "del d['perm_why']['notifications']")"       "G3 $APP: grant 'notifications' has no why"
green  "a grant whose why the app overrides"    "$APP/build.json" "$(J "$AB['perms_why']={'notifications':'New mail.'}")"
mutate "backends not a list"                    "$APP/build.json" "$(J "$AB['backends']='jmap'")"         "G3 $APP: configs_pages.about.backends must be a list"
mutate "a backend that is no fleet service"     "$APP/build.json" "$(J "$AB['backends'].append({'id':'nope','why':'x'})")" "G3 $APP: backend 'nope' is not a service"
mutate "a backend without a why"                "$APP/build.json" "$(J "$AB['backends'].append({'id':'jmap'})")" "G3 $APP: every configs_pages.about.backends entry needs an id and a why"
mutate "a backend whose service has no mesh name" ac_cloud-account/build.json "$(J "[x.pop('mesh_dns') for x in d['fleet_endpoints']['services'] if x['id']=='jmap']")" "G3 $APP: backend 'jmap' has no mesh_dns"
mutate "an app the licence map does not know"   licenses/curated.json "$(J "del d['directories']['$APP']")"           "G3 $APP: About field 'license' does not resolve"
mutate "the crash sink without a port"          ac_cloud-c3-webserver/build.json "$(J "del d['runtime']['port']")"    "About field 'crash' does not resolve"
mutate "an app without a debug API port"        "$APP/debug-api.json" "$(J "d['ports']={}")"                         "G3 $APP: About field 'debug' does not resolve"
mutate "an app without a name"                  "$APP/build.json" "$(J "d.pop('display_name',None); d.pop('name',None)")" "G3 $APP: About field 'name' does not resolve"
# G4 the app reaches the pages
mutate "an app whose sources stop drawing the scaffold" "$APPKT" "s=s.replace('NavBarTopFirstRowScaffold(','OwnScaffold(')" "G4 $APP: no source reaches Configs"
# #1652 an app whose UI is a shared lib module: G4 reads the declared module, so the lib losing the scaffold is that app's finding
UIAPP=ac_cloud-writer
UIKT="$(cd "$BASE" && python3 -c "import json,os,sys; b=json.load(open('$UIAPP/build.json')); print('\n'.join(os.path.normpath(os.path.join('$UIAPP',d)) for d in b['android']['ui_module_dirs']))" | while IFS= read -r d; do grep -rl 'NavBarTopFirstRowScaffold(' "$d"; done)"
if [ -z "$UIKT" ]; then fail "no scaffold call under $UIAPP's android.ui_module_dirs — the fixture moved"; else
    stage
    for f in $UIKT; do sed -i 's/NavBarTopFirstRowScaffold(/OwnScaffold(/g' "$T/$f"; done
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "G4 $UIAPP: no source reaches Configs" <<<"$out"; then ok "an app whose UI lib stops drawing the scaffold goes red (G4 $UIAPP)"
    else fail "an app whose UI lib stops drawing the scaffold stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
    green "the UI lib still draws it while the app's own shell names no scaffold" "$UIAPP/build.json" "s=s"
fi
mutate "a pending entry for an app that is wired" "$DATA" "$(J "d['pending']['$APP']='a reason that is long enough to pass'")" "G4 $APP: reaches Configs ▸ General / ▸ About now"
PENDKT="$(cd "$BASE" && grep -rlE 'FleetConfigsMenu\(|FleetConfigsEntries\.|FleetConfigsActivity\.intent\(' "$PEND" | head -1)"
if [ -n "$PENDKT" ]; then
    mutate "a menu-wired app that drops its Configs menu" "$PENDKT" "s=re.sub(r'FleetConfigsMenu\(|FleetConfigsEntries\.(addTo|addButtons|open)\(|FleetConfigsActivity\.intent\(','OwnSettings(',s)" "G4 $PEND: no source reaches Configs"
else fail "no Configs menu call under $PEND — the fixture moved"; fi
# G5 the ledgers
mutate "a pending entry for no app"             "$DATA" "$(J "d['pending']['ac_cloud-ghost']='a reason that is long enough to pass'")" "G5 $DATA::pending names ac_cloud-ghost"
mutate "a pending entry without a reason"       "$DATA" "$(J "d['pending']['$APP']='todo'")"                      "G5 $DATA::pending.$APP needs a reason"
mutate "an app both exempt and pending"         "$DATA" "$(J "d['pending']['$APP']='a reason that is long enough to pass'; d['exempt']['$APP']='a reason that is long enough to pass'")" "G5 $APP is both exempt and pending"
# G0 the lib
mutate "the lib changes the required sections"  "$DECL" "s=s.replace('\"language\")','\"lang\")')" "G0 $DECL REQUIRED_SECTIONS"
mapfile -t HOLDS < <(python3 - "$ROOT/$DATA" <<'PY'
import json, sys
for h in json.load(open(sys.argv[1]))["lib"]["must_hold"]:
    print("\t".join((h["file"], repr(h["needle"]), h["needle"].replace("\n", "\\n"))))
PY
)
[ "${#HOLDS[@]}" -ge 12 ] || fail "lib.must_hold lists only ${#HOLDS[@]} entries"
for line in "${HOLDS[@]}"; do
    IFS=$'\t' read -r hf hneedle hraw <<<"$line"
    mutate "the lib loses \`$hraw\`" "$hf" "s=s.replace($hneedle,'XXGONE')" "no longer carries \`$hraw\`"
done

[ "$FAILURES" -eq 0 ] && echo "configs-pages-guard.test: OK" || { echo "configs-pages-guard.test: $FAILURES FAILED"; exit 1; }
