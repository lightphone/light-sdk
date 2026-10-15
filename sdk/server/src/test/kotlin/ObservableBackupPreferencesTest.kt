import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.backup.DefaultObservableBackupPreferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class ObservableBackupPreferencesTest {
    private val androidPrefs = FakeSharedPreferences()
    private val prefs = DefaultObservableBackupPreferences(androidPrefs)

    @Test
    fun lastStatus_isIgnoredWhenNoProviderIsLinked() = runBlocking {
        // e.g. a cancelled backup reporting in after the user signed out
        prefs.setLastBackupStatus(BackupStatus.Failed("backup cancelled", Clock.System.now()))

        assertNull(prefs.lastBackupStatusFlow.value)
    }

    @Test
    fun lastStatus_isRecordedWhenProviderIsLinked() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        val status = BackupStatus.Succeeded(Clock.System.now())

        prefs.setLastBackupStatus(status)

        assertEquals(status, prefs.lastBackupStatusFlow.value)
    }

    @Test
    fun unlinkingProvider_clearsLastStatusAndDisables() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        prefs.setEnabled(true)
        prefs.setLastBackupStatus(BackupStatus.Succeeded(Clock.System.now()))

        prefs.setActiveProvider(null)

        assertNull(prefs.lastBackupStatusFlow.value)
        assertEquals(false, prefs.enabledFlow.value)
    }

    @Test
    fun enabledPreference_survivesSignOutAndRelink() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        prefs.setEnabled(true)

        prefs.setActiveProvider(null)
        assertEquals(false, prefs.enabledFlow.value)

        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        assertEquals(true, prefs.enabledFlow.value)
    }
}
