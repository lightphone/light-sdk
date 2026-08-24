# Detached audio

Why detached playback is built the way it is. For how to use it, see the
[audio section of the SDK README](../../sdk/client/README.md#audio).

## Attachment modes

`LightAudioPlayer` supports two attachment modes:

- `Attached` owns an in-process player. Releasing the tool's handle stops and releases playback.
- `Detached` controls a player owned by an SDK `MediaSessionService`. Releasing the handle disconnects the tool while playback and its queue may remain live. Tools using this method opt in by adding `detached-audio` to their `capabilities` in `lighttool.toml`.

The attached/detached (ownership-based) terminology is preferred over background/foreground (visibility-based) because it describes the relationship between the tool and the player object, not whether audio is currently playing or whether the tool is visible.
Android implements detached playback with a _foreground_ service, so visibility-based naming (background/foreground) would make SDK terminology confusing.

Here's a basic architecture diagram:

```text
LightAudioPlayer
├── Attached ── ExoPlayer in the tool screen lifecycle
└── Detached ── media3 MediaController ── MediaSession
                                             └── LightAudioService
                                                 (MediaSessionService)
                                                  └── ExoPlayer
```

## Architecture

This section zooms in on the components and their relationships:

```text
┌─ TOOL PROCESS (com.example.mytool) ─────────────────────────────┐
│                                                                 │
│  PlayerViewModel                                                │
│       │                                                         │
│       ▼                                                         │
│  DefaultLightAudio : LightAudio                                 │
│      wraps SealedLightActivity                                  │
│       │  .newPlayer(usage, playback)                            │
│       │                                                         │
│       ├── checks ──▶ CAPABILITY_DETACHED_AUDIO marker           │
│       ├── opens ───▶ DetachedSessionState                       │
│       │              └── also holds the live session's usage    │
│       ▼                                                         │
│  LightAudioPlayer                                               │
│       │    wraps a media3 Player implementation                 │
│       │                                                         │
│       ├─ Attached ──▶ ExoPlayer ─────────────────────────────┐  │
│       │              (owned by the player, dies with it)     │  │
│       │                                                      │  │
│       └─ Detached ──▶ MediaController ─┐                     │  │
│                       + connectionHints│                     │  │
│                                        │                     │  │
│                              binder (loopback, same proc)    │  │
│                                        │                     │  │
│  ┌─ LightAudioService : MediaSessionService ─────────────┐   │  │
│  │   plugin-generated; no android:process                │   │  │
│  │   foregroundServiceType="mediaPlayback"               │   │  │
│  │                                                       │   │  │
│  │   MediaSession ◀───── SessionCallback: onConnect,     │   │  │
│  │      │                  onPostConnect, onDisconnected │   │  │
│  │      ▼                                                │   │  │
│  │   ExoPlayer  ─────────────────────────────────────────┼───┤  │
│  │      setAudioAttributes(usage, handleAudioFocus=true) │   │  │
│  └───┬───────────────────────────────────────────────────┘   │  │
└──────┼───────────────────────────────────────────────────────┼──┘
       │        ▲                                              │
       │        │ other controllers of the same MediaSession:  │
       │        ├── Android system media controls              │
       │        ├── Bluetooth / headset controls               │
       │        └── media3 notification controller             │
       │                                                       │
       │ publishes platform session                            │
       ▼                                                       │
┌───────────────────────── ANDROID SYSTEM ─────────────────────┼──┐
│   MediaSessionManagerService                    AudioManager │  │
└──────────────────────────────────────────────────────────────┼──┘
        ▲   query active sessions              AUDIOFOCUS_GAIN │
        └───────────────────────────┐              arbitration │
┌─ LIGHTOS PROCESS ─────────────────┼──────────────────────────┼──┐
│ (uid.system, the launcher)        │                          │  │
│                                   │                          │  │
│   MediaSessionManager.getActiveSessions()                    │  │
│      └── android.media.session.MediaController               │  │
│          └── Now-playing: LockScreen / Toolbox               │  │
│                                                              │  │
│   LightOSAudioPlayerService                                  │  │
│      │  survives as uid.system persistent launcher           │  │
│      ├── LightOSAudioPlayerAudioFocus ◀──────────────────────┘  │
│      │      requests AUDIOFOCUS_GAIN,                           │
│      │        OnAudioFocusChangeListener already pauses         │
│      │        LightOS music when a tool plays                   │
│      └── LightOSAudioPlayerState                                │
└─────────────────────────────────────────────────────────────────┘
```

Detached audio wraps media3's `MediaSessionService`, described in the [official Android documentation](https://developer.android.com/media/media3/session/background-playback). `LightAudioService` extends that service, owns one `ExoPlayer`, and publishes the player through one `MediaSession`.

`LightAudioService` is declared by the Gradle plugin, in the manifest of each tool that opted in, without `android:process` — so it runs inside the tool process. The service asserts this process relationship when it starts, since it's a crucial detail for the architecture.
Being in the same process allows the tool code and the service to access the same `DetachedSessionState` instance, which is responsible for:

- enforcing one detached handle
- recording the live session's audio usage
- carrying construction-time configuration to the service before it builds its player, and recording what the live session was built with
- telling the service whether a tool still holds the detached handle.

The public player API is backed by media3's `Player` interface.
Attached mode uses `ExoPlayer` and detached mode uses `MediaController`.
Queue, transport, position, metadata, and playback errors therefore have the same SDK surface in both modes.

The SDK maps its audio API onto the media3 components as follows:

- In detached mode, `LightAudioPlayer` creates a `MediaController` for that session instead of creating its own `ExoPlayer`.
- `LightAudioItem` becomes a media3 `MediaItem`. Its `LightMediaMetadata` becomes `MediaMetadata`. The session can then expose the same queue and metadata to Android, media buttons, and future LightOS controls.
- Player state received by the controller is mirrored into the `LightAudioPlayer` state flows.
- media3 `PlaybackException` values are mapped into SDK-owned `LightAudioError` values so attached and detached players have the same error surface.
- Releasing `LightAudioPlayer` closes its controller and handle without releasing the service-owned player.

The controller identifies itself as a tool controller and sends its `LightAudioUsage` through connection hints. Other platform controllers, such as Bluetooth, may connect to the session, but they do not own the SDK's detached handle or select its audio usage.

### Configuration that connection hints cannot carry

Audio usage travels as a connection hint because it can be applied to a player that already exists. Where a player reads bytes from cannot: a media source factory is a constructor argument, and `onCreate` has already built the player by the time the first controller connects. Connection hints are therefore too late for anything settled at construction.

Such configuration goes through `DetachedSessionState` instead. The tool stages it there before asking for a controller — which is what starts the service — and the service takes it in `onCreate`. This works only because the service shares the tool's process, and it is the reason `LightMediaCache` is described as data the SDK acts on rather than handed over as a configured media3 object: a lambda holding tool state would run inside a service the tool does not own, and a media3 object built by the tool would make its lifetime the tool's problem.

Because construction is the only chance, a live session's caches cannot be adopted the way its usage can. Reconnecting with a different set throws rather than being silently ignored, and a session the system revived rather than a tool started records that it was built without caches, so a later handle asking for them is refused instead of being handed a player that has none.

`LightMediaSourceFactory` travels the same way, with one difference. Caches are data and can be compared, so a reconnect asking for the same ones is allowed through. Two factories are indistinguishable whether or not they would build the same pipeline, so there is no comparison to make: a reconnecting handle must pass no factory at all, and the service keeps only whether the live session had one. The lambda itself is dropped once the player exists, since it can never be applied to a second one.

## Foreground-service notification on LP3

Android requires a media-playback foreground service to publish a notification.
On stock Android, SystemUI renders that notification. LP3 uses LightOS, which
does not render a notification shade and does not listen for notifications from
tool processes. The media3 notification therefore satisfies the Android
foreground-service requirement but is not visible on LP3.

The notification is not the LightOS now-playing surface. LightOS discovers the
platform `MediaSession` separately and renders its own controls.

## Lifetime and idle stop

Playing detached audio keeps the service alive after the tool releases its handle.
A paused service must not retain its player and process forever, so the service starts a 60-second timer when both conditions hold:

1. playback is not playing
2. no tool holds the detached handle.

Playback resuming or a handle opening cancels the timer. When it fires, the service stops itself and releases the session and player.
The handle, rather than the number of connected controllers, is the liveness signal because it covers controller connection gaps and gives ownership one source of truth.
The timeout governs abandoned paused playback, not active playback. A paused tool that still holds its player can resume after 60 seconds.

## Reconnecting

A new detached player connects to one of two states:

- **Live:** the service still owns its queue, index, position, and playback state.
- **Fresh:** the service was never started or has stopped, so its queue is empty.

Before inspecting the queue, a tool waits for `availability` to become `Ready`, normally through `awaitReady()`. Re-initializing an already active queue results in replacing playback that survived from the previous screen.

`release()` only disconnects a detached handle. A tool that intends to end detached playback calls `stop()` first. Once the idle rule has stopped the service, restoring queue and position is the tool's responsibility.

A live session also retains its `LightAudioUsage`. Reconnecting with a different usage throws synchronously instead of silently changing or ignoring the live session's audio attributes.

## Playback errors

Both modes report failures as `LightAudioError` (SDK-owned type) rather than exposing media3's `PlaybackException`.
This keeps media3 out of the public API and gives attached and detached modes a single error interface.

An in-process `ExoPlayer` throws `ExoPlaybackException`, which adds additional fields to the base class that do not survive the controller boundary. `PlaybackException` itself is serializable and reaches a `MediaController`, but its subclass detail does not.

Anything the SDK derived from the exception type would therefore be richer in attached mode than in detached mode, and the two would diverge exactly where the rest of this design keeps them identical.

`errorCode` crosses the controller/session boundary. The SDK maps it into four categories a tool can act on: `Source`, `Unsupported`, `Output` and `Unknown`. It carries the stable media3 error-code name as a diagnostic string for logs.

media3 stops the queue when an item fails; it does not skip to the next one. The SDK preserves that. Automatically advancing would be a playback policy rather than a player behavior, and unplayable content would advance in a loop.
Recovering is the tool's decision.

## Tool opt-in

Tools opt into detached playback through `lighttool.toml`:

```toml
[tool]
capabilities = ["detached-audio"]
```

The Light SDK Gradle plugin translates this capability into the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` manifest
permissions, the capability marker, and the `<service>` declaration.

The SDK factory rejects detached construction without the capability and reports the required entry.
Attached playback needs no additional opt-in.

## LightOS contract

The integration boundary is the platform `MediaSession`:

- Audio-focus arbitration with LightOS already uses Android `AudioManager`.
- Detached tools publish queue and metadata through their session.
- LightOS publishes metadata for its own player and resolves media-button arbitration between sessions.
- A unified now-playing surface can discover active sessions with `MEDIA_CONTENT_CONTROL` and render metadata plus transport from an `android.media.session.MediaController`.
