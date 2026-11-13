#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ engine-contract-guard.test — prove the guard FAILS when an engine ║
# ║ and the app that binds it disagree, and passes when they agree   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #705. The guard is the only check that reads BOTH sides of an app/engine
# binding (an app's own testers may not read the engine's source, and the
# engine's may not read the app's). A guard only ever watched passing is
# indistinguishable from `exit 0`, so this builds a fixture from the REAL files
# that declare the one binding today (cloud-drive -> Cloud-Lib-Gh), requires a
# pass on it, then breaks each property on a copy, proves the break landed, and
# requires a FAIL naming that property.
#
# python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-engine-contract-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FLEET=aa_cloud-superapp/data/constellation-fleet.json
LIBBJ=ab_cloud-libs-shared/lib-apks/build.json
GH=ab_cloud-libs-shared/libs/gh
DRIVEBJ=ac_cloud-drive/build.json
# #1693: the gh sign-in binding (the client that calls loginStart/loginPoll) moved from the SuperApp to Cloud Account
ACCBJ=ac_cloud-account/build.json
ACCMF=ac_cloud-account/app/src/main/AndroidManifest.xml
CLIENT=ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync/GhEngine.kt
SVC=$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt
MF=$GH/src/main/AndroidManifest.xml
WF=.github/workflows/ship-cloud-drive.yml
DRIVEMF=ac_cloud-drive/app/src/main/AndroidManifest.xml
DRIVEGRADLE=ac_cloud-drive/app/build.gradle
# the SuperApp binds two engines (gh, feed) and sees them through QUERY_ALL_PACKAGES, not <queries>
FEED=ab_cloud-libs-shared/libs/feed
SABJ=aa_cloud-superapp/build.json
SAMF=aa_cloud-superapp/app/src/main/AndroidManifest.xml
SAFEED=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/rss/RemoteFeed.kt
# #1332: the SuperApp also binds the cal engine (the Tasks popup), through cal/TasksFeed.kt
CAL=ab_cloud-libs-shared/libs/cal
SACAL=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/cal/TasksFeed.kt
SAGH=ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/GhEngine.kt
SAWF=.github/workflows/ship-cloud-superapp.yml
# Cloud News binds the news engine and names its methods as string literals
NEWS=ab_cloud-libs-shared/libs/news
NEWSBJ=ac_cloud-news/build.json
NEWSCLIENT=ac_cloud-news/app/src/main/java/com/diegonmarcos/cloudnews/NewsBridge.kt
NEWSMF=ac_cloud-news/app/src/main/AndroidManifest.xml
NEWSGRADLE=ac_cloud-news/app/build.gradle
NEWSWF=.github/workflows/ship-cloud-news.yml
# the image scan: a SHARED client (libs:ml-l-image, engine-client.json) binding a typed-wire engine
IMG=ab_cloud-libs-shared/libs/ml-l-image
IMGENGINE=ab_cloud-libs-shared/libs/ml-l-image-mlkit
IMGCLIENT=$IMG/src/main/java/com/diegonmarcos/superapp/image/ImageScanEngine.kt
IMGSVC=$IMGENGINE/src/main/java/com/diegonmarcos/superapp/image/ImageScanBackendService.kt
# #871 the analytics sink: a SHARED client (libs:analytics, engine-client.json) that every app compiles, binding an engine nothing compiles
AN=ab_cloud-libs-shared/libs/analytics
SINK=ab_cloud-libs-shared/libs/analytics-sink
ANCLIENT=$AN/src/main/java/com/diegonmarcos/superapp/analytics/SinkLink.kt
SINKSVC=$SINK/src/main/java/com/diegonmarcos/superapp/analytics/sink/AnalyticsSinkService.kt
# #871 the ops engine: a SHARED client (libs:ops, engine-client.json) the SuperApp and C3 compile, binding an engine neither compiles
OPS=ab_cloud-libs-shared/libs/ops
OPSENGINE=ab_cloud-libs-shared/libs/ops-engine
OPSCLIENT=$OPS/src/main/java/com/diegonmarcos/superapp/ops/OpsLink.kt
OPSSVC=$OPSENGINE/src/main/java/com/diegonmarcos/superapp/ops/engine/OpsEngineService.kt
for f in "$ROOT/$OPS/engine-client.json" "$ROOT/$OPSCLIENT" "$ROOT/$OPSSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
# #871 the rclone engine (move 10): a SHARED client (libs:rclone, engine-client.json) Cloud Drive compiles, binding an engine it does not compile
RC=ab_cloud-libs-shared/libs/rclone
RCENGINE=ab_cloud-libs-shared/libs/rclone-engine
RCCLIENT=$RC/src/main/java/com/diegonmarcos/cloudlib/rclone/RcloneLink.kt
RCSVC=$RCENGINE/src/main/java/com/diegonmarcos/cloudlib/rclone/engine/RcloneEngineService.kt
for f in "$ROOT/$RC/engine-client.json" "$ROOT/$RCCLIENT" "$ROOT/$RCSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
# #871 the mounts engine (move 12): a SHARED client (libs:mounts, engine-client.json) Cloud Drive compiles, binding an engine it does not compile
MT=ab_cloud-libs-shared/libs/mounts
MTENGINE=ab_cloud-libs-shared/libs/mounts-engine
MTCLIENT=$MT/src/main/java/com/diegonmarcos/cloudlib/mounts/MountLink.kt
MTSVC=$MTENGINE/src/main/java/com/diegonmarcos/cloudlib/mounts/engine/MountsEngineService.kt
for f in "$ROOT/$MT/engine-client.json" "$ROOT/$MTCLIENT" "$ROOT/$MTSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
# #881 the decisions engine: a SHARED client (libs:decisions-link, engine-client.json) that apps compile once one asks a decision model
DEC=ab_cloud-libs-shared/libs/decisions-link
DECENGINE=ab_cloud-libs-shared/libs/decisions-engine
DECCLIENT=$DEC/src/main/java/com/diegonmarcos/superapp/decisions/link/DecisionsLink.kt
DECSVC=$DECENGINE/src/main/java/com/diegonmarcos/superapp/decisions/engine/DecisionsEngineService.kt
for f in "$ROOT/$DEC/engine-client.json" "$ROOT/$DECCLIENT" "$ROOT/$DECSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
for f in "$ROOT/$AN/engine-client.json" "$ROOT/$ANCLIENT" "$ROOT/$SINKSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
for f in "$GUARD" "$ROOT/$FLEET" "$ROOT/$LIBBJ" "$ROOT/$DRIVEBJ" "$ROOT/$CLIENT" "$ROOT/$SVC" "$ROOT/$MF" "$ROOT/$WF" \
         "$ROOT/$DRIVEMF" "$ROOT/$DRIVEGRADLE" "$ROOT/$FEED/src/main/AndroidManifest.xml" "$ROOT/$SABJ" "$ROOT/$SAMF" \
         "$ROOT/$SAFEED" "$ROOT/$SACAL" "$ROOT/$CAL/src/main/AndroidManifest.xml" "$ROOT/$SAGH" "$ROOT/$ACCBJ" "$ROOT/$ACCMF" "$ROOT/$SAWF" "$ROOT/$NEWS/src/main/AndroidManifest.xml" "$ROOT/$NEWSBJ" \
         "$ROOT/$NEWSCLIENT" "$ROOT/$NEWSMF" "$ROOT/$NEWSGRADLE" "$ROOT/$NEWSWF" "$ROOT/$IMG/engine-client.json" \
         "$ROOT/$IMGCLIENT" "$ROOT/$IMGSVC"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done

