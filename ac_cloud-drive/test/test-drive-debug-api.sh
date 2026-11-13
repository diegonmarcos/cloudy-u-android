#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #669 — the Sync▸Git flow, exercisable over loopback from the phone shell  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# cloud-drive registers its own route groups (health / git / session / log) on
# libs:devtools' AppDebugServer — the fleet's ONE loopback debug transport
# (127.0.0.1, own port from libs/devtools/debug-ports.json, fleet Bearer on everything but /api/system/ping, whose
# unauthorized banner proves liveness). This tester pins the properties that
# make that safe and honest, and MUTATION-PROVES each one: a check that stays
# green when its property is broken proves nothing.
#
#   D1  NO LISTENER (#ipc, a0_docs/eng-specs/fleet-ipc.md). The shared debug API
#       binds nothing (the SuperApp gateway is the phone's one door), and this
#       app opens NO server socket of its own.
#   D2  GATED ON EVERY ROUTE. App route groups are reached only through the IPC
#       door, which re-checks the caller's CONSTELLATION_DATA (signature) through
#       FleetIpcWire.gate, and through the gateway, which demands the fleet token.
#   D3  NO SECRET IN A RESPONSE. No token, cookie value or full remote URL is
#       ever interpolated into a reply: session answers a boolean, state and
#       clone report the URL's HOST only, the listing carries no URLs, and the
#       chain route never reads the outcome's token.
#   D4  THE CLONE IS THE PAGE'S. /api/git/clone drives GitSyncCoordinator's
#       cloneInto — never a second clone implementation.
#   D5  REGISTERED AND DECLARED. The manifest carries the non-exported init
#       provider, the provider registers the routes, and build.json declares
#       the API (bind 127.0.0.1, port range, groups).
#   D6  HONEST STATUS. A clone that fails or times out answers ok:false with
#       the loud reason — never a 200-with-nothing.
#   D8  #735 THE GITHUB LEG IS gh's. The list and clone routes list through
#       DriveGitChain.repos (gh repo list for the github rung, never the fleet
#       lister, which answered "no fleet repos_url is declared"), and a clone's
#       credential is DriveGitChain.cloneAuth over the github rung's answer —
#       whose rung asks the gh ENGINE for its credential before the filed one.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
# grep -c (never -q) after pipes: pipefail + SIGPIPE turns a -q match into a
# failure.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SHARED="$ROOT/ab_cloud-libs-shared"
API="$APP/app/src/main/java/com/diegonmarcos/clouddrive/debugapi/DriveDebugApi.kt"
PROVIDER="$APP/app/src/main/java/com/diegonmarcos/clouddrive/debugapi/DriveDebugApiProvider.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
SERVER="$SHARED/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
DOOR="$SHARED/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpc.kt"
BJ="$APP/build.json"
DEBUGLOG="$APP/app/src/main/java/com/diegonmarcos/clouddrive/DriveDebugLog.kt"
WIRING="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/DriveGitChain.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# a file's CODE, comment lines stripped — every ABSENCE grep runs through this,
# so a comment DOCUMENTING a forbidden shape never matches as the shape itself.
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

for required in "$API" "$PROVIDER" "$MANIFEST" "$SERVER" "$DOOR" "$BJ" "$DEBUGLOG" "$WIRING"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks, as functions of their inputs, so the mutation block runs them on copies ──

# d1 <AppDebugServer.kt> <DriveDebugApi.kt> <DriveDebugApiProvider.kt>
d1() {
    local server="$1" api="$2" provider="$3"
    # #ipc the shared debug API listens nowhere: no server socket, no bind
    [ "$(_code "$server" | grep -cE 'ServerSocket|\.bind\(|\.accept\(')" -eq 0 ] || return 1
    # this app opens no server socket of its own
    [ "$(cat "$api" "$provider" | grep -vE '^[[:space:]]*(\*|//|/\*)' | grep -cE 'ServerSocket|\.bind\(')" -eq 0 ] || return 1
}

