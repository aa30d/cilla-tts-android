#!/usr/bin/env bash
set -euo pipefail

# The patched libptts_ffi.so is not committed (see .gitignore). Build it when it is missing, so a
# plain `./build.sh` produces an APK whose native library matches this source tree; `--native`
# rebuilds it even when one is present.
if [[ "${1:-}" == "--native" || "${BUILD_NATIVE:-0}" == "1" || ! -f app/src/main/jniLibs/arm64-v8a/libptts_ffi.so ]]; then
  echo "Building patched libptts_ffi.so (needs Rust, cargo-ndk and the Android NDK)..."
  ./native/build-ptts-ffi.sh
fi

python3 "$(cd "$(dirname "$0")" && pwd)/native/check-ptts-ffi.py" \
  "$(cd "$(dirname "$0")" && pwd)/app/src/main/jniLibs"

./gradlew assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m
echo "APK built at: app/build/outputs/apk/debug/app-debug.apk"
