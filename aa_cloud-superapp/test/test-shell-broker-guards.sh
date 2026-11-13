#!/usr/bin/env bash
# #1510 The shell broker is the ONE owner of the privileged shell. Static: no build, no device.
#   B1  STRICT (#1512): no app other than the SuperApp compiles libs:shizuku-adb-debug-tools, and data/shell-migration.json's `pending`
#       is EMPTY (the migration is done; the file stays as the ratchet, an entry can never be added again)
#   B2  no app other than the SuperApp names an ADB connection class: EmbeddedAdbChannel, AdbShellServer, HostShell, WdKeeper, AdbPairingService, RishBridge
#   B7  the shared libs other apps compile (libs:account, libs:appstore, libs:updater) do not link or import the shizuku lib, except libs:updater's
#       SuperApp-only source set (src/shell) behind its findProject guard
#   B8  the stdin door: only install verbs may stream (`pm install -S`, `pm install-write -S`), every cap is within the hard limit, and no mesh /
#       terminal / read-only caller has a stdin rule
#   B3  every app that links libs:shell-client has an allowlist entry under its application id; every allowlist caller is a known app id or alias
#   B4  the broker and the client have no sticky service, no boot receiver, no foreground service, no alarm or WorkManager loop
#   B5  the allowlist is default-deny: no caller has a catch-all rule, and the broker lib declares no unguarded component
#   B6  libs:shell-client links nothing (no libs:core, no shizuku lib): linking it adds no fan-out edge
#   B9  #1682 ONE ADB owner: the owner is the app named by 1_cicd/src/data/adb-owner.json::adb_owner (B1/B2 read it,
#       not a hard-coded SuperApp); exactly that app compiles libs:shizuku-adb-debug-tools and libs:shell-broker (the exported provider), its
#       application id is the declared `package`, and libs:shell-client's ShellWire.BROKER_PKG and <queries> name that same package
#   B10 #1682 the declared mesh relay caller (ac_cloud-c3-webserver/data/mesh-shell-relay.json::caller) is the allowlist alias of the
#       webserver and is a caller with no stdin rule
#   B11 #1682 P2b the owner side of a non-owner app is gated: its gradle links the ADB libs only inside the ADB-OWNER-SIDE block under
#       `if (adbOwnerSide)`, settings.gradle derives adbOwnerSide from adb-owner.json (or the non-shipped CI verify env), and the dormant
#       stand-in it ships names no ADB class. B1 reads the SHIPPED variant (the gated block stripped).
# Then each violation is planted in the collected facts and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
python3 - "$ROOT" <<'PY'
import json, os, re, sys
R = sys.argv[1]; fails = 0
LIB = "ab_cloud-libs-shared/libs/"
SA = "aa_cloud-superapp"
OWNER_DECL = json.load(open(os.path.join(R, "1_cicd/src/data/adb-owner.json")))
OWNER = OWNER_DECL["adb_owner"]; OWNER_PKG = OWNER_DECL["package"]
BROKER = "libs:shell-broker"
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad)); fails += 0 if c else 1
def rd(p):
    try: return open(os.path.join(R, p), encoding="utf-8").read()
    except OSError: return ""
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
SHIZ = "libs:shizuku-adb-debug-tools"
CLIENT = "libs:shell-client"
ADB_TOKENS = re.compile(r"\b(EmbeddedAdbChannel|AdbShellServer|HostShell\w*|WdKeeper\w*|AdbPairingService|RishBridge)\b")
HARD_CAP = 512 * 1024 * 1024
STDIN_CALLERS = {"com.diegonmarcos.cloudstore", "com.diegonmarcos.cloudaccount", "com.diegonmarcos.clouddrive"}

