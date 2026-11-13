#!/usr/bin/env bash
# #1721 no user-facing text sends the user to an in-process ADB shell. Static: no build, no device.
#   T1  no string literal outside the owner tree (src/owner, the owner libs) says "SuperApp's ADB shell" / "armed for the SuperApp",
#       or names the old page path "Configs > ... ADB Shell" / "Configs > Perms > ADB": since #1682 the ADB shell is the page of
#       Cloud C3 Webserver, opened with ShellClient.openArmPage (or AdbReadiness.openAdbShell)
# Then each violation is planted and must turn red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys
R = sys.argv[1]
OWN_LIBS = ("shizuku-adb-debug-tools", "shell-broker", "adb-shell-core", "shell-client")
BAD = re.compile(r"SuperApp'?s ADB|armed for the SuperApp|Configs\s*[>▸›]\s*(?:Network\s*[>▸›]\s*)?(?:Perms\s*[>▸›]\s*)?ADB")
STR = re.compile(r'"(?:[^"\\\n]|\\.)*"')
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), s, flags=re.S)
def rd(p):
    with open(p, encoding="utf-8", errors="replace") as f: return f.read()
def roots():
    out = [os.path.join(R, "aa_cloud-superapp/app/src/main"), os.path.join(R, "aa_cloud-superapp/app/src/client")]
    libs = os.path.join(R, "ab_cloud-libs-shared/libs")
    for l in sorted(os.listdir(libs)):
        if l not in OWN_LIBS: out.append(os.path.join(libs, l, "src/main"))
    return out
def scan(override=None):
    hits = []
    for top in roots():
        for d, _, fs in os.walk(top):
            for f in fs:
                if not f.endswith((".kt", ".java")): continue
                p = os.path.join(d, f)
                t = strip(rd(p)) if override is None or override[0] != p else override[1]
                for m in STR.finditer(t):
                    if BAD.search(m.group(0)): hits.append(os.path.relpath(p, R) + ": " + m.group(0)[:100])
    return hits
bad = 0
h = scan()
if h:
    bad += 1; print("FAIL T1 stale ADB-shell text:"); [print("   ", x) for x in h]
else: print("ok   T1 no string literal points at an in-process ADB shell")
# mutation: plant one in a real file, it must be caught
probe = os.path.join(R, "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/PermAccess.kt")
planted = scan((probe, 'val x = "Arm the SuperApp\'s ADB shell (Configs > Perms > ADB shell)"'))
if planted: print("ok   T1 mutation planted text is caught")
else: bad += 1; print("FAIL T1 mutation not caught")
sys.exit(1 if bad else 0)
PY