# d2 <FleetIpc.kt> <DriveDebugApi.kt> : the IPC door re-checks the caller through the gate
d2() {
    local door="$1" api="$2"
    python3 - "$door" "$api" <<'PYTHON'
import sys
door = open(sys.argv[1], encoding="utf-8").read()
api = open(sys.argv[2], encoding="utf-8").read()
code = "\n".join(l for l in door.splitlines() if not l.strip().startswith(("*", "//", "/*")))
serve = code[code.find("fun serve("):code.find("fun call(")]
if "checkCallingPermission(FleetToken.PERMISSION) == PackageManager.PERMISSION_GRANTED" not in serve: sys.exit(1)
if "FleetIpcWire.gate(granted) ?:" not in serve: sys.exit(1)
if "AppDebugServer.dispatch(" not in serve: sys.exit(1)         # every route behind the gate
# and the api registers exactly through the gated extension point
if api.count('AppDebugServer.route(') < 4: sys.exit(1)
sys.exit(0)
PYTHON
}

# d3 <DriveDebugApi.kt> : no secret, no cookie echo, no full URL in a reply
d3() {
    python3 - "$1" <<'PYTHON'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
code = "\n".join(l for l in src.splitlines() if not l.strip().startswith(("*", "//", "/*")))
# a token or cookie VALUE interpolated into any string is the defect
for forbidden in ('${cookie', 'esc(cookie', '${token', 'esc(token', '${FleetSession.cookie', 'esc(FleetSession.cookie', '${FleetSession.fresh()', 'esc(FleetSession.fresh()', '.token', 'Authorization'):
    if forbidden in code: sys.exit(1)
# session presence is only ever a boolean
if '"session_present":${FleetSession.present}' not in code: sys.exit(1)
if '"fleet_session_present":${FleetSession.present}' not in code: sys.exit(1)
# the registry's remote travels as its HOST, never the URL (userinfo can carry a credential)
if 'GitSyncCoordinator.hostOf(r.remoteUrl)' not in code: sys.exit(1)
if 'esc(r.remoteUrl)' in code: sys.exit(1)
# the fleet listing reply carries names/owners, never a clone URL
m = re.search(r'private fun listJson.*?(?=\n    private fun|\n    //)', code, re.S)
if not m or 'cloneUrl' in m.group(0): sys.exit(1)
sys.exit(0)
PYTHON
}

# d4 <DriveDebugApi.kt> : the clone is coordinator.cloneInto, and nothing else clones
d4() {
    local api="$1"
    [ "$(grep -c '\.cloneInto(' "$api")" -ge 1 ] || return 1
    [ "$(_code "$api" | grep -cE 'GitEngine\.clone|cloneRepository')" -eq 0 ] || return 1
}

# d5 <manifest> <provider.kt> <build.json> : registered, not exported, declared
d5() {
    local manifest="$1" provider="$2" bj="$3"
    python3 - "$manifest" "$provider" "$bj" <<'PYTHON'
import json, re, sys
manifest = open(sys.argv[1], encoding="utf-8").read()
provider = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r'<provider[^>]*debugapi\.DriveDebugApiProvider[^>]*/>', manifest, re.S)
if not m: sys.exit(1)
if 'android:exported="false"' not in m.group(0): sys.exit(1)
if 'DriveDebugApi.register(ctx)' not in provider: sys.exit(1)
decl = json.load(open(sys.argv[3], encoding="utf-8")).get("diagnostics", {}).get("debug_api")
if not isinstance(decl, dict): sys.exit(1)
if decl.get("bind") != "127.0.0.1": sys.exit(1)
# #792 the port is the fleet table's, not a range this app restates
if decl.get("port_table") != "ab_cloud-libs-shared/libs/devtools/debug-ports.json": sys.exit(1)
for g in ("health", "git", "session", "log"):
    if g not in decl.get("groups", []): sys.exit(1)
sys.exit(0)
PYTHON
}

