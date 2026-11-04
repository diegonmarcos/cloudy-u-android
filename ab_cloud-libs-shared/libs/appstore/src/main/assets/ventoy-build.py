#!/usr/bin/env python3
"""#1444 builds the Ventoy disk image PIECES inside the Cloud Terminal (cld.termux's Debian rootfs).

The Store (an Android app with the USB host grant) cannot format exFAT; this terminal can (mkfs.exfat, exfatprogs) but
cannot open the USB node. So the terminal builds every byte that must land on the drive and the Store writes them
raw over USB (libaums). Layout = Ventoy2Disk's MBR style:
  LBA 0            boot.img (MBR boot code) + partition table + disk signature
  LBA 1..2047      core.img (decompressed core.img.xz, GRUB core)
  LBA 2048..       partition 1, exFAT, label Ventoy, up to 32 MiB before the end of the disk
  last 32 MiB      partition 2, type 0xEF, VTOYEFI (ventoy.disk.img.xz, decompressed)
Output in --out: payload.bin (the pieces back to back) and manifest.json (where each piece goes). Of partition 1 only
its first part is written (boot regions, FAT, bitmap, up-case table, root directory; a fresh exFAT is zero after that).
Not run on a device block node: every path here is a regular file, never /dev/anything.
"""
import argparse, hashlib, json, lzma, os, random, shutil, struct, subprocess, sys, tarfile, urllib.request

SECTOR = 512
EFI_SECTORS = 65536            # 32 MiB, Ventoy's VTOYEFI
P1_START = 2048                # 1 MiB
MAX_MBR = 0xFFFFFFFF           # sectors an MBR entry can address (2 TiB)
MIN_DISK = 256 << 20
CORE_MAX = (P1_START - 1) * SECTOR


def die(msg):
    print("ERROR " + msg, flush=True)
    sys.exit(2)


def layout(disk_bytes):
    """-> (p1_start, p1_sectors, efi_start, efi_sectors); raises ValueError when the disk cannot hold Ventoy."""
    if disk_bytes < MIN_DISK:
        raise ValueError("drive is smaller than 256 MiB")
    total = disk_bytes // SECTOR
    if total > MAX_MBR:
        raise ValueError("drive is larger than 2 TiB: Ventoy's GPT layout is not written by this build")
    efi_start = total - EFI_SECTORS
    efi_start -= efi_start % 8                      # 4 KiB aligned
    p1 = (efi_start - P1_START)
    p1 -= p1 % 2048                                  # whole MiB, as Ventoy aligns it
    return P1_START, p1, P1_START + p1, EFI_SECTORS


def chs(lba):
    return b"\xfe\xff\xff"                           # LBA addressing: Ventoy writes the saturated CHS too


def entry(active, ptype, start, count):
    return struct.pack("<B3sB3sII", 0x80 if active else 0, chs(start), ptype, chs(start + count - 1), start, count)


def mbr(boot_img, p1, p2, signature):
    if len(boot_img) != SECTOR:
        raise ValueError("boot.img is not one sector")
    sec = bytearray(boot_img)
    sec[440:444] = struct.pack("<I", signature)
    sec[444:446] = b"\0\0"
    sec[446:462] = entry(True, 0x07, *p1)
    sec[462:478] = entry(False, 0xEF, *p2)
    sec[478:510] = b"\0" * 32
    sec[510:512] = b"\x55\xaa"
    return bytes(sec)


def exfat_head(path):
    """Bytes of an exFAT image that mkfs wrote: boot regions + FAT + bitmap + up-case + root dir (heap offset + 8 clusters)."""
    with open(path, "rb") as f:
        bs = f.read(SECTOR)
    if bs[3:11] != b"EXFAT   ":
        raise ValueError("mkfs.exfat did not write an exFAT boot sector")
    heap_off = struct.unpack_from("<I", bs, 88)[0]
    sps, spc = bs[108], bs[109]
    cluster = (1 << sps) * (1 << spc)
    return (heap_off * (1 << sps) + 8 * cluster)


