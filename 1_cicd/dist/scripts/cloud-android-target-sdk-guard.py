# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-target-sdk-guard.py ───
#!/usr/bin/env python3
"""
cloud-android-target-sdk-guard -- #1675: no fleet app may target an SDK below the floor Android demands for a SILENT update
on the newest Android the fleet runs on.

WHY. Cloud Store updates fleet apps with PackageInstaller sessions that ask for USER_ACTION_NOT_REQUIRED. Android honours it
only when the updated app targets a recent enough SDK (Android 12 -> 29+, 13 -> 30+, 14 -> 31+, 15 -> 33+, 16 -> 34+,
17 -> 35+); below that it shows the confirmation dialog on every update. The owner's phone runs Android 17, so the floor is 35.
A raise is easy to forget for one app, and the symptom (a dialog on that app's updates only) never fails a build.

FLEET MEMBERSHIP is the same data rule data/regen.sh uses: a directory one or two levels deep whose build.json declares
android.application_id + release.ghcr, or lib_apks, or forks.

RESOLUTION (every value found counts; the LOWEST decides):
  build.json android.target_sdk; gradle.properties targetSdkVersion=N; any libs.versions.toml key containing "targetSdk"
  (case-insensitive) = "N"; a literal `targetSdk N` / `targetSdk = N` / `targetSdkVersion N` / `targetSdkVersion = N` in a
  Gradle file of the app (a library default such as safeExtGet('targetSdkVersion', 34) or a `cond ? a : N` fallback is not
  the app's own value and is skipped); `TARGET_SDK = N` in buildSrc / build-logic / a Versions.kt (a Gradle convention plugin).

RULES (policy 1_cicd/src/data/target-sdk-guard.json)
  T1 every fleet app resolves at least one targetSdk value (an unresolvable app is red: the guard cannot vouch for it)
  T2 the lowest value is >= floor, unless the app is in `exempt` with a reason
  T3 every exemption names a fleet app that is still below the floor (a stale exemption is red), and there are at most
     exempt_max of them (the list may only shrink)
  T5 an app listed in `unresolved` (its Gradle tree is not in git: fetched from upstream at build time) must still be
     unresolvable here, with a reason; it is reported, not vouched for
  T4 floor == the rule for newest_device_sdk (the table above), so the two numbers cannot drift

Usage: cloud-android-target-sdk-guard.py ROOT [--matrix]     EXIT 0 green, 1 a violation, 2 usage
"""
import glob
import json
import os
import re
import sys

POLICY = "1_cicd/src/data/target-sdk-guard.json"
SKIP = {".git", "node_modules", "build", ".gradle", "test", "androidTest", "z_archive", "intermediates", "libraries", "dist"}
RULE = {31: 29, 32: 29, 33: 30, 34: 31, 35: 33, 36: 34, 37: 35}

GRADLE_LIT = re.compile(r"\btargetSdk(?:Version)?\s*(?:=\s*)?(\d+)\b")
TOML = re.compile(r'(?im)^\s*[\w.-]*targetsdk[\w.-]*\s*=\s*"(\d+)"')
PROPS = re.compile(r"(?m)^\s*targetSdkVersion\s*=\s*(\d+)")
CONST = re.compile(r"\bTARGET_SDK\s*=\s*(\d+)")


def rule_for(sdk):
    if sdk < 31:
        return 0
    return RULE.get(sdk, 35)


