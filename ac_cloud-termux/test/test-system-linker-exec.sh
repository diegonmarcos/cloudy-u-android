#!/bin/sh
# #1708 -- the first exec of a session at targetSdk 29+ goes through the system linker.
#
# From targetSdk 29 Android forbids execve() of a file in the app's data directory, and
# everything this terminal starts lives there. SystemLinkerExec (termux-shared) is what turns
# `$PREFIX/bin/login` into `/system/bin/linker64 $PREFIX/bin/sh $PREFIX/bin/login`; the
# bootstrap's termux-exec library (LD_PRELOAD) does the same for every exec after that one.
#
# This tester COMPILES SystemLinkerExec.java with a plain JDK (the class has no android import
# for exactly this reason) and RUNS it over real files: a dynamic ELF, a static ELF, #! scripts
# with and without an argument, a script with no #! line, a link out of the data directory, a
# #! loop. Then it reads the wiring: every exec site goes through it, the session carries
# LD_PRELOAD, enter.sh keeps termux-exec loaded until the guest starts and runs proot from the
# native library directory, the APK ships proot and its loader there, and targetSdk is 35+.
#
# No Android SDK, no network.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
SHARED="$DIR/termux-shared/src/main/java/com/termux/shared/shell"
APP="$DIR/app/src/main/java/com/termux"
SRC="$SHARED/SystemLinkerExec.java"

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT

echo "── #1708 system linker exec [$(basename "$DIR")] ──"

# ── 1. the class, executed ─────────────────────────────────────────────────
mkdir -p "$T/src/com/termux/shared/shell" "$T/classes"
cp "$SRC" "$T/src/com/termux/shared/shell/"
if grep -q '^import android\.' "$SRC"; then bad "SystemLinkerExec imports android.*: this tester can no longer run it"; fi
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
        if (a[0].equals("preload")) {
            System.out.println(SystemLinkerExec.preloadLibrary(a[1]));
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

DATA="$T/data/data/cld.termux"
BIN="$DATA/files/usr/bin"
LNK=/system/bin/linker64
mkdir -p "$BIN" "$DATA/files/home" "$T/apk/lib" "$DATA/files/usr/lib"
# A dynamic ELF and a static one, as the JDK's host has them: any dynamic binary, and proot's
# loader shape (an ELF with no PT_INTERP) written by hand so no compiler is needed.
cp "$(command -v sh)" "$BIN/dash" 2>/dev/null || cp /bin/sh "$BIN/dash"
ln -s dash "$BIN/sh"
ln -s dash "$BIN/bash"
ln -s dash "$BIN/env"
python3 - "$BIN/static-tool" <<'PY'
import struct, sys
# ELF64 little-endian, one PT_LOAD program header and no PT_INTERP: a statically linked program.
ehdr = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8) + struct.pack("<HHIQQQIHHHHHH", 2, 62, 1, 0x401000, 64, 0, 0, 64, 56, 1, 0, 0, 0)
phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, 0x400000, 0x400000, 120, 120, 0x1000)
open(sys.argv[1], "wb").write(ehdr + phdr)
PY
chmod 0700 "$BIN/static-tool"
printf '#!%s/sh\necho login\n' "$BIN" > "$BIN/login"
printf '#!/usr/bin/env bash\necho env\n' > "$DATA/files/home/env-script"
printf '#!/system/bin/sh\necho system\n' > "$DATA/files/home/system-script"
printf '#!  /bin/sh  -e  \necho spaced\n' > "$DATA/files/home/spaced-script"
printf 'echo no shebang\n' > "$DATA/files/home/plain-script"
printf '#!%s/files/home/loop-b\n' "$DATA" > "$DATA/files/home/loop-a"
printf '#!%s/files/home/loop-a\n' "$DATA" > "$DATA/files/home/loop-b"
cp "$BIN/static-tool" "$T/apk/lib/libcloudproot.so"
ln -s "$T/apk/lib/libcloudproot.so" "$DATA/files/native-proot"
cp "$BIN/dash" "$T/outside-tool"

expect() {  # $1 = what, $2 = expected argv joined with |, rest = Probe arguments
    what="$1"; want="$2"; shift 2
    got="$(probe "$@")"
    [ "$got" = "$want" ] && ok "$what" || bad "$what: got '$got', expected '$want'"
}
expect "a dynamic ELF in the data dir is started by the linker, arguments kept" \
    "$LNK|$BIN/dash|-c|true|" true "$LNK" "$DATA" "$BIN" "$BIN/dash" -c true
