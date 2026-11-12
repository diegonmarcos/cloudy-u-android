#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #705 — Cloud Drive REACHES gh, it does not CARRY it: gh runs in the gh     ║
# ║ engine (Cloud-Lib-Gh.apk) and the GitHub card is a thin client over IPC    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Cloud Drive used to compile libs:gh and ship the 39 MB static gh inside its own
# APK, so every gh change republished Cloud Drive. The engine half of the move is
# pinned by ab_cloud-libs-shared/lib-apks/test/test-engine-services.sh (it runs on
# the Cloud Libs ship, the only one that watches libs/gh now). This is the CLIENT
# half, and it reads only Cloud Drive's own source so a failure here is Cloud
# Drive's and stops its release:
#
#   D1  NOT CARRIED: no module map entry, no gradle link, no import of the gh
#       library, and the ship workflow no longer watches libs/gh — the one line
#       that would make a gh change republish this app again.
#   D2  DECLARED: build.json::engines.gh names its Store row (fleet), the action
#       (from {package}), the contract this build needs and the sign-in pacing;
#       build.gradle resolves the package from the fleet manifest, fails the
#       build on an unknown id, and bakes every field.
#   D3  VISIBLE: the manifest queries the engine's package, or Android 11+ hides
#       it and "installed" reads as "not installed".
#   D4  HANDSHAKE BEFORE BIND: the client resolves the service by action and
#       package with its meta-data, refuses a contract below the declared one,
#       and only then builds the binder client; every call goes through it.
#   D5  LOUD: missing and too-old are two different lines, each naming the
#       Store; the page's GitHub card calls the engine and nothing else.
#   D6  NO SECRET LEAVES: the credential the engine hands back has a redacted
#       toString, nothing in the client writes to logcat or stdout directly,
#       and the one log it does keep (DriveDebugLog, the sign-in's steps — a
#       sign-in once failed on a phone with no line about it anywhere) never
#       carries the secret or the engine's raw credential answer.
#   D7  A PAGE THE SIGN-IN OPENS IS ON THE DECLARED HOST, checked on this side of
#       the binder too.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
MAIN="$APP/app/src/main/java"
CLIENT="$MAIN/com/diegonmarcos/clouddrive/sync/GhEngine.kt"
PAGE="$MAIN/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
STR="$APP/app/src/main/res/values/strings.xml"
WF="$ROOT/1_cicd/src/cicd/ship-cloud-drive.yml"
for required in "$BJ" "$GRADLE" "$MANIFEST" "$CLIENT" "$PAGE" "$STR" "$WF"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
# a file's CODE: comment lines dropped, because these files EXPLAIN what they no longer do
_code() { grep -vE '^[[:space:]]*(\*|//|/\*|<!--)' "$1"; }

