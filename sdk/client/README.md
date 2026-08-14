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

Attached players follow the tool screen lifecycle. Detached players opt into
service-owned playback that can continue after the screen leaves.

#### Setup and lifecycle

`LightAudio` is constructed from the `SealedLightActivity` the screen already receives.
Pass it into your view model, create players, recorders, capture sources, and PCM voices there, and release their handles in `onCleared()`.

`newPlayer()` creates an attached player by default.
Its playback belongs to the tool screen and `release()` stops it.
A detached player moves playback ownership to an SDK service, so `release()` disconnects the tool handle but does not stop playback.
Call `stop()` before `release()` when the tool intends to end detached playback.

```kotlin
class PlayerViewModel(audio: LightAudio) : LightViewModel<Unit>() {
    private val player: LightAudioPlayer = audio.newPlayer()

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

class PlayerScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel(DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        // your UI
    }
}
```

#### Player

`LightAudioPlayer` plays files, bundled assets, and local or remote URLs. A player owns one queue and exposes its state through `StateFlow` objects: `positionMs`, `durationMs`, `isPlaying`, `currentMediaItemIndex`, `error`, and `availability`.
`currentMediaItemIndex` is `NO_MEDIA_ITEM` while the queue is empty.

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

- Use `pause`, `stop`, and `seekTo` for transport controls.
- `skipBack()` and `skipForward()` seek 15 seconds within the current item; `skipToPrevious()` and `skipToNext()` move through the queue.
- Set `speed` to change playback rate; values at or below zero clamp to the minimum supported rate.
- Use `setSource(File)` as a convenience for a one-item local-file queue.
- Playback requests audio focus automatically.
- If focus is unavailable, `play()` does nothing.
- Observe `isPlaying` for the actual state.
- Transient focus loss pauses and later resumes playback, while duckable loss lowers the volume.

##### Playback failures

Player creation failures and playback failures use different mechanisms.

`LightAudioPlayerException` is **thrown** when you create a player, for things the tool got wrong: a missing capability, a second detached handle, or a usage that conflicts with a live detached session.
Correct the cause, then create the player again.

`LightAudioError` is **observed** while playing, for things the content or the device got wrong. Playback stopped, but the player still works; select another item to continue.

```kotlin
player.error.collect { error ->
    when (error?.kind) {
        LightAudioErrorKind.Source -> // network or file I/O; worth retrying
        LightAudioErrorKind.Unsupported -> // bad container or codec; skip it
        LightAudioErrorKind.Output -> // the audio device could not be opened
        LightAudioErrorKind.Unknown -> // unclassified
        null -> // no current failure
    }
}
```

`error.diagnostic` carries the underlying platform error name for logs, and `error.itemIndex` is the queue position that failed, or `-1` when unavailable.

When an item fails, playback stops rather than skipping to the next item.
The SDK does not advance automatically, because unplayable content would advance in a loop.
Setting a queue again or selecting a healthy item clears `error`.

##### Detached playback and reconnecting

Create a detached player when playback must continue after its tool screen is released.
Enable detached audio in `lighttool.toml`:

```toml
[tool]
capabilities = ["detached-audio"]
```

Wait for its controller before deciding whether the session is fresh:

```kotlin
val player = audio.newPlayer(playback = LightAudioPlayback.Detached)
if (player.awaitReady() && player.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
    player.setMediaQueue(items)
}
```

The player `availability` field exposes the connection lifecycle:

- `Initializing` — the detached controller is connecting.
- `Ready` — commands can be handled. Attached players start here.
- `Released` — the handle is closed and cannot accept commands.

`awaitReady()` suspends through initialization and returns `true` only when the player reaches `Ready`. Returns `false` if the player is released first.

A non-empty queue belongs to the surviving service. Reuse it instead of setting the queue again, which would replace live playback. Position, duration, playing state, queue index, and playback error are populated when the controller connects.
Once the service stops, the next player is fresh and restoring its queue and position is the tool's responsibility.

