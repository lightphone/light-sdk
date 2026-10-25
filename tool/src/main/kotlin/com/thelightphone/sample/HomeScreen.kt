package com.thelightphone.sample

import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightMotionMode
import com.thelightphone.sdk.LightMotionSensors
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

internal enum class ToolMode { Compass, Level }

class HomeScreenViewModel(private val sensors: LightMotionSensors) : LightViewModel<Unit>() {
    private val _mode = MutableStateFlow(ToolMode.Compass)
    internal val mode = _mode
    val reading = sensors.reading

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        startSelectedSensor()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        sensors.stop()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        sensors.stop()
        super.onAppPause()
    }

    override fun onCleared() {
        sensors.close()
        super.onCleared()
    }

    internal fun selectMode(mode: ToolMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        startSelectedSensor()
    }

    private fun startSelectedSensor() {
        sensors.start(
            if (_mode.value == ToolMode.Compass) LightMotionMode.Compass else LightMotionMode.Level
        )
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {
    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel(lightContext.motionSensors)

    @Composable
    override fun Content() {
        val reading by viewModel.reading.collectAsState()
        val mode by viewModel.mode.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LightText(
                    text = "Compass + Level",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.fillMaxWidth(),
                    align = TextAlign.Center,
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    ModeTab(
                        label = "COMPASS",
                        selected = mode == ToolMode.Compass,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.selectMode(ToolMode.Compass) }
                    ModeTab(
                        label = "LEVEL",
                        selected = mode == ToolMode.Level,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.selectMode(ToolMode.Level) }
                }

                if (mode == ToolMode.Compass) {
                    CompassFace(
                        headingDegrees = reading.headingDegrees,
                        modifier = Modifier.size(228.dp),
                    )
                    val direction = reading.headingDegrees?.let(::compassDirection)
                    LightText(
                        text = when {
                            !reading.compassAvailable -> "Compass sensor unavailable"
                            reading.headingDegrees == null -> "Finding north…"
                            reading.compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                                "Move phone in a figure eight to calibrate"
                            else -> "$direction · magnetic north"
                        },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val pitch = reading.pitchDegrees
                    val roll = reading.rollDegrees
                    LevelFace(
                        pitchDegrees = pitch,
                        rollDegrees = roll,
                        modifier = Modifier.size(228.dp),
                    )
                    LightText(
                        text = when {
                            !reading.levelAvailable -> "Level sensor unavailable"
                            pitch == null || roll == null -> "Move phone to begin"
                            isLevel(pitch, roll) -> "LEVEL"
                            else -> "Tilt gently to center the bubble"
                        },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content = LightThemeTokens.colors.content
    Column(
        modifier = modifier.lightClickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Button,
            lighten = !selected,
            align = TextAlign.Center,
        )
        Canvas(modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp).height(2.dp)) {
            if (selected) drawLine(
                content,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                strokeWidth = size.height,
            )
        }
    }
}

@Composable
private fun CompassFace(headingDegrees: Float?, modifier: Modifier = Modifier) {
    val content = LightThemeTokens.colors.content
    val secondary = LightThemeTokens.colors.contentSecondary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val native = drawContext.canvas.nativeCanvas
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = size.minDimension * 0.46f
            native.save()
            native.rotate(-(headingDegrees ?: 0f), centerX, centerY)
            drawCircle(secondary, radius, style = Stroke(width = 2.dp.toPx()))
            for (tick in 0 until 36) {
                val angle = Math.toRadians((tick * 10 - 90).toDouble())
                val major = tick % 9 == 0
                val length = if (major) 18.dp.toPx() else 8.dp.toPx()
                val outerX = centerX + kotlin.math.cos(angle).toFloat() * (radius - 2.dp.toPx())
                val outerY = centerY + kotlin.math.sin(angle).toFloat() * (radius - 2.dp.toPx())
                val innerX = centerX + kotlin.math.cos(angle).toFloat() * (radius - length)
                val innerY = centerY + kotlin.math.sin(angle).toFloat() * (radius - length)
                drawLine(if (major) content else secondary, androidx.compose.ui.geometry.Offset(outerX, outerY), androidx.compose.ui.geometry.Offset(innerX, innerY), if (major) 2.dp.toPx() else 1.dp.toPx())
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = content.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 18.dp.toPx()
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val labels = listOf("N", "E", "S", "W")
            labels.forEachIndexed { index, label ->
                val angle = Math.toRadians((index * 90 - 90).toDouble())
                val distance = radius - 32.dp.toPx()
                native.drawText(label, centerX + kotlin.math.cos(angle).toFloat() * distance, centerY + kotlin.math.sin(angle).toFloat() * distance + paint.textSize / 3f, paint)
            }
            val pointer = android.graphics.Path().apply {
                moveTo(centerX, centerY - radius + 27.dp.toPx())
                lineTo(centerX - 8.dp.toPx(), centerY - radius + 45.dp.toPx())
                lineTo(centerX + 8.dp.toPx(), centerY - radius + 45.dp.toPx())
                close()
            }
            native.drawPath(pointer, paint)
            native.restore()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = headingDegrees?.roundToInt()?.rem(360)?.let { "%03d°".format(it) } ?: "---°",
                variant = LightTextVariant.Subheading,
            )
            LightText(text = "BEARING", variant = LightTextVariant.Superfine, lighten = true)
        }
    }
}

@Composable
private fun LevelFace(pitchDegrees: Float?, rollDegrees: Float?, modifier: Modifier = Modifier) {
    val content = LightThemeTokens.colors.content
    val secondary = LightThemeTokens.colors.contentSecondary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = size.minDimension * 0.46f
            drawCircle(secondary, radius, style = Stroke(width = 2.dp.toPx()))
            drawCircle(secondary, radius * 0.48f, style = Stroke(width = 1.dp.toPx()))
            drawLine(secondary, androidx.compose.ui.geometry.Offset(centerX - radius, centerY), androidx.compose.ui.geometry.Offset(centerX + radius, centerY), 1.dp.toPx())
            drawLine(secondary, androidx.compose.ui.geometry.Offset(centerX, centerY - radius), androidx.compose.ui.geometry.Offset(centerX, centerY + radius), 1.dp.toPx())
            drawCircle(content, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(centerX, centerY), style = Stroke(width = 1.dp.toPx()))
            if (pitchDegrees != null && rollDegrees != null) {
                val maxTilt = 10f
                val usableRadius = radius * 0.48f
                val offsetX = (rollDegrees / maxTilt).coerceIn(-1f, 1f) * usableRadius
                val offsetY = (-pitchDegrees / maxTilt).coerceIn(-1f, 1f) * usableRadius
                drawCircle(content, 9.dp.toPx(), androidx.compose.ui.geometry.Offset(centerX + offsetX, centerY + offsetY))
            }
        }
        if (pitchDegrees != null && rollDegrees != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(text = "X ${"%+.1f".format(rollDegrees)}°   Y ${"%+.1f".format(pitchDegrees)}°", variant = LightTextVariant.Superfine, lighten = true)
            }
        }
    }
}

private fun compassDirection(degrees: Float): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees / 45f).roundToInt() % 8)]

private fun isLevel(pitch: Float, roll: Float): Boolean = kotlin.math.abs(pitch) < 0.5f && kotlin.math.abs(roll) < 0.5f
