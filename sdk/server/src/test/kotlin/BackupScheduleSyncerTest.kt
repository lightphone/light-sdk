import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.backup.BackupScheduleSyncer
import com.thelightphone.sdk.server.backup.ObservableBackupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private class FakePrefs : ObservableBackupPreferences {
    override val activeProviderFlow = MutableStateFlow<RemoteBackupProvider?>(RemoteBackupProvider.Custom)
    override val enabledFlow = MutableStateFlow(true)
    override val backupIntervalFlow = MutableStateFlow(1.days)
    override val requiresChargingFlow = MutableStateFlow(true)
    override val requiresUnmeteredNetworkFlow = MutableStateFlow(true)
    override val lastBackupStatusFlow = MutableStateFlow<BackupStatus?>(null)
}

class BackupScheduleSyncerTest {
    private val prefs = FakePrefs()
    private val calls = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun startSyncer() = scope.launch {
        BackupScheduleSyncer(
            preferences = prefs,
            schedule = { calls.send("schedule") },
            unschedule = { calls.send("unschedule") },
        ).run()
    }

    private suspend fun next() = withTimeout(5.seconds) { calls.receive() }

    // Nothing else should have been called; gives the syncer a moment to (wrongly) emit.
    private suspend fun assertNoMoreCalls() = assertNull(withTimeoutOrNull(300) { calls.receive() })

    @Test
    fun schedulesOnStart_whenLinkedEnabledAndPeriodic() = runBlocking {
        val job = startSyncer()
        assertEquals("schedule", next())
        assertNoMoreCalls()
        job.cancel()
    }

    @Test
    fun unschedulesOnStart_whenNoProvider() = runBlocking {
        prefs.activeProviderFlow.value = null
        val job = startSyncer()
        assertEquals("unschedule", next())
        job.cancel()
    }

    @Test
    fun disablingUnschedules_andReEnablingSchedulesAgain() = runBlocking {
        val job = startSyncer()
        assertEquals("schedule", next())

        prefs.enabledFlow.value = false
        assertEquals("unschedule", next())

        prefs.enabledFlow.value = true
        assertEquals("schedule", next())
        job.cancel()
    }

    @Test
    fun manualInterval_unschedules() = runBlocking {
        val job = startSyncer()
        assertEquals("schedule", next())

        prefs.backupIntervalFlow.value = Duration.INFINITE
        assertEquals("unschedule", next())
        job.cancel()
    }

    @Test
    fun linkingAProvider_schedules() = runBlocking {
        prefs.activeProviderFlow.value = null
        val job = startSyncer()
        assertEquals("unschedule", next())

        prefs.activeProviderFlow.value = RemoteBackupProvider.Custom
        assertEquals("schedule", next())
        job.cancel()
    }

    @Test
    fun changingConstraints_reschedules() = runBlocking {
        val job = startSyncer()
        assertEquals("schedule", next())

        prefs.requiresChargingFlow.value = false
        assertEquals("schedule", next())

        prefs.requiresUnmeteredNetworkFlow.value = false
        assertEquals("schedule", next())
        job.cancel()
    }
}
