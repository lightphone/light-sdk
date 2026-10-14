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
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaCache
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.LightQueueItem
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
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    suspend fun load(): LightAudioPlayback = store.getPlayback().also { _playback.value = it }

    suspend fun set(playback: LightAudioPlayback) {
        _playback.value = playback
        store.setPlayback(playback)
    }
}

internal fun nextPlaybackMode(playback: LightAudioPlayback): LightAudioPlayback =
        when (playback) {
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
    private val playerFlow = MutableStateFlow(audio.newPlayer(playerSpec(mode.playback.value)))
    private val currentPlayer: LightAudioPlayer
        get() = playerFlow.value
    private var expiredDawnId = SampleAudioCatalog.EXPIRED_DAWN_ID
    var clips = AudioLibraryRepository(filesDir).list()
        private set
    val currentItem =
            playerFlow
                    .flatMapLatest { player ->
                        combine(player.queue, player.currentMediaItemIndex, ::queueItemAt)
                    }
                    .stateIn(
                            viewModelScope,
                            SharingStarted.Eagerly,
                            queueItemAt(
                                    currentPlayer.queue.value,
                                    currentPlayer.currentMediaItemIndex.value
                            ),
                    )
    val queue =
            playerFlow
                    .flatMapLatest { it.queue }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.queue.value)
    val currentIndex =
            playerFlow
                    .flatMapLatest { it.currentMediaItemIndex }
                    .stateIn(
                            viewModelScope,
                            SharingStarted.Eagerly,
                            currentPlayer.currentMediaItemIndex.value
                    )
    val availability =
            playerFlow
                    .flatMapLatest { it.availability }
                    .stateIn(
                            viewModelScope,
                            SharingStarted.Eagerly,
                            currentPlayer.availability.value
                    )
    val positionMs =
            playerFlow
                    .flatMapLatest { it.positionMs }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.positionMs.value)
    val durationMs =
            playerFlow
                    .flatMapLatest { it.durationMs }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.durationMs.value)
    val isPlaying =
            playerFlow
                    .flatMapLatest { it.isPlaying }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.isPlaying.value)
    val error =
            playerFlow
                    .flatMapLatest { it.error }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, currentPlayer.error.value)
    val playback = mode.playback
    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    private val modeLoadJob =
            viewModelScope.launch {
                val initialPlayback = mode.playback.value
                val persistedPlayback = mode.load()
                if (persistedPlayback != initialPlayback) {
                    replacePlayer(persistedPlayback)
                } else {
                    seedQueue(currentPlayer)
                }
            }

    private suspend fun seedQueue(player: LightAudioPlayer) {
        if (!player.awaitReady() || player !== currentPlayer || player.queue.value.isNotEmpty())
                return
        player.setMediaQueue(clips.map(AudioClip::toLightAudioItem))
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        expiredDawnId = freshExpiredDawnId()
        clips = clips.withExpiredDawnId(expiredDawnId)
        val freshId = expiredDawnId
        viewModelScope.launch {
            modeLoadJob.join()
            val player = currentPlayer
            if (!player.awaitReady() || player !== currentPlayer) return@launch
            val queue = player.queue.value
            val staleIndex = queue.staleExpiredDawnIndex(freshId)
            val legacyIndex = queue.indexOfFirst { it.id == SampleAudioCatalog.LEGACY_DAWN_ID }
            val brokenItem = clips.first { it.id == freshId }.toLightAudioItem()
            if (staleIndex >= 0) {
                player.replaceMediaItem(staleIndex, brokenItem)
            }
            if (legacyIndex >= 0) {
                if (staleIndex >= 0) player.removeMediaItem(legacyIndex)
                else player.replaceMediaItem(legacyIndex, brokenItem)
            }
        }
    }

    fun togglePlayPause() {
        if (!canCommand()) return
        if (isPlaying.value) currentPlayer.pause() else currentPlayer.play()
    }

    fun skipBack() = seekBy(-SKIP_INTERVAL_MS)
    fun skipForward() = seekBy(SKIP_INTERVAL_MS)
    fun skipToPrevious() {
        if (canCommand()) currentPlayer.skipToPrevious()
    }
    fun skipToNext() {
        if (canCommand()) currentPlayer.skipToNext()
    }

    private fun seekBy(deltaMs: Long) {
        if (!canCommand()) return
        val player = currentPlayer
        player.seekTo(skipPosition(player.positionMs.value, player.durationMs.value, deltaMs))
    }

    fun playFromQueue(index: Int) {
        if (!canCommand()) return
        currentPlayer.seekToDefaultPosition(index)
        currentPlayer.play()
    }

    fun moveUp(index: Int) {
        if (canCommand() && index > 0) currentPlayer.moveMediaItem(index, index - 1)
    }

    fun moveDown(index: Int) {
        if (canCommand() && index < queue.value.lastIndex)
                currentPlayer.moveMediaItem(index, index + 1)
    }

    fun repairFailedSource() {
        if (!canCommand()) return
        val index = repairIndex(error.value, expiredDawnId) ?: return
        val expired = clips.first { it.id == expiredDawnId }
        currentPlayer.replaceMediaItem(index, expired.repairedDawnItem())
        currentPlayer.play()
    }

    fun canRepair(error: LightAudioError): Boolean = repairIndex(error, expiredDawnId) != null

    private fun canCommand(): Boolean =
            currentPlayer.availability.value != LightAudioPlayerAvailability.Refused &&
                    currentPlayer.availability.value != LightAudioPlayerAvailability.Released

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(speed.value) + 1).mod(SPEEDS.size)]
        _speed.value = next
        if (canCommand()) currentPlayer.speed = next
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
        if (canCommand()) {
            oldPlayer.stop()
            oldPlayer.setMediaQueue(emptyList())
        }
        oldPlayer.release()

        val nextPlayer =
                audio.newPlayer(playerSpec(nextPlayback)).apply {
                    speed = this@PlayerViewModel.speed.value
                }
        playerFlow.value = nextPlayer
        viewModelScope.launch { seedQueue(nextPlayer) }
    }

    override fun onCleared() {
        currentPlayer.release()
        super.onCleared()
    }

    companion object {
        private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val SKIP_INTERVAL_MS = 15_000L
    }
}

