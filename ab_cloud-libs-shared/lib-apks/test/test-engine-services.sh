#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #705 — an ENGINE that an app binds instead of compiling must be bindable,  ║
# ║ versioned, guarded and able to run, from its own Cloud-Lib APK alone       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Once an app stops compiling an engine (cloud-drive no longer links libs:gh), the
# app's ship stops watching the engine's directory. This repo's ship (Cloud Libs)
# is then the ONLY pipeline that runs when the engine changes, so the engine's
# half of the contract is checked here or nowhere.
#
# Engines are DISCOVERED, never listed: every module under the scan root whose
# manifest declares the engine CONTRACT meta-data on a service is one.
#
#   E1  the service is exported and guarded by CONSTELLATION_DATA (signature).
#   E2  a client can FIND it: an intent-filter action of exactly ${applicationId}.ENGINE.
#   E3  it declares its CONTRACT as a positive integer.
#   E4  its class extends DataBackendService -- or, for an engine whose input is
#       not a string (the image scan takes a file descriptor), is a Service whose
#       binder is its typed wire's Stub and answers methods() with methodNames()
#       -- and methodNames() names exactly the methods dispatch() answers — a method the engine answers but does not
#       list cannot be found by a client degrading knowingly, and one it lists
#       but does not answer is a contract it does not keep.
#   E5  the module compiles against libs:core (the base class and the permission).
#   E6  an engine that EXECS a binary out of nativeLibraryDir is listed in
#       lib_apks.exec_native, so its APK extracts the .so; every exec_native name
#       is a real module.
#   E7  the gh engine holds INTERNET: gh's whole job is talking to GitHub, from
#       the engine's process now.
#   E9  #729 the gh engine holds its own network for a sign-in: LOGIN_START
#       builds the GhLogin job with GhLoginKeeper.hold, and the manifest declares
#       that keeper as a specialUse foreground service with both permissions.
#       gh polls GitHub while the user is in the browser; Android 15 cuts the
#       network of a backgrounded process and the poll died as a DNS failure.
#       #1692 and that hold can never stay on forever: GhLogin has a hard expiry
#       (a watchdog that stops gh once the code expires), releases in a finally
#       on every exit, LOGIN_START hands it a kill that ends gh's process, and the
#       keeper's notification carries a Cancel action that cancels the job and
#       cancels it again when the keeper itself is destroyed.
#   E8  every engine an app BINDS by handshake is found here: gh (Cloud Drive),
#       cal (Cloud Me and Cloud Tasks, engine-apk-split move 3), feed (SuperApp,
#       move 4), news (Cloud News, move 5) and ml-l-image-mlkit (the image scan
#       Drive, Mail, Camera, Media Center and Office reach through libs:ml-l-image,
#       move 6), calc (Cloud Calc, #767) and ml-l-sound-yamnet (the sound identification
#       Cloud Calc and Cloud Camera reach through libs:ml-l-sound, #798) and
#       fleetconfig (#825: every fleet app's FleetConfigProvider binds it) and analytics-sink (#871:
#       the POST of every app's analytics events, reached through libs:analytics) and ops-engine (#871: the
#       Dagu calls of the SuperApp's and C3's Dagu page, reached through libs:ops) and rclone-engine (#871 move 10:
#       the pinned rclone binary and its exec, reached by Cloud Drive through libs:rclone) and mounts-engine (#871 move 12:
#       the ftp / sftp / ssh / webdav clients, reached by Cloud Drive through libs:mounts) and decisions-engine (#881:
#       the one place the fleet asks the Jev decision model, reached through libs:decisions-link). An engine whose contract meta-data
#       went missing would drop out of every check above without a word.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs and lib-apks, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SHARED="$ROOT/ab_cloud-libs-shared"
LIBS="$SHARED/libs"
BJ="$SHARED/lib-apks/build.json"
GH="$LIBS/gh"
for required in "$BJ" "$GH/src/main/AndroidManifest.xml" "$GH/build.gradle" "$LIBS/cal/src/main/AndroidManifest.xml" "$LIBS/feed/src/main/AndroidManifest.xml" \
                "$LIBS/news/src/main/AndroidManifest.xml" "$LIBS/calc/src/main/AndroidManifest.xml" \
                "$LIBS/ml-l-image-mlkit/src/main/AndroidManifest.xml" "$LIBS/ml-l-sound-yamnet/src/main/AndroidManifest.xml" \
                "$LIBS/fleetconfig/src/main/AndroidManifest.xml" "$LIBS/analytics-sink/src/main/AndroidManifest.xml" "$LIBS/ops-engine/src/main/AndroidManifest.xml" "$LIBS/decisions-engine/src/main/AndroidManifest.xml" "$LIBS/rclone-engine/src/main/AndroidManifest.xml" "$LIBS/mounts-engine/src/main/AndroidManifest.xml" \
                "$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# engines <libs root> <lib-apks build.json> : every check, on whatever tree it is handed
