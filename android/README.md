# Android client

Kotlin + Jetpack Compose, one module, built with the Gradle wrapper. Three
states: enter the invite code, draw (prompt box, picture, "change it" continues
the same drawing), and a gallery. The server URL is baked in as
`BuildConfig.SERVER_URL` (override with `-PserverUrl=...`); the code is the only
thing a person types, stored encrypted on the device.

## Build

```
make apk        # android/app/build/outputs/apk/release/elisart.apk
make publish    # ...and upload it to the VM's download page
```

Needs the JDK from `mise install` and an Android SDK. One-time SDK setup on
macOS:

```
brew install --cask android-commandlinetools
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

## Signing

`android/keystore.properties` (gitignored) points at a release keystore kept
outside the repo (`~/.config/elisart/release.jks`). Keep both: Android only
installs an update over an app signed with the same key. Without the file the
build signs with the debug key.
