#!/bin/sh
# #1708 -- this terminal at targetSdk 29+: bin/login is started by /system/bin/sh, proot and its
# loader run from the native library directory.
#
# From targetSdk 29 Android forbids execve() of a file in the app's data directory. Here that is
# exactly three files: $PREFIX/bin/login (a /system/bin/sh script the app execs for every
# session), $PREFIX/bin/proot-static (which login execs) and the loader proot extracts and execs
# for every guest program. Everything inside the guest is mapped by that loader, not exec'd.
#
# SystemLinkerExec is the SAME file as cloud-terminal-termux's (byte-identical, checked here);
# its full behaviour is executed by ac_cloud-termux/test/test-system-linker-exec.sh. This tester
# compiles this app's copy with a plain JDK, runs the cases this terminal depends on, and reads
# the wiring: every exec site goes through it, NativeProot links bin/proot-static and exports
# PROOT_LOADER when a command's environment is built, the APK ships both binaries as native
# libraries, and the manifest and code meet what targetSdk 31/34/35 demand.
#
# No Android SDK, no network.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
SHARED="$DIR/termux-shared/src/main/java/com/termux/shared"
APP="$DIR/app/src/main/java/com/termux"
SRC="$SHARED/shell/SystemLinkerExec.java"
TWIN="$ROOT/ac_cloud-termux/termux-shared/src/main/java/com/termux/shared/shell/SystemLinkerExec.java"

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }
has() { grep -qF -- "$2" "$1"; }   # $1 = file, $2 = literal

T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT

echo "── #1708 system linker exec [$(basename "$DIR")] ──"

# ── 1. the class: the same file as the termux terminal's, compiled and run ──
if [ -f "$TWIN" ]; then
    cmp -s "$SRC" "$TWIN" && ok "SystemLinkerExec.java is byte-identical in both terminals" \
        || bad "SystemLinkerExec.java differs between the two terminals"
else
    bad "missing $TWIN (this tester is unrun for the twin, not passing)"
fi
mkdir -p "$T/src/com/termux/shared/shell" "$T/classes"
cp "$SRC" "$T/src/com/termux/shared/shell/"
cat > "$T/src/com/termux/shared/shell/Probe.java" <<'JAVA'
package com.termux.shared.shell;

import java.util.Arrays;

/** argv on stdout, one element per line, for: Probe <required> <linker> <dataRoot> <binDir> <argv...> */
public final class Probe {
    public static void main(String[] a) {
        if (a[0].equals("required")) {
            System.out.println(SystemLinkerExec.required(Integer.parseInt(a[1]), a.length > 2 ? a[2] : null));
            return;
        }
        String[] argv = Arrays.copyOfRange(a, 4, a.length);
        for (String s : SystemLinkerExec.wrap(argv, new String[]{a[2]}, a[3], Boolean.parseBoolean(a[0]), a[1]))
            System.out.println(s);
    }
}
JAVA
if ! javac -nowarn -d "$T/classes" "$T/src/com/termux/shared/shell/SystemLinkerExec.java" "$T/src/com/termux/shared/shell/Probe.java" 2>"$T/javac.log"; then
    bad "SystemLinkerExec.java does not compile with a plain JDK: $(head -5 "$T/javac.log" | tr '\n' ' ')"
    echo "── $fails failure(s) ──"; exit 1
