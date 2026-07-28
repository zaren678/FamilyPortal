# Family Portal

Dedicated landscape Android dashboard for a shared Google Calendar, Home Assistant cameras, and doorbell person alerts.

## Prerequisites

- Android Studio with JDK 17 or newer; this machine should use Android Studio's bundled JDK 21.
- Android SDK 36.
- Google Cloud project with Calendar API enabled.
- Home Assistant URL, a dedicated non-admin user's long-lived token, and entity IDs.
- Lorex/NVR H.264 RTSP substream URLs using read-only credentials.

## Google OAuth

1. Create a release keystore outside this repository and keep it backed up.
2. Register an Android OAuth client for `com.johnanderson.familyportal` and the release certificate SHA-1.
3. Copy `local.properties.example` to `local.properties` and set the real client ID.
4. Move the OAuth consent screen out of Testing before appliance deployment so the refresh token does not expire after seven days.

The app requests only identity, email, and `calendar.readonly`. Authentication occurs in a system browser through AppAuth; embedded WebView sign-in is not supported.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test assembleDebug
```

For release builds, copy `keystore.properties.example` to `keystore.properties`, fill in the external keystore values, and run `./gradlew bundleRelease assembleRelease`.

## Portal setup

Connect the Portal and inspect compatibility before installation:

```bash
./scripts/inspect_portal.sh
```

The required baseline is Android API 26 with an H.264 decoder and a browser that can return an AppAuth custom-scheme redirect. Configure Lorex substreams at roughly 720p and 10–15 FPS.

Install and make Family Portal the default HOME app:

```bash
./scripts/provision_portal.sh app/build/outputs/apk/debug/app-debug.apk
```

To restore the Meta launcher, list HOME candidates with `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME` and set the desired component with `adb shell cmd package set-home-activity COMPONENT`.

## First-run configuration

1. Open Settings and set a 4–8 digit PIN.
2. Sign into Google, refresh the calendar list, and enable every calendar to display.
3. Enter the Home Assistant URL, token, and person-detection binary sensor.
4. Add each HA camera entity and RTSP URL; mark exactly one as the doorbell.
5. Confirm the active hours and idle-dimming delay.

Secrets are stored with an Android Keystore AES-GCM key and are not logged. Calendar events remain available from the local Room cache when Google is offline.
