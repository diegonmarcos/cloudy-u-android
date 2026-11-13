#!/usr/bin/env python3
"""#1708 -- write the loader the pinned proot-static embeds as a file of its own.

Usage: carve-proot-loader.py <android-abi> <proot-static> <out-file>

WHY. From targetSdk 29 an Android app may not execve() a file in its own data directory
(SELinux: no execute_no_trans on app_data_file). proot starts every guest program by exec'ing
its LOADER, and this proot carries the loader inside its own binary and writes it to
$PROOT_TMP_DIR before exec'ing it: a data-dir file, so every guest exec is "Permission denied"
once the app targets 29+. proot reads PROOT_LOADER first; pointed at a copy of the same loader
in the app's nativeLibraryDir (the one directory an app may exec from) nothing is extracted.
Both terminals ship that copy as lib*.so next to proot itself.

WHAT. The loader is the byte range between proot's own symbols _binary_loader_exe_start and
_binary_loader_exe_end (objcopy's names for an embedded file); nothing is rebuilt and nothing
else is fetched. The result is refused unless it is an ELF executable of proot's machine and
hashes to proot-loader.json::archs.<abi>.loader_sha256, so the file shipped is exactly the loader
the pinned proot would have extracted itself. The proot handed in must be proot.json's pin, and
proot-loader.json must name that same proot: a proot bump without a loader bump is refused.
"""
import hashlib
import json
import os
import struct
import sys
from pathlib import Path

DECL = Path(__file__).resolve().parent / "proot.json"
LOADER_DECL = Path(__file__).resolve().parent / "proot-loader.json"
START, END = b"_binary_loader_exe_start", b"_binary_loader_exe_end"


def carve(elf: bytes) -> bytes:
    """The embedded loader of a 64-bit little-endian proot-static. Raises ValueError otherwise."""
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF")
    e_shoff, = struct.unpack_from("<Q", elf, 0x28)
    e_shentsize, e_shnum = struct.unpack_from("<HH", elf, 0x3A)
    sections = []
    for i in range(e_shnum):
        _, sh_type, _, sh_addr, sh_offset, sh_size, sh_link, _, _, sh_entsize = struct.unpack_from(
            "<IIQQQQIIQQ", elf, e_shoff + i * e_shentsize)
        sections.append((sh_type, sh_addr, sh_offset, sh_size, sh_link, sh_entsize))
    found = {}
    for sh_type, _, sh_offset, sh_size, sh_link, sh_entsize in sections:
        if sh_type != 2 or not sh_entsize:  # SHT_SYMTAB
            continue
        str_off = sections[sh_link][2]
        for n in range(sh_size // sh_entsize):
            st_name, _, _, st_shndx, st_value, _ = struct.unpack_from("<IBBHQQ", elf, sh_offset + n * sh_entsize)
            name = elf[str_off + st_name:elf.index(b"\0", str_off + st_name)]
            if name in (START, END):
                found[name] = (st_value, st_shndx)
    if START not in found or END not in found:
        raise ValueError("proot has no _binary_loader_exe_start/_end symbols (a stripped or unbundled build)")
    (a, shndx), (b, shndx_b) = found[START], found[END]
    if shndx != shndx_b or not 0 < shndx < len(sections) or b <= a:
        raise ValueError("the loader symbols do not delimit one range of one section")
    _, sh_addr, sh_offset, sh_size, _, _ = sections[shndx]
    if not (sh_addr <= a and b <= sh_addr + sh_size):
        raise ValueError("the loader range lies outside its section")
    loader = elf[sh_offset + (a - sh_addr):sh_offset + (b - sh_addr)]
    if loader[:4] != b"\x7fELF" or loader[4:6] != elf[4:6] or loader[0x12:0x14] != elf[0x12:0x14]:
        raise ValueError("the carved range is not an ELF of proot's own class and machine")
    return loader


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    abi, proot, out = sys.argv[1:]
    pin = json.load(open(DECL))["archs"][abi]
    loader_pin = json.load(open(LOADER_DECL))["archs"].get(abi) or {}
    if loader_pin.get("proot_sha256") != pin["sha256"]:
        print(f"FAIL carve-proot-loader: proot-loader.json names proot {loader_pin.get('proot_sha256')} for {abi}, "
              f"proot.json pins {pin['sha256']}: the proot pin moved without its loader pin", file=sys.stderr)
        return 1
    data = Path(proot).read_bytes()
    got = hashlib.sha256(data).hexdigest()
    if got != pin["sha256"]:
        print(f"FAIL carve-proot-loader: {proot} hashes to {got}, proot.json pins {pin['sha256']} for {abi}",
              file=sys.stderr)
        return 1
    try:
        loader = carve(data)
    except ValueError as e:
        print(f"FAIL carve-proot-loader: {proot}: {e}", file=sys.stderr)
        return 1
    got = hashlib.sha256(loader).hexdigest()
    if got != loader_pin.get("loader_sha256"):
        print(f"FAIL carve-proot-loader: the loader inside {proot} hashes to {got}, "
              f"proot-loader.json pins {loader_pin.get('loader_sha256')} for {abi}", file=sys.stderr)
        return 1
    tmp = out + ".tmp"
    with open(tmp, "wb") as f:
        f.write(loader)
    os.chmod(tmp, 0o755)
    os.replace(tmp, out)
    print(f"carve-proot-loader: {abi} loader {len(loader)} bytes sha256 {got} -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
