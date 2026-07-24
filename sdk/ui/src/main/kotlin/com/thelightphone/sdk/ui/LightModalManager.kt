package com.thelightphone.sdk.ui

import androidx.compose.runtime.Composable
import com.thelightphone.sdk.ui.LightModalManager.DEFAULT_DURATION
import com.thelightphone.sdk.ui.LightModalManager.show
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A transient overlay drawn on top of the current screen..
 * Implementations own their own appearance
 */
interface LightModal {
    @Composable
    fun Content()

    // called if the modal timed out (not manually dismissed)
    val onExpired: () -> Unit

    /**
     * Signal that this modal was manually dismissed (e.g. user navigated away, button pressed).
     * The manager will clear the modal immediately without invoking [onExpired].
     */
    fun dismiss()

    /**
     * Called by the manager to race this modal's manual dismissal against the timeout.
     * Should suspend until [dismiss] is called.
     */
    suspend fun awaitDismiss()
}

/**
 * Show/dismiss transient modal overlays
 *
 * At most one modal is active at a time, showing a new one immediately replaces the
 * current one. The active modal auto-dismisses after [DEFAULT_DURATION] unless it is
 * replaced first.
 */
object LightModalManager {

    val DEFAULT_DURATION: Duration = 2.seconds

    // Dispatchers.Main.immediate so state/timer changes are ordered on the UI thread even
    // when show() is called from a background thread (e.g. a service callback).
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _activeModal = MutableStateFlow<LightModal?>(null)
    val activeModal: StateFlow<LightModal?> = _activeModal.asStateFlow()

    private var dismissJob: Job? = null

    /** Show [modal] over the UI, replacing any current modal and restarting the dismiss timer. */
    @Synchronized
    fun show(modal: LightModal, duration: Duration = DEFAULT_DURATION) {
        dismissJob?.cancel()
        _activeModal.value = modal
        dismissJob = scope.launch {
            // null return means the timeout elapsed, non-null means modal dismissed itself first.
            val timedOut = withTimeoutOrNull(duration) {
                modal.awaitDismiss()
                false
            } ?: true
            _activeModal.update {
                if (modal == it) {
                    if (timedOut) {
                        modal.onExpired()
                        delay(100)
                    }
                    null
                } else it
            }
        }
    }

    /** Immediately dismiss the active modal, if any. */
    @Synchronized
    fun dismiss() {
        dismissJob?.cancel()
        dismissJob = null
        _activeModal.value = null
    }
}