# d1 <build.json> <app/build.gradle> <main java root> <workflow>
d1() {
    local bj="$1" gradle="$2" main="$3" wf="$4" bad=0 hit
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8")).get("modules") or {}
bad = 0
if "libs:gh" in m:
    print("    build.json::modules declares libs:gh again — settings.gradle would compile gh into this app"); bad = 1
if "libs:gh" in ((m.get("app") or {}).get("depends_on") or []):
    print("    build.json::modules.app.depends_on lists libs:gh"); bad = 1
sys.exit(bad)
PYTHON
    [ "$(_code "$gradle" | grep -cE "project\\(['\"]:libs:gh['\"]\\)")" -eq 0 ] \
        || { echo "    app/build.gradle links :libs:gh — the 39 MB binary is back inside Cloud Drive"; bad=1; }
    hit="$(grep -rnE '^import com\.diegonmarcos\.cloudlib\.gh\.' "$main" || true)"
    [ -z "$hit" ] || { echo "    the app imports the gh library it no longer compiles:"; printf '%s\n' "$hit" | sed 's/^/        /'; bad=1; }
    grep -qF '"ab_cloud-libs-shared/libs/gh/**"' "$wf" \
        && { echo "    ship-cloud-drive.yml watches libs/gh — every gh change would republish Cloud Drive again"; bad=1; }
    return $bad
}

# d2 <build.json> <app/build.gradle>
d2() {
    local bj="$1" gradle="$2" bad=0 field
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
e = (json.load(open(sys.argv[1], encoding="utf-8")).get("engines") or {}).get("gh") or {}
bad = 0
if not isinstance(e.get("fleet"), str) or not e["fleet"].strip():
    print("    engines.gh.fleet does not name the engine's Store row"); bad = 1
a = e.get("action")
if not isinstance(a, str) or "{package}" not in a or not a.endswith(".ENGINE"):
    print("    engines.gh.action %r is not the engine's {package}.ENGINE action" % (a,)); bad = 1
c = e.get("min_contract")
if not isinstance(c, int) or isinstance(c, bool) or c < 1:
    print("    engines.gh.min_contract %r is not a contract number >= 1" % (c,)); bad = 1
# #1693 gh's sign-in runs in Cloud Account: this app declares no login pacing, it only READS the engine.
if "login_poll_ms" in e or "login_max_ms" in e:
    print("    engines.gh still declares sign-in pacing (login_poll_ms / login_max_ms): the sign-in is Cloud Account's"); bad = 1
sys.exit(bad)
PYTHON
    grep -qF 'def ghEngineFleet = fleetById[ghEngineDecl.fleet]' <<<"$(_code "$gradle")" \
        || { echo "    build.gradle does not resolve the engine's package from the fleet manifest"; bad=1; }
    python3 - "$gradle" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"if \(ghEngineFleet == null\) \{(.*?)\n\}", src, re.S)
if not m or "throw new GradleException" not in m.group(1):
    print("    an engine fleet id the manifest lacks does not fail the build"); sys.exit(1)
PYTHON
    for field in GH_ENGINE_PACKAGE GH_ENGINE_ACTION GH_ENGINE_MIN_CONTRACT; do
        grep -qE "buildConfigField +\"[a-zA-Z]+\", +\"$field\"" <<<"$(_code "$gradle")" \
            || { echo "    build.gradle does not bake $field"; bad=1; }
    done
    grep -qE 'ghEnginePackage: ghEnginePackage\]' <<<"$(_code "$gradle")" \
        || { echo "    the manifest placeholder ghEnginePackage is not the resolved package"; bad=1; }
    return $bad
}

# d3 <manifest>
d3() {
    python3 - "$1" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
names = [p.get(A + "name") for q in root.iter("queries") for p in q.iter("package")]
if "${ghEnginePackage}" not in names:
    print("    <queries> does not name ${ghEnginePackage}: on API 30+ the engine is invisible and reads as not installed")
    sys.exit(1)
PYTHON
}

# d4 <GhEngine.kt>
d4() {
    python3 - "$1" <<'PYTHON'
import re, sys
src = "\n".join(l for l in open(sys.argv[1], encoding="utf-8").read().split("\n") if not re.match(r"\s*(\*|//|/\*)", l))
bad = 0
def no(msg):
    global bad
    print("    " + msg); bad = 1
m = re.search(r"\n    fun check\(\): Check \{(.*?)\n    \}\n", src, re.S)
if not m:
    no("GhEngine has no check()"); sys.exit(1)
body = m.group(1)
if "resolveService(Intent(BuildConfig.GH_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)" not in body:
    no("check() does not resolve the service by the declared action in the declared package, with its meta-data")
if "metaData?.getInt(CONTRACT_KEY, 0)" not in body:
    no("check() does not read the engine's CONTRACT")
old = body.find("if (found < needed) return Check.TooOld(")
bind = body.find("DataBackendClient(")
if old < 0:
    no("check() does not refuse an engine below the declared contract")
if bind < 0 or (old >= 0 and bind < old):
    no("check() binds the engine before (or without) checking its contract")
if "val needed = BuildConfig.GH_ENGINE_MIN_CONTRACT" not in body:
    no("check() does not take the needed contract from the declaration")
if not re.search(r"if \(installed\) Check\.TooOld\(pkg, 0, needed\) else Check\.NotInstalled\(pkg\)", body):
    no("check() does not tell an engine APK with no service (too old) from no engine APK at all")
if 'const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"' not in src:
    no("the client reads a contract key that is not the engines' declared one")
a = re.search(r"\n    private fun ask\(method: String, vararg args: String\): JSONObject \{(.*?)\n    \}\n", src, re.S)
if not a or "val why = check()" not in a.group(1) or "why !is Check.Ready" not in a.group(1):
    no("ask() reaches the engine without the handshake")
calls = set(re.findall(r"\bc\.call\(", src))
if len(re.findall(r"\.call\(", src)) != 1:
    no("something other than ask() calls the engine's binder directly")
sys.exit(bad)
PYTHON
}

# d5 <page> <strings.xml>
d5() {
    local page="$1" str="$2" bad=0 sid want
    grep -qE 'is GhEngine\.Check\.NotInstalled -> ctx\.getString\(R\.string\.git_gh_missing, engine\.pkg\)' "$page" \
        || { echo "    a missing engine is not the git_gh_missing line"; bad=1; }
    grep -qE 'is GhEngine\.Check\.TooOld -> ctx\.getString\(R\.string\.git_gh_engine_old, engine\.pkg, engine\.found, engine\.needed\)' "$page" \
        || { echo "    an engine too old for this build is not the git_gh_engine_old line"; bad=1; }
    while IFS='|' read -r sid want; do
        grep -qE "name=\"$sid\">[^<]*$want" "$str" \
            || { echo "    $sid does not name its next step ($want)"; bad=1; }
    done <<'STEPS'
git_gh_missing|install it from Store ▸ Cloud Constellation ▸ Libs
git_gh_engine_old|update it from Store ▸ Cloud Constellation ▸ Libs
STEPS
    [ "$(_code "$page" | grep -cE '\bGhRunner\b|\bGhOutput\b|ghRunner')" -eq 0 ] \
        || { echo "    the page still drives gh in-process"; bad=1; }
    local need
    for need in 'ghEngine.status(ghHost)' 'ghEngine.repoList(ghLimit, GitHubRepos.GH_FIELDS)' 'ghEngine.credential(ghHost)'; do
        grep -qF "$need" "$page" || { echo "    the GitHub card does not reach the engine for: $need"; bad=1; }
    done
    return $bad
}

# d6 <GhEngine.kt>
d6() {
    local client="$1" bad=0 leak
    grep -qF 'override fun toString(): String = "Credential(username=$username, secret=<redacted>)"' "$client" \
        || { echo "    GhEngine.Credential prints its secret"; bad=1; }
    leak="$(_code "$client" | grep -nE '(^|[^A-Za-z])Log\.[a-z]+\(|println\(|printStackTrace' || true)"
    [ -z "$leak" ] || { echo "    the gh client writes to logcat or stdout directly:"; printf '%s\n' "$leak" | sed 's/^/        /'; bad=1; }
    leak="$(_code "$client" | grep -nE '(DriveDebugLog\.[a-z]+|[^A-Za-z]log)\(.*(secret|password|\$o\b|\$\{o\b)' || true)"
    [ -z "$leak" ] || { echo "    the gh client's log carries the credential:"; printf '%s\n' "$leak" | sed 's/^/        /'; bad=1; }
    return $bad
}

# d7 <GhEngine.kt> <page> : #1693 the engine is READ-ONLY here; the sign-in is a link to Cloud Account
d7() {
    local client="$1" page="$2" bad=0
    grep -qE 'fun login\(|LOGIN_START|LOGIN_POLL|loginStart|loginPoll|fun follow\(|pageOnHost' <<<"$(_code "$client")" \
        && { echo "    the gh client can start or follow a gh sign-in again"; bad=1; }
    grep -qE 'ghEngine\.login\(|GhEngine\.pageOnHost' <<<"$(_code "$page")" \
        && { echo "    the page starts a gh sign-in itself"; bad=1; }
    grep -qF 'AccountClient.openSignIn(ctx, SignInContract.PROVIDER_GITHUB)' "$page" \
        || { echo "    the GitHub sign-in is not a link to Cloud Account"; bad=1; }
    return $bad
}

echo "── D1 Cloud Drive does not carry gh ──"
d1 "$BJ" "$GRADLE" "$MAIN" "$WF" && pass "no module entry, no gradle link, no import, and the ship workflow does not watch libs/gh" \
    || fail "gh is compiled into Cloud Drive again, or a gh change still republishes it"
echo "── D2 the engine is declared and resolved from the fleet manifest ──"
d2 "$BJ" "$GRADLE" && pass "engines.gh names its Store row, action, contract and pacing; build.gradle resolves, refuses an unknown id and bakes all of it" \
    || fail "the gh engine is not declared, resolved or baked"
echo "── D3 the engine's package is visible to this app ──"
d3 "$MANIFEST" && pass "<queries> names \${ghEnginePackage}" || fail "the engine would be invisible on API 30+"
echo "── D4 the contract is checked before anything binds ──"
d4 "$CLIENT" && pass "the service is resolved by action+package with its contract, a lower one is refused, and only then is it bound; every call goes through the handshake" \
    || fail "the client binds an engine it has not checked"
echo "── D5 missing and too-old are loud, different, and name the Store ──"
d5 "$PAGE" "$STR" && pass "two lines, two next steps, both naming Store ▸ Cloud Constellation ▸ Libs; the card reaches gh only through the engine" \
    || fail "a missing or old engine is silent, or the page still runs gh itself"
echo "── D6 the credential never leaves through the client ──"
d6 "$CLIENT" && pass "redacted toString, no raw logcat, and the on-device log never carries the credential" || fail "the client can print or log the credential"
echo "── D7 the engine is read-only here: the GitHub sign-in is a link to Cloud Account ──"
d7 "$CLIENT" "$PAGE" && pass "no gh login in the client, none started by the page, the sign-in links to Cloud Account" || fail "this app can start a gh sign-in again"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }
# PROOF THE MUTATION APPLIED: the copy differs from the original AND carries the planted text.
_applied() {
    python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$1" "$2" "$3" \
        || { echo "  MUT-NOOP    the mutation did not apply ($3)"; HOLLOW=$((HOLLOW+1)); return 1; }
}
_gone() { python3 -c 'import sys; sys.exit(0 if sys.argv[2] not in open(sys.argv[1]).read() else 1)' "$1" "$2" \
        || { echo "  MUT-NOOP    the mutation did not remove ($2)"; HOLLOW=$((HOLLOW+1)); return 1; }; }
_sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$1" "$2" "$3"; }
_json() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$1" "$2"; }

echo "── MUT each property, broken on a copy, goes red ──"
# D1 — the module comes back, three ways, and the workflow watches it again
cp "$BJ" "$MUT/b.json"
_green d1 d1 "$MUT/b.json" "$GRADLE" "$MAIN" "$WF" && {
    _json "$MUT/b.json" 'd["modules"]["libs:gh"] = {"dir": "../ab_cloud-libs-shared/libs/gh", "type": "library"}'
    _applied "$BJ" "$MUT/b.json" '"libs:gh"' && _red "D1 build.json declares libs:gh again" d1 "$MUT/b.json" "$GRADLE" "$MAIN" "$WF"; }
cp "$GRADLE" "$MUT/app.gradle"
_green d1 d1 "$BJ" "$MUT/app.gradle" "$MAIN" "$WF" && {
    _sub "$MUT/app.gradle" "    implementation project(':libs:auth')" "    implementation project(':libs:auth')
    implementation project(':libs:gh')"
    _applied "$GRADLE" "$MUT/app.gradle" "project(':libs:gh')" && _red "D1 app/build.gradle links :libs:gh again" d1 "$BJ" "$MUT/app.gradle" "$MAIN" "$WF"; }
mkdir -p "$MUT/java/x"; cp "$PAGE" "$MUT/java/x/GitReposScreen.kt"
_green d1 d1 "$BJ" "$GRADLE" "$MUT/java" "$WF" && {
    _sub "$MUT/java/x/GitReposScreen.kt" 'import kotlinx.coroutines.Dispatchers' 'import com.diegonmarcos.cloudlib.gh.GhRunner
import kotlinx.coroutines.Dispatchers'
    _applied "$PAGE" "$MUT/java/x/GitReposScreen.kt" 'import com.diegonmarcos.cloudlib.gh.GhRunner' \
        && _red "D1 the page imports the gh library again" d1 "$BJ" "$GRADLE" "$MUT/java" "$WF"; }