def fetch(url, dest):
    if not url.startswith("https://"):
        die("https only: " + url)
    with urllib.request.urlopen(url, timeout=60) as r, open(dest, "wb") as o:
        shutil.copyfileobj(r, o, 1 << 20)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def member(tf, suffix):
    for m in tf.getmembers():
        if m.isfile() and m.name.endswith(suffix) and ".." not in m.name.split("/"):
            return tf.extractfile(m).read()
    raise ValueError("the Ventoy release has no " + suffix)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--disk-bytes", type=int, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--url")
    ap.add_argument("--sha256")
    ap.add_argument("--tarball", help="a local release archive (tests); skips the download")
    ap.add_argument("--label", default="Ventoy")
    ap.add_argument("--seed", type=int, help="fixed disk signature (tests)")
    a = ap.parse_args()
    try:
        p1_start, p1_sec, efi_start, efi_sec = layout(a.disk_bytes)
    except ValueError as e:
        die(str(e))
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(a.out, mode=0o700)
    tar = a.tarball
    if not tar:
        if not a.url:
            die("no --url or --tarball")
        tar = os.path.join(a.out, "ventoy.tar.gz")
        print("downloading " + a.url, flush=True)
        fetch(a.url, tar)
    got = sha256(tar)
    if a.sha256 and got != a.sha256.lower():
        die("sha256 mismatch: release is %s, expected %s" % (got, a.sha256))
    print("release sha256 " + got, flush=True)
    with tarfile.open(tar) as tf:
        boot = member(tf, "/boot/boot.img")
        core = lzma.decompress(member(tf, "/boot/core.img.xz"))
        efi = lzma.decompress(member(tf, "/ventoy/ventoy.disk.img.xz"))
    if len(core) > CORE_MAX:
        die("core.img is %d bytes, more than the 1 MiB gap" % len(core))
    if len(efi) != EFI_SECTORS * SECTOR:
        die("ventoy.disk.img is %d bytes, expected 32 MiB" % len(efi))
    core += b"\0" * (CORE_MAX - len(core))
    signature = a.seed if a.seed is not None else random.SystemRandom().getrandbits(32)
    sector0 = mbr(boot, (p1_start, p1_sec), (efi_start, efi_sec), signature)

    p1 = os.path.join(a.out, "p1.img")
    with open(p1, "wb") as f:
        f.truncate(p1_sec * SECTOR)
    print("mkfs.exfat %d MiB" % (p1_sec * SECTOR >> 20), flush=True)
    r = subprocess.run(["mkfs.exfat", "-L", a.label, p1], capture_output=True, text=True)
    if r.returncode != 0:
        die("mkfs.exfat failed: " + (r.stderr or r.stdout).strip()[-400:])
    head = min(exfat_head(p1), p1_sec * SECTOR)
    head += (-head) % (4096)

    pieces = []                                       # (disk byte offset, bytes)
    with open(p1, "rb") as f:
        p1_head = f.read(head)
    os.remove(p1)
    pieces.append((0, sector0))
    pieces.append((SECTOR, core))
    pieces.append((p1_start * SECTOR, p1_head))
    pieces.append((efi_start * SECTOR, efi))
    manifest = {"version": 1, "diskBytes": a.disk_bytes, "sector": SECTOR, "releaseSha256": got, "label": a.label,
                "pieces": [], "payloadBytes": 0, "payloadSha256": ""}
    h = hashlib.sha256()
    pos = 0
    with open(os.path.join(a.out, "payload.bin"), "wb") as o:
        for off, data in pieces:
            if off % SECTOR or len(data) % SECTOR:
                die("a piece is not sector aligned")
            o.write(data)
            h.update(data)
            manifest["pieces"].append({"disk": off, "pay": pos, "len": len(data)})
            pos += len(data)
    manifest["payloadBytes"], manifest["payloadSha256"] = pos, h.hexdigest()
    if not a.tarball:
        os.remove(tar)
    with open(os.path.join(a.out, "manifest.json"), "w") as m:
        json.dump(manifest, m)
    print("READY %d bytes in %d pieces" % (pos, len(pieces)), flush=True)


if __name__ == "__main__":
    main()