# d6 <DriveDebugApi.kt> : every non-outcome is ok:false with the reason in words
d6() {
    local api="$1"
    [ "$(grep -c '"ok":false,"why":"\${esc(why)}"' "$api")" -ge 1 ] || return 1
    [ "$(grep -c '"status":"timeout"' "$api")" -ge 1 ] || return 1
    # the timeout reply is a failure, never a success
    [ "$(_code "$api" | grep -c '"ok":true,"status":"timeout"')" -eq 0 ] || return 1
    # the engine's own outcome decides ok on a finished clone
    [ "$(grep -c '{"ok":\${r.ok}' "$api")" -ge 1 ] || return 1
}

# d8 <DriveDebugApi.kt> <DriveGitChain.kt> : the github leg lists and clones on gh
d8() {
    python3 - "$1" "$2" <<'PYTHON'
import re, sys
strip = lambda src: "\n".join(l for l in src.splitlines() if not l.strip().startswith(("*", "//", "/*")))
api = strip(open(sys.argv[1], encoding="utf-8").read())
wiring = strip(open(sys.argv[2], encoding="utf-8").read())
# both routes list through the kind dispatch, never the fleet lister directly
if api.count("DriveGitChain.repos(ctx, ") < 2: sys.exit(1)
if "FleetGit.repos(" in api: sys.exit(1)
# the dispatch sends the github kind to gh, everything else to the fleet
if not re.search(r'if \(rung\(rungId\)\?\.kind == RUNG_GITHUB\) ghRepos\(GhEngine\(ctx\)\) else FleetGit\.repos\(session, rungId\)', wiring): sys.exit(1)
if "engine.repoList(" not in wiring or "GitHubRepos.parseGh(" not in wiring: sys.exit(1)
# the clone's credential is the github rung's answer, not the vault slot alone
if "DriveGitChain.cloneAuth(viaFleet)" not in api or "DriveGitChain.githubCredential(ctx)" not in api: sys.exit(1)
if "vaultGitToken" in api: sys.exit(1)
# the rung the chain walks asks the gh engine for its credential
m = re.search(r'private fun onDevice\(ctx: Context\).*?\n    \)', wiring, re.S)
if not m or "GhEngine(ctx).credential(host)" not in m.group(0): sys.exit(1)
if not re.search(r'RUNG_GITHUB -> github\(\)', wiring): sys.exit(1)
if not re.search(r'github: \(\) -> GitAuthChain\.Answer = \{ onDevice\(ctx\) \}', wiring): sys.exit(1)
sys.exit(0)
PYTHON
}

echo "── #669 drive loopback debug API ──"
d1 "$SERVER" "$API" "$PROVIDER" && pass "D1 no listener: the shared API binds nothing and this app opens no server socket" || fail "D1 a listener in the shared API or this app"
d2 "$DOOR" "$API"               && pass "D2 every app route behind the IPC door's signature re-check (the gateway adds the fleet token)" || fail "D2 IPC gate"
d3 "$API"                       && pass "D3 no token/cookie/URL ever leaves in a response" || fail "D3 no-secret rule"
d4 "$API"                       && pass "D4 the clone route drives the page's cloneInto, no second clone" || fail "D4 clone path reuse"
d5 "$MANIFEST" "$PROVIDER" "$BJ" && pass "D5 provider registered (not exported) and API declared in build.json" || fail "D5 registration + declaration"
d6 "$API"                       && pass "D6 honest status on every reply, timeout included" || fail "D6 honest status"
[ "$(grep -c 'fun tail(ctx: Context, lines: Int)' "$DEBUGLOG")" -ge 1 ] \
    && pass "D7 /api/log/tail reads DriveDebugLog's own file (one reader, in-process)" \
    || fail "D7 DriveDebugLog.tail missing"
d8 "$API" "$WIRING"             && pass "D8 the github leg lists through gh and clones on the github rung's credential, which asks the gh engine" || fail "D8 github leg on gh"

# ── MUTATIONS — each property must go RED when broken ───────────────────────
W="${TMPDIR:-/tmp}/drive-debug-api-mut.$$"
MUTATIONS=0
HOLLOW=0

