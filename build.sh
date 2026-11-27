#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--native" || "${BUILD_NATIVE:-0}" == "1" ]]; then
  ./native/build-ptts-ffi.sh
fi

./gradlew assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m
echo "APK built at: app/build/outputs/apk/debug/app-debug.apk"