cp "$WF" "$MUT/wf.yml"
_green d1 d1 "$BJ" "$GRADLE" "$MAIN" "$MUT/wf.yml" && {
    _sub "$MUT/wf.yml" '      - "ab_cloud-libs-shared/libs/git-sync/**"' '      - "ab_cloud-libs-shared/libs/gh/**"
      - "ab_cloud-libs-shared/libs/git-sync/**"'
    _applied "$WF" "$MUT/wf.yml" 'libs/gh/**' && _red "D1 the ship workflow watches libs/gh again" d1 "$BJ" "$GRADLE" "$MAIN" "$MUT/wf.yml"; }
# D2 — the declaration rots
cp "$BJ" "$MUT/b.json"
_green d2 d2 "$MUT/b.json" "$GRADLE" && {
    _json "$MUT/b.json" 'd["engines"]["gh"]["min_contract"] = 0'
    _applied "$BJ" "$MUT/b.json" '"min_contract": 0' && _red "D2 a contract of 0 accepts any engine" d2 "$MUT/b.json" "$GRADLE"; }
cp "$BJ" "$MUT/b.json"
_green d2 d2 "$MUT/b.json" "$GRADLE" && {
    _json "$MUT/b.json" 'd["engines"]["gh"]["action"] = "com.diegonmarcos.cloudlib.gh.ENGINE"'
    _applied "$BJ" "$MUT/b.json" '"com.diegonmarcos.cloudlib.gh.ENGINE"' && _red "D2 the action is a typed package, not {package}" d2 "$MUT/b.json" "$GRADLE"; }
