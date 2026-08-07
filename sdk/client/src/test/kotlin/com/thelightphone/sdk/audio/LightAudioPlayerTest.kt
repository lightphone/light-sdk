package com.thelightphone.sdk.audio

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import java.lang.reflect.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LightAudioPlayerTest {
    private val player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, _, _ -> null } as Player

    @Test
    fun playbackErrorCodesMapToActionableKinds() {
        assertEquals(
            LightAudioErrorKind.Source,
            errorKindFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            LightAudioErrorKind.Unsupported,
            errorKindFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
        assertEquals(
            LightAudioErrorKind.Output,
            errorKindFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED),
        )
        assertEquals(LightAudioErrorKind.Unknown, errorKindFor(Int.MAX_VALUE))
    }

    @Test
    fun pendingCommandsWaitUntilReadyAndFlushInOrder() {
        val commands = PendingPlayerCommands()
        val calls = mutableListOf<Int>()

        commands.dispatch { calls += 1 }
        commands.dispatch { calls += 2 }
        assertTrue(calls.isEmpty())

        commands.ready(player)

        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun connectedPlayerSnapshotRestoresLivePlaybackState() {
        val livePlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMediaItemCount" -> 3
                "getCurrentMediaItemIndex" -> 1
                "getCurrentPosition" -> 12_345L
                "getDuration" -> 60_000L
                "isPlaying" -> true
                else -> null
            }
        } as Player

        assertEquals(
            ConnectedPlayerState(
                currentMediaItemIndex = 1,
                positionMs = 12_345L,
                durationMs = 60_000L,
                isPlaying = true,
            ),
            livePlayer.snapshotState(),
        )
    }

    @Test
    fun connectedPlayerSnapshotIdentifiesFreshSession() {
        val freshPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMediaItemCount" -> 0
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> 0L
                "getDuration" -> C.TIME_UNSET
                "isPlaying" -> false
                else -> null
            }
        } as Player

        val state = freshPlayer.snapshotState()
        assertEquals(NO_MEDIA_ITEM, state.currentMediaItemIndex)
        assertEquals(0L, state.durationMs)
    }

    @Test
    fun commandsRunImmediatelyAfterReady() {
        val commands = PendingPlayerCommands()
        val calls = mutableListOf<Int>()
        commands.ready(player)

        commands.dispatch { calls += 1 }

        assertEquals(listOf(1), calls)
    }

    @Test
    fun availabilityMovesFromInitializingToReady() {
        val commands = PendingPlayerCommands()
        assertEquals(LightAudioPlayerAvailability.Initializing, commands.availability.value)

        commands.ready(player)

        assertEquals(LightAudioPlayerAvailability.Ready, commands.availability.value)
        assertTrue(runBlocking { awaitPlayerReady(commands.availability) })
    }

    @Test
    fun releaseDropsPendingCommandsAndRejectsNewOnes() {
        val commands = PendingPlayerCommands()
        var called = false
        commands.dispatch { called = true }

        commands.release()
        commands.ready(player)

        assertEquals(false, called)
        assertEquals(LightAudioPlayerAvailability.Released, commands.availability.value)
        assertEquals(false, runBlocking { awaitPlayerReady(commands.availability) })
        assertFailsWith<IllegalStateException> { commands.dispatch { called = true } }
        assertFailsWith<IllegalStateException> { commands.requireActive() }
    }

    @Test
    fun releaseAfterReadyIsTerminal() {
        val commands = PendingPlayerCommands()
        commands.ready(player)

        commands.release()
        commands.ready(player)

        assertEquals(LightAudioPlayerAvailability.Released, commands.availability.value)
    }

    @Test
    fun awaitingConnectionReturnsFalseWhenReleased() = runBlocking {
        val commands = PendingPlayerCommands()
        val result = async { awaitPlayerReady(commands.availability) }
        yield()
        assertEquals(false, result.isCompleted)

        commands.release()

        assertEquals(false, result.await())
    }

    @Test
    fun skipPositionClampsToStartAndDuration() {
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = -15_000L))
        assertEquals(20_000L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(60_000L, skipPosition(positionMs = 55_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 0L, deltaMs = 15_000L))
    }

    @Test
    fun sourceUriMapsAssetsAndUrls() {
        assertEquals(
            "asset:///audio/sample.ogg",
            LightAudioSource.AssetSource("/audio/sample.ogg").uriString(),
        )
        assertEquals(
            "https://example.com/live.mp3",
            LightAudioSource.UrlSource("https://example.com/live.mp3").uriString(),
        )
    }
}
