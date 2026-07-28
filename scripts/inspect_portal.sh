#!/bin/sh
set -eu

adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell wm size
adb shell wm density
adb shell dumpsys meminfo | sed -n '1,15p'
adb shell "grep -i -E 'video/avc|h264' /vendor/etc/media_codecs*.xml 2>/dev/null" | head -20 || true
adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://accounts.google.com || true
adb shell dumpsys device_policy | grep -i -E 'owner|admin' | head -30 || true
