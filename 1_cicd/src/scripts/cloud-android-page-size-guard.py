#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-page-size-guard — every native library in a built  ║
# ║ APK must load on a 16 KB page-size device                        ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Android 15+ phones with 16 KB pages run a 4 KB-aligned library only in a
# compatibility mode and warn "This app isn't 16 KB-compatible. ELF alignment
# check failed." The phone named cloud-drive for libusb-lib.so and
# liberrno-lib.so (libaums' JNI helpers, linked with 4 KB LOAD segments) and
# printed "Unknown error" for the other five. Nothing in CI looked at the built
# APK's native libraries, so the defect reached the phone first.
#
#   cloud-android-page-size-guard.py <app-dir> <apk> [<apk> ...]
#
#     app-dir  the ship's WORK_DIR as written in the workflow (ac_cloud-drive,
#              ab_cloud-libs-shared/lib-apks); the data file is keyed by it.
#
# Two halves, both measured on the APK itself, never inferred from gradle:
#
#   ELF   every lib/<abi>/*.so for the ABIs in page-size-guard.json carries
#         only PT_LOAD segments with p_align >= 16384 (readelf -lW; llvm-objdump
#         -p as the fallback reader). A library with no PT_LOAD fails too.
#   ZIP   every such entry is STORED (not deflated) at a data offset that is a
#         multiple of 16384, so the platform can mmap it straight from the APK,
#         and `zipalign -c -P 16 -v 4` agrees. Skipped ONLY for an app the data
#         file declares legacy_packaging (it extracts its libs on purpose, e.g.
#         to exec binaries out of nativeLibraryDir); the ELF half still applies.
#
# A deflated library in an app NOT declared legacy fails: that is the
# useLegacyPackaging / extractNativeLibs=true drift. Exemptions are by app and
# entry name with a written reason in 1_cicd/src/data/page-size-guard.json,
# never a silent skip. zipalign is taken from $ANDROID_HOME/build-tools (newest
# first) or PATH; on CI (CI=true) a missing zipalign is a failure, because a
# check that silently did not run is how this reached the phone.
#
# Proof that it can fail: 1_cicd/src/scripts/test/page-size-guard.test.sh,
# run by the same managed Ship step right before this guard.
import glob
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def find_data():
    # Runs from 1_cicd/dist/scripts on CI and from 1_cicd/src/scripts in tests; the data lives in src.
    env = os.environ.get("PAGE_SIZE_GUARD_DATA")
    if env:
        return env
    for rel in ("../data/page-size-guard.json", "../../src/data/page-size-guard.json"):
        p = os.path.normpath(os.path.join(HERE, rel))
        if os.path.isfile(p):
            return p
    sys.exit("page-size-guard: 1_cicd/src/data/page-size-guard.json not found")


def load_aligns(path):
    """Every PT_LOAD p_align of the ELF at path, via readelf (llvm-objdump fallback)."""
    if shutil.which("readelf"):
        out = subprocess.run(["readelf", "-lW", path], capture_output=True, text=True)
        if out.returncode != 0:
            raise ValueError("readelf: " + (out.stderr.strip() or "not an ELF"))
        return [int(l.split()[-1], 16) for l in out.stdout.splitlines() if l.split()[:1] == ["LOAD"]]
    if shutil.which("llvm-objdump"):
        out = subprocess.run(["llvm-objdump", "-p", path], capture_output=True, text=True)
        if out.returncode != 0:
            raise ValueError("llvm-objdump: " + (out.stderr.strip() or "not an ELF"))
        return [2 ** int(m) for m in re.findall(r"^\s*LOAD\s.*align 2\*\*(\d+)", out.stdout, re.M)]
    raise SystemExit("page-size-guard: neither readelf nor llvm-objdump is installed")


def data_offset(fh, info):
    fh.seek(info.header_offset)
    h = fh.read(30)
    if h[:4] != b"PK\x03\x04":
        raise ValueError("bad local header")
    n, e = struct.unpack("<HH", h[26:30])
    return info.header_offset + 30 + n + e


