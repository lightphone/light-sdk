## Using the LightOS Emulator as a System App
On real LP3 hardware, LightOS runs as a system app, which means it has access to Android functionality that a normal app does not. It is possible (and desirable) to run our LightOS emulator software _as_ a system app on an Android device emulator on your computer. **You will not be able to do this on any consumer Android hardware**. Besides running your tool on a real LP3 running a production build of LightOS, this will give you the best idea of how your tool will work on real Light hardware. Here are the instructions for setting it up:

#### 1. Create an AVD
Create an Android Virtual Device in Android Studio with the following properties:
* **System image**: API 34 (Android 14), **without Google Play Services** (use the "AOSP" / `google_apis` or `default` target — NOT `google_apis_playstore`)
* **Architecture**: arm64-v8a or x86_64
* **Screen**: 1080 x 1240, 3.92" display (to match the Light Phone III)

> You **must** use an image built with `test-keys` (shown in `adb shell getprop ro.build.description`). Production/user-signed images will not accept the AOSP platform test key used by the emulator app.

#### 2. Boot and prepare the emulator
(Note that the `emulator` and `adb` executables should be available in your Android sdk installation)
Start the emulator with writable system partition support:

```bash
emulator -avd <your_avd_name> -writable-system
```

> The `-writable-system` flag is required to push files into `/system`. You will need to use this flag **every time** you boot the emulator.

Then set up the system partition for writing:

```bash
adb root
adb remount
```

If `adb remount` fails with a "verity" error, disable verified boot first:

```bash
adb disable-verity
adb reboot
adb root
adb remount
```

#### 3. Generate the platform signing key

The emulator app must be signed with the AOSP platform test key so that it can share `uid 1000` with the Android system. The build expects a Java keystore at `sdk/emulator/keys/platform.jks`.

Download the AOSP platform test key files:

```bash
mkdir -p sdk/emulator/keys
curl -o /tmp/platform.x509.pem https://raw.githubusercontent.com/wfairclough/android_aosp_keys/refs/heads/master/platform.x509.pem
curl -o /tmp/platform.pk8 https://raw.githubusercontent.com/wfairclough/android_aosp_keys/refs/heads/master/platform.pk8
```

Convert the pk8 private key to PEM format, then import both into a Java keystore:

```bash
# Convert pk8 (PKCS#8 DER) to PEM
openssl pkcs8 -inform DER -nocrypt -in /tmp/platform.pk8 -out /tmp/platform.pem

# Bundle the cert + key into a PKCS#12 file
openssl pkcs12 -export \
    -in /tmp/platform.x509.pem \
    -inkey /tmp/platform.pem \
    -name platform \
    -out /tmp/platform.p12 \
    -passout pass:android

# Import into a Java keystore
keytool -importkeystore \
    -srckeystore /tmp/platform.p12 \
    -srcstoretype PKCS12 \
    -srcstorepass android \
    -destkeystore sdk/emulator/keys/platform.jks \
    -deststoretype JKS \
    -deststorepass android

# Clean up
rm /tmp/platform.pk8 /tmp/platform.x509.pem /tmp/platform.pem /tmp/platform.p12
```

> These are the well-known AOSP **test** keys — they are not secret. They only work on emulator images built with `test-keys`.

#### 4. Build the emulator app

```bash
./gradlew :sdk:emulator:assembleDebug
```

#### 5. Install as a privileged system app

```bash
# Create the priv-app directory
adb shell mkdir -p /system/priv-app/LightOSEmulator

# Push the APK
adb push sdk/emulator/build/outputs/apk/debug/emulator-debug.apk \
    /system/priv-app/LightOSEmulator/LightOSEmulator.apk

# Reboot so PackageManager picks it up as a system app
adb reboot
```

After reboot, verify the app is running as system:

```bash
# Should show /system/priv-app/LightOSEmulator
adb shell pm path com.thelightphone.sdk.emulator

# Should show uid=1000
adb shell dumpsys package com.thelightphone.sdk.emulator | grep "uid="
```

#### 6. Reinstalling after changes
When you rebuild the emulator app, you can update it without a full reboot:

```bash
./gradlew :sdk:emulator:assembleDebug
adb install -r sdk/emulator/build/outputs/apk/debug/emulator-debug.apk
```

The app will retain its system uid as long as the signing key and `sharedUserId` remain unchanged. The emulator app will log a [warning message](sdk/emulator/src/main/kotlin/com/thelightphone/sdk/emulator/MainActivity.kt) on startup if it is not running as a system app!

#### Troubleshooting

| Symptom | Fix |
|---|---|
| `uid=` shows a number other than `1000` | Fully uninstall (`adb uninstall com.thelightphone.sdk.emulator`), remove leftover data (`adb shell rm -rf /data/app/*com.thelightphone.sdk.emulator*`), re-push to priv-app, and reboot. |
| `adb remount` says "Device must be bootloader unlocked" | Run `adb disable-verity && adb reboot`, then `adb root && adb remount`. |
| App doesn't appear after reboot | Check that the APK was pushed to the correct path: `/system/priv-app/LightOSEmulator/LightOSEmulator.apk` (directory name and file name both matter). |
| Signatures don't match (`dumpsys` shows different sig hashes) | Make sure you are using an AOSP `test-keys` system image, not a production-signed image. Run `adb shell getprop ro.build.description` — it should end with `test-keys`. |

#### 7. Setting the LightOS emulator as the Android Launcher

(This is not critical, but makes the behavior more like a real Light Phone - when you hit the Android home button, it will drop you into the LightOS emulator instead of the default Android launcher.)

run:

`adb shell cmd package set-home-activity com.thelightphone.sdk.emulator/.MainActivity`

#### 8. Disabling Animations

(This is also not critical, but LightOS uses no cross-tool animations by default. These settings will help the emulator feel similar)
run:

`adb shell settings put global window_animation_scale 0`                                                                                                                                                                                                                                                            
`adb shell settings put global transition_animation_scale 0`                                                                                                                                                                                                                                                       
`adb shell settings put global animator_duration_scale 0`