# _stage : fresh pristine copies of every source a check reads
_stage() {
    rm -rf "$W"; mkdir -p "$W"
    cp "$API" "$W/DriveDebugApi.kt"
    cp "$PROVIDER" "$W/DriveDebugApiProvider.kt"
    cp "$MANIFEST" "$W/AndroidManifest.xml"
    cp "$SERVER" "$W/AppDebugServer.kt"
    cp "$DOOR" "$W/FleetIpc.kt"
    cp "$BJ" "$W/drive.json"
    cp "$WIRING" "$W/DriveGitChain.kt"
}
# _sub <file> <old> <new> : an EXACT replacement that MUST actually apply.
_sub() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import sys
p, old, new = sys.argv[1:4]
src = open(p, encoding="utf-8").read()
if old not in src:
    sys.stderr.write("MUTATION DID NOT APPLY: %r absent from %s\n" % (old, p)); sys.exit(2)
open(p, "w", encoding="utf-8").write(src.replace(old, new, 1))
PYTHON
}
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 || { echo "  MUT-VOID    $label — check not green on pristine copies"; HOLLOW=$((HOLLOW + 1)); return 1; }; }
_red() {
    local label="$1"; shift
    MUTATIONS=$((MUTATIONS + 1))
    if "$@" >/dev/null 2>&1; then
        echo "  MUT-HOLLOW  $label — mutated and STILL PASSES: that check proves nothing"
        HOLLOW=$((HOLLOW + 1))
    else
        echo "  MUT-RED     $label"
    fi
}

_stage && _green "d1" d1 "$W/AppDebugServer.kt" "$W/DriveDebugApi.kt" "$W/DriveDebugApiProvider.kt" && {
    _sub "$W/AppDebugServer.kt" '    fun stop() { served.set(false) }' '    fun stop() { served.set(false) }
    private val back = java.net.ServerSocket(38154)'
    _red "D1 the shared API listens on a port again" d1 "$W/AppDebugServer.kt" "$W/DriveDebugApi.kt" "$W/DriveDebugApiProvider.kt"; }
_stage && _green "d1" d1 "$W/AppDebugServer.kt" "$W/DriveDebugApi.kt" "$W/DriveDebugApiProvider.kt" && {
    _sub "$W/DriveDebugApi.kt" 'private const val POLL_MS = 250L' 'private const val POLL_MS = 250L
    private val rogue = java.net.ServerSocket(38200)'
    _red "D1 this app grows a server socket of its own" d1 "$W/AppDebugServer.kt" "$W/DriveDebugApi.kt" "$W/DriveDebugApiProvider.kt"; }

_stage && _green "d2" d2 "$W/FleetIpc.kt" "$W/DriveDebugApi.kt" && {
    _sub "$W/FleetIpc.kt" 'ctx.checkCallingPermission(FleetToken.PERMISSION) == PackageManager.PERMISSION_GRANTED' 'true'
    _red "D2 the door stops re-checking the caller's signature permission" d2 "$W/FleetIpc.kt" "$W/DriveDebugApi.kt"; }
_stage && _green "d2" d2 "$W/FleetIpc.kt" "$W/DriveDebugApi.kt" && {
    _sub "$W/FleetIpc.kt" 'FleetIpcWire.gate(granted) ?: ' ''
    _red "D2 the gate deleted — every route answers any caller" d2 "$W/FleetIpc.kt" "$W/DriveDebugApi.kt"; }