engines() {
    python3 - "$1" "$2" <<'PYTHON'
import json, os, re, sys
import xml.etree.ElementTree as ET

libs, bj_path = sys.argv[1], sys.argv[2]
A = "{http://schemas.android.com/apk/res/android}"
CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
GUARD = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
bad = 0
def no(msg):
    global bad
    print("    " + msg); bad = 1

exec_native = {k for k in json.load(open(bj_path, encoding="utf-8"))["lib_apks"].get("exec_native", {}) if not k.startswith("_")}
modules = sorted(d for d in os.listdir(libs) if os.path.isfile(os.path.join(libs, d, "build.gradle")))
for name in sorted(exec_native - set(modules)):
    no("lib_apks.exec_native names %r, which is not a module under the scan root" % name)

def sources(module):
    root = os.path.join(libs, module, "src", "main")
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith((".kt", ".java")):
                yield os.path.join(dirpath, f)

def code(path):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(path, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

found = []
for module in modules:
    mf = os.path.join(libs, module, "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(mf):
        continue
    try:
        tree = ET.parse(mf)
    except ET.ParseError as e:
        no("%s's manifest does not parse: %s" % (module, e)); continue
    for svc in tree.getroot().iter("service"):
        metas = {m.get(A + "name"): m.get(A + "value") for m in svc.iter("meta-data")}
        if CONTRACT_KEY not in metas:
            continue
        cls = svc.get(A + "name") or ""
        found.append(module)
        # E1
        if svc.get(A + "exported") != "true":
            no("E1 %s: %s declares a contract but is not exported — no client can bind it" % (module, cls))
        if svc.get(A + "permission") != GUARD:
            no("E1 %s: %s is not guarded by %s — any installed app could drive it" % (module, cls, GUARD))
        # E2
        actions = [a.get(A + "name") for f in svc.iter("intent-filter") for a in f.iter("action")]
        if "${applicationId}.ENGINE" not in actions:
            no("E2 %s: %s has no ${applicationId}.ENGINE action (has %s) — a client resolves engines by it" % (module, cls, actions))
        # E3
        v = metas.get(CONTRACT_KEY) or ""
        if not re.fullmatch(r"[1-9][0-9]*", v):
            no("E3 %s: the contract %r is not a positive integer" % (module, v))
        # E4
        simple = cls.rsplit(".", 1)[-1]
        src = [p for p in sources(module) if re.search(r"class\s+%s\b" % re.escape(simple), code(p))]
        if len(src) != 1:
            no("E4 %s: %s is declared but its class is not in the module's sources" % (module, cls)); continue
        text = code(src[0])
        typed = re.search(r"class\s+%s\s*:\s*Service\(\)" % re.escape(simple), text) \
            and re.search(r"object\s*:\s*I\w+\.Stub\(\)", text) \
            and re.search(r"override\s+fun\s+methods\(\)\s*:\s*Array<String>\s*=\s*methodNames\(\)", text)
        if not re.search(r"class\s+%s\s*:\s*DataBackendService\(\)" % re.escape(simple), text) and not typed:
            no("E4 %s: %s neither extends DataBackendService nor serves a typed wire Stub whose methods() is methodNames()" % (module, simple))
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        def resolve(tok):
            tok = tok.strip()
            if tok.startswith('"') and tok.endswith('"'):
                return tok[1:-1]
            return consts.get(tok, "?" + tok)
        m = re.search(r"fun\s+methodNames\(\)\s*:\s*Array<String>\s*=\s*arrayOf\(([^)]*)\)", text)
        listed = {resolve(t) for t in m.group(1).split(",") if t.strip()} if m else set()
        if not listed:
            no("E4 %s: methodNames() names no method" % module)
        stub = re.search(r"object\s*:\s*(I\w+)\.Stub\(\)", text)
        d = re.search(r"fun\s+dispatch\(.*?\)\s*:\s*String\s*=\s*when\s*\(\s*method\s*\)\s*\{(.*?)\n    \}", text, re.S)
        answered = set()
        if not d and stub and not re.search(r"fun\s+dispatch\(", text):
            # a typed multi-method wire (INetBackend): the AIDL file IS the dispatch table
            aidl = [os.path.join(dp, stub.group(1) + ".aidl") for dp, _, fs in os.walk(libs) if stub.group(1) + ".aidl" in fs]
            if len(aidl) != 1:
                no("E4 %s: %s.aidl is not one file under the libs root" % (module, stub.group(1)))
            else:
                body = re.sub(r"/\*.*?\*/|//[^\n]*", "", open(aidl[0], encoding="utf-8").read(), flags=re.S)
                answered = set(re.findall(r"\b(\w+)\s*\([^)]*\)\s*;", body)) - {"methods"}
                listed = listed - {"methods"}
        elif not d:
            no("E4 %s: dispatch() is not a `when (method)` this tester can read" % module)
        else:
            for label in re.findall(r"^\s{8}([A-Za-z_\"][^\n]*?)\s*->", d.group(1), re.M):
                if label.strip() != "else":
                    answered |= {resolve(t) for t in label.split(",")}
        if listed and (d or answered) and listed != answered:
            no("E4 %s: methodNames() lists %s but dispatch answers %s" % (module, sorted(listed), sorted(answered)))
        unresolved = sorted(x for x in listed | answered if x.startswith("?"))
        if unresolved:
            no("E4 %s: method names that are not string constants: %s" % (module, unresolved))
        # E5
        gradle = open(os.path.join(libs, module, "build.gradle"), encoding="utf-8").read()
        if not re.search(r"""^\s*(api|implementation)\s+project\(['"]:libs:core['"]\)""", gradle, re.M):
            no("E5 %s: does not compile against libs:core — DataBackendService and CONSTELLATION_DATA live there" % module)
        # E6
        execs = any("applicationInfo.nativeLibraryDir" in code(p) for p in sources(module)) and \
                any("ProcessBuilder(" in code(p) for p in sources(module))
        if execs and module not in exec_native:
            no("E6 %s: its engine execs a binary out of nativeLibraryDir but lib_apks.exec_native does not list it — "
               "the APK would keep the .so stored and the exec would find nothing" % module)
        # E7
        if module == "gh":
            perms = [p.get(A + "name") for p in tree.getroot().iter("uses-permission")]
            if "android.permission.INTERNET" not in perms:
                no("E7 gh: the engine runs gh, which talks to GitHub, without INTERNET")
            # E9
            for perm in ("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"):
                if perm not in perms:
                    no("E9 gh: the sign-in keeper cannot run in the foreground without %s" % perm)
            keepers = [s for s in tree.getroot().iter("service") if (s.get(A + "name") or "").endswith(".GhLoginKeeper")]
            if len(keepers) != 1 or "specialUse" not in (keepers[0].get(A + "foregroundServiceType") or "") \
                    or keepers[0].get(A + "exported") != "false":
                no("E9 gh: GhLoginKeeper is not declared once as a non-exported specialUse foreground service")
            if not re.search(r"GhLogin\(host,\s*hold\s*=\s*\{\s*GhLoginKeeper\.hold\(", text):
                no("E9 gh: LOGIN_START starts gh's sign-in without GhLoginKeeper.hold — its poll loses the network "
                   "the moment the browser is up")
            gsrc = {os.path.basename(p): code(p) for p in sources(module)}
            job = gsrc.get("GhRunner.kt", "")
            keeper = gsrc.get("GhLoginKeeper.kt", "")
            if not re.search(r"watchdog\s*=\s*Thread\(\{.*?expiresAt.*?if \(ended == null\) stop\(", job, re.S):
                no("E9 gh: GhLogin has no hard expiry — an abandoned sign-in keeps the foreground job on forever")
            if not re.search(r"\}\s*finally\s*\{\s*releaseOnce\(\)", job):
                no("E9 gh: GhLogin does not release the keeper in a finally — a failed or thrown sign-in keeps it on")
            if not re.search(r"private fun stop\(.*?kill\(\).*?releaseOnce\(\)", job, re.S):
                no("E9 gh: stopping a sign-in neither ends gh nor releases the keeper")
            if not re.search(r"kill\s*=\s*\{\s*proc\.get\(\)\?\.destroyForcibly\(\)", text) or "proc.set(it)" not in text:
                no("E9 gh: LOGIN_START gives GhLogin no way to end gh's process on expiry or cancel")
            if not re.search(r"addAction\(Notification\.Action\.Builder\(null,\s*\"Cancel sign-in\",\s*cancel\)", keeper) \
                    or not re.search(r"ACTION_CANCEL\)\s*\{.*?\.cancel\(\)", keeper, re.S):
                no("E9 gh: the sign-in notification has no working Cancel action")
            if not re.search(r"override fun onDestroy\(\)\s*\{\s*job\?\.takeIf \{ it\.running \}\?\.cancel\(", keeper):
                no("E9 gh: the keeper going away leaves gh running")

for must in ("gh", "cal", "feed", "news", "ml-l-image-mlkit", "calc", "ml-l-sound-yamnet", "fleetconfig", "analytics-sink", "ops-engine", "decisions-engine", "rclone-engine", "mounts-engine", "net-wg"):
    if must not in found:
        no("E8 no %s engine was found — the contract meta-data or the service moved, so every check above ran without it" % must)
print("    engines: %s" % found)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── E1-E9 every engine on the shelf is bindable, versioned, guarded and runnable ──"
engines "$LIBS" "$BJ" && pass "every engine service is exported and signature-guarded, findable by \${applicationId}.ENGINE, versioned, lists exactly what it answers, links core, extracts what it execs; gh holds INTERNET and its sign-in holds the network" \
    || fail "an engine on the shelf cannot be bound, found, versioned or run as declared"

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
_sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$1" "$2" "$3"; }
_json() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$1" "$2"; }
# a fresh copy of the shelf's gh engine and the lib-apks declaration, laid out as the real tree
_stage() {
    rm -rf "$MUT/libs" "$MUT/build.json"; mkdir -p "$MUT/libs"
    cp -r "$GH" "$MUT/libs/gh"; cp -r "$LIBS/cal" "$MUT/libs/cal"; cp -r "$LIBS/feed" "$MUT/libs/feed"; cp -r "$LIBS/news" "$MUT/libs/news"; cp -r "$LIBS/ml-l-image-mlkit" "$MUT/libs/ml-l-image-mlkit"; cp -r "$LIBS/calc" "$MUT/libs/calc"; cp -r "$LIBS/ml-l-sound-yamnet" "$MUT/libs/ml-l-sound-yamnet"; cp -r "$LIBS/fleetconfig" "$MUT/libs/fleetconfig"; cp -r "$LIBS/analytics-sink" "$MUT/libs/analytics-sink"; cp -r "$LIBS/ops-engine" "$MUT/libs/ops-engine"; cp -r "$LIBS/decisions-engine" "$MUT/libs/decisions-engine"; cp -r "$LIBS/rclone-engine" "$MUT/libs/rclone-engine"; cp -r "$LIBS/mounts-engine" "$MUT/libs/mounts-engine"; cp -r "$LIBS/net-wg" "$MUT/libs/net-wg"; cp -r "$LIBS/net" "$MUT/libs/net"; cp "$BJ" "$MUT/build.json"
}
M_MF="$MUT/libs/gh/src/main/AndroidManifest.xml"
M_SVC="$MUT/libs/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"
M_GRADLE="$MUT/libs/gh/build.gradle"
R_MF="$GH/src/main/AndroidManifest.xml"
R_SVC="$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"

echo "── MUT each property, broken on a copy, goes red ──"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:exported="true"' 'android:exported="false"'
    _applied "$R_MF" "$M_MF" 'android:exported="false"' \
        && _red "E1 the engine service is not exported" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA">' '">'
    python3 -c 'import sys; sys.exit(0 if "permission.CONSTELLATION_DATA\">" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" 'android:exported="true">' \
        && _red "E1 the engine service is bindable by any app (no signature guard)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '${applicationId}.ENGINE' 'com.diegonmarcos.cloudlib.gh.ENGINE'
    _applied "$R_MF" "$M_MF" '"com.diegonmarcos.cloudlib.gh.ENGINE"' \
        && _red "E2 the action is a typed package literal instead of the applicationId's" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:value="1"' 'android:value="one"'
    _applied "$R_MF" "$M_MF" 'android:value="one"' \
        && _red "E3 the contract is not an integer" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START, LOGIN_POLL)' 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START)'
    _applied "$R_SVC" "$M_SVC" 'CREDENTIAL, LOGIN_START)' \
        && _red "E4 a method the engine answers is missing from methodNames()" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" '        LOGIN_POLL -> Login.poll()
' ''
    python3 -c 'import sys; sys.exit(0 if "LOGIN_POLL -> Login.poll()" not in open(sys.argv[1]).read() else 1)' "$M_SVC" \
        && _applied "$R_SVC" "$M_SVC" 'LOGIN_START -> Login.start(runner, arg(args, 0), applicationContext, caller())' \
        && _red "E4 the engine lists a method it no longer answers (an older client would break)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'class GhBackendService : DataBackendService()' 'class GhBackendService : android.app.Service()'
    _applied "$R_SVC" "$M_SVC" 'GhBackendService : android.app.Service()' \
        && _red "E4 the engine class does not speak IDataBackend" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_GRADLE" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$M_GRADLE" \
        && _applied "$GH/build.gradle" "$M_GRADLE" "implementation 'androidx.core:core-ktx" \
        && _red "E5 the engine no longer compiles against libs:core" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _json "$MUT/build.json" 'd["lib_apks"]["exec_native"].pop("gh"); d["lib_apks"]["exec_native"]["_planted"] = "gh-removed"'
    _applied "$BJ" "$MUT/build.json" 'gh-removed' \
        && _red "E6 the gh engine execs a binary its APK no longer extracts" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _json "$MUT/build.json" 'd["lib_apks"]["exec_native"]["gihub"] = "a typo"'
    _applied "$BJ" "$MUT/build.json" 'gihub' \
        && _red "E6 exec_native names a module that does not exist" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '    <uses-permission android:name="android.permission.INTERNET" />
' ''
    python3 -c 'import sys; sys.exit(0 if "android.permission.INTERNET" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" '<application>' \
        && _red "E7 the gh engine runs gh without INTERNET" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'GhLogin(host, hold = { GhLoginKeeper.hold(ctx, it, who) }, ' 'GhLogin(host, '
    _applied "$R_SVC" "$M_SVC" 'job = GhLogin(host, kill' \
        && _red "E9 the sign-in runs without holding the engine's network" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
' ''
    python3 -c 'import sys; sys.exit(0 if "FOREGROUND_SERVICE_SPECIAL_USE" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" 'android.permission.FOREGROUND_SERVICE" />' \
        && _red "E9 the keeper's foreground type has no permission" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:foregroundServiceType="specialUse"' 'android:foregroundServiceType="dataSync"'
    _applied "$R_MF" "$M_MF" '"dataSync"' \
        && _red "E9 the keeper's declared type does not match the type it starts with" engines "$MUT/libs" "$MUT/build.json"; }
M_JOB="$MUT/libs/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhRunner.kt"
R_JOB="$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhRunner.kt"
M_KEEP="$MUT/libs/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhLoginKeeper.kt"
R_KEEP="$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhLoginKeeper.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_JOB" 'if (ended == null) stop(' 'if (ended == null) Log.i(GhRunner.TAG, '
    _applied "$R_JOB" "$M_JOB" 'if (ended == null) Log.i(GhRunner.TAG, ' \
        && _red "E9 #1692 an abandoned sign-in never expires (the watchdog only logs)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_JOB" '} finally {
            releaseOnce()
        }' '}'
    _applied "$R_JOB" "$M_JOB" 'e.javaClass.simpleName}")
        }
        ended' \
        && _red "E9 #1692 a failed sign-in keeps the keeper (no release in finally)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_JOB" 'runCatching { kill() }' 'runCatching { }'
    _applied "$R_JOB" "$M_JOB" 'runCatching { }' \
        && _red "E9 #1692 cancel and expiry leave gh running" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'kill = { proc.get()?.destroyForcibly() }' 'kill = { }'
    _applied "$R_SVC" "$M_SVC" 'kill = { }' \
        && _red "E9 #1692 LOGIN_START hands GhLogin a kill that ends nothing" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_KEEP" '            .addAction(Notification.Action.Builder(null, "Cancel sign-in", cancel).build())