def find_zipalign():
    roots = [os.environ.get(k) for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT")]
    cands = []
    for r in filter(None, roots):
        cands += glob.glob(os.path.join(r, "build-tools", "*", "zipalign"))

    def ver(p):
        return [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", os.path.basename(os.path.dirname(p)))]
    cands.sort(key=ver, reverse=True)
    return cands[0] if cands else shutil.which("zipalign")


def main(argv):
    if len(argv) < 3:
        sys.exit("usage: cloud-android-page-size-guard.py <app-dir> <apk> [<apk> ...]")
    app = os.path.normpath(argv[1])
    apks = argv[2:]
    data = json.load(open(find_data()))
    page = int(data["min_align"])
    abis = data["abis"]
    legacy = app in data.get("legacy_packaging", {})
    exempt = data.get("exempt_libs", {}).get(app, {})
    lib_re = re.compile(r"^lib/(%s)/[^/]+\.so$" % "|".join(map(re.escape, abis)))

    zipalign = find_zipalign()
    on_ci = os.environ.get("CI") == "true"
    print("page-size-guard: app=%s min_align=%d abis=%s legacy_packaging=%s zipalign=%s"
          % (app, page, ",".join(abis), legacy, zipalign or "none"))
    bad = 0
    for apk in apks:
        if not os.path.isfile(apk):
            print("FAIL %s: no such APK" % apk); bad += 1; continue
        print("== %s" % apk)
        tmp = tempfile.mkdtemp(prefix="page-size-")
        try:
            with zipfile.ZipFile(apk) as z, open(apk, "rb") as fh:
                libs = [i for i in z.infolist() if lib_re.match(i.filename)]
                if not libs:
                    print("  (no native libraries for %s)" % ",".join(abis))
                for info in libs:
                    name = info.filename
                    why = []
                    p = os.path.join(tmp, "x.so")
                    with open(p, "wb") as o:
                        o.write(z.read(name))
                    al = []
                    try:
                        al = load_aligns(p)
                    except ValueError as e:
                        why.append(str(e))
                    if not al and not why:
                        why.append("no LOAD segment")
                    low = [a for a in al if a < page]
                    if low:
                        why.append("LOAD segment aligned 2**%d < 2**%d" % (min(low).bit_length() - 1, page.bit_length() - 1))
                    stored = info.compress_type == zipfile.ZIP_STORED
                    off = data_offset(fh, info)
                    zip_note = "%s offset%%%d=%d" % ("stored" if stored else "DEFLATED", page, off % page)
                    if not legacy:
                        if not stored:
                            why.append("compressed in the APK (useLegacyPackaging/extractNativeLibs): the platform cannot map it")
                        elif off % page:
                            why.append("zip data offset %d is not 16 KB-aligned" % off)
                    aligns = " ".join("2**%d" % (a.bit_length() - 1) for a in al) or "-"
                    if why and name in exempt:
                        print("  EXEMPT %-48s LOAD %s  %s  (%s)" % (name, aligns, zip_note, exempt[name]))
                    elif why:
                        print("  FAIL   %-48s LOAD %s  %s  -> %s" % (name, aligns, zip_note, "; ".join(why)))
                        bad += 1
                    else:
                        print("  PASS   %-48s LOAD %s  %s" % (name, aligns, zip_note))
                if not legacy and libs:
                    if zipalign:
                        r = subprocess.run([zipalign, "-c", "-P", "16", "-v", "4", apk], capture_output=True, text=True)
                        failed = [l.strip() for l in r.stdout.splitlines() if "BAD" in l]
                        if r.returncode != 0:
                            print("  FAIL   zipalign -c -P 16 -v 4: %s" % ("; ".join(failed[:5]) or r.stdout.strip().splitlines()[-1:]))
                            bad += 1
                        else:
                            print("  PASS   zipalign -c -P 16 -v 4")
                    elif on_ci:
                        print("  FAIL   zipalign not found (ANDROID_HOME/build-tools): the zip check did not run")
                        bad += 1
                    else:
                        print("  SKIP   zipalign not installed here (offsets checked above)")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    if bad:
        print("page-size-guard: %d problem(s). Fix the library (16 KB-aligned build) or the packaging; "
              "an exemption needs a reason in 1_cicd/src/data/page-size-guard.json." % bad)
        return 1
    print("page-size-guard: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
