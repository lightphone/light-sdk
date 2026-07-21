package com.thelightphone.audiodemo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioVoice
import com.thelightphone.sdk.audio.rememberLightAudio
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow

data class SoundboardNote(
    val label: String,
    val midiNote: Int,
) {
    val frequencyHz: Double = noteFrequency(midiNote)
}

/** A piano black key and the white-key gap (0-based) it is centered over. */
data class BlackKey(
    val note: SoundboardNote,
    val afterWhiteIndex: Int,
)

data class OneShotSample(
    val label: String,
    val assetPath: String,
)

class ToneViewModel : LightViewModel<Unit>() {
    val whiteKeys = PIANO_WHITE_KEYS
    val blackKeys = PIANO_BLACK_KEYS
    val oneShots = ONE_SHOT_SAMPLES
    val lastNote = MutableStateFlow<SoundboardNote?>(null)
    val lastOneShot = MutableStateFlow<OneShotSample?>(null)
    val sampleError = MutableStateFlow<String?>(null)

    // A pool of monophonic voices; rotating across them lets overlapping taps
    // ring together — polyphony composed on the app side, mixed by the platform.
    private val voices = mutableListOf<LightAudioVoice>()
    private var nextVoice = 0
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var oneShotPcm = emptyMap<OneShotSample, ShortArray>()

    fun attachAudio(audio: LightAudio, readAsset: (String) -> ByteArray) {
        if (voices.isNotEmpty()) return
        sampleRate = audio.capabilities.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        repeat(VOICE_COUNT) { voices += audio.newVoice(sampleRate = sampleRate) }
        oneShotPcm = oneShots.mapNotNull { sample ->
            runCatching {
                val wav = decodePcmWav(readAsset(sample.assetPath))
                sample to resampleMonoPcm(wav.samples, wav.sampleRate, sampleRate)
            }.onFailure { sampleError.value = it.message ?: "Failed to decode ${sample.label}" }
                .getOrNull()
        }.toMap()
    }

    fun play(note: SoundboardNote) {
        lastNote.value = note
        lastOneShot.value = null
        nextVoice()?.play(synthesizeSine(sampleRate, note.frequencyHz, TONE_DURATION_MS))
    }

    fun play(sample: OneShotSample) {
        val pcm = oneShotPcm[sample] ?: return
        lastNote.value = null
        lastOneShot.value = sample
        nextVoice()?.play(pcm)
    }

    fun shutdown() {
        voices.forEach(LightAudioVoice::release)
        voices.clear()
        nextVoice = 0
        oneShotPcm = emptyMap()
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }

    private fun nextVoice(): LightAudioVoice? {
        val voice = voices.getOrNull(nextVoice) ?: return null
        nextVoice = (nextVoice + 1) % voices.size
        return voice
    }

    companion object {
        private const val VOICE_COUNT = 6
        private const val TONE_DURATION_MS = 250
        private const val DEFAULT_SAMPLE_RATE = 48_000
    }
}

class ToneScreen(activity: SealedLightActivity) : LightScreen<Unit, ToneViewModel>(activity) {
    override val viewModelClass = ToneViewModel::class.java
    override fun createViewModel() = ToneViewModel()

    override fun onScreenDestroy() {
        viewModel.shutdown()
    }

