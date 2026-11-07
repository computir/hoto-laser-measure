# Laser Measure: an open-source app for the HOTO Smart Laser Measure (QWCJY001)

HOTO's QWCJY001 Bluetooth laser measure was built to work with the Mi Home app, and that support no longer works. This free Android app works with the measure directly. It needs no Xiaomi account and doesn't track you. Measuring works fully offline.

## Features

- **Live readings.** Press the button on the measure and the reading shows up in the app.
- **Units.** Choose ft-in (rounded to 1/16" or 1/32"), ft, in, m, cm or mm. The app also shows each reading in the other unit system.
- **Area and volume.** Each reading fills in the next side. Tap a side to measure it again.
- **Room mode.** Measure each wall going around the room, including L-shaped rooms and alcoves. Mark any corner that sticks out into the room. The app draws the floor plan, works out area and perimeter (plus volume and wall area if you add the ceiling height), can calculate the last wall for you, and saves each room to History.
- **Front or back edge.** Set which edge the measure measures from. The measure's own screen switches between metres and feet to match the app.
- **History.** Every distance is saved automatically. You can name, copy, delete or share measurements.
- **Opens automatically.** Optionally, Android opens the app when you switch the measure on.
- **Stays connected.** The screen stays on while the app is connected. If you lock the phone, the connection stays up for 1 minute, so readings taken during that minute are still saved.
- **Updates itself.** On the Device tab, the app can check this repository's releases, then download, verify and install a new version.
- **Help built in.** Tap **?** in the app for help.

The app needs Android 12 or later with Bluetooth LE. It has been tested on a Pixel 8 Pro running Android 17.

## Install

1. Download `LaserMeasure.apk` from the [latest release](../../releases/latest).
2. Open the file on your phone. If Android asks, allow your browser or Files app to install unknown apps.
3. If Play Protect warns that the app comes from an unknown developer, choose **Install anyway**. The app isn't on the Play Store.

**Advanced Protection on?** Android then blocks every app, browser and file manager from installing APKs, so install from a computer instead. See the script below.

## Updating

**From the app:** open the **Device** tab, go to **Updates**, and tap **Check for updates**, then **Download and install**. By default the app also checks about once a day. Updates keep your history, settings and pairing.

**From a computer** (needed with Advanced Protection or a work policy): turn on USB debugging on the phone, plug it in, then run this from a copy of the repository:

```
powershell -ExecutionPolicy Bypass -File scripts\update-phone.ps1
```

The script downloads the latest release and checks it against GitHub's SHA-256 checksum. It then confirms the APK is signed with the project's release key and installs it over USB. You need adb (Android platform-tools); the script finds it through `local.properties`, `ANDROID_HOME` or `PATH`, or you can pass `-Adb <path>`. The key check also uses `apksigner` from the Android SDK build-tools. If that isn't installed, the script skips the check, and Android still refuses an update signed with a different key.

## First use

1. Turn the measure on.
2. In the app, open the **Device** tab and tap **Allow Bluetooth**. The app asks for the *Nearby devices* permission only, not location.
3. Tap **Search for measure**, then **Pair**. The measure shows up as `xiaojiang.cc_…`.
4. Press the button on the measure. The reading appears in the app.

The measure stores only **one** pairing at a time, so pairing with this app replaces its Mi Home pairing. If the app later shows **Pair again**, search for the measure and pair it again. Your history is kept.

## Build from source

You need JDK 17 and the Android SDK (platform 36).

```
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. You can also open the project in Android Studio.

The release build is unsigned unless you set the Gradle property `HOTO_KEYSTORE_PROPERTIES`, for example in `~/.gradle/gradle.properties`. Point it at a file that contains `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Official releases are signed with a key that isn't in this repository. If you build your own copy, it won't install over an official release (or the other way round) until you uninstall the existing copy first.

## Security

- The app only goes online to check for updates. It talks to GitHub over HTTPS (`api.github.com` and the release download host) and sends no data about you or your measurements. It doesn't ask for location access.
- Before an update is installed, the app checks it against the SHA-256 checksum GitHub publishes for it. The update must also be this same app, with a higher version, signed with the same release key. Android then asks you to confirm.
- The pairing token is stored separately and is left out of Android backups and device transfers.
- Session messages are checked for authenticity (AES-CCM), and replayed messages are rejected.
- The measure's pairing has no PIN, which is a limitation of the device itself. Pair in private, and if the app unexpectedly says *Pair again*, someone else paired with the measure.

To report a security problem, please open a GitHub issue. Leave out sensitive details, and ask for a private way to share them.

## How it works

The measure uses Xiaomi's BLE "standard auth" scheme:

| Step | What happens |
|---|---|
| Pair (once) | The phone and the measure do a P-256 ECDH key exchange. HKDF-SHA256 (`mible-setup-info`) derives a 12-byte token, which the phone stores. |
| Login (every connection) | The two sides exchange random challenges. HKDF-SHA256 (`mible-login-info`) derives the session keys, and each side proves itself with an HMAC. |
| Readings | Readings arrive as AES-CCM-encrypted notifications on characteristic `FEA8`. Attribute `04/00` is the distance in mm, `02/01` is the measuring edge, and `02/02` is the display unit. The app must acknowledge each report. |
| Auto-open | Android's companion-device presence detection watches for the measure's advertising and wakes the app when the measure appears. |

The Bluetooth code is in [`app/src/main/java/com/hotomeasure/app/ble/`](app/src/main/java/com/hotomeasure/app/ble). The unit tests check it against:
- the BouncyCastle AES-CCM implementation
- the RFC 5869 HKDF test vectors
- a real login session recorded from the device, replayed byte for byte
- real encrypted measurement frames
- a simulated device that walks through pairing

## Credits and license

The protocol details and captured test data come from the [Zamer](https://github.com/yelloduxx/zamer) project (Apache-2.0), which reverse-engineered this device first.

Laser Measure is released under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

This is an independent project. It is not affiliated with or endorsed by HOTO or Xiaomi.