GATE = re.compile(r"// ADB-OWNER-SIDE BEGIN[^\n]*\n\s*if \(adbOwnerSide\) \{.*?\n\s*\}\s*\n\s*// ADB-OWNER-SIDE END", re.S)
def shipped(g): return GATE.sub("", g)
MARK = re.compile(r"//\s*ADB-OWNER-SIDE BEGIN.*?//\s*ADB-OWNER-SIDE END", re.S)   # any app's gated owner block (gradle)
def side(g, is_owner): return g if is_owner else MARK.sub("", g)
def gate_facts():
    W = "ac_cloud-c3-webserver"; G = f"{W}/src-tauri/gen/android"
    kts = rd(f"{G}/app/build.gradle.kts"); st = strip(rd(f"{G}/settings.gradle"))
    dormant = rd(f"{W}/src-tauri/gen/android/app/src/dormant/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt")
    sa = {"gradle": rd(f"{SA}/app/build.gradle"), "settings": strip(rd(f"{SA}/settings.gradle")), "client": rd(f"{SA}/app/src/client/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"),
          "owner": rd(f"{SA}/app/src/owner/java/com/diegonmarcos/superapp/adbside/AdbSide.kt"), "bj": rd(f"{SA}/build.json")}
    return {"kts": kts, "settings": st, "dormant": strip(dormant), "sa": sa}
