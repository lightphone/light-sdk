package com.thelightphone.sdk.audio

/**
 * The one detached session a tool process can have.
 *
 * Two facts with deliberately different lifetimes, behind one lock:
 *
 * - **The handle** spans `newPlayer` to `release`. It caps detached players at
 *   one per process, and it is what keeps [LightAudioService] alive — a handle
 *   exists before its `MediaController` connects and after it disconnects, so
 *   counting controllers instead would let the service stop underneath an
 *   arriving reconnect.
 * - **The staged spec** is what the latest `newPlayer` asked for. The service
 *   builds its player from it in `onCreate`, before any controller connects.
 * - **The session spec** spans the service adopting the staged spec to the
 *   service stopping. It outlives the handle on purpose: releasing a handle
 *   does not stop detached playback, and a later handle must not silently
 *   change the usage or cache of audio that is still playing.
 *
 * **This type is process-local and the service depends on that.**
 * [LightAudioService] is declared without `android:process` so it shares the
 * tool's process and therefore this instance. Splitting the process gives each
 * side its own copy, and the service's copy would report no open handle — it
 * would stop itself [IDLE_STOP_MS] into any pause with a tool still attached. The service
 * asserts its process on startup rather than letting that happen quietly.
 */
internal class DetachedSessionState {
    private var handleOpen = false
    private var stagedSpec: LightPlayerSpec = LightPlayerSpec(playback = LightAudioPlayback.Detached)
    private var sessionSpec: LightPlayerSpec? = null
    private var handleChanged: (() -> Unit)? = null

    /** Takes the single detached handle, or fails when one is already out. */
    fun openHandle(): Boolean {
        val listener = synchronized(this) {
            if (handleOpen) return false
            handleOpen = true
            handleChanged
        }
        listener?.invoke()
        return true
    }

    /** Frees the handle. Does not stop playback — the service owns that. */
    fun closeHandle() {
        val listener = synchronized(this) {
            handleOpen = false
            handleChanged
        }
        listener?.invoke()
    }

    /** Service side: re-evaluate liveness whenever handle ownership changes. */
    @Synchronized
    fun setHandleChangedListener(listener: (() -> Unit)?) {
        handleChanged = listener
    }

    /** Whether a tool holds a handle, and so whether the service must stay up. */
    @Synchronized
    fun isHandleOpen(): Boolean = handleOpen

    @Synchronized
    fun adoptSpec(spec: LightPlayerSpec) {
        sessionSpec = spec
    }

    /**
     * Service side: the session is gone, so the next handle starts fresh. The
     * staged spec stays: a handle may have staged it for the next service
     * already, and every `newPlayer` overwrites it anyway.
     */
    @Synchronized
    fun clearSession() {
        sessionSpec = null
    }

    @Synchronized
    fun activeSpec(): LightPlayerSpec? = sessionSpec

    @Synchronized
    fun stageSpec(spec: LightPlayerSpec) {
        stagedSpec = spec
    }

    @Synchronized
    fun stagedSpec(): LightPlayerSpec = stagedSpec
}

internal val detachedSessionState = DetachedSessionState()

internal fun isDetachedSpecCompatible(
    activeSpec: LightPlayerSpec?,
    requestedSpec: LightPlayerSpec,
): Boolean = activeSpec == null || activeSpec == requestedSpec