' ''
    _applied "$R_KEEP" "$M_KEEP" '.setOngoing(true)
            .build()
    }' \
        && _red "E9 #1692 the sign-in notification has no Cancel action" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_KEEP" 'if (j != null) j.cancel() else stopSelf()' 'stopSelf()'
    _applied "$R_KEEP" "$M_KEEP" 'ACTION_CANCEL) {
            Log.i(GhRunner.TAG, "gh-net: sign-in cancelled from the notification")
            val j = job
            stopSelf()' \
        && _red "E9 #1692 Cancel hides the notification but leaves gh signing in" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_KEEP" '        job?.takeIf { it.running }?.cancel("the sign-in keeper was stopped' '        job?.takeIf { it.running }?.toString()  //("the sign-in keeper was stopped'
    _applied "$R_KEEP" "$M_KEEP" '?.toString()  //' \
        && _red "E9 #1692 the keeper dying leaves gh running" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$R_MF" "$M_MF" 'engine.VERSION' \
        && _red "the contract key is renamed, so every check would run on nothing" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/cal/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/cal/src/main/AndroidManifest.xml" "$MUT/libs/cal/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the cal engine Cloud Me binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/feed/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/feed/src/main/AndroidManifest.xml" "$MUT/libs/feed/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the feed engine the SuperApp binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/news/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/news/src/main/AndroidManifest.xml" "$MUT/libs/news/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the news engine Cloud News binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/calc/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/calc/src/main/AndroidManifest.xml" "$MUT/libs/calc/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the calc engine Cloud Calc binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

M_IMGMF="$MUT/libs/ml-l-image-mlkit/src/main/AndroidManifest.xml"
M_IMGSVC="$MUT/libs/ml-l-image-mlkit/src/main/java/com/diegonmarcos/superapp/image/ImageScanBackendService.kt"
R_IMGSVC="$LIBS/ml-l-image-mlkit/src/main/java/com/diegonmarcos/superapp/image/ImageScanBackendService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/ml-l-image-mlkit/src/main/AndroidManifest.xml" "$M_IMGMF" 'engine.VERSION' \
        && _red "E8 the image engine five apps bind stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" 'override fun methods(): Array<String> = methodNames()' 'override fun methods(): Array<String> = arrayOf(BARCODE)'
    _applied "$R_IMGSVC" "$M_IMGSVC" '= arrayOf(BARCODE)' \
        && _red "E4 the typed wire's methods() stops answering with methodNames() (a client would not see ocr)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" 'private val binder = object : IImageScanEngine.Stub() {' 'private val binder = object : android.os.Binder() {'
    _applied "$R_IMGSVC" "$M_IMGSVC" 'object : android.os.Binder()' \
        && _red "E4 a Service() engine that serves no typed wire Stub" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" '        OCR -> scanner.ocrJson(image)
' ''
    python3 -c 'import sys; sys.exit(0 if "OCR -> scanner" not in open(sys.argv[1]).read() else 1)' "$M_IMGSVC" \
        && _applied "$R_IMGSVC" "$M_IMGSVC" 'BARCODE -> scanner' \
        && _red "E4 the typed engine lists ocr but its dispatch no longer answers it" engines "$MUT/libs" "$MUT/build.json"; }

