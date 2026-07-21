# Light SDK demo tools

| Module | Package | Description |
|--------|---------|-------------|
| `ui-demo` | `com.thelightphone.uidemo` | UI toolkit gallery: theme, icons, scroll, text input, modal |
| `weather` | `com.thelightphone.weather` | weather tool via Open-Meteo API |
| `authenticator` | `com.thelightphone.authenticator` | 2FA authenticator
| `audio-demo` | `com.thelightphone.audiodemo` | audio SDK showcase: player, recorder, capture, tone

## How to run on device

```bash
./gradlew :examples:ui-demo:installDebug
adb shell am start -n com.thelightphone.uidemo/com.thelightphone.sdk.LightActivity

./gradlew :examples:weather:installDebug
adb shell am start -n com.thelightphone.weather/com.thelightphone.sdk.LightActivity

./gradlew :examples:authenticator:installDebug
adb shell am start -n com.thelightphone.authenticator/com.thelightphone.sdk.LightActivity

./gradlew :examples:audio-demo:installDebug
adb shell am start -n com.thelightphone.audiodemo/com.thelightphone.sdk.LightActivity
```