expect "a symlinked tool keeps the name it was called by (multicall binaries read argv[0])" \
    "$LNK|$BIN/bash|-l|" true "$LNK" "$DATA" "$BIN" "$BIN/bash" -l
expect "bin/login (#! \$PREFIX/bin/sh) becomes linker + sh + login" \
    "$LNK|$BIN/sh|$BIN/login|" true "$LNK" "$DATA" "$BIN" "$BIN/login"
expect "#!/usr/bin/env bash maps env into \$PREFIX/bin and keeps the one argument" \
    "$LNK|$BIN/env|bash|$DATA/files/home/env-script|a b|" true "$LNK" "$DATA" "$BIN" "$DATA/files/home/env-script" "a b"
expect "#!/system/bin/sh runs the system shell directly (a system file may be exec'd)" \
    "/system/bin/sh|$DATA/files/home/system-script|" true "$LNK" "$DATA" "$BIN" "$DATA/files/home/system-script"
expect "blanks around the interpreter and its argument are the kernel's" \
    "$LNK|$BIN/sh|-e|$DATA/files/home/spaced-script|" true "$LNK" "$DATA" "$BIN" "$DATA/files/home/spaced-script"
expect "a file with no #! line runs under the bootstrap's sh" \
    "$LNK|$BIN/sh|$DATA/files/home/plain-script|" true "$LNK" "$DATA" "$BIN" "$DATA/files/home/plain-script"
expect "a static ELF is left alone (the linker cannot load it; it ships in nativeLibraryDir instead)" \
    "$BIN/static-tool|-V|" true "$LNK" "$DATA" "$BIN" "$BIN/static-tool" -V
expect "a link in the data dir to a native library is NOT sent through the linker" \
    "$DATA/files/native-proot|-V|" true "$LNK" "$DATA" "$BIN" "$DATA/files/native-proot" -V
expect "a program outside the data dir is untouched" \
    "$T/outside-tool|x|" true "$LNK" "$DATA" "$BIN" "$T/outside-tool" x
expect "a missing program is untouched, so exec reports the real error" \
    "$BIN/nope|" true "$LNK" "$DATA" "$BIN" "$BIN/nope"
expect "where the rule does not apply nothing changes" \
    "$BIN/login|-c|x|" false "$LNK" "$DATA" "$BIN" "$BIN/login" -c x
loop="$(probe true "$LNK" "$DATA" "$BIN" "$DATA/files/home/loop-a")"
case "$loop" in *"loop-a|"*) ok "a #! loop ends instead of growing forever" ;; *) bad "a #! loop did not end with the script in argv: '$loop'" ;; esac

# The decision: Android 10+, and not a domain that may still exec its data.
expect "Android 9: never"                              "false|" required 28 "u:r:untrusted_app:s0:c1,c2"
expect "Android 14, targetSdk 35 (untrusted_app): yes" "true|"  required 34 "u:r:untrusted_app:s0:c512,c768"
expect "Android 14, shared uid still at 28 (untrusted_app_27): no" "false|" required 34 "u:r:untrusted_app_27:s0:c512,c768"
expect "Android 14, untrusted_app_25: no"              "false|" required 34 "u:r:untrusted_app_25:s0:c512,c768"
expect "Android 14, untrusted_app_32: yes"             "true|"  required 34 "u:r:untrusted_app_32:s0:c512,c768"
expect "SELinux context unreadable: no (as termux-exec decides)" "false|" required 34

# LD_PRELOAD: the 2.x library, then the 1.x name, never a file that is not a library.
expect "no termux-exec library, no LD_PRELOAD" "null|" preload "$DATA/files/usr"
: > "$DATA/files/usr/lib/libtermux-exec-ld-preload.so"
expect "an empty library file is not preloaded (it would stop every program from starting)" "null|" preload "$DATA/files/usr"
cp "$BIN/dash" "$DATA/files/usr/lib/libtermux-exec.so"
expect "termux-exec 1.x's name is used when 2.x's is absent" "$DATA/files/usr/lib/libtermux-exec.so|" preload "$DATA/files/usr"
cp "$BIN/dash" "$DATA/files/usr/lib/libtermux-exec-ld-preload.so"
expect "termux-exec 2.x's primary library is preferred" "$DATA/files/usr/lib/libtermux-exec-ld-preload.so|" preload "$DATA/files/usr"

# ── 2. the wiring ──────────────────────────────────────────────────────────
UTILS="$SHARED/TermuxShellUtils.java"
has() { grep -qF -- "$2" "$1"; }   # $1 = file, $2 = literal
has "$UTILS" 'return SystemLinkerExec.wrap(setupProcessArgsDirect(fileToExecute, arguments),' \
    && ok "TermuxShellUtils.setupProcessArgs wraps what it returns" \
    || bad "TermuxShellUtils.setupProcessArgs no longer goes through SystemLinkerExec.wrap"
