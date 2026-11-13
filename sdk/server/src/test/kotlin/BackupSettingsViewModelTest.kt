import android.app.Activity
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.backup.BackupCapableTool
import com.thelightphone.sdk.server.backup.BackupPackageAllowList
import com.thelightphone.sdk.server.backup.DefaultBackupSettingsViewModel
import com.thelightphone.sdk.server.backup.DefaultObservableBackupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

private class TestViewModel(
    prefs: DefaultObservableBackupPreferences,
    val events: MutableList<String>,
) : DefaultBackupSettingsViewModel(
    prefs,
    BackupPackageAllowList(FakeSharedPreferences()),
    CoroutineScope(Dispatchers.Unconfined),
) {
    override suspend fun getBackupCapablePackages() = emptyList<BackupCapableTool>()
    override fun labelForPackage(packageName: String) = packageName
    override fun kickoffBackupWorker() { events += "kickoff" }
    override suspend fun removeStoredCredentials(provider: RemoteBackupProvider) { events += "remove:${provider.name}" }
    override suspend fun cancelBackupWork() { events += "cancel" }
    override fun launchToolManagerForLogin(activity: Activity) = Unit
}

class BackupSettingsViewModelTest {
    private val prefs = DefaultObservableBackupPreferences(FakeSharedPreferences())
    private val events = mutableListOf<String>()
    private val viewModel = TestViewModel(prefs, events)

    private fun linkedAndEnabled() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        prefs.setEnabled(true)
    }

    @Test
    fun signOut_cancelsWorkThenRemovesCredentialsThenUnlinks() = runBlocking {
        linkedAndEnabled()
        prefs.setLastBackupStatus(BackupStatus.Succeeded(Clock.System.now()))

        viewModel.signOut()

        assertEquals(listOf("cancel", "remove:Custom"), events)
        assertNull(prefs.activeProviderFlow.value)
        assertEquals(false, prefs.enabledFlow.value)
        assertNull(prefs.lastBackupStatusFlow.value)
    }

    @Test
    fun signOut_withNoProvider_doesNothing() = runBlocking {
        viewModel.signOut()

        assertEquals(emptyList(), events)
    }

    @Test
    fun disablingBackups_cancelsWork() = runBlocking {
        linkedAndEnabled()

        viewModel.onEnabledChanged(false)

        assertEquals(listOf("cancel"), events)
        assertEquals(false, prefs.enabledFlow.value)
    }

    @Test
    fun enablingBackups_doesNotCancelWork() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)

        viewModel.onEnabledChanged(true)

        assertEquals(emptyList(), events)
        assertEquals(true, prefs.enabledFlow.value)
    }

    @Test
    fun runBackupNow_kicksOffWhenLinkedAndEnabled() {
        linkedAndEnabled()

        viewModel.runBackupNow()

        assertEquals(listOf("kickoff"), events)
    }

    @Test
    fun runBackupNow_isIgnoredWhenDisabled() = runBlocking {
        prefs.setActiveProvider(RemoteBackupProvider.Custom)
        prefs.setEnabled(false)

        viewModel.runBackupNow()

        assertEquals(emptyList(), events)
    }

    @Test
    fun runBackupNow_isIgnoredWithoutProvider() = runBlocking {
        prefs.setEnabled(true)

        viewModel.runBackupNow()

        assertEquals(emptyList(), events)
    }
}
