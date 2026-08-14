package com.thelightphone.sdk.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.thelightphone.sdk.LightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

class LightNfcReader internal constructor(
    private val activity: LightActivity,
    private val config: LightNfcReaderConfig,
) {
    fun asFlow(): Flow<LightNfcTap> = callbackFlow {
        val availability = activity.readNfcAvailability()
        if (!availability.isReady) {
            throw LightNfcUnavailableException(availability.unavailableMessage())
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: throw LightNfcUnavailableException(UNSUPPORTED_MESSAGE)

        val session = ReaderModeSession(
            callback = NfcAdapter.ReaderCallback { tag ->
                runCatching { tag.readTap() }
                    .onSuccess { trySend(it) }
                    .onFailure { error ->
                        close(error as? LightNfcException ?: LightNfcReadException(READ_FAILED_MESSAGE, error))
                    }
            },
            flags = config.toReaderFlags(),
            extras = config.toReaderExtras(),
            onEnableFailure = { close(LightNfcUnavailableException(ENABLE_FAILED_MESSAGE, it)) },
            onAdapterDisabled = { close(LightNfcUnavailableException(DISABLED_MESSAGE)) },
        )
        ReaderModes.start(activity, adapter, session)

        awaitClose { ReaderModes.stop(activity, session) }
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.Main.immediate)

    suspend fun awaitTap(): LightNfcTap = asFlow().first()
}

internal class ReaderModeSession(
    val callback: NfcAdapter.ReaderCallback,
    val flags: Int,
    val extras: Bundle?,
    val onEnableFailure: (Throwable) -> Unit,
    val onAdapterDisabled: () -> Unit,
)

internal object ReaderModes {
    private val perActivity = mutableMapOf<LightActivity, ActivityReaderMode>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var adapterWatchContext: Context? = null

    private val adapterWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_ON)
            if (state != NfcAdapter.STATE_OFF && state != NfcAdapter.STATE_TURNING_OFF) return
            perActivity.values.toList().forEach { it.notifyAdapterDisabled() }
        }
    }

    fun start(activity: LightActivity, adapter: NfcAdapter, session: ReaderModeSession) = onMain {
        if (perActivity.isEmpty()) {
            val context = activity.applicationContext
            context.registerReceiver(
                adapterWatch,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            adapterWatchContext = context
        }
        perActivity.getOrPut(activity) { ActivityReaderMode(activity, adapter) }.start(session)
    }

    fun stop(activity: LightActivity, session: ReaderModeSession) = onMain {
        val readerMode = perActivity[activity] ?: return@onMain
        if (readerMode.stop(session)) perActivity.remove(activity)
        if (perActivity.isEmpty()) {
            adapterWatchContext?.unregisterReceiver(adapterWatch)
            adapterWatchContext = null
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

private class ActivityReaderMode(
    private val activity: LightActivity,
    private val adapter: NfcAdapter,
) {
    private val sessions = ArrayDeque<ReaderModeSession>()

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> applyCurrent()
            Lifecycle.Event.ON_PAUSE -> runCatching { adapter.disableReaderMode(activity) }
            else -> Unit
        }
    }

    fun start(session: ReaderModeSession) {
        sessions.addLast(session)
        if (sessions.size == 1) {
            activity.lifecycle.addObserver(observer)
        } else if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            applyCurrent()
        }
    }

    fun notifyAdapterDisabled() {
        sessions.toList().forEach { it.onAdapterDisabled() }
    }

    fun stop(session: ReaderModeSession): Boolean {
        val wasCurrent = sessions.lastOrNull() === session
        sessions.remove(session)
        if (sessions.isEmpty()) {
            activity.lifecycle.removeObserver(observer)
            runCatching { adapter.disableReaderMode(activity) }
            return true
        }
        if (wasCurrent && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            applyCurrent()
        }
        return false
    }

    private fun applyCurrent() {
        val session = sessions.lastOrNull() ?: return
        runCatching {
            adapter.enableReaderMode(activity, session.callback, session.flags, session.extras)
        }.onFailure(session.onEnableFailure)
    }
}
