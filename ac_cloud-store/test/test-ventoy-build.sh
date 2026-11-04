#!/usr/bin/env bash
# #1444 ventoy-build.py (the Cloud Terminal's half of the Ventoy install), run for real on a fake release archive:
#   B1 the pieces rebuild an 1 GiB disk whose MBR is Ventoy's (boot.img + signature + 0x55AA, p1 type 07 active at
#      LBA 2048, p2 type EF in the last 32 MiB), whose core.img sits at LBA 1 and whose VTOYEFI image is intact
#   B2 partition 1 is a clean exFAT (fsck.exfat) labelled Ventoy
#   B3 every refusal: a wrong sha256, a drive under 256 MiB or over 2 TiB, a non-https url, a release without boot.img
#   B4 the manifest is what the Store checks: contiguous payload, sector-aligned pieces, payload sha256 matches
# Then planted defects in the builder must turn it red. Needs mkfs.exfat/fsck.exfat (exfatprogs): installed when absent.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${VENTOY_BUILD_PY:-$(cd "$HERE/../../ab_cloud-libs-shared/libs/appstore/src/main/assets" && pwd)/ventoy-build.py}"
if ! command -v mkfs.exfat >/dev/null 2>&1 || ! command -v fsck.exfat >/dev/null 2>&1; then
  if [ "$(id -u)" = 0 ]; then apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq exfatprogs >/dev/null 2>&1
  else sudo apt-get update -qq >/dev/null 2>&1; sudo apt-get install -y -qq exfatprogs >/dev/null 2>&1; fi
