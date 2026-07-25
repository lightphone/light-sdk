package com.thelightphone.audiodemo

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightExoPlayerConfigurer
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File

/**
 * Proof case for BYO ExoPlayer: custom scheme + cache + load control + priority,
 * all adopted through [LightAudio.newPlayer] without importing Android Context.
 */
@OptIn(UnstableApi::class)
class StreamingViewModel(
    audio: LightAudio,
    readAsset: (String) -> ByteArray,
) : LightViewModel<Unit>() {
    private val demoBytes: Map<String, ByteArray> = mapOf(
        "track1" to readAsset("audio/small-talk-build-iv.ogg"),
    )

    private val player: LightAudioPlayer = audio.newPlayer(LightAudioUsage.Music) {
        buildStreamingPlayer(demoBytes)
    }

    val positionMs = player.positionMs
    val durationMs = player.durationMs
    val isPlaying = player.isPlaying

    init {
        player.setMediaQueue(
            listOf(
                LightAudioItem(
                    source = LightAudioSource.CustomSource("demo:///track1"),
                    metadata = LightMediaMetadata(
                        title = "Demo custom source",
                        artist = "BYO ExoPlayer",
                        album = "audio-demo",
                    ),
                    mimeType = "audio/ogg",
                    customCacheKey = "demo:track1",
                    mediaId = "demo:track1",
                ),
                LightAudioItem(
                    source = LightAudioSource.UrlSource(
                        "https://ice6.somafm.com/groovesalad-128-mp3",
                    ),
                    metadata = LightMediaMetadata(
                        title = "SomaFM (via default factory)",
                        artist = "STREAM",
                    ),
                    mediaId = "stream:groovesalad",
                ),
            ),
        )
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun togglePlayPause() {
        if (isPlaying.value) pause() else play()
    }

    fun skipToNext() = player.skipToNext()
    fun skipToPrevious() = player.skipToPrevious()

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

@OptIn(UnstableApi::class)
private fun LightExoPlayerConfigurer.buildStreamingPlayer(
    demoBytes: Map<String, ByteArray>,
): ExoPlayer {
    val priorityTaskManager = PriorityTaskManager()
    val cache = SimpleCache(
        File(cacheDir, "audio-demo-stream"),
        LeastRecentlyUsedCacheEvictor(16L * 1024L * 1024L),
        databaseProvider(),
    )
    val upstream = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(defaultDataSourceFactory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setUpstreamPriorityTaskManager(priorityTaskManager)
        .setUpstreamPriority(C.PRIORITY_PLAYBACK)
    val defaults = DefaultMediaSourceFactory(upstream)
    val demoFactory = DemoSchemeMediaSourceFactory(demoBytes, defaults)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs= */ 10_000,
            /* maxBufferMs= */ 40_000,
            /* bufferForPlaybackMs= */ 1_500,
            /* bufferForPlaybackAfterRebufferMs= */ 3_000,
        )
        .build()
    return builder
        .setMediaSourceFactory(demoFactory)
        .setLoadControl(loadControl)
        .setPriorityTaskManager(priorityTaskManager)
        .setPriority(C.PRIORITY_PLAYBACK)
        .build()
}

/**
 * Resolves `demo:///<id>` from in-memory bytes; delegates every other URI to
 * [defaults] so file/asset/http(s) keep working on the same player.
 */
@UnstableApi
private class DemoSchemeMediaSourceFactory(
    private val demoBytes: Map<String, ByteArray>,
    private val defaults: DefaultMediaSourceFactory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory {
        defaults.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        defaults.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaults.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && uri.scheme == DEMO_SCHEME) {
            val id = uri.pathSegments.firstOrNull().orEmpty()
            val bytes = demoBytes[id]
                ?: error("Unknown demo track id: $id")
            val progressive = DefaultMediaSourceFactory(
                DataSource.Factory {
                    object : DataSource {
                        private val inner = ByteArrayDataSource(bytes)
                        override fun addTransferListener(transferListener: TransferListener) {
                            inner.addTransferListener(transferListener)
                        }

                        override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec)
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            inner.read(buffer, offset, length)

                        override fun getUri() = inner.uri
                        override fun close() = inner.close()
                    }
                },
            )
            return progressive.createMediaSource(mediaItem)
        }
        return defaults.createMediaSource(mediaItem)
    }

    companion object {
        const val DEMO_SCHEME = "demo"
    }
}

@OptIn(UnstableApi::class)
class StreamingScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, StreamingViewModel>(sealedActivity) {
    override val viewModelClass = StreamingViewModel::class.java

    override fun createViewModel() = StreamingViewModel(
        DefaultLightAudio(sealedActivity),
        lightContext::readAsset,
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("BYO Stream"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Configured ExoPlayer with cache, load control, " +
                            "priority, and demo:/// custom source.",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = "Track 1: demo:///track1 (ogg bytes)",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.play() }
                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = "Track 2: SomaFM URL (delegated factory)",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                viewModel.skipToNext()
                                viewModel.play()
                            }
                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
                LightText(
                    text = "${formatDuration(position)}  /  ${formatDuration(duration)}",
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    monospace = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            LightIcons.REWIND,
                            viewModel::skipToPrevious,
                            contentDescription = "Previous track",
                        ),
                        LightBarButton.LightIcon(
                            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            viewModel::togglePlayPause,
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.FAST_FORWARD,
                            viewModel::skipToNext,
                            contentDescription = "Next track",
                        ),
                    ),
                )
            }
        }
    }
}
