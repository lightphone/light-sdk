package com.thelightphone.sdk.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

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
 * - **The session usage** spans the service adopting it to the service stopping.
 *   It outlives the handle on purpose: releasing a handle does not stop detached
 *   playback, and a later handle must not silently change the usage of audio
 *   that is still playing.
 *
 * **This type is process-local and the service depends on that.**
 * [LightAudioService] is declared without `android:process` so it shares the
 * tool's process and therefore this instance. Splitting the process gives each
 * side its own copy, and the service's copy would report no open handle — it
 * would stop itself 60s into any pause with a tool still attached. The service
 * asserts its process on startup rather than letting that happen quietly.
 */
@OptIn(UnstableApi::class)
internal class DetachedSessionState {
    private var handleOpen = false
    private var sessionUsage: LightAudioUsage? = null
    private var stagedCaches: List<LightMediaCache> = emptyList()
    private var sessionCaches: List<LightMediaCache>? = null
    private var stagedSourceFactory: LightMediaSourceFactory? = null
    private var sessionHasSourceFactory: Boolean? = null
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

    /** Service side: the live session settled on a usage. */
    @Synchronized
    fun adoptUsage(usage: LightAudioUsage) {
        sessionUsage = usage
    }

    /** Service side: the session is gone, so the next handle starts fresh. */
    @Synchronized
    fun clearSession() {
        sessionUsage = null
        sessionCaches = null
        stagedCaches = emptyList()
        sessionHasSourceFactory = null
        stagedSourceFactory = null
    }

    /** The live session's usage, or `null` when no session is running. */
    @Synchronized
    fun activeUsage(): LightAudioUsage? = sessionUsage

    /**
     * Leaves the caches a starting service should build its player with.
     *
     * Connection hints cannot carry these. The system constructs the service and
     * `onCreate` builds the player there, which happens before any controller
     * connects — so the only channel that arrives in time is this process-local
     * state, written by `newPlayer` before it asks for a controller at all.
     */
    @Synchronized
    fun stageCaches(caches: List<LightMediaCache>) {
        stagedCaches = caches
    }

    /** Service side: the caches left for the player being constructed. */
    @Synchronized
    fun stagedCaches(): List<LightMediaCache> = stagedCaches

    /** Service side: the live session settled on the caches it was built with. */
    @Synchronized
    fun adoptCaches(caches: List<LightMediaCache>) {
        sessionCaches = caches
        stagedCaches = emptyList()
    }

    /** The live session's caches, or `null` when no session is running. */
    @Synchronized
    fun activeCaches(): List<LightMediaCache>? = sessionCaches

    /** Leaves the source factory a starting service should build its player with. */
    @Synchronized
    fun stageSourceFactory(factory: LightMediaSourceFactory?) {
        stagedSourceFactory = factory
    }

    /** Service side: the factory left for the player being constructed. */
    @Synchronized
    fun stagedSourceFactory(): LightMediaSourceFactory? = stagedSourceFactory

    /**
     * Service side: the live session settled on its read path.
     *
     * Only whether there was a factory is kept. The factory itself is dropped,
     * because holding a tool's lambda past construction would outlive the screen
     * that supplied it for no gain: it can never be applied to a second player.
     */
    @Synchronized
    fun adoptSourceFactory(present: Boolean) {
        sessionHasSourceFactory = present
        stagedSourceFactory = null
    }

    /** Whether the live session has a custom read path, or `null` when idle. */
    @Synchronized
    fun activeSourceFactoryPresence(): Boolean? = sessionHasSourceFactory
}

internal val detachedSessionState = DetachedSessionState()

internal fun isDetachedUsageCompatible(
    activeUsage: LightAudioUsage?,
    requestedUsage: LightAudioUsage,
): Boolean = activeUsage == null || activeUsage == requestedUsage

/**
 * Whether a connecting handle's caches match the ones the live session was
 * built with.
 *
 * A live session already has its player, and a player cannot be told to read
 * from somewhere else afterwards. Asking is therefore either redundant or
 * impossible, and the difference is worth a thrown error rather than a player
 * that quietly caches nothing the tool asked for.
 */
internal fun isDetachedCacheCompatible(
    activeCaches: List<LightMediaCache>?,
    requestedCaches: List<LightMediaCache>,
): Boolean = activeCaches == null || activeCaches == requestedCaches

/**
 * Whether a connecting handle may bring a source factory.
 *
 * Caches can be compared, so a reconnect that asks for the same ones is allowed
 * through. Two factories that would build the same pipeline are
 * indistinguishable from two that would not, so there is no equivalent check to
 * make: reconnecting to a live session means accepting the read path it already
 * has, and the only honest way to say that is to require no factory at all.
 */
@OptIn(UnstableApi::class)
internal fun isDetachedSourceFactoryCompatible(
    activeSourceFactoryPresence: Boolean?,
    requestedFactory: LightMediaSourceFactory?,
): Boolean = activeSourceFactoryPresence == null || requestedFactory == null
