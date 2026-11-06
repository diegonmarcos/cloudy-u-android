# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-safe-prefs-guard.py ───
#!/usr/bin/env python3
"""#1106 no direct EncryptedSharedPreferences / MasterKey use outside libs:crash SafeEncryptedPrefs.

The raw call throws (AEADBadTagException, Keystore VERIFICATION_FAILED) when the prefs file survived but the
Keystore master key is another one, and Cloud Drive crashed at start-up on it. SafeEncryptedPrefs never throws.
Usage: cloud-android-safe-prefs-guard.py [ROOT]   (default: the repo root)
Policy: 1_cicd/src/data/safe-encrypted-prefs-guard.json. Exit 1 on any finding."""
import json, os, re, sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SKIP = {".git", "build", "node_modules", ".gradle", "dist"}
DIRECT = re.compile(r"EncryptedSharedPreferences\.create\(|MasterKeys\.getOrCreate\(|MasterKey\.Builder\(")


def main():
    pol = os.path.join(ROOT, "1_cicd/src/data/safe-encrypted-prefs-guard.json")
    allow = set(json.load(open(pol)).get("allow", {})) if os.path.isfile(pol) else set()
    bad, seen = [], 0
    for d, dirs, files in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in files:
            if not f.endswith((".kt", ".java")):
                continue
            p = os.path.join(d, f)
            rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
            if "/src/test/" in rel or "/src/androidTest/" in rel or rel in allow:
                continue
            try:
                s = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if "androidx.security.crypto" not in s:
                continue
            seen += 1
            for i, line in enumerate(s.splitlines(), 1):
                if DIRECT.search(line) and not line.lstrip().startswith(("//", "*", "/*")):
                    bad.append(f"{rel}:{i}: direct {DIRECT.search(line).group(0)} -- open it with SafeEncryptedPrefs.prefs(context, name) (libs:crash), or list the file in safe-encrypted-prefs-guard.json with a reason")
    # #1474 runtime classpath: security-crypto declared compileOnly was left out of Cloud News' APK and crashed it at
    # start-up (NoClassDefFoundError MasterKey$Builder). A module may keep it compileOnly only if it links libs:crash,
    # and libs:crash itself must package it (implementation/api), so every app linking any such module carries it.
    dep = re.compile(r"""^\s*(compileOnly|implementation|api)\b.*security-crypto""", re.M)
    crash = os.path.join(ROOT, "ab_cloud-libs-shared/libs/crash/build.gradle")
    crash_ok = os.path.isfile(crash) and any(m.group(1) != "compileOnly" for m in dep.finditer(open(crash).read()))
    for d, dirs, files in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in files:
            if f not in ("build.gradle", "build.gradle.kts"):
                continue
            p = os.path.join(d, f)
            rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
            s = open(p, encoding="utf-8", errors="replace").read()
            kinds = {m.group(1) for m in dep.finditer(s)}
            if "compileOnly" in kinds and not kinds - {"compileOnly"}:
                if not re.search(r"project\(['\"]:libs:crash['\"]\)", s) and not rel.endswith("libs/crash/build.gradle"):
                    bad.append(f"{rel}: security-crypto is compileOnly and libs:crash is not linked, so it is not on the app's runtime classpath (#1474)")
                elif not crash_ok:
                    bad.append(f"{rel}: security-crypto is compileOnly here and libs/crash/build.gradle does not package it (implementation/api) (#1474)")
    for r in sorted(allow):
        if not os.path.isfile(os.path.join(ROOT, r)):
            bad.append(f"safe-encrypted-prefs-guard.json: allow names {r}, which does not exist")
    for b in bad:
        print("FAIL", b)
    print(f"safe-prefs-guard: {seen} file(s) using androidx.security.crypto checked, {len(bad)} finding(s)")
    return 1 if bad else 0


sys.exit(main())