fi
probe() { java -cp "$T/classes" com.termux.shared.shell.Probe "$@" 2>/dev/null | tr '\n' '|'; }
expect() {  # $1 = what, $2 = expected argv joined with |, rest = Probe arguments
    what="$1"; want="$2"; shift 2
    got="$(probe "$@")"
    [ "$got" = "$want" ] && ok "$what" || bad "$what: got '$got', expected '$want'"
}
DATA="$T/data/data/cld.termux.nix"
BIN="$DATA/files/usr/bin"
LNK=/system/bin/linker64
mkdir -p "$BIN" "$T/apk/lib"
# bin/login as the bootstrap ships it: a /system/bin/sh script.
printf '#!/system/bin/sh\nset -e\nexec %s/proot-static "$@"\n' "$BIN" > "$BIN/login"
python3 - "$T/apk/lib/libcloudproot.so" <<'PY'
import struct, sys
# ELF64 little-endian, one PT_LOAD program header and no PT_INTERP: a statically linked program, as proot is.
ehdr = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8) + struct.pack("<HHIQQQIHHHHHH", 2, 62, 1, 0x401000, 64, 0, 0, 64, 56, 1, 0, 0, 0)
phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, 0x400000, 0x400000, 120, 120, 0x1000)
open(sys.argv[1], "wb").write(ehdr + phdr)
PY
ln -s "$T/apk/lib/libcloudproot.so" "$BIN/proot-static"
expect "bin/login is started by /system/bin/sh with its arguments (what the kernel's #! did before)" \
    "/system/bin/sh|$BIN/login|fish|-l|-c|true|" true "$LNK" "$DATA" "$BIN" "$BIN/login" fish -l -c true
expect "bin/proot-static, a link to the native library, is exec'd as it is (never through the linker)" \
    "$BIN/proot-static|-V|" true "$LNK" "$DATA" "$BIN" "$BIN/proot-static" -V
expect "where the rule does not apply bin/login is exec'd directly, as before" \
    "$BIN/login|fish|" false "$LNK" "$DATA" "$BIN" "$BIN/login" fish
expect "Android 14, targetSdk 35 (untrusted_app): the rule applies" "true|"  required 34 "u:r:untrusted_app:s0:c512,c768"
expect "Android 14, shared uid still at 28 (untrusted_app_27): it does not" "false|" required 34 "u:r:untrusted_app_27:s0:c512,c768"
expect "Android 9: never" "false|" required 28 "u:r:untrusted_app:s0:c1,c2"

# ── 2. the wiring ──────────────────────────────────────────────────────────
UTILS="$SHARED/termux/shell/TermuxShellUtils.java"
has "$UTILS" 'return SystemLinkerExec.wrap(setupShellCommandArgumentsDirect(executable, arguments),' \
    && ok "TermuxShellUtils.setupShellCommandArguments wraps what it returns" \
    || bad "TermuxShellUtils.setupShellCommandArguments no longer goes through SystemLinkerExec.wrap"
for site in "$SHARED/termux/shell/command/runner/terminal/TermuxSession.java" "$SHARED/shell/command/runner/app/AppShell.java" \
            "$APP/app/TerminalDebugApi.java" "$APP/app/CloudSessionService.java"; do
    grep -q 'setupShellCommandArguments(' "$site" \
        && ok "$(basename "$site") builds its argv through setupShellCommandArguments" \
        || bad "$(basename "$site") execs without setupShellCommandArguments"
done
other="$(grep -rlE 'Runtime\.getRuntime\(\)\.exec\(|JNI\.createSubprocess\(|new ProcessBuilder' "$DIR/app/src/main/java" "$DIR/termux-shared/src/main/java" "$DIR/terminal-emulator/src/main/java" 2>/dev/null \
    | grep -vE '/(AppShell|TerminalDebugApi|CloudSessionService|TerminalSession|AndroidUtils)\.java$' || true)"
[ -z "$other" ] && ok "no other file in the app starts a process" || bad "a new exec site bypasses SystemLinkerExec: $other"
for site in "$SHARED/termux/shell/command/runner/terminal/TermuxSession.java" "$APP/app/CloudSessionService.java"; do
    has "$site" 'setupShellCommandArgumentsDirect(' \
        && ok "$(basename "$site"): a session started through /system/bin/sh is not handed a \"-sh\" argv[0] (no login-shell mksh reading ~/.profile)" \
        || bad "$(basename "$site") would start /system/bin/sh as a login shell"
done

NP="$SHARED/termux/shell/NativeProot.java"
ENV="$SHARED/termux/shell/command/environment/TermuxShellEnvironment.java"
has "$ENV" 'NativeProot.apply(currentPackageContext, environment);' \
    && ok "every command's environment goes through NativeProot.apply" \
    || bad "TermuxShellEnvironment.getEnvironment no longer calls NativeProot.apply: proot would run from the data dir"