    @Composable
    override fun Content() {
        viewModel.attachAudio(rememberLightAudio(), lightContext::readAsset)
        val colors by LightThemeController.colors.collectAsState()
        val lastNote by viewModel.lastNote.collectAsState()
        val lastOneShot by viewModel.lastOneShot.collectAsState()
        val sampleError by viewModel.sampleError.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Tone"),
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    Column(Modifier.fillMaxWidth().weight(1f)) {
                        viewModel.oneShots.chunked(SAMPLE_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                row.forEach { sample ->
                                    OneShotPad(
                                        sample = sample,
                                        selected = sample == lastOneShot,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        onClick = { viewModel.play(sample) },
                                    )
                                }
                            }
                        }
                    }
                    Piano(
                        whiteKeys = viewModel.whiteKeys,
                        blackKeys = viewModel.blackKeys,
                        lastNote = lastNote,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                        onPlay = viewModel::play,
                    )
                }
                LightText(
                    text = sampleError?.uppercase()
                        ?: lastOneShot?.let { "SAMPLE  ${it.label}" }
                        ?: lastNote?.let { "${it.label}  ${it.frequencyHz.toInt()} HZ" }
                        ?: "TAP A NOTE OR SAMPLE",
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth().height(2f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun Piano(
    whiteKeys: List<SoundboardNote>,
    blackKeys: List<BlackKey>,
    lastNote: SoundboardNote?,
    modifier: Modifier,
    onPlay: (SoundboardNote) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val whiteWidth = maxWidth / whiteKeys.size
        val blackWidth = whiteWidth * BLACK_KEY_WIDTH_FRACTION
        val blackHeight = maxHeight * BLACK_KEY_HEIGHT_FRACTION
        Row(Modifier.fillMaxSize()) {
            whiteKeys.forEach { key ->
                WhiteKey(
                    selected = key == lastNote,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPlay(key) },
                )
            }
        }
        // Drawn after the white keys so they sit on top and intercept taps in
        // their bounds; the white keys catch every tap outside a black key.
        blackKeys.forEach { black ->
            BlackKey(
                selected = black.note == lastNote,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = whiteWidth * (black.afterWhiteIndex + 1) - blackWidth / 2)
                    .width(blackWidth)
                    .height(blackHeight),
                onClick = { onPlay(black.note) },
            )
        }
    }
}

@Composable
private fun WhiteKey(
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val fill = if (selected) colors.contentSecondary else colors.background
    Box(
        modifier = modifier.padding(horizontal = KEY_GAP)
            .background(fill)
            .border(BorderStroke(1.dp, colors.content))
            .lightClickable(onClick = onClick),
    )
}

@Composable
private fun BlackKey(
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val fill = if (selected) colors.contentSecondary else colors.content
    Box(modifier = modifier.background(fill).lightClickable(onClick = onClick))
}

@Composable
private fun OneShotPad(
    sample: OneShotSample,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.lightClickable(onClick = onClick).padding(0.25f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = if (selected) "> ${sample.label}" else sample.label,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            maxLines = 1,
        )
    }
}

internal fun noteFrequency(midiNote: Int): Double =
    440.0 * 2.0.pow((midiNote - 69) / 12.0)

private val PIANO_WHITE_KEYS = listOf(
    SoundboardNote("C4", 60),
    SoundboardNote("D4", 62),
    SoundboardNote("E4", 64),
    SoundboardNote("F4", 65),
    SoundboardNote("G4", 67),
    SoundboardNote("A4", 69),
    SoundboardNote("B4", 71),
)

private val PIANO_BLACK_KEYS = listOf(
    BlackKey(SoundboardNote("C#4", 61), afterWhiteIndex = 0),
    BlackKey(SoundboardNote("D#4", 63), afterWhiteIndex = 1),
    BlackKey(SoundboardNote("F#4", 66), afterWhiteIndex = 3),
    BlackKey(SoundboardNote("G#4", 68), afterWhiteIndex = 4),
    BlackKey(SoundboardNote("A#4", 70), afterWhiteIndex = 5),
)

private val ONE_SHOT_SAMPLES = listOf(
    OneShotSample("GONG", "audio/gong.wav"),
    OneShotSample("LASER", "audio/laser.wav"),
    OneShotSample("MAGIC", "audio/magic.wav"),
    OneShotSample("POP", "audio/pop.wav"),
)

private const val SAMPLE_COLUMNS = 2
private const val BLACK_KEY_WIDTH_FRACTION = 0.6f
private const val BLACK_KEY_HEIGHT_FRACTION = 0.6f
private val KEY_GAP = 1.dp
