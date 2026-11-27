#!/usr/bin/env python3
"""Fail when a libptts_ffi.so is missing a symbol the app calls.

Usage: native/check-ptts-ffi.py [JNI_LIBS_DIR]

JNI_LIBS_DIR defaults to app/src/main/jniLibs. It is a parameter because the native build stages
its libraries in native/.work and callers check them *before* copying them into the app, so a
checker that only looked at the app directory would either fail on a clean tree or pass on
libraries nobody had built yet.
"""
import pathlib
import sys


def main(jni_libs):
    jni = pathlib.Path(jni_libs)
    manifest = jni / "ptts-ffi.symbols"
    symbols = []
    if manifest.is_file():
        symbols = [
            line.strip() for line in manifest.read_text().splitlines()
            if line.strip() and not line.startswith("#")
        ]
    else:
        print(f"warning: {manifest} is missing; run native/build-ptts-ffi.sh", file=sys.stderr)
    libs = sorted(jni.glob("*/libptts_ffi.so"))
    if not libs:
        print(f"error: no libptts_ffi.so under {jni}", file=sys.stderr)
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
    target = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs"
    # Accept the repository root too, so an older invocation keeps working.
    root = pathlib.Path(target)
    if (root / "app/src/main/jniLibs").is_dir():
        target = root / "app/src/main/jniLibs"
    sys.exit(main(target))
