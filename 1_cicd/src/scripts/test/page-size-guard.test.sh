#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ page-size-guard.test — prove the 16 KB guard FAILS on each way a ║
# ║ native library breaks a 16 KB phone, and passes a clean APK      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The phone reported cloud-drive's libusb-lib.so / liberrno-lib.so ("LOAD
# segment not aligned") before any CI step had looked. A guard only ever seen
# green is indistinguishable from `exit 0`, so the managed ship step runs this
# file FIRST: it builds APK-shaped zips around tiny real ELF64 objects (one
# PT_LOAD, chosen p_align) and requires RED for every defect and GREEN for the
# clean shape and for the two declared escape hatches (legacy_packaging,
# exempt_libs) — each hatch also proven NOT to cover anything else.
#
# Needs bash, python3 and readelf (or llvm-objdump): what the guard needs.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-page-size-guard.py"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# mk <out.apk> <entry>:<align>:<stored|deflated|unaligned|notelf|noload> ...
mk() {
  python3 -I - "$@" <<'PY'
import struct, sys, zipfile
def elf(align, load=True):
    ph = struct.pack("<IIQQQQQQ", 1 if load else 6, 5, 0, 0, 0, 4096, 4096, align)
    eh = (b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
          + struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 64, 0, 0, 64, 56, 1, 64, 0, 0))
    return (eh + ph).ljust(4096, b"\0")
out, specs = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(out, "w") as z:
    for s in specs:
        name, align, kind = s.split(":")
        body = b"not an elf" * 50 if kind == "notelf" else elf(int(align), kind != "noload")
        zi = zipfile.ZipInfo(name, (1981, 1, 1, 1, 1, 0))
        if kind == "deflated":
            zi.compress_type = zipfile.ZIP_DEFLATED
        else:
            pos = z.fp.tell() + 30 + len(name.encode()) + 4
            pad = (-pos) % 16384 + (1 if kind == "unaligned" else 0)
            zi.extra = struct.pack("<HH", 0xD935, pad) + b"\0" * pad
        z.writestr(zi, body)
PY
}

# expect <0|1> <label> <app> <apk> [grep-pattern]
expect() {
  local want="$1" label="$2" app="$3" apk="$4" pat="${5:-}" outp rc
  outp="$(python3 -I "$GUARD" "$app" "$apk" 2>&1)"; rc=$?
  [ "$rc" -ne 0 ] && rc=1
  if [ "$rc" != "$want" ]; then fail "$label (exit $rc, wanted $want)"; printf '%s\n' "$outp" | sed 's/^/         /'; return; fi
  if [ -n "$pat" ] && ! grep -q -- "$pat" <<<"$outp"; then fail "$label (output lacks '$pat')"; printf '%s\n' "$outp" | sed 's/^/         /'; return; fi
  ok "$label"
}

A=lib/arm64-v8a; X=lib/x86_64
# A policy of our own, so the test does not move when the fleet's data does.
cat > "$T/policy.json" <<'J'
{"min_align": 16384, "abis": ["arm64-v8a", "x86_64"],
 "legacy_packaging": {"app/legacy": "test: extracts on purpose"},
 "exempt_libs": {"app/exempt": {"lib/arm64-v8a/libold.so": "test: known 4 KB lib"}}}
J
export PAGE_SIZE_GUARD_DATA="$T/policy.json"
unset CI

mk "$T/clean.apk" "$A/libpdfium.so:16384:stored" "$X/libpdfium.so:65536:stored" "lib/armeabi-v7a/libv7.so:4096:stored"
expect 0 "clean: 16 KB/64 KB LOAD, stored, 16 KB offsets (32-bit ABIs are out of scope)" app/drive "$T/clean.apk" "PASS   $A/libpdfium.so"

mk "$T/m1.apk" "$A/libpdfium.so:16384:stored" "$A/libusb-lib.so:4096:stored"
expect 1 "MUT the phone's case: libusb-lib.so with a 4 KB LOAD segment" app/drive "$T/m1.apk" "FAIL   $A/libusb-lib.so.*2\*\*12 < 2\*\*14"
mk "$T/m2.apk" "$X/liberrno-lib.so:4096:stored"
expect 1 "MUT a 4 KB lib in the x86_64 APK" app/drive "$T/m2.apk" "liberrno-lib.so"
mk "$T/m3.apk" "$A/libpdfium.so:16384:deflated"
expect 1 "MUT a 16 KB lib stored COMPRESSED (useLegacyPackaging drift, the 'Unknown error' shape)" app/drive "$T/m3.apk" "compressed in the APK"
mk "$T/m4.apk" "$A/libpdfium.so:16384:unaligned"
expect 1 "MUT a stored lib at a zip offset that is not 16 KB-aligned" app/drive "$T/m4.apk" "not 16 KB-aligned"
mk "$T/m5.apk" "$A/libjunk.so:0:notelf"
expect 1 "MUT a .so that is not an ELF" app/drive "$T/m5.apk" "FAIL   $A/libjunk.so"
mk "$T/m6.apk" "$A/libnoload.so:16384:noload"
expect 1 "MUT an ELF with no PT_LOAD at all" app/drive "$T/m6.apk" "no LOAD segment"
expect 1 "MUT an APK path that does not exist" app/drive "$T/missing.apk" "no such APK"

mk "$T/l1.apk" "$A/libtermux.so:16384:deflated"
expect 0 "declared legacy_packaging: a compressed 16 KB lib passes" app/legacy "$T/l1.apk"
mk "$T/l2.apk" "$A/libtermux.so:4096:deflated"
expect 1 "MUT legacy_packaging does NOT excuse a 4 KB ELF" app/legacy "$T/l2.apk" "2\*\*12 < 2\*\*14"
expect 1 "MUT legacy_packaging is per app: another app's compressed lib still fails" app/drive "$T/l1.apk" "compressed"

mk "$T/e1.apk" "$A/libold.so:4096:stored"
expect 0 "declared exemption: the named 4 KB lib passes as EXEMPT" app/exempt "$T/e1.apk" "EXEMPT $A/libold.so"
mk "$T/e2.apk" "$A/libold.so:4096:stored" "$A/libnew.so:4096:stored"
expect 1 "MUT an exemption does not cover a NEW 4 KB lib in the same app" app/exempt "$T/e2.apk" "FAIL   $A/libnew.so"
expect 1 "MUT an exemption is per app" app/drive "$T/e1.apk" "FAIL   $A/libold.so"

outp="$(CI=true ANDROID_HOME="$T/nosdk" ANDROID_SDK_ROOT= PATH="/usr/bin:/bin" python3 -I "$GUARD" app/drive "$T/clean.apk" 2>&1)"
if [ $? -ne 0 ] && grep -q "zipalign not found" <<<"$outp"; then ok "MUT on CI a missing zipalign is a failure, not a skip"
elif command -v zipalign >/dev/null 2>&1; then ok "zipalign on PATH here: the CI-missing case cannot be staged"
else fail "on CI a missing zipalign was not a failure"; printf '%s\n' "$outp"; fi

if [ "$FAILURES" -ne 0 ]; then echo "page-size-guard.test: $FAILURES case(s) wrong"; exit 1; fi
echo "page-size-guard.test: all cases as expected"
