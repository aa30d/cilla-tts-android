#!/usr/bin/env bash
# Check that native/xn-ptts.patch still applies to the upstream commit build-ptts-ffi.sh pins.
#
# The patch is the only thing standing between this app and a library without the Cilla Lite and
# voice-cloning symbols, and it is written by hand against a moving upstream: a rebase of
# xn-ptts, or an edit to the patch that does not compile, should fail here rather than after a
# ten-minute NDK build. Runs in CI before the native build, and needs only `git`.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATCH="$REPO_ROOT/native/xn-ptts.patch"
REPO="$(sed -n 's/^XN_PTTS_REPO="\([^"]*\)"/\1/p' "$REPO_ROOT/native/build-ptts-ffi.sh")"
COMMIT="$(sed -n 's/^XN_PTTS_COMMIT="\([^"]*\)"/\1/p' "$REPO_ROOT/native/build-ptts-ffi.sh")"

if [ -z "$REPO" ] || [ -z "$COMMIT" ]; then
  echo "could not read XN_PTTS_REPO / XN_PTTS_COMMIT from native/build-ptts-ffi.sh" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

git -C "$WORK_DIR" init -q
git -C "$WORK_DIR" remote add origin "$REPO"
git -C "$WORK_DIR" fetch -q --depth 1 origin "$COMMIT"
git -C "$WORK_DIR" checkout -q FETCH_HEAD

if ! git -C "$WORK_DIR" apply --check "$PATCH"; then
  echo "native/xn-ptts.patch does not apply to $REPO@$COMMIT" >&2
  exit 1
fi
echo "native/xn-ptts.patch applies to $REPO@$COMMIT"
