#!/usr/bin/env bash
set -euo pipefail

./gradlew assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m
echo "APK built at: app/build/outputs/apk/debug/app-debug.apk"