has "$UTILS" 'environment.add("LD_PRELOAD=" + preload);' && has "$UTILS" 'addSystemLinkerExecEnvironment(currentPackageContext, environment);' \
    && ok "a session's environment carries LD_PRELOAD when the rule applies" \
    || bad "buildEnvironment does not add LD_PRELOAD: the first shell would start unintercepted and run nothing"
for site in "$SHARED/TermuxSession.java" "$SHARED/TermuxTask.java" "$APP/cloud/CloudExecService.java" "$APP/app/CloudSessionService.java"; do
    if grep -q 'setupProcessArgs(' "$site" && ! grep -q 'Runtime.getRuntime().exec(new String\|new ProcessBuilder' "$site"; then
        ok "$(basename "$site") builds its argv through setupProcessArgs"
    else
        bad "$(basename "$site") execs without setupProcessArgs"
    fi
done
# Every Runtime.exec / createSubprocess in the app's own code must be one of the sites above.
other="$(grep -rlE 'Runtime\.getRuntime\(\)\.exec\(|JNI\.createSubprocess\(|new ProcessBuilder' "$DIR/app/src/main/java" "$DIR/termux-shared/src/main/java" "$DIR/terminal-emulator/src/main/java" 2>/dev/null \
    | grep -vE '/(TermuxTask|CloudExecService|CloudSessionService|TerminalSession|AndroidUtils)\.java$' || true)"
[ -z "$other" ] && ok "no other file in the app starts a process" || bad "a new exec site bypasses SystemLinkerExec: $other"

ENTER="$DIR/rootfs/enter.sh"
python3 - "$ENTER" <<'PY' && ok "enter.sh keeps termux-exec loaded until the guest starts, and runs \$PROOT everywhere" || bad "enter.sh: LD_PRELOAD is dropped before the last Termux tool runs, or a proot call bypasses \$PROOT (see above)"
import re, sys
s = open(sys.argv[1]).read()
code = "\n".join(l for l in s.splitlines() if not l.lstrip().startswith("#"))
unsets = [m.start() for m in re.finditer(r"^unset LD_PRELOAD$", code, re.M)]
final = code.rfind('exec "$PROOT" --kill-on-exit')
assert len(unsets) == 1 and 0 < unsets[0] < final, ("exactly one unset, right before the session exec", unsets, final)
between = code[unsets[0]:final].strip().splitlines()
assert between == ["unset LD_PRELOAD"], ("nothing may run between the unset and the exec", between)
assert code.count('"$HERE/proot"') == 1 and 'PROOT="$HERE/proot"' in code, "the staged proot is named once outright, as the fallback"
runs = len(re.findall(r'"\$PROOT"|"\$\{PROOT:-\$HERE/proot\}"', code))
assert runs == 3 and code.count("$HERE/proot") == 3, ("unpack, webserver and session all run $PROOT (the two blocks tests run alone default it)", runs)
b, e = s.find("# native_proot begin"), s.find("# native_proot end")
block = s[b:e]
assert 0 < b < e and "realpath" in block and "export PROOT_LOADER" in block and "native-proot-loader" in block, "native_proot block"
PY

# enter.sh executed with stubs: with the two links, proot is the link's TARGET and PROOT_LOADER is set.
S="$T/stage"; mkdir -p "$S" "$T/nld" "$T/home"
cat > "$T/nld/libcloudproot.so" <<'STUB'
#!/bin/sh
echo "PROOT=$0 LOADER=${PROOT_LOADER:-unset} LD_PRELOAD=${LD_PRELOAD:-unset}"
STUB
cp "$T/nld/libcloudproot.so" "$T/nld/libcloudprootloader.so"; chmod 0755 "$T/nld/"*
cp "$ENTER" "$S/enter.sh"
cp "$T/nld/libcloudproot.so" "$S/proot"
mkdir -p "$S/rootfs/etc"; echo "root:x:0:0:root:/root:/bin/sh" > "$S/rootfs/etc/passwd"
echo deadbeef > "$S/rootfs.sha256"; echo deadbeef > "$S/rootfs/.cloud-rootfs.sha256"
# The stub proot is a script: started with a bogus LD_PRELOAD exported by the caller, its own
# output shows the variable was unset before it ran.
out="$(HOME="$T/home" CLOUD_ROOTFS_FALLBACK=false CLOUD_TEST_PRELOAD=x sh -c 'LD_PRELOAD="$CLOUD_TEST_PRELOAD"; export LD_PRELOAD; exec sh "$0" -c true' "$S/enter.sh" 2>/dev/null | tail -1)"
case "$out" in
    "PROOT=$S/proot LOADER=unset LD_PRELOAD=unset") ok "without the links enter.sh runs the staged proot, no PROOT_LOADER, LD_PRELOAD dropped for the guest" ;;
    *) bad "without the links: '$out'" ;;
