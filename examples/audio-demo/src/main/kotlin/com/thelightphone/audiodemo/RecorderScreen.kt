package com.thelightphone.audiodemo

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioException
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioRecorder
import com.thelightphone.sdk.audio.rememberLightAudio
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RecorderScreenState {
    PermissionRequired,
    Ready,
    Recording,
    Review,
    ConfirmDiscard,
}

class RecorderViewModel(filesDir: File) : LightViewModel<Unit>() {
    val screenState = MutableStateFlow(RecorderScreenState.PermissionRequired)
    val elapsedMs = MutableStateFlow(0L)
    val reviewPositionMs = MutableStateFlow(0L)
    val reviewDurationMs = MutableStateFlow(0L)
    val reviewPlaying = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val library = AudioLibraryRepository(filesDir)
    private var recorder: LightAudioRecorder? = null
    private var player: LightAudioPlayer? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMs = 0L
    private var elapsedJob: Job? = null
    private var playerJob: Job? = null

    fun attachAudio(audio: LightAudio) {
        if (recorder == null) recorder = audio.newRecorder()
        if (player == null) {
            player = audio.newPlayer().also(::observePlayer)
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { refreshPermission() }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        stopRecordingForLifecycle()
    }

    fun startRecording() {
        val recorder = recorder ?: return
        if (screenState.value != RecorderScreenState.Ready) return
        error.value = null
        player?.stop()
        val file = library.newRecordingFile()
        try {
            recorder.start(file)
        } catch (e: LightAudioException) {
            file.delete()
            error.value = e.message ?: "Recording failed"
            return
        }
        recordingFile = file
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        elapsedMs.value = 0L
        screenState.value = RecorderScreenState.Recording
        startElapsedUpdates()
    }

    fun stopRecording() {
        if (screenState.value != RecorderScreenState.Recording) return
        finishRecording(review = true)
    }

    fun toggleReviewPlayback() {
        val player = player ?: return
        if (reviewPlaying.value) {
            player.pause()
        } else {
            if (reviewDurationMs.value > 0L && reviewPositionMs.value >= reviewDurationMs.value) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun save() {
        player?.stop()
        recordingFile = null
        clearReview()
        screenState.value = RecorderScreenState.Ready
    }

    fun requestDiscard() {
        player?.pause()
        screenState.value = RecorderScreenState.ConfirmDiscard
    }

    fun cancelDiscard() {
        screenState.value = RecorderScreenState.Review
    }

    fun confirmDiscard() {
        player?.stop()
        recordingFile?.delete()
        recordingFile = null
        clearReview()
        screenState.value = RecorderScreenState.Ready
    }

    fun shutdown() {
        stopRecordingForLifecycle()
        elapsedJob?.cancel()
        playerJob?.cancel()
        recorder?.release()
        recorder = null
        player?.release()
        player = null
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }

    private suspend fun refreshPermission() {
        val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)
        if (screenState.value != RecorderScreenState.Recording) {
            screenState.value = if (granted) RecorderScreenState.Ready else RecorderScreenState.PermissionRequired
        }
    }

    private fun startElapsedUpdates() {
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (isActive && screenState.value == RecorderScreenState.Recording) {
                elapsedMs.value = SystemClock.elapsedRealtime() - recordingStartedAtMs
                delay(ELAPSED_UPDATE_MS)
            }
        }
    }

    private fun finishRecording(review: Boolean) {
        elapsedJob?.cancel()
        elapsedJob = null
        val duration = recorder?.stop() ?: 0L
        elapsedMs.value = duration
        val validFile = recordingFile?.takeIf { duration > 0L && it.exists() }
        if (validFile == null) {
            recordingFile?.delete()
            recordingFile = null
            screenState.value = RecorderScreenState.Ready
        } else {
            if (review) player?.setSource(validFile)
            screenState.value = if (review) RecorderScreenState.Review else RecorderScreenState.Ready
        }
    }

    private fun stopRecordingForLifecycle() {
        if (screenState.value == RecorderScreenState.Recording) finishRecording(review = false)
        player?.pause()
    }

    private fun observePlayer(value: LightAudioPlayer) {
        playerJob = viewModelScope.launch {
            launch { value.positionMs.collect(reviewPositionMs::emit) }
            launch { value.durationMs.collect(reviewDurationMs::emit) }
            launch { value.isPlaying.collect(reviewPlaying::emit) }
        }
    }

    private fun clearReview() {
        elapsedMs.value = 0L
        reviewPositionMs.value = 0L
        reviewDurationMs.value = 0L
        reviewPlaying.value = false
    }

    companion object {
        private const val ELAPSED_UPDATE_MS = 100L
    }
}

class RecorderScreen(activity: SealedLightActivity) : LightScreen<Unit, RecorderViewModel>(activity) {
    override val viewModelClass = RecorderViewModel::class.java
    override fun createViewModel() = RecorderViewModel(lightContext.filesDir)