# a fresh fixture: the real files, laid out as the repo lays them out
stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t/ab_cloud-libs-shared/libs" "$WORK/t/aa_cloud-superapp/data" \
        "$WORK/t/ab_cloud-libs-shared/lib-apks" "$(dirname "$WORK/t/$CLIENT")" "$WORK/t/.github/workflows"
    cp "$ROOT/$FLEET" "$WORK/t/$FLEET"; cp "$ROOT/$LIBBJ" "$WORK/t/$LIBBJ"; cp "$ROOT/$WF" "$WORK/t/$WF"
    cp -r "$ROOT/$GH" "$WORK/t/$GH"; cp "$ROOT/$DRIVEBJ" "$WORK/t/$DRIVEBJ"; cp "$ROOT/$CLIENT" "$WORK/t/$CLIENT"
    local f; for f in "$DRIVEMF" "$DRIVEGRADLE" "$SABJ" "$SAMF" "$SAFEED" "$SACAL" "$SAGH" "$ACCBJ" "$ACCMF" "$SAWF" \
                      "$NEWSBJ" "$NEWSCLIENT" "$NEWSMF" "$NEWSGRADLE" "$NEWSWF"; do
        mkdir -p "$(dirname "$WORK/t/$f")"; cp "$ROOT/$f" "$WORK/t/$f"; done
    cp -r "$ROOT/$FEED" "$WORK/t/$FEED"; cp -r "$ROOT/$CAL" "$WORK/t/$CAL"; cp -r "$ROOT/$NEWS" "$WORK/t/$NEWS"
    cp -r "$ROOT/$IMG" "$WORK/t/$IMG"; cp -r "$ROOT/$IMGENGINE" "$WORK/t/$IMGENGINE"
    cp -r "$ROOT/$AN" "$WORK/t/$AN"; cp -r "$ROOT/$SINK" "$WORK/t/$SINK"
    cp -r "$ROOT/$OPS" "$WORK/t/$OPS"; cp -r "$ROOT/$OPSENGINE" "$WORK/t/$OPSENGINE"
    cp -r "$ROOT/$DEC" "$WORK/t/$DEC"; cp -r "$ROOT/$DECENGINE" "$WORK/t/$DECENGINE"
    cp -r "$ROOT/$RC" "$WORK/t/$RC"; cp -r "$ROOT/$RCENGINE" "$WORK/t/$RCENGINE"
    cp -r "$ROOT/$MT" "$WORK/t/$MT"; cp -r "$ROOT/$MTENGINE" "$WORK/t/$MTENGINE"
}
guard() { python3 "$GUARD" "$WORK/t" >"$WORK/out" 2>&1; }
sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); assert sys.argv[2] in s, 'anchor not found'; open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$WORK/t/$1" "$2" "$3"; }
js() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$WORK/t/$1" "$2"; }
# PROOF THE BREAK LANDED: the fixture file differs from the real one and carries the planted text
landed() { python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$ROOT/$1" "$WORK/t/$1" "$2"; }
# red <label> <code the FAIL line must carry>
red() {
    if guard; then fail "$1 — the guard PASSED"; return; fi
    if grep -qF "FAIL  $2" "$WORK/out"; then ok "$1"; else fail "$1 — failed, but not with $2: $(head -c 300 "$WORK/out")"; fi
}

stage
if guard; then ok "the real binding passes: $(tail -1 "$WORK/out")"; else fail "the real binding does not pass: $(cat "$WORK/out")"; fi

stage; js "$FLEET" 'next(a for a in d["apps"] if a["id"] == "lib-gh")["id"] = "lib-gh-renamed"'
landed "$FLEET" 'lib-gh-renamed' && red "K1 the Store row the app names is gone" "K1"
stage; js "$FLEET" 'next(a for a in d["apps"] if a["id"] == "lib-gh")["kind"] = "app"'
landed "$FLEET" '"kind": "app"' && red "K1 the Store row is an app, not a lib" "K1"
stage; js "$FLEET" 'next(a for a in d["apps"] if a["id"] == "lib-gh")["package"] = "com.diegonmarcos.cloudlib.github"'
landed "$FLEET" 'cloudlib.github' && red "K1 the row's package is no engine module on the shelf" "K1"
stage; sub "$MF" 'android:name="com.diegonmarcos.cloud.engine.CONTRACT"' 'android:name="com.diegonmarcos.cloud.engine.VERSION"'
landed "$MF" 'engine.VERSION' && red "K2 the engine no longer declares a contract" "K2"
stage; sub "$MF" '${applicationId}.ENGINE' '${applicationId}.ENGINE2'
landed "$MF" 'ENGINE2' && red "K2 the engine answers a different action than the app looks for" "K2"
stage; js "$DRIVEBJ" 'd["engines"]["gh"]["min_contract"] = 2'
landed "$DRIVEBJ" '"min_contract": 2' && red "K3 the app needs a contract the engine does not declare" "K3"
stage; js "$IMG/engine-client.json" 'd["engines"]["image"]["method_contracts"]["recognize"] = 4'
landed "$IMG/engine-client.json" '"recognize": 4' && red "K3 a method needs a contract above the one the image engine declares" "K3"
stage; js "$IMG/engine-client.json" 'd["engines"]["image"]["method_contracts"]["detect"] = 4'
landed "$IMG/engine-client.json" '"detect": 4' && red "K3 #798 detect needs a contract above the one the image engine declares" "K3"
stage; js "$IMG/engine-client.json" 'd["engines"]["image"]["method_contracts"]["recognize"] = 0'
landed "$IMG/engine-client.json" '"recognize": 0' && red "K3 a per-method floor below the binding's own floor" "K3"
stage; sub "$SVC" 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START, LOGIN_POLL)' 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START)'
landed "$SVC" 'CREDENTIAL, LOGIN_START)' && red "K4 the engine drops a method the app still calls" "K4"
stage; sub "$CLIENT" '    fun status(host: String): Result = result(ask(STATUS, host))' '    fun status(host: String): Result = result(ask(STATUS, host))
    fun whoami(): Result = result(ask(WHOAMI))
    private val WHOAMI = "whoami"'