esac
ln -s "$T/nld/libcloudproot.so" "$S/native-proot"
ln -s "$T/nld/libcloudprootloader.so" "$S/native-proot-loader"
out="$(HOME="$T/home" CLOUD_ROOTFS_FALLBACK=false sh "$S/enter.sh" -c true 2>/dev/null | tail -1)"
real_nld="$(cd "$T/nld" && pwd -P)"
case "$out" in
    "PROOT=$real_nld/libcloudproot.so LOADER=$real_nld/libcloudprootloader.so LD_PRELOAD=unset") ok "with the links enter.sh runs the native proot by its REAL path and points PROOT_LOADER at the native loader" ;;
    *) bad "with the links: '$out'" ;;
esac
rm -f "$S/native-proot-loader"
out="$(HOME="$T/home" CLOUD_ROOTFS_FALLBACK=false sh "$S/enter.sh" -c true 2>/dev/null | tail -1)"
case "$out" in
    "PROOT=$S/proot LOADER=unset LD_PRELOAD=unset") ok "one link alone is not enough: the staged proot runs (a proot without its loader would start nothing)" ;;
    *) bad "with only one link: '$out'" ;;
esac

ROOTFS_JAVA="$APP/cloud/CloudRootfs.java"
has "$ROOTFS_JAVA" 'static final String NATIVE_PROOT_LINK = "native-proot";' && has "$ROOTFS_JAVA" 'static final String NATIVE_LOADER_LINK = "native-proot-loader";' \
    && has "$ROOTFS_JAVA" 'linkNative(context);' && has "$APP/app/TermuxApplication.java" 'CloudRootfs.linkNative(this);' \
    && ok "CloudRootfs keeps the two links enter.sh reads, on every process start and every refresh" \
    || bad "the native-proot links are not maintained under the names enter.sh reads"
has "$UTILS" 'NATIVE_PROOT_LOADER = "libcloudprootloader.so"' && has "$UTILS" 'NATIVE_PROOT = "libcloudproot.so"' \
    && has "$DIR/app/build.gradle" "new File(fleetToolsDir, 'libcloudproot.so')" && has "$DIR/app/build.gradle" "new File(fleetToolsDir, 'libcloudprootloader.so')" \
    && has "$DIR/app/build.gradle" "preBuild.dependsOn tasks.named('stageNativeProot')" \
    && ok "the build ships proot and its loader under the library names the app links to" \
    || bad "app/build.gradle does not stage libcloudproot.so / libcloudprootloader.so, or the names differ from TermuxShellUtils"
has "$DIR/app/build.gradle" 'useLegacyPackaging true' && has "$DIR/app/src/main/AndroidManifest.xml" 'android:extractNativeLibs="true"' \
    && ok "native libraries are extracted to disk (a library inside the APK cannot be exec'd)" \
    || bad "native libraries are no longer extracted: nativeLibraryDir would hold nothing to exec"

# The loader carver refuses anything but the pinned bytes.
CARVE="$ROOT/ab_cloud-terminal-store/carve-proot-loader.py"
python3 - "$CARVE" "$ROOT/ab_cloud-terminal-store" <<'PY' && ok "proot-loader.json pins a loader for every ABI proot.json pins, against the same proot" || bad "proot-loader.json and proot.json disagree (see above)"
import json, sys
proot = json.load(open(sys.argv[2] + "/proot.json"))["archs"]
loader = json.load(open(sys.argv[2] + "/proot-loader.json"))["archs"]
assert set(proot) == set(loader), (sorted(proot), sorted(loader))
for abi, pin in proot.items():
    assert loader[abi]["proot_sha256"] == pin["sha256"], abi
    assert len(loader[abi]["loader_sha256"]) == 64, abi
PY
if python3 "$CARVE" x86_64 "$BIN/static-tool" "$T/carved" >/dev/null 2>&1; then
    bad "carve-proot-loader.py accepted a file that is not the pinned proot"
else
    ok "carve-proot-loader.py refuses a proot that is not the pin"
fi

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
fgs = {".app.TermuxService", ".app.RunCommandService", ".app.UnpackService"}
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
for svc in "$APP/app/TermuxService.java" "$APP/app/RunCommandService.java" "$APP/app/UnpackService.java"; do
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
