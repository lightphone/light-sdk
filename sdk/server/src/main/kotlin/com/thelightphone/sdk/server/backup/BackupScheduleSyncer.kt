package com.thelightphone.sdk.server.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration

// Keeps the periodic backup job in step with the user's backup preferences. Any change to the
// provider, enabled flag, interval or constraints re-runs [schedule] (which should be idempotent,
// e.g. BackupScheduler.syncSchedule) or, when backups shouldn't run automatically, [unschedule].
class BackupScheduleSyncer(
    private val preferences: ObservableBackupPreferences,
    private val scope: CoroutineScope,
    private val schedule: suspend () -> Unit,
    private val unschedule: suspend () -> Unit,
) {
    private data class Inputs(
        val hasProvider: Boolean,
        val enabled: Boolean,
        val interval: Duration,
        val requiresCharging: Boolean,
        val requiresUnmeteredNetwork: Boolean,
    ) {
        // An infinite interval is the "manually" option.
        val shouldSchedule get() = hasProvider && enabled && interval.isFinite()
    }

    fun start(): Job = scope.launch {
        combine(
            preferences.activeProviderFlow,
            preferences.enabledFlow,
            preferences.backupIntervalFlow,
            preferences.requiresChargingFlow,
            preferences.requiresUnmeteredNetworkFlow,
        ) { provider, enabled, interval, charging, unmetered ->
            Inputs(provider != null, enabled, interval, charging, unmetered)
        }
            .distinctUntilChanged()
            .collectLatest { if (it.shouldSchedule) schedule() else unschedule() }
    }
}
