#!/bin/sh
set -eu

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PACKAGE=com.johnanderson.familyportal
ACTIVITY=$PACKAGE/.MainActivity

adb install -r "$APK"
adb shell cmd package set-home-activity "$ACTIVITY" || \
    adb shell pm set-home-activity "$ACTIVITY" || true
adb shell dumpsys deviceidle whitelist +"$PACKAGE" || true
adb shell settings put system screen_off_timeout 60000
adb shell am start -n "$ACTIVITY"
