#!/bin/sh
set -eu

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PACKAGE=com.johnanderson.familyportal
ACTIVITY=$PACKAGE/.MainActivity

adb install -r "$APK"

# As the home screen, Family Portal's Settings > Device is the only way back to the
# device's own settings (e.g. to turn USB debugging back on). Don't take over Home
# unless one of its buttons will work.
if ! adb shell cmd package resolve-activity --brief -a android.settings.SETTINGS | grep -q /; then
    if ! adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
        grep / | grep -v -e "$PACKAGE/" -e FallbackHome | grep -q /; then
        echo "No device settings or other home screen to fall back to; not making Family Portal the home screen." >&2
        exit 1
    fi
fi

adb shell cmd package set-home-activity "$ACTIVITY" || \
    adb shell pm set-home-activity "$ACTIVITY" || true
adb shell dumpsys deviceidle whitelist +"$PACKAGE" || true
adb shell settings put system screen_off_timeout 60000
adb shell am start -n "$ACTIVITY"
