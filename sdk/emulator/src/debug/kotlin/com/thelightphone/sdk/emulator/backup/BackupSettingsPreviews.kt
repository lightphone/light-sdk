package com.thelightphone.sdk.emulator.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun BackupSettingsPreview() {
    LightTheme(colors = LightThemeColors.Dark) {
        BackupSettings(
            viewModel = rememberPreviewBackupSettingsViewModel(),
            onBackClick = {},
            providerName = { name },
        )
    }
}
