#!/usr/bin/env python3
"""Fail when a libptts_ffi.so in jniLibs is missing a symbol the app calls."""
import sys, pathlib

def main(root):
    jni = pathlib.Path(root) / "app/src/main/jniLibs"
    manifest = jni / "ptts-ffi.symbols"
    if not manifest.is_file():
        print(f"warning: {manifest} is missing; run native/build-ptts-ffi.sh", file=sys.stderr)
    symbols = [
        line.strip() for line in manifest.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ] if manifest.is_file() else []
    libs = sorted(p for p in jni.glob("*/libptts_ffi.so"))
    if not libs:
        print("error: no libptts_ffi.so under app/src/main/jniLibs", file=sys.stderr)
        return 1
    bad = 0
    for lib in libs:
        blob = lib.read_bytes()
        missing = [s for s in symbols if s.encode() not in blob]
        if missing:
            bad = 1
            print(f"error: {lib} is missing {', '.join(missing)}", file=sys.stderr)
            print("       rebuild it with ./native/build-ptts-ffi.sh", file=sys.stderr)
    return bad

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