M_SNDMF="$MUT/libs/ml-l-sound-yamnet/src/main/AndroidManifest.xml"
M_SNDSVC="$MUT/libs/ml-l-sound-yamnet/src/main/java/com/diegonmarcos/cloudlib/sound/SoundBackendService.kt"
R_SNDSVC="$LIBS/ml-l-sound-yamnet/src/main/java/com/diegonmarcos/cloudlib/sound/SoundBackendService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNDMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/ml-l-sound-yamnet/src/main/AndroidManifest.xml" "$M_SNDMF" 'engine.VERSION' \
        && _red "E8 #798 the sound engine Cloud Calc and Cloud Camera bind stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNDSVC" '        INFO -> info()
' ''
    python3 -c 'import sys; sys.exit(0 if "INFO -> info()" not in open(sys.argv[1]).read() else 1)' "$M_SNDSVC" \
        && _applied "$R_SNDSVC" "$M_SNDSVC" 'CLASSIFY -> classifier' \
        && _red "E4 #798 the sound engine lists info but its dispatch no longer answers it" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNDMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/ml-l-sound-yamnet/src/main/AndroidManifest.xml" "$M_SNDMF" 'android:exported="true"' \
        && _red "E1 #798 the sound engine is exported without the signature guard" engines "$MUT/libs" "$MUT/build.json"; }