python3 -c 'import sys,re; s=open(sys.argv[1]).read(); s=s.replace("    private val WHOAMI = \"whoami\"\n", ""); s=s.replace("        const val STATUS = \"status\"", "        const val STATUS = \"status\"\n        const val WHOAMI = \"whoami\""); open(sys.argv[1],"w").write(s)' "$WORK/t/$CLIENT"
landed "$CLIENT" 'const val WHOAMI = "whoami"' && red "K4 the app calls a method the engine does not answer" "K4"
stage; sub "$CLIENT" 'const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"' 'const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.VERSION"'
landed "$CLIENT" 'engine.VERSION' && red "K5 the client reads a contract key the engines do not declare" "K5"
stage; js "$DRIVEBJ" 'd["modules"]["libs:gh"] = {"dir": "../ab_cloud-libs-shared/libs/gh", "type": "library"}'
landed "$DRIVEBJ" '"libs:gh"' && red "K6 the app declares the engine's module again (it would compile it)" "K6"
stage; sub "$WF" '      - "ab_cloud-libs-shared/libs/git-sync/**"' '      - "ab_cloud-libs-shared/libs/gh/**"
      - "ab_cloud-libs-shared/libs/git-sync/**"'
landed "$WF" 'libs/gh/**' && red "K6 the app's ship workflow watches the engine again (an engine edit re-ships it)" "K6"
stage; js "$SABJ" 'd["modules"]["libs:feed"] = {"dir": "../ab_cloud-libs-shared/libs/feed", "type": "library"}'
landed "$SABJ" '"libs:feed"' && red "K6 the SuperApp declares the feed engine's module again (finding F3)" "K6"
stage; sub "$SAWF" '      - "ab_cloud-libs-shared/libs/mail/**"' '      - "ab_cloud-libs-shared/libs/feed/**"
      - "ab_cloud-libs-shared/libs/mail/**"'
