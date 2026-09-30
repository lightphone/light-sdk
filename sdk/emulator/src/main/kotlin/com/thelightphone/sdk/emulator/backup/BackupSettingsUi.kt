package com.thelightphone.sdk.emulator.backup

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.backup.BackupAllowedStatus
import com.thelightphone.sdk.server.backup.BackupIntervalOption
import com.thelightphone.sdk.server.backup.BackupSettingsViewModel
import com.thelightphone.sdk.ui.*
import com.thelightphone.sdk.ui.LightBarButton.LightIcon
import com.thelightphone.sdk.ui.R
import kotlinx.coroutines.launch

data class BackupSettingsUiState(
    val backupsEnabled: Boolean,
    val providerName: String?,
    val interval: BackupIntervalOption,
    val onlyOnWifi: Boolean,
    val onlyWhileCharging: Boolean,
    val lastRun: BackupStatus?
)

fun BackupStatus?.summary(): String {
    return when (this) {
        // TODO add time of failure
        is BackupStatus.Failed, BackupStatus.NeedsReauth, BackupStatus.QuotaExceeded -> "Failed"
        is BackupStatus.Succeeded -> "$completedAt"
        null -> "Unknown"
    }
}

@Composable
fun BackupSettings(
    viewModel: BackupSettingsViewModel,
    onBackClick: () -> Unit,
    providerName: RemoteBackupProvider.() -> String,
) {
    val backupPreferences = viewModel.backupPreferences
    val enabled by backupPreferences.enabledFlow.collectAsState()
    val activeProvider by backupPreferences.activeProviderFlow.collectAsState()
    val backupInterval by backupPreferences.backupIntervalFlow.collectAsState()
    val requiresUnmeteredNetwork by backupPreferences.requiresUnmeteredNetworkFlow.collectAsState()
    val requiresCharging by backupPreferences.requiresChargingFlow.collectAsState()
    val lastBackupStatus by backupPreferences.lastBackupStatusFlow.collectAsState()

    val uiState = BackupSettingsUiState(
        backupsEnabled = enabled,
        providerName = activeProvider?.providerName(),
        interval = if (backupInterval.isFinite()) BackupIntervalOption.Nightly else BackupIntervalOption.None,
        onlyOnWifi = requiresUnmeteredNetwork,
        onlyWhileCharging = requiresCharging,
        lastRun = lastBackupStatus
    )

    BackupSettings(
        state = uiState,
        interactions = viewModel,
        onBackClick = onBackClick,
    )
}

enum class BackupSettingsPage {
    Root, SelectingProvider, SelectingFrequency, ViewingLastRun, ViewingSources
}

@Composable
fun BackupSettings(
    state: BackupSettingsUiState,
    interactions: BackupSettingsViewModel,
    onBackClick: () -> Unit,
) {
    var page by remember { mutableStateOf(BackupSettingsPage.Root) }
    val scope = rememberCoroutineScope()
    val title = when (page) {
        BackupSettingsPage.Root -> "Backups"
        BackupSettingsPage.SelectingProvider -> "Provider"
        BackupSettingsPage.SelectingFrequency -> "Run Backups"
        BackupSettingsPage.ViewingLastRun -> "Last Run"
        BackupSettingsPage.ViewingSources -> "Sources"
    }
    val rootScrollState = rememberScrollState()
    Column {
        LightTopBar(
            leftButton = LightIcon(
                icon = LightIcons.BACK,
                onClick = {
                    if (page != BackupSettingsPage.Root) {
                        page = BackupSettingsPage.Root
                    } else {
                        onBackClick()
                    }
                },
            ),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        when (page) {
            BackupSettingsPage.Root -> {
                BackupSettingsRoot(
                    state, interactions, rootScrollState,
                    onFrequencyClick = { page = BackupSettingsPage.SelectingFrequency },
                    onProviderClick = {
                        if (state.providerName == null) {
                            interactions.launchToolManagerForLogin()
                        } else {
                            page = BackupSettingsPage.SelectingProvider
                        }
                    },
                    onLastRunClick = { page = BackupSettingsPage.ViewingLastRun },
                    onSourcesClick = { page = BackupSettingsPage.ViewingSources },
                )
            }

            BackupSettingsPage.SelectingProvider -> {}
            BackupSettingsPage.ViewingLastRun -> {
                val isBackupRunning by interactions.isBackupRunning.collectAsState()
                ViewingLastRun(
                    lastRun = state.lastRun,
                    isRunning = isBackupRunning,
                    canRunBackup = state.backupsEnabled && state.providerName != null,
                    onRunBackupNow = { interactions.runBackupNow() },
                )
            }
            BackupSettingsPage.ViewingSources -> {
                val backupAllowList by interactions.backupAllowList.collectAsState()
                ViewingSources(backupAllowList) { packageName, allowed ->
                    scope.launch { interactions.setBackupAllowedForPackage(packageName, allowed) }
                }
            }
            BackupSettingsPage.SelectingFrequency -> {
                SelectingFrequency {
                    scope.launch { interactions.onFrequencySelected(it) }
                    page = BackupSettingsPage.Root
                }
            }
        }
    }
}

@Composable
fun SelectingFrequency(
    onFrequencySelected: (BackupIntervalOption) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        modifier = Modifier.fillMaxSize()
    ) {
        BackupIntervalOption.entries.forEach {
            LightText(
                it.label(),
                LightTextVariant.Copy,
                modifier = Modifier
                    .padding(horizontal = 1f.gridUnitsAsDp())
                    .lightClickable { onFrequencySelected(it) }
            )
        }
    }
}