M_SNKMF="$MUT/libs/analytics-sink/src/main/AndroidManifest.xml"
M_SNKSVC="$MUT/libs/analytics-sink/src/main/java/com/diegonmarcos/superapp/analytics/sink/AnalyticsSinkService.kt"
R_SNKSVC="$LIBS/analytics-sink/src/main/java/com/diegonmarcos/superapp/analytics/sink/AnalyticsSinkService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNKMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/analytics-sink/src/main/AndroidManifest.xml" "$M_SNKMF" 'engine.VERSION' \
        && _red "E8 #871 the analytics sink engine fifteen apps report through stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNKSVC" '        MATOMO -> SinkTransport.send(SinkTransport.matomo(JSONObject(request)))
' ''
    python3 -c 'import sys; sys.exit(0 if "MATOMO -> SinkTransport" not in open(sys.argv[1]).read() else 1)' "$M_SNKSVC" \
        && _applied "$R_SNKSVC" "$M_SNKSVC" 'UMAMI -> SinkTransport' \
        && _red "E4 #871 the sink engine lists matomo but its dispatch no longer answers it (every app's second copy would fail)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SNKMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/analytics-sink/src/main/AndroidManifest.xml" "$M_SNKMF" 'android:exported="true"' \
        && _red "E1 #871 the sink engine is exported without the signature guard (any app could drive its INTERNET)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/analytics-sink/build.gradle" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$MUT/libs/analytics-sink/build.gradle" \
        && _applied "$LIBS/analytics-sink/build.gradle" "$MUT/libs/analytics-sink/build.gradle" "implementation project(':libs:analytics')" \
        && _red "E5 #871 the sink engine no longer compiles against libs:core (the permission that guards it)" engines "$MUT/libs" "$MUT/build.json"; }

