#!/usr/bin/env bash
# ╔════════════════════════════════════════════════════════════════════╗
# ║ mesh-source-guard.test — prove the guard FAILS when an app drops out ║
# ║ of the fleet mesh, and passes on the real tree                      ║
# ╚════════════════════════════════════════════════════════════════════╝
#
# #753. A gate only ever watched passing is indistinguishable from `exit 0`.
# This copies the files the guard reads (git-tracked gradle scripts, patches,
# JSON (module maps, by-reference sources — #763), ship workflows, the fleet
# roster, every AndroidManifest.xml, DebugInitProvider.kt), requires a
# pass on the copy, then breaks one property at a time, proves the break
# landed, and requires a FAIL naming the app or the manifest it broke.
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-mesh-source-guard.py"
CORE_MF=ab_cloud-libs-shared/libs/core/src/main/AndroidManifest.xml
DEV_MF=ab_cloud-libs-shared/libs/devtools/src/main/AndroidManifest.xml
FLEET=aa_cloud-superapp/data/constellation-fleet.json
WRITER=ac_cloud-keyboard/app/build.gradle
MAIL=ac_cloud-mail/app/build.gradle.kts
CORE=ab_cloud-libs-shared/libs/core/build.gradle
SHARED=ab_cloud-libs-shared/libs
WRITER_WF=.github/workflows/ship-cloud-keyboard.yml
INIT_KT=ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/DebugInitProvider.kt
CAMERA_MF=ac_cloud-camera/app/src/main/AndroidManifest.xml
for f in "$GUARD" "$ROOT/$CORE_MF" "$ROOT/$DEV_MF" "$ROOT/$FLEET" "$ROOT/$WRITER" "$ROOT/$MAIL" "$ROOT/$CORE" "$ROOT/$WRITER_WF" "$ROOT/$INIT_KT" "$ROOT/$CAMERA_MF"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && { git ls-files -z -- '*.gradle' '*.gradle.kts' '*.patch' '*.json' '*Cargo.toml' '.github/workflows/ship-*.yml' '*AndroidManifest.xml'
                     printf '%s\0' "$INIT_KT"; } | xargs -0 cp --parents -t "$WORK/t")
}

# mutate <label> <file> <python-edit-of-s> <expected-substring-in-guard-output>
mutate() {
    stage
    local before after out
    before="$(cat "$WORK/t/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    after="$(cat "$WORK/t/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$WORK/t")"; local rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -5; fi
}

stage
out="$(python3 "$GUARD" "$WORK/t")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "unbroken copy passes ($(tail -1 <<<"$out"))"
else fail "unbroken copy is red (rc=$rc)"; printf '%s\n' "$out" | grep GAP; fi

# An app can reach a mesh lib by more than one route (writer: libs:core itself and libs:writer-ui, #1652; mail: libs:core and
# libs:updater), and since the fleet-default Configs tail (#1630) libs:fleetconfigs-logcat links libs:devtools itself, so cutting
# one dependency line no longer drops an app. Each mutation therefore removes EVERY route to the mesh lib: the dependency lines
# of the libs whose own closure holds it, computed by cloud_android_lib_closure (the module the guard itself uses), never
# listed here. Libs that do not reach it stay linked, so only the membership is gone. With core cut, devtools still arrives
# through the Logcat tab: the app keeps the debug provider and loses CONSTELLATION_DATA, the other half of the #743 defect.
# cut <label> <gradle file> <mesh lib> <expected-substring-in-guard-output>
cut() {
    stage
    local before after out
    before="$(cat "$WORK/t/$2")"
    python3 - "$ROOT/1_cicd/src/scripts" "$WORK/t" "$2" "$3" <<'PY'
import re, sys
sys.path.insert(0, sys.argv[1])
import cloud_android_lib_closure as C
root, rel, mesh = sys.argv[2], sys.argv[3], sys.argv[4]
edges = C.lib_edges(root)
reach = {l for l in C.shared_libs(root) if mesh in C.closure({l}, edges)} | {mesh}
p = root + "/" + rel
keep = [l for l in open(p).read().split("\n") if not (C._code(l) and C.deps(l) & reach)]
open(p, "w").write("\n".join(keep))
PY
    after="$(cat "$WORK/t/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$WORK/t")"; local rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -5; fi
}
cut "writer drops every lib that reaches core" "$WRITER" core \
    "keyboard         G1 dependency closure lacks libs:core (direct:"
cut "mail drops every lib that reaches core" "$MAIL" core \
    "mail             G1 dependency closure lacks libs:core (direct:"