landed "$SAWF" 'libs/feed/**' && red "K6 the SuperApp's ship workflow watches the feed engine again" "K6"
stage; sub "$NEWSCLIENT" 'return ask("tone", topic)' 'return ask("toneHistory", topic)'
landed "$NEWSCLIENT" 'ask("toneHistory"' && red "K4 a client naming its method as a string literal calls one the engine does not answer" "K4"
stage; js "$NEWSBJ" 'd["modules"]["libs:news"] = {"dir": "../ab_cloud-libs-shared/libs/news", "type": "library"}'
landed "$NEWSBJ" '"libs:news"' && red "K6 Cloud News declares the news engine's module again (finding F3)" "K6"
stage; sub "$NEWSWF" '      - "ab_cloud-libs-shared/libs/updater/**"' '      - "ab_cloud-libs-shared/libs/news/**"
      - "ab_cloud-libs-shared/libs/updater/**"'
landed "$NEWSWF" 'libs/news/**' && red "K6 Cloud News's ship workflow watches the news engine again" "K6"
stage; sub "$NEWSMF" '<package android:name="${newsEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${newsEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$NEWSMF" \
    && red "K8 Cloud News stops querying its engine (finding F2: invisible on Android 11+)" "K8"
stage; python3 - "$WORK/t/$CLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "        if (found < needed) return Check.TooOld(pkg, found, needed)\n"
assert gate in s
open(p, "w").write(s.replace(gate, ""))
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$CLIENT" \
    && red "K7 the client binds without refusing a contract below the needed one" "K7"