M_OPSMF="$MUT/libs/ops-engine/src/main/AndroidManifest.xml"
M_OPSSVC="$MUT/libs/ops-engine/src/main/java/com/diegonmarcos/superapp/ops/engine/OpsEngineService.kt"
R_OPSSVC="$LIBS/ops-engine/src/main/java/com/diegonmarcos/superapp/ops/engine/OpsEngineService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_OPSMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/ops-engine/src/main/AndroidManifest.xml" "$M_OPSMF" 'engine.VERSION' \
        && _red "E8 #871 the ops engine the SuperApp's and C3's Dagu page calls through stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_OPSSVC" '        DAGU_START -> DaguTransport.start(JSONObject(request))
' ''
    python3 -c 'import sys; sys.exit(0 if "DAGU_START -> DaguTransport" not in open(sys.argv[1]).read() else 1)' "$M_OPSSVC" \
        && _applied "$R_OPSSVC" "$M_OPSSVC" 'DAGU_LIST -> DaguTransport' \
        && _red "E4 #871 the ops engine lists dagu_start but its dispatch no longer answers it (the Run button would fail)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_OPSMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/ops-engine/src/main/AndroidManifest.xml" "$M_OPSMF" 'android:exported="true"' \
        && _red "E1 #871 the ops engine is exported without the signature guard (any app could drive its INTERNET with a bearer)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/ops-engine/build.gradle" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$MUT/libs/ops-engine/build.gradle" \
        && _applied "$LIBS/ops-engine/build.gradle" "$MUT/libs/ops-engine/build.gradle" "implementation project(':libs:ops')" \
        && _red "E5 #871 the ops engine no longer compiles against libs:core (the permission that guards it)" engines "$MUT/libs" "$MUT/build.json"; }