def collect():
    apps = {}
    for d in sorted(os.listdir(R)):
        if not re.match(r"a[a-d]_", d) or not os.path.isfile(f"{R}/{d}/build.json"): continue
        gen = f"{d}/src-tauri/gen/android"   # #1682 the webserver's gradle root (native app, gradle dir kept from Tauri)
        if not os.path.isdir(f"{R}/{d}/app") and not os.path.isdir(f"{R}/{gen}/app") and d != SA: continue
        try: bj = json.load(open(f"{R}/{d}/build.json"))
        except ValueError: continue
        is_owner = (d == OWNER)   # #1682 P3 step 5/6: a gated owner block / adb_owner_only module counts only for the declared owner
        gradle = side(rd(f"{d}/app/build.gradle"), is_owner) + rd(f"{d}/settings.gradle") + (rd(f"{gen}/app/build.gradle.kts") if is_owner else shipped(rd(f"{gen}/app/build.gradle.kts")))
        mods = set(k for k, v in (bj.get("modules") or {}).items() if is_owner or not (isinstance(v, dict) and v.get("adb_owner_only")))
        aid = ((bj.get("android") or {}).get("application_id")) or ""
        if not aid:
            m = re.search(r'applicationId\s+"([^"]+)"', rd(f"{d}/app/build.gradle")); s = re.search(r'applicationIdSuffix\s+"([^"]*)"', rd(f"{d}/app/build.gradle"))
            aid = (m.group(1) + (s.group(1) if s else "")) if m else ""
        deps = lambda mod: (mod in mods and mod in (bj.get("modules") or {}) and d != "") and True
        shiz = (SHIZ in mods) or ("project(':%s')" % SHIZ in gradle) or ('project(":%s")' % SHIZ in gradle)
        client = (CLIENT in mods) or ("project(':%s')" % CLIENT in gradle) or ('project(":%s")' % CLIENT in gradle)
        broker = (BROKER in mods) or ("project(':%s')" % BROKER in gradle) or ('project(":%s")' % BROKER in gradle)
        toks = set()
        for base, dirs, files in os.walk(f"{R}/{d}/app/src/main") if os.path.isdir(f"{R}/{d}/app/src/main") else []:
            for f in files:
                if f.endswith((".kt", ".java")):
                    toks |= set(ADB_TOKENS.findall(re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', strip(open(os.path.join(base, f), encoding="utf-8", errors="replace").read()))))
        if not aid:
            m = re.search(r'applicationId\s*=\s*"([^"]+)"', rd(f"{gen}/app/build.gradle.kts")); aid = m.group(1) if m else ""
        apps[d] = {"aid": aid, "shiz": shiz, "client": client, "toks": toks, "broker": broker}
    return apps

RELAY = json.load(open(os.path.join(R, "ac_cloud-c3-webserver/data/mesh-shell-relay.json")))
def verdicts(apps, pending, allow, libs, shared):
    global fails
    gf = GF
    if OWNER != "ac_cloud-c3-webserver":
        gated = GATE.search(gf["kts"]) is not None and not re.search(r"project\([\"']:libs:(shizuku-adb-debug-tools|shell-broker)", shipped(gf["kts"]))
        sett = "adb-owner.json" in gf["settings"] and "adbOwnerDecl.adb_owner == 'ac_cloud-c3-webserver'" in gf["settings"] and "C3_ADB_OWNER_VERIFY" in gf["settings"] and "if (adbOwnerSide)" in gf["settings"]
        dorm = "const val active: Boolean = false" in gf["dormant"] and not re.search(r"superapp\.(adbdebug|shellbroker|system)", gf["dormant"])
        ok(gated and sett and dorm, "B11 the webserver's owner side is gated by adb-owner.json and ships dormant",
           "B11 the webserver's owner side is not gated (gradle block %s, settings gate %s, dormant stand-in %s)" % (gated, sett, dorm))
    if OWNER != SA:
        sa = gf["sa"]
        bjm = json.loads(sa["bj"])["modules"]
        flagged = all(isinstance(bjm.get(m), dict) and bjm[m].get("adb_owner_only") for m in ("libs:shizuku-adb-debug-tools", "libs:shell-broker", "libs:adb-shell-core"))
        gate = "adb-owner.json" in sa["settings"] and "adb_owner_only" in sa["settings"] and "gradle.ext.adbOwnerSide" in sa["settings"] and "C3_ADB_OWNER_VERIFY" in sa["settings"]
        gl = MARK.sub("", sa["gradle"])
        linked = re.search(r"project\([\"']:libs:(shizuku-adb-debug-tools|shell-broker|adb-shell-core)", gl) is not None
        client = "const val OWNER = false" in sa["client"] and not re.search(r"superapp\.(adbdebug|shellbroker)|AdbShellFragment|WirelessDebugKeeper|PrivilegedPlane", strip(sa["client"]))
        ok(flagged and gate and not linked and client, "B12 the SuperApp is a client: owner modules flagged and gated by adb-owner.json, no owner link outside the gate, a client AdbSide",
           "B12 the SuperApp is not a clean client (modules flagged %s, settings gate %s, owner link outside the gate %s, client AdbSide %s)" % (flagged, gate, linked, client))
    rc = RELAY["caller"]
    ok(allow.get("aliases", {}).get(RELAY["caller_package"]) == rc and rc in allow["callers"] and not allow["callers"][rc].get("stdin"),
       "B10 the mesh relay caller is the webserver's declared alias", "B10 the allowlist alias of %s is not the declared relay caller %r" % (RELAY["caller_package"], rc))
    others = {d: a for d, a in apps.items() if d != OWNER}
    bad = sorted(d for d, a in others.items() if a["shiz"])
    ok(not bad, "B1 no app but the ADB owner (%s) compiles libs:shizuku-adb-debug-tools" % OWNER, "B1 compiles libs:shizuku-adb-debug-tools: " + ", ".join(bad))
    own = apps.get(OWNER, {})
    ok(own.get("shiz") and own.get("broker"), "B9 the declared owner compiles the channel and the broker", "B9 the declared owner %s does not compile both libs" % OWNER)
    badb = sorted(d for d, a in others.items() if a.get("broker"))
    ok(not badb, "B9 no app but the owner exports the broker provider", "B9 compiles libs:shell-broker besides the owner: " + ", ".join(badb))
    ok(own.get("aid") == OWNER_PKG, "B9 the owner's application id is the declared package", "B9 owner application id %r is not the declared %r" % (own.get("aid"), OWNER_PKG))
    wire = libs["shell-client"]["src"]; m = re.search(r'BROKER_PKG\s*=\s*"([^"]+)"', wire)
    ok(m is not None and m.group(1) == OWNER_PKG and ('<package android:name="%s"' % OWNER_PKG) in libs["shell-client"]["manifest"],
       "B9 shell-client targets the declared owner package", "B9 ShellWire.BROKER_PKG / <queries> do not name the declared owner %r" % OWNER_PKG)
    ok(not pending, "B1 shell-migration.json pending is empty", "B1 pending is not empty (the migration is finished): " + ", ".join(sorted(pending)))
    badt = sorted(d for d, a in others.items() if a["toks"])
    ok(not badt, "B2 no app but the SuperApp starts an ADB connection", "B2 ADB connection classes in an app other than the SuperApp: " + ", ".join(badt))
    for name, t in shared.items():
        ok(not t["gradle_links"], f"B7 {name} does not link the shizuku lib", f"B7 {name} links libs:shizuku-adb-debug-tools outside a findProject guard")
        ok(not t["imports"], f"B7 {name} does not import the adbdebug package", f"B7 {name} imports com.diegonmarcos.superapp.adbdebug")
    for k, c in allow["callers"].items():
        for r in c.get("stdin", []):
            verb = re.match(r"\^\(?(pm|cmd package)", r["re"]) and re.search(r"install(-write)?\b", r["re"]) and not re.search(r"uninstall|\.\*", r["re"])
            ok(verb and 0 < r.get("maxBytes", 0) <= HARD_CAP, f"B8 {k} stdin rule {r['id']} is an install verb within the hard cap",
               f"B8 {k} stdin rule {r['id']} is not an install verb, or its maxBytes is missing / over {HARD_CAP}")
    stray = sorted(k for k, c in allow["callers"].items() if c.get("stdin") and k not in STDIN_CALLERS)
    ok(not stray, "B8 only the installers have stdin rules", "B8 a caller that is no installer may stream input: " + ", ".join(stray))
    callers = allow["callers"]; aliases = allow.get("aliases", {})
    clients = {d: a for d, a in others.items() if a["client"]}
    miss = sorted(d for d, a in clients.items() if a["aid"] not in callers and aliases.get(a["aid"]) not in callers)
    ok(not miss, "B3 every shell-client app has an allowlist entry (%d)" % len(clients), "B3 shell-client app without an allowlist entry: " + ", ".join(miss))
    known = {a["aid"] for a in apps.values() if a["aid"]} | set(aliases.values())
    # #1682 a logical identity (an entry that `extends` another, e.g. the owner's own "owner") is not a package: it must extend a known caller and carry no dot-package name
    logical = {c for c, v in callers.items() if v.get("extends") in callers and "." not in c}
    ghost = sorted(c for c in callers if c not in known and c not in logical)
    ok(not ghost, "B3 every allowlist caller is a known app id or alias", "B3 allowlist caller that is no app: " + ", ".join(ghost))
    for name, text in libs.items():
        body = strip(text["src"])
        ok(not re.search(r"START_STICKY|startForeground|BOOT_COMPLETED|AlarmManager|WorkManager|foregroundServiceType|ForegroundService", body + text["manifest"]),
           f"B4 {name}: no sticky service, boot hook, foreground service or loop", f"B4 {name}: sticky/boot/foreground/alarm/WorkManager found")
        ok("<receiver" not in text["manifest"], f"B4 {name}: no receiver", f"B4 {name}: declares a <receiver>")
    flat = [r["re"] for c in callers.values() for r in c.get("allow", [])]
    ok(not any(re.fullmatch(r"\^?\(?\.[*+]\)?\$?", x) for x in flat), "B5 no catch-all rule", "B5 a catch-all rule defeats default deny")
    m = libs["shell-broker"]["manifest"]
    comps = re.findall(r"<(provider|service|activity)\b(.*?)(?:/>|>)", m, flags=re.S)
    ok(comps and all("CONSTELLATION_DATA" in body for _, body in comps), "B5 every broker component is signature-guarded", "B5 a broker component lacks the CONSTELLATION_DATA permission")
    g = libs["shell-client"]["gradle"]
    ok(not re.search(r"project\(", g), "B6 libs:shell-client links no project", "B6 libs:shell-client links another project")

def shared_libs():
    out = {}
    for n in ("account", "appstore", "updater"):
        g = strip(rd(f"{LIB}{n}/build.gradle"))
        # the only allowed link: libs:updater's own `if (findProject(':libs:shizuku-adb-debug-tools') != null) { implementation project(...) }`
        g2 = re.sub(r"if \(findProject\(':libs:shizuku-adb-debug-tools'\) != null\) \{\s*implementation project\(':libs:shizuku-adb-debug-tools'\)\s*\}", "", g) if n == "updater" else g
        imps = False
        for base, _, files in os.walk(f"{R}/{LIB}{n}/src"):
            if base.replace("\\", "/").endswith("src/shell") or "/src/shell/" in base.replace("\\", "/") + "/": continue
            for f in files:
                if f.endswith((".kt", ".java")) and "superapp.adbdebug" in strip(open(os.path.join(base, f), encoding="utf-8", errors="replace").read()): imps = True
        out[n] = {"gradle_links": bool(re.search(r"project\(['\"]:libs:shizuku-adb-debug-tools['\"]\)", g2)), "imports": imps}
    return out

GF = gate_facts()
apps = collect()
pending = json.load(open(f"{R}/{SA}/data/shell-migration.json"))["pending"]
allow = json.load(open(f"{R}/{LIB}shell-broker/src/main/assets/shell-allowlist.json"))
def libsrc(n):
    s = ""
    for base, _, files in os.walk(f"{R}/{LIB}{n}/src/main"):
        for f in files:
            if f.endswith((".kt", ".java")): s += open(os.path.join(base, f), encoding="utf-8").read() + "\n"
    return {"src": s, "manifest": re.sub(r"<!--.*?-->", "", rd(f"{LIB}{n}/src/main/AndroidManifest.xml"), flags=re.S), "gradle": rd(f"{LIB}{n}/build.gradle")}
libs = {"shell-broker": libsrc("shell-broker"), "shell-client": libsrc("shell-client")}
ok(OWNER in apps and apps[OWNER]["shiz"], "the declared owner (%s) owns the channel" % OWNER, "the declared owner %s must compile libs:shizuku-adb-debug-tools" % OWNER)
shared = shared_libs()
verdicts(apps, pending, allow, libs, shared)
print("== planted violations must turn red")
import copy
def planted(label, mutate):
    global fails
    a, p, al, l, sh = copy.deepcopy(apps), copy.deepcopy(pending), copy.deepcopy(allow), copy.deepcopy(libs), copy.deepcopy(shared)
    mutate(a, p, al, l, sh)
    before = fails
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()): verdicts(a, p, al, l, sh)
    red = fails > before; fails = before
    print(("  PASS: " if red else "  FAIL: ") + label + (" turned a check red" if red else " went unnoticed")); fails += 0 if red else 1
planted("a new app compiling the shizuku lib", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": True, "client": False, "toks": set()}))
planted("a new app naming HostShell", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": False, "client": False, "toks": {"HostShell"}}))
planted("a shell-client app with no allowlist entry", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": False, "client": True, "toks": set()}))
planted("an allowlist caller that is no app", lambda a, p, al, l, sh: al["callers"].__setitem__("x.ghost", {"allow": []}))
planted("a catch-all rule", lambda a, p, al, l, sh: al["callers"]["com.diegonmarcos.clouddrive"]["allow"].append({"id": "x", "re": "^.*$"}))
planted("a sticky service in the broker", lambda a, p, al, l, sh: l["shell-broker"].__setitem__("src", l["shell-broker"]["src"] + "\nreturn START_STICKY\n"))
planted("a boot receiver in the broker", lambda a, p, al, l, sh: l["shell-broker"].__setitem__("manifest", l["shell-broker"]["manifest"] + '<receiver android:name="x"><action android:name="android.intent.action.BOOT_COMPLETED"/></receiver>'))
planted("an unguarded broker provider", lambda a, p, al, l, sh: l["shell-broker"].__setitem__("manifest", l["shell-broker"]["manifest"].replace("CONSTELLATION_DATA", "X", 1)))
planted("an app in the pending list (the list must stay empty)", lambda a, p, al, l, sh: p.__setitem__("ac_cloud-wallet", "x"))
planted("a migrated app re-linking the shizuku lib (Store)", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-store", {"aid": "com.diegonmarcos.cloudstore", "shiz": True, "client": True, "toks": set()}))
planted("a terminal naming HostShell again", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-termux", {"aid": "cld.termux", "shiz": False, "client": True, "toks": {"HostShell"}}))
planted("libs:appstore linking the shizuku lib", lambda a, p, al, l, sh: sh["appstore"].__setitem__("gradle_links", True))
planted("libs:account importing adbdebug", lambda a, p, al, l, sh: sh["account"].__setitem__("imports", True))
planted("a stdin rule for an arbitrary shell", lambda a, p, al, l, sh: al["callers"]["com.diegonmarcos.cloudstore"].setdefault("stdin", []).append({"id": "x", "re": "^sh -c .*$", "maxBytes": 10}))
planted("a stdin rule with no cap", lambda a, p, al, l, sh: al["callers"]["com.diegonmarcos.cloudstore"]["stdin"].append({"id": "x", "re": "^pm install -S [0-9]+$"}))
planted("a stdin rule above the hard limit", lambda a, p, al, l, sh: al["callers"]["com.diegonmarcos.cloudaccount"]["stdin"][0].__setitem__("maxBytes", HARD_CAP + 1))
planted("the mesh door allowed to stream", lambda a, p, al, l, sh: al["callers"]["mesh"].__setitem__("stdin", [{"id": "x", "re": "^pm install -S [0-9]+$", "maxBytes": 10}]))
planted("a terminal allowed to stream", lambda a, p, al, l, sh: al["callers"]["cld.termux"].__setitem__("stdin", [{"id": "x", "re": "^pm install -S [0-9]+$", "maxBytes": 10}]))
planted("a second app exporting the broker (two owners)", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-fake2", {"aid": "x.fake2", "shiz": False, "client": False, "toks": set(), "broker": True}))
planted("another app compiling the shizuku lib while not the owner", lambda a, p, al, l, sh: a.__setitem__("ac_cloud-fake3", {"aid": "x.fake3", "client": False, "toks": set(), "broker": False, "shiz": True}))
planted("the owner dropping the broker", lambda a, p, al, l, sh: a[OWNER].__setitem__("broker", False))
planted("shell-client targeting another package than the declaration", lambda a, p, al, l, sh: l["shell-client"].__setitem__("src", l["shell-client"]["src"].replace('BROKER_PKG = "%s"' % OWNER_PKG, 'BROKER_PKG = "x.other"')))
planted("the webserver's broker alias changed", lambda a, p, al, l, sh: al["aliases"].__setitem__(RELAY["caller_package"], "cld.termux"))
def _gf(k, v):
    global GF
    old = GF; GF = dict(GF); GF[k] = v(GF[k]); return old
if OWNER != SA:   # flipped: the SuperApp's gate is the one under test (B12)
    for label, f in (("the owner libs linked outside the SuperApp's gate", lambda sa: dict(sa, gradle=sa["gradle"] + "\nimplementation project(':libs:shell-broker')")),
                     ("the SuperApp's settings gate not reading adb-owner.json", lambda sa: dict(sa, settings=sa["settings"].replace("adb-owner.json", "x.json"))),
                     ("the SuperApp's client AdbSide claiming to be the owner", lambda sa: dict(sa, client=sa["client"].replace("OWNER = false", "OWNER = true")))):
        old = GF; GF = dict(GF, sa=f(GF["sa"])); planted(label, lambda a, p, al, l, sh: None); GF = old
else:
    for label, k, f in (("the owner libs linked outside the gate", "kts", lambda t: t + '\nimplementation(project(":libs:shell-broker"))'),
                        ("the settings gate not reading adb-owner.json", "settings", lambda t: t.replace("adb-owner.json", "x.json")),
                        ("the dormant stand-in active", "dormant", lambda t: t.replace("active: Boolean = false", "active: Boolean = true"))):
        old = _gf(k, f); planted(label, lambda a, p, al, l, sh: None); GF = old
planted("shell-client linking a project", lambda a, p, al, l, sh: l["shell-client"].__setitem__("gradle", "implementation project(':libs:core')"))
print("FAILS=%d" % fails); sys.exit(1 if fails else 0)
PY
