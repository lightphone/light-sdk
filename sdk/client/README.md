# `:sdk:client`

The client library that every Light tool compiles against. It re-maps/simplifies the default Android app lifecycles, hands you a Compose-friendly screen/view-model framework, and brokers communication with LightOS (or the [emulator](../emulator)) over the SDK service binding.


## Building blocks

### Screens

A screen is a piece of UI plus its lifecycle hooks. Most tools subclass `LightScreen<R, VM>`, which pairs a `SimpleLightScreen<R>` with a `LightViewModel<R>`:

```kotlin
@InitialScreen
class HomeScreen(activity: SealedLightActivity)
    : LightScreen<Unit, HomeScreenViewModel>(activity) {

    override val viewModelClass = HomeScreenViewModel::class.java
    override fun createViewModel() = HomeScreenViewModel(fileShare)

    @Composable
    override fun Content() {
        // your Compose UI
    }
}
```

- `R` is the *result type* the screen can return to whoever opened it (`Unit` if it doesn't return anything).
- The class annotated with `@InitialScreen` is the boot screen. The SDK scans for it at startup; excluding it (or having more than one) will fail the build.
- `SimpleLightScreen` is the no-view-model variant if you don't need one.
- Override `willShow`, `willHide`, `onAppPause`, `onScreenDestroy` for lifecycle hooks.

### View models

Model-View-ViewModel architecture is relatively popular for standard Android application development, so we included some classes that wrap standard Android MVVM APIs. You do not have to use them! Have your tool's Screens extend `SimpleLightScreen` if you want to avoid MVVM. Otherwise extend `LightScreen` and specify your `LightViewModel` class.

`LightViewModel<R>` extends [`androidx.lifecycle.ViewModel`](https://developer.android.com/topic/libraries/architecture/viewmodel) and adds Light-specific hooks:

```kotlin
class HomeScreenViewModel(private val fileShare: LightFileShare)
    : LightViewModel<Unit>() {

    val items = MutableStateFlow<List<String>>(emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        items.value = fileShare.list("ringtones")
    }

    override fun onBackPressed(): Boolean = false  // true to consume the back press
}
```

### Navigation

From any screen:

```kotlin
navigateTo(::DetailScreen) { result ->
    // called when DetailScreen.goBack(result) fires
}
```

`navigateTo` takes a `(SealedLightActivity) -> SimpleLightScreen<R>` factory and an optional result callback. To return a value, call `goBack(result)` on the child screen.

The SDK manages its own back stack and renders a back bar at the bottom of the screen. The system back gesture is wired to the same logic; you don't need to handle it yourself.

### Per-screen storage and files

Every screen gets:

- `dataStore: DataStore<Preferences>` — a shared Preferences DataStore (named `DEFAULT_DATASTORE`) for the whole tool.
- `filesDir: File` — the standard app private files directory.
- `fileShare: LightFileShare` — files written here can be read by LightOS via a content provider (e.g., ringtones, wallpapers).

### Audio

`LightAudio` provides a minimal and opinionated API for dealing with sound input and output, both at the file (`LightAudioPlayer`,  `LightAudioRecorder`) and buffer levels (`LightAudioVoice`, `LightAudioCapture`).
[`:examples:audio-demo`](../../examples/audio-demo) has a complete app demo of the current functionality.

_NOTE: The SDK only supports foreground audio at the moment. Background audio requires some work on LightOS, and is planned._

#### Setup and lifecycle

`rememberLightAudio()` provides a Compose-scoped factory for foreground audio. Create players, recorders, capture sources, and PCM voices from it, then release the components when their owning screen or view model is destroyed. The factory itself does not need to be released.

```kotlin
class PlayerViewModel : LightViewModel<Unit>() {
    private var player: LightAudioPlayer? = null

    fun attachAudio(audio: LightAudio) {
        if (player == null) player = audio.newPlayer()
    }

    fun shutdown() {
        player?.release()
        player = null
    }
}

@Composable
override fun Content() {
    viewModel.attachAudio(rememberLightAudio())
    // your UI
}

override fun onScreenDestroy() {
    viewModel.shutdown()
}
```

#### Player

`LightAudioPlayer` plays files, bundled assets, and local or remote URLs. A player owns one queue and exposes position, duration, playback state, and queue index through `StateFlow` objects.

```kotlin
player.setMediaQueue(
    listOf(
        LightAudioItem(
            source = LightAudioSource.AssetSource("audio/example.mp3"),
            metadata = LightMediaMetadata(title = "Example"),
        ),
    ),
)
player.play()
```

- Use `pause`, `stop`, `seekTo`, or the skip methods for transport controls.
- Playback requests audio focus automatically.
- If focus is unavailable, `play()` does nothing.
- Observe `isPlaying` for the actual state.
- Transient focus loss pauses and later resumes playback, while duckable loss lowers the volume.

#### PCM voice

`LightAudioVoice` plays short mono signed 16-bit PCM buffers.
Use it for synthesized sounds or short samples that don't require playback controls.

```kotlin
val voice = audio.newVoice()
voice.play(pcm)
```

- One voice is monophonic: calling `play()` re-triggers it.
- Create and release multiple voices when sounds need to overlap.
- Generate or resample buffers for the voice's sample rate. The preferred output rate is available from `audio.capabilities.sampleRate`.

#### Recorder

`LightAudioRecorder` writes microphone input to an MPEG-4 file containing AAC audio.
Add `android.permission.RECORD_AUDIO` to `lighttool.toml` and request the runtime permission before recording:

```toml
permissions = ["android.permission.RECORD_AUDIO"]
```

```kotlin
try {
    recorder.start(File(filesDir, "recording.m4a"))
} catch (error: LightAudioException) {
    // show error.message
}

val durationMs = recorder.stop()
```

- Starting a recording cancels any active recording.
- `stop()` finalizes the file and returns its elapsed duration, or `0` if no valid recording was produced.
- `cancel()` stops recording and deletes its output.
- Set `RecorderConfig.source` to `Unprocessed` to request raw input when supported; `Mic` uses the standard processed microphone path.

#### Capture

`LightAudioCapture` provides microphone input as a `Flow<ShortArray>` of mono signed 16-bit PCM buffers.
It also requires the record-audio permission.

Use it for functionality that requires real-time processing of audio input, rather than recording it to a file.

```toml
permissions = ["android.permission.RECORD_AUDIO"]
```

```kotlin
val capture = audio.newCapture()
capture.asFlow().collect { pcm ->
    // analyze the newest PCM buffer
}
```

- Collection owns the microphone lifetime: starting collection starts capture, and cancelling stops it.
- Use one active collector per capture instance.
- A capture startup failure throws `LightAudioCaptureException` from collection.
- Set `CaptureConfig.source` to `Unprocessed` to request raw input when supported; `Mic` uses the standard processed microphone path.

### Talking to LightOS

`callRemoteServiceMethod(method, payload)` sends a typed request to the LightOS server (or to `:sdk:emulator` in dev) and returns a `LightResult<Response>`. The set of available methods lives in `:sdk:shared`'s `LightServiceMethod`. Example:

```kotlin
val result = callRemoteServiceMethod(
    LightServiceMethod.SetRingtone,
    LightServiceMethod.SetRingtone.Request(type = 1, uri = uri),
)
result.error?.let { Log.e(TAG, "code=${it.code}") }
```

### Tool entry point (optional)

If your tool needs to do work outside the scope of a specific screen, write a Kotlin `object` that implements `LightEntryPoint` and annotate the class with `@EntryPoint`:

```kotlin
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        serverData.collect { /* observe push credentials, etc. */ }
    }

    // if your app is registered to handle push notifications, they'll all come in here
    override suspend fun onPushNotification(data: ByteArray) { /* ... */ }
}
```

`onToolCreate` is called once from the SDK `Application`. `onPushNotification` is dispatched when UnifiedPush delivers a message via `LightPushService`.

### Background jobs

`LightWork` lets you run code in the background. These jobs can run even when your tool isn't on screen, and across reboots. The system decides when to run them (it may wait until the device is idle, charging, or on Wi-Fi), so they aren't great for anything time-sensitive.

Declare a job as a top-level `val` annotated with `@LightJob`. The key you pass is the string you'll use to refer to the job elsewhere — it must be unique within your tool:

```kotlin
@LightJob("sync-contacts")
val syncContacts: LightJobHandler = { ctx, input ->
    // ...do the work...
    LightJobResult.Success()
}
```

The handler receives a `SealedLightContext` (use it for DataStore, files, etc.) and an input bundle. Return one of:

- `LightJobResult.Success(outputData)` — finished cleanly. `outputData` is optional and made available to any code watching the job.
- `LightJobResult.Retry` — something transient went wrong (a flaky network, etc.); the system will try again later, waiting longer between each attempt.
- `LightJobResult.Error(outputData)` — failed permanently; don't run again.

Schedule it from any screen:

```kotlin
LightWork.enqueue(lightContext, "sync-contacts", mapOf("force" to "true"))
```

The `inputData` map is forwarded straight to the handler. Values must be strings — encode richer types yourself.

For repeating work, use `enqueuePeriodic` with an interval. The minimum is 15 minutes; shorter intervals are rounded up:

```kotlin
LightWork.enqueuePeriodic(lightContext, "sync-contacts", repeatInterval = 30.minutes)
```

Cancel a scheduled or running job by key:

```kotlin
LightWork.cancel(lightContext, "sync-contacts")
```

Watch state changes (great inside a `LaunchedEffect` or view model):

```kotlin
LightWork.observe(lightContext, "sync-contacts").collect { state ->
    when (state) {
        LightJobState.Running -> // show a spinner
        is LightJobState.Succeeded -> // state.outputData is what the handler returned
        is LightJobState.Failed -> // ditto
        else -> Unit
    }
}
```

For a one-shot read use `LightWork.getState(...)`; to suspend until the job reaches a terminal state use `LightWork.awaitCompletion(...)`.

If you need multiple concurrent runs of the same job, pass a `tag` to `enqueue` / `enqueuePeriodic` and use that same `tag` with `cancel` / `observe`.

### Push notifications

// TODO

## Restricted dependencies

This module is wired up with the [`:plugin`](../../plugin) build plugin, which restricts which third-party libraries can appear on your tool's classpath. If you try to add a dependency that isn't allow-listed, Gradle will fail at configuration time. See [`LightSdkPlugin.kt`](../../plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt) for the current allow-list, and the [top-level README](../../README.md) for why this exists.

## Related

- [`:tool`](../../tool) — the scaffold module you actually edit when building a tool.
- [`:sdk:ui`](../ui) — Compose components and theme tokens (`LightText`, `LightTheme`, etc.).
- [`:sdk:shared`](../shared) — constants and serializable data models shared with `:sdk:server`.
- [`:sdk:server`](../server) — the LightOS side of the connection that `:sdk:client` talks to.