M_RCMF="$MUT/libs/rclone-engine/src/main/AndroidManifest.xml"
M_RCSVC="$MUT/libs/rclone-engine/src/main/java/com/diegonmarcos/cloudlib/rclone/engine/RcloneEngineService.kt"
R_RCSVC="$LIBS/rclone-engine/src/main/java/com/diegonmarcos/cloudlib/rclone/engine/RcloneEngineService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_RCMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/rclone-engine/src/main/AndroidManifest.xml" "$M_RCMF" 'engine.VERSION' \
        && _red "E8 #871 the rclone engine Cloud Drive runs every transfer through stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_RCSVC" '        JOB_CANCEL -> calls().jobCancel(JSONObject(request))
' ''
    python3 -c 'import sys; sys.exit(0 if "JOB_CANCEL -> calls" not in open(sys.argv[1]).read() else 1)' "$M_RCSVC" \
        && _applied "$R_RCSVC" "$M_RCSVC" 'JOB_START -> calls' \
        && _red "E4 #871 the rclone engine lists job_cancel but its dispatch no longer answers it (a running transfer could not be stopped)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_RCMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/rclone-engine/src/main/AndroidManifest.xml" "$M_RCMF" 'android:exported="true"' \
        && _red "E1 #871 the rclone engine is exported without the signature guard (any app could run rclone on the user's remotes)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/rclone-engine/build.gradle" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$MUT/libs/rclone-engine/build.gradle" \
        && _applied "$LIBS/rclone-engine/build.gradle" "$MUT/libs/rclone-engine/build.gradle" "implementation project(':libs:rclone')" \
        && _red "E5 #871 the rclone engine no longer compiles against libs:core (the permission that guards it)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _json "$MUT/build.json" 'd["lib_apks"]["exec_native"].pop("rclone-engine"); d["lib_apks"]["exec_native"]["_planted"] = "rclone-engine-removed"'
    python3 -c 'import json,sys; sys.exit(0 if "rclone-engine" not in json.load(open(sys.argv[1]))["lib_apks"]["exec_native"] else 1)' "$MUT/build.json" \
        && _red "E6 #871 the rclone engine execs a binary but its APK is no longer extracted at install (the exec would find nothing)" engines "$MUT/libs" "$MUT/build.json"; }

