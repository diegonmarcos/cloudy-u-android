#!/usr/bin/env bash
# #1682 P3 ONE ADB owner, no in-process channel elsewhere. Static: no build, no device.
#   O1  no source under aa_cloud-superapp/app/src/main or ab_cloud-libs-shared/libs/*/src/main (outside the owner libs
#       shizuku-adb-debug-tools, shell-broker, adb-shell-core and the client libs:shell-client) names an adbdebug / shellbroker class,
#       unless 1_cicd/src/data/adb-in-process-ratchet.json lists it; no gradle file outside them links an owner lib unless listed
#   O2  every listed entry still is a user (the ratchet only shrinks: delete what moved to libs:shell-client)
#   O4  #1682 P3 step 5: the SuperApp builds as a CLIENT, so nothing under aa_cloud-superapp/app/src/main or src/test (or a shared lib outside
#       the owner libs) names a class of libs:adb-shell-core / shizuku-adb-debug-tools / shell-broker; those classes are used only from
#       src/owner and src/owner/test (the client variant, src/client, names none of them either)
#   O5  #1682 P4 parity: the client AdbSide has exactly the members of the owner AdbSide (a feature added on one side only breaks one build),
#       and the client's ADB page (AdbOwnerLinkFragment) opens the owner's page and shows its readiness
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
GATED = re.compile(r"//\s*ADB-OWNER-SIDE BEGIN.*?//\s*ADB-OWNER-SIDE END", re.S)   # #1682 P3 step 5: a link gated on the owner side is not an in-process user
def gradle_code(s): return strip(GATED.sub("", s))
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
        if LINK.search(gradle_code(rd(g))): links.add(os.path.relpath(g, R))
    return users, links
def owner_classes():
    names = set()
    for lib in ("adb-shell-core", "shizuku-adb-debug-tools", "shell-broker"):
        for d, _, fs in os.walk(os.path.join(R, "ab_cloud-libs-shared/libs", lib, "src/main")):
            for f in fs:
                if f.endswith((".kt", ".java")):
                    names |= set(re.findall(r"^(?:public |internal |private |abstract |sealed |data |enum |open |fun )*(?:class|object|interface) ([A-Z]\w+)", rd(os.path.join(d, f)), re.M))
    return names - {"Tab", "State", "Status", "Prefs", "Ok", "Row", "Result", "Method", "Allowlist", "ConnState", "StatusRow", "Reply"} | {"Allowlist"}
def client_users():
    names = owner_classes(); out = set()
    # a name the SuperApp declares itself (its own energy ShizukuUserService) is not the owner lib's class
    for d, _, fs in os.walk(os.path.join(R, "aa_cloud-superapp/app/src/main")):
        for f in fs:
            if f.endswith(".kt"): names -= set(re.findall(r"^(?:public |internal |private |abstract |sealed |data |enum |open )*(?:class|object|interface) ([A-Z]\w+)", rd(os.path.join(d, f)), re.M))
    strs = re.compile(r'"(?:[^"\\\n]|\\.)*"')
    roots = [os.path.join(R, "aa_cloud-superapp/app/src/main"), os.path.join(R, "aa_cloud-superapp/app/src/client"), os.path.join(R, "aa_cloud-superapp/app/src/test")]
    rx = re.compile(r"\b(" + "|".join(sorted(names, key=len, reverse=True)) + r")\b")
    for top in roots:
        for d, _, fs in os.walk(top):
            for f in fs:
                if f.endswith(".kt"):
                    p = os.path.join(d, f)
                    t = strs.sub('""', strip(rd(p)))
                    # only a real reference: an import of / fully-qualified use of the owner class, or a same-package use of one of its names
                    for m in rx.finditer(t):
                        out.add(os.path.relpath(p, R) + " -> " + m.group(1)); break
    return out
def side_members(t):
    t = strip(t)
    return sorted(set(re.findall(r"^\s{4}(?:fun|val|const val|class)\s+(\w+)", t, re.M)))