fi
export PATH="$PATH:/usr/sbin:/sbin"
command -v mkfs.exfat >/dev/null && command -v fsck.exfat >/dev/null || { echo "FAIL  exfatprogs is not available"; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

CHECK=$(cat <<'PY'
import hashlib, json, os, struct, subprocess, sys
out, tar, disk_bytes = sys.argv[1], sys.argv[2], int(sys.argv[3])
def need(c, m):
    if not c: print("  FAIL  " + m); sys.exit(1)
m = json.load(open(os.path.join(out, "manifest.json")))
pay = open(os.path.join(out, "payload.bin"), "rb").read()
need(hashlib.sha256(pay).hexdigest() == m["payloadSha256"] and len(pay) == m["payloadBytes"], "B4 payload sha256/size match the manifest")
pos = 0
for p in m["pieces"]:
    need(p["pay"] == pos and p["disk"] % 512 == 0 and p["len"] % 512 == 0, "B4 piece contiguous and sector aligned")
    pos += p["len"]
disk = os.path.join(out, "disk.img")
with open(disk, "wb") as d:
    d.truncate(disk_bytes)
    for p in m["pieces"]:
        d.seek(p["disk"]); d.write(pay[p["pay"]:p["pay"] + p["len"]])
raw = open(disk, "rb")
s0 = raw.read(512)
need(s0[510:] == b"\x55\xaa", "B1 MBR signature")
need(s0[:440] == bytes([0xEB]) * 440, "B1 boot.img code kept in the MBR")
need(struct.unpack("<I", s0[440:444])[0] == 0x12345678, "B1 disk signature")
e1, e2 = s0[446:462], s0[462:478]
need(e1[0] == 0x80 and e1[4] == 0x07 and struct.unpack("<II", e1[8:16])[0] == 2048, "B1 partition 1 = exFAT (07), active, at LBA 2048")
need(e2[4] == 0xEF and struct.unpack("<I", e2[12:16])[0] == 65536, "B1 partition 2 = EFI (EF), 32 MiB")
p1s, p1n = struct.unpack("<II", e1[8:16]); p2s, p2n = struct.unpack("<II", e2[8:16])
need(p1s + p1n <= p2s and p2s + p2n <= disk_bytes // 512 and (disk_bytes // 512 - (p2s + p2n)) < 8, "B1 p1 ends before p2, p2 is the last 32 MiB")
raw.seek(512); need(raw.read(16) != b"\0" * 16, "B1 core.img at LBA 1")
raw.seek(p2s * 512); need(raw.read(4) == b"EFI!", "B1 VTOYEFI image at partition 2")
raw.seek(p2s * 512 + (p2n * 512) - 4); need(raw.read(4) == b"EFI!", "B1 VTOYEFI image complete")
with open(os.path.join(out, "p1.img"), "wb") as o:
    raw.seek(p1s * 512); left = p1n * 512
    while left:
        b = raw.read(min(left, 1 << 22)); left -= len(b)
        if b.count(0) == len(b): o.seek(len(b), 1)
        else: o.write(b)
    o.truncate(p1n * 512)
r = subprocess.run(["fsck.exfat", os.path.join(out, "p1.img")], capture_output=True, text=True)
need(r.returncode == 0 and "clean" in r.stdout + r.stderr, "B2 fsck.exfat: partition 1 is a clean exFAT: " + (r.stdout + r.stderr)[-200:])
d = subprocess.run(["dump.exfat", os.path.join(out, "p1.img")], capture_output=True, text=True).stdout
need("Volume label: \t\t\t\tVentoy" in d or "Ventoy" in d, "B2 label Ventoy")
print("  PASS  B1 B2 B4")
PY
)
mkdir -p "$T/rel/ventoy-1.0.0/boot" "$T/rel/ventoy-1.0.0/ventoy" "$T/bad/ventoy-1.0.0/ventoy"
python3 -I - "$T" <<'PY'
import os, sys, lzma
t = sys.argv[1]
open(t + "/rel/ventoy-1.0.0/boot/boot.img", "wb").write(bytes([0xEB]) * 512)
open(t + "/rel/ventoy-1.0.0/boot/core.img.xz", "wb").write(lzma.compress(os.urandom(300000)))
open(t + "/rel/ventoy-1.0.0/ventoy/ventoy.disk.img.xz", "wb").write(lzma.compress(b"EFI!" * (8 << 20)))
open(t + "/bad/ventoy-1.0.0/ventoy/ventoy.disk.img.xz", "wb").write(lzma.compress(b"EFI!" * (8 << 20)))
PY
tar czf "$T/rel.tar.gz" -C "$T/rel" ventoy-1.0.0
tar czf "$T/bad.tar.gz" -C "$T/bad" ventoy-1.0.0
SHA=$(sha256sum "$T/rel.tar.gz" | awk '{print $1}')
fails=0
run() { python3 -I "$1" --disk-bytes "$2" --out "$T/out" --tarball "$3" --seed 305419896 "${@:4}" 2>&1; }
verify() { python3 -I -c "$CHECK" "$T/out" "$T/rel.tar.gz" "$1"; }

D=$((1<<30))
if run "$PY" $D "$T/rel.tar.gz" --sha256 "$SHA" >"$T/log" 2>&1 && verify $D; then :; else cat "$T/log"; echo "  FAIL  build or verify"; fails=$((fails+1)); fi

refuse() { # name, expected words, args...
  local name=$1 words=$2; shift 2
  if out=$(run "$PY" "$@") ; then echo "  FAIL  B3 $name: accepted"; fails=$((fails+1))
  elif echo "$out" | grep -q "$words"; then :; else echo "  FAIL  B3 $name: wrong refusal: $out"; fails=$((fails+1)); fi
}
refuse "wrong sha256" "sha256 mismatch" $D "$T/rel.tar.gz" --sha256 "$(printf 'b%.0s' $(seq 64))"
refuse "tiny drive" "smaller than 256 MiB" $((100<<20)) "$T/rel.tar.gz"
refuse "over 2 TiB" "larger than 2 TiB" $((3<<40)) "$T/rel.tar.gz"
refuse "no boot.img" "no /boot/boot.img" $D "$T/bad.tar.gz"
if out=$(python3 -I "$PY" --disk-bytes $D --out "$T/o2" --url http://example.com/x.tar.gz 2>&1); then echo "  FAIL  B3 http accepted"; fails=$((fails+1)); elif ! echo "$out" | grep -q "https only"; then echo "  FAIL  B3 http: $out"; fails=$((fails+1)); fi
[ $fails -eq 0 ] && echo "  PASS  B3 five refusals"

# Planted defects: each one must make the checks fail.
proven=0; stayed=0
while IFS='|' read -r name a b; do
  [ -z "$name" ] && continue
  python3 -I -c 'import sys; s=open(sys.argv[1]).read(); assert sys.argv[2] in s, "anchor missing: "+sys.argv[2]; open(sys.argv[4],"w").write(s.replace(sys.argv[2], sys.argv[3], 1))' "$PY" "$a" "$b" "$T/mut.py" || { echo "  FAIL  mutant $name: anchor missing"; stayed=$((stayed+1)); continue; }
  if run "$T/mut.py" $D "$T/rel.tar.gz" --sha256 "$SHA" >"$T/mlog" 2>&1 && verify $D >/dev/null 2>&1 \
     && ! run "$T/mut.py" $D "$T/rel.tar.gz" --sha256 "$(printf 'b%.0s' $(seq 64))" >/dev/null 2>&1; then echo "  FAIL  mutant stayed green: $name"; stayed=$((stayed+1)); else proven=$((proven+1)); fi
done <<'MUTANTS'
p2-type|entry(False, 0xEF,|entry(False, 0x0C,
p1-type|entry(True, 0x07,|entry(True, 0x0B,
no-signature|sec[510:512] = b"\x55\xaa"|sec[510:512] = b"\0\0"
core-offset|pieces.append((SECTOR, core))|pieces.append((SECTOR * 2, core))
efi-short|pieces.append((efi_start * SECTOR, efi))|pieces.append((efi_start * SECTOR, efi[:-512]))
head-short|head = min(exfat_head(p1), p1_sec * SECTOR)|head = 4096
no-sha-check|if a.sha256 and got != a.sha256.lower():|if False:
MUTANTS
[ "$stayed" -eq 0 ] && echo "  PASS  $proven planted defect(s) proven red" || fails=$((fails+1))
[ $fails -eq 0 ] && echo "test-ventoy-build: all checks passed" || { echo "test-ventoy-build: $fails FAILED"; exit 1; }
