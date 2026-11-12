#!/usr/bin/env python3
"""
cloud-android-apk-intent-guard -- #1675: no store or launcher code installs an APK through an INTENT.

WHY. Cloud Store's per-app Update with a cached APK handed the file to Android with ACTION_VIEW + the APK mime type; on a
phone with a file handler that opens the "Open with..." chooser instead of installing, and when it does reach the system
installer that installer (not Cloud Store) becomes the app's installer of record, so every later update prompts. Every
install and update must go through the store queue (StoreStages / BatchInstall -> libs:updater UpdateInstaller, ONE
PackageInstaller session).

SCOPE: Kotlin/Java main sources of the store and install code: ab_cloud-libs-shared/libs/appstore, libs/updater,
ac_cloud-store, aa_cloud-superapp (comments stripped; test dirs skipped).

RULES
  A1 no Intent.ACTION_INSTALL_PACKAGE
  A2 no setDataAndType / setType / Intent(...) carrying the APK mime type "application/vnd.android.package-archive" (as a
     literal, or through a val that holds it)
  A3 no file that makes a FileProvider URI for an .apk also starts an activity with ACTION_VIEW
  (a MediaStore MIME_TYPE put, e.g. saving a copy to Downloads, is not an intent and is allowed)

Usage: cloud-android-apk-intent-guard.py ROOT      EXIT 0 green, 1 a violation, 2 usage
"""
import os
import re
import sys

SCOPE = ["ab_cloud-libs-shared/libs/appstore", "ab_cloud-libs-shared/libs/updater", "ac_cloud-store", "aa_cloud-superapp"]
SKIP = {"build", "test", "androidTest", ".gradle", "node_modules"}
MIME = "application/vnd.android.package-archive"


def strip(src):
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return re.sub(r"(?<![:\"])//[^\n]*", "", src)


def check(rel, src):
    bad = []
    s = strip(src)
    if "ACTION_INSTALL_PACKAGE" in s:
        bad.append(f"A1 {rel}: Intent.ACTION_INSTALL_PACKAGE")
    holders = re.findall(r"\b(?:val|var)\s+(\w+)\s*(?::\s*String\s*)?=\s*\"" + re.escape(MIME) + "\"", s)
    names = [re.escape('"' + MIME + '"')] + [r"\b" + re.escape(h) + r"\b" for h in holders]
    for n in names:
        if re.search(r"(?:setDataAndType|setType|setDataAndTypeAndNormalize|Intent)\s*\([^)]*" + n, s):
            bad.append(f"A2 {rel}: an intent carries the APK mime type")
            break
    if re.search(r"FileProvider\.getUriForFile", s) and re.search(r"\.apk\b", s) and re.search(r"ACTION_VIEW", s) \
            and re.search(r"startActivity\s*\(", s):
        bad.append(f"A3 {rel}: a FileProvider URI for an .apk and an ACTION_VIEW activity start in one file")
    return bad


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = os.path.abspath(argv[1])
    bad, n = [], 0
    for sc in SCOPE:
        for d, dirs, files in os.walk(os.path.join(root, sc)):
            dirs[:] = [x for x in dirs if x not in SKIP]
            for f in files:
                if f.endswith((".kt", ".java")):
                    p = os.path.join(d, f)
                    n += 1
                    bad += check(os.path.relpath(p, root), open(p, encoding="utf-8", errors="replace").read())
    for b in bad:
        print("FAIL " + b)
    if not bad:
        print(f"apk-intent guard: {n} store/launcher sources, no APK install through an intent")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