# devtools reaches an app through core's api edge and, since #1630, through fleetconfigs-logcat's own: with no shared lib
# linking it any more, every app that links only core keeps CONSTELLATION_DATA and loses the provider, receiver and <queries>.
stage
before="$(cd "$WORK/t" && cat $SHARED/*/build.gradle* | cksum)"
(cd "$WORK/t" && for g in $SHARED/*/build.gradle*; do
    python3 -c "import re,sys; p=sys.argv[1]; s=open(p).read(); s=re.sub(r'(?m)^[ \t]*(?!//)\w+[ (]+project\([\x27\x22]:libs:devtools[\x27\x22]\)\)?[^\n]*\n', '', s); open(p,'w').write(s)" "$g"; done)
after="$(cd "$WORK/t" && cat $SHARED/*/build.gradle* | cksum)"
if [ "$before" = "$after" ]; then fail "no shared lib links devtools: the mutation did not land — the fixture moved"; else
    out="$(python3 "$GUARD" "$WORK/t")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "G1 dependency closure lacks libs:devtools" <<<"$out"; then ok "no shared lib links devtools any more goes red (G1 dependency closure lacks libs:devtools)"
    else fail "no shared lib links devtools any more stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -5; fi
fi
# A static lib an app compiles is a push path of its workflow, so a core edit ships writer.
mutate "writer's ship workflow stops watching core" "$WRITER_WF" \
    "s=s.replace('      - \"ab_cloud-libs-shared/libs/core/**\"\n', '', 1)" \
    "keyboard         G2 ship-cloud-keyboard.yml does not watch ab_cloud-libs-shared/libs/core/**"
# #763 G2 is two-sided: watching a lib the app does not compile rebuilds and
# republishes it for nothing (vault watched analytics, browser and updater).
mutate "writer's ship workflow watches a lib writer does not compile" "$WRITER_WF" \
    "s=s.replace('      - \"ab_cloud-libs-shared/libs/core/**\"\n', '      - \"ab_cloud-libs-shared/libs/core/**\"\n      - \"ab_cloud-libs-shared/libs/fin/**\"\n', 1)" \
    "keyboard         G2 ship-cloud-keyboard.yml watches ab_cloud-libs-shared/libs/fin/** which"
mutate "core stops requesting CONSTELLATION_DATA" "$CORE_MF" \
    "s=re.sub(r'<uses-permission[^>]*CONSTELLATION_DATA\"\s*/>', '', s, count=1)" \
    "libs:core no longer REQUESTS CONSTELLATION_DATA"
mutate "devtools drops its <queries>" "$DEV_MF" \
    "s=re.sub(r'<queries>.*?</queries>', '', s, count=1, flags=re.S)" \
    "libs:devtools lost <queries> MESH_MEMBER"
# #762 G4: the provider merges but the server cannot start. watchdog, writer
# and camera declare no INTERNET of their own — devtools' request is their only
# one, which is exactly the #762 defect when it goes.
mutate "devtools stops requesting INTERNET" "$DEV_MF" \
    "s=re.sub(r'<uses-permission android:name=\"android.permission.INTERNET\"\s*/>', '', s, count=1)" \
    "libs:devtools no longer REQUESTS INTERNET"
mutate "devtools drops the DebugInitProvider" "$DEV_MF" \
    "s=re.sub(r'<provider\s+android:name=\"com.diegonmarcos.superapp.devtools.DebugInitProvider\".*?/>', '', s, count=1, flags=re.S)" \
    "libs:devtools lost the DebugInitProvider"
mutate "DebugInitProvider.onCreate stops starting the server" "$INIT_KT" \
    "s=s.replace('runCatching { AppDebugServer.start(ctx) }', '// runCatching { AppDebugServer.start(ctx) }', 1)" \
    "DebugInitProvider.onCreate no longer calls AppDebugServer.start"
mutate "camera's manifest strips INTERNET back out" "$CAMERA_MF" \
    "s=s.replace('<uses-permission android:name=\"android.permission.CAMERA\"/>', '<uses-permission android:name=\"android.permission.CAMERA\"/><uses-permission android:name=\"android.permission.INTERNET\" tools:node=\"remove\"/>', 1)" \
    "camera           G4 app/src/main/AndroidManifest.xml removes android.permission.INTERNET"
mutate "a fleet app row points at a dir that is not there" "$FLEET" \
    "d=json.loads(s); [r.update(repo_url=r['repo_url']+'-gone') for r in d['apps'] if r['id']=='keyboard']; s=json.dumps(d)" \
    "keyboard         source dir"

[ "$FAILURES" -eq 0 ] && echo "mesh-source-guard.test: OK" || { echo "mesh-source-guard.test: $FAILURES FAILED"; exit 1; }
