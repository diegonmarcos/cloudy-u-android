#!/usr/bin/env bash
# #1682 P3 ONE ADB owner, no in-process channel elsewhere. Static: no build, no device.
#   O1  no source under aa_cloud-superapp/app/src/main or ab_cloud-libs-shared/libs/*/src/main (outside the owner libs
#       shizuku-adb-debug-tools, shell-broker, adb-shell-core and the client libs:shell-client) names an adbdebug / shellbroker class,
#       unless 1_cicd/src/data/adb-in-process-ratchet.json lists it; no gradle file outside them links an owner lib unless listed
#   O2  every listed entry still is a user (the ratchet only shrinks: delete what moved to libs:shell-client)
#   O3  once adb-owner.json names an owner other than aa_cloud-superapp the ratchet is EMPTY (the cut-over moved every user)
# Then each violation is planted and must turn red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
python3 - "$ROOT" <<'PY'
import glob, json, os, re, sys
R = sys.argv[1]
OWN_LIBS = ("shizuku-adb-debug-tools", "shell-broker", "adb-shell-core", "shell-client")
USE = re.compile(r"\bcom\.diegonmarcos\.superapp\.(adbdebug|shellbroker)\.[A-Za-z]|^\s*import\s+com\.diegonmarcos\.superapp\.(adbdebug|shellbroker)\b", re.M)
LINK = re.compile(r"""project\(\s*['"]:libs:(shizuku-adb-debug-tools|shell-broker|adb-shell-core)['"]\s*\)""")
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
def rd(p):
    with open(p, encoding="utf-8", errors="replace") as f: return f.read()
rat = json.load(open(os.path.join(R, "1_cicd/src/data/adb-in-process-ratchet.json")))
owner = json.load(open(os.path.join(R, "1_cicd/src/data/adb-owner.json")))["adb_owner"]
def scan():
    users, links = set(), set()
    roots = [os.path.join(R, "aa_cloud-superapp/app/src/main")] + [d for d in glob.glob(os.path.join(R, "ab_cloud-libs-shared/libs/*/src/main")) if d.split(os.sep)[-3] not in OWN_LIBS]
    for top in roots:
        for d, _, fs in os.walk(top):
            for f in fs:
                if f.endswith((".kt", ".java")):
                    p = os.path.join(d, f)
                    if USE.search(strip(rd(p))): users.add(os.path.relpath(p, R))
    for g in glob.glob(os.path.join(R, "ab_cloud-libs-shared/libs/*/build.gradle*")) + [os.path.join(R, "aa_cloud-superapp/app/build.gradle")]:
        if g.split(os.sep)[-2] in OWN_LIBS or not os.path.exists(g): continue
        if LINK.search(strip(rd(g))): links.add(os.path.relpath(g, R))
    return users, links
def checks(users, links, rat, owner):
    out = []
    lf, lg = set(rat["files"]), set(rat["gradle"])
    out.append(("O1 no new in-process ADB user", sorted((users - lf) | (links - lg))))
    out.append(("O2 every ratchet entry still is a user (else delete it)", sorted((lf - users) | (lg - links))))
    out.append(("O3 after the cut-over no in-process user is left", sorted(lf | lg) if owner != "aa_cloud-superapp" else []))
    return out
U, L = scan(); fails = 0
for n, b in checks(U, L, rat, owner):
    print(("  PASS: " if not b else "  FAIL: ") + n + ("" if not b else " -> " + ", ".join(b[:12]))); fails += 1 if b else 0
print("  INFO: %d in-process source users and %d gradle links left for the P3 cut-over" % (len(rat["files"]), len(rat["gradle"])))
for label, rid, u, l, r, o in (
        ("a new SuperApp file using the broker in process", "O1", U | {"aa_cloud-superapp/app/src/main/java/X.kt"}, L, rat, owner),
        ("a new lib linking the shizuku lib", "O1", U, L | {"ab_cloud-libs-shared/libs/x/build.gradle"}, rat, owner),
        ("a moved file left in the ratchet", "O2", U - {next(iter(sorted(U)), "")}, L, rat, owner),
        ("the flip with users left", "O3", U, L, rat, "ac_cloud-c3-webserver")):
    red = any(n.startswith(rid) and b for n, b in checks(u, l, r, o))
    print(("  PASS: " if red else "  FAIL: ") + f"planted: {label} turns {rid} red"); fails += 0 if red else 1
print("FAIL" if fails else "PASS: one ADB owner ratchet"); sys.exit(1 if fails else 0)
PY
