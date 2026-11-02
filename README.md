# WoB Tracker

Independent Android app for Words of Brandon. Initial placeholder UI uses a warm off-white, lavender and plum palette. Tracker features will be planned separately.

## Android

Kotlin + Jetpack Compose, Material 3, SDK 35, minimum SDK 26, Java 17, Gradle 8.11.1.

## Updates and releases

Changes pushed to `main` automatically build, sign and publish a versioned APK through GitHub Actions **when the signing secrets are configured and the build succeeds**. The app checks published GitHub Releases on launch and on demand, then offers a user-approved APK installation. No manual tagging is needed.

Read [SIGNING.md](SIGNING.md) to configure the four GitHub Actions secrets and protect the signing key. [Build status](https://github.com/cosmerearchives-creator/Wob-Tracker/actions) · [Releases](https://github.com/cosmerearchives-creator/Wob-Tracker/releases).