_stage && _green "d3" d3 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" '"""{"ok":true,"session_present":${FleetSession.present}}"""' \
                               '"""{"ok":true,"session_present":${FleetSession.present},"cookie":"${cookie}"}"""'
    _red "D3 the session route echoes the cookie back" d3 "$W/DriveDebugApi.kt"; }
_stage && _green "d3" d3 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" 'GitSyncCoordinator.hostOf(r.remoteUrl)' 'esc(r.remoteUrl)'
    _red "D3 the state route leaks the full remote URL (userinfo can carry a credential)" d3 "$W/DriveDebugApi.kt"; }
_stage && _green "d3" d3 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" '"narrative":"${esc(outcome.narrative())}"' '"narrative":"${esc(outcome.narrative())}","token":"${outcome.token}"'
    _red "D3 the chain route hands the credential to the caller" d3 "$W/DriveDebugApi.kt"; }
_stage && _green "d3" d3 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" '"owner":"${esc(r.owner)}"' '"owner":"${esc(r.owner)}","url":"${esc(r.cloneUrl)}"'
    _red "D3 the listing grows clone URLs back" d3 "$W/DriveDebugApi.kt"; }

_stage && _green "d4" d4 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" 'c.cloneInto(' 'com.diegonmarcos.cloudlib.gitsync.GitEngine.clone('
    _red "D4 the route grows its own clone instead of the page's" d4 "$W/DriveDebugApi.kt"; }

_stage && _green "d5" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json" && {
    _sub "$W/AndroidManifest.xml" 'android:name=".debugapi.DriveDebugApiProvider"
            android:authorities="${applicationId}.drivedebugapi"
            android:exported="false"' 'android:name=".debugapi.DriveDebugApiProvider"
            android:authorities="${applicationId}.drivedebugapi"
            android:exported="true"'
    _red "D5 the init provider exported to other apps" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json"; }
_stage && _green "d5" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json" && {
    _sub "$W/DriveDebugApiProvider.kt" 'runCatching { DriveDebugApi.register(ctx) }' 'runCatching { }'
    _red "D5 the provider stops registering — routes exist in code, answer 404 on the phone" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json"; }
_stage && _green "d5" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json" && {
    _sub "$W/drive.json" '"bind": "127.0.0.1"' '"bind": "0.0.0.0"'
    _red "D5 the declaration stops promising loopback" d5 "$W/AndroidManifest.xml" "$W/DriveDebugApiProvider.kt" "$W/drive.json"; }

_stage && _green "d6" d6 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" '"""{"ok":false,"status":"timeout"' '"""{"ok":true,"status":"timeout"'
    _red "D6 a timed-out clone reported as a success" d6 "$W/DriveDebugApi.kt"; }
_stage && _green "d6" d6 "$W/DriveDebugApi.kt" && {
    _sub "$W/DriveDebugApi.kt" '{"ok":${r.ok}' '{"ok":true'
    _red "D6 a failed clone reported ok — the 200-with-nothing shape" d6 "$W/DriveDebugApi.kt"; }

_stage && _green "d8" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/DriveDebugApi.kt" 'when (val out = runCatching { DriveGitChain.repos(ctx, rungId, FleetSession.fresh()) }' 'when (val out = runCatching { FleetGit.repos(FleetSession.fresh(), rungId) }'
    _red "D8 /api/git/list?rung=github asks the fleet lister again (no fleet repos_url)" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt"; }
_stage && _green "d8" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/DriveGitChain.kt" 'runCatching { GhEngine(ctx).credential(host) }.getOrNull()?.secret' 'null'
    _red "D8 the github rung stops asking the gh engine — gh's sign-in never reaches the chain" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt"; }
_stage && _green "d8" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/DriveDebugApi.kt" 'runCatching { DriveGitChain.githubCredential(ctx) }' 'runCatching { GitAuthChain.Answer.Credential(DriveAuthApply.vaultGitToken(ctx)) }'
    _red "D8 the clone presents only the vault slot again" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt"; }
_stage && _green "d8" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/DriveGitChain.kt" 'if (rung(rungId)?.kind == RUNG_GITHUB) ghRepos(GhEngine(ctx)) else' 'if (false) ghRepos(GhEngine(ctx)) else'
    _red "D8 the kind dispatch sends the github rung to the fleet lister" d8 "$W/DriveDebugApi.kt" "$W/DriveGitChain.kt"; }

rm -rf "$W"
echo "── $MUTATIONS mutations, $HOLLOW of them hollow or void ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "OK  #669 the loopback git debug API holds: $MUTATIONS mutations all went red"
    exit 0
fi
echo "FAILED  $FAILURES check(s) — the debug API is not what it declares"
exit 1
