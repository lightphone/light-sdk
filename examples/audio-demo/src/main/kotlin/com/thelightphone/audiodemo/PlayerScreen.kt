package com.thelightphone.audiodemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlayerViewModel(
    filesDir: File,
    audio: LightAudio
) : LightViewModel<Unit>() {
    private val player: LightAudioPlayer = audio.newPlayer()
    val clips = AudioLibraryRepository(filesDir).list()
    val currentClip: StateFlow<AudioClip?> = player.currentMediaItemIndex
        .map(clips::getOrNull)
        .stateIn(viewModelScope, SharingStarted.Eagerly, clips.getOrNull(player.currentMediaItemIndex.value))
    val positionMs = player.positionMs
    val durationMs = player.durationMs
    val isPlaying = player.isPlaying
    val speed = MutableStateFlow(1f)
    val skipSilence = MutableStateFlow(false)
    val playNext = MutableStateFlow(true)

    fun play(clip: AudioClip) {
        val selection = playbackSelectionFor(clip, clips)
        player.speed = speed.value
        player.skipSilence = skipSilence.value
        player.pauseAtEndOfMediaItems = pauseAtEndOfMediaItemsFor(playNext.value)
        player.setMediaQueue(selection.queue.map(AudioClip::toLightAudioItem), selection.startIndex)
        player.play()
    }

    fun togglePlayPause() {
        if (isPlaying.value) player.pause() else player.play()
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun skipToPrevious() = player.skipToPrevious()
    fun skipToNext() = player.skipToNext()

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(speed.value) + 1).mod(SPEEDS.size)]
        speed.value = next
        player.speed = next
    }

    fun toggleSkipSilence() {
        val enabled = !skipSilence.value
        skipSilence.value = enabled
        player.skipSilence = enabled
    }

    fun togglePlayNext() {
        val enabled = !playNext.value
        playNext.value = enabled
        player.pauseAtEndOfMediaItems = pauseAtEndOfMediaItemsFor(enabled)
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

private fun AudioClip.toLightAudioItem() = LightAudioItem(
    source = when (val source = source) {
        is AudioClipSource.FileSource -> LightAudioSource.FileSource(source.file)
        is AudioClipSource.AssetSource -> LightAudioSource.AssetSource(source.assetPath)
        is AudioClipSource.UrlSource -> LightAudioSource.UrlSource(source.url)
    },
    metadata = toLightMediaMetadata(),
)

private fun AudioClip.toLightMediaMetadata() = LightMediaMetadata(
    title = displayName,
    artist = kind.name,
    durationMs = durationMs.takeIf { it > 0L },
)

internal data class PlaybackSelection(
    val queue: List<AudioClip>,
    val startIndex: Int,
)

internal fun pauseAtEndOfMediaItemsFor(playNext: Boolean): Boolean = !playNext

/** The whole library is one playlist; play-next only controls auto-advance. */
internal fun playbackSelectionFor(
    selected: AudioClip,
    clips: List<AudioClip>,
): PlaybackSelection {
    val startIndex = clips.indexOf(selected)
    require(startIndex >= 0) { "Selected clip must be present in the playlist" }
    return PlaybackSelection(clips, startIndex)
}

class PlayerScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel(lightContext.filesDir, DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val current by viewModel.currentClip.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val speed by viewModel.speed.collectAsState()
        val skipSilence by viewModel.skipSilence.collectAsState()
        val playNext by viewModel.playNext.collectAsState()
        val durationDisplay = playerDurationDisplay(duration)

        LightTheme(colors = colors) {
            Column(Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Player"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    viewModel.clips.forEachIndexed { index, clip ->
                        AudioClipRow(index + 1, clip, selected = clip == current) { viewModel.play(clip) }
                    }
                }
                LightText(
                    text = "${formatDuration(position)}  /  ${durationDisplay.totalTime}",
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    monospace = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                Row(Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp())) {
                    PlayerOption("SPEED", "${speed}x", Modifier.weight(1f), viewModel::cycleSpeed)
                    PlayerOption(
                        "SKIP SILENCE",
                        if (skipSilence) "ON" else "OFF",
                        Modifier.weight(1f),
                        viewModel::toggleSkipSilence,
                    )
                    PlayerOption(
                        "PLAY NEXT",
                        if (playNext) "ON" else "OFF",
                        Modifier.weight(1f),
                        viewModel::togglePlayNext,
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            LightIcons.REWIND,
                            viewModel::skipToPrevious,
                            contentDescription = "Previous track",
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_BACKWARD_FIFTEEN,
                            viewModel::skipBack.takeIf { durationDisplay.canSeek },
                        ),
                        LightBarButton.LightIcon(
                            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            viewModel::togglePlayPause,
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_FORWARD_FIFTEEN,
                            viewModel::skipForward.takeIf { durationDisplay.canSeek },
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

@Composable
private fun AudioClipRow(number: Int, clip: AudioClip, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = if (selected) "> $number. ${clip.displayName}" else "$number. ${clip.displayName}",
            variant = LightTextVariant.Copy,
            maxLines = 1,
        )
        LightText(
            text = "${clip.kind.name.uppercase()}  ${clip.formatLabel}",
            variant = LightTextVariant.Fine,
            lighten = true,
        )
    }
}

@Composable
private fun PlayerOption(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Superfine,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LightText(
            text = value,
            variant = LightTextVariant.Fine,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal data class PlayerDurationDisplay(
    val totalTime: String,
    val canSeek: Boolean,
)

internal fun playerDurationDisplay(durationMs: Long): PlayerDurationDisplay =
    if (durationMs > 0L) {
        PlayerDurationDisplay(totalTime = formatDuration(durationMs), canSeek = true)
    } else {
        PlayerDurationDisplay(totalTime = "--:--", canSeek = false)
    }

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