cp "$GRADLE" "$MUT/app.gradle"
_green d2 d2 "$BJ" "$MUT/app.gradle" && {
    python3 - "$MUT/app.gradle" <<'PYTHON'
import re, sys
p = sys.argv[1]; s = open(p).read()
s = re.sub(r"if \(ghEngineFleet == null\) \{.*?\n\}", "if (ghEngineFleet == null) {\n    ghEngineFleet = [package: 'com.example.planted']\n}", s, count=1, flags=re.S)
open(p, "w").write(s)
PYTHON
    _applied "$GRADLE" "$MUT/app.gradle" 'com.example.planted' && _red "D2 an unknown fleet id falls back to a package instead of failing" d2 "$BJ" "$MUT/app.gradle"; }
cp "$GRADLE" "$MUT/app.gradle"
_green d2 d2 "$BJ" "$MUT/app.gradle" && {
    _sub "$MUT/app.gradle" '        buildConfigField "int",    "GH_ENGINE_MIN_CONTRACT", "${ghEngineMinContract}"
' ''
    _gone "$MUT/app.gradle" '"GH_ENGINE_MIN_CONTRACT"' && _applied "$GRADLE" "$MUT/app.gradle" 'GH_ENGINE_ACTION' \
        && _red "D2 the needed contract is not baked" d2 "$BJ" "$MUT/app.gradle"; }
# D3 — invisible
cp "$MANIFEST" "$MUT/AndroidManifest.xml"
_green d3 d3 "$MUT/AndroidManifest.xml" && {
    _sub "$MUT/AndroidManifest.xml" '        <package android:name="${ghEnginePackage}" />
' ''
    _gone "$MUT/AndroidManifest.xml" '"${ghEnginePackage}"' && _applied "$MANIFEST" "$MUT/AndroidManifest.xml" '${authMissionPackage}' \
        && _red "D3 the engine's package is not queried" d3 "$MUT/AndroidManifest.xml"; }
# D4 — bind before the handshake, no contract floor, no handshake on a call
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d4 d4 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" '        if (found < needed) return Check.TooOld(pkg, found, needed)
' ''
    _gone "$MUT/GhEngine.kt" 'if (found < needed)' && _applied "$CLIENT" "$MUT/GhEngine.kt" 'val found = service.metaData' \
        && _red "D4 an engine below the declared contract is bound anyway" d4 "$MUT/GhEngine.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d4 d4 "$MUT/GhEngine.kt" && {
    python3 - "$MUT/GhEngine.kt" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
bind = """        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
"""
gate = "        if (found < needed) return Check.TooOld(pkg, found, needed)\n"
assert bind in s and gate in s
s = s.replace(bind, "").replace(gate, bind + gate)
open(p, "w").write(s)
PYTHON
    _applied "$CLIENT" "$MUT/GhEngine.kt" 'service.name)
        }
        if (found < needed)' && _red "D4 the engine is bound before its contract is checked" d4 "$MUT/GhEngine.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d4 d4 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" '        val why = check()
        val c = client
        if (why !is Check.Ready || c == null)' '        val c = client
        if (c == null)'
    _applied "$CLIENT" "$MUT/GhEngine.kt" '        if (c == null) return' && _red "D4 a call reaches the engine without the handshake" d4 "$MUT/GhEngine.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d4 d4 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" 'return if (installed) Check.TooOld(pkg, 0, needed) else Check.NotInstalled(pkg)' 'return Check.NotInstalled(pkg)'
    _applied "$CLIENT" "$MUT/GhEngine.kt" '            return Check.NotInstalled(pkg)' && _red "D4 an engine APK too old to serve reads as not installed" d4 "$MUT/GhEngine.kt"; }
