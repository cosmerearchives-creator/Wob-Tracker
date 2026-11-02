# Android signing (one-time setup)

Generate a secure Android signing keystore on your trusted device, and back up the .jks file and passwords outside GitHub. Never commit or send them in chat.

The GitHub repository must contain these **Actions repository secrets**:

- `ANDROID_KEYSTORE_BASE64` — complete single-line Base64 representation of the keystore
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — alias used at key generation, e.g. `wobtracker`
- `ANDROID_KEY_PASSWORD` — key password

For Termux, generate the key (only once):

```bash
mkdir -p ~/wob-tracker-keys && cd ~/wob-tracker-keys
keytool -genkeypair -v -keystore wob-tracker-release.jks -storetype PKCS12 -alias wobtracker -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 wob-tracker-release.jks | termux-clipboard-set
```

[Manage repository secrets](https://github.com/cosmerearchives-creator/Wob-Tracker/settings/secrets/actions).

## Automatic release

After all four secrets exist, **every push to `main`** triggers a signed Android build. Once the build succeeds, GitHub Actions automatically publishes a prerelease with tag `v0.1.<run-number>` and the signed APK. You do not need to create tags/releases manually.

The Android app checks GitHub Releases and presents an update when a higher version is available. Android prompts the user before installation. Never change the signing key or the application ID between releases.

Build failures will prevent the release from being published. You can inspect failures under [Actions](https://github.com/cosmerearchives-creator/Wob-Tracker/actions).
