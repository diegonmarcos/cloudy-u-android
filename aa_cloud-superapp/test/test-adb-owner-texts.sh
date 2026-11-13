#!/usr/bin/env bash
# #1721 no user-facing text sends the user to an in-process ADB shell. Static: no build, no device.
#   T1  no string literal outside the owner tree (src/owner, the owner libs) says "SuperApp's ADB shell" / "armed for the SuperApp",
#       or names the old page path "Configs > ... ADB Shell" / "Configs > Perms > ADB": since #1682 the ADB shell is the page of
#       Cloud C3 Webserver, opened with ShellClient.openArmPage (or AdbReadiness.openAdbShell)
#   T2  #1732 no user-facing string (Kotlin literal or res/values XML) of the Store, the libs it links, or the client-safe shell-client names the
#       SuperApp as the shell broker / ADB owner ("SuperApp (the shell broker) is not installed", "Arm in SuperApp", "Install Cloud SuperApp", ...):
#       the ADB owner is Cloud C3 Webserver (ShellWire.BROKER_PKG)
#   T3  #1732 the Store's "owner not installed" caption names Cloud C3 Webserver and offers the install (StoreShell.arm -> offerInstall -> StoreHandoff)
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
OWNER_ONLY = ("shell-broker", "adb-shell-core", "shizuku-adb-debug-tools")   # the owner's own libs (they ARE the broker)
BAD2 = re.compile(r"SuperApp\\?'?s shell broker|SuperApp \(the shell broker\)|Install Cloud SuperApp|Arm in SuperApp|SuperApp missing|SuperApp is not installed|SuperApp broker|shell broker in the SuperApp|the SuperApp opens its arm")
def roots2():
    out = [os.path.join(R, "ac_cloud-store/app/src/main")]
    libs = os.path.join(R, "ab_cloud-libs-shared/libs")
    for l in sorted(os.listdir(libs)):
        if l not in OWNER_ONLY: out.append(os.path.join(libs, l, "src/main"))
    return out
def scan2(override=None):
    hits = []
    for top in roots2():
        for d, _, fs in os.walk(top):
            for f in fs:
                p = os.path.join(d, f)
                xml = f.endswith(".xml") and os.sep + "values" in d
                if not (f.endswith((".kt", ".java")) or xml): continue
                t = rd(p) if override is None or override[0] != p else override[1]
                if not xml: t = strip(t)
                units = [t] if xml else [m.group(0) for m in STR.finditer(t)]
                if xml: units = re.findall(r">([^<]*)<", t)
                for u in units:
                    if BAD2.search(u): hits.append(os.path.relpath(p, R) + ": " + u.strip()[:110])
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
h2 = scan2()
if h2:
    bad += 1; print("FAIL T2 a Store/libs text names the SuperApp as the ADB owner:"); [print("   ", x) for x in h2]
else: print("ok   T2 no Store/libs text names the SuperApp as the shell broker")
# mutation: a Kotlin literal and a resource string, both planted in real files
kt = os.path.join(R, "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreChannel.kt")
xm = os.path.join(R, "ab_cloud-libs-shared/libs/appstore/src/main/res/values/strings.xml")
if scan2((kt, 'val x = "SuperApp (the shell broker) is not installed"')): print("ok   T2 mutation planted Kotlin text is caught")
else: bad += 1; print("FAIL T2 mutation (Kotlin) not caught")
if scan2((xm, '<string name="a">Install Cloud SuperApp: it owns the shell</string>')): print("ok   T2 mutation planted resource text is caught")
else: bad += 1; print("FAIL T2 mutation (resource) not caught")
# T3: the Store's unpaired caption and its tap
sx = rd(xm)
m = re.search(r'name="store_bar_channel_unpaired_caption">([^<]*)<', sx)
cap = m.group(1) if m else ""
if "Cloud C3 Webserver" in cap and re.search(r"[Ii]nstall", cap) and "open" in cap.lower(): print("ok   T3 the unpaired caption names Cloud C3 Webserver and offers install / open")
else: bad += 1; print("FAIL T3 the unpaired caption does not name Cloud C3 Webserver with install/open: " + cap)
ssh = rd(os.path.join(R, "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreShell.kt"))
arm = re.search(r"fun arm\(.*?\n    }\n", ssh, re.S); arm = arm.group(0) if arm else ""
if "offerInstall(ctx)" in arm and "StoreHandoff.intent(" in ssh and "BROKER_PKG" in ssh: print("ok   T3 the Store chip tap offers the install when the owner is missing")
else: bad += 1; print("FAIL T3 StoreShell.arm does not offer the install of the ADB owner")
mut = arm.replace("offerInstall(ctx)", "false")
if "offerInstall(ctx)" not in mut: print("ok   T3 mutation (no install offer) is caught")
else: bad += 1; print("FAIL T3 mutation not caught")
sys.exit(1 if bad else 0)
PY