def parity(owner_src, client_src, link_src):
    bad = []
    o, c = side_members(owner_src), side_members(client_src)
    bad += ["owner-only member " + m for m in o if m not in c] + ["client-only member " + m for m in c if m not in o]
    ls = strip(link_src)
    if "ShellClient.openArmPage(" not in ls: bad.append("client page does not open the owner's page")
    if "AdbReadiness.state" not in ls: bad.append("client page does not show the owner's readiness")
    return bad
def checks(users, links, rat, owner):
    out = []
    lf, lg = set(rat["files"]), set(rat["gradle"])
    out.append(("O1 no new in-process ADB user", sorted((users - lf) | (links - lg))))
    out.append(("O2 every ratchet entry still is a user (else delete it)", sorted((lf - users) | (lg - links))))
    SD = "aa_cloud-superapp/app/src/%s/java/com/diegonmarcos/superapp/"
    out.append(("O5 client AdbSide matches the owner AdbSide; the client page links to the owner", parity(rd(os.path.join(R, SD % "owner" + "adbside/AdbSide.kt")), rd(os.path.join(R, SD % "client" + "adbside/AdbSide.kt")), rd(os.path.join(R, SD % "client" + "configs/AdbOwnerLinkFragment.kt")))))
    out.append(("O4 the client-built SuperApp sources name no owner class", sorted(client_users())))
    out.append(("O3 after the cut-over no in-process user is left", sorted(lf | lg) if owner != "aa_cloud-superapp" else []))
    return out
U, L = scan(); fails = 0
for n, b in checks(U, L, rat, owner):
    print(("  PASS: " if not b else "  FAIL: ") + n + ("" if not b else " -> " + ", ".join(b[:12]))); fails += 1 if b else 0
print("  INFO: %d in-process source users and %d gradle links left for the P3 cut-over" % (len(rat["files"]), len(rat["gradle"])))
FAKE = dict(rat, files=list(rat["files"]) + ["aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/Moved.kt"])   # an entry no source uses
for label, rid, u, l, r, o in (
        ("a new SuperApp file using the broker in process", "O1", U | {"aa_cloud-superapp/app/src/main/java/X.kt"}, L, rat, owner),
        ("a new lib linking the shizuku lib", "O1", U, L | {"ab_cloud-libs-shared/libs/x/build.gradle"}, rat, owner),
        ("a moved file left in the ratchet", "O2", U, L, FAKE, owner),
        ("the flip with users left", "O3", U, L, FAKE, "ac_cloud-c3-webserver")):
    red = any(n.startswith(rid) and b for n, b in checks(u, l, r, o))
    print(("  PASS: " if red else "  FAIL: ") + f"planted: {label} turns {rid} red"); fails += 0 if red else 1
_o = rd(os.path.join(R, "aa_cloud-superapp/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt")); _c = rd(os.path.join(R, "aa_cloud-superapp/app/src/client/java/com/diegonmarcos/superapp/adbside/AdbSide.kt")); _l = rd(os.path.join(R, "aa_cloud-superapp/app/src/client/java/com/diegonmarcos/superapp/configs/AdbOwnerLinkFragment.kt"))
for label, o2, c2, l2 in (("a member only on the owner AdbSide", _o + "\n    fun extraOwnerOnly() = 1\n", _c, _l), ("a member only on the client AdbSide", _o, _c + "\n    fun extraClientOnly() = 1\n", _l),
                          ("a client page that no longer opens the owner", _o, _c, _l.replace("ShellClient.openArmPage(", "x(")), ("a client page with no readiness", _o, _c, _l.replace("AdbReadiness.state", "y"))):
    red = bool(parity(o2, c2, l2)); print(("  PASS: " if red else "  FAIL: ") + f"planted: {label} turns O5 red"); fails += 0 if red else 1
print("FAIL" if fails else "PASS: one ADB owner ratchet"); sys.exit(1 if fails else 0)
PY