private fun playerSpec(playback: LightAudioPlayback) =
        LightPlayerSpec(
                playback = playback,
                cache = LightMediaCache("demo-stream", sizeBytes = 64L * 1024 * 1024),
        )

internal fun queueItemAt(queue: List<LightQueueItem>, index: Int): LightQueueItem? =
        queue.getOrNull(index)

internal fun skipPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long =
        (positionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))

internal fun freshExpiredDawnId(): String = "${SampleAudioCatalog.EXPIRED_DAWN_ID}:${UUID.randomUUID()}"

internal fun isExpiredDawnId(id: String?): Boolean = id == SampleAudioCatalog.EXPIRED_DAWN_ID ||
    id?.startsWith("${SampleAudioCatalog.EXPIRED_DAWN_ID}:") == true

internal fun List<AudioClip>.withExpiredDawnId(id: String): List<AudioClip> = map { clip ->
    if (isExpiredDawnId(clip.id)) clip.copy(id = id) else clip
}

internal fun List<LightQueueItem>.staleExpiredDawnIndex(freshId: String): Int =
    indexOfFirst { isExpiredDawnId(it.id) && it.id != freshId }

internal fun repairIndex(error: LightAudioError?, expiredDawnId: String): Int? =
        error
                ?.takeIf {
                    it.kind == LightAudioErrorKind.Source &&
                            it.itemId == expiredDawnId
                }
                ?.itemIndex
                ?.takeIf { it >= 0 }

internal fun AudioClip.repairedDawnItem(): LightAudioItem =
        copy(
                        source = AudioClipSource.UrlSource(SampleAudioCatalog.DAWN_URL),
                        displayName = "(repaired) The Dawn of Everything",
                )
                .toLightAudioItem()

internal fun AudioClip.toLightAudioItem() =
        LightAudioItem(
                source =
                        when (val source = source) {
                            is AudioClipSource.FileSource ->
                                    LightAudioSource.FileSource(source.file)
                            is AudioClipSource.AssetSource ->
                                    LightAudioSource.AssetSource(source.assetPath)
                            is AudioClipSource.UrlSource -> LightAudioSource.UrlSource(source.url)
                        },
                metadata = toLightMediaMetadata(),
                id = id,
        )