python3 - "$NP" <<'PY' && ok "NativeProot: only where the rule applies, both libraries required, PROOT_LOADER exported, the old proot renamed and never deleted" || bad "NativeProot lost a guarantee (see above)"
import re, sys
s = open(sys.argv[1]).read()
code = "\n".join(l for l in s.splitlines() if not l.strip().startswith(("*", "/*", "//")))
assert "if (!SystemLinkerExec.required()) return;" in code, "not gated on the rule"
assert 'NATIVE_PROOT = "libcloudproot.so"' in code and 'NATIVE_PROOT_LOADER = "libcloudprootloader.so"' in code, "library names"
assert "if (!proot.isFile() || !loader.isFile())" in code, "both libraries are required"
assert 'environment.put(ENV_PROOT_LOADER, loader.getAbsolutePath());' in code and 'ENV_PROOT_LOADER = "PROOT_LOADER"' in code, "PROOT_LOADER"
assert 'PROOT_ENTRY = "proot-static"' in code and "Os.symlink(" in code, "the link"
assert "Os.rename(path, new File(bin, PROOT_KEPT).getAbsolutePath());" in code, "the bootstrap's proot is kept"
assert not re.search(r"entry\.delete\(\)|Os\.remove\(|Os\.unlink\(", code), "the bootstrap's proot must never be deleted"
assert "catch (Throwable t)" in code, "never throws"
PY
has "$DIR/app/build.gradle" 'new File(libDir, "libcloudproot.so")' && has "$DIR/app/build.gradle" 'new File(libDir, "libcloudprootloader.so")' \
    && has "$DIR/app/build.gradle" "preBuild.dependsOn tasks.named('stageNativeProot')" \
    && has "$DIR/app/build.gradle" 'ab_cloud-terminal-store' \
    && ok "the build ships the pinned proot and its carved loader under the library names NativeProot links to" \
    || bad "app/build.gradle does not stage libcloudproot.so / libcloudprootloader.so"
has "$DIR/app/build.gradle" 'useLegacyPackaging true' && has "$DIR/app/src/main/AndroidManifest.xml" 'android:extractNativeLibs="true"' \
    && ok "native libraries are extracted to disk (a library inside the APK cannot be exec'd)" \
    || bad "native libraries are no longer extracted: nativeLibraryDir would hold nothing to exec"

# ── 3. the target, and what else it demands ────────────────────────────────
TARGET="$(sed -n 's/^targetSdkVersion=//p' "$DIR/gradle.properties")"
GUARD="$ROOT/1_cicd/src/data/target-sdk-guard.json"
# Two states are whole: 28 WITH the fleet guard's exemption (the code above is shipped but inert,
# and prove-terminal-target-sdk.yml builds it at 35 without publishing), or 35+ WITHOUT it.
exempt="$(python3 -c 'import json,sys; print("yes" if sys.argv[2] in json.load(open(sys.argv[1])).get("exempt", {}) else "no")' "$GUARD" "$(basename "$DIR")" 2>/dev/null || echo unknown)"
case "${TARGET:-0}:$exempt" in
    28:yes) ok "targetSdkVersion is 28 and target-sdk-guard.json still exempts this app (the 35 build is proven on emulators before it is published)" ;;
    3[5-9]:no|[4-9][0-9]:no) ok "targetSdkVersion is $TARGET (35+: Cloud Store updates are silent on Android 17) and the fleet guard no longer exempts this app" ;;
    *) bad "targetSdkVersion '${TARGET:-unset}' and the guard exemption ('$exempt') disagree: 28 needs the exemption, 35+ must drop it" ;;
