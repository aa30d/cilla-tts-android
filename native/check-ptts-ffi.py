#!/usr/bin/env python3
"""Fail when a libptts_ffi.so does not export a symbol the app calls.

Usage: native/check-ptts-ffi.py [JNI_LIBS_DIR]

JNI_LIBS_DIR defaults to app/src/main/jniLibs. It is a parameter because the native build stages
its libraries in native/.work and callers check them *before* copying them into the app.

The check reads each library's *dynamic* symbol table (`readelf --dyn-syms`, or the ELF headers
with plain Python when binutils is absent). Searching the file for the name as bytes would also
match a string literal, a Rust panic message or a debug-info entry, which is exactly the mistake
that lets a stale library through.

No manifest, or no libraries, is a failure rather than a warning: both mean the check cannot say
the APK is good, and a silent pass is the behaviour this script exists to prevent.
"""
import pathlib
import struct
import subprocess
import sys

SHT_DYNSYM = 11
SYMBOL_TABLE_TYPES = {SHT_DYNSYM}


def dynamic_symbols_with_readelf(lib):
    try:
        out = subprocess.run(
            ["readelf", "--wide", "--dyn-syms", str(lib)],
            capture_output=True, text=True, check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    import re

    # e.g. "   123: 0000000000012345  1234 FUNC    GLOBAL DEFAULT   12 ptts_new"
    # with possible version suffixes: "adler32@@ZLIB_1.2.0", and undefined entries ending in UND.
    row = re.compile(
        r"^\s*\d+:\s+[0-9a-fA-F]+\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*(.*)$"
    )
    names = set()
    for line in out.splitlines():
        m = row.match(line)
        if not m:
            continue
        size, kind, ndx, name = m.group(1), m.group(2), m.group(5), m.group(6).strip()
        if not name or kind == "UND" or ndx == "UND" or not size.strip():
            continue
        names.add(name.split("@")[0])
    return names


def dynamic_symbols_with_python(lib):
    """The `.dynsym` names, read straight from the ELF: binutils is not on every machine."""
    data = lib.read_bytes()
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        e_shoff, = struct.unpack_from(endian + "Q", data, 0x28)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", data, 0x3A)
        sh_fmt = endian + "IIQQQQIIQQ"
    else:
        e_shoff, = struct.unpack_from(endian + "I", data, 0x20)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", data, 0x2E)
        sh_fmt = endian + "IIIIIIIIII"
    sections = []
    for i in range(e_shnum):
        base = e_shoff + i * e_shentsize
        sections.append(struct.unpack_from(sh_fmt, data, base))
    names = set()
    for section in sections:
        # (name, type, flags, addr, offset, size, link, info, align, entsize)
        sh_type, sh_offset, sh_size, sh_link, sh_entsize = section[1], section[4], section[5], section[6], section[9]
        if sh_type not in SYMBOL_TABLE_TYPES or not sh_size:
            continue
        strtab = sections[sh_link]
        str_off, str_size = strtab[4], strtab[5]
        strs = data[str_off:str_off + str_size]
        step = sh_entsize or (24 if is64 else 16)
        for off in range(sh_offset, sh_offset + sh_size, step):
            if is64:
                st_name, st_info, _, st_shndx = struct.unpack_from(endian + "IBBH", data, off)
            else:
                st_name, _, _, st_info, _, st_shndx = struct.unpack_from(endian + "IIIBBH", data, off)
            if st_shndx == 0 or not st_name:  # UND: imported, not exported
                continue
            end = strs.find(b"\0", st_name)
            names.add(strs[st_name:end if end >= 0 else len(strs)].decode("utf-8", "replace"))
    return names


def exported_symbols(lib):
    return dynamic_symbols_with_readelf(lib) or dynamic_symbols_with_python(lib) or set()


def main(jni_libs):
    jni = pathlib.Path(jni_libs)
    manifest = jni / "ptts-ffi.symbols"
    if not manifest.is_file():
        print(
            f"error: {manifest} is missing; run ./native/build-ptts-ffi.sh --symbols-only",
            file=sys.stderr,
        )
        return 1
    symbols = [
        line.strip() for line in manifest.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]
    if not symbols:
        print(f"error: {manifest} lists no symbols", file=sys.stderr)
        return 1
    libs = sorted(jni.glob("*/libptts_ffi.so"))
    if not libs:
        print(f"error: no libptts_ffi.so under {jni}", file=sys.stderr)
        return 1
    bad = 0
    for lib in libs:
        exported = exported_symbols(lib)
        if not exported:
            print(f"error: {lib} is not an ELF library with a dynamic symbol table", file=sys.stderr)
            bad = 1
            continue
        missing = [s for s in symbols if s not in exported]
        if missing:
            bad = 1
            print(f"error: {lib} does not export {', '.join(missing)}", file=sys.stderr)
            print("       rebuild it with ./native/build-ptts-ffi.sh", file=sys.stderr)
    return bad


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs"
    # Accept the repository root too, so an older invocation keeps working.
    root = pathlib.Path(target)
    if (root / "app/src/main/jniLibs").is_dir():
        target = root / "app/src/main/jniLibs"
    sys.exit(main(target))