stage; python3 - "$WORK/t/$SAFEED" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
bind = """        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx.applicationContext, service.packageName, service.name)
        }
"""
first = "        val pm = ctx.packageManager\n"
assert bind in s and first in s
open(p, "w").write(s.replace(bind, "").replace(first, first + bind.replace("service.packageName, service.name", "BuildConfig.FEED_ENGINE_PACKAGE, \"planted.Service\"")))
PYTHON
landed "$SAFEED" 'planted.Service' && red "K7 the client binds by class name before the handshake accepts the engine" "K7"
# Cloud Drive holds QUERY_ALL_PACKAGES (Manage ▸ Apps lists every app), which makes every engine
# visible; the per-package query is what these two cases prove, so the permission is planted out first.
stage; sub "$DRIVEMF" 'android:name="android.permission.QUERY_ALL_PACKAGES"' 'android:name="android.permission.PLANTED_NOT_QUERY_ALL"'
sub "$DRIVEMF" '<package android:name="${ghEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${ghEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$DRIVEMF" \
    && red "K8 the app does not query the engine's package (invisible on Android 11+, finding F2)" "K8"
stage; sub "$DRIVEMF" 'android:name="android.permission.QUERY_ALL_PACKAGES"' 'android:name="android.permission.PLANTED_NOT_QUERY_ALL"'
sub "$DRIVEGRADLE" 'ghEnginePackage: ghEnginePackage]' 'ghEnginePackage: "com.example.planted"]'
landed "$DRIVEGRADLE" 'com.example.planted' && red "K8 the queried placeholder is not the package the build resolved" "K8"
stage; sub "$SAMF" 'android:name="android.permission.QUERY_ALL_PACKAGES"' 'android:name="android.permission.PLANTED_NOT_QUERY_ALL"'
landed "$SAMF" 'PLANTED_NOT_QUERY_ALL' && red "K8 an app that drops QUERY_ALL_PACKAGES and queries nothing cannot see its engines" "K8"
stage; sub "$IMGSVC" 'const val OCR = "ocr"' 'const val OCR = "text"'
landed "$IMGSVC" '"text"' && red "K4 the image engine renames a method the shared client still calls" "K4"
stage; sub "$DRIVEGRADLE" "    implementation project(':libs:ml-l-image')" "    implementation project(':libs:ml-l-image-mlkit')"
landed "$DRIVEGRADLE" "project(':libs:ml-l-image-mlkit')" && red "K6 an app's gradle file links the image engine again (no module map entry needed)" "K6"
stage; python3 - "$WORK/t/$IMGCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "        if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$IMGCLIENT" \
    && red "K7 the shared typed-wire client binds without a contract floor" "K7"
stage; sub "$IMG/src/main/AndroidManifest.xml" '<package android:name="${imageEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${imageEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$IMG/src/main/AndroidManifest.xml" \
    && red "K8 the shared client module stops querying its engine for every consumer" "K8"