private fun AudioClip.toLightMediaMetadata() =
        LightMediaMetadata(
                title = displayName,
                artist = kind.name,
                durationMs = durationMs.takeIf { it > 0L },
        )

class PlayerScreen(private val sealedActivity: SealedLightActivity) :
        LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() =
            PlayerViewModel(
                    filesDir = lightContext.filesDir,
                    audio = DefaultLightAudio(sealedActivity),
                    modeStore = DataStorePlayerModeStore(lightContext.dataStore),
            )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val current by viewModel.currentItem.collectAsState()
        val queue by viewModel.queue.collectAsState()
        val currentIndex by viewModel.currentIndex.collectAsState()
        val availability by viewModel.availability.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val error by viewModel.error.collectAsState()
        val speed by viewModel.speed.collectAsState()
        val playback by viewModel.playback.collectAsState()
        val durationDisplay = playerDurationDisplay(duration)

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                        leftButton =
                                LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Player"),
                )
                LightScrollView(
                        modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    queue.forEachIndexed { index, item ->
                        QueueItemRow(
                                index = index,
                                item = item,
                                selected = index == currentIndex,
                                onSelect = { viewModel.playFromQueue(index) },
                                onUp = { viewModel.moveUp(index) },
                                onDown = { viewModel.moveDown(index) },
                        )
                    }
                }
                LightText(
                        text = current?.metadata?.title ?: "Nothing playing",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                )
                LightText(
                        text = "${formatDuration(position)}  /  ${durationDisplay.totalTime}",
                        variant = LightTextVariant.Fine,
                        align = TextAlign.Center,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                error?.let {
                    LightText(
                            text = playbackErrorMessage(it),
                            variant = LightTextVariant.Fine,
                            align = TextAlign.Center,
                            modifier =
                                    Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                    if (viewModel.canRepair(it)) {
                        LightText(
                                text = "REPLACE EXPIRED URL + PLAY",
                                variant = LightTextVariant.Fine,
                                align = TextAlign.Center,
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .lightClickable(
                                                        onClick = viewModel::repairFailedSource
                                                )
                                                .padding(bottom = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                LightText(
                        text = "CONNECTION: ${availability.name.uppercase()} · STREAM CACHE: 64 MB",
                        variant = LightTextVariant.Superfine,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                )
                Row(
                        Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
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
                        items =
                                listOf(
                                        LightBarButton.LightIcon(
                                                LightIcons.REWIND,
                                                viewModel::skipToPrevious,
                                                contentDescription = "Previous track",
                                        ),
                                        LightBarButton.LightIcon(
                                                LightIcons.SKIP_BACKWARD_FIFTEEN,
                                                viewModel::skipBack.takeIf {
                                                    durationDisplay.canSeek
                                                },
                                        ),
                                        LightBarButton.LightIcon(
                                                if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                                                viewModel::togglePlayPause,
                                        ),
                                        LightBarButton.LightIcon(
                                                LightIcons.SKIP_FORWARD_FIFTEEN,
                                                viewModel::skipForward.takeIf {
                                                    durationDisplay.canSeek
                                                },
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
private fun QueueItemRow(
        index: Int,
        item: LightQueueItem,
        selected: Boolean,
        onSelect: () -> Unit,
        onUp: () -> Unit,
        onDown: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp())) {
        LightText(
                text = "${if (selected) ">" else " "} ${index + 1}. ${item.metadata.title}",
                variant = LightTextVariant.Copy,
                maxLines = 1,
                modifier = Modifier.lightClickable(onClick = onSelect),
        )
        Row(Modifier.fillMaxWidth()) {
            LightText(
                    text = "ID: ${item.id.take(24)}",
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier.weight(1f),
            )
            QueueAction("↑", onUp)
            QueueAction("↓", onDown)
        }
    }
}

@Composable
private fun QueueAction(label: String, onClick: () -> Unit) {
    LightText(
            text = label,
            variant = LightTextVariant.Fine,
            modifier =
                    Modifier.lightClickable(onClick = onClick)
                            .padding(horizontal = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun PlayerOption(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
            modifier.lightClickable(onClick = onClick).padding(vertical = 0.5f.gridUnitsAsDp()),
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
