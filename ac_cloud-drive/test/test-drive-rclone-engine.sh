#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════
# ║ #567 push 4 / #871 move 10 — Cloud Drive uses rclone through the ENGINE,  ║
# ║ and carries neither the binary nor its packaging                         ║
# ╚═════════════════════════════════════════════════════════════════
#
# The parsers, stores and the runner's handling of the engine's answers are proven by
# RcloneEngineTest and RcloneRunnerTest in every drive ship. The pinned binary, its fetch and
# its exec moved to libs:rclone-engine (Cloud-Lib-Rclone-Engine.apk); their checks moved to
# ab_cloud-libs-shared/lib-apks/test/test-rclone-engine.sh. What only a tester over the build
# files can see, here:
#
#   R1  the app does not extract native libs for a binary it no longer carries
#       (no useLegacyPackaging in app/build.gradle), and does not declare the engine's module.
#   R2  the client reaches the engine, not a process: RcloneRunner is built on the transport,
#       the module execs nothing, and the screen says what to install when the engine is absent.
#   R4  the remote-types catalogue is one named asset, opened by the screen and
#       read by the JVM test; the job store's declare() is what the host calls.
#   R5  the pure half imports nothing from android.*.
#
# OWN-SOURCE ONLY: everything read is under this app or a module dir its
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:rclone"]["dir"])))' "$APP/build.json" "$APP")"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/rclone"
RUNNER="$PKG/RcloneRunner.kt"
SCREEN="$PKG/RcloneScreen.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/rclone/RcloneEngineTest.kt"
APP_GRADLE="$APP/app/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$RUNNER" "$SCREEN" "$TEST" "$APP_GRADLE" "$PKG/RcloneLink.kt" "$PKG/RcloneTransport.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── R1 the app carries no binary ──"
if grep -qE "useLegacyPackaging" <<<"$(grep -vE '^[[:space:]]*(\*|//|/\*)' "$APP_GRADLE")"; then fail "ac_cloud-drive/app/build.gradle still sets useLegacyPackaging — nothing here is exec'd from nativeLibraryDir any more (the engine APK's flavor sets it)"; else pass "no useLegacyPackaging: the app extracts no native lib"; fi
if python3 -c 'import json,sys; sys.exit(1 if "libs:rclone-engine" in json.load(open(sys.argv[1]))["modules"] else 0)' "$APP/build.json"; then pass "build.json::modules does not declare the engine"; else fail "build.json::modules declares libs:rclone-engine — the binary would be compiled back in"; fi

echo "── R2 the client reaches the engine ──"
if grep -qE "^class RcloneRunner\(" "$RUNNER" && grep -qE "private val transport: RcloneTransport" "$RUNNER"; then pass "RcloneRunner is built on RcloneTransport"; else fail "RcloneRunner is not built on the transport"; fi
if grep -rqE "ProcessBuilder\(|nativeLibraryDir" "$MOD/src/main"; then fail "libs:rclone execs a process or reads nativeLibraryDir — the engine does that"; else pass "libs:rclone execs nothing"; fi
if grep -qE "unavailableReason" "$SCREEN"; then pass "the screen says what to install when the engine is absent"; else fail "the screen does not show the engine's absence"; fi

echo "── R4 catalogue + host contract ──"
if grep -qE "FleetData\.get\(.*RcloneRemoteTypes\.DATASET" "$SCREEN" && grep -qE "RcloneRemoteTypes\.DATASET" "$TEST"; then pass "remote-types dataset (#1547, served) read by name in screen and test"; else fail "remote-types dataset not read by its declared name in both screen and test"; fi
DSET="$(grep -oE 'const val DATASET = "[^"]+"' "$PKG/RcloneRemoteTypes.kt" | sed 's/.*= "//; s/"$//')"
[ -f "$ROOT/data/fleet/$DSET.json" ] && pass "served dataset file exists" || fail "served dataset file missing: data/fleet/$DSET.json"
[ -z "$(find "$MOD/src/main/assets" -type f 2>/dev/null)" ] && pass "the lib carries no data asset" || fail "libs:rclone still ships assets"
if grep -qE "fun declare\(jobs: List<RcloneJob>\)" "$PKG/RcloneJobs.kt" && grep -qE "\.declare\(" "$TEST"; then pass "declare() exists and is tested"; else fail "declare() contract missing or untested"; fi
for verb in start cancel lsjson obscure download test version; do
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches runner.$verb("; else fail "screen never calls .$verb("; fi
done

echo "── R5 pure half ──"
for f in "$PKG/RcloneConfig.kt" "$PKG/RcloneOutput.kt" "$PKG/RcloneJobs.kt" "$PKG/RcloneRemoteTypes.kt"; do
    if grep -qE "^import android\." "$f"; then fail "$(basename "$f") imports android.*"; else pass "$(basename "$f") pure"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-rclone-engine: all checks passed"; else echo "test-drive-rclone-engine: $FAILURES check(s) FAILED"; exit 1; fi
