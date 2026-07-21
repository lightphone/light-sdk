package com.thelightphone.audiodemo

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.CaptureConfig
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioCapture
import com.thelightphone.sdk.audio.MicSource
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
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

enum class CaptureScreenState {
    PermissionRequired,
    Ready,
    Capturing,
}

class CaptureViewModel : LightViewModel<Unit>() {
    val screenState = MutableStateFlow(CaptureScreenState.PermissionRequired)
    val spectrum = MutableStateFlow(FloatArray(SPECTRUM_BANDS))
    val sampleRate = MutableStateFlow(DEFAULT_SAMPLE_RATE)
    val source = MutableStateFlow(MicSource.Unprocessed)
    val error = MutableStateFlow<String?>(null)

    private val analyzer = SpectrumAnalyzer(bandCount = SPECTRUM_BANDS)
    private var audio: LightAudio? = null
    private var capture: LightAudioCapture? = null
    private var captureJob: Job? = null

    fun attachAudio(audio: LightAudio) {
        this.audio = audio
        if (capture != null) return
        val rate = audio.capabilities.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        sampleRate.value = rate
        capture = newCapture()
    }

    /** Selects the microphone source; only valid while idle (recreates the mic). */
    fun selectSource(value: MicSource) {
        if (screenState.value != CaptureScreenState.Ready || value == source.value) return
        source.value = value
        capture = newCapture()
    }

    private fun newCapture(): LightAudioCapture? = audio?.newCapture(
        CaptureConfig(sampleRate = sampleRate.value, bufferFrames = FFT_SIZE, source = source.value),
    )

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { refreshPermission() }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        stopCapture()
    }

    fun startCapture() {
        val capture = capture ?: return
        if (screenState.value != CaptureScreenState.Ready) return
        error.value = null
        analyzer.reset()
        screenState.value = CaptureScreenState.Capturing
        var nextFrameAtNs = 0L
        captureJob = viewModelScope.launch {
            capture.asFlow()
                .conflate()
                .mapNotNull { samples ->
                    val now = System.nanoTime()
                    if (now < nextFrameAtNs) return@mapNotNull null
                    nextFrameAtNs = now + FRAME_INTERVAL_NS
                    analyzer.analyze(samples, sampleRate.value)
                }
                .flowOn(Dispatchers.Default)
                .catch { cause ->
                    error.value = cause.message ?: "Capture failed"
                    spectrum.value = FloatArray(SPECTRUM_BANDS)
                    screenState.value = CaptureScreenState.Ready
                }
                .collect(spectrum::emit)
        }
    }

    fun stopCapture() {
        // Cancelling collection closes the capture flow (awaitClose stops the mic).
        captureJob?.cancel()
        captureJob = null
        spectrum.value = FloatArray(SPECTRUM_BANDS)
        if (screenState.value == CaptureScreenState.Capturing) {
            screenState.value = CaptureScreenState.Ready
        }
    }

    fun shutdown() {
        stopCapture()
        capture = null
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }

    private suspend fun refreshPermission() {
        val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)
        if (screenState.value != CaptureScreenState.Capturing) {
            screenState.value = if (granted) CaptureScreenState.Ready else CaptureScreenState.PermissionRequired
        }
    }

    companion object {
        private const val FFT_SIZE = 1_024
        private const val SPECTRUM_BANDS = 32
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val FRAME_INTERVAL_NS = 50_000_000L
    }
}

class CaptureScreen(activity: SealedLightActivity) : LightScreen<Unit, CaptureViewModel>(activity) {
    override val viewModelClass = CaptureViewModel::class.java
    override fun createViewModel() = CaptureViewModel()

    override fun onScreenDestroy() {
        viewModel.shutdown()
    }

    @Composable
    override fun Content() {
        viewModel.attachAudio(rememberLightAudio())
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.screenState.collectAsState()
        val spectrum by viewModel.spectrum.collectAsState()
        val sampleRate by viewModel.sampleRate.collectAsState()
        val source by viewModel.source.collectAsState()
        val error by viewModel.error.collectAsState()
        val sourceLabel = if (source == MicSource.Unprocessed) "RAW" else "MIC"

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Capture"),
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        CaptureScreenState.PermissionRequired -> LightText(
                            "MICROPHONE ACCESS REQUIRED",
                            LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                        CaptureScreenState.Ready -> SourceSelector(
                            selected = source,
                            onSelect = viewModel::selectSource,
                        )
                        CaptureScreenState.Capturing -> SpectrumBars(spectrum, Modifier.fillMaxSize())
                    }
                }
                LightText(
                    text = error?.uppercase() ?: when (state) {
                        CaptureScreenState.PermissionRequired -> "RAW PCM"
                        CaptureScreenState.Ready -> "READY  ${sampleRate / 1_000} KHZ  $sourceLabel"
                        CaptureScreenState.Capturing -> "LIVE  $sourceLabel  80 HZ - 8 KHZ"
                    },
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LightBottomBar(
                    items = when (state) {
                        CaptureScreenState.PermissionRequired -> listOf(
                            LightBarButton.Text("ALLOW", onClick = { permissionLauncher?.launch() }),
                        )
                        CaptureScreenState.Ready -> listOf(
                            LightBarButton.LightIcon(LightIcons.MICROPHONE, viewModel::startCapture),
                        )
                        CaptureScreenState.Capturing -> listOf(
                            LightBarButton.LightIcon(LightIcons.STOP, viewModel::stopCapture),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceSelector(selected: MicSource, onSelect: (MicSource) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LightText(
            "AUDIO SOURCE",
            LightTextVariant.Superfine,
            lighten = true,
            monospace = true,
        )
        Row(Modifier.padding(top = 0.5f.gridUnitsAsDp())) {
            SourceSegment("UNPROCESSED", selected == MicSource.Unprocessed) { onSelect(MicSource.Unprocessed) }
            SourceSegment("MIC", selected == MicSource.Mic) { onSelect(MicSource.Mic) }
        }
    }
}

@Composable
private fun SourceSegment(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (active) colors.content else colors.background)
            .border(BorderStroke(1.dp, colors.content))
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            label,
            LightTextVariant.Copy,
            monospace = true,
            color = if (active) colors.background else colors.content,
        )
    }
}

@Composable
private fun SpectrumBars(values: FloatArray, modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.content
    Canvas(modifier) {
        val gap = size.width / values.size * 0.2f
        val barWidth = size.width / values.size - gap
        values.forEachIndexed { index, value ->
            val height = size.height * value.coerceIn(0f, 1f)
            drawRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}
