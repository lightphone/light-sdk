package com.thelightphone.sdk.audio

/**
 * Base class for expected audio failures that a tool can report or recover from,
 * such as a busy device, unavailable microphone, or unsupported format.
 *
 * Invalid arguments are programming errors and throw [IllegalArgumentException]
 * instead.
 *
 * @param message caller-readable failure description
 * @param cause underlying platform failure, when available
 */
open class LightAudioException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Thrown by [LightAudioRecorder.start] when recording could not be started.
 *
 * @param message caller-readable failure description
 * @param cause underlying platform failure, when available
 */
class LightAudioRecorderException(message: String, cause: Throwable? = null) :
    LightAudioException(message, cause)

/**
 * Emitted as a failure from [LightAudioCapture.asFlow] when capture could not
 * start.
 *
 * @param message caller-readable failure description
 * @param cause underlying platform failure, when available
 */
class LightAudioCaptureException(message: String, cause: Throwable? = null) :
    LightAudioException(message, cause)
