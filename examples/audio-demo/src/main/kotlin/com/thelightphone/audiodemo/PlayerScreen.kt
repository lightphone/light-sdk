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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

interface PlayerModeStore {
    suspend fun getPlayback(): LightAudioPlayback
    suspend fun setPlayback(playback: LightAudioPlayback)
}

// In order to get the detached audio player demo working, we need to
// be able to get in and out of the tool while audio keeps playing.
// For that, the detached mode toggle UI state should be persistent.
internal class DataStorePlayerModeStore(
    private val dataStore: DataStore<Preferences>,
) : PlayerModeStore {
    override suspend fun getPlayback(): LightAudioPlayback =
        if (dataStore.data.first()[DETACHED_MODE_KEY] == true) {
            LightAudioPlayback.Detached
        } else {
            LightAudioPlayback.Attached
        }

    override suspend fun setPlayback(playback: LightAudioPlayback) {
        dataStore.edit { preferences ->
            preferences[DETACHED_MODE_KEY] = playback == LightAudioPlayback.Detached
        }
    }

    private companion object {
        val DETACHED_MODE_KEY = booleanPreferencesKey("player_detached")
    }
}

internal class PlayerDemoMode(
    private val store: PlayerModeStore,
    initialPlayback: LightAudioPlayback = LightAudioPlayback.Attached,
) {
    private val _playback = MutableStateFlow(initialPlayback)
    val playback = _playback.asStateFlow()

    suspend fun load(): LightAudioPlayback = store.getPlayback().also {
        _playback.value = it
    }

    suspend fun set(playback: LightAudioPlayback) {
        _playback.value = playback
        store.setPlayback(playback)
    }
}

internal fun nextPlaybackMode(playback: LightAudioPlayback): LightAudioPlayback = when (playback) {
    LightAudioPlayback.Attached -> LightAudioPlayback.Detached
    LightAudioPlayback.Detached -> LightAudioPlayback.Attached
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    filesDir: File,
    private val audio: LightAudio,
    modeStore: PlayerModeStore,
) : LightViewModel<Unit>() {
    private val mode = PlayerDemoMode(modeStore)
    // The playback toggle swaps the player instance, so screen state follows
    // the flow rather than one player's own flows, which would go stale.
    private val playerFlow = MutableStateFlow(audio.newPlayer(playback = mode.playback.value))
    private val currentPlayer: LightAudioPlayer get() = playerFlow.value
    val clips = AudioLibraryRepository(filesDir).list()
    val currentClip: StateFlow<AudioClip?> = playerFlow
        .flatMapLatest { it.currentMediaItemIndex }
        .map(clips::getOrNull)
        .stateIn(viewModelScope, SharingStarted.Eagerly, clips.getOrNull(currentPlayer.currentMediaItemIndex.value))
    val positionMs = playerFlow
        .flatMapLatest { it.positionMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.positionMs.value)
    val durationMs = playerFlow
        .flatMapLatest { it.durationMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.durationMs.value)
    val isPlaying = playerFlow
        .flatMapLatest { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.isPlaying.value)
    val error = playerFlow
        .flatMapLatest { it.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.error.value)
    val playback = mode.playback
    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    private val modeLoadJob = viewModelScope.launch {
        val initialPlayback = mode.playback.value
        val persistedPlayback = mode.load()
        if (persistedPlayback != initialPlayback) {
            replacePlayer(persistedPlayback)
        }
    }

    fun play(clip: AudioClip) {
        val selectedPlayer = currentPlayer
        viewModelScope.launch {
            if (!selectedPlayer.awaitReady() || selectedPlayer !== currentPlayer) return@launch
            val selection = playbackSelectionFor(clip, clips)
            selectedPlayer.speed = speed.value
            selectedPlayer.setMediaQueue(
                selection.queue.map(AudioClip::toLightAudioItem),
                selection.startIndex,
            )
            selectedPlayer.play()
        }
    }

    fun togglePlayPause() {
        if (isPlaying.value) currentPlayer.pause() else currentPlayer.play()
    }

    fun skipBack() = currentPlayer.skipBack()
    fun skipForward() = currentPlayer.skipForward()
    fun skipToPrevious() = currentPlayer.skipToPrevious()
    fun skipToNext() = currentPlayer.skipToNext()

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(speed.value) + 1).mod(SPEEDS.size)]
        _speed.value = next
        currentPlayer.speed = next
    }

    fun toggleDetached() {
        viewModelScope.launch {
            modeLoadJob.join()
            val nextPlayback = nextPlaybackMode(playback.value)
            replacePlayer(nextPlayback)
            mode.set(nextPlayback)
        }
    }

    private fun replacePlayer(nextPlayback: LightAudioPlayback) {
        val oldPlayer = currentPlayer
        oldPlayer.stop()
        oldPlayer.setMediaQueue(emptyList())
        oldPlayer.release()

        val nextPlayer = audio.newPlayer(playback = nextPlayback).apply {
            speed = this@PlayerViewModel.speed.value
        }
        playerFlow.value = nextPlayer
    }

    override fun onCleared() {
        currentPlayer.release()
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

/** The whole library is one playlist. */
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
    override fun createViewModel() = PlayerViewModel(
        filesDir = lightContext.filesDir,
        audio = DefaultLightAudio(sealedActivity),
        modeStore = DataStorePlayerModeStore(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val current by viewModel.currentClip.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val error by viewModel.error.collectAsState()
        val speed by viewModel.speed.collectAsState()
        val playback by viewModel.playback.collectAsState()
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
                error?.let {
                    LightText(
                        text = playbackErrorMessage(it),
                        variant = LightTextVariant.Fine,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    PlayerOption("SPEED", "${speed}x", Modifier.weight(1f), viewModel::cycleSpeed)
                    PlayerOption(
                        "DETACHED",
                        if (playback == LightAudioPlayback.Detached) "ON" else "OFF",
                        Modifier.weight(1f),
                        viewModel::toggleDetached,
                    )
                }
                LightText(
                    text = "MODE SWITCH STOPS PLAYBACK",
                    variant = LightTextVariant.Superfine,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
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

internal fun playbackErrorMessage(error: LightAudioError): String =
    "${error.kind}: ${error.diagnostic}. Select another item to continue."

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
