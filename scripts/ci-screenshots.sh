#!/bin/sh
# Takes a few screenshots of an installed-from-APK openPad++ on a running emulator/device:
#   sh scripts/ci-screenshots.sh <apk> <output directory>
# Used by the instrumented workflow to keep pictures of the current alpha build and of the previous release next to each
# other (artifact "screenshots"). Purely informational: the coordinates are best guesses, nothing here may fail a build.
set -u

APK=$1
OUT=$2
PKG=io.github.zeperus.openpad

mkdir -p "$OUT"
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" || exit 0
adb shell cmd uimode night no

SIZE=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=${SIZE% *}
H=${SIZE#* }
DENSITY=$(adb shell wm density | tr -d '\r' | sed -n 's/.*: \([0-9]*\).*/\1/p' | tail -1)
px() { echo $(($1 * DENSITY / 160)); }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
adb shell input tap $((W / 2)) $((H / 2))
sleep 1
adb shell input text "Weekend%splan"
adb shell input keyevent 66
adb shell input text "Buy%smilk%sand%sbread"
adb shell input keyevent 66
adb shell input text "Call%sAnna%sabout%sSaturday"
sleep 2
shot editor-light

# the navigation icon (top left) and the overflow menu (top right) of the top bar
adb shell input tap "$(px 28)" "$(px 60)"
sleep 2
shot drawer-light
adb shell input keyevent 4
sleep 1
adb shell input tap $((W - $(px 28))) "$(px 60)"
sleep 2
shot menu-light
adb shell input keyevent 4
sleep 1

adb shell cmd uimode night yes
sleep 4
shot editor-dark
adb shell input tap "$(px 28)" "$(px 60)"
sleep 2
shot drawer-dark
adb shell input keyevent 4
adb shell cmd uimode night no
adb uninstall "$PKG" >/dev/null 2>&1
exit 0
