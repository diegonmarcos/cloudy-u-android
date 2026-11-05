# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-log-export-guard.py ───
#!/usr/bin/env python3
"""#1490 every fleet app exports its logs over IPC: the provider lives in libs:devtools (declared once, riding on libs:core;
libs/log-export is its standalone module and test home), and every app of the fleet manifest links it.

Usage: cloud-android-log-export-guard.py [ROOT]   (default: the repo root)      Exit 1 on any finding."""
import glob
import json
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
LIBS = "ab_cloud-libs-shared/libs"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"


def read(rel):
    try:
        with open(os.path.join(ROOT, rel), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def strip(text):
    return re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.S)


def const(text, name):
    m = re.search(r"const val " + name + r'\s*=\s*"([^"]*)"', text or "")
    return m.group(1) if m else None


def main():
    bad = []
    manifest = read(f"{LIBS}/devtools/src/main/AndroidManifest.xml") or ""
    m = re.search(r"<provider[^>]*LogExportProvider[^>]*/?>", manifest, re.S)
    if not m:
        bad.append(f"{LIBS}/devtools: the manifest does not declare LogExportProvider (the log-export IPC door)")
    else:
        tag = m.group(0)
        if 'android:exported="true"' not in tag:
            bad.append("devtools manifest: LogExportProvider is not exported (Cloud-Webserver could not call it)")
        if "CONSTELLATION_DATA" not in tag:
            bad.append("devtools manifest: LogExportProvider has no android:permission=CONSTELLATION_DATA (an unprotected log export)")
        if "${applicationId}.logexport" not in tag:
            bad.append("devtools manifest: LogExportProvider authority is not ${applicationId}.logexport")
    if "devtools/src/main/java/com/diegonmarcos/superapp/logexport" not in (read(f"{LIBS}/log-export/build.gradle") or ""):
        bad.append(f"{LIBS}/log-export/build.gradle: no longer compiles devtools' logexport package (its tests would test nothing)")
    contract = read(f"{LIBS}/devtools/src/main/java/com/diegonmarcos/superapp/logexport/LogExportContract.kt")
    provider = read(f"{LIBS}/devtools/src/main/java/com/diegonmarcos/superapp/logexport/LogExportProvider.kt") or ""
    if contract is None:
        bad.append(f"{LIBS}/devtools: logexport/LogExportContract.kt is missing")
    else:
        if const(contract, "PERMISSION") != const(read(f"{LIBS}/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetToken.kt"), "PERMISSION"):
            bad.append("LogExportContract.PERMISSION differs from FleetToken.PERMISSION (one membership permission)")
        if const(contract, "CRASH_DIR") != const(read(f"{LIBS}/crash/src/main/java/com/diegonmarcos/superapp/crash/CrashReports.kt"), "DIR"):
            bad.append("LogExportContract.CRASH_DIR differs from CrashReports.DIR (the crash ring would be read from the wrong folder)")
    if "LogExportContract.refusal(" not in provider or "Binder.getCallingUid" not in provider:
        bad.append("LogExportProvider.call does not re-check the caller (the framework does not permission-check call())")

    fleet = json.loads(read(FLEET) or "{}")
    seen = set()
    for a in fleet.get("apps", []):
        if a.get("kind") != "app":
            continue
        d = (a.get("repo_url") or "").rstrip("/").split("/")[-1]
        if not d or not os.path.isdir(os.path.join(ROOT, d)):
            bad.append(f"fleet manifest app {a.get('id')}: its directory {d!r} (repo_url) does not exist")
            continue
        seen.add(d)
        try:
            bj = json.loads(read(f"{d}/build.json") or "{}")
        except ValueError:
            bad.append(f"{d}/build.json: not valid JSON")
            continue
        # linked through core (-> devtools): the build.json module map, a fetched tree's materialized modules, or a hand-written settings file
        fetched = any(isinstance(v, dict) and str(v.get("dir", "")).rstrip("/").endswith("libs/devtools")
                      for v in ((bj.get("build") or {}).get("modules") or {}).values())
        linked = "libs:devtools" in (bj.get("modules") or {}) or fetched or any(
            re.search(r"""include.*['\"]:libs:devtools['\"]""", strip(open(sf, encoding="utf-8", errors="replace").read()))
            for sf in glob.glob(os.path.join(ROOT, d, "**/settings.gradle*"), recursive=True))
        if not linked:
            bad.append(f"{d}: does not link libs:devtools, so it has no log-export IPC (the fleet manifest lists it as an app)")
        for mf in glob.glob(os.path.join(ROOT, d, "**/AndroidManifest.xml"), recursive=True):
            if "/build/" in mf or "/z_archive/" in mf:
                continue
            t = open(mf, encoding="utf-8", errors="replace").read()
            if re.search(r"LogExportProvider[^>]*tools:node", t) or re.search(r"tools:node[^>]*LogExportProvider", t):
                bad.append(f"{os.path.relpath(mf, ROOT)}: removes LogExportProvider (an opt-out of the log export)")

    for b in bad:
        print("FAIL", b)
    print(f"log-export-guard: {len(seen)} app(s) checked, {len(bad)} finding(s)")
    return 1 if bad else 0


sys.exit(main())
