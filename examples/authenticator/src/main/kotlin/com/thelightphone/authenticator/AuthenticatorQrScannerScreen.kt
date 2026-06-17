package com.thelightphone.authenticator

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightQrCodeScanner
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthenticatorQrScannerScreen(
    sealedActivity: SealedLightActivity,
    private val repository: TotpAccountRepository
) : SimpleLightScreen<Result<StoredAccount>>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan QR Code",
                onScanned = { pendingScan = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(pendingScan) {
            val value = pendingScan ?: return@LaunchedEffect

            val result = try {
                OtpAuthUriParser.parse(value).fold(
                    onSuccess = { account ->
                         withContext(Dispatchers.IO) {
                            Result.success(repository.addAccount(account))
                        }
                    },
                    onFailure = { Result.failure(it) },
                )
            } catch (error: Exception) {
                Result.failure(error)
            }

            goBack(result)
        }
    }
}