@Composable
fun BackupIntervalOption.label(): String = when (this) {
    BackupIntervalOption.None -> "Manually"
    BackupIntervalOption.Nightly -> "Automatically (Nightly)"
}

@Composable
fun ToggleRow(
    text: String,
    toggleState: Boolean,
    rowEnabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 1f.gridUnitsAsDp())
            .lightClickable(enabled = rowEnabled) { onToggle(!toggleState) }
    ) {
        val color =
            LightThemeTokens.colors.content.copy(alpha = if (rowEnabled) 1f else 0.3f)
        Icon(
            tint = color,
            painter = if (toggleState) {
                painterResource(R.drawable.ic_toggle_state_on_white)
            } else painterResource(
                R.drawable.ic_toggle_state_off_white
            ),
            contentDescription = text + if (toggleState) " toggled on." else " toggled off."
        )
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        LightText(text, LightTextVariant.Copy, color = color)
    }
}

@Composable
fun ViewingSources(
    backupAllowList: List<BackupAllowedStatus>,
    onToggleAllowed: (packageName: String, allowed: Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        backupAllowList.forEach { status ->
            ToggleRow(status.label, status.allowed) { allowed ->
                onToggleAllowed(status.packageName, allowed)
            }
        }
    }
}

@Composable
fun ViewingLastRun(
    lastRun: BackupStatus?,
    isRunning: Boolean,
    canRunBackup: Boolean,
    onRunBackupNow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            lastRun.summary(),
            LightTextVariant.Copy,
            modifier = Modifier.padding(1f.gridUnitsAsDp()),
        )

        val runEnabled = !isRunning && canRunBackup
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = if (isRunning) "Backup Running…" else "Run Backup Now",
                    onClick = onRunBackupNow,
                    enabled = runEnabled
                ),
            ),
        )
    }
}

@Composable
fun BackupSettingsRoot(
    state: BackupSettingsUiState,
    interactions: BackupSettingsViewModel,
    scrollState: ScrollState,
    onFrequencyClick: () -> Unit,
    onProviderClick: () -> Unit,
    onLastRunClick: () -> Unit,
    onSourcesClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState) // Makes the Column scrollable
    ) {
        @Composable
        fun HeaderRow(headerText: String, mainText: String, onClick: () -> Unit) {
            Column(
                Modifier
                    .padding(horizontal = 1f.gridUnitsAsDp())
                    .lightClickable { onClick() }) {
                LightText(headerText, LightTextVariant.Detail)
                LightText(mainText, LightTextVariant.Copy)
            }
        }

        val backupsPossible = state.providerName != null
        ToggleRow(
            "Backups Enabled",
            state.backupsEnabled && backupsPossible,
            rowEnabled = backupsPossible
        ) { scope.launch { interactions.onEnabledChanged(it) } }
        HeaderRow("Provider", state.providerName ?: "None") { onProviderClick() }
        HeaderRow("Run Backups", state.interval.label()) { onFrequencyClick() }
        ToggleRow("Only on Wifi", state.onlyOnWifi) { scope.launch { interactions.onlyOnWifiToggled(it) } }
        ToggleRow(
            "Only While Charging",
            state.onlyWhileCharging
        ) { scope.launch { interactions.onlyWhileChargingToggled(it) } }
        HeaderRow("Last Run", state.lastRun.summary()) { onLastRunClick() }
        Row(
            Modifier
                .padding(horizontal = 1f.gridUnitsAsDp())
                .lightClickable { onSourcesClick() }) {
            LightText(
                "Sources",
                LightTextVariant.Copy
            )
        }
    }
}
