# You Lock

You Lock is an Android app that protects selected apps with a PIN, a drawn pattern, or your device's strong biometric prompt. It is built with Kotlin and Jetpack Compose.

## Features

- Browse installed launchable apps and choose which ones to protect.
- Choose PIN, pattern, or fingerprint unlock. PIN remains available as a fallback for pattern and fingerprint.
- Keep app selections and unlock settings saved locally on the device.
- Monitor selected apps with an Android foreground service and show a lock overlay when one opens.
- Keep an app unlocked for the current visit, then require authentication again after leaving it.
- Use installed app icons and optionally adjust battery optimization settings.

## Requirements

- Android Studio with Android SDK 35
- Android device or emulator running Android 8.0 (API 26) or later

## Run

Open the project folder in Android Studio, allow Gradle to sync, and run the `app` configuration. On first launch, create and confirm a PIN. In the app list, select apps and choose an unlock method.

To protect apps, grant Usage Access and Display over other apps, then start **Background app lock**. Android shows an ongoing notification while the monitor is running. Battery optimization can be adjusted from the app list if Android pauses monitoring.

Fingerprint unlock uses Android's strong biometric prompt and requires a supported, enrolled biometric. A PIN fallback remains available. Pattern unlock requires drawing the same pattern twice during setup.

## Download

Download the latest signed APK from [GitHub Releases](https://github.com/not-user-mayank/You-Lock/releases/latest).

## Permissions

The monitor uses Usage Access to identify foreground apps, Display over other apps to show the lock screen, and a foreground service notification while monitoring. These permissions are enabled by the user in Android settings or in response to Android prompts.

## Build

On Windows, run `gradlew.bat :app:assembleDebug`. On macOS or Linux, run `./gradlew :app:assembleDebug`.
