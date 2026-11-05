#!/usr/bin/env bash
# #1510 The shell broker is the ONE owner of the privileged shell. Static: no build, no device.
#   B1  no app other than the SuperApp compiles libs:shizuku-adb-debug-tools, except the apps still listed in data/shell-migration.json
#       (a ratchet: the list never grows, and a listed app that no longer compiles it must be removed from it)
#   B2  no app other than the SuperApp (and the listed ones) names an ADB connection class: EmbeddedAdbChannel, AdbShellServer, HostShell, WdKeeper, AdbPairingService
#   B3  every app that links libs:shell-client has an allowlist entry under its application id; every allowlist caller is a known app id or alias
#   B4  the broker and the client have no sticky service, no boot receiver, no foreground service, no alarm or WorkManager loop
#   B5  the allowlist is default-deny: no caller has a catch-all rule, and the broker lib declares no unguarded component
#   B6  libs:shell-client links nothing (no libs:core, no shizuku lib): linking it adds no fan-out edge
# Then each violation is planted in the collected facts and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
python3 - "$ROOT" <<'PY'
import json, os, re, sys
R = sys.argv[1]; fails = 0
LIB = "ab_cloud-libs-shared/libs/"
SA = "aa_cloud-superapp"
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad)); fails += 0 if c else 1
def rd(p):
    try: return open(os.path.join(R, p), encoding="utf-8").read()
    except OSError: return ""
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
SHIZ = "libs:shizuku-adb-debug-tools"
CLIENT = "libs:shell-client"
ADB_TOKENS = re.compile(r"\b(EmbeddedAdbChannel|AdbShellServer|HostShell\w*|WdKeeper\w*|AdbPairingService)\b")

def collect():
    apps = {}
    for d in sorted(os.listdir(R)):
        if not re.match(r"a[a-d]_", d) or not os.path.isfile(f"{R}/{d}/build.json"): continue
        if not os.path.isdir(f"{R}/{d}/app") and d != SA: continue
        try: bj = json.load(open(f"{R}/{d}/build.json"))
        except ValueError: continue
        gradle = rd(f"{d}/app/build.gradle") + rd(f"{d}/settings.gradle")
        mods = set((bj.get("modules") or {}).keys())
        aid = ((bj.get("android") or {}).get("application_id")) or ""
        if not aid:
            m = re.search(r'applicationId\s+"([^"]+)"', rd(f"{d}/app/build.gradle")); s = re.search(r'applicationIdSuffix\s+"([^"]*)"', rd(f"{d}/app/build.gradle"))
            aid = (m.group(1) + (s.group(1) if s else "")) if m else ""
        deps = lambda mod: (mod in mods and mod in (bj.get("modules") or {}) and d != "") and True
        shiz = (SHIZ in mods) or ("project(':%s')" % SHIZ in gradle) or ('project(":%s")' % SHIZ in gradle)
        client = (CLIENT in mods) or ("project(':%s')" % CLIENT in gradle)
        toks = set()
        for base, dirs, files in os.walk(f"{R}/{d}/app/src/main") if os.path.isdir(f"{R}/{d}/app/src/main") else []:
            for f in files:
                if f.endswith((".kt", ".java")):
                    toks |= set(ADB_TOKENS.findall(strip(open(os.path.join(base, f), encoding="utf-8", errors="replace").read())))
        apps[d] = {"aid": aid, "shiz": shiz, "client": client, "toks": toks}
    return apps

def verdicts(apps, pending, allow, libs):
    global fails
    others = {d: a for d, a in apps.items() if d != SA}
    bad = sorted(d for d, a in others.items() if a["shiz"] and d not in pending)
    ok(not bad, "B1 no unlisted app compiles libs:shizuku-adb-debug-tools", "B1 compiles libs:shizuku-adb-debug-tools without being pending: " + ", ".join(bad))
    stale = sorted(d for d in pending if not (d in apps and apps[d]["shiz"]))
    ok(not stale, "B1 every pending app still compiles it", "B1 pending but already migrated (remove from data/shell-migration.json): " + ", ".join(stale))
    badt = sorted(d for d, a in others.items() if a["toks"] and d not in pending)
    ok(not badt, "B2 no unlisted app starts an ADB connection", "B2 ADB connection classes in an unlisted app: " + ", ".join(badt))
    callers = allow["callers"]; aliases = allow.get("aliases", {})
    clients = {d: a for d, a in others.items() if a["client"]}
    miss = sorted(d for d, a in clients.items() if a["aid"] not in callers and aliases.get(a["aid"]) not in callers)
    ok(not miss, "B3 every shell-client app has an allowlist entry (%d)" % len(clients), "B3 shell-client app without an allowlist entry: " + ", ".join(miss))
    known = {a["aid"] for a in apps.values() if a["aid"]} | set(aliases.values())
    ghost = sorted(c for c in callers if c not in known)
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
ok(SA in apps and apps[SA]["shiz"], "SuperApp owns the channel", "SuperApp must compile libs:shizuku-adb-debug-tools")
verdicts(apps, pending, allow, libs)
print("== planted violations must turn red")
import copy
def planted(label, mutate):
    global fails
    a, p, al, l = copy.deepcopy(apps), copy.deepcopy(pending), copy.deepcopy(allow), copy.deepcopy(libs)
    mutate(a, p, al, l)
    before = fails
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()): verdicts(a, p, al, l)
    red = fails > before; fails = before
    print(("  PASS: " if red else "  FAIL: ") + label + (" turned a check red" if red else " went unnoticed")); fails += 0 if red else 1
planted("a new app compiling the shizuku lib", lambda a, p, al, l: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": True, "client": False, "toks": set()}))
planted("a new app naming HostShell", lambda a, p, al, l: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": False, "client": False, "toks": {"HostShell"}}))
planted("a shell-client app with no allowlist entry", lambda a, p, al, l: a.__setitem__("ac_cloud-fake", {"aid": "x.fake", "shiz": False, "client": True, "toks": set()}))
planted("an allowlist caller that is no app", lambda a, p, al, l: al["callers"].__setitem__("x.ghost", {"allow": []}))
planted("a catch-all rule", lambda a, p, al, l: al["callers"]["com.diegonmarcos.clouddrive"]["allow"].append({"id": "x", "re": "^.*$"}))
planted("a sticky service in the broker", lambda a, p, al, l: l["shell-broker"].__setitem__("src", l["shell-broker"]["src"] + "\nreturn START_STICKY\n"))
planted("a boot receiver in the broker", lambda a, p, al, l: l["shell-broker"].__setitem__("manifest", l["shell-broker"]["manifest"] + '<receiver android:name="x"><action android:name="android.intent.action.BOOT_COMPLETED"/></receiver>'))
planted("an unguarded broker provider", lambda a, p, al, l: l["shell-broker"].__setitem__("manifest", l["shell-broker"]["manifest"].replace("CONSTELLATION_DATA", "X", 1)))
planted("a migrated app left in pending", lambda a, p, al, l: p.__setitem__("ac_cloud-wallet", "x"))
planted("shell-client linking a project", lambda a, p, al, l: l["shell-client"].__setitem__("gradle", "implementation project(':libs:core')"))
print("FAILS=%d" % fails); sys.exit(1 if fails else 0)
PY
