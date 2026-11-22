# You Lock

You Lock is an Android app that protects selected apps with a PIN, a drawn pattern, or your device's strong biometric prompt. It is built with Kotlin and Jetpack Compose.

## Features

- Browse installed launchable apps and choose which ones to protect.
- Choose PIN, pattern, or fingerprint unlock. PIN remains available as a fallback for pattern and fingerprint.
- Keep app selections and unlock settings saved locally on the device.
- Optionally require the PIN again whenever You Lock is reopened.
- Set a recovery question and answer to reset a forgotten PIN.
- Optionally activate Android's device administrator so Android requires deactivation before uninstalling You Lock.
- Optionally restart monitoring after device reboot.
- Optionally capture a front-camera photo after 2, 4, or 6 incorrect unlock attempts.
- Review captured photos privately and save selected photos to the device gallery.
- Monitor selected apps with an Android foreground service and show a lock overlay when one opens.
- Keep an app unlocked for the current visit, then require authentication again after leaving it.
- Use installed app icons and optionally adjust battery optimization settings.

## Requirements

- Android Studio with Android SDK 35
- Android device or emulator running Android 8.0 (API 26) or later
- Java 17 or newer for Gradle builds

## Run

Open the project folder in Android Studio, allow Gradle to sync, and run the `app` configuration. On first launch, create and confirm a PIN. In the app list, select apps and choose an unlock method.

To protect apps, grant Usage Access and Display over other apps, then start **Background app lock**. Android shows an ongoing notification while the monitor is running. Turn on **Restart monitoring after reboot** to ask Android to restart the service on the next boot. You must first start monitoring and grant the required permissions. Some phone makers may still stop background work through battery controls, and force-stopping You Lock disables automatic restarts until it is opened again.

The optional uninstall protection uses Android's device administrator flow. When enabled, Android requires the user to deactivate You Lock in system settings before uninstalling it. The user can still deactivate it, and it does not prevent factory reset or guarantee protection from device-management tools.

The recovery answer is stored locally as a salted hash. Keep the answer memorable: it cannot be retrieved from the app, and uninstalling or clearing app data removes the saved recovery information.

Failed-attempt photos are off by default. Enabling them asks for camera permission. Android's camera indicator is shown during camera use, and the monitoring notification states when camera protection is enabled. Photos are kept in You Lock's private storage until the user taps **Save**; deleting the app clears unsaved photos. Camera access may need to be re-enabled by opening You Lock after a reboot because Android limits starting camera foreground services from the background.

Fingerprint unlock uses Android's strong biometric prompt and requires a supported, enrolled biometric. A PIN fallback remains available. Pattern unlock requires drawing the same pattern twice during setup.

## Download

Download the latest signed APK from [GitHub Releases](https://github.com/not-user-mayank/You-Lock/releases/latest).

## Permissions

The monitor uses Usage Access to identify foreground apps, Display over other apps to show the lock screen, and a foreground service notification while monitoring. These permissions are enabled by the user in Android settings or in response to Android prompts.

## Build

On Windows, run `gradlew.bat :app:assembleDebug`. On macOS or Linux, run `./gradlew :app:assembleDebug`.

Release builds use a local signing key configured in `.signing/signing.properties`. Keep this file and its referenced keystore private. If the signing file is absent, Gradle creates an unsigned release APK.