# D5 — silence, and the page drives gh itself
cp "$STR" "$MUT/strings.xml"
_green d5 d5 "$PAGE" "$MUT/strings.xml" && {
    _sub "$MUT/strings.xml" ' — update it from Store ▸ Cloud Constellation ▸ Libs.' '.'
    _applied "$STR" "$MUT/strings.xml" 'needs %3$d.<' && _red "D5 the too-old line names no next step" d5 "$PAGE" "$MUT/strings.xml"; }
cp "$PAGE" "$MUT/page.kt"
_green d5 d5 "$MUT/page.kt" "$STR" && {
    _sub "$MUT/page.kt" 'is GhEngine.Check.TooOld -> ctx.getString(R.string.git_gh_engine_old, engine.pkg, engine.found, engine.needed)' 'is GhEngine.Check.TooOld -> ctx.getString(R.string.git_gh_missing, engine.pkg)'
    _applied "$PAGE" "$MUT/page.kt" 'TooOld -> ctx.getString(R.string.git_gh_missing' && _red "D5 too-old is worded as missing" d5 "$MUT/page.kt" "$STR"; }
cp "$PAGE" "$MUT/page.kt"
_green d5 d5 "$MUT/page.kt" "$STR" && {
    _sub "$MUT/page.kt" 'ghEngine.credential(ghHost)' 'com.diegonmarcos.cloudlib.gh.GhRunner(ctx).credential(ghHost)'
    _applied "$PAGE" "$MUT/page.kt" 'GhRunner(ctx).credential' && _red "D5 the clone asks an in-process gh for the credential" d5 "$MUT/page.kt" "$STR"; }
# D6 — the secret leaks
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d6 d6 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" 'secret=<redacted>)"' 'secret=$secret)"'
    _applied "$CLIENT" "$MUT/GhEngine.kt" 'secret=$secret' && _red "D6 Credential prints the secret" d6 "$MUT/GhEngine.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d6 d6 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" '        val secret = o.optString("secret")' '        val secret = o.optString("secret")
        android.util.Log.d("GhEngine", "credential answer: $o")'
    _applied "$CLIENT" "$MUT/GhEngine.kt" 'Log.d("GhEngine"' && _red "D6 the client logs the engine's credential answer" d6 "$MUT/GhEngine.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d6 d6 "$MUT/GhEngine.kt" && {
    _sub "$MUT/GhEngine.kt" '        val held = ' '        log("credential for $host: $username / $secret")
        val held = '
    _applied "$CLIENT" "$MUT/GhEngine.kt" '$username / $secret' && _red "D6 the on-device log carries the secret" d6 "$MUT/GhEngine.kt"; }
# D7 — a sign-in comes back into this app
cp "$PAGE" "$MUT/page.kt"
_green d7 d7 "$CLIENT" "$MUT/page.kt" && {
    _sub "$MUT/page.kt" 'AccountClient.openSignIn(ctx, SignInContract.PROVIDER_GITHUB)' 'ghEngine.login(ghHost) { _, _ -> }.let { null }'
    _applied "$PAGE" "$MUT/page.kt" 'ghEngine.login(ghHost)' && _red "D7 the page runs gh auth login itself again" d7 "$CLIENT" "$MUT/page.kt"; }
cp "$CLIENT" "$MUT/GhEngine.kt"
_green d7 d7 "$MUT/GhEngine.kt" "$PAGE" && {
    _sub "$MUT/GhEngine.kt" '    private fun ask(' '    fun login(host: String): Result = result(ask("loginStart", host))

    private fun ask('
    _applied "$CLIENT" "$MUT/GhEngine.kt" 'fun login(host' && _red "D7 the client can start a gh sign-in again" d7 "$MUT/GhEngine.kt" "$PAGE"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 19 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-gh-engine: all checks passed"; else echo "test-drive-gh-engine: $FAILURES check(s) FAILED"; exit 1; fi
