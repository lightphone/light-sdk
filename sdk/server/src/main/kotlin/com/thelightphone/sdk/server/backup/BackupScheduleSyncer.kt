package com.thelightphone.sdk.server.backup

import android.content.Context
import com.thelightphone.backup.BackupScheduler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration

// Keeps the periodic backup job in step with the user's backup preferences.
class BackupScheduleSyncer(
    private val preferences: ObservableBackupPreferences,
    private val schedule: suspend () -> Unit,
    private val unschedule: suspend () -> Unit,
) {

    constructor(appContext: Context, preferences: DefaultObservableBackupPreferences) : this(
        preferences = preferences,
        schedule = { BackupScheduler.syncSchedule(appContext, preferences) },
        unschedule = { BackupScheduler.cancelPeriodic(appContext) },
    )

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

    // Suspends until cancelled, so launch it in the scope that should own the syncing.
    suspend fun run() {
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
