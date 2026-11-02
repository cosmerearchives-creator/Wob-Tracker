# WoB Tracker

An Android-first Word of Brandon tracker. This initial foundation is deliberately feature-light.

## Development

- Kotlin, Jetpack Compose, Material 3
- Android Gradle Plugin 8.9.2, Gradle 8.11.1, JDK 17
- Android SDK 35; minimum Android 26
- Application ID: `com.cosmerearchives.wobtracker`
- Run locally with Gradle 8.11.1: `gradle assembleDebug` (or generate a Gradle wrapper with `gradle wrapper`).

## GitHub Releases and signing

See [SIGNING.md](SIGNING.md). Build verification runs for pushes to `main`. Signed APK publication is triggered by a `v*` tag after the required repository secrets have been configured.

Updates are *user-approved*, not silent. The app checks GitHub Releases on launch or on demand, downloads the newest versioned APK, and hands it to Android's installer. It only installs APKs with the matching app ID and signing certificate (enforced by Android).

The release workflow needs `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Never commit a keystore or credentials. A release tag must be monotonically newer (e.g., `v0.1.1-alpha`). Preserve your original keystore for all future app updates.