def read(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def strip_comments(src):
    out = []
    for line in src.splitlines():
        t = line.lstrip()
        if t.startswith(("//", "/*", "*", "#")):
            continue
        out.append(re.sub(r"(?<![:\"'])\s//\s.*$", "", line))
    return "\n".join(out)


def walk(d):
    for cur, dirs, files in os.walk(d):
        dirs[:] = [x for x in dirs if x not in SKIP and not x.startswith("z-archived")]
        for f in files:
            yield os.path.join(cur, f)


def is_member(bj):
    a = bj.get("android") or {}
    rel = bj.get("release") or {}
    return bj.get("lib_apks") is not None or bj.get("forks") is not None or (
        a.get("application_id") is not None and rel.get("ghcr") is not None)


def fleet_dirs(root):
    out = []
    for bjp in sorted(glob.glob(os.path.join(root, "*/build.json")) + glob.glob(os.path.join(root, "*/*/build.json"))):
        try:
            bj = json.load(open(bjp, encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(bj, dict) and is_member(bj):
            out.append((os.path.relpath(os.path.dirname(bjp), root), bj))
    return out


def values(root, rel, bj):
    """[(value, where)] for one app dir."""
    found = []
    t = (bj.get("android") or {}).get("target_sdk")
    if isinstance(t, int):
        found.append((t, f"{rel}/build.json android.target_sdk"))
    d = os.path.join(root, rel)
    for p in walk(d):
        b = os.path.basename(p)
        r = os.path.relpath(p, root)
        if b.endswith((".gradle", ".gradle.kts")):
            for line in strip_comments(read(p)).splitlines():
                if "safeExtGet" in line or "?" in line or "hasProperty" in line:
                    continue
                for m in GRADLE_LIT.finditer(line):
                    found.append((int(m.group(1)), r))
        elif b == "gradle.properties":
            found += [(int(v), r) for v in PROPS.findall(read(p))]
        elif b.endswith(".toml"):
            found += [(int(v), r) for v in TOML.findall(read(p))]
        elif b.endswith((".kt", ".kts")) and ("buildSrc" in r or "build-logic" in r or b == "Versions.kt"):
            found += [(int(v), r) for v in CONST.findall(strip_comments(read(p)))]
    return found


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__)
        return 2
    root = os.path.abspath(args[0])
    pol = json.load(open(os.path.join(root, POLICY), encoding="utf-8"))
    floor, newest = int(pol["floor"]), int(pol["newest_device_sdk"])
    exempt = pol.get("exempt", {})
    exempt_max = int(pol.get("exempt_max", len(exempt)))
    unresolved = pol.get("unresolved", {})
    bad = []
    if floor != rule_for(newest):
        bad.append(f"T4 floor {floor} != the rule for API {newest} ({rule_for(newest)})")
    if len(exempt) > exempt_max:
        bad.append(f"T3 {len(exempt)} exemptions > exempt_max {exempt_max} (the list may only shrink)")
    seen = {}
    for rel, bj in fleet_dirs(root):
        key = os.path.basename(rel)
        v = values(root, rel, bj)
        if not v:
            if str(unresolved.get(key, "")).strip():
                print(f"NOTE T5 {rel}: targetSdk not resolvable from git ({unresolved[key]})")
                seen[key] = None
            else:
                bad.append(f"T1 {rel}: no targetSdk could be resolved (declare build.json android.target_sdk)")
            continue
        if key in unresolved:
            bad.append(f"T5 {rel}: listed in `unresolved` but resolves to {min(v)[0]} now (drop the entry)")
        low, where = min(v)
        seen[key] = low
        if "--matrix" in argv:
            print(f"{rel:45} {low:3}  ({where})")
        if low < floor:
            why = exempt.get(key)
            if not why or not str(why).strip():
                bad.append(f"T2 {rel}: targetSdk {low} ({where}) < {floor}, the floor Android {newest - 20} (API {newest}) "
                           f"demands for a silent update; raise it (and handle the behaviour changes) or exempt it with a reason")
    for key in exempt:
        if key not in seen:
            bad.append(f"T3 exemption {key}: not a fleet app (drop it)")
        elif seen[key] is None or seen[key] >= floor:
            bad.append(f"T3 exemption {key}: targetSdk is {seen[key]} >= {floor} now (drop the exemption)")
    for b in bad:
        print("FAIL " + b)
    if not bad:
        print(f"target-sdk guard: {len(seen)} fleet apps, all >= {floor} or exempt with a reason ({len(exempt)})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