stage; js "$IMG/engine-client.json" 'd["engines"]["image"]["fleet"] = "lib-ml-l-image-renamed"'
landed "$IMG/engine-client.json" 'lib-ml-l-image-renamed' && red "K1 a shared client module names a Store row that does not exist" "K1"
stage; cat > "$WORK/t/$(dirname "$CLIENT")/PlantedBridge.kt" <<'KOTLIN'
package com.diegonmarcos.clouddrive.sync
class PlantedBridge(ctx: android.content.Context) {
    private val client = com.diegonmarcos.superapp.core.DataBackendClient(ctx, "com.diegonmarcos.cloudlib.cal", "com.diegonmarcos.superapp.cal.CalBackendService")
}
KOTLIN
[ -f "$WORK/t/$(dirname "$CLIENT")/PlantedBridge.kt" ] && red "K9 an app binds an engine by class name outside build.json::engines (how F2 hid)" "K9"
stage; sub "$SINKSVC" 'const val MATOMO = "matomo"' 'const val MATOMO = "matomo2"'
landed "$SINKSVC" '"matomo2"' && red "K4 #871 the sink engine renames a method every app's client still calls" "K4"
stage; python3 - "$WORK/t/$ANCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "        if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$ANCLIENT" \
    && red "K7 #871 the analytics client binds without a contract floor" "K7"
stage; sub "$AN/src/main/AndroidManifest.xml" '<package android:name="${analyticsEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${analyticsEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$AN/src/main/AndroidManifest.xml" \
    && red "K8 #871 the analytics client stops querying its engine for every consumer" "K8"
stage; js "$AN/engine-client.json" 'd["engines"]["analytics"]["fleet"] = "lib-analytics-sink-renamed"'
landed "$AN/engine-client.json" 'lib-analytics-sink-renamed' && red "K1 #871 the analytics client names a Store row that does not exist" "K1"
stage; js "$DRIVEBJ" 'd["modules"]["libs:analytics-sink"] = {"dir": "../ab_cloud-libs-shared/libs/analytics-sink", "type": "library"}'
landed "$DRIVEBJ" '"libs:analytics-sink"' && red "K6 #871 an app declares the sink engine's module (it would compile the POST back in)" "K6"
stage; sub "$OPSSVC" 'const val DAGU_START = "dagu_start"' 'const val DAGU_START = "dagu_start2"'
landed "$OPSSVC" '"dagu_start2"' && red "K4 #871 the ops engine renames a method the SuperApp's and C3's client still calls" "K4"
stage; python3 - "$WORK/t/$OPSCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "        if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$OPSCLIENT" \
    && red "K7 #871 the ops client binds without a contract floor" "K7"
stage; sub "$OPS/src/main/AndroidManifest.xml" '<package android:name="${opsEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${opsEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$OPS/src/main/AndroidManifest.xml" \
    && red "K8 #871 the ops client stops querying its engine for every consumer" "K8"
stage; js "$OPS/engine-client.json" 'd["engines"]["ops"]["fleet"] = "lib-ops-engine-renamed"'
landed "$OPS/engine-client.json" 'lib-ops-engine-renamed' && red "K1 #871 the ops client names a Store row that does not exist" "K1"
stage; js "$DRIVEBJ" 'd["modules"]["libs:ops-engine"] = {"dir": "../ab_cloud-libs-shared/libs/ops-engine", "type": "library"}'
landed "$DRIVEBJ" '"libs:ops-engine"' && red "K6 #871 an app declares the ops engine's module (it would compile the Dagu calls back in)" "K6"
stage; sub "$RCSVC" 'const val JOB_POLL = "job_poll"' 'const val JOB_POLL = "job_poll2"'
landed "$RCSVC" '"job_poll2"' && red "K4 #871 the rclone engine renames a method Cloud Drive's client still calls" "K4"
stage; python3 - "$WORK/t/$RCCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "            if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$RCCLIENT" \
    && red "K7 #871 the rclone client binds without a contract floor" "K7"
stage; sub "$RC/src/main/AndroidManifest.xml" '<package android:name="${rcloneEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${rcloneEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$RC/src/main/AndroidManifest.xml" \
    && red "K8 #871 the rclone client stops querying its engine for every consumer" "K8"
