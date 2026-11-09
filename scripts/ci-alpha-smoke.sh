#!/bin/sh
# Smoke test of the minified "alpha" build on a running emulator/device (used by .github/workflows/instrumented.yml).
#
# The instrumented tests run against the debug build. What testers install is the alpha build type (R8-shrunk, not
# debuggable), and a shrinker mistake only shows when that build runs. So: install it, start it, type a few characters,
# and fail if the process died, crashed, or the build is debuggable after all. Needs adb and a booted device.
set -eu

PKG=io.github.zeperus.openpad
APK=app/build/outputs/apk/alpha/app-alpha.apk

./gradlew assembleAlpha
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK"
adb logcat -b crash -c || true

adb shell am start -W -n "$PKG/.MainActivity"
sleep 8

# tap the middle of the screen (the editor), then type
SIZE=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=${SIZE% *}
H=${SIZE#* }
adb shell input tap $((W / 2)) $((H / 2))
sleep 2
adb shell input text "smoke"
sleep 3

fail() {
    echo "ALPHA SMOKE TEST FAILED: $1"
    adb logcat -d -b crash | tail -80 || true
    exit 1
}

adb shell pidof "$PKG" >/dev/null || fail "the app is not running any more"
if adb logcat -d -b crash | grep -q "Process: $PKG"; then fail "the app crashed"; fi
if adb shell dumpsys package "$PKG" | grep -E "^\s*(pkgFlags|flags)=" | grep -q DEBUGGABLE; then fail "the alpha build is debuggable"; fi

# informational only (the accessibility dump is not reliable enough on CI to fail the build on it)
if adb shell uiautomator dump /sdcard/openpad-smoke.xml >/dev/null 2>&1 && adb shell cat /sdcard/openpad-smoke.xml | grep -q "smoke"; then
    echo "typed text is on screen"
else
    echo "note: could not confirm the typed text in the accessibility dump"
fi
echo "alpha smoke test passed"

# pictures of this build and of the previous release, for comparing them (never fails the build)
if [ -n "${PREVIOUS_APK_URL:-}" ]; then
    (curl -sSL --max-time 120 -o app/build/previous.apk "$PREVIOUS_APK_URL" && sh scripts/ci-screenshots.sh app/build/previous.apk app/build/screenshots/previous) || true
fi
sh scripts/ci-screenshots.sh "$APK" app/build/screenshots/current || true
