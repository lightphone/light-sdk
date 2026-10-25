package com.thelightphone.sdk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

/** Sensor data useful for simple offline tools such as a compass or spirit level. */
data class LightMotionReading(
    val headingDegrees: Float? = null,
    val pitchDegrees: Float? = null,
    val rollDegrees: Float? = null,
    val compassAvailable: Boolean = false,
    val levelAvailable: Boolean = false,
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
)

enum class LightMotionMode { Compass, Level }

/**
 * Provides a narrow, lifecycle-controlled view of motion sensors without exposing an Android
 * Context or SensorManager to a tool. No runtime permission is needed for these sensor readings.
 */
class LightMotionSensors internal constructor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val _reading = MutableStateFlow(
        LightMotionReading(
            compassAvailable = rotationVector != null || (accelerometer != null && magnetometer != null),
            levelAvailable = gravity != null || accelerometer != null,
        )
    )
    val reading: StateFlow<LightMotionReading> = _reading.asStateFlow()

    private var activeMode: LightMotionMode? = null
    private var accelerationValues: FloatArray? = null
    private var filteredAcceleration: FloatArray? = null
    private var magneticValues: FloatArray? = null
    private var smoothedHeading: Float? = null
    private var registered = false

    fun start(mode: LightMotionMode) {
        stop()
        activeMode = mode
        smoothedHeading = null
        if (mode == LightMotionMode.Level) filteredAcceleration = null
        _reading.value = when (mode) {
            LightMotionMode.Compass -> _reading.value.copy(headingDegrees = null)
            LightMotionMode.Level -> _reading.value.copy(pitchDegrees = null, rollDegrees = null)
        }
        val sensors = when (mode) {
            LightMotionMode.Compass -> if (rotationVector != null) listOf(rotationVector) else listOfNotNull(accelerometer, magnetometer)
            LightMotionMode.Level -> listOfNotNull(gravity ?: accelerometer)
        }
        sensors.forEach { sensor ->
            registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) || registered
        }
    }

    fun stop() {
        if (registered) manager.unregisterListener(this)
        registered = false
        activeMode = null
    }

    fun close() = stop()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                _reading.value = _reading.value.copy(compassAccuracy = event.accuracy)
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                updateHeading(matrix)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerationValues = event.values.copyOf()
                when (activeMode) {
                    LightMotionMode.Compass -> if (rotationVector == null) updateFallbackHeading()
                    LightMotionMode.Level -> if (gravity == null) {
                        val previous = filteredAcceleration
                        val filtered = FloatArray(3) { axis ->
                            previous?.get(axis)?.let { it + 0.2f * (event.values[axis] - it) }
                                ?: event.values[axis]
                        }
                        filteredAcceleration = filtered
                        updateLevel(filtered)
                    }
                    null -> Unit
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.copyOf()
                _reading.value = _reading.value.copy(compassAccuracy = event.accuracy)
                if (activeMode == LightMotionMode.Compass && rotationVector == null) updateFallbackHeading()
            }
            Sensor.TYPE_GRAVITY -> if (activeMode == LightMotionMode.Level) updateLevel(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            _reading.value = _reading.value.copy(compassAccuracy = accuracy)
        }
    }

    private fun updateFallbackHeading() {
        val acceleration = accelerationValues ?: return
        val magnetic = magneticValues ?: return
        val matrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(matrix, null, acceleration, magnetic)) updateHeading(matrix)
    }

    private fun updateHeading(matrix: FloatArray) {
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        val rawHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
        val previous = smoothedHeading
        val heading = if (previous == null) rawHeading else {
            val delta = ((rawHeading - previous + 540f) % 360f) - 180f
            (previous + delta * 0.22f + 360f) % 360f
        }
        smoothedHeading = heading
        _reading.value = _reading.value.copy(headingDegrees = heading)
    }

    private fun updateLevel(values: FloatArray?) {
        values ?: return
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val pitch = Math.toDegrees(atan2((-x).toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
        val roll = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat()
        _reading.value = _reading.value.copy(pitchDegrees = pitch, rollDegrees = roll)
    }
}