`release()` disconnects a detached handle but does not stop its playback.
To end detached playback, call `stop()` before `release()`. Calling `release()`
alone only disconnects the handle; playback may continue.
Only one detached handle may exist per tool process.

A live session must be reopened with the same `LightAudioUsage`; requesting a different usage throws `LightAudioPlayerException`.

For more details on the detached playback design, refer to [Design Decisions - Detached audio](../../docs/design_decisions/detached_audio.md).

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

### NFC

`LightNfc` reads NFC tags — and other phones presenting a tag — while your tool is in the foreground.
Declare the permission in `lighttool.toml`; the plugin emits the matching `<uses-feature android:name="android.hardware.nfc" android:required="false" />` for you, so phones without NFC are never filtered out of the store listing.

```toml
permissions = ["android.permission.NFC"]
```

#### Availability

`LightNfc` is a factory constructed from the `SealedLightActivity` your screen already receives. `availability` is read again on every access, because NFC can be switched on or off in Settings while your tool is backgrounded.

```kotlin
val nfc: LightNfc = DefaultLightNfc(sealedActivity)

val prompt = when (nfc.availability) {
    LightNfcAvailability.Ready -> "Hold your phone near the other device."
    LightNfcAvailability.Disabled -> "Turn on NFC in Settings, then try again."
    LightNfcAvailability.PermissionMissing -> "This tool doesn't have access to NFC."
    LightNfcAvailability.Unsupported -> "This phone can't use NFC."
}
```

- `availability.isReady` is the short form. Gate any tap affordance on it, so a phone without NFC never shows the button.
- `Unsupported` means no NFC hardware; `Disabled` means the user turned NFC off; `PermissionMissing` means the tool omitted `android.permission.NFC` from `lighttool.toml`, and is logged with that detail.
- Screens can refresh it from `willShow()`, which runs every time the screen comes back to the front.

#### Reading taps

`LightNfcReader` provides taps as a `Flow<LightNfcTap>`, holding reader mode for as long as it is collected.

```kotlin
nfc.newReader().asFlow().collect { tap ->
    val address = tap.uri ?: tap.text
}
```

For a one-shot read, `awaitTap()` takes the first tap and stops:

```kotlin
val tap = nfc.newReader().awaitTap()
```

- Collection owns reader mode: starting collection arms the reader, or the next time the tool resumes if it is backgrounded; backgrounding disarms it; cancelling releases it.
- Collection is the boundary, not the screen. A reader collected from a scope that outlives the screen stays armed after the user navigates away, so collect from something that ends with the screen, as `LightNfcTapReader` does.
- Concurrent collectors share the one reader mode Android grants the Activity: the newest receives taps, earlier ones resume as later ones stop, and the last to stop releases the radio.
- Release matters: while reader mode is on, no other app sees taps.
- Every tap carries `serialNumber`, the tag's UID as uppercase hex.
- `records` holds the decoded NDEF message as `LightNfcRecord.Uri`, `LightNfcRecord.Text`, or `LightNfcRecord.Binary`. `tap.uri` and `tap.text` are shortcuts for the first record of each kind.
- A tag with no NDEF message — a bare UID badge — reads successfully with an empty `records` list.
- Failures arrive from collection as a `LightNfcException`: `LightNfcUnavailableException` when NFC is off, absent, not granted to the tool, or couldn't start; `LightNfcReadException` when the tag left the field or its contents couldn't be decoded. The exception message is product copy naming the actual cause.
- `LightNfcReaderConfig` narrows the technologies polled, skips the platform's NDEF check, silences the platform tap sound, and sets the presence-check delay.

#### Tap prompt

`LightNfcTapReader` is the ready-made counterpart to `LightQrCodeScanner`. It runs the reader while the screen is showing and renders the prompt, so a tool that just needs an address off a tap does not handle availability itself.

```kotlin
LightNfcTapReader(
    onTap = { tap -> tap.uri?.let(::onAddressScanned) },
    onBack = { goBack(Unit) },
)
```

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