esac
M="$DIR/app/src/main/AndroidManifest.xml"
python3 - "$M" <<'PY' && ok "manifest: every component with an intent filter says exported, every foreground service declares specialUse with its subtype" || bad "manifest does not meet targetSdk 31/34 (see above)"
import sys, xml.dom.minidom
A = "http://schemas.android.com/apk/res/android"
doc = xml.dom.minidom.parse(sys.argv[1])
for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
    for el in doc.getElementsByTagName(tag):
        if el.getElementsByTagName("intent-filter"):
            assert el.getAttributeNS(A, "exported") in ("true", "false"), (tag, el.getAttributeNS(A, "name"), "no android:exported")
perms = {p.getAttributeNS(A, "name") for p in doc.getElementsByTagName("uses-permission")}
assert "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in perms, "FOREGROUND_SERVICE_SPECIAL_USE is not requested"
fgs = {".app.TermuxService", ".app.RunCommandService", ".app.UnpackService", ".app.MeshSshdService"}
for el in doc.getElementsByTagName("service"):
    name = el.getAttributeNS(A, "name")
    if name in fgs:
        assert el.getAttributeNS(A, "foregroundServiceType") == "specialUse", (name, "no foregroundServiceType")
        props = [p.getAttributeNS(A, "name") for p in el.getElementsByTagName("property")]
        assert "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in props, (name, "no special-use subtype")
        fgs.discard(name)
assert not fgs, ("foreground services missing from the manifest", fgs)
assert doc.documentElement.getAttributeNS(A, "sharedUserId") == "cld.termux.shared", "sharedUserId changed"
PY
if grep -rnE 'PendingIntent\.get(Activity|Service|Broadcast|ForegroundService)\(' "$DIR/app/src/main/java" "$DIR/termux-shared/src/main/java" | grep -v 'FLAG_IMMUTABLE\|FLAG_MUTABLE' | grep -q .; then
    bad "a PendingIntent is created without FLAG_IMMUTABLE/FLAG_MUTABLE (a crash at targetSdk 31+): $(grep -rnE 'PendingIntent\.get(Activity|Service|Broadcast|ForegroundService)\(' "$DIR/app/src/main/java" "$DIR/termux-shared/src/main/java" | grep -v 'FLAG_IMMUTABLE\|FLAG_MUTABLE' | head -3)"
else
    ok "every PendingIntent states its mutability"
fi
if grep -rnE '(^|[^.A-Za-z])registerReceiver\(' "$DIR/app/src/main/java" "$DIR/termux-shared/src/main/java" | grep -v 'RECEIVER_EXPORTED\|RECEIVER_NOT_EXPORTED\|ContextCompat\.registerReceiver' | grep -q .; then
    bad "a receiver is registered without an export flag (a SecurityException at targetSdk 34+)"
else
    ok "every runtime receiver states whether it is exported"
fi
for svc in "$APP/app/TermuxService.java" "$APP/app/RunCommandService.java" "$APP/app/UnpackService.java" "$APP/app/MeshSshdService.java"; do
    python3 - "$svc" <<'PY' && ok "$(basename "$svc"): a refused foreground start is caught, never a crash" || bad "$(basename "$svc") calls startForeground outside a try block"
import re, sys
lines = [l.strip() for l in open(sys.argv[1]).read().splitlines()]
code = [l for l in lines if l and not l.startswith(("//", "*", "/*"))]
calls = [i for i, l in enumerate(code) if re.match(r"startForeground\(", l)]
assert calls, "no startForeground call"
for i in calls:
    assert code[i - 1] == "try {", ("startForeground is not the first statement of a try block", code[i - 1], code[i])
PY
done
has "$DIR/app/src/main/res/values-v35/edge_to_edge.xml" 'android:windowOptOutEdgeToEdgeEnforcement">true' \
    && has "$APP/app/TermuxApplication.java" 'R.style.CloudEdgeToEdgeOptOut' \
    && ok "the terminal's own screens keep their window inside the system bars at targetSdk 35" \
    || bad "the edge-to-edge opt-out (values-v35 + TermuxApplication) is gone: the terminal would draw under the bars"

echo "── $fails failure(s) ──"
[ "$fails" -eq 0 ]
