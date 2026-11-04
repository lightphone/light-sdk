package com.thelightphone.sdk.emulator

import android.media.session.PlaybackState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

enum class MediaControl(
    val action: Long,
    val icon: LightIconConfiguration,
    val send: PlatformMediaSessionObserver.() -> Unit,
) {
    Play(PlaybackState.ACTION_PLAY, LightIcons.PLAY, PlatformMediaSessionObserver::play),
    Pause(PlaybackState.ACTION_PAUSE, LightIcons.PAUSE, PlatformMediaSessionObserver::pause),
    Previous(PlaybackState.ACTION_SKIP_TO_PREVIOUS, LightIcons.REWIND, PlatformMediaSessionObserver::previous),
    Next(PlaybackState.ACTION_SKIP_TO_NEXT, LightIcons.FAST_FORWARD, PlatformMediaSessionObserver::next),
    Rewind(PlaybackState.ACTION_REWIND, LightIcons.SKIP_BACKWARD_FIFTEEN, PlatformMediaSessionObserver::rewind),
    FastForward(PlaybackState.ACTION_FAST_FORWARD, LightIcons.SKIP_FORWARD_FIFTEEN, PlatformMediaSessionObserver::fastForward),
}

/**
 * Now-playing metadata and transport controls, modeled after LightOS's Standby
 * controls: ±15s for speech, previous/next otherwise. Every line and slot keeps
 * its space with or without a [session], so nothing around it moves.
 */
@Composable
fun PlatformMediaSessionControls(
    session: PlatformMediaSession?,
    onControl: (MediaControl) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speech = session?.usage == "speech"
    val back = if (speech) MediaControl.Rewind else MediaControl.Previous
    val playPause = if (session?.isPlaying == true) MediaControl.Pause else MediaControl.Play
    val forward = if (speech) MediaControl.FastForward else MediaControl.Next
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().lightClickable(enabled = session != null, onClick = onOpen)) {
            MetadataLine(session?.artist)
            MetadataLine(session?.title)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(3.5f.gridUnitsAsDp())
        ) {
            ControlSlot(session, back, onControl, Alignment.CenterStart)
            ControlSlot(session, playPause, onControl, Alignment.Center)
            ControlSlot(session, forward, onControl, Alignment.CenterEnd)
        }
    }
}

@Composable
private fun MetadataLine(text: String?) {
    LightText(
        text = text?.ifEmpty { null } ?: " ",
        variant = LightTextVariant.Fine,
        align = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RowScope.ControlSlot(
    session: PlatformMediaSession?,
    control: MediaControl,
    onControl: (MediaControl) -> Unit,
    alignment: Alignment,
) {
    Box(Modifier.weight(1f), contentAlignment = alignment) {
        if (session?.supports(control.action) == true) {
            LightIcon(
                icon = control.icon,
                size = 1.8f,
                modifier = Modifier.lightClickable { onControl(control) }
            )
        }
    }
}

internal fun previewSession(usage: String, playing: Boolean) = PlatformMediaSession(
    packageName = "com.example.tool",
    title = "A very long episode title that does not fit on one line",
    artist = "Some Artist",
    playbackState = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
    actions = MediaControl.entries.fold(0L) { actions, control -> actions or control.action },
    usage = usage,
)

@Preview(widthDp = 1080 / 3, showBackground = true)
@Composable
fun PlatformMediaSessionControlsMusicPreview() {
    LightTheme(colors = LightThemeColors.Dark) {
        Surface { PlatformMediaSessionControls(previewSession("music", playing = true), {}, {}) }
    }
}

@Preview(widthDp = 1080 / 3, showBackground = true)
@Composable
fun PlatformMediaSessionControlsSpeechPreview() {
    LightTheme(colors = LightThemeColors.Light) {
        Surface { PlatformMediaSessionControls(previewSession("speech", playing = false), {}, {}) }
    }
}