    override fun onScreenDestroy() {
        viewModel.shutdown()
    }

    @Composable
    override fun Content() {
        viewModel.attachAudio(rememberLightAudio())
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.screenState.collectAsState()
        val elapsed by viewModel.elapsedMs.collectAsState()
        val reviewPosition by viewModel.reviewPositionMs.collectAsState()
        val reviewDuration by viewModel.reviewDurationMs.collectAsState()
        val reviewPlaying by viewModel.reviewPlaying.collectAsState()
        val error by viewModel.error.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Recorder"),
                )
                when (state) {
                    RecorderScreenState.PermissionRequired -> PermissionRequiredView(
                        onAllow = { permissionLauncher?.launch() },
                        modifier = Modifier.weight(1f),
                    )
                    RecorderScreenState.Ready -> ReadyView(
                        error = error,
                        onRecord = viewModel::startRecording,
                        modifier = Modifier.weight(1f),
                    )
                    RecorderScreenState.Recording -> RecordingView(
                        elapsedMs = elapsed,
                        onStop = viewModel::stopRecording,
                        modifier = Modifier.weight(1f),
                    )
                    RecorderScreenState.Review -> ReviewView(
                        positionMs = reviewPosition,
                        durationMs = reviewDuration,
                        playing = reviewPlaying,
                        onDiscard = viewModel::requestDiscard,
                        onTogglePlayback = viewModel::toggleReviewPlayback,
                        onSave = viewModel::save,
                        modifier = Modifier.weight(1f),
                    )
                    RecorderScreenState.ConfirmDiscard -> ConfirmDiscardView(
                        onCancel = viewModel::cancelDiscard,
                        onDiscard = viewModel::confirmDiscard,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequiredView(
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecorderStateView(
        text = "MICROPHONE ACCESS REQUIRED",
        monospace = false,
        actions = listOf(LightBarButton.Text("ALLOW", onClick = onAllow)),
        modifier = modifier,
    )
}

@Composable
private fun ReadyView(
    error: String?,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecorderStateView(
        text = error?.uppercase() ?: "READY",
        actions = listOf(LightBarButton.LightIcon(LightIcons.MICROPHONE, onRecord)),
        modifier = modifier,
    )
}

@Composable
private fun RecordingView(
    elapsedMs: Long,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecorderStateView(
        text = "RECORDING\n${formatRecorderTime(elapsedMs)}",
        actions = listOf(LightBarButton.LightIcon(LightIcons.STOP, onStop)),
        modifier = modifier,
    )
}

@Composable
private fun ReviewView(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onDiscard: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecorderStateView(
        text = "REVIEW\n${formatRecorderTime(positionMs)} / ${formatRecorderTime(durationMs)}",
        actions = listOf(
            LightBarButton.LightIcon(LightIcons.DELETE, onDiscard),
            LightBarButton.LightIcon(
                if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                onTogglePlayback,
            ),
            LightBarButton.LightIcon(LightIcons.ACCEPT, onSave),
        ),
        modifier = modifier,
    )
}

@Composable
private fun ConfirmDiscardView(
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecorderStateView(
        text = "DISCARD RECORDING?",
        actions = listOf(
            LightBarButton.Text("CANCEL", onClick = onCancel),
            LightBarButton.Text("DISCARD", onClick = onDiscard),
        ),
        modifier = modifier,
    )
}

@Composable
private fun RecorderStateView(
    text: String,
    actions: List<LightBarButton>,
    modifier: Modifier = Modifier,
    monospace: Boolean = true,
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                monospace = monospace,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(actions)
    }
}

internal fun formatRecorderTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