stage; js "$RC/engine-client.json" 'd["engines"]["rclone"]["fleet"] = "lib-rclone-engine-renamed"'
landed "$RC/engine-client.json" 'lib-rclone-engine-renamed' && red "K1 #871 the rclone client names a Store row that does not exist" "K1"
stage; js "$DRIVEBJ" 'd["modules"]["libs:rclone-engine"] = {"dir": "../ab_cloud-libs-shared/libs/rclone-engine", "type": "library"}'
landed "$DRIVEBJ" '"libs:rclone-engine"' && red "K6 #871 Cloud Drive declares the rclone engine's module (it would compile the binary back in)" "K6"
stage; sub "$MTSVC" 'const val OPEN = "open"' 'const val OPEN = "open2"'
landed "$MTSVC" '"open2"' && red "K4 #871 the mounts engine renames a method Cloud Drive's client still calls" "K4"
stage; python3 - "$WORK/t/$MTCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "            if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$MTCLIENT" \
    && red "K7 #871 the mounts client binds without a contract floor" "K7"
stage; sub "$MT/src/main/AndroidManifest.xml" '<package android:name="${mountsEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${mountsEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$MT/src/main/AndroidManifest.xml" \
    && red "K8 #871 the mounts client stops querying its engine for every consumer" "K8"
stage; js "$MT/engine-client.json" 'd["engines"]["mounts"]["fleet"] = "lib-mounts-engine-renamed"'
landed "$MT/engine-client.json" 'lib-mounts-engine-renamed' && red "K1 #871 the mounts client names a Store row that does not exist" "K1"
stage; js "$DRIVEBJ" 'd["modules"]["libs:mounts-engine"] = {"dir": "../ab_cloud-libs-shared/libs/mounts-engine", "type": "library"}'
landed "$DRIVEBJ" '"libs:mounts-engine"' && red "K6 #871 Cloud Drive declares the mounts engine's module (it would compile the protocol clients back in)" "K6"
stage; sub "$DECSVC" 'const val CONSENT = "consent"' 'const val CONSENT = "consent2"'
landed "$DECSVC" '"consent2"' && red "K4 #881 the decisions engine renames a method the client still calls" "K4"
stage; python3 - "$WORK/t/$DECCLIENT" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
gate = "            if (found < needed) return "
i = s.index(gate); j = s.index("\n", i) + 1
open(p, "w").write(s[:i] + s[j:])
PYTHON
python3 -c 'import sys; sys.exit(0 if "found < needed" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$DECCLIENT" \
    && red "K7 #881 the decisions client binds without a contract floor" "K7"
stage; sub "$DEC/src/main/AndroidManifest.xml" '<package android:name="${decisionsEnginePackage}" />' ''
python3 -c 'import sys; sys.exit(0 if "${decisionsEnginePackage}" not in open(sys.argv[1]).read() else 1)' "$WORK/t/$DEC/src/main/AndroidManifest.xml" \
    && red "K8 #881 the decisions client stops querying its engine for every consumer" "K8"
stage; js "$DEC/engine-client.json" 'd["engines"]["decisions"]["fleet"] = "lib-decisions-engine-renamed"'
landed "$DEC/engine-client.json" 'lib-decisions-engine-renamed' && red "K1 #881 the decisions client names a Store row that does not exist" "K1"
stage; js "$DEC/engine-client.json" 'd["engines"]["decisions"]["min_contract"] = 2'
landed "$DEC/engine-client.json" '"min_contract": 2' && red "K3 #881 the decisions client needs a contract the engine does not declare" "K3"
stage; js "$DRIVEBJ" 'd["modules"]["libs:decisions-engine"] = {"dir": "../ab_cloud-libs-shared/libs/decisions-engine", "type": "library"}'
landed "$DRIVEBJ" '"libs:decisions-engine"' && red "K6 #881 an app declares the decisions engine's module (it would compile the token and the budget back in)" "K6"
stage; js "$DRIVEBJ" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$SABJ" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$ACCBJ" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$NEWSBJ" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$IMG/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$AN/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$OPS/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$DEC/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$RC/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
js "$MT/engine-client.json" 'd.pop("engines"); d["_planted"] = "no-engines"'
landed "$DRIVEBJ" 'no-engines' && landed "$SABJ" 'no-engines' && landed "$ACCBJ" 'no-engines' && landed "$NEWSBJ" 'no-engines' && landed "$IMG/engine-client.json" 'no-engines' && red "vacuity: no app declares an engine, so nothing is checked" "no app declares"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS — the engine contract guard fails on every broken binding and passes the real one"; else echo "FAIL — $FAILURES case(s)"; exit 1; fi
