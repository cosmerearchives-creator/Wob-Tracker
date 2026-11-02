# One-time Android signing setup

1. On a trusted computer with Java JDK installed, generate a private signing key (not on GitHub):
   ```sh
   keytool -genkeypair -v -keystore wob-tracker-release.jks -storetype PKCS12 -alias wobtracker -keyalg RSA -keysize 3072 -validity 10000
   ```
   Choose and save strong passwords. Keep the .jks in two secure, offline backup locations.
2. Convert the entire .jks file to one unwrapped Base64 string:
   - macOS: `base64 < wob-tracker-release.jks | tr -d '\n'`
   - Linux: `base64 -w 0 wob-tracker-release.jks`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("wob-tracker-release.jks")) | Set-Clipboard`
3. Go to [repository Settings → Secrets and variables → Actions](https://github.com/cosmerearchives-creator/Wob-Tracker/settings/secrets/actions) → **New repository secret**. Set:
   - `ANDROID_KEYSTORE_BASE64`: the entire Base64 text
   - `ANDROID_KEYSTORE_PASSWORD`: the keystore password
   - `ANDROID_KEY_ALIAS`: `wobtracker` (or the alias you chose)
   - `ANDROID_KEY_PASSWORD`: the key password (can equal the keystore password)
4. To publish the initial signed APK, create and push the annotated Git tag `v0.1.0-alpha` from `main` (or create a release tag through GitHub's Releases interface). The release workflow builds and uploads `WoB-Tracker-v0.1.0-alpha.apk`.
5. To verify auto-update end to end, install the first signed APK, then tag a newer commit `v0.1.1-alpha`. Open the installed app and use **Check for updates**. Confirm Android installs it without removing the app.

**Important:** Do not send passwords or keystore files through chat or commit them to source control. If a release uses a different signing certificate, Android won't install it over the old version. Future Play Store distribution may require a different update strategy.