M_MTMF="$MUT/libs/mounts-engine/src/main/AndroidManifest.xml"
M_MTSVC="$MUT/libs/mounts-engine/src/main/java/com/diegonmarcos/cloudlib/mounts/engine/MountsEngineService.kt"
R_MTSVC="$LIBS/mounts-engine/src/main/java/com/diegonmarcos/cloudlib/mounts/engine/MountsEngineService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MTMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/mounts-engine/src/main/AndroidManifest.xml" "$M_MTMF" 'engine.VERSION' \
        && _red "E8 #871 the mounts engine Cloud Drive opens every connection through stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MTSVC" '        CLOSE -> calls().close(JSONObject(request))
' ''
    python3 -c 'import sys; sys.exit(0 if "CLOSE -> calls" not in open(sys.argv[1]).read() else 1)' "$M_MTSVC" \
        && _applied "$R_MTSVC" "$M_MTSVC" 'OPEN -> calls' \
        && _red "E4 #871 the mounts engine lists close but its dispatch no longer answers it (connections would never be released)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MTMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/mounts-engine/src/main/AndroidManifest.xml" "$M_MTMF" 'android:exported="true"' \
        && _red "E1 #871 the mounts engine is exported without the signature guard (any app could open connections to the user's servers)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/mounts-engine/build.gradle" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$MUT/libs/mounts-engine/build.gradle" \
        && _applied "$LIBS/mounts-engine/build.gradle" "$MUT/libs/mounts-engine/build.gradle" "implementation project(':libs:mounts')" \
        && _red "E5 #871 the mounts engine no longer compiles against libs:core (the permission that guards it)" engines "$MUT/libs" "$MUT/build.json"; }

M_DECMF="$MUT/libs/decisions-engine/src/main/AndroidManifest.xml"
M_DECSVC="$MUT/libs/decisions-engine/src/main/java/com/diegonmarcos/superapp/decisions/engine/DecisionsEngineService.kt"
R_DECSVC="$LIBS/decisions-engine/src/main/java/com/diegonmarcos/superapp/decisions/engine/DecisionsEngineService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_DECMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/decisions-engine/src/main/AndroidManifest.xml" "$M_DECMF" 'engine.VERSION' \
        && _red "E8 #881 the decisions engine every app asks the decision model through stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_DECSVC" 'arrayOf(DECIDE, STATUS, CONSENT, OUTCOME)' 'arrayOf(DECIDE, STATUS)'
    _applied "$R_DECSVC" "$M_DECSVC" 'arrayOf(DECIDE, STATUS)' \
        && _red "E4 #881 the decisions engine dispatches consent but no longer lists it (the Configs switch would be refused)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_DECMF" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' ''
    _applied "$LIBS/decisions-engine/src/main/AndroidManifest.xml" "$M_DECMF" 'android:exported="true"' \
        && _red "E1 #881 the decisions engine is exported without the signature guard (any app could spend the fleet's budget)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/decisions-engine/build.gradle" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$MUT/libs/decisions-engine/build.gradle" \
        && _applied "$LIBS/decisions-engine/build.gradle" "$MUT/libs/decisions-engine/build.gradle" "implementation project(':libs:decisions-link')" \
        && _red "E5 #881 the decisions engine no longer compiles against libs:core (the permission that guards it)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    NW="$MUT/libs/net-wg/src/main/java/com/diegonmarcos/superapp/netwg/NetBackendService.kt"
    _sub "$NW" '"setIdleTunnel", "getIdleStatus", "needsConsent", "setRelay", "getRelayStatus", "probeRelay")' '"setIdleTunnel", "needsConsent", "setRelay", "getRelayStatus", "probeRelay")'
    _applied "$LIBS/net-wg/src/main/java/com/diegonmarcos/superapp/netwg/NetBackendService.kt" "$NW" '"setIdleTunnel", "needsConsent", "setRelay", "getRelayStatus", "probeRelay")' \
        && _red "E4 a typed engine (net-wg) answers a method its methodNames() does not list" engines "$MUT/libs" "$MUT/build.json"; }
echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 22 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-engine-services: all checks passed"; else echo "test-engine-services: $FAILURES check(s) FAILED"; exit 1; fi